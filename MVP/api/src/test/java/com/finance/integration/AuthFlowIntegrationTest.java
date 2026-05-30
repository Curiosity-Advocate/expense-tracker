package com.finance.integration;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.service.AuthService;
import com.finance.service.UserSetupService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Autowired AuthService authService;
    @Autowired UserSetupService userSetupService;
    @Autowired HikariDataSource appDataSource;

    private JdbcTemplate appJdbc;

    @BeforeEach
    void wipeUserState() {
        appJdbc = new JdbcTemplate(appDataSource);
        // Wipe in dependency order. user_login_failures → bank_accounts → users
        // (FK chain). The superuser connection used by Flyway disables RLS for
        // DELETE; we connect as expense_app here which has DELETE permission
        // but RLS-restricted reads. DELETE with no WHERE bypasses RLS for the
        // privilege check on PUBLIC role; we use TRUNCATE CASCADE for clarity.
        appJdbc.execute("SET LOCAL app.current_user_id = '00000000-0000-0000-0000-000000000000'");
        appJdbc.execute("TRUNCATE user_login_failures, bank_accounts, users RESTART IDENTITY CASCADE");
    }

    @Test
    void register_persistsUser_andSetupCreatesSystemBankAccounts() {
        RegisteredUser created = authService.register(
                new RegisterCommand("alice", "alice@example.com", "password123"));
        userSetupService.setupNewUser(created.userId());

        Integer userCount = appJdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, created.userId());
        assertThat(userCount).isEqualTo(1);

        var accountNames = appJdbc.queryForList(
                "SELECT name FROM bank_accounts WHERE user_id = ? ORDER BY name",
                String.class, created.userId());
        assertThat(accountNames).containsExactly("Cash", "Crypto");
    }

    @Test
    void register_duplicateUsername_throwsUserAlreadyExists() {
        authService.register(new RegisterCommand("bob", "bob@example.com", "password123"));

        assertThatThrownBy(() -> authService.register(
                new RegisterCommand("bob", "bob2@example.com", "password123")))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void register_duplicateEmail_throwsUserAlreadyExists() {
        authService.register(new RegisterCommand("carol", "carol@example.com", "password123"));

        assertThatThrownBy(() -> authService.register(
                new RegisterCommand("carol2", "carol@example.com", "password123")))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void login_happyPath_returnsAccessAndRefreshTokenPair() {
        authService.register(new RegisterCommand("dave", "dave@example.com", "correct_password"));

        TokenPair pair = authService.login(new LoginCommand("dave", "correct_password"));

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.accessTokenExpiresAt()).isAfter(Instant.now());
        assertThat(pair.refreshTokenExpiresAt()).isAfter(pair.accessTokenExpiresAt());
        assertThat(pair.tokenType()).isEqualTo("Bearer");

        // Refresh token row landed with rotated_from = NULL (start of chain).
        Integer rowCount = appJdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens " +
                "WHERE user_id IN (SELECT id FROM users WHERE username = 'dave') " +
                "  AND rotated_from IS NULL AND revoked_at IS NULL",
                Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials_recordsFailureRow() {
        RegisteredUser user = authService.register(
                new RegisterCommand("eve", "eve@example.com", "correct"));

        assertThatThrownBy(() -> authService.login(new LoginCommand("eve", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        Integer failureCount = appJdbc.queryForObject(
                "SELECT COUNT(*) FROM user_login_failures WHERE user_id = ?",
                Integer.class, user.userId());
        assertThat(failureCount).isEqualTo(1);
    }

    @Test
    void login_fifthFailureInWindow_locksAccount() {
        RegisteredUser user = authService.register(
                new RegisterCommand("frank", "frank@example.com", "correct"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginCommand("frank", "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        Instant lockedUntil = appJdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?",
                (rs, n) -> rs.getTimestamp("locked_until").toInstant(),
                user.userId());
        assertThat(lockedUntil).isAfter(Instant.now());

        // Sixth attempt is rejected because the account is locked, regardless
        // of password correctness.
        assertThatThrownBy(() -> authService.login(new LoginCommand("frank", "correct")))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void login_fourFailuresInWindow_doesNotLock() {
        RegisteredUser user = authService.register(
                new RegisterCommand("grace", "grace@example.com", "correct"));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginCommand("grace", "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        Instant lockedUntil = appJdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?",
                (rs, n) -> {
                    var ts = rs.getTimestamp("locked_until");
                    return ts == null ? null : ts.toInstant();
                },
                user.userId());
        assertThat(lockedUntil).isNull();
    }

    @Test
    void login_expiredLockout_isClearedOnNextAttempt() {
        RegisteredUser user = authService.register(
                new RegisterCommand("hank", "hank@example.com", "correct"));

        // Backdate the lockout to a minute ago. expense_app has UPDATE on users.
        appJdbc.update(
                "UPDATE users SET locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
                user.userId());

        TokenPair pair = authService.login(new LoginCommand("hank", "correct"));
        assertThat(pair.accessToken()).isNotBlank();

        Instant lockedUntilAfter = appJdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?",
                (rs, n) -> {
                    var ts = rs.getTimestamp("locked_until");
                    return ts == null ? null : ts.toInstant();
                },
                user.userId());
        assertThat(lockedUntilAfter).isNull();
    }

    @Test
    void login_nonExistentUser_throwsInvalidCredentials() {
        assertThatThrownBy(() -> authService.login(
                new LoginCommand("does-not-exist-" + UUID.randomUUID(), "anything")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
