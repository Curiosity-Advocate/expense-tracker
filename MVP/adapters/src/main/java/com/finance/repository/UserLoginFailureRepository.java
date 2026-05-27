package com.finance.repository;

import com.finance.entity.UserLoginFailureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface UserLoginFailureRepository extends JpaRepository<UserLoginFailureEntity, UUID> {

    // Sliding-window count: how many failures has this user had since `since`.
    // Backed by idx_user_login_failures_user_at.
    long countByUserIdAndAttemptedAtAfter(UUID userId, Instant since);
}
