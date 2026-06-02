package com.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ExpenseSummaryResponse(
        String groupBy,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal totalAmount,
        List<SummaryGroupResponse> groups) {}
