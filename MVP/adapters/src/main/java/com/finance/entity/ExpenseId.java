package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ExpenseId implements Serializable {

    @Column(name = "id")
    private UUID id;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    public ExpenseId() {}

    public ExpenseId(UUID id, LocalDate expenseDate) {
        this.id = id;
        this.expenseDate = expenseDate;
    }

    public UUID getId() { return id; }
    public LocalDate getExpenseDate() { return expenseDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpenseId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(expenseDate, that.expenseDate);
    }

    @Override
    public int hashCode() { return Objects.hash(id, expenseDate); }
}
