package com.finance.command;

import com.finance.domain.TargetType;

import java.math.BigDecimal;
import java.util.List;

// categories rules enforced by the service layer:
//   CATEGORY      → exactly one INCLUSIVE entry, no EXCLUSIVE
//   MULTI_CATEGORY → two or more INCLUSIVE entries
//   TOTAL         → zero or more EXCLUSIVE entries, no INCLUSIVE
public record CreateTargetCommand(
        TargetType targetType,
        BigDecimal amount,
        int periodYear,
        int periodMonth,
        List<TargetCategoryCommand> categories) {}
