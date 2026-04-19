package com.finance.repository;

import com.finance.entity.RevokedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface RevokedTokenRepository extends JpaRepository<RevokedTokenEntity, UUID> {

    // Used by the gateway filter on every authenticated request.
    // If a jti is found here, the token has been revoked — reject the request.
    boolean existsByTokenJti(String tokenJti);

    // Used by the nightly cleanup job.
    // Tokens past their expiry are dead weight — they'd be rejected by JJWT anyway.
    void deleteAllByExpiresAtBefore(Instant cutoff);
}