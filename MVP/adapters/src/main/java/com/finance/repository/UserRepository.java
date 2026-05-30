package com.finance.repository;

import com.finance.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsernameOrEmail(String username, String email);

    // Used by AccessGrantService.create() to resolve a granteeUsername to a
    // user that has opted into delegation by setting is_discoverable = TRUE.
    Optional<UserEntity> findByUsernameAndIsDiscoverableTrue(String username);
}
