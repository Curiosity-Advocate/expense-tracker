package com.finance.repository;

import com.finance.entity.AccessGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessGrantRepository extends JpaRepository<AccessGrantEntity, UUID> {

    // Every grant the user is party to (grantor OR grantee). RLS on
    // access_grants enforces the same filter at the DB layer; the explicit
    // WHERE here is the Layer-1 defence per ADR-0011.
    @Query("SELECT ag FROM AccessGrantEntity ag "
            + "WHERE ag.grantorId = :userId OR ag.granteeId = :userId "
            + "ORDER BY ag.expiresAt DESC")
    List<AccessGrantEntity> findAllVisibleToUser(@Param("userId") UUID userId);

    // Revoke path. Three-layer pattern: service signature passes userId,
    // this WHERE filters by party, RLS at the DB rejects independently.
    @Query("SELECT ag FROM AccessGrantEntity ag "
            + "WHERE ag.id = :id "
            + "  AND (ag.grantorId = :userId OR ag.granteeId = :userId)")
    Optional<AccessGrantEntity> findByIdAndPartyTo(@Param("id") UUID id,
                                                   @Param("userId") UUID userId);
}
