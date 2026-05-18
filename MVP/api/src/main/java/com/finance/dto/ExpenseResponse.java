package com.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        LocalDate expenseDate,
        BigDecimal amount,
        String merchantName,
        String paymentMethod,
        UUID bankAccountId,
        String source,
        boolean aiCategorised,
        List<CategoryAllocation> categories,
        String notes,
        Instant createdAt) {}
