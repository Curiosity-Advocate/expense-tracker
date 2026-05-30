package com.finance.domain;

import java.time.Instant;
import java.util.UUID;

// Returned by AccessGrantService queries. Usernames are denormalised from
// the JOIN with users so the controller can map straight to the response DTO
// without a second round-trip. revokedAt = null means the grant is active.
public record AccessGrant(
        UUID id,
        UUID grantorId,
        String grantorUsername,
        UUID granteeId,
        String granteeUsername,
        String accessLevel,
        Instant expiresAt,
        Instant revokedAt) {}
