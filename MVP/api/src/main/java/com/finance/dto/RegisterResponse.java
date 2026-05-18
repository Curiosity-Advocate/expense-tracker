package com.finance.dto;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(UUID userId, String username, String email, Instant createdAt) {}
