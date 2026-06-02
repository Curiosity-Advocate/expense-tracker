package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// SHA-256 hash + grant + grantee + expiry. No audit columns (security
// primitive — same exclusion as refresh_tokens). All fields except
// created_at are populated at INSERT and never updated.
@Entity
@Table(name = "sudo_tokens")
public class SudoTokenEntity {

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "grant_id", nullable = false, updatable = false)
    private UUID grantId;

    @Column(name = "grantee_id", nullable = false, updatable = false)
    private UUID granteeId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public String getTokenHash() { return tokenHash; }
    public UUID getGrantId()     { return grantId; }
    public UUID getGranteeId()   { return granteeId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTokenHash(String v)  { this.tokenHash = v; }
    public void setGrantId(UUID v)      { this.grantId = v; }
    public void setGranteeId(UUID v)    { this.granteeId = v; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
