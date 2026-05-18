package com.finance.service.impl;

import com.finance.command.CreateCategoryCommand;
import com.finance.command.UpdateCategoryCommand;
import com.finance.domain.Category;
import com.finance.entity.CategoryEntity;
import com.finance.exception.CategoryAlreadyExistsException;
import com.finance.exception.CategoryNotFoundException;
import com.finance.exception.SystemCategoryImmutableException;
import com.finance.repository.CategoryRepository;
import com.finance.service.CategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PostgresCategoryService implements CategoryService {

    private final CategoryRepository categoryRepository;

    public PostgresCategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> getCategoriesForUser(UUID userId) {
        return categoryRepository.findAllVisibleToUser(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Category createCategory(UUID userId, CreateCategoryCommand cmd) {
        if (categoryRepository.existsByUserIdAndName(userId, cmd.name())) {
            throw new CategoryAlreadyExistsException(cmd.name());
        }
        CategoryEntity entity = new CategoryEntity(userId, cmd.name(), cmd.description(), cmd.parentId());
        return toDomain(categoryRepository.save(entity));
    }

    @Override
    public Category updateCategory(UUID userId, UUID categoryId, UpdateCategoryCommand cmd) {
        CategoryEntity entity = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        if (entity.getUserId() == null) {
            throw new SystemCategoryImmutableException();
        }
        if (!entity.getUserId().equals(userId)) {
            throw new CategoryNotFoundException(categoryId); // don't reveal existence to other users
        }

        if (cmd.name() != null) {
            if (categoryRepository.existsByUserIdAndName(userId, cmd.name())
                    && !entity.getName().equals(cmd.name())) {
                throw new CategoryAlreadyExistsException(cmd.name());
            }
            entity.setName(cmd.name());
        }
        if (cmd.description() != null) entity.setDescription(cmd.description());

        return toDomain(categoryRepository.save(entity));
    }

    private Category toDomain(CategoryEntity e) {
        return new Category(e.getId(), e.getName(), e.getDescription(), e.getUserId() == null);
    }
}
