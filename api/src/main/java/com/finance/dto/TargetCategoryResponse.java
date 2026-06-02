package com.finance.dto;

import java.util.UUID;

public record TargetCategoryResponse(UUID categoryId, String categoryName, String participation) {}
