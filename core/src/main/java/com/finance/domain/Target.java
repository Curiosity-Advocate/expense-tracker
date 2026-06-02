package com.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Target(
        UUID targetId,
        TargetType targetType,
        BigDecimal amount,
        int periodYear,
        int periodMonth,
        List<TargetCategory> categories,
        Instant createdAt) {}
