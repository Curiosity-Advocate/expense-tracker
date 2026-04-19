package com.finance.security;

import com.finance.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

// @Component not @Service — this is an infrastructure utility, not a domain service.
// It knows about JWT internals (signing, parsing, claims) and nothing else.
// PostgresAuthService depends on this — it never constructs JWTs itself.
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final int expiryDays;

    public JwtService(JwtProperties properties) {
        // Keys.hmacShaKeyFor requires at least 256 bits (32 bytes) for HS256.
        // If JWT_SECRET is too short, this throws at startup — fast failure, not silent bug.
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expiryDays = properties.getExpiryDays();
    }

    // Generates a signed JWT for the given user.
    // jti (JWT ID) is a unique identifier per token — used by the revocation table
    // to identify and reject specific tokens after logout.
    public String generateToken(UUID userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expiryDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())   // jti claim — unique per token
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    // Extracts and validates all claims from a token.
    // Throws JwtException (unchecked) if the token is expired, malformed, or signature fails.
    // The gateway filter catches JwtException and returns 401.
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Instant getExpiry(String token) {
        return parseToken(token).getExpiration().toInstant();
    }

    public String getJti(String token) {
        return parseToken(token).getId();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }
}