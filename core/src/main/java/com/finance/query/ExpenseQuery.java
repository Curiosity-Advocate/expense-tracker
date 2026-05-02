package com.finance.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseQuery(
        LocalDate dateFrom,
        LocalDate dateTo,
        String merchantName,
        List<String> categories,
        String paymentMethod,
        UUID bankAccountId,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String source,
        boolean includeDeleted,
        int page,
        int pageSize,
        String sortBy,
        String sortOrder
) {
    public ExpenseQuery {
        if (page < 1)        page     = 1;
        if (pageSize < 1)    pageSize = 20;
        if (pageSize > 100)  pageSize = 100;
        if (sortBy == null)  sortBy   = "expenseDate";
        if (sortOrder == null) sortOrder = "DESC";
    }
}