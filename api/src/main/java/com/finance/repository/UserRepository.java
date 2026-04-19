package com.finance.repository;

import com.finance.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// We check both username and email in one query for the 409 response.
// Importantly we do NOT expose which one already exists — that is a user enumeration
// vulnerability. The caller only learns "something already exists", nothing more.
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    boolean existsByUsernameOrEmail(String username, String email);
    Optional<UserEntity> findByUsername(String username); // needed for login in step 5
}