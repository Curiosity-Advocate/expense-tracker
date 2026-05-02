package com.finance.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

// IdClass for the composite PK on expense_categories (expense_id, expense_date, category_id).
// expense_date is carried here because the FK references the partitioned expenses table
// which has a composite PK of (id, expense_date).
public class ExpenseCategoryId implements Serializable {

    private UUID expenseId;
    private LocalDate expenseDate;
    private UUID categoryId;

    public ExpenseCategoryId() {}

    public ExpenseCategoryId(UUID expenseId, LocalDate expenseDate, UUID categoryId) {
        this.expenseId = expenseId;
        this.expenseDate = expenseDate;
        this.categoryId = categoryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpenseCategoryId that)) return false;
        return Objects.equals(expenseId, that.expenseId) &&
               Objects.equals(expenseDate, that.expenseDate) &&
               Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expenseId, expenseDate, categoryId);
    }

    public UUID getExpenseId()                  { return expenseId; }
    public void setExpenseId(UUID expenseId)    { this.expenseId = expenseId; }
    public LocalDate getExpenseDate()           { return expenseDate; }
    public void setExpenseDate(LocalDate d)     { this.expenseDate = d; }
    public UUID getCategoryId()                 { return categoryId; }
    public void setCategoryId(UUID categoryId)  { this.categoryId = categoryId; }
}