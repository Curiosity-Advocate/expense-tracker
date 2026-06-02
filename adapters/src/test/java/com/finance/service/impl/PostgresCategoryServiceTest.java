package com.finance.service.impl;

import com.finance.command.CreateCategoryCommand;
import com.finance.command.UpdateCategoryCommand;
import com.finance.entity.CategoryEntity;
import com.finance.exception.CategoryAlreadyExistsException;
import com.finance.exception.CategoryNotFoundException;
import com.finance.exception.SystemCategoryImmutableException;
import com.finance.repository.CategoryRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresCategoryServiceTest {

    @Mock CategoryRepository categoryRepository;

    @InjectMocks PostgresCategoryService service;

    @Nested
    class CreateCategory {

        @Test
        void duplicateName_throwsCategoryAlreadyExistsException() {
            UUID userId = UUID.randomUUID();
            CreateCategoryCommand cmd = new CreateCategoryCommand("Groceries", null, null);

            when(categoryRepository.existsByUserIdAndName(userId, "Groceries")).thenReturn(true);

            assertThatThrownBy(() -> service.createCategory(userId, cmd))
                    .isInstanceOf(CategoryAlreadyExistsException.class);
        }
    }

    @Nested
    class UpdateCategory {

        @Test
        void systemCategory_throwsSystemCategoryImmutableException() {
            UUID userId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UpdateCategoryCommand cmd = new UpdateCategoryCommand("Renamed", null);

            // No-arg constructor leaves userId null → service treats it as a system category
            CategoryEntity systemCategory = new CategoryEntity();
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(systemCategory));

            assertThatThrownBy(() -> service.updateCategory(userId, categoryId, cmd))
                    .isInstanceOf(SystemCategoryImmutableException.class);
        }

        @Test
        void otherUserCategory_throwsCategoryNotFoundException() {
            UUID userId = UUID.randomUUID();
            UUID otherUserId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UpdateCategoryCommand cmd = new UpdateCategoryCommand("Renamed", null);

            CategoryEntity otherUserCategory = new CategoryEntity(otherUserId, "Groceries", null, null);
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(otherUserCategory));

            assertThatThrownBy(() -> service.updateCategory(userId, categoryId, cmd))
                    .isInstanceOf(CategoryNotFoundException.class);
        }

        @Test
        void duplicateName_throwsCategoryAlreadyExistsException() {
            UUID userId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UpdateCategoryCommand cmd = new UpdateCategoryCommand("Transport", null);

            // Category belongs to this user, currently named "Groceries"
            CategoryEntity ownCategory = new CategoryEntity(userId, "Groceries", null, null);
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(ownCategory));
            // "Transport" already exists for this user
            when(categoryRepository.existsByUserIdAndName(userId, "Transport")).thenReturn(true);

            assertThatThrownBy(() -> service.updateCategory(userId, categoryId, cmd))
                    .isInstanceOf(CategoryAlreadyExistsException.class);
        }
    }
}
