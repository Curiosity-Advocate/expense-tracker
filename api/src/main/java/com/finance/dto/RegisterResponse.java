package com.finance.dto;

import java.time.Instant;
import java.util.UUID;

// What gets serialised to JSON and returned in the response body.
// Maps from RegisteredUser (domain) — controller does this mapping.
public record RegisterResponse(
        UUID userId,
        String username,
        String email,
        Instant createdAt
) {}