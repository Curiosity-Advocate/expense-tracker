package com.finance.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// All fields nullable — only present fields are applied (PATCH semantics).
// amount, merchantName, and paymentMethod are immutable for BANK_IMPORT expenses.
public record UpdateExpenseRequest(
        BigDecimal amount,
        String merchantName,
        String paymentMethod,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,
        String notes) {}
