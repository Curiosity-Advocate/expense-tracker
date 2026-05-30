package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.entity.RevokedTokenEntity;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.repository.RevokedTokenRepository;
import com.finance.security.JwtService;
import com.finance.service.AuthService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// Register and login route through `setupJdbcTemplate` (expense_setup pool,
// BYPASSRLS). Logout uses the JPA repository on the @Primary pool — a JWT
// is present at that point, so user_isolation RLS applies normally. See ADR-0011.
@Service
public class PostgresAuthService implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS    = 5;
    private static final int FAILURE_WINDOW_MINUTES = 10;
    private static final int LOCKOUT_MINUTES        = 15;

    // JDBC named-parameter keys. Must match the :placeholder names in the SQL
    // constants below. Extracted here so the same identifier is used everywhere.
    private static final String PARAM_USERNAME = "username";
    private static final String PARAM_EMAIL    = "email";
    private static final String PARAM_ID       = "id";
    private static final String PARAM_USER_ID  = "userId";

    private static final String SQL_USER_EXISTS =
            "SELECT EXISTS (SELECT 1 FROM users WHERE username = :username OR email = :email)";

    private static final String SQL_INSERT_USER =
            "INSERT INTO users (id, username, email, password_hash) " +
            "VALUES (:id, :username, :email, :passwordHash) " +
            "RETURNING created_at";

    private static final String SQL_FIND_USER_BY_USERNAME =
            "SELECT id, password_hash, locked_until FROM users WHERE username = :username";

    private static final String SQL_CLEAR_LOCKED_UNTIL =
            "UPDATE users SET locked_until = NULL WHERE id = :id";

    private static final String SQL_SET_LOCKED_UNTIL =
            "UPDATE users SET locked_until = :lockedUntil WHERE id = :id";

    private static final String SQL_INSERT_LOGIN_FAILURE =
            "INSERT INTO user_login_failures (user_id, attempted_at) VALUES (:userId, :attemptedAt)";

    private static final String SQL_COUNT_RECENT_FAILURES =
            "SELECT COUNT(*) FROM user_login_failures " +
            "WHERE user_id = :userId AND attempted_at > :windowStart";

    private final NamedParameterJdbcTemplate setupJdbcTemplate;
    private final RevokedTokenRepository     revokedTokenRepository;
    private final BCryptPasswordEncoder      passwordEncoder;
    private final JwtService                 jwtService;
    private final Clock                      clock;

    // Hash compared against when a username doesn't exist. Generated once at
    // startup with the same encoder, so a non-existent-user login takes the
    // same ~100ms as a wrong-password login — prevents username enumeration
    // via response-time analysis.
    private final String                     timingDefenceHash;

    public PostgresAuthService(NamedParameterJdbcTemplate setupJdbcTemplate,
                               RevokedTokenRepository revokedTokenRepository,
                               BCryptPasswordEncoder passwordEncoder,
                               JwtService jwtService,
                               Clock clock) {
        this.setupJdbcTemplate      = setupJdbcTemplate;
        this.revokedTokenRepository = revokedTokenRepository;
        this.passwordEncoder        = passwordEncoder;
        this.jwtService             = jwtService;
        this.clock                  = clock;
        this.timingDefenceHash      = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional(DataSourceConfig.SETUP_TX_MANAGER)
    public RegisteredUser register(RegisterCommand command) {
        String passwordHash = passwordEncoder.encode(command.password());

        Boolean exists = setupJdbcTemplate.queryForObject(
                SQL_USER_EXISTS,
                new MapSqlParameterSource()
                        .addValue(PARAM_USERNAME, command.username())
                        .addValue(PARAM_EMAIL,    command.email()),
                Boolean.class);

        if (Boolean.TRUE.equals(exists)) {
            throw new UserAlreadyExistsException();
        }

        UUID id = UUID.randomUUID();
        Instant createdAt = setupJdbcTemplate.queryForObject(
                SQL_INSERT_USER,
                new MapSqlParameterSource()
                        .addValue(PARAM_ID,       id)
                        .addValue(PARAM_USERNAME, command.username())
                        .addValue(PARAM_EMAIL,    command.email())
                        .addValue("passwordHash", passwordHash),
                (rs, n) -> rs.getTimestamp("created_at").toInstant());

        return new RegisteredUser(id, command.username(), command.email(), createdAt);
    }

    @Override
    @Transactional(DataSourceConfig.SETUP_TX_MANAGER)
    public TokenPair login(LoginCommand command) {
        UserLoginRecord user;
        try {
            user = setupJdbcTemplate.queryForObject(
                    SQL_FIND_USER_BY_USERNAME,
                    new MapSqlParameterSource(PARAM_USERNAME, command.username()),
                    (rs, n) -> new UserLoginRecord(
                            (UUID) rs.getObject("id"),
                            rs.getString("password_hash"),
                            toInstant(rs.getTimestamp("locked_until"))));
        } catch (EmptyResultDataAccessException e) {
            // Dummy BCrypt comparison so response time matches the wrong-password
            // path. Result is discarded — we always throw InvalidCredentials.
            passwordEncoder.matches(command.password(), timingDefenceHash);
            throw new InvalidCredentialsException();
        }

        Instant now           = Instant.now(clock);
        Instant effectiveLock = user.lockedUntil;

        // Clear an elapsed lockout so this attempt isn't rejected on a stale value.
        if (effectiveLock != null && effectiveLock.isBefore(now)) {
            setupJdbcTemplate.update(SQL_CLEAR_LOCKED_UNTIL,
                    new MapSqlParameterSource(PARAM_ID, user.id));
            effectiveLock = null;
        }

        if (effectiveLock != null && effectiveLock.isAfter(now)) {
            throw new AccountLockedException(effectiveLock);
        }

        if (!passwordEncoder.matches(command.password(), user.passwordHash)) {
            recordFailedAttempt(user.id, now);
            throw new InvalidCredentialsException();
        }

        // Successful login: if anything was set on locked_until before this
        // attempt, clear it. Failure rows are retained for forensic value;
        // the worker purges them after 30 days.
        if (user.lockedUntil != null) {
            setupJdbcTemplate.update(SQL_CLEAR_LOCKED_UNTIL,
                    new MapSqlParameterSource(PARAM_ID, user.id));
        }

        String token = jwtService.generateToken(user.id, command.username());
        return new TokenPair(token, jwtService.getExpiry(token), "Bearer");
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        // Defensive — invalid tokens (malformed, tampered, expired) are already
        // useless; treat logout as a no-op rather than throwing. Keeps logout
        // idempotent and avoids leaking token-validity signal to probing attackers.
        if (!jwtService.isValid(rawToken)) {
            return;
        }

        String  jti       = jwtService.getJti(rawToken);
        Instant expiresAt = jwtService.getExpiry(rawToken);

        // Idempotent — second logout call on same token does nothing.
        if (revokedTokenRepository.existsByTokenJti(UUID.fromString(jti))) {
            return;
        }

        RevokedTokenEntity revoked = new RevokedTokenEntity();
        revoked.setTokenJti(UUID.fromString(jti));
        revoked.setUserId(jwtService.getUserId(rawToken));
        revoked.setRevokedAt(Instant.now(clock));
        revoked.setExpiresAt(expiresAt);
        revokedTokenRepository.save(revoked);
    }

    // Sliding-window lockout: record the attempt, then count how many failures
    // this user has had in the last FAILURE_WINDOW_MINUTES. Lock if at threshold.
    // Failure rows are kept (not deleted on success) so they age out by time,
    // protecting against the "type wrong, log in once, type wrong forever"
    // bypass that a counter-reset model would allow.
    private void recordFailedAttempt(UUID userId, Instant now) {
        setupJdbcTemplate.update(
                SQL_INSERT_LOGIN_FAILURE,
                new MapSqlParameterSource()
                        .addValue(PARAM_USER_ID, userId)
                        .addValue("attemptedAt", Timestamp.from(now)));

        Instant windowStart = now.minus(FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        Long recentFailures = setupJdbcTemplate.queryForObject(
                SQL_COUNT_RECENT_FAILURES,
                new MapSqlParameterSource()
                        .addValue(PARAM_USER_ID, userId)
                        .addValue("windowStart", Timestamp.from(windowStart)),
                Long.class);

        if (recentFailures != null && recentFailures >= MAX_FAILED_ATTEMPTS) {
            setupJdbcTemplate.update(
                    SQL_SET_LOCKED_UNTIL,
                    new MapSqlParameterSource()
                            .addValue(PARAM_ID,      userId)
                            .addValue("lockedUntil", Timestamp.from(now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES))));
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record UserLoginRecord(UUID id, String passwordHash, Instant lockedUntil) {}
}
