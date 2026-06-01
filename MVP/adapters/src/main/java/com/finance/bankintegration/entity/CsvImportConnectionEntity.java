package com.finance.bankintegration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "csv_import_connections")
public class CsvImportConnectionEntity {

    // PK is also FK to bank_accounts(id) — at most one CSV config per account.
    @Id
    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "bank_id", nullable = false)
    private String bankId;

    @Column(name = "csv_export_url")
    private String csvExportUrl;

    @Column(name = "last_imported_at")
    private Instant lastImportedAt;

    @Column(name = "last_date_to")
    private LocalDate lastDateTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID      getBankAccountId()  { return bankAccountId; }
    public UUID      getUserId()         { return userId; }
    public String    getBankId()         { return bankId; }
    public String    getCsvExportUrl()   { return csvExportUrl; }
    public Instant   getLastImportedAt() { return lastImportedAt; }
    public LocalDate getLastDateTo()     { return lastDateTo; }

    public void setBankAccountId(UUID v)     { this.bankAccountId = v; }
    public void setUserId(UUID v)            { this.userId = v; }
    public void setBankId(String v)          { this.bankId = v; }
    public void setCsvExportUrl(String v)    { this.csvExportUrl = v; }
    public void setLastImportedAt(Instant v) { this.lastImportedAt = v; }
    public void setLastDateTo(LocalDate v)   { this.lastDateTo = v; }
}
