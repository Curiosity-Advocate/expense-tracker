package com.finance.service;

import com.finance.command.CreateCategoryCommand;
import com.finance.command.UpdateCategoryCommand;
import com.finance.domain.Category;

import java.util.List;
import java.util.UUID;

public interface CategoryService {
    List<Category> getCategoriesForUser(UUID userId);
    Category createCategory(UUID userId, CreateCategoryCommand command);
    Category updateCategory(UUID userId, UUID categoryId, UpdateCategoryCommand command);
}
