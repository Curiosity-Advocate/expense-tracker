package com.finance.integration;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.AccessGrant;
import com.finance.domain.RegisteredUser;
import com.finance.exception.GrantNotFoundException;
import com.finance.exception.GranteeNotDiscoverableException;
import com.finance.exception.SelfGrantNotAllowedException;
import com.finance.service.AccessGrantService;
import com.finance.service.AuthService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired @Qualifier("appDataSource") HikariDataSource appDataSource;
    @Autowired @Qualifier("appTransactionManager") PlatformTransactionManager appTxManager;

    private JdbcTemplate appJdbc;
    private TransactionTemplate appTx;

    @BeforeEach
    void wipe() {
        appJdbc = new JdbcTemplate(appDataSource);
        appTx   = new TransactionTemplate(appTxManager);
        // TRUNCATE via the setup pool (bypasses RLS, not subject to it anyway).
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, access_grants, users RESTART IDENTITY CASCADE");
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

    // Run a service call as the given user: SET LOCAL app.current_user_id within
    // an outer transaction so it shares the connection the service's @Transactional
    // joins (the RlsSessionAspect skips, since there's no SecurityContext, leaving
    // our value in place). Same pattern as AuditTrailIntegrationTest.
    //
    // For tests that expect the service to throw, put assertThatThrownBy OUTSIDE
    // runAs: the exception propagates out of the callback and TransactionTemplate
    // rolls back and re-throws IT (not UnexpectedRollbackException, which only
    // happens when the callback returns normally on a rollback-only tx).
    private void runAs(UUID userId, Runnable body) {
        appTx.executeWithoutResult(status -> {
            appJdbc.execute("SET LOCAL app.current_user_id = '" + userId + "'");
            body.run();
        });
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

        assertThatThrownBy(() -> runAs(userId, () -> accessGrantService.create(
                new CreateAccessGrantCommand(userId, "carol", READ_WRITE, 7))))
                .isInstanceOf(SelfGrantNotAllowedException.class);
    }

    @Test
    void create_granteeNotDiscoverable_throws() {
        UUID grantor = register("dave");
        register("eve");  // exists but is_discoverable = FALSE (default)

        assertThatThrownBy(() -> runAs(grantor, () -> accessGrantService.create(
                new CreateAccessGrantCommand(grantor, "eve", READ_WRITE, 7))))
                .isInstanceOf(GranteeNotDiscoverableException.class);
    }

    @Test
    void create_granteeDoesNotExist_throwsSameAsNotDiscoverable() {
        UUID grantor = register("frank");

        assertThatThrownBy(() -> runAs(grantor, () -> accessGrantService.create(
                new CreateAccessGrantCommand(grantor, "no-such-user", READ_WRITE, 7))))
                .isInstanceOf(GranteeNotDiscoverableException.class);
    }

    @Test
    void create_expiresInDaysBelowMin_throws() {
        UUID grantor = register("grace");
        registerDiscoverable("hal");

        assertThatThrownBy(() -> runAs(grantor, () -> accessGrantService.create(
                new CreateAccessGrantCommand(grantor, "hal", READ_WRITE, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_expiresInDaysAboveMax_throws() {
        UUID grantor = register("ian");
        registerDiscoverable("jan");

        assertThatThrownBy(() -> runAs(grantor, () -> accessGrantService.create(
                new CreateAccessGrantCommand(grantor, "jan", READ_WRITE, 31))))
                .isInstanceOf(IllegalArgumentException.class);
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

        assertThatThrownBy(() -> runAs(outside, () -> accessGrantService.revoke(grantId, outside)))
                .isInstanceOf(GrantNotFoundException.class);
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

        assertThatThrownBy(() -> runAs(userId, () -> accessGrantService.revoke(UUID.randomUUID(), userId)))
                .isInstanceOf(GrantNotFoundException.class);
    }

    // Value-returning variant of runAs (same SET LOCAL-in-transaction approach).
    private <T> T runAsReturning(UUID userId, Supplier<T> body) {
        return appTx.execute(status -> {
            appJdbc.execute("SET LOCAL app.current_user_id = '" + userId + "'");
            return body.get();
        });
    }
}
