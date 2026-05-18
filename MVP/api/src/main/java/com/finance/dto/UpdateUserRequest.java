package com.finance.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserRequest(@NotNull Boolean isDiscoverable) {}
