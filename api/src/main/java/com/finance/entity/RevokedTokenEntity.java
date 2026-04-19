package com.finance.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "revoked_tokens")
public class RevokedTokenEntity {

    @Id
    private UUID id;

    // user_id — kept for audit queries ("show me all tokens revoked by user X")
    // Not used for token validation — jti lookup is sufficient for that.
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // The JWT jti claim — what the gateway filter looks up on every request.
    @Column(name = "token_jti", nullable = false, unique = true)
    private String tokenJti;

    // No DEFAULT in DB — application sets this explicitly.
    // Preserves the audit relationship: revoked_at vs expires_at tells you
    // whether this was an early logout or a delayed async revocation.
    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    // Needed for the nightly cleanup job — rows past expires_at are dead weight.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    private void onCreate() {
        this.id = UUID.randomUUID();
    }

    public UUID getId()                          { return id; }
    public UUID getUserId()                      { return userId; }
    public void setUserId(UUID userId)           { this.userId = userId; }
    public String getTokenJti()                  { return tokenJti; }
    public void setTokenJti(String jti)          { this.tokenJti = jti; }
    public Instant getRevokedAt()                { return revokedAt; }
    public void setRevokedAt(Instant revokedAt)  { this.revokedAt = revokedAt; }
    public Instant getExpiresAt()                { return expiresAt; }
    public void setExpiresAt(Instant expiresAt)  { this.expiresAt = expiresAt; }
}