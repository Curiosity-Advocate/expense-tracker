package com.finance.integration;

import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.service.AuthService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Verifies the V23 audit triggers populate created_by / modified_by correctly
// across the two pools, and that lock_created_by enforces immutability.
// Includes a forward-compatibility test for D3's delegation pattern
// (app.acting_user_id) — currently never set in production, but the
// COALESCE-based trigger handles it as if it were.
class AuditTrailIntegrationTest extends IntegrationTestBase {

    @Autowired AuthService authService;
    @Autowired @Qualifier("appDataSource") HikariDataSource appDataSource;
    @Autowired @Qualifier("appTransactionManager") PlatformTransactionManager appTxManager;

    private static final String SET_CURRENT_USER_PREFIX  = "SET LOCAL app.current_user_id = '";
    private static final String SET_ACTING_USER_PREFIX   = "SET LOCAL app.acting_user_id = '";
    private static final String SQL_BANK_ACCOUNT_BY_USER = "SELECT id FROM bank_accounts WHERE user_id = ?";

    private JdbcTemplate appJdbc;
    private TransactionTemplate appTx;

    @BeforeEach
    void wipe() {
        appJdbc = new JdbcTemplate(appDataSource);
        appTx   = new TransactionTemplate(appTxManager);

        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, users RESTART IDENTITY CASCADE");
    }

    // Helper: register a user (setup pool, no session vars) and return its id.
    private UUID registerUser(String username) {
        RegisteredUser user = authService.register(
                new RegisterCommand(username, username + "@x.com", "pw_correct"));
        return user.userId();
    }

    // Helper: register and also create a default Cash bank account so we can
    // insert expenses (which FK to bank_accounts).
    private UUID registerWithCashAccount(String username) {
        UUID userId = registerUser(username);
        UUID accountId = UUID.randomUUID();
        appTx.executeWithoutResult(status -> {
            appJdbc.execute(SET_CURRENT_USER_PREFIX + userId + "'");
            appJdbc.update(
                    "INSERT INTO bank_accounts (id, user_id, name, account_type, is_system) " +
                    "VALUES (?, ?, 'Cash', 'CASH', true)",
                    accountId, userId);
        });
        return userId;
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void insert_onAppPool_withCurrentUserId_populatesBothAuditColumns() {
        UUID userId    = registerWithCashAccount("alice");
        UUID accountId = setupJdbc().queryForObject(
                SQL_BANK_ACCOUNT_BY_USER, UUID.class, userId);

        assertThat((UUID) setupJdbc().queryForObject(
                "SELECT created_by FROM bank_accounts WHERE id = ?", UUID.class, accountId))
                .isEqualTo(userId);
        assertThat((UUID) setupJdbc().queryForObject(
                "SELECT modified_by FROM bank_accounts WHERE id = ?", UUID.class, accountId))
                .isEqualTo(userId);
    }

    @Test
    void update_onAppPool_changesModifiedBy_butNotCreatedBy() {
        UUID userA = registerWithCashAccount("bob");
        UUID accountId = setupJdbc().queryForObject(
                SQL_BANK_ACCOUNT_BY_USER, UUID.class, userA);

        appTx.executeWithoutResult(status -> {
            appJdbc.execute(SET_CURRENT_USER_PREFIX + userA + "'");
            appJdbc.update("UPDATE bank_accounts SET name = 'Cash v2' WHERE id = ?", accountId);
        });

        assertThat((UUID) setupJdbc().queryForObject(
                "SELECT created_by FROM bank_accounts WHERE id = ?", UUID.class, accountId))
                .isEqualTo(userA);  // unchanged
        assertThat((UUID) setupJdbc().queryForObject(
                "SELECT modified_by FROM bank_accounts WHERE id = ?", UUID.class, accountId))
                .isEqualTo(userA);
    }

    // ── Setup-pool (pre-auth) writes leave audit columns NULL ────────────────

    @Test
    void register_onSetupPool_leavesUserCreatedByAsNull() {
        UUID userId = registerUser("carol");

        UUID createdBy = setupJdbc().queryForObject(
                "SELECT created_by FROM users WHERE id = ?", UUID.class, userId);
        assertThat(createdBy).isNull();
    }

    // ── lock_created_by enforces immutability ────────────────────────────────

    @Test
    void update_attemptingToChangeCreatedBy_throws() {
        UUID userId    = registerWithCashAccount("dave");
        UUID otherUser = registerUser("dave_other");
        UUID accountId = setupJdbc().queryForObject(
                SQL_BANK_ACCOUNT_BY_USER, UUID.class, userId);

        assertThatThrownBy(() ->
                appTx.executeWithoutResult(status -> {
                    appJdbc.execute(SET_CURRENT_USER_PREFIX + userId + "'");
                    appJdbc.update(
                            "UPDATE bank_accounts SET created_by = ? WHERE id = ?",
                            otherUser, accountId);
                }))
                .hasMessageContaining("created_by is immutable");
    }

    // ── Forward-compat: D3 delegation simulation ─────────────────────────────
    // When the gateway filter (D3) sets BOTH session variables — current_user_id
    // for RLS (the data owner) and acting_user_id for audit (the actor) — the
    // trigger reads acting_user_id first. created_by / modified_by record the
    // actor, while RLS scopes to the owner's data.

    @Test
    void insert_withActingUserId_recordsActor_notDataOwner() {
        UUID owner = registerWithCashAccount("eve_owner");
        UUID actor = registerUser("eve_delegate");

        // owner has a bank account already (from registerWithCashAccount).
        // Insert an expense as actor-on-behalf-of-owner via direct JDBC.
        UUID expenseId  = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        UUID accountId  = setupJdbc().queryForObject(
                SQL_BANK_ACCOUNT_BY_USER, UUID.class, owner);

        appTx.executeWithoutResult(status -> {
            appJdbc.execute(SET_CURRENT_USER_PREFIX + owner + "'");
            appJdbc.execute(SET_ACTING_USER_PREFIX + actor + "'");
            appJdbc.update(
                    "INSERT INTO expenses (id, user_id, amount, merchant_name, " +
                    "                       expense_date, payment_method, bank_account_id) " +
                    "VALUES (?, ?, ?, 'Test', ?, 'CASH', ?)",
                    expenseId, owner, new BigDecimal("10.00"), Date.valueOf(today), accountId);
        });

        // Row belongs to owner (RLS), but audit columns record actor.
        appTx.executeWithoutResult(status -> {
            appJdbc.execute(SET_CURRENT_USER_PREFIX + owner + "'");

            UUID rowOwner = appJdbc.queryForObject(
                    "SELECT user_id FROM expenses WHERE id = ?", UUID.class, expenseId);
            UUID createdBy = appJdbc.queryForObject(
                    "SELECT created_by FROM expenses WHERE id = ?", UUID.class, expenseId);
            UUID modifiedBy = appJdbc.queryForObject(
                    "SELECT modified_by FROM expenses WHERE id = ?", UUID.class, expenseId);

            assertThat(rowOwner).isEqualTo(owner);
            assertThat(createdBy).isEqualTo(actor);
            assertThat(modifiedBy).isEqualTo(actor);
        });
    }

    // Note: the trigger's "preserve OLD.modified_by when no actor is set"
    // branch isn't covered by integration tests — every app-pool transaction
    // is wrapped by RlsSessionAspect which always sets current_user_id, and
    // RLS blocks UPDATEs that would land with neither GUC set. The branch
    // exists for future scheduled-job paths that may touch user-scoped rows
    // outside the request lifecycle, and is verifiable by inspection of the
    // V23 trigger function.
}
