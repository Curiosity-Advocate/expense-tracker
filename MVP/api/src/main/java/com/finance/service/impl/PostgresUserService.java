package com.finance.service.impl;

import com.finance.domain.UserProfile;
import com.finance.entity.UserEntity;
import com.finance.exception.UserNotFoundException;
import com.finance.repository.UserRepository;
import com.finance.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PostgresUserService implements UserService {

    private final UserRepository userRepository;

    public PostgresUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toProfile)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Override
    @Transactional
    public UserProfile updateDiscoverability(UUID userId, boolean isDiscoverable) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setDiscoverable(isDiscoverable);
        return toProfile(userRepository.save(user));
    }

    private UserProfile toProfile(UserEntity entity) {
        return new UserProfile(
                entity.getId(),
                entity.getUsername(),
                entity.getEmail(),
                entity.isDiscoverable(),
                entity.getCreatedAt());
    }
}
