package com.finance.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense_idempotency_keys")
public class ExpenseIdempotencyKeyEntity {

    @EmbeddedId
    private ExpenseIdempotencyKeyId id;

    @Column(name = "expense_id", nullable = false)
    private UUID expenseId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(86400); // 24 hours
        }
    }

    public ExpenseIdempotencyKeyEntity() {}

    public ExpenseIdempotencyKeyEntity(ExpenseIdempotencyKeyId id, UUID expenseId, LocalDate expenseDate) {
        this.id = id;
        this.expenseId = expenseId;
        this.expenseDate = expenseDate;
    }

    public ExpenseIdempotencyKeyId getId() { return id; }
    public UUID getExpenseId() { return expenseId; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public Instant getExpiresAt() { return expiresAt; }
}
