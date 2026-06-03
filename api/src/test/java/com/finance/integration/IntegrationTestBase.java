package com.finance.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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
}
