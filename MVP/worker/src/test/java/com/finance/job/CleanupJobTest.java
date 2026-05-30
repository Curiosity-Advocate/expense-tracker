package com.finance.job;

import com.finance.alert.JobFailureAlerter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupJobTest {

    @Mock JdbcTemplate jdbc;
    @Mock Clock clock;
    @Mock JobFailureAlerter alerter;

    @InjectMocks CleanupJob job;

    @BeforeEach
    void wireAlerter() {
        // Real alerter wraps the action in retries; for these tests the alerter is
        // mocked, so we manually invoke the Runnable so the JDBC calls actually fire.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(alerter).executeMonitored(anyString(), any(Runnable.class));
    }

    @Test
    void deleteExpiredRefreshTokens_usesClockInstant_asCutoff() {
        Instant fixed = Instant.parse("2026-05-25T02:20:00Z");
        when(clock.instant()).thenReturn(fixed);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.deleteExpiredRefreshTokens();

        verify(jdbc).update(
                eq("DELETE FROM refresh_tokens WHERE expires_at < ?"),
                eq(fixed));
    }

    @Test
    void deleteExpiredIdempotencyKeys_usesClockInstant_asCutoff() {
        Instant fixed = Instant.parse("2026-05-25T02:05:00Z");
        when(clock.instant()).thenReturn(fixed);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.deleteExpiredIdempotencyKeys();

        verify(jdbc).update(
                eq("DELETE FROM expense_idempotency_keys WHERE expires_at < ?"),
                eq(fixed));
    }

    // Sanity: bare anyString matcher proves only one jdbc.update call per invocation
    @Test
    void deleteExpiredRefreshTokens_callsJdbcUpdateExactlyOnce() {
        when(clock.instant()).thenReturn(Instant.parse("2026-05-25T02:20:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.deleteExpiredRefreshTokens();

        verify(jdbc).update(anyString(), eq(Instant.parse("2026-05-25T02:20:00Z")));
    }

    // v1.1 #2 — login-failure cleanup uses (clock.instant() - 30 days) as the cutoff.
    @Test
    void deleteOldLoginFailures_usesClockInstantMinus30Days_asCutoff() {
        Instant fixed = Instant.parse("2026-05-25T02:10:00Z");
        Instant expectedCutoff = fixed.minus(30, java.time.temporal.ChronoUnit.DAYS);
        when(clock.instant()).thenReturn(fixed);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.deleteOldLoginFailures();

        verify(jdbc).update(
                eq("DELETE FROM user_login_failures WHERE attempted_at < ?"),
                eq(expectedCutoff));
    }

    // v1.1 #4 — prunes job_execution_state: SUCCESS rows older than 1 day,
    // ALERTED rows older than 7 days.
    @Test
    void cleanupJobExecutionState_deletesSuccessOlderThan1Day_andAlertedOlderThan7Days() {
        Instant fixed = Instant.parse("2026-06-01T02:15:00Z");
        when(clock.instant()).thenReturn(fixed);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.cleanupJobExecutionState();

        verify(jdbc).update(
                eq("DELETE FROM job_execution_state WHERE status = ? AND updated_at < ?"),
                eq("SUCCESS"),
                eq(fixed.minus(1, java.time.temporal.ChronoUnit.DAYS)));
        verify(jdbc).update(
                eq("DELETE FROM job_execution_state WHERE status = ? AND updated_at < ?"),
                eq("ALERTED"),
                eq(fixed.minus(7, java.time.temporal.ChronoUnit.DAYS)));
    }
}
