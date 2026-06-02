package com.finance.dto;

import com.finance.domain.TargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CreateTargetRequest(
        @NotNull TargetType targetType,
        @NotNull @Positive BigDecimal amount,
        @NotNull Integer periodYear,
        @NotNull Integer periodMonth,
        List<TargetCategoryRequest> categories) {}
