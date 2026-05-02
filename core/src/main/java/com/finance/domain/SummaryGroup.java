package com.finance.domain;

import java.math.BigDecimal;

// Represents one group in the summary response.
// groupKey is the value being grouped by:
//   CATEGORY  → category name e.g. "GROCERIES"
//   MERCHANT  → merchant name e.g. "Woolworths"
//   MONTH     → month string e.g. "2026-04"
public record SummaryGroup(
        String groupKey,
        BigDecimal totalAmount,
        long transactionCount,
        double percentageOfTotal
) {}