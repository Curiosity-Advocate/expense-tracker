package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ExpenseCategoryId implements Serializable {

    @Column(name = "expense_id")
    private UUID expenseId;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    @Column(name = "category_id")
    private UUID categoryId;

    public ExpenseCategoryId() {}

    public ExpenseCategoryId(UUID expenseId, LocalDate expenseDate, UUID categoryId) {
        this.expenseId = expenseId;
        this.expenseDate = expenseDate;
        this.categoryId = categoryId;
    }

    public UUID getExpenseId() { return expenseId; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public UUID getCategoryId() { return categoryId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpenseCategoryId that)) return false;
        return Objects.equals(expenseId, that.expenseId)
                && Objects.equals(expenseDate, that.expenseDate)
                && Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() { return Objects.hash(expenseId, expenseDate, categoryId); }
}
