package com.finance.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Refresh tokens are NOT JWTs — they're opaque, cryptographically random
// values. The raw token is returned to the client once at issuance; only
// the SHA-256 hex of the token is persisted (refresh_tokens.token_hash).
//
// Why SHA-256 not BCrypt: refresh tokens carry 256 bits of randomness,
// so brute force is computationally infeasible regardless of hash speed.
// BCrypt would add latency to every /refresh call for no security benefit.
// Same reasoning that ADR-0011 / data-model.md apply to sudo_tokens.
@Component
public class RefreshTokenGenerator {

    private static final int TOKEN_BYTES = 32;          // 256 bits of entropy
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedRefreshToken generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String rawBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new GeneratedRefreshToken(rawBase64, hash(rawBase64));
    }

    // Used on /refresh to look up the presented token. Same algorithm as
    // generate() so what was stored at issuance matches what we look up now.
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record GeneratedRefreshToken(String rawToken, String hash) {}
}
