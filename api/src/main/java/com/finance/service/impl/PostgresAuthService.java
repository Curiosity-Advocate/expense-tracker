package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.entity.UserEntity;
import com.finance.entity.RevokedTokenEntity;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.repository.RevokedTokenRepository;
import com.finance.repository.UserRepository;
import com.finance.security.JwtService;
import com.finance.service.AuthService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// The implementation of the AuthService contract.
// This class knows about JPA and BCrypt — infrastructure details.
// The interface (in core) knows about neither.
@Service
public class PostgresAuthService implements AuthService {

    // Brute force lockout constants — externalise to config if policy needs to change.
    private static final int    MAX_FAILED_ATTEMPTS  = 5;
    private static final int    LOCKOUT_MINUTES      = 15;

    private final UserRepository        userRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService            jwtService;
    private final Clock                 clock;

    public PostgresAuthService(UserRepository userRepository,
                                RevokedTokenRepository revokedTokenRepository,
                                JwtService jwtService,
                                Clock clock) {
        this.userRepository       = userRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.passwordEncoder      = new BCryptPasswordEncoder();
        this.jwtService           = jwtService;
        this.clock                = clock;
    }

    @Override
    public RegisteredUser register(RegisterCommand command) {
        if (userRepository.existsByUsernameOrEmail(command.username(), command.email())) {
            throw new UserAlreadyExistsException();
        }

        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setEmail(command.email());
        user.setPasswordHash(passwordEncoder.encode(command.password()));
        UserEntity savedUser = userRepository.save(user);
        return new RegisteredUser(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public TokenPair login(LoginCommand command) {
        // Always look up by username first.
        // If not found, throw the same exception as wrong password —
        // the caller cannot distinguish which case occurred.
        UserEntity user = userRepository.findByUsername(command.username())
                .orElseThrow(InvalidCredentialsException::new);

        // Check lockout before attempting password verification.
        // This prevents timing attacks where BCrypt comparison time
        // reveals that the username exists.
        if (isLocked(user)) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            handleFailedAttempt(user);
            throw new InvalidCredentialsException();
        }

        // Successful login — reset the failed attempt counter.
        resetFailedAttempts(user);

        String token = jwtService.generateToken(user.getId(), user.getUsername());

        return new TokenPair(
                token,
                jwtService.getExpiry(token),
                "Bearer"
        );
    }

    @Override
    @Transactional
    public void logout(String token) {
        // Parse the token to extract jti and expiry.
        // If the token is already expired or malformed, JwtService throws JwtException —
        // the gateway filter will have already rejected those before reaching here,
        // but we let the exception propagate if somehow it slips through.
        String  jti      = jwtService.getJti(token);
        Instant expiresAt = jwtService.getExpiry(token);

        // Idempotent — if the token is already revoked, do nothing.
        // This handles the case where the client calls logout twice (e.g. retry on network failure).
        if (revokedTokenRepository.existsByTokenJti(jti)) {
            return;
        }

        RevokedTokenEntity revoked = new RevokedTokenEntity();
        revoked.setUserId(jwtService.getUserId(token));
        revoked.setTokenJti(jti);
        revoked.setRevokedAt(Instant.now(clock));
        revoked.setExpiresAt(expiresAt);

        revokedTokenRepository.save(revoked);
    }

    private boolean isLocked(UserEntity user) {
        return user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(Instant.now(clock));
    }

    private void handleFailedAttempt(UserEntity user) {
        int attempts = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now(clock).plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
        }

        userRepository.save(user);
    }

    private void resetFailedAttempts(UserEntity user) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }
}