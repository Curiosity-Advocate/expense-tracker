package com.finance.controller;

import com.finance.domain.Category;
import com.finance.domain.UserPrincipal;
import com.finance.service.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<?> getCategories(
            @AuthenticationPrincipal UserPrincipal principal) {

        List<Category> categories = categoryService.getCategoriesForUser(principal.userId());

        return ResponseEntity.ok(Map.of("data", categories));
    }
}