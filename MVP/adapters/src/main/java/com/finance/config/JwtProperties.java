package com.finance.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Binds app.jwt.* from application.yml. Two expiries: access tokens
// are short-lived JWTs; refresh tokens are opaque, stored in refresh_tokens.
// Upper bounds on the integer fields are sanity caps (above which the
// short-lived design no longer makes sense). See S4 in roadmap.md.
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
        @NotBlank
        @Size(min = 32, message = "JWT secret must be at least 32 characters (HMAC-SHA256 requires a 256-bit key)")
        String secret,

        @Min(1) @Max(60)
        int accessTokenExpiryMinutes,

        @Min(1) @Max(30)
        int refreshTokenExpiryDays) {}
