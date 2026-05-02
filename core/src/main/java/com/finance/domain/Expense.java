package com.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Domain object returned by ExpenseService.
// No JPA or Spring annotations — pure domain vocabulary.
public record Expense(
        UUID expenseId,
        LocalDate expenseDate,
        BigDecimal amount,
        String merchantName,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,  // category name → weighted amount
        String notes,
        String paymentMethod,
        UUID bankAccountId,
        ExpenseSource source,          // MANUAL | BANK_IMPORT
        BankStatus bankStatus,      // PENDING | POSTED | null for manual
        boolean aiCategorised,
        boolean isMerged,
        Instant createdAt
) {}