package com.finance.exception;

import java.util.UUID;

public class CategoryNotFoundException extends RuntimeException {
    public CategoryNotFoundException(UUID id) {
        super("Category not found: " + id);
    }

    public CategoryNotFoundException(String name) {
        super("Category not found: " + name);
    }
}
