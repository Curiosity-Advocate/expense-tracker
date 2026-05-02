package com.finance.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categories")
public class CategoryEntity {

    @Id
    private UUID id;

    // NULL for system categories — do not mark as nullable = false
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        this.id = UUID.randomUUID();
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId()                         { return id; }
    public UUID getUserId()                     { return userId; }
    public void setUserId(UUID userId)          { this.userId = userId; }
    public String getName()                     { return name; }
    public void setName(String name)            { this.name = name; }
    public String getDescription()              { return description; }
    public void setDescription(String desc)     { this.description = desc; }
    public boolean isSystem()                   { return isSystem; }
    public void setSystem(boolean isSystem)     { this.isSystem = isSystem; }
    public Instant getCreatedAt()               { return createdAt; }
}