package com.finance.dto;

import java.time.Instant;

// Maps from TokenPair (domain) — controller does this mapping.
// expiresAt is present from day one so the mobile client handles expiry correctly.
// When V2 short-lived tokens arrive, the client already knows to check this field.
public record LoginResponse(
        String accessToken,
        Instant expiresAt,
        String tokenType
) {}