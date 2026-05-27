package com.finance.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// v1.1 #4 — JobFailureAlerter retry + alert + crash recovery behavior.
@ExtendWith(MockitoExtension.class)
class JobFailureAlerterTest {

    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate tx;
    @Mock JavaMailSender mailSender;
    @Mock Clock clock;

    private JobFailureAlerter alerter;

    @BeforeEach
    void setUp() {
        // Run the callback passed to tx.execute(...) directly so the mocked JdbcTemplate
        // sees the same calls a real REQUIRES_NEW transaction would issue.
        lenient().when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-06-01T02:00:00Z"));

        // Default: no existing job_execution_state row → fresh tick at attempt 1.
        lenient().when(jdbc.queryForList(anyString(), eq("testJob"))).thenReturn(List.of());

        alerter = newAlerter("ops@example.com", /*initialBackoffMs*/ 0L);
    }

    private JobFailureAlerter newAlerter(String recipient, long backoffMs) {
        return new JobFailureAlerter(
                jdbc, tx, mailSender, clock,
                recipient, "noreply@expense-tracker.local",
                5, backoffMs);
    }

    @Test
    void executeMonitored_successOnFirstAttempt_persistsSuccess_andSendsNoAlert() {
        alerter.executeMonitored("testJob", () -> {});

        verify(jdbc).update(
                contains("SET status = ?"),
                eq("SUCCESS"), eq(1), isNull(), eq("testJob"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void executeMonitored_failsOnceThenSucceeds_persistsSuccess_andSendsNoAlert() {
        Runnable action = mock(Runnable.class);
        doThrow(new RuntimeException("transient")).doNothing().when(action).run();

        alerter.executeMonitored("testJob", action);

        verify(action, times(2)).run();
        verify(jdbc).update(
                contains("SET status = ?"),
                eq("SUCCESS"), eq(2), isNull(), eq("testJob"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void executeMonitored_allAttemptsFail_setsAlerted_sendsEmail_andRethrows() {
        RuntimeException boom = new RuntimeException("db is down");
        Runnable action = mock(Runnable.class);
        doThrow(boom).when(action).run();

        assertThatThrownBy(() -> alerter.executeMonitored("testJob", action))
                .isSameAs(boom);

        verify(action, times(5)).run();
        verify(jdbc).update(
                contains("SET status = ?"),
                eq("ALERTED"), eq(5), eq("db is down"), eq("testJob"));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void executeMonitored_blankRecipient_suppressesAlertEmail() {
        alerter = newAlerter("", 0L);
        RuntimeException boom = new RuntimeException("failure");
        Runnable action = mock(Runnable.class);
        doThrow(boom).when(action).run();

        assertThatThrownBy(() -> alerter.executeMonitored("testJob", action))
                .isSameAs(boom);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    // Stale RUNNING row → previous tick crashed mid-retry. New tick must resume
    // from savedAttempt+1, not restart at 1.
    @Test
    void executeMonitored_crashRecoveryFromStaleRunning_resumesFromSavedAttempt() {
        Instant staleTime = Instant.parse("2026-06-01T01:00:00Z"); // 1 hour ago — stale
        when(jdbc.queryForList(anyString(), eq("testJob")))
                .thenReturn(List.of(Map.of(
                        "status", "RUNNING",
                        "attempt_count", 3,
                        "updated_at", Timestamp.from(staleTime))));

        Runnable action = mock(Runnable.class); // succeeds on first call

        alerter.executeMonitored("testJob", action);

        // Resumed at attempt 4, succeeded immediately → action invoked once
        verify(action, times(1)).run();
        verify(jdbc).update(
                contains("SET status = ?"),
                eq("SUCCESS"), eq(4), isNull(), eq("testJob"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
