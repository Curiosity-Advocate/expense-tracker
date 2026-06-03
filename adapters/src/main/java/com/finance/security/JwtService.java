package com.finance.security;

import com.finance.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String generateAccessToken(UUID userId, String username) {
        Instant now    = Instant.now(clock);
        Instant expiry = now.plus(properties.accessTokenExpiryMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())          // jti — unique per token
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey())
                .compact();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(claims(token).getSubject());
    }

    public String getUsername(String token) {
        return claims(token).get("username", String.class);
    }

    public String getJti(String token) {
        return claims(token).getId();
    }

    public Instant getExpiry(String token) {
        return claims(token).getExpiration().toInstant();
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            claims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    private Claims claims(String token) {
        // Validate expiry against the injected Clock, not jjwt's default
        // wall-clock. In production this is the system clock (behaviour
        // unchanged); in tests it makes validation deterministic and keeps
        // token generation and validation on one time source. jjwt's Clock is
        // its own functional interface (Date now()), adapted from java.time here.
        return Jwts.parser()
                .clock(() -> Date.from(Instant.now(clock)))
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
