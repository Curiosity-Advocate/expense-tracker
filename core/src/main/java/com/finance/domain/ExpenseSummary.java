package com.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Domain object returned by ExpenseService.getSummary().
// totalAmount is the grand total across all groups in the period.
// groups is the breakdown by the requested groupBy dimension.
public record ExpenseSummary(
        BigDecimal totalAmount,
        LocalDate periodFrom,
        LocalDate periodTo,
        List<SummaryGroup> groups
) {}