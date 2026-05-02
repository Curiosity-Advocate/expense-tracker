package com.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateExpenseRequest(

        // Client-generated UUID for safe retries.
        // If null, server treats every request as unique — no idempotency protection.
        UUID idempotencyKey,

        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank
        String merchantName,

        @NotNull
        LocalDate expenseDate,

        // Empty list is allowed — service substitutes UNCATEGORISED
        List<String> categories,

        Map<String, BigDecimal> categoryWeights,

        String notes,
        String paymentMethod,

        // Null means use the user's system CASH account
        UUID bankAccountId
) {}