package com.finance.repository;

import com.finance.entity.RevokedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface RevokedTokenRepository extends JpaRepository<RevokedTokenEntity, UUID> {
    boolean existsByTokenJti(UUID tokenJti);
    void deleteAllByExpiresAtBefore(Instant cutoff);  // used by nightly cleanup job
}
