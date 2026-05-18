package com.finance.service.impl;

import com.finance.service.CategoryResolutionService;
import com.finance.entity.CategoryEntity;
import com.finance.entity.ExpenseCategoryEntity;
import com.finance.entity.ExpenseCategoryId;
import com.finance.exception.CategoryNotFoundException;
import com.finance.repository.CategoryRepository;
import com.finance.repository.ExpenseCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Replaces all category-weight rows for a given expense atomically.
// Always uses even split: weightAmount = amount / numCategories (rounded half-even).
@Service
public class PostgresCategoryResolutionService implements CategoryResolutionService {

    private final CategoryRepository categoryRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;

    public PostgresCategoryResolutionService(CategoryRepository categoryRepository,
                                             ExpenseCategoryRepository expenseCategoryRepository) {
        this.categoryRepository = categoryRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
    }

    @Override
    @Transactional
    public void replaceCategories(UUID userId, UUID expenseId, LocalDate expenseDate,
                                  BigDecimal amount, List<String> categoryNames) {
        expenseCategoryRepository.deleteByExpense(expenseId, expenseDate);

        List<CategoryEntity> categories = categoryNames.stream()
                .map(name -> categoryRepository.findByNameVisibleToUser(userId, name)
                        .orElseThrow(() -> new CategoryNotFoundException(null)))
                .toList();

        BigDecimal evenSplit = amount.divide(
                BigDecimal.valueOf(categories.size()), 2, RoundingMode.HALF_EVEN);

        for (CategoryEntity cat : categories) {
            expenseCategoryRepository.save(new ExpenseCategoryEntity(
                    new ExpenseCategoryId(expenseId, expenseDate, cat.getId()),
                    userId,
                    evenSplit));
        }
    }
}
