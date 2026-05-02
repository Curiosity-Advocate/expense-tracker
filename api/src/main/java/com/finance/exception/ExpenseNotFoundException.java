package com.finance.exception;

import java.util.UUID;

// Thrown when an expense is not found for the given id and user.
// Intentionally does not reveal whether the expense exists but belongs
// to another user — same principle as UserRepository's 404 vs 403 decision.
// Both cases return 404 to prevent existence leakage.
public class ExpenseNotFoundException extends RuntimeException {

    public ExpenseNotFoundException(UUID expenseId) {
        super("No expense found with id: " + expenseId);
    }
}