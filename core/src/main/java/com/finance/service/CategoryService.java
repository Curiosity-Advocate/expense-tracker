package com.finance.service;

import com.finance.domain.Category;

import java.util.List;
import java.util.UUID;

public interface CategoryService {

    List<Category> getCategoriesForUser(UUID userId);
}