package com.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Expense(
        UUID expenseId,
        LocalDate expenseDate,
        BigDecimal amount,
        String merchantName,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,  // computed server-side, never from client
        String notes,
        String paymentMethod,
        UUID bankAccountId,
        ExpenseSource source,
        boolean aiCategorised,
        Instant createdAt) {}
