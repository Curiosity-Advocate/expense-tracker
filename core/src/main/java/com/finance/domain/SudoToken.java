package com.finance.domain;

import java.time.Instant;

// Returned by SudoTokenService.create() — the raw token is shown to the
// client exactly once and never persisted (only its SHA-256 hash is stored
// in sudo_tokens). The client must remember the raw value for the duration
// of the delegation session (15 min) and include it in the X-Sudo-Token
// header on any /api/v1/expenses/... request that uses ?asUserId=.
public record SudoToken(String rawToken, Instant expiresAt) {}
