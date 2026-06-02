package com.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// The BCryptPasswordEncoder bean lives here, NOT in SecurityConfig, to break a
// dependency cycle. SecurityConfig injects AsUserIdFilter (to register it in
// the filter chain); AsUserIdFilter injects SudoTokenService; PostgresSudoTokenService
// injects BCryptPasswordEncoder. If that bean were declared in SecurityConfig,
// the graph would close into a cycle:
//
//   SecurityConfig -> AsUserIdFilter -> PostgresSudoTokenService
//                  -> BCryptPasswordEncoder (@Bean in SecurityConfig) -> SecurityConfig
//
// Spring (since Boot 2.6) rejects circular references by default. Hosting the
// encoder in a standalone config that depends on nothing keeps the graph acyclic.
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
