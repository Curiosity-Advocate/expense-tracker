package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ExpenseIdempotencyKeyId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    public ExpenseIdempotencyKeyId() {}

    public ExpenseIdempotencyKeyId(UUID userId, String idempotencyKey) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpenseIdempotencyKeyId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() { return Objects.hash(userId, idempotencyKey); }
}
