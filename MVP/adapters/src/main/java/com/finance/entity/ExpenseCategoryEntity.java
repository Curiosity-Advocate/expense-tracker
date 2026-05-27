package com.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

// weight_amount is the pre-computed dollar share of the expense for this category.
// e.g. a £100 expense across 4 categories → £25.00 each.
@Entity
@Table(name = "expense_categories")
public class ExpenseCategoryEntity {

    @EmbeddedId
    private ExpenseCategoryId id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "weight_amount", nullable = false)
    private BigDecimal weightAmount;

    public ExpenseCategoryEntity() {}

    public ExpenseCategoryEntity(ExpenseCategoryId id, UUID userId, BigDecimal weightAmount) {
        this.id = id;
        this.userId = userId;
        this.weightAmount = weightAmount;
    }

    public ExpenseCategoryId getId() { return id; }
    public UUID getUserId() { return userId; }
    public BigDecimal getWeightAmount() { return weightAmount; }
}
