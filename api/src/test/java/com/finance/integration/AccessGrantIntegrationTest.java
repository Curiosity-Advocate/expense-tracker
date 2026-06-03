package com.finance.integration;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.AccessGrant;
import com.finance.domain.RegisteredUser;
import com.finance.exception.GrantNotFoundException;
import com.finance.exception.GranteeNotDiscoverableException;
import com.finance.exception.SelfGrantNotAllowedException;
import com.finance.domain.UserPrincipal;
import com.finance.service.AccessGrantService;
import com.finance.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessGrantIntegrationTest extends IntegrationTestBase {

    private static final String READ_WRITE = "READ_WRITE";

    @Autowired AuthService authService;
    @Autowired AccessGrantService accessGrantService;

    @BeforeEach
    void wipe() {
        // TRUNCATE via the setup pool (bypasses RLS, not subject to it anyway).
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, access_grants, users RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // Helpers — register a user, optionally mark as discoverable. is_discoverable
    // defaults to FALSE on registration, so the grantee branch needs explicit toggling.
    private UUID register(String username) {
        RegisteredUser u = authService.register(
                new RegisterCommand(username, username + "@x.com", "pw_correct"));
        return u.userId();
    }

    private UUID registerDiscoverable(String username) {
        UUID id = register(username);
        setupJdbc().update("UPDATE users SET is_discoverable = TRUE WHERE id = ?", id);
        return id;
    }

    // Run a service call as the given user. Instead of opening an outer
    // transaction and SET LOCAL-ing (which broke when the inner @Transactional
    // service threw — the outer tx became rollback-only → UnexpectedRollbackException),
    // this populates the SecurityContext so the service's own @Transactional +
    // RlsSessionAspect set app.current_user_id — exactly like a real HTTP request.
    private void runAs(UUID userId, Runnable body) {
        authenticateAs(userId);
        body.run();
    }

    private void authenticateAs(UUID userId) {
        UserPrincipal principal = UserPrincipal.of(userId, "user-" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_happyPath_returnsGrantWithUsernames() {
        UUID grantor = register("alice");
        UUID grantee = registerDiscoverable("bob");

        runAs(grantor, () -> {
            AccessGrant g = accessGrantService.create(
                    new CreateAccessGrantCommand(grantor, "bob", READ_WRITE, 7));

            assertThat(g.grantorId()).isEqualTo(grantor);
            assertThat(g.grantorUsername()).isEqualTo("alice");
            assertThat(g.granteeId()).isEqualTo(grantee);
            assertThat(g.granteeUsername()).isEqualTo("bob");
            assertThat(g.accessLevel()).isEqualTo(READ_WRITE);
            assertThat(g.expiresAt()).isAfter(Instant.now());
            assertThat(g.revokedAt()).isNull();
        });
    }

    @Test
    void create_selfGrant_throws() {
        UUID userId = registerDiscoverable("carol");

        runAs(userId, () ->
                assertThatThrownBy(() -> accessGrantService.create(
                        new CreateAccessGrantCommand(userId, "carol", READ_WRITE, 7)))
                        .isInstanceOf(SelfGrantNotAllowedException.class));
    }

    @Test
    void create_granteeNotDiscoverable_throws() {
        UUID grantor = register("dave");
        register("eve");  // exists but is_discoverable = FALSE (default)

        runAs(grantor, () ->
                assertThatThrownBy(() -> accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "eve", READ_WRITE, 7)))
                        .isInstanceOf(GranteeNotDiscoverableException.class));
    }

    @Test
    void create_granteeDoesNotExist_throwsSameAsNotDiscoverable() {
        UUID grantor = register("frank");

        runAs(grantor, () ->
                assertThatThrownBy(() -> accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "no-such-user", READ_WRITE, 7)))
                        .isInstanceOf(GranteeNotDiscoverableException.class));
    }

    @Test
    void create_expiresInDaysBelowMin_throws() {
        UUID grantor = register("grace");
        registerDiscoverable("hal");

        runAs(grantor, () ->
                assertThatThrownBy(() -> accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "hal", READ_WRITE, 0)))
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void create_expiresInDaysAboveMax_throws() {
        UUID grantor = register("ian");
        registerDiscoverable("jan");

        runAs(grantor, () ->
                assertThatThrownBy(() -> accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "jan", READ_WRITE, 31)))
                        .isInstanceOf(IllegalArgumentException.class));
    }

    // ── list — RLS dual-clause ───────────────────────────────────────────────

    @Test
    void list_returnsGrantsForBothGrantorAndGrantee() {
        UUID grantor = register("kate");
        UUID grantee = registerDiscoverable("liam");

        runAs(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "liam", READ_WRITE, 7)));

        runAs(grantor, () -> {
            List<AccessGrant> asGrantor = accessGrantService.listForUser(grantor);
            assertThat(asGrantor).hasSize(1);
        });

        runAs(grantee, () -> {
            List<AccessGrant> asGrantee = accessGrantService.listForUser(grantee);
            assertThat(asGrantee).hasSize(1);
        });
    }

    @Test
    void list_doesNotShowOtherUsersGrants() {
        UUID partyA  = register("mia");
        UUID partyB  = registerDiscoverable("noah");
        UUID outside = register("olive");

        runAs(partyA, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(partyA, "noah", READ_WRITE, 7)));

        runAs(outside, () -> {
            List<AccessGrant> visible = accessGrantService.listForUser(outside);
            assertThat(visible).isEmpty();
        });
    }

    // ── revoke ───────────────────────────────────────────────────────────────

    @Test
    void revoke_byGrantor_setsRevokedAt() {
        UUID grantor = register("pia");
        registerDiscoverable("quinn");

        UUID grantId = runAsReturning(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "quinn", READ_WRITE, 7)).id());

        runAs(grantor, () -> accessGrantService.revoke(grantId, grantor));

        Instant revokedAt = setupJdbc().queryForObject(
                "SELECT revoked_at FROM access_grants WHERE id = ?",
                (rs, n) -> rs.getTimestamp("revoked_at").toInstant(),
                grantId);
        assertThat(revokedAt).isNotNull();
    }

    @Test
    void revoke_byGrantee_setsRevokedAt() {
        UUID grantor = register("rita");
        UUID grantee = registerDiscoverable("sam");

        UUID grantId = runAsReturning(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "sam", READ_WRITE, 7)).id());

        // Grantee can revoke their own delegation too — "I don't want this access".
        runAs(grantee, () -> accessGrantService.revoke(grantId, grantee));

        Instant revokedAt = setupJdbc().queryForObject(
                "SELECT revoked_at FROM access_grants WHERE id = ?",
                (rs, n) -> rs.getTimestamp("revoked_at").toInstant(),
                grantId);
        assertThat(revokedAt).isNotNull();
    }

    @Test
    void revoke_byNonParty_throwsGrantNotFound() {
        UUID grantor = register("tess");
        registerDiscoverable("uma");
        UUID outside = register("vic");

        UUID grantId = runAsReturning(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "uma", READ_WRITE, 7)).id());

        runAs(outside, () ->
                assertThatThrownBy(() -> accessGrantService.revoke(grantId, outside))
                        .isInstanceOf(GrantNotFoundException.class));
    }

    @Test
    void revoke_alreadyRevoked_silentNoOp() {
        UUID grantor = register("wendy");
        registerDiscoverable("xavier");

        UUID grantId = runAsReturning(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, "xavier", READ_WRITE, 7)).id());

        runAs(grantor, () -> accessGrantService.revoke(grantId, grantor));
        // Second revoke must not throw.
        runAs(grantor, () -> accessGrantService.revoke(grantId, grantor));
    }

    @Test
    void revoke_unknownGrantId_throwsGrantNotFound() {
        UUID userId = register("yvonne");

        runAs(userId, () ->
                assertThatThrownBy(() ->
                        accessGrantService.revoke(UUID.randomUUID(), userId))
                        .isInstanceOf(GrantNotFoundException.class));
    }

    // Value-returning variant of runAs (see runAs for why this uses the
    // SecurityContext rather than an outer transaction).
    private <T> T runAsReturning(UUID userId, Supplier<T> body) {
        authenticateAs(userId);
        return body.get();
    }
}
