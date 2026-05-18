package com.finance.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// All filter fields are nullable/optional — nulls are silently ignored when building
// the JPA Specification in the service. Callers only set what they need.
public record ExpenseQuery(
        LocalDate dateFrom,
        LocalDate dateTo,
        String merchantName,
        List<String> categories,
        String paymentMethod,
        UUID bankAccountId,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String source,
        boolean includeDeleted,
        int page,
        int pageSize,
        String sortBy,
        String sortOrder) {}
