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

    private static final JwtProperties PROPERTIES = new JwtProperties(SECRET, 7);

    private static final Clock CURRENT_CLOCK =
            Clock.fixed(Instant.parse("2026-01-11T10:00:00Z"), ZoneOffset.UTC);

    private final JwtService service = new JwtService(PROPERTIES, CURRENT_CLOCK);

    @Nested
    class TokenClaims {

        @Test
        void getUserId_returnsCorrectId() {
            UUID userId = UUID.randomUUID();

            String token = service.generateToken(userId, "john");
            UUID extracted = service.getUserId(token);

            assertThat(extracted).isEqualTo(userId);
        }

        @Test
        void getExpiry_isExactlyExpiryDaysAfterIssuedAt() {
            UUID userId = UUID.randomUUID();
            Instant expectedExpiry = CURRENT_CLOCK.instant().plus(7, ChronoUnit.DAYS);

            String token = service.generateToken(userId, "john");

            assertThat(service.getExpiry(token)).isEqualTo(expectedExpiry);
        }
    }

    @Nested
    class Validation {

        @Test
        void isValid_returnsTrue_forFreshToken() {
            String token = service.generateToken(UUID.randomUUID(), "john");

            assertThat(service.isValid(token)).isTrue();
        }

        @Test
        void isValid_returnsFalse_forTamperedToken() {
            String token = service.generateToken(UUID.randomUUID(), "john");
            String tampered = token + "tampered";

            assertThat(service.isValid(tampered)).isFalse();
        }

        @Test
        void isValid_returnsFalse_forExpiredToken() {
            // Generate using a clock 8 days in the past with expiryDays=7
            // → expiry = (now - 8d) + 7d = now - 1d → already expired when parsed
            Clock pastClock = Clock.fixed(
                    Instant.now().minus(8, ChronoUnit.DAYS), ZoneOffset.UTC);
            JwtService pastService = new JwtService(PROPERTIES, pastClock);

            String expiredToken = pastService.generateToken(UUID.randomUUID(), "john");

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
