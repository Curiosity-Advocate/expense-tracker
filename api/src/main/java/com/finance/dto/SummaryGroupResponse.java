package com.finance.dto;

import java.math.BigDecimal;

public record SummaryGroupResponse(
        String groupKey,
        BigDecimal totalAmount,
        long transactionCount,
        double percentageOfTotal) {}
