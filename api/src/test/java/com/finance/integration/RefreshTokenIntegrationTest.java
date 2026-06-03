package com.finance.integration;

import com.finance.command.LoginCommand;
import com.finance.command.RefreshTokenCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.exception.InvalidRefreshTokenException;
import com.finance.exception.RefreshTokenReuseException;
import com.finance.security.SecureTokenGenerator;
import com.finance.security.SecureTokenGenerator.GeneratedToken;
import com.finance.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenIntegrationTest extends IntegrationTestBase {

    @Autowired AuthService authService;
    @Autowired SecureTokenGenerator tokenGenerator;
    @Autowired NamedParameterJdbcTemplate setupJdbcTemplate;

    @BeforeEach
    void wipe() {
        // ON DELETE CASCADE on refresh_tokens.user_id wipes refresh rows alongside.
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, users RESTART IDENTITY CASCADE");
    }

    private TokenPair registerAndLogin(String username) {
        authService.register(new RegisterCommand(username, username + "@x.com", "pw_correct"));
        return authService.login(new LoginCommand(username, "pw_correct"));
    }

    // ── refresh — happy path ─────────────────────────────────────────────────

    @Test
    void refresh_happyPath_rotatesToNewPair_andOldTokenBecomesUnusable() {
        TokenPair first  = registerAndLogin("alice");
        TokenPair second = authService.refresh(new RefreshTokenCommand(first.refreshToken()));

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // Reusing the first refresh token now triggers reuse detection.
        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(first.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void refresh_chainOfRotations_preservesSessionStartedAtAndExpiresAt() {
        TokenPair pair = registerAndLogin("bob");
        UUID userId = setupJdbc().queryForObject(
                "SELECT id FROM users WHERE username = 'bob'", UUID.class);

        Instant originalSessionStartedAt = setupJdbc().queryForObject(
                "SELECT session_started_at FROM refresh_tokens WHERE user_id = ? AND rotated_from IS NULL",
                (rs, n) -> rs.getTimestamp("session_started_at").toInstant(), userId);
        Instant originalExpiresAt = setupJdbc().queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE user_id = ? AND rotated_from IS NULL",
                (rs, n) -> rs.getTimestamp("expires_at").toInstant(), userId);

        // Rotate a few times.
        for (int i = 0; i < 3; i++) {
            pair = authService.refresh(new RefreshTokenCommand(pair.refreshToken()));
        }

        Instant latestSessionStartedAt = setupJdbc().queryForObject(
                "SELECT session_started_at FROM refresh_tokens " +
                "WHERE user_id = ? AND revoked_at IS NULL",
                (rs, n) -> rs.getTimestamp("session_started_at").toInstant(), userId);
        Instant latestExpiresAt = setupJdbc().queryForObject(
                "SELECT expires_at FROM refresh_tokens " +
                "WHERE user_id = ? AND revoked_at IS NULL",
                (rs, n) -> rs.getTimestamp("expires_at").toInstant(), userId);

        // session_started_at copied unchanged across rotations.
        assertThat(latestSessionStartedAt).isEqualTo(originalSessionStartedAt);
        // expires_at also unchanged — chain cannot extend past the original window.
        assertThat(latestExpiresAt).isEqualTo(originalExpiresAt);
    }

    @Test
    void refresh_rotatedFromChain_pointsAtPredecessor() {
        TokenPair first  = registerAndLogin("carol");
        TokenPair second = authService.refresh(new RefreshTokenCommand(first.refreshToken()));

        String firstHash  = tokenGenerator.hash(first.refreshToken());
        String secondHash = tokenGenerator.hash(second.refreshToken());

        String rotatedFrom = setupJdbc().queryForObject(
                "SELECT rotated_from FROM refresh_tokens WHERE token_hash = ?",
                String.class, secondHash);
        assertThat(rotatedFrom).isEqualTo(firstHash);
    }

    // ── refresh — failure modes ───────────────────────────────────────────────

    @Test
    void refresh_withRotatedToken_triggersReuseDetection_andRevokesActiveChain() {
        TokenPair first  = registerAndLogin("dave");
        TokenPair second = authService.refresh(new RefreshTokenCommand(first.refreshToken()));

        // Replay the rotated token.
        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(first.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);

        // The currently-active second token is now revoked by the cascade.
        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(second.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void refresh_withExpiredToken_throwsInvalid() {
        RegisteredUser user = authService.register(
                new RegisterCommand("eve", "eve@x.com", "pw_correct"));

        // Synthesise an expired chain link directly (the immutability trigger
        // forbids UPDATEs to session_started_at/expires_at; INSERTs are fine).
        GeneratedToken expired = tokenGenerator.generate();
        Instant longAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        Instant aDayAgo = longAgo.plus(7, ChronoUnit.DAYS); // = 1 day ago, past expiry

        setupJdbcTemplate.update(
                "INSERT INTO refresh_tokens " +
                "(token_hash, user_id, session_started_at, expires_at, rotated_from) " +
                "VALUES (:tokenHash, :userId, :sessionStartedAt, :expiresAt, NULL)",
                new MapSqlParameterSource()
                        .addValue("tokenHash",        expired.hash())
                        .addValue("userId",           user.userId())
                        .addValue("sessionStartedAt", Timestamp.from(longAgo))
                        .addValue("expiresAt",        Timestamp.from(aDayAgo)));

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(expired.rawToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_withUnknownToken_throwsInvalid() {
        // Random 32-byte token that's never been issued.
        GeneratedToken unknown = tokenGenerator.generate();

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(unknown.rawToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ── multi-chain semantics ─────────────────────────────────────────────────

    @Test
    void login_calledTwice_createsTwoParallelChains() {
        authService.register(new RegisterCommand("frank", "frank@x.com", "pw_correct"));

        TokenPair phone  = authService.login(new LoginCommand("frank", "pw_correct"));
        TokenPair laptop = authService.login(new LoginCommand("frank", "pw_correct"));

        UUID userId = setupJdbc().queryForObject(
                "SELECT id FROM users WHERE username = 'frank'", UUID.class);
        Integer chainStarts = setupJdbc().queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens " +
                "WHERE user_id = ? AND rotated_from IS NULL AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(chainStarts).isEqualTo(2);

        // Both chains rotate independently.
        TokenPair phoneAfter  = authService.refresh(new RefreshTokenCommand(phone.refreshToken()));
        TokenPair laptopAfter = authService.refresh(new RefreshTokenCommand(laptop.refreshToken()));
        assertThat(phoneAfter.refreshToken()).isNotEqualTo(laptopAfter.refreshToken());
    }

    @Test
    void reuseDetection_cascadeRevokesAllUsersActiveChains() {
        authService.register(new RegisterCommand("grace", "grace@x.com", "pw_correct"));
        TokenPair phone  = authService.login(new LoginCommand("grace", "pw_correct"));
        TokenPair laptop = authService.login(new LoginCommand("grace", "pw_correct"));

        // Rotate phone once so its first token is replayable.
        authService.refresh(new RefreshTokenCommand(phone.refreshToken()));

        // Replay the rotated phone token → reuse detected → cascade.
        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(phone.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);

        // Laptop's currently-active token is also revoked by the cascade.
        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(laptop.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_happyPath_revokesPresentedToken_subsequentRefreshTriggersReuse() {
        TokenPair pair = registerAndLogin("hank");

        authService.logout(pair.refreshToken());

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenCommand(pair.refreshToken())))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void logout_withStaleToken_silentNoOp_activeChainKeepsWorking() {
        TokenPair first  = registerAndLogin("ivy");
        TokenPair second = authService.refresh(new RefreshTokenCommand(first.refreshToken()));

        // Logout with the now-stale first token. Should be a silent no-op.
        authService.logout(first.refreshToken());  // does not throw

        // Active token still works.
        TokenPair third = authService.refresh(new RefreshTokenCommand(second.refreshToken()));
        assertThat(third.refreshToken()).isNotBlank();
    }

    @Test
    void logout_withUnknownToken_silentNoOp() {
        registerAndLogin("jack");
        GeneratedToken random = tokenGenerator.generate();

        authService.logout(random.rawToken());  // does not throw
    }

    @Test
    void logout_withNullOrBlank_silentNoOp() {
        registerAndLogin("kate");

        authService.logout(null);
        authService.logout("");
        authService.logout("   ");
    }
}
