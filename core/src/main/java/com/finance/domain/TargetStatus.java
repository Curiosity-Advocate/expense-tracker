package com.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Returned by GET /api/v1/targets/{targetId}/status.
// prediction is null when there is insufficient data (daysElapsed = 0).
// The controller renders this as a LOW-confidence null-amount prediction node
// so the client can show "not enough data yet" rather than an error.
public record TargetStatus(
        UUID targetId,
        BigDecimal targetAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        double percentageUsed,
        PredictionResult prediction,
        Instant dataFreshAsOf) {}
