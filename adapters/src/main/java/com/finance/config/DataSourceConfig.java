package com.finance.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

// Two connection pools — see ADR-0011.
//
// `app` is the @Primary pool used by every authenticated request. It connects
// as expense_app (RLS-enforced) and is wired into JPA, so every existing
// repository keeps working without any annotation changes.
//
// `setup` is a separate pool used only by the three pre-authentication methods
// — register, login, setupNewUser. Under the Option-A pivot (ADR-0011) it
// connects as the table-owner role (the same role Flyway runs as), which
// Postgres auto-bypasses RLS for. The v2.0 design used a BYPASSRLS expense_setup
// role here, but managed Postgres providers refuse to grant BYPASSRLS so we
// fall back to owner-based bypass.
//
// Access to the setup pool is exclusively through `setupJdbcTemplate`. We do
// NOT bind it to JPA — keeping it on plain JdbcTemplate means the only SQL
// that ever runs through this pool is the SQL these three methods write
// explicitly. No surprise lazy loads, no entity-graph traversals.
//
// IMPORTANT — the @Qualifier("setupDataSource") on the setup-pool consumers
// below is load-bearing, do not remove it. There are two HikariDataSource
// beans and appDataSource is @Primary. This class lives in the `adapters`
// module, which does NOT apply the Spring Boot Gradle plugin, so it compiles
// WITHOUT `-parameters`. With parameter names stripped, Spring cannot match a
// @Bean method parameter to a bean by name and falls back to by-type — which
// resolves to the @Primary appDataSource. Without the qualifier, the setup
// transaction manager and setup JdbcTemplate both silently bind to the APP
// pool (expense_app, RLS-enforced), so register/login fail with an RLS policy
// violation surfaced as "bad SQL grammar". The qualifier pins them explicitly.
@Configuration
public class DataSourceConfig {

    // The qualifier referenced by @Transactional on setup-pool methods and
    // checked by RlsSessionAspect to skip its principal-injection logic.
    // Defined as a constant so the bean name lives in exactly one place.
    public static final String SETUP_TX_MANAGER = "setupTransactionManager";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.app")
    public HikariDataSource appDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean("setupDataSource")
    @ConfigurationProperties("spring.datasource.setup")
    public HikariDataSource setupDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // App-side transaction manager — JPA-backed, wraps the auto-configured
    // EntityManagerFactory. @Primary so every existing @Transactional resolves
    // here by default; no service code needs to change.
    @Bean
    @Primary
    public PlatformTransactionManager appTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    // Setup-side transaction manager — plain JDBC. Must be referenced by name:
    // @Transactional("setupTransactionManager"). Any drift between this name
    // and the annotation strings in services is caught by the test in step 6.
    @Bean(name = SETUP_TX_MANAGER)
    public PlatformTransactionManager setupTransactionManager(
            @Qualifier("setupDataSource") HikariDataSource setupDataSource) {
        return new DataSourceTransactionManager(setupDataSource);
    }

    // The only injection point for setup-pool data access. Services that need
    // pre-auth DB access take this; they do NOT take a DataSource directly.
    // NamedParameterJdbcTemplate gives :name placeholders, which are safer
    // against positional-argument bugs than the basic JdbcTemplate.
    @Bean
    public NamedParameterJdbcTemplate setupJdbcTemplate(
            @Qualifier("setupDataSource") HikariDataSource setupDataSource) {
        return new NamedParameterJdbcTemplate(setupDataSource);
    }
}
