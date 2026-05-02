package com.finance.query;

import com.finance.domain.GroupBy;

import java.time.LocalDate;

// Carries filter and grouping parameters for the summary endpoint.
// dateFrom and dateTo are required — summary without a date range
// would aggregate all expenses which is too expensive and not useful.
// groupBy is required — determines which materialized view is queried.
public record SummaryQuery(
        LocalDate dateFrom,
        LocalDate dateTo,
        GroupBy groupBy
) {
    // Compact constructor — validates required fields
    public SummaryQuery {
    }
}