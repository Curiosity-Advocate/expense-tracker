package com.finance.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// Nightly cleanup of expired rows. Runs at 02:00 UTC daily.
// Uses superuser credentials (configured in application.yml) to bypass RLS.
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final JdbcTemplate jdbc;

    public CleanupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredRevokedTokens() {
        int deleted = jdbc.update("DELETE FROM revoked_tokens WHERE expires_at < ?", Instant.now());
        log.info("Cleaned up {} expired revoked token(s)", deleted);
    }

    @Scheduled(cron = "0 5 2 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredIdempotencyKeys() {
        int deleted = jdbc.update("DELETE FROM expense_idempotency_keys WHERE expires_at < ?", Instant.now());
        log.info("Cleaned up {} expired idempotency key(s)", deleted);
    }
}
