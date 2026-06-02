package com.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TargetStatusResponse(
        UUID targetId,
        BigDecimal targetAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        double percentageUsed,
        PredictionResultResponse prediction, // null on first day of period
        Instant dataFreshAsOf) {}
