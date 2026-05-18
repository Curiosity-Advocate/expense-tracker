package com.finance.domain;

import java.util.UUID;

// One row in the target's category list.
// categoryName is denormalised here for display — avoids an extra lookup in the controller.
public record TargetCategory(UUID categoryId, String categoryName, ParticipationType participation) {}
