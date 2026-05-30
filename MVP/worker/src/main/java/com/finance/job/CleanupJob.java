package com.finance.job;

import com.finance.alert.JobExecutionStatus;
import com.finance.alert.JobFailureAlerter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

// Nightly cleanup of expired rows. Runs at 02:00 UTC daily.
// Uses superuser credentials (configured in application.yml) to bypass RLS.
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);
    private static final int LOGIN_FAILURE_RETENTION_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JobFailureAlerter alerter;

    public CleanupJob(JdbcTemplate jdbc, Clock clock, JobFailureAlerter alerter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.alerter = alerter;
    }

    // S4 cleanup. Deleting by expires_at catches both naturally-expired rows
    // and rotated/logged-out ones — revocation does not shorten expires_at,
    // so revoked rows age out alongside expired ones.
    @Scheduled(cron = "0 20 2 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredRefreshTokens() {
        alerter.executeMonitored("deleteExpiredRefreshTokens", () -> {
            int deleted = jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < ?", clock.instant());
            log.info("Cleaned up {} expired refresh token(s)", deleted);
        });
    }

    @Scheduled(cron = "0 5 2 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredIdempotencyKeys() {
        alerter.executeMonitored("deleteExpiredIdempotencyKeys", () -> {
            int deleted = jdbc.update("DELETE FROM expense_idempotency_keys WHERE expires_at < ?", clock.instant());
            log.info("Cleaned up {} expired idempotency key(s)", deleted);
        });
    }

    // v1.1 #2 — sliding-window lockout writes one row per failed login.
    // 30-day retention keeps the table bounded and preserves forensic value.
    @Scheduled(cron = "0 10 2 * * *", zone = "UTC")
    @Transactional
    public void deleteOldLoginFailures() {
        alerter.executeMonitored("deleteOldLoginFailures", () -> {
            int deleted = jdbc.update(
                    "DELETE FROM user_login_failures WHERE attempted_at < ?",
                    clock.instant().minus(LOGIN_FAILURE_RETENTION_DAYS, ChronoUnit.DAYS));
            log.info("Cleaned up {} login failure row(s)", deleted);
        });
    }

    // v1.1 #4 — prune job_execution_state so the table stays bounded.
    // SUCCESS rows expire after 1 day; ALERTED rows kept 7 days for postmortems.
    @Scheduled(cron = "0 15 2 * * *", zone = "UTC")
    @Transactional
    public void cleanupJobExecutionState() {
        alerter.executeMonitored("cleanupJobExecutionState", () -> {
            int deletedSuccess = jdbc.update(
                    "DELETE FROM job_execution_state WHERE status = ? AND updated_at < ?",
                    JobExecutionStatus.SUCCESS.name(), clock.instant().minus(1, ChronoUnit.DAYS));
            int deletedAlerted = jdbc.update(
                    "DELETE FROM job_execution_state WHERE status = ? AND updated_at < ?",
                    JobExecutionStatus.ALERTED.name(), clock.instant().minus(7, ChronoUnit.DAYS));
            log.info("Cleaned up {} SUCCESS and {} ALERTED job state row(s)", deletedSuccess, deletedAlerted);
        });
    }
}
