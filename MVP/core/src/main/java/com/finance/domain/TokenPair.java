package com.finance.domain;

import java.time.Instant;

// Returned by AuthService.login() and AuthService.refresh().
//
// Access token: JWT, ~15 min lifetime (configured in app.jwt.access-token-expiry-minutes).
// Refresh token: opaque random value, ~7 days from original login (rotation does not extend).
// See S4 design in roadmap.md and refresh_tokens table in data-model.md.
public record TokenPair(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String tokenType) {}
