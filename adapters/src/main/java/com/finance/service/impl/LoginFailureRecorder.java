package com.finance.service.impl;

import com.finance.config.DataSourceConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// Records a failed login attempt and applies the sliding-window lockout.
//
// This lives in its own bean (rather than as a private method of
// PostgresAuthService) so its work runs in a SEPARATE transaction. login() is
// @Transactional and throws InvalidCredentialsException on a wrong password —
// which rolls back login()'s transaction. If the failure INSERT + lockout
// UPDATE ran inside that transaction they'd be rolled back too, so the failure
// row would never persist and account lockout would never trigger. Calling this
// bean (REQUIRES_NEW) through the Spring proxy suspends login()'s transaction,
// commits the failure record on its own connection, then resumes — so the
// record survives login()'s rollback. Runs on the setup pool (no user context).
@Component
public class LoginFailureRecorder {

    private static final int MAX_FAILED_ATTEMPTS    = 5;
    private static final int FAILURE_WINDOW_MINUTES = 10;
    private static final int LOCKOUT_MINUTES        = 15;

    private static final String SQL_INSERT_LOGIN_FAILURE =
            "INSERT INTO user_login_failures (user_id, attempted_at) VALUES (:userId, :attemptedAt)";

    private static final String SQL_COUNT_RECENT_FAILURES =
            "SELECT COUNT(*) FROM user_login_failures " +
            "WHERE user_id = :userId AND attempted_at > :windowStart";

    private static final String SQL_SET_LOCKED_UNTIL =
            "UPDATE users SET locked_until = :lockedUntil WHERE id = :id";

    private final NamedParameterJdbcTemplate setupJdbcTemplate;

    public LoginFailureRecorder(NamedParameterJdbcTemplate setupJdbcTemplate) {
        this.setupJdbcTemplate = setupJdbcTemplate;
    }

    // Sliding-window lockout: record the attempt, then count how many failures
    // this user has had in the last FAILURE_WINDOW_MINUTES. Lock if at threshold.
    // Failure rows are kept (not deleted on success) so they age out by time,
    // protecting against the "type wrong, log in once, type wrong forever"
    // bypass that a counter-reset model would allow.
    @Transactional(value = DataSourceConfig.SETUP_TX_MANAGER, propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID userId, Instant now) {
        setupJdbcTemplate.update(
                SQL_INSERT_LOGIN_FAILURE,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("attemptedAt", Timestamp.from(now)));

        Instant windowStart = now.minus(FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        Long recentFailures = setupJdbcTemplate.queryForObject(
                SQL_COUNT_RECENT_FAILURES,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("windowStart", Timestamp.from(windowStart)),
                Long.class);

        if (recentFailures != null && recentFailures >= MAX_FAILED_ATTEMPTS) {
            setupJdbcTemplate.update(
                    SQL_SET_LOCKED_UNTIL,
                    new MapSqlParameterSource()
                            .addValue("id",          userId)
                            .addValue("lockedUntil", Timestamp.from(now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES))));
        }
    }
}
