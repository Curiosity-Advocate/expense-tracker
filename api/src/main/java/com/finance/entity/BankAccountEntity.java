package com.finance.entity;

import jakarta.persistence.*;

import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
public class BankAccountEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "institution_name", nullable = false, length = 100)
    private String institutionName;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(name = "account_number_masked", length = 20)
    private String accountNumberMasked;

    // Basiq's internal account ID — null for system accounts
    @Column(name = "basiq_account_id", length = 255)
    private String basiqAccountId;

    // Reference key to retrieve OAuth token from Bitwarden at runtime.
    // The token value itself never enters this database.
    @Column(name = "bitwarden_secret_id", length = 255)
    private String bitwardenSecretId;

    @Column(name = "is_system_account", nullable = false)
    private boolean isSystemAccount = false;

    // CASH or CRYPTO — null for real bank accounts
    @Column(name = "system_account_type", length = 20)
    private String systemAccountType;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @CurrentTimestamp(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    @PrePersist
    private void onCreate() {
        this.id = UUID.randomUUID();
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId()                                     { return id; }
    public UUID getUserId()                                 { return userId; }
    public void setUserId(UUID userId)                      { this.userId = userId; }
    public String getInstitutionName()                      { return institutionName; }
    public void setInstitutionName(String institutionName)  { this.institutionName = institutionName; }
    public String getAccountName()                          { return accountName; }
    public void setAccountName(String accountName)          { this.accountName = accountName; }
    public String getAccountNumberMasked()                  { return accountNumberMasked; }
    public void setAccountNumberMasked(String masked)       { this.accountNumberMasked = masked; }
    public String getBasiqAccountId()                       { return basiqAccountId; }
    public void setBasiqAccountId(String basiqAccountId)    { this.basiqAccountId = basiqAccountId; }
    public String getBitwardenSecretId()                    { return bitwardenSecretId; }
    public void setBitwardenSecretId(String id)             { this.bitwardenSecretId = id; }
    public boolean isSystemAccount()                        { return isSystemAccount; }
    public void setSystemAccount(boolean systemAccount)     { this.isSystemAccount = systemAccount; }
    public String getSystemAccountType()                    { return systemAccountType; }
    public void setSystemAccountType(String type)           { this.systemAccountType = type; }
    public String getStatus()                               { return status; }
    public void setStatus(String status)                    { this.status = status; }
    public Instant getLastSyncedAt()                        { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt)       { this.lastSyncedAt = lastSyncedAt; }
    public Instant getCreatedAt()                           { return createdAt; }
    public Instant getUpdatedAt()                           { return updatedAt; }
}