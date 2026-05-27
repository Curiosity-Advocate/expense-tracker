package com.finance.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "revoked_tokens")
public class RevokedTokenEntity {

    @Id
    @Column(name = "token_jti")
    private UUID tokenJti;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public UUID getTokenJti()    { return tokenJti; }
    public UUID getUserId()      { return userId; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setTokenJti(UUID v)   { this.tokenJti = v; }
    public void setUserId(UUID v)     { this.userId = v; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
