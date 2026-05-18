package com.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateExpenseRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String merchantName,
        @NotNull LocalDate expenseDate,
        @NotBlank String paymentMethod,
        UUID bankAccountId,
        @NotEmpty List<String> categories,
        // Optional: keyed by category name, values are the absolute split amounts.
        // Must sum to amount. Null = even split.
        Map<String, BigDecimal> categoryWeights,
        String notes,
        @NotBlank String idempotencyKey) {}
