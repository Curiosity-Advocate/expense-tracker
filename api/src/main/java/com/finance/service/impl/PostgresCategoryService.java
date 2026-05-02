package com.finance.service.impl;

import com.finance.domain.Category;
import com.finance.entity.CategoryEntity;
import com.finance.repository.CategoryRepository;
import com.finance.service.CategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PostgresCategoryService implements CategoryService {

    private final CategoryRepository categoryRepository;

    public PostgresCategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> getCategoriesForUser(UUID userId) {
        return categoryRepository.findByUserIdOrUserIdIsNull(userId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private Category toDomain(CategoryEntity entity) {
        return new Category(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.isSystem(),
                entity.getUserId(),
                entity.getCreatedAt()
        );
    }
}