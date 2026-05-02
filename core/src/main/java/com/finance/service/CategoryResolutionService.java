package com.finance.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

public interface CategoryResolutionService {

    // Resolves category names to their IDs visible to the user.
    // Substitutes UNCATEGORISED if the list is empty.
    // Returns map of category name → category ID
    Map<String, UUID> resolveCategories(UUID userId, List<String> categoryNames);

    // Computes even-split weights across resolved categories.
    // Returns map of category ID → weighted amount
    Map<UUID, BigDecimal> computeWeights(BigDecimal amount,
                                         Map<String, UUID> resolvedCategories);

    // Replaces all categories on an expense.
    // Substitutes UNCATEGORISED if resolved list is empty.
    void replaceCategories(UUID userId, UUID expenseId,
                           LocalDate expenseDate, BigDecimal amount,
                           List<String> categoryNames);
}