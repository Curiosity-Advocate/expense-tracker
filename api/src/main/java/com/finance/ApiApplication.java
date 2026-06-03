package com.finance;

import com.finance.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// BankIntegrationProperties is intentionally NOT enabled here — AsyncExecutorConfig
// inside com.finance.bankintegration enables it, keeping that package sealed
// (enforced by ArchitectureTest). JwtProperties lives outside that package.
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
// order = 1 ensures @Transactional fires before the RLS aspect (order = 2),
// so the transaction is open when SET LOCAL runs inside the aspect.
@EnableTransactionManagement(order = 1)
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
