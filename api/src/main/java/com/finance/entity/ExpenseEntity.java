package com.finance.entity;

import com.finance.domain.ExpenseId;
import com.finance.domain.ExpenseSource;
import com.finance.domain.BankStatus;

import jakarta.persistence.*;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expenses")
@IdClass(ExpenseId.class)
public class ExpenseEntity {

    @Id
    private UUID id;

    @Id
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "merchant_name", nullable = false, length = 255)
    private String merchantName;

    @Column(name = "notes")
    private String notes;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ExpenseSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_status", length = 20)
    private BankStatus bankStatus;

    @Column(name = "external_transaction_id", length = 255)
    private String externalTransactionId;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "ai_categorised", nullable = false)
    private boolean aiCategorised = false;

    @Column(name = "is_merged", nullable = false)
    private boolean isMerged = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CurrentTimestamp(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @PrePersist
    private void onCreate() {
        this.id = UUID.randomUUID();
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId()                                         { return id; }
    public LocalDate getExpenseDate()                           { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate)           { this.expenseDate = expenseDate; }
    public UUID getUserId()                                     { return userId; }
    public void setUserId(UUID userId)                          { this.userId = userId; }
    public BigDecimal getAmount()                               { return amount; }
    public void setAmount(BigDecimal amount)                    { this.amount = amount; }
    public String getMerchantName()                             { return merchantName; }
    public void setMerchantName(String merchantName)            { this.merchantName = merchantName; }
    public String getNotes()                                    { return notes; }
    public void setNotes(String notes)                          { this.notes = notes; }
    public String getPaymentMethod()                            { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod)          { this.paymentMethod = paymentMethod; }
    public UUID getBankAccountId()                              { return bankAccountId; }
    public void setBankAccountId(UUID bankAccountId)            { this.bankAccountId = bankAccountId; }
    public ExpenseSource getSource()                                   { return source; }
    public void setSource(ExpenseSource source)                        { this.source = source; }
    public BankStatus getBankStatus()                           { return bankStatus; }
    public void setBankStatus(BankStatus bankStatus)            { this.bankStatus = bankStatus; }
    public String getExternalTransactionId()                    { return externalTransactionId; }
    public void setExternalTransactionId(String id)             { this.externalTransactionId = id; }
    public UUID getIdempotencyKey()                             { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey)          { this.idempotencyKey = idempotencyKey; }
    public boolean isAiCategorised()                            { return aiCategorised; }
    public void setAiCategorised(boolean aiCategorised)         { this.aiCategorised = aiCategorised; }
    public boolean isMerged()                                   { return isMerged; }
    public void setMerged(boolean isMerged)                     { this.isMerged = isMerged; }
    public Instant getDeletedAt()                               { return deletedAt; }
    public void setDeletedAt(Instant deletedAt)                 { this.deletedAt = deletedAt; }
    public Instant getCreatedAt()                               { return createdAt; }
    public Instant getUpdatedAt()                               { return updatedAt; }
}