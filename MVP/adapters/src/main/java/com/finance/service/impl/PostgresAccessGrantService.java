package com.finance.service.impl;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.domain.AccessGrant;
import com.finance.entity.AccessGrantEntity;
import com.finance.entity.UserEntity;
import com.finance.exception.GrantNotFoundException;
import com.finance.exception.GranteeNotDiscoverableException;
import com.finance.exception.SelfGrantNotAllowedException;
import com.finance.repository.AccessGrantRepository;
import com.finance.repository.UserRepository;
import com.finance.service.AccessGrantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Runs on the app pool with normal RLS enforcement. The dual-clause RLS policy
// on access_grants (grantor_id = current_user OR grantee_id = current_user)
// scopes visibility to grants the user is party to. See ADR-0011.
@Service
public class PostgresAccessGrantService implements AccessGrantService {

    // v2.0 supports a single access level. CHECK constraint chk_access_level
    // in V24 is the DB backstop; this set is the service-layer pre-check that
    // produces a friendlier error before the DB sees the row.
    private static final Set<String> ALLOWED_ACCESS_LEVELS = Set.of("READ_WRITE");

    // Same 1-30 day window as the rest of the delegation model.
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 30;

    private final AccessGrantRepository grantRepository;
    private final UserRepository        userRepository;
    private final Clock                 clock;

    public PostgresAccessGrantService(AccessGrantRepository grantRepository,
                                      UserRepository userRepository,
                                      Clock clock) {
        this.grantRepository = grantRepository;
        this.userRepository  = userRepository;
        this.clock           = clock;
    }

    @Override
    @Transactional
    public AccessGrant create(CreateAccessGrantCommand command) {
        if (!ALLOWED_ACCESS_LEVELS.contains(command.accessLevel())) {
            throw new IllegalArgumentException(
                    "Unsupported accessLevel: " + command.accessLevel());
        }
        if (command.expiresInDays() < MIN_EXPIRES_IN_DAYS
                || command.expiresInDays() > MAX_EXPIRES_IN_DAYS) {
            throw new IllegalArgumentException(
                    "expiresInDays must be between " + MIN_EXPIRES_IN_DAYS
                    + " and " + MAX_EXPIRES_IN_DAYS);
        }

        UserEntity grantee = userRepository
                .findByUsernameAndIsDiscoverableTrue(command.granteeUsername())
                .orElseThrow(GranteeNotDiscoverableException::new);

        if (command.grantorId().equals(grantee.getId())) {
            throw new SelfGrantNotAllowedException();
        }

        Instant now = Instant.now(clock);
        AccessGrantEntity entity = new AccessGrantEntity();
        entity.setGrantorId(command.grantorId());
        entity.setGranteeId(grantee.getId());
        entity.setAccessLevel(command.accessLevel());
        entity.setExpiresAt(now.plus(command.expiresInDays(), ChronoUnit.DAYS));

        AccessGrantEntity saved = grantRepository.save(entity);

        // Look up grantor's username once for the response. (Could be passed
        // in from the controller via UserPrincipal, but keeping it self-contained
        // means callers don't need to know audit-shaping details.)
        String grantorUsername = userRepository.findById(command.grantorId())
                .map(UserEntity::getUsername)
                .orElseThrow(() -> new IllegalStateException(
                        "grantorId does not resolve to a user — should be impossible "
                                + "given the authenticated UserPrincipal originated it"));

        return toDomain(saved, grantorUsername, grantee.getUsername());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccessGrant> listForUser(UUID userId) {
        List<AccessGrantEntity> grants = grantRepository.findAllVisibleToUser(userId);
        if (grants.isEmpty()) {
            return List.of();
        }

        // One bulk query to resolve all referenced usernames. Avoids the N+1
        // that per-row lookups would cause.
        Set<UUID> referencedUserIds = grants.stream()
                .flatMap(g -> Stream.of(g.getGrantorId(), g.getGranteeId()))
                .collect(Collectors.toSet());
        Map<UUID, String> usernames = userRepository.findAllById(referencedUserIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getUsername));

        return grants.stream()
                .map(g -> toDomain(g,
                        usernames.get(g.getGrantorId()),
                        usernames.get(g.getGranteeId())))
                .toList();
    }

    @Override
    @Transactional
    public void revoke(UUID grantId, UUID requestingUserId) {
        AccessGrantEntity grant = grantRepository
                .findByIdAndPartyTo(grantId, requestingUserId)
                .orElseThrow(GrantNotFoundException::new);

        // Idempotent — already-revoked rows produce a silent no-op.
        if (grant.getRevokedAt() != null) {
            return;
        }
        grant.setRevokedAt(Instant.now(clock));
        grantRepository.save(grant);
    }

    private static AccessGrant toDomain(AccessGrantEntity e,
                                        String grantorUsername,
                                        String granteeUsername) {
        return new AccessGrant(
                e.getId(),
                e.getGrantorId(), grantorUsername,
                e.getGranteeId(), granteeUsername,
                e.getAccessLevel(),
                e.getExpiresAt(),
                e.getRevokedAt());
    }
}
