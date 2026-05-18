package com.finance.domain;

import java.time.Instant;
import java.util.UUID;

// Returned by GET /api/v1/users/me and PATCH /api/v1/users/me.
// isDiscoverable is the opt-in flag a user must set before another
// user can grant them access.
public record UserProfile(
        UUID userId,
        String username,
        String email,
        boolean isDiscoverable,
        Instant createdAt) {}
