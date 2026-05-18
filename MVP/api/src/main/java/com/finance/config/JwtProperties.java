package com.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds app.jwt.* from application.yml.
// Registered in @EnableConfigurationProperties on ApiApplication.
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, int expiryDays) {}
