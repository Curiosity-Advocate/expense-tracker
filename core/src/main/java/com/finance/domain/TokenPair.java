package com.finance.domain;

import java.time.Instant;

// Named TokenPair because V2 will add a refresh token alongside the access token.
// The field is already named accessToken (not just token) so the mobile client
// is written correctly from day one — no client changes needed when refresh tokens arrive.
public record TokenPair(
        String accessToken,
        Instant expiresAt,
        String tokenType    // always "Bearer" — present so the client never hardcodes it
) {}