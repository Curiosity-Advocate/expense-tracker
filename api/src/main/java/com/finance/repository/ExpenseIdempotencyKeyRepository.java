package com.finance.repository;

import com.finance.entity.ExpenseIdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseIdempotencyKeyRepository extends JpaRepository<ExpenseIdempotencyKeyEntity, UUID> {

    // Looks up a non-expired idempotency key for this user.
    // If found, the request is a retry — return the original expense instead of creating a new one.
    Optional<ExpenseIdempotencyKeyEntity> findByIdempotencyKeyAndUserIdAndExpiresAtAfter(
            UUID idempotencyKey,
            UUID userId,
            Instant now);

    void deleteAllByExpiresAtBefore(Instant cutoff);
}