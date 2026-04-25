public class HibernateConfig {
    
}
package com.finance.config;

import com.finance.security.RlsInterceptor;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// HibernatePropertiesCustomizer lets us add Hibernate settings
// without replacing Spring Boot's auto-configuration entirely.
// We're adding exactly one thing: our StatementInspector.
@Configuration
public class HibernateConfig {

    private final RlsInterceptor rlsInterceptor;

    public HibernateConfig(RlsInterceptor rlsInterceptor) {
        this.rlsInterceptor = rlsInterceptor;
    }

    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return properties -> properties.put(
                AvailableSettings.STATEMENT_INSPECTOR,
                rlsInterceptor
        );
    }
}