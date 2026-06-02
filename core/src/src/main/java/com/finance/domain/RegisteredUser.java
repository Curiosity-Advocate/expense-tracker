package com.finance.domain;

import java.time.Instant;
import java.util.UUID;

public record RegisteredUser(UUID userId, String username, String email, Instant createdAt) {}
