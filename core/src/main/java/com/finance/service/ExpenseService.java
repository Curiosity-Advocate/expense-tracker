package com.finance.service;

import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;
import com.finance.domain.Expense;
import com.finance.domain.ExpensePage;
import com.finance.domain.ExpenseSummary;
import com.finance.query.ExpenseQuery;
import com.finance.query.SummaryQuery;

import java.time.LocalDate;
import java.util.UUID;

public interface ExpenseService {

    // POST /api/v1/expenses — manual entry only
    Expense createExpense(UUID userId, CreateExpenseCommand command);

    // GET /api/v1/expenses
    ExpensePage queryExpenses(UUID userId, ExpenseQuery query);

    // GET /api/v1/expenses/{id}
    // expenseDate is required alongside id because of the composite PK
    // on the partitioned expenses table.
    Expense getExpense(UUID userId, UUID expenseId, LocalDate expenseDate);

    // PATCH /api/v1/expenses/{id}
    Expense updateExpense(UUID userId, UUID expenseId, LocalDate expenseDate,UpdateExpenseCommand command);

    // DELETE /api/v1/expenses/{id}
    void softDeleteExpense(UUID userId, UUID expenseId, LocalDate expenseDate);

    // GET /api/v1/expenses/summary
    ExpenseSummary getSummary(UUID userId, SummaryQuery query);
}