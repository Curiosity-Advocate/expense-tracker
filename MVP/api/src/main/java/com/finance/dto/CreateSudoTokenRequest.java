package com.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSudoTokenRequest(
        @NotNull UUID grantId,
        @NotBlank String password) {}
