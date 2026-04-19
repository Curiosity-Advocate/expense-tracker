package com.finance.domain;

import java.util.UUID;

// Represents the authenticated user for the duration of a request.
// Set by the gateway filter, injected into every module via Spring's SecurityContext.
// No module ever calls AuthService to re-authenticate during a request —
// if this object is present, the user has already been verified.
//
// Kept intentionally minimal — only what modules need to enforce data isolation.
// No roles in V1 (single-user-type system). Add roles here when admin is introduced.
public record UserPrincipal(
        UUID userId,
        String username
) {}