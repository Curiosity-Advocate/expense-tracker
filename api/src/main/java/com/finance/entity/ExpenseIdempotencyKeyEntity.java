package com.finance.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense_idempotency_keys")
public class ExpenseIdempotencyKeyEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expense_id", nullable = false)
    private UUID expenseId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.expiresAt == null) {
            this.expiresAt = Instant.now().plusSeconds(86400); // 24 hours
        }
    }

    public UUID getIdempotencyKey()                         { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey)      { this.idempotencyKey = idempotencyKey; }
    public UUID getUserId()                                 { return userId; }
    public void setUserId(UUID userId)                      { this.userId = userId; }
    public UUID getExpenseId()                              { return expenseId; }
    public void setExpenseId(UUID expenseId)                { this.expenseId = expenseId; }
    public LocalDate getExpenseDate()                       { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate)       { this.expenseDate = expenseDate; }
    public Instant getCreatedAt()                           { return createdAt; }
    public Instant getExpiresAt()                           { return expiresAt; }
}