package com.finance.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

// Wraps scheduled jobs with a retry loop (up to maxAttempts) and persistent state.
// State is stored in job_execution_state (one row per job name) so counters survive
// worker restarts. On crash recovery, the next tick resumes from the saved attempt.
// If all attempts fail, an email alert is sent and status is set to ALERTED.
@Component
public class JobFailureAlerter {

    private static final Logger log = LoggerFactory.getLogger(JobFailureAlerter.class);
    private static final int CRASH_STALE_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JavaMailSender mailSender;
    private final Clock clock;
    private final String recipient;
    private final String fromAddress;
    private final int maxAttempts;
    private final long initialBackoffMs;

    public JobFailureAlerter(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            JavaMailSender mailSender,
            Clock clock,
            @Value("${alerts.job-failure.recipient:}") String recipient,
            @Value("${alerts.job-failure.from:noreply@expense-tracker.local}") String fromAddress,
            @Value("${alerts.job-failure.max-attempts:5}") int maxAttempts,
            @Value("${alerts.job-failure.initial-backoff-ms:30000}") long initialBackoffMs) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mailSender = mailSender;
        this.clock = clock;
        this.recipient = recipient;
        this.fromAddress = fromAddress;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
    }

    public void executeMonitored(String jobName, Runnable action) {
        int startAttempt = resolveStartAttempt(jobName);

        if (startAttempt > maxAttempts) {
            // Previous tick crashed after exhausting all retries but before persisting ALERTED.
            log.error("Job {} used all {} attempt(s) in a prior crashed tick — alerting now", jobName, maxAttempts);
            persistStatus(jobName, JobExecutionStatus.ALERTED, maxAttempts, "Retries exhausted in previous crashed tick");
            sendAlert(jobName, maxAttempts, new RuntimeException("Retries exhausted in previous crashed tick"));
            return;
        }

        RuntimeException lastError = null;
        for (int attempt = startAttempt; attempt <= maxAttempts; attempt++) {
            persistAttempt(jobName, attempt);
            try {
                action.run();
                persistStatus(jobName, JobExecutionStatus.SUCCESS, attempt, null);
                return;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Job {} attempt {}/{} failed: {}", jobName, attempt, maxAttempts, e.getMessage(), e);
                if (attempt < maxAttempts) {
                    // Exponential backoff: initialBackoffMs, *2, *4, *8 ...
                    doSleep(initialBackoffMs * (1L << (attempt - startAttempt)));
                }
            }
        }

        log.error("Job {} failed all {} attempt(s)", jobName, maxAttempts, lastError);
        persistStatus(jobName, JobExecutionStatus.ALERTED, maxAttempts, lastError.getMessage());
        sendAlert(jobName, maxAttempts, lastError);
        throw lastError;
    }

    // Returns the attempt number to start from (1-based). Returns savedAttempt+1 on
    // crash recovery — which may exceed maxAttempts if the prior tick exhausted
    // every retry, in which case executeMonitored alerts immediately without retrying.
    private int resolveStartAttempt(String jobName) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, attempt_count, updated_at FROM job_execution_state WHERE job_name = ?",
                    jobName);

            if (rows.isEmpty()) {
                jdbc.update(
                        "INSERT INTO job_execution_state (job_name, status, attempt_count) VALUES (?, ?, 0)",
                        jobName, JobExecutionStatus.RUNNING.name());
                return 1;
            }

            String existingStatus = (String) rows.get(0).get("status");
            int savedAttempt = ((Number) rows.get(0).get("attempt_count")).intValue();
            Instant updatedAt = ((Timestamp) rows.get(0).get("updated_at")).toInstant();
            boolean stale = updatedAt.isBefore(clock.instant().minus(CRASH_STALE_MINUTES, ChronoUnit.MINUTES));

            if (JobExecutionStatus.RUNNING.name().equals(existingStatus) && stale) {
                log.warn("Job {} was RUNNING since {} — crash recovery, resuming from attempt {}",
                        jobName, updatedAt, savedAttempt + 1);
                return savedAttempt + 1;
            }

            // SUCCESS, ALERTED, or non-stale RUNNING → fresh tick
            jdbc.update(
                    "UPDATE job_execution_state SET status = ?, attempt_count = 0, updated_at = NOW() WHERE job_name = ?",
                    JobExecutionStatus.RUNNING.name(), jobName);
            return 1;
        });
    }

    private void persistAttempt(String jobName, int attempt) {
        tx.execute(status -> {
            jdbc.update(
                    "UPDATE job_execution_state SET attempt_count = ?, updated_at = NOW() WHERE job_name = ?",
                    attempt, jobName);
            return null;
        });
    }

    private void persistStatus(String jobName, JobExecutionStatus status, int attempt, String error) {
        tx.execute(s -> {
            jdbc.update(
                    "UPDATE job_execution_state SET status = ?, attempt_count = ?, last_error = ?, updated_at = NOW() WHERE job_name = ?",
                    status.name(), attempt, error, jobName);
            return null;
        });
    }

    // Package-private so tests can construct the alerter with initialBackoffMs=0
    // and avoid real sleeps.
    void doSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendAlert(String jobName, int attempts, Throwable cause) {
        if (recipient == null || recipient.isBlank()) {
            log.warn("Suppressing alert for {} — alerts.job-failure.recipient not configured", jobName);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(recipient);
            msg.setSubject("[expense-tracker] " + jobName + " failed after " + attempts + " attempt(s)");
            msg.setText("Job exhausted all " + attempts + " attempt(s).\n\n"
                    + "Last error: " + cause.getMessage() + "\n\n"
                    + "Check the worker logs for the full stack trace.");
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Failed to send job-failure alert for {}", jobName, e);
        }
    }
}
