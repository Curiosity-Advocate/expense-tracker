package com.finance.domain;

import java.util.UUID;

// isSystem = true means this is a system default category (user_id IS NULL in DB).
// System categories are visible to all users and cannot be modified or deleted.
public record Category(UUID categoryId, String name, String description, boolean isSystem) {}
