package com.finance.repository;

import com.finance.entity.SudoTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SudoTokenRepository extends JpaRepository<SudoTokenEntity, String> {

    // Used by SudoTokenService.verify(). The (granteeId, expiresAt) filters
    // are Layer-1 defence (ADR-0011) on top of the RLS policy that already
    // scopes to grantee_id at the DB. The grant's current state is checked
    // by the service via AccessGrantRepository — kept as a separate lookup
    // rather than a JOIN so the codebase pattern stays consistent.
    Optional<SudoTokenEntity> findByTokenHashAndGranteeIdAndExpiresAtAfter(
            String tokenHash, UUID granteeId, Instant now);
}
