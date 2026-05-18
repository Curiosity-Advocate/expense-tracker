package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.entity.RevokedTokenEntity;
import com.finance.entity.UserEntity;
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
import java.util.UUID;

@Service
public class PostgresAuthService implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES     = 15;

    private final UserRepository         userRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final BCryptPasswordEncoder  passwordEncoder;
    private final JwtService             jwtService;
    private final Clock                  clock;

    public PostgresAuthService(UserRepository userRepository,
                               RevokedTokenRepository revokedTokenRepository,
                               BCryptPasswordEncoder passwordEncoder,
                               JwtService jwtService,
                               Clock clock) {
        this.userRepository        = userRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.passwordEncoder       = passwordEncoder;
        this.jwtService            = jwtService;
        this.clock                 = clock;
    }

    @Override
    @Transactional
    public RegisteredUser register(RegisterCommand command) {
        if (userRepository.existsByUsernameOrEmail(command.username(), command.email())) {
            throw new UserAlreadyExistsException();
        }

        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setEmail(command.email());
        user.setPasswordHash(passwordEncoder.encode(command.password()));

        UserEntity saved = userRepository.save(user);
        return new RegisteredUser(saved.getId(), saved.getUsername(), saved.getEmail(), saved.getCreatedAt());
    }

    @Override
    @Transactional
    public TokenPair login(LoginCommand command) {
        UserEntity user = userRepository.findByUsername(command.username())
                .orElseThrow(InvalidCredentialsException::new);

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

    private void recordFailedAttempt(UserEntity user) {
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
