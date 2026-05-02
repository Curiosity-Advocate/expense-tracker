package com.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// PATCH request body — all fields optional.
// Null means "do not update this field".
// Validation is minimal here — business rules are enforced at the service layer.
public record UpdateExpenseRequest(
        BigDecimal amount,
        String merchantName,
        LocalDate expenseDate,
        List<String> categories,
        Map<String, BigDecimal> categoryWeights,
        String notes,
        String paymentMethod
) {}