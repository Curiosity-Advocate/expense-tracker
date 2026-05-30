package com.finance.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

// Two connection pools — see ADR-0011.
//
// `app` is the @Primary pool used by every authenticated request. It connects
// as expense_app (RLS-enforced) and is wired into JPA, so every existing
// repository keeps working without any annotation changes.
//
// `setup` is a separate pool used only by the three pre-authentication methods
// — register, login, setupNewUser. It connects as expense_setup (BYPASSRLS).
// expense_app has no membership in expense_setup as of V20, so a SQL injection
// through the app pool cannot reach the setup pool's privileges.
//
// Access to the setup pool is exclusively through `setupJdbcTemplate`. We do
// NOT bind it to JPA — keeping it on plain JdbcTemplate means the only SQL
// that ever runs against expense_setup is the SQL these three methods write
// explicitly. No surprise lazy loads, no entity-graph traversals.
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

    @Bean
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
    public PlatformTransactionManager setupTransactionManager(HikariDataSource setupDataSource) {
        return new DataSourceTransactionManager(setupDataSource);
    }

    // The only injection point for setup-pool data access. Services that need
    // pre-auth DB access take this; they do NOT take a DataSource directly.
    // NamedParameterJdbcTemplate gives :name placeholders, which are safer
    // against positional-argument bugs than the basic JdbcTemplate.
    @Bean
    public NamedParameterJdbcTemplate setupJdbcTemplate(HikariDataSource setupDataSource) {
        return new NamedParameterJdbcTemplate(setupDataSource);
    }
}
