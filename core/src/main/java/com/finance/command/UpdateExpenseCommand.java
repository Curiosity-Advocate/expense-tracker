package com.finance.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// PATCH semantics — only fields present in the request are updated.
// Null means "do not update this field", not "set this field to null".
// The service enforces which fields are mutable based on expense source.
public record UpdateExpenseCommand(
        BigDecimal amount,              // immutable for BANK_IMPORT
        String merchantName,            // immutable for BANK_IMPORT
        LocalDate expenseDate,          // immutable for BANK_IMPORT
        List<String> categories,        // mutable for all sources
        Map<String, BigDecimal> categoryWeights,  // mutable for all sources
        String notes,                   // mutable for all sources
        String paymentMethod            // immutable for BANK_IMPORT
) {}