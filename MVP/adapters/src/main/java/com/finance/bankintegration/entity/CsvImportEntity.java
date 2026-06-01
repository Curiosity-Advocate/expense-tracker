package com.finance.bankintegration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "csv_imports")
public class CsvImportEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String status;

    @Column(name = "exported_on_date", nullable = false)
    private LocalDate exportedOnDate;

    @Column(name = "parser_version_tag", nullable = false)
    private String parserVersionTag;

    @Column(name = "raw_csv_bytes", nullable = false)
    private byte[] rawCsvBytes;

    @Column(name = "raw_csv_bytes_deleted_at")
    private Instant rawCsvBytesDeletedAt;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "deduped_count", nullable = false)
    private int dedupedCount;

    @Column(name = "parse_error_count", nullable = false)
    private int parseErrorCount;

    @Column(name = "last_processed_row", nullable = false)
    private int lastProcessedRow;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID      getId()                   { return id; }
    public UUID      getBankAccountId()        { return bankAccountId; }
    public UUID      getUserId()               { return userId; }
    public String    getStatus()               { return status; }
    public LocalDate getExportedOnDate()       { return exportedOnDate; }
    public String    getParserVersionTag()     { return parserVersionTag; }
    public byte[]    getRawCsvBytes()          { return rawCsvBytes; }
    public Instant   getRawCsvBytesDeletedAt() { return rawCsvBytesDeletedAt; }
    public int       getImportedCount()        { return importedCount; }
    public int       getDedupedCount()         { return dedupedCount; }
    public int       getParseErrorCount()      { return parseErrorCount; }
    public int       getLastProcessedRow()     { return lastProcessedRow; }
    public String    getErrorMessage()         { return errorMessage; }
    public Instant   getSubmittedAt()          { return submittedAt; }
    public Instant   getStartedAt()            { return startedAt; }
    public Instant   getCompletedAt()          { return completedAt; }

    public void setBankAccountId(UUID v)        { this.bankAccountId = v; }
    public void setUserId(UUID v)               { this.userId = v; }
    public void setStatus(String v)             { this.status = v; }
    public void setExportedOnDate(LocalDate v)  { this.exportedOnDate = v; }
    public void setParserVersionTag(String v)   { this.parserVersionTag = v; }
    public void setRawCsvBytes(byte[] v)        { this.rawCsvBytes = v; }
    public void setRawCsvBytesDeletedAt(Instant v) { this.rawCsvBytesDeletedAt = v; }
    public void setImportedCount(int v)         { this.importedCount = v; }
    public void setDedupedCount(int v)          { this.dedupedCount = v; }
    public void setParseErrorCount(int v)       { this.parseErrorCount = v; }
    public void setLastProcessedRow(int v)      { this.lastProcessedRow = v; }
    public void setErrorMessage(String v)       { this.errorMessage = v; }
    public void setStartedAt(Instant v)         { this.startedAt = v; }
    public void setCompletedAt(Instant v)       { this.completedAt = v; }
}
