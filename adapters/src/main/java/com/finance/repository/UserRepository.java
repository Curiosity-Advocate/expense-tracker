package com.finance.repository;

import com.finance.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsernameOrEmail(String username, String email);

    // Cross-user delegation lookups. The users RLS policy exposes only the
    // caller's own row, so these go through SECURITY DEFINER functions (V33)
    // that bypass RLS and return ONLY the id / username — never sensitive
    // columns. Used by AccessGrantService for grantee discovery and counterparty
    // username resolution.
    @Query(value = "SELECT user_id FROM find_discoverable_user(:username)", nativeQuery = true)
    Optional<UUID> findDiscoverableUserId(@Param("username") String username);

    @Query(value = "SELECT username FROM username_of(:id)", nativeQuery = true)
    Optional<String> resolveUsername(@Param("id") UUID id);
}
