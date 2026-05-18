package com.finance.dto;

import java.math.BigDecimal;

public record PredictionResultResponse(
        BigDecimal projectedAmount,
        boolean willExceedTarget,
        BigDecimal projectedExceedanceAmount,
        String strategyUsed,
        String strategyVersion,
        String confidence,
        int daysElapsed,
        int daysRemainingInPeriod) {}
