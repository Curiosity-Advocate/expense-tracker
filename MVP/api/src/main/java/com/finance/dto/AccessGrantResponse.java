package com.finance.dto;

import java.time.Instant;
import java.util.UUID;

// Mirrors the AccessGrant domain record. revokedAt is null when the grant
// is still active. accessLevel is a String here (not an enum) to keep the
// API surface stable when future levels are added without forcing a client
// dependency on the enum's exact value set.
public record AccessGrantResponse(
        UUID id,
        UUID grantorId,
        String grantorUsername,
        UUID granteeId,
        String granteeUsername,
        String accessLevel,
        Instant expiresAt,
        Instant revokedAt) {}
