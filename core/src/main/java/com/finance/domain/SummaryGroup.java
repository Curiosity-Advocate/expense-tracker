package com.finance.domain;

import java.math.BigDecimal;

public record SummaryGroup(
        String groupKey,
        BigDecimal totalAmount,
        long transactionCount,
        double percentageOfTotal) {}
