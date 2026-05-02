package com.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense_categories")
@IdClass(ExpenseCategoryId.class)
public class ExpenseCategoryEntity {

    @Id
    @Column(name = "expense_id", nullable = false)
    private UUID expenseId;

    @Id
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Id
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private CategoryEntity category;

    public CategoryEntity getCategory() { return category; }

    // Pre-computed even split — amount / number of categories
    @Column(name = "weight_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal weightAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getExpenseId()                      { return expenseId; }
    public void setExpenseId(UUID expenseId)        { this.expenseId = expenseId; }
    public LocalDate getExpenseDate()               { return expenseDate; }
    public void setExpenseDate(LocalDate date)      { this.expenseDate = date; }
    public UUID getCategoryId()                     { return categoryId; }
    public void setCategoryId(UUID categoryId)      { this.categoryId = categoryId; }
    public BigDecimal getWeightAmount()             { return weightAmount; }
    public void setWeightAmount(BigDecimal amount)  { this.weightAmount = amount; }
    public Instant getCreatedAt()                   { return createdAt; }
}