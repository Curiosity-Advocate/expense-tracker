package com.finance.controller;

import com.finance.command.CreateCategoryCommand;
import com.finance.command.UpdateCategoryCommand;
import com.finance.domain.Category;
import com.finance.domain.UserPrincipal;
import com.finance.dto.CategoryResponse;
import com.finance.dto.CreateCategoryRequest;
import com.finance.dto.UpdateCategoryRequest;
import com.finance.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List all categories visible to the current user (system + own)")
    public ResponseEntity<List<CategoryResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        List<CategoryResponse> categories = categoryService.getCategoriesForUser(principal.userId())
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(categories);
    }

    @PostMapping
    @Operation(summary = "Create a user-defined category")
    public ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateCategoryRequest req) {
        Category category = categoryService.createCategory(principal.userId(),
                new CreateCategoryCommand(req.name(), req.description(), req.parentId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(category));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a user-defined category (system categories are immutable)")
    public ResponseEntity<CategoryResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest req) {
        Category updated = categoryService.updateCategory(principal.userId(), id,
                new UpdateCategoryCommand(req.name(), req.description()));
        return ResponseEntity.ok(toResponse(updated));
    }

    private CategoryResponse toResponse(Category c) {
        return new CategoryResponse(c.categoryId(), c.name(), c.description(), c.isSystem());
    }
}
