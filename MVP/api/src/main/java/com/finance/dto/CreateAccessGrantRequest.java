package com.finance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateAccessGrantRequest(
        @NotBlank String granteeUsername,
        @NotBlank String accessLevel,
        @Min(1) @Max(30) int expiresInDays) {}
