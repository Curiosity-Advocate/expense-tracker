package com.finance.service.impl;

import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.entity.UserEntity;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.repository.UserRepository;
import com.finance.service.AuthService;
import com.finance.config.ClockConfig;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

// The implementation of the AuthService contract.
// This class knows about JPA and BCrypt — infrastructure details.
// The interface (in core) knows about neither.
@Service
public class PostgresAuthService implements AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Clock clock; // injected, not hardcoded

    // Constructor injection — the Spring way. No @Autowired needed when there
    // is exactly one constructor. Spring injects the UserRepository bean automatically.
    public PostgresAuthService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.clock = clock;
    }

    @Override
    public RegisteredUser register(RegisterCommand command) {
        // Check before insert — avoids relying solely on DB unique constraint exceptions,
        // which would give us a less controlled error path.
        if (userRepository.existsByUsernameOrEmail(command.username(), command.email())) {
            throw new UserAlreadyExistsException();
        }

        UserEntity user = new UserEntity();
        user.setUsername(command.username());
        user.setEmail(command.email());
        // BCrypt hash — the plaintext password is never stored. Ever.
        // passwordEncoder.encode() generates a new random salt each time,
        // so two users with the same password produce different hashes.
        user.setPasswordHash(passwordEncoder.encode(command.password()));
        user.setCreatedAt(Instant.now(clock)); // clock-controlled, not system clock

        UserEntity savedUser = userRepository.save(user);

        // Map entity → domain object. The controller receives domain vocabulary,
        // not JPA entities.
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
 
        String token = jwtService.generateToken(user.getId());
 
        return new TokenPair(
                token,
                jwtService.getExpiry(token),
                "Bearer"
        );
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