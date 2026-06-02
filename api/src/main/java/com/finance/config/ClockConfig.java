package com.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    // Injecting Clock instead of calling Instant.now() directly lets tests
    // control time without mocking static methods.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
