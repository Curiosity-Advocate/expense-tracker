package com.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Internal service used by the expense service when updating categories on a PATCH.
// Replaces the category-weight rows atomically within the calling transaction.
public interface CategoryResolutionService {
    void replaceCategories(UUID userId, UUID expenseId, LocalDate expenseDate,
                           BigDecimal amount, List<String> categoryNames);
}
