package com.finance.security;

import com.finance.config.JwtProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    // Minimum 32 bytes for HMAC-SHA256
    private static final String SECRET = "test_secret_at_least_32_characters_long_xxxx";

    // 15-minute access token expiry (matches application.yml default).
    // Refresh-token expiry is unused by JwtService itself — it lives on the
    // refresh_tokens row, not in the JWT — but JwtProperties needs a value.
    private static final int ACCESS_TOKEN_EXPIRY_MINUTES = 15;
    private static final int REFRESH_TOKEN_EXPIRY_DAYS   = 7;

    private static final JwtProperties PROPERTIES =
            new JwtProperties(SECRET, ACCESS_TOKEN_EXPIRY_MINUTES, REFRESH_TOKEN_EXPIRY_DAYS);

    private static final Clock CURRENT_CLOCK =
            Clock.fixed(Instant.parse("2026-01-11T10:00:00Z"), ZoneOffset.UTC);

    private final JwtService service = new JwtService(PROPERTIES, CURRENT_CLOCK);

    @Nested
    class TokenClaims {

        @Test
        void getUserId_returnsCorrectId() {
            UUID userId = UUID.randomUUID();

            String token = service.generateAccessToken(userId, "john");
            UUID extracted = service.getUserId(token);

            assertThat(extracted).isEqualTo(userId);
        }

        @Test
        void getExpiry_matchesConfiguredAccessTokenExpiry() {
            UUID userId = UUID.randomUUID();
            Instant expectedExpiry = CURRENT_CLOCK.instant()
                    .plus(ACCESS_TOKEN_EXPIRY_MINUTES, ChronoUnit.MINUTES);

            String token = service.generateAccessToken(userId, "john");

            assertThat(service.getExpiry(token)).isEqualTo(expectedExpiry);
        }
    }

    @Nested
    class Validation {

        @Test
        void isValid_returnsTrue_forFreshToken() {
            String token = service.generateAccessToken(UUID.randomUUID(), "john");

            assertThat(service.isValid(token)).isTrue();
        }

        @Test
        void isValid_returnsFalse_forTamperedToken() {
            String token = service.generateAccessToken(UUID.randomUUID(), "john");
            String tampered = token + "tampered";

            assertThat(service.isValid(tampered)).isFalse();
        }

        @Test
        void isValid_returnsFalse_forExpiredToken() {
            // Generate using a clock 30 minutes in the past with 15-min expiry
            // → token expires 15 min ago → invalid when parsed against current clock
            Clock pastClock = Clock.fixed(
                    CURRENT_CLOCK.instant().minus(30, ChronoUnit.MINUTES), ZoneOffset.UTC);
            JwtService pastService = new JwtService(PROPERTIES, pastClock);

            String expiredToken = pastService.generateAccessToken(UUID.randomUUID(), "john");

            assertThat(service.isValid(expiredToken)).isFalse();
        }

        @Test
        void isValid_returnsFalse_forNullOrBlankToken() {
            assertThat(service.isValid(null)).isFalse();
            assertThat(service.isValid("")).isFalse();
            assertThat(service.isValid("   ")).isFalse();
        }
    }
}
