package com.finance.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The security regression test for S1. If V20 is ever reverted — or if someone
// re-grants expense_setup membership to expense_app — these tests fail. They
// prove the central claim of S1: SQL injection through the app pool cannot
// escalate to BYPASSRLS by issuing SET LOCAL ROLE expense_setup.
class PoolIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired @Qualifier("appDataSource")   HikariDataSource appDataSource;
    @Autowired @Qualifier("setupDataSource") HikariDataSource setupDataSource;

    @Test
    void appPool_cannotElevateToSetupRole() {
        JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);

        // Postgres error 42501 — insufficient_privilege. The app role is no
        // longer a member of expense_setup so the SET ROLE statement is denied.
        // Asserting on SQLState rather than message text keeps the check stable
        // across Postgres versions whose wording differs.
        assertThatThrownBy(() -> appJdbc.execute("SET LOCAL ROLE expense_setup"))
                .hasCauseInstanceOf(SQLException.class)
                .satisfies(thrown -> {
                    SQLException sqlEx = (SQLException) thrown.getCause();
                    assertThat(sqlEx.getSQLState()).isEqualTo("42501");
                });
    }

    @Test
    void appPool_isNotAMemberOfSetupRole() {
        JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);

        // pg_has_role returns false because V20's REVOKE removed membership.
        Boolean isMember = appJdbc.queryForObject(
                "SELECT pg_has_role(current_user, 'expense_setup', 'MEMBER')",
                Boolean.class);
        assertThat(isMember).isFalse();
    }

    @Test
    void setupPool_canInsertWithoutRlsContext() {
        JdbcTemplate setupJdbc = new JdbcTemplate(setupDataSource);

        // No app.current_user_id is set on this connection. The setup pool
        // connects as the table-owner/superuser (Option-A pivot, ADR-0011) and
        // bypasses RLS, so the INSERT into users succeeds anyway — the capability
        // the three pre-auth methods rely on. (username must fit varchar(50).)
        UUID id = UUID.randomUUID();
        setupJdbc.update(
                "INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                id, "iso-" + id, id + "@test.com", "hashed");

        Integer count = setupJdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void appPool_withoutRlsContext_returnsZeroRows() {
        JdbcTemplate setupJdbc = new JdbcTemplate(setupDataSource);
        JdbcTemplate appJdbc   = new JdbcTemplate(appDataSource);

        // Insert via the setup pool (owner/superuser — bypasses RLS).
        UUID id = UUID.randomUUID();
        setupJdbc.update(
                "INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                id, "rls-" + id, id + "@test.com", "hashed");

        // Read via the app pool with NO app.current_user_id set. RESTRICTIVE
        // RLS policy returns zero rows when the session variable is missing or
        // empty (V32 added the NULLIF empty-string guard so this fails closed
        // rather than erroring on a pooled connection's stale '' GUC).
        Integer visible = appJdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id);
        assertThat(visible).isZero();
    }
}
