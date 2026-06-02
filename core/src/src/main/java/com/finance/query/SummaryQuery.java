package com.finance.query;

import com.finance.domain.GroupBy;

import java.time.LocalDate;

public record SummaryQuery(LocalDate dateFrom, LocalDate dateTo, GroupBy groupBy) {}
