package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_login_failures")
public class UserLoginFailureEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    public UserLoginFailureEntity() {}

    public UserLoginFailureEntity(UUID userId, Instant attemptedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
