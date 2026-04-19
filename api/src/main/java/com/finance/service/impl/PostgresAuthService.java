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
}