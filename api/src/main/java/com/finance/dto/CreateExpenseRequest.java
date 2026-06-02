package com.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateExpenseRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String merchantName,
        @Schema(description = "Date the expense occurred, in UTC (YYYY-MM-DD). Callers in non-UTC timezones must convert.", example = "2026-05-23")
        @NotNull LocalDate expenseDate,
        @NotBlank String paymentMethod,
        UUID bankAccountId,
        @NotEmpty List<String> categories,
        // Optional: keyed by category name, values are the absolute split amounts.
        // Must sum to amount. Null = even split.
        Map<String, BigDecimal> categoryWeights,
        String notes,
        @NotBlank
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "must be a valid UUID")
        String idempotencyKey) {}
