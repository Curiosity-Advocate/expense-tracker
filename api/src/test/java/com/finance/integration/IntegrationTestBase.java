package com.finance.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

// Singleton Postgres container, shared by every integration test class in the JVM.
//
// IMPORTANT: this uses the manual singleton-container pattern, NOT the
// JUnit @Testcontainers/@Container lifecycle. With @Container on a static field
// in a *shared base class*, JUnit starts the container for the first test class
// and STOPS it when that class finishes — but Spring's TestContext framework
// caches the ApplicationContext (and the Hikari pools bound to the then-current
// mapped port) and reuses it for later test classes. Those classes then hit a
// dead port → "Connection refused" / CannotGetJdbcConnectionException. Starting
// the container once in a static initializer and never stopping it
// (Testcontainers' Ryuk sidecar reaps it on JVM exit) keeps the mapped port
// stable for every cached context.
//
// test-init.sql creates the expense_app login role before Flyway runs. Flyway
// then runs as the container superuser (postgres). Under the Option-A pivot
// (ADR-0011) the setup pool connects as that superuser (DB_SUPERUSER_*), which
// bypasses RLS; the app pool connects as the non-superuser expense_app, which
// RLS isolates. DB_SETUP_PASSWORD is vestigial post-pivot but still supplied so
// the Flyway placeholder resolves.
@SpringBootTest
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("postgres")
                    .withPassword("test_superuser_password")
                    .withInitScript("test-init.sql");

    static {
        POSTGRES.start();
    }

    // Wires the running container into Spring properties. The app reads these
    // env-var-style placeholders from application.yml without modification.
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST",               POSTGRES::getHost);
        registry.add("DB_PORT",               () -> POSTGRES.getMappedPort(5432).toString());
        registry.add("DB_NAME",               POSTGRES::getDatabaseName);
        registry.add("DB_USERNAME",           () -> "expense_app");
        registry.add("DB_APP_PASSWORD",       () -> "test_app_password");
        registry.add("DB_SETUP_PASSWORD",     () -> "test_setup_password");
        registry.add("DB_SUPERUSER_USERNAME", POSTGRES::getUsername);
        registry.add("DB_SUPERUSER_PASSWORD", POSTGRES::getPassword);

        // JWT secret must be at least 32 characters — see JwtProperties.
        registry.add("JWT_SECRET",
                () -> "test_jwt_secret_at_least_32_chars_long_xxx");
    }

    // ── RLS-aware raw-JDBC helpers ───────────────────────────────────────
    //
    // Raw JdbcTemplate calls on the app pool need an RLS context, but the old
    // pattern — `appJdbc.execute("SET LOCAL app.current_user_id = ...")` followed
    // by a separate `appJdbc.query(...)` — is broken: each call borrows its own
    // pooled connection in autocommit, so SET LOCAL applies only to its own empty
    // transaction and is discarded. Worse, it leaves the GUC defined-and-reset-to-''
    // on that connection, so a later query's `current_setting('app.current_user_id')::uuid`
    // fails with "invalid input syntax for type uuid: ''".
    //
    // These helpers run the SET LOCAL and the work in ONE transaction (hence one
    // pinned connection), mirroring how RlsSessionAspect sets the variable inside
    // each service method's @Transactional in production.

    @Autowired
    protected HikariDataSource appDataSource;

    private TransactionTemplate appTx() {
        return new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
    }

    /** Runs {@code work} on the app pool with RLS context for {@code userId}, in one transaction. */
    protected void withAppUser(UUID userId, Consumer<JdbcTemplate> work) {
        withAppUser(userId, null, work);
    }

    /**
     * Runs {@code work} with both the data-owner ({@code app.current_user_id}) and, when
     * non-null, the acting user ({@code app.acting_user_id}) set — the D3 delegation shape
     * the {@code set_audit_user} trigger reads.
     */
    protected void withAppUser(UUID currentUserId, UUID actingUserId, Consumer<JdbcTemplate> work) {
        appTx().executeWithoutResult(status -> {
            // JdbcTemplate(appDataSource) resolves the transaction-bound connection
            // via DataSourceUtils, so SET LOCAL and the work share one connection.
            JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
            jdbc.execute("SET LOCAL app.current_user_id = '" + currentUserId + "'");
            if (actingUserId != null) {
                jdbc.execute("SET LOCAL app.acting_user_id = '" + actingUserId + "'");
            }
            work.accept(jdbc);
        });
    }

    /** Query variant of {@link #withAppUser(UUID, Consumer)} that returns a value. */
    protected <T> T queryAsAppUser(UUID userId, Function<JdbcTemplate, T> work) {
        return appTx().execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
            jdbc.execute("SET LOCAL app.current_user_id = '" + userId + "'");
            return work.apply(jdbc);
        });
    }

    /**
     * Runs maintenance SQL that does NOT need an RLS context (e.g. TRUNCATE, which
     * is not subject to row-level security). No session variable is set, so the
     * connection is not polluted with an empty GUC.
     */
    protected void appExec(String sql) {
        new JdbcTemplate(appDataSource).execute(sql);
    }
}
