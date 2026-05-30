package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_grants")
public class AccessGrantEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "grantor_id", nullable = false, updatable = false)
    private UUID grantorId;

    @Column(name = "grantee_id", nullable = false, updatable = false)
    private UUID granteeId;

    @Column(name = "access_level", nullable = false, updatable = false)
    private String accessLevel;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // Audit columns are owned by DB defaults + triggers (V1, V23). Java never
    // writes them — insertable = false, updatable = false. See data-model.md
    // §Cross-cutting conventions.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", insertable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "modified_by", insertable = false, updatable = false)
    private UUID modifiedBy;

    public UUID getId()             { return id; }
    public UUID getGrantorId()      { return grantorId; }
    public UUID getGranteeId()      { return granteeId; }
    public String getAccessLevel()  { return accessLevel; }
    public Instant getExpiresAt()   { return expiresAt; }
    public Instant getRevokedAt()   { return revokedAt; }

    public void setGrantorId(UUID v)     { this.grantorId = v; }
    public void setGranteeId(UUID v)     { this.granteeId = v; }
    public void setAccessLevel(String v) { this.accessLevel = v; }
    public void setExpiresAt(Instant v)  { this.expiresAt = v; }
    public void setRevokedAt(Instant v)  { this.revokedAt = v; }
}
