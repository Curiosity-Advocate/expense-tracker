package com.finance.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Generates opaque cryptographically random tokens with their SHA-256 hashes.
// The raw token is returned to the client once; only the hex hash is persisted
// (in refresh_tokens.token_hash for S4, in sudo_tokens.token_hash for D2).
//
// Why SHA-256 not BCrypt: these tokens carry 256 bits of randomness, so brute
// force is computationally infeasible regardless of hash speed. BCrypt would
// add latency to every issuance / lookup for no security benefit.
@Component
public class SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;          // 256 bits of entropy
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedToken generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String rawBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new GeneratedToken(rawBase64, hash(rawBase64));
    }

    // Used on /refresh and during sudo-token verification to look up the
    // presented raw value by its hash. Same algorithm as generate() so what
    // was stored at issuance matches what we look up now.
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

    public record GeneratedToken(String rawToken, String hash) {}
}
