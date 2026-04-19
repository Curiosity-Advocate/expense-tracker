package com.finance.domain;

import java.time.Instant;
import java.util.UUID;

// What the service returns after a successful registration.
// The controller maps this to a RegisterResponse (HTTP vocabulary).
// Core domain objects never contain HTTP or JPA concepts.
public record RegisteredUser(
        UUID userId,
        String username,
        String email,
        Instant createdAt
) {}