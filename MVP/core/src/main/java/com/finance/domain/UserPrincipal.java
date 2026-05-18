package com.finance.domain;

import java.util.UUID;

// Stored in Spring's SecurityContext after JWT validation.
// Passed via @AuthenticationPrincipal in controllers — avoids
// hitting the database on every request just to find out who the caller is.
public record UserPrincipal(UUID userId, String username) {}
