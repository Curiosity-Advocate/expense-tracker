package com.finance.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Binds app.jwt.* from application.yml.
// Registered in @EnableConfigurationProperties on ApiApplication.
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
        @NotBlank
        @Size(min = 32, message = "JWT secret must be at least 32 characters (HMAC-SHA256 requires a 256-bit key)")
        String secret,

        @Min(1)
        int expiryDays) {}
