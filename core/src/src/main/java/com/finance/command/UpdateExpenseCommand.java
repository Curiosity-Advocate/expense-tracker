package com.finance.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// All fields are nullable — only fields present in the PATCH body are applied.
// The service enforces which fields are immutable for BANK_IMPORT expenses.
public record UpdateExpenseCommand(
        BigDecimal amount,
        String merchantName,
        LocalDate expenseDate,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,
        String notes,
        String paymentMethod) {}
