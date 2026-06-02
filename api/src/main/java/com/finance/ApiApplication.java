package com.finance;

import com.finance.bankintegration.config.BankIntegrationProperties;
import com.finance.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, BankIntegrationProperties.class})
// order = 1 ensures @Transactional fires before the RLS aspect (order = 2),
// so the transaction is open when SET LOCAL runs inside the aspect.
@EnableTransactionManagement(order = 1)
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
