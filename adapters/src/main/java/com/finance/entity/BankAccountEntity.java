package com.finance.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
public class BankAccountEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId()             { return id; }
    public UUID getUserId()         { return userId; }
    public String getName()         { return name; }
    public String getAccountType()  { return accountType; }
    public boolean isSystem()       { return isSystem; }

    public void setUserId(UUID v)       { this.userId = v; }
    public void setName(String v)       { this.name = v; }
    public void setAccountType(String v) { this.accountType = v; }
    public void setSystem(boolean v)    { this.isSystem = v; }
}
