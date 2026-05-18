package com.finance.domain;

import java.util.List;

// Pagination wrapper returned by GET /api/v1/expenses.
// The page field is 1-indexed to match the API contract —
// Spring Data's 0-indexed page is adjusted in the service layer.
public record ExpensePage(
        List<Expense> data,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {}
