package com.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.finance.domain.ExpenseSource;

public record ExpenseResponse(
        UUID expenseId,
        BigDecimal amount,
        String merchantName,
        LocalDate expenseDate,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,
        String notes,
        String paymentMethod,
        UUID bankAccountId,
        ExpenseSource source,
        boolean aiCategorised,
        Instant createdAt
) {}