package com.finance.command;

import java.util.UUID;

// parentId is optional — allows hierarchical categories (e.g. FOOD → GROCERIES).
// System categories cannot be used as parents for now (enforced at service layer).
public record CreateCategoryCommand(String name, String description, UUID parentId) {}
