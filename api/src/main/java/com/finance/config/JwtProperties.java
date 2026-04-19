package com.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// @ConfigurationProperties binds application.yml properties to a typed Java object.
// Prefix "app.jwt" means it reads app.jwt.secret and app.jwt.expiry-days from yml.
// This is safer than @Value — all JWT config is in one place, validated at startup.
// If JWT_SECRET env var is missing, Spring Boot fails fast before the app starts.
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private int expiryDays;

    public String getSecret()            { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public int getExpiryDays()           { return expiryDays; }
    public void setExpiryDays(int days)  { this.expiryDays = days; }
}