package com.finance.dto;

import com.finance.domain.ParticipationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TargetCategoryRequest(@NotNull UUID categoryId, @NotNull ParticipationType participation) {}
