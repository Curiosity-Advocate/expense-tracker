package com.finance.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// categoryWeights is optional — null means use even split.
// If provided, every category in categories must have an entry and weights must sum to amount.
public record CreateExpenseCommand(
        UUID idempotencyKey,
        BigDecimal amount,
        String merchantName,
        LocalDate expenseDate,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,
        String notes,
        String paymentMethod,
        UUID bankAccountId) {}
