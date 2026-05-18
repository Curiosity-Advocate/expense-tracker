package com.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TargetResponse(
        UUID targetId,
        String targetType,
        BigDecimal amount,
        int periodYear,
        int periodMonth,
        List<TargetCategoryResponse> categories,
        Instant createdAt) {}
