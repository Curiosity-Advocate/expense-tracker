package com.finance.domain;

import java.math.BigDecimal;

// Output of the prediction engine. strategyUsed + strategyVersion are surfaced
// in the API response so the caller knows exactly which algorithm ran.
// When the algorithm changes, a new strategy class is created — the old version
// is never modified, preserving historical reproducibility (Open/Closed Principle).
public record PredictionResult(
        BigDecimal projectedAmount,
        boolean willExceedTarget,
        BigDecimal projectedExceedanceAmount,  // null if projectedAmount <= targetAmount
        String strategyUsed,
        String strategyVersion,
        Confidence confidence,
        int daysElapsed,
        int daysRemainingInPeriod) {}
