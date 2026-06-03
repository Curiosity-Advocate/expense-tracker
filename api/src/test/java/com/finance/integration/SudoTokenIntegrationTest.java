package com.finance.integration;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.command.CreateSudoTokenCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.AccessGrant;
import com.finance.domain.SudoToken;
import com.finance.domain.SudoTokenVerification;
import com.finance.exception.GrantNotUsableException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.InvalidSudoTokenException;
import com.finance.security.SecureTokenGenerator;
import com.finance.service.AccessGrantService;
import com.finance.service.AuthService;
import com.finance.service.SudoTokenService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SudoTokenIntegrationTest extends IntegrationTestBase {

    private static final String READ_WRITE = "READ_WRITE";
    private static final String CORRECT_PW = "pw_correct";
    private static final String SET_USER_PREFIX = "SET LOCAL app.current_user_id = '";

    @Autowired AuthService authService;
    @Autowired AccessGrantService accessGrantService;
    @Autowired SudoTokenService sudoTokenService;
    @Autowired SecureTokenGenerator tokenGenerator;
    @Autowired @Qualifier("appDataSource") HikariDataSource appDataSource;
    @Autowired @Qualifier("appTransactionManager") PlatformTransactionManager appTxManager;

    private JdbcTemplate appJdbc;
    private TransactionTemplate appTx;

    @BeforeEach
    void wipe() {
        appJdbc = new JdbcTemplate(appDataSource);
        appTx   = new TransactionTemplate(appTxManager);
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, sudo_tokens, "
                + "access_grants, users RESTART IDENTITY CASCADE");
    }

    private UUID register(String username) {
        return authService.register(
                new RegisterCommand(username, username + "@x.com", CORRECT_PW)).userId();
    }

    private UUID registerDiscoverable(String username) {
        UUID id = register(username);
        setupJdbc().update("UPDATE users SET is_discoverable = TRUE WHERE id = ?", id);
        return id;
    }

    private void runAs(UUID userId, Runnable body) {
        appTx.executeWithoutResult(status -> {
            appJdbc.execute(SET_USER_PREFIX + userId + "'");
            body.run();
        });
    }

    private <T> T runAsReturning(UUID userId, Supplier<T> body) {
        return appTx.execute(status -> {
            appJdbc.execute(SET_USER_PREFIX + userId + "'");
            return body.get();
        });
    }

    // Helper: create a grant where grantor delegates to grantee, return grant id.
    private UUID createGrant(UUID grantor, String granteeUsername) {
        return runAsReturning(grantor, () ->
                accessGrantService.create(
                        new CreateAccessGrantCommand(grantor, granteeUsername, READ_WRITE, 7)).id());
    }

    // ── create — happy path ──────────────────────────────────────────────────

    @Test
    void create_happyPath_returnsSudoTokenWithExpiry_andRowAppearsInDb() {
        UUID grantor = register("alice");
        UUID grantee = registerDiscoverable("bob");
        UUID grantId = createGrant(grantor, "bob");

        SudoToken token = runAsReturning(grantee, () ->
                sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)));

        assertThat(token.rawToken()).isNotBlank();
        assertThat(token.expiresAt()).isAfter(Instant.now());

        String hash = tokenGenerator.hash(token.rawToken());
        Integer count = setupJdbc().queryForObject(
                "SELECT COUNT(*) FROM sudo_tokens WHERE token_hash = ?", Integer.class, hash);
        assertThat(count).isEqualTo(1);
    }

    // ── create — failure modes ───────────────────────────────────────────────

    @Test
    void create_wrongPassword_throwsInvalidCredentials() {
        UUID grantor = register("carol");
        UUID grantee = registerDiscoverable("dave");
        UUID grantId = createGrant(grantor, "dave");

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, "wrong_password")))
                        .isInstanceOf(InvalidCredentialsException.class));
    }

    @Test
    void create_unknownGrant_throwsGrantNotUsable() {
        UUID grantee = register("eve");

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, UUID.randomUUID(), CORRECT_PW)))
                        .isInstanceOf(GrantNotUsableException.class));
    }

    @Test
    void create_grantNotOwnedByGrantee_throwsGrantNotUsable() {
        UUID grantor = register("frank");
        registerDiscoverable("grace");
        UUID outsider = register("hal");
        UUID grantId = createGrant(grantor, "grace");

        runAs(outsider, () ->
                assertThatThrownBy(() -> sudoTokenService.create(
                        new CreateSudoTokenCommand(outsider, grantId, CORRECT_PW)))
                        .isInstanceOf(GrantNotUsableException.class));
    }

    @Test
    void create_grantAlreadyRevoked_throwsGrantNotUsable() {
        UUID grantor = register("ian");
        UUID grantee = registerDiscoverable("jan");
        UUID grantId = createGrant(grantor, "jan");

        runAs(grantor, () -> accessGrantService.revoke(grantId, grantor));

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)))
                        .isInstanceOf(GrantNotUsableException.class));
    }

    @Test
    void create_grantExpired_throwsGrantNotUsable() {
        UUID grantor = register("kate");
        UUID grantee = registerDiscoverable("liam");
        UUID grantId = createGrant(grantor, "liam");

        // Backdate the grant's expiry directly. lock_created_at and the audit
        // triggers don't block expires_at updates.
        Instant longAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        runAs(grantor, () ->
                appJdbc.update("UPDATE access_grants SET expires_at = ? WHERE id = ?",
                        Timestamp.from(longAgo), grantId));

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)))
                        .isInstanceOf(GrantNotUsableException.class));
    }

    // ── verify — happy path ──────────────────────────────────────────────────

    @Test
    void verify_happyPath_returnsCorrectVerification() {
        UUID grantor = register("mia");
        UUID grantee = registerDiscoverable("noah");
        UUID grantId = createGrant(grantor, "noah");

        SudoToken token = runAsReturning(grantee, () ->
                sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)));

        SudoTokenVerification verification = runAsReturning(grantee, () ->
                sudoTokenService.verify(token.rawToken(), grantee));

        assertThat(verification.grantId()).isEqualTo(grantId);
        assertThat(verification.grantorId()).isEqualTo(grantor);
        assertThat(verification.granteeId()).isEqualTo(grantee);
    }

    // ── verify — failure modes ───────────────────────────────────────────────

    @Test
    void verify_unknownToken_throwsInvalidSudoToken() {
        UUID userId = register("olive");

        runAs(userId, () ->
                assertThatThrownBy(() ->
                        sudoTokenService.verify(tokenGenerator.generate().rawToken(), userId))
                        .isInstanceOf(InvalidSudoTokenException.class));
    }

    @Test
    void verify_nullOrBlankToken_throwsInvalidSudoToken() {
        UUID userId = register("pia");

        runAs(userId, () -> {
            assertThatThrownBy(() -> sudoTokenService.verify(null, userId))
                    .isInstanceOf(InvalidSudoTokenException.class);
            assertThatThrownBy(() -> sudoTokenService.verify("", userId))
                    .isInstanceOf(InvalidSudoTokenException.class);
            assertThatThrownBy(() -> sudoTokenService.verify("   ", userId))
                    .isInstanceOf(InvalidSudoTokenException.class);
        });
    }

    @Test
    void verify_expiredToken_throwsInvalidSudoToken() {
        UUID grantor = register("quinn");
        UUID grantee = registerDiscoverable("rita");
        createGrant(grantor, "rita");

        // Direct INSERT with a backdated expires_at — bypasses the service's
        // 15-min default. INSERT isn't subject to the no-update lock so the
        // values land as specified.
        var raw = tokenGenerator.generate();
        UUID grantId = setupJdbc().queryForObject(
                "SELECT id FROM access_grants WHERE grantor_id = ?", UUID.class, grantor);
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        runAs(grantee, () ->
                appJdbc.update(
                        "INSERT INTO sudo_tokens (token_hash, grant_id, grantee_id, expires_at) "
                                + "VALUES (?, ?, ?, ?)",
                        raw.hash(), grantId, grantee, Timestamp.from(longAgo)));

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.verify(raw.rawToken(), grantee))
                        .isInstanceOf(InvalidSudoTokenException.class));
    }

    @Test
    void verify_grantRevokedAfterIssuance_throwsInvalidSudoToken() {
        UUID grantor = register("sam");
        UUID grantee = registerDiscoverable("tess");
        UUID grantId = createGrant(grantor, "tess");

        SudoToken token = runAsReturning(grantee, () ->
                sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)));

        // Grantor revokes the grant AFTER the sudo token was issued.
        runAs(grantor, () -> accessGrantService.revoke(grantId, grantor));

        runAs(grantee, () ->
                assertThatThrownBy(() -> sudoTokenService.verify(token.rawToken(), grantee))
                        .isInstanceOf(InvalidSudoTokenException.class));
    }

    @Test
    void verify_wrongGranteeId_throwsInvalidSudoToken() {
        UUID grantor = register("uma");
        UUID grantee = registerDiscoverable("vic");
        UUID outsider = register("wendy");
        UUID grantId = createGrant(grantor, "vic");

        SudoToken token = runAsReturning(grantee, () ->
                sudoTokenService.create(
                        new CreateSudoTokenCommand(grantee, grantId, CORRECT_PW)));

        // Outsider tries to verify the grantee's sudo token. RLS hides
        // the row from them; the service-level granteeId filter also
        // rejects independently.
        runAs(outsider, () ->
                assertThatThrownBy(() -> sudoTokenService.verify(token.rawToken(), outsider))
                        .isInstanceOf(InvalidSudoTokenException.class));
    }
}
