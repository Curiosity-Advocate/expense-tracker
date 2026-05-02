package com.finance.domain;

import java.time.Instant;
import java.util.UUID;

public record Category(
        UUID categoryId,
        String name,
        String description,
        boolean isSystem,
        UUID userId,        // null for system categories
        Instant createdAt
) {}