package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.entity.RevokedTokenEntity;
import com.finance.entity.UserEntity;
import com.finance.entity.UserLoginFailureEntity;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.repository.RevokedTokenRepository;
import com.finance.repository.UserLoginFailureRepository;
import com.finance.repository.UserRepository;
import com.finance.security.JwtService;
import com.finance.security.RoleElevationService;
import com.finance.service.AuthService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class PostgresAuthService implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int FAILURE_WINDOW_MINUTES = 10;
    private static final int LOCKOUT_MINUTES     = 15;

    private final UserRepository              userRepository;
    private final UserLoginFailureRepository  loginFailureRepository;
    private final RevokedTokenRepository      revokedTokenRepository;
    private final BCryptPasswordEncoder       passwordEncoder;
    private final JwtService                  jwtService;
    private final Clock                       clock;
    private final RoleElevationService        roleElevationService;

    // Hash compared against when a username doesn't exist. Generated once at
    // startup with the same encoder, so a non-existent-user login takes the
    // same ~100ms as a wrong-password login — prevents username enumeration
    // via response-time analysis.
    private final String                      timingDefenceHash;

    public PostgresAuthService(UserRepository userRepository,
                               UserLoginFailureRepository loginFailureRepository,
                               RevokedTokenRepository revokedTokenRepository,
                               BCryptPasswordEncoder passwordEncoder,
                               JwtService jwtService,
                               Clock clock,
                               RoleElevationService roleElevationService) {
        this.userRepository        = userRepository;
        this.loginFailureRepository = loginFailureRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.passwordEncoder       = passwordEncoder;
        this.jwtService            = jwtService;
        this.clock                 = clock;
        this.roleElevationService  = roleElevationService;
        this.timingDefenceHash     = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public RegisteredUser register(RegisterCommand command) {
        // Hash password before elevating — pure CPU work, no DB needed.
        // Keeps the elevated SQL window as small as possible.
        String passwordHash = passwordEncoder.encode(command.password());

        // Elevate: queries on users table need to bypass user_isolation RLS
        // because no UserPrincipal exists during registration.
        roleElevationService.elevateToSetupRole();

        if (userRepository.existsByUsernameOrEmail(command.username(), command.email())) {
            throw new UserAlreadyExistsException();
        }

        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setEmail(command.email());
        user.setPasswordHash(passwordHash);

        UserEntity saved = userRepository.save(user);
        return new RegisteredUser(saved.getId(), saved.getUsername(), saved.getEmail(), saved.getCreatedAt());
    }

    @Override
    @Transactional
    public TokenPair login(LoginCommand command) {
        // Login's first DB call is findByUsername. Elevate immediately because
        // user_isolation RLS would otherwise hide the user from the lookup.
        roleElevationService.elevateToSetupRole();

        Optional<UserEntity> userOpt = userRepository.findByUsername(command.username());

        if (userOpt.isEmpty()) {
            // Dummy BCrypt comparison so response time matches the wrong-password
            // path. Result is discarded — we always throw InvalidCredentials.
            passwordEncoder.matches(command.password(), timingDefenceHash);
            throw new InvalidCredentialsException();
        }

        UserEntity user = userOpt.get();

        clearExpiredLockout(user);

        if (isLocked(user)) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            recordFailedAttempt(user);
            throw new InvalidCredentialsException();
        }

        resetFailedAttempts(user);

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return new TokenPair(token, jwtService.getExpiry(token), "Bearer");
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        // Defensive — invalid tokens (malformed, tampered, expired) are already
        // useless; treat logout as a no-op rather than throwing. Keeps logout
        // idempotent and avoids leaking token-validity signal to probing attackers.
        if (!jwtService.isValid(rawToken)) {
            return;
        }

        String  jti      = jwtService.getJti(rawToken);
        Instant expiresAt = jwtService.getExpiry(rawToken);

        // Idempotent — second logout call on same token does nothing.
        if (revokedTokenRepository.existsByTokenJti(UUID.fromString(jti))) {
            return;
        }

        RevokedTokenEntity revoked = new RevokedTokenEntity();
        revoked.setTokenJti(UUID.fromString(jti));
        revoked.setUserId(jwtService.getUserId(rawToken));
        revoked.setRevokedAt(Instant.now(clock));
        revoked.setExpiresAt(expiresAt);
        revokedTokenRepository.save(revoked);
    }

    private boolean isLocked(UserEntity user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now(clock));
    }

    // Clear an elapsed lockout so this attempt isn't rejected on a stale value.
    // No counter to reset — failure rows naturally age out of the sliding window
    // because LOCKOUT_MINUTES (15) > FAILURE_WINDOW_MINUTES (10).
    private void clearExpiredLockout(UserEntity user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isBefore(Instant.now(clock))) {
            user.setLockedUntil(null);
        }
    }

    // Sliding-window lockout: record the attempt, then count how many failures
    // this user has had in the last FAILURE_WINDOW_MINUTES. Lock if at threshold.
    // Failure rows are kept (not deleted on success) so they age out by time, not
    // by login state — protecting against the "type wrong, log in once, type
    // wrong forever" bypass that a counter-reset model would allow.
    private void recordFailedAttempt(UserEntity user) {
        Instant now = Instant.now(clock);
        loginFailureRepository.save(new UserLoginFailureEntity(user.getId(), now));

        Instant windowStart = now.minus(FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        long recentFailures = loginFailureRepository.countByUserIdAndAttemptedAtAfter(
                user.getId(), windowStart);

        if (recentFailures >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
            userRepository.save(user);
        }
    }

    // Successful login clears any active lockout. Failure rows are retained
    // for forensic value; CleanupJob purges them after 30 days.
    private void resetFailedAttempts(UserEntity user) {
        if (user.getLockedUntil() != null) {
            user.setLockedUntil(null);
            userRepository.save(user);
        }
    }
}
