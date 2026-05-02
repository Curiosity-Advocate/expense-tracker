package com.finance.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Crosses the boundary from HTTP layer into service layer.
// Categories can be empty — service substitutes UNCATEGORISED.
// bankAccountId can be null — service substitutes the user's CASH account.
public record CreateExpenseCommand(
        UUID idempotencyKey,
        BigDecimal amount,
        String merchantName,
        LocalDate expenseDate,
        List<String> categories,    // category names, not IDs
        Map<String, BigDecimal> categoryWeights,
        String notes,
        String paymentMethod,
        UUID bankAccountId          // null means use system CASH account
) {}