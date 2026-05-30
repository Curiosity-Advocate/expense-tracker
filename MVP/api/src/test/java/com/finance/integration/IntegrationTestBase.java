package com.finance.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// One Postgres container per JVM, shared across every IntegrationTestBase subclass.
// withReuse(true) keeps the container alive between test runs locally if
// Testcontainers' reuse flag is enabled in ~/.testcontainers.properties.
//
// The init script creates expense_app before Flyway runs. Flyway then runs
// V1–V20 as the superuser, which creates expense_setup (V17) and gives it
// LOGIN + password (V20). After this, both application pools can connect.
@SpringBootTest
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("postgres")
            .withPassword("test_superuser_password")
            .withInitScript("test-init.sql")
            .withReuse(true);

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
