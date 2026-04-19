package com.finance.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // TODO: email verification — set true programmatically in V1.
    // In production this would default to false until the user clicks a verification link.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "is_discoverable", nullable = false)
    private boolean isDiscoverable = false;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    // updatable = false — Hibernate never includes created_at in an UPDATE statement.
    // The DB trigger trg_users_lock_created_at enforces this at the DB layer as a
    // second line of defense — even raw SQL cannot overwrite it.
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // insertable = false, updatable = false — Hibernate never touches updated_at at all.
    // The DB trigger trg_users_set_updated_at owns this field entirely.
    // This ensures updated_at is accurate even if someone runs SQL directly against the DB,
    // bypassing the application layer.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @Generated(GenerationTime.ALWAYS)
    private Instant updatedAt;

    // @PrePersist fires automatically before JPA executes the INSERT.
    // UUID and createdAt are set here — application-generated, not DB-generated.
    // updated_at is intentionally absent — the DB DEFAULT NOW() on the column handles
    // the initial value, and the trigger handles every subsequent update.
    @PrePersist
    private void onCreate() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.emailVerified = true; // V1: programmatic verification, see TODO above
    }

    // No @PreUpdate — the DB trigger owns updated_at. Nothing for Java to do here.

    public UUID getId()                       { return id; }
    public String getUsername()               { return username; }
    public void setUsername(String username)  { this.username = username; }
    public String getEmail()                  { return email; }
    public void setEmail(String email)        { this.email = email; }
    public String getPasswordHash()           { return passwordHash; }
    public void setPasswordHash(String hash)  { this.passwordHash = hash; }
    public Instant getCreatedAt()             { return createdAt; }
    public int getFailedLoginCount()          { return failedLoginCount; }
    public void setFailedLoginCount(int n)    { this.failedLoginCount = n; }
    public Instant getLockedUntil()           { return lockedUntil; }
    public void setLockedUntil(Instant t)     { this.lockedUntil = t; }
}