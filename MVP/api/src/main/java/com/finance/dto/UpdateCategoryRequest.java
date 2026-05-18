package com.finance.dto;

import jakarta.validation.constraints.Size;

// Both nullable — only present fields are updated.
public record UpdateCategoryRequest(
        @Size(max = 50) String name,
        @Size(max = 255) String description) {}
