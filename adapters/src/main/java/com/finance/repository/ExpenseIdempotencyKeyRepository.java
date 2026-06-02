package com.finance.repository;

import com.finance.entity.ExpenseIdempotencyKeyEntity;
import com.finance.entity.ExpenseIdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ExpenseIdempotencyKeyRepository extends JpaRepository<ExpenseIdempotencyKeyEntity, ExpenseIdempotencyKeyId> {

    // Used by the nightly worker to clean up expired keys.
    @Modifying
    @Query("DELETE FROM ExpenseIdempotencyKeyEntity k WHERE k.expiresAt < :now")
    void deleteAllExpiredBefore(@Param("now") Instant now);
}
