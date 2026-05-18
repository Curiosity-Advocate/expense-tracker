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
    Expense createExpense(UUID userId, CreateExpenseCommand command);
    ExpensePage queryExpenses(UUID userId, ExpenseQuery query);
    Expense getExpense(UUID userId, UUID expenseId, LocalDate expenseDate);
    Expense updateExpense(UUID userId, UUID expenseId, LocalDate expenseDate, UpdateExpenseCommand command);
    void softDeleteExpense(UUID userId, UUID expenseId, LocalDate expenseDate);
    ExpenseSummary getSummary(UUID userId, SummaryQuery query);
}
