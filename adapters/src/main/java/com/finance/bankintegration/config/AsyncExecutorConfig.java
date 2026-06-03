package com.finance.bankintegration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// Dedicated thread pool for CSV import processing. Bounded so a runaway
// upload burst can't spawn arbitrary threads.
//
// Sizing for personal-use scale (10 users, weekly imports): core=2 / max=4
// / queue=20 means up to 4 concurrent imports and 20 queued. At our scale
// the queue should never fill.
//
// Named so thread dumps make it clear which work belongs to CSV import
// vs other Spring async paths.
// Also the in-package home for enabling BankIntegrationProperties: keeping the
// @EnableConfigurationProperties here (rather than on ApiApplication) preserves
// the "bankintegration is internally sealed" ArchUnit rule — nothing outside the
// package needs to reference BankIntegrationProperties.
@Configuration
@EnableAsync
@EnableConfigurationProperties(BankIntegrationProperties.class)
public class AsyncExecutorConfig {

    public static final String CSV_IMPORT_EXECUTOR = "csvImportExecutor";

    @Bean(name = CSV_IMPORT_EXECUTOR)
    public Executor csvImportExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("csv-import-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}
