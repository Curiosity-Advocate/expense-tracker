package com.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ExpenseSummary(
        BigDecimal totalAmount,
        LocalDate periodFrom,
        LocalDate periodTo,
        List<SummaryGroup> groups) {}
