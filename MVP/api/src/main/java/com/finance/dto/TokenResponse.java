package com.finance.dto;

import java.time.Instant;

// Used by POST /api/v1/auth/login and POST /api/v1/auth/refresh.
// Both endpoints issue a fresh access + refresh pair, so they share a
// single response shape. If they ever diverge (e.g. login adds onboarding
// fields), split into LoginResponse + RefreshResponse at that point.
public record TokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String tokenType) {}
