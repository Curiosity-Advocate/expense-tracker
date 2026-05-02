package com.finance.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

// IdClass for the composite primary key on expenses (id, expense_date).
// Required by PostgreSQL partitioning — partition key must be part of the PK.
// Lives in core so both api and worker can reference it.
public class ExpenseId implements Serializable {

    private UUID id;
    private LocalDate expenseDate;

    public ExpenseId() {}

    public ExpenseId(UUID id, LocalDate expenseDate) {
        this.id = id;
        this.expenseDate = expenseDate;
    }

    // equals() and hashCode() are mandatory for @IdClass —
    // JPA uses them to compare and cache entity identities.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpenseId that)) return false;
        return Objects.equals(id, that.id) &&
               Objects.equals(expenseDate, that.expenseDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, expenseDate);
    }

    public UUID getId()                     { return id; }
    public void setId(UUID id)             { this.id = id; }
    public LocalDate getExpenseDate()       { return expenseDate; }
    public void setExpenseDate(LocalDate d) { this.expenseDate = d; }
}