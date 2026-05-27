package com.finance.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true; // set true programmatically in v1.0

    @Column(name = "is_discoverable", nullable = false)
    private boolean isDiscoverable = false;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId()                  { return id; }
    public String getUsername()          { return username; }
    public String getEmail()             { return email; }
    public String getPasswordHash()      { return passwordHash; }
    public boolean isDiscoverable()      { return isDiscoverable; }
    public boolean isActive()            { return isActive; }
    public Instant getLockedUntil()      { return lockedUntil; }
    public Instant getCreatedAt()        { return createdAt; }

    public void setUsername(String v)        { this.username = v; }
    public void setEmail(String v)           { this.email = v; }
    public void setPasswordHash(String v)    { this.passwordHash = v; }
    public void setDiscoverable(boolean v)   { this.isDiscoverable = v; }
    public void setLockedUntil(Instant v)    { this.lockedUntil = v; }
}
