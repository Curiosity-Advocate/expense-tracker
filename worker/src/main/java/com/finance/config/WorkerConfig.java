package com.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WorkerConfig {

    @Bean
    TransactionTemplate requiresNewTransactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }
}
