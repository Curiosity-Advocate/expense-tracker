package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RefreshTokenCommand;
import com.finance.command.RegisterCommand;
import com.finance.config.DataSourceConfig;
import com.finance.config.JwtProperties;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.InvalidRefreshTokenException;
import com.finance.exception.RefreshTokenReuseException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.security.JwtService;
import com.finance.security.SecureTokenGenerator;
import com.finance.security.SecureTokenGenerator.GeneratedToken;
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

// All three pre-auth flows (register, login, refresh) and logout route through
// setupJdbcTemplate on the setup pool. expense_setup has BYPASSRLS, which is
// required because /refresh and /logout look up rows by token_hash before any
// UserPrincipal is in scope — RLS would otherwise hide the row. See ADR-0011.
@Service
public class PostgresAuthService implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS    = 5;
    private static final int FAILURE_WINDOW_MINUTES = 10;
    private static final int LOCKOUT_MINUTES        = 15;

    // JDBC named-parameter keys.
    private static final String PARAM_USERNAME           = "username";
    private static final String PARAM_EMAIL              = "email";
    private static final String PARAM_ID                 = "id";
    private static final String PARAM_USER_ID            = "userId";
    private static final String PARAM_TOKEN_HASH         = "tokenHash";
    private static final String PARAM_SESSION_STARTED_AT = "sessionStartedAt";
    private static final String PARAM_EXPIRES_AT         = "expiresAt";
    private static final String PARAM_ROTATED_FROM       = "rotatedFrom";
    private static final String PARAM_REASON             = "reason";

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

    private static final String SQL_INSERT_REFRESH_TOKEN =
            "INSERT INTO refresh_tokens " +
            "(token_hash, user_id, session_started_at, expires_at, rotated_from) " +
            "VALUES (:tokenHash, :userId, :sessionStartedAt, :expiresAt, :rotatedFrom)";

    // JOIN users on lookup so we can fetch username for the new access token
    // claim in one round-trip. Both tables are accessible from the setup pool.
    private static final String SQL_FIND_REFRESH_TOKEN =
            "SELECT rt.user_id, u.username, rt.session_started_at, rt.expires_at, rt.revoked_at " +
            "FROM refresh_tokens rt " +
            "JOIN users u ON u.id = rt.user_id " +
            "WHERE rt.token_hash = :tokenHash";

    // Conditional UPDATE is the atomicity primitive. rowcount = 1 means we won
    // the rotation race; rowcount = 0 means the row was rotated/revoked by another
    // transaction between our SELECT and our UPDATE — handled as reuse detection
    // in the refresh path, and as silent no-op in the logout path.
    private static final String SQL_REVOKE_REFRESH_TOKEN =
            "UPDATE refresh_tokens SET revoked_at = NOW(), revoke_reason = :reason " +
            "WHERE token_hash = :tokenHash AND revoked_at IS NULL";

    private final NamedParameterJdbcTemplate setupJdbcTemplate;
    private final SecureTokenGenerator      tokenGenerator;
    private final RefreshTokenChainRevoker   chainRevoker;
    private final BCryptPasswordEncoder      passwordEncoder;
    private final JwtService                 jwtService;
    private final JwtProperties              jwtProperties;
    private final Clock                      clock;

    // Hash compared against when a username doesn't exist. Generated once at
    // startup with the same encoder, so a non-existent-user login takes the
    // same ~100ms as a wrong-password login — prevents username enumeration
    // via response-time analysis.
    private final String                     timingDefenceHash;

    public PostgresAuthService(NamedParameterJdbcTemplate setupJdbcTemplate,
                               SecureTokenGenerator tokenGenerator,
                               RefreshTokenChainRevoker chainRevoker,
                               BCryptPasswordEncoder passwordEncoder,
                               JwtService jwtService,
                               JwtProperties jwtProperties,
                               Clock clock) {
        this.setupJdbcTemplate     = setupJdbcTemplate;
        this.tokenGenerator = tokenGenerator;
        this.chainRevoker          = chainRevoker;
        this.passwordEncoder       = passwordEncoder;
        this.jwtService            = jwtService;
        this.jwtProperties         = jwtProperties;
        this.clock                 = clock;
        this.timingDefenceHash     = passwordEncoder.encode(UUID.randomUUID().toString());
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

        // Failure rows are intentionally retained on success (not deleted)
        // so they age out via the worker's 30-day cleanup, not via login state.
        if (user.lockedUntil != null) {
            setupJdbcTemplate.update(SQL_CLEAR_LOCKED_UNTIL,
                    new MapSqlParameterSource(PARAM_ID, user.id));
        }

        // Login deliberately does NOT clean previous chains. Multiple parallel
        // chains per user are supported (multi-device sessions). v3.0's
        // "active sessions" UI will let users see/revoke individual chains.
        return issueFreshChain(user.id, command.username(), now);
    }

    @Override
    @Transactional(DataSourceConfig.SETUP_TX_MANAGER)
    public TokenPair refresh(RefreshTokenCommand command) {
        // DoS protection (refresh-spam from a stolen-but-still-valid token) is
        // out of scope for S4 — handled at the gateway by B8 rate limiting.
        String hash = tokenGenerator.hash(command.refreshToken());

        RefreshTokenRow existing;
        try {
            existing = setupJdbcTemplate.queryForObject(
                    SQL_FIND_REFRESH_TOKEN,
                    new MapSqlParameterSource(PARAM_TOKEN_HASH, hash),
                    (rs, n) -> new RefreshTokenRow(
                            (UUID) rs.getObject("user_id"),
                            rs.getString("username"),
                            rs.getTimestamp("session_started_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            toInstant(rs.getTimestamp("revoked_at"))));
        } catch (EmptyResultDataAccessException e) {
            throw new InvalidRefreshTokenException();
        }

        Instant now = Instant.now(clock);

        // Expired: past the session_started_at + 7-day window. No cascade —
        // expiry is benign, not a compromise signal.
        if (existing.expiresAt.isBefore(now)) {
            throw new InvalidRefreshTokenException();
        }

        // Already revoked: this is a replay of a token whose chain link has
        // moved on. Treat as reuse — cascade-revoke every active chain for
        // this user before throwing.
        if (existing.revokedAt != null) {
            chainRevoker.cascadeRevoke(existing.userId);
            throw new RefreshTokenReuseException();
        }

        // Conditional revoke. rowcount = 0 means another transaction won the
        // race between our SELECT above and this UPDATE — that token is now
        // revoked, so this attempt is effectively reuse.
        int rowsAffected = setupJdbcTemplate.update(
                SQL_REVOKE_REFRESH_TOKEN,
                new MapSqlParameterSource()
                        .addValue(PARAM_TOKEN_HASH, hash)
                        .addValue(PARAM_REASON,     "ROTATED"));

        if (rowsAffected == 0) {
            chainRevoker.cascadeRevoke(existing.userId);
            throw new RefreshTokenReuseException();
        }

        // Insert the new chain link. session_started_at is copied UNCHANGED
        // from the parent so the chain cannot extend past the original-login
        // + 7-day window — max-session cap.
        GeneratedToken newToken = tokenGenerator.generate();
        Instant newExpiresAt = existing.sessionStartedAt
                .plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS);

        setupJdbcTemplate.update(
                SQL_INSERT_REFRESH_TOKEN,
                new MapSqlParameterSource()
                        .addValue(PARAM_TOKEN_HASH,         newToken.hash())
                        .addValue(PARAM_USER_ID,            existing.userId)
                        .addValue(PARAM_SESSION_STARTED_AT, Timestamp.from(existing.sessionStartedAt))
                        .addValue(PARAM_EXPIRES_AT,         Timestamp.from(newExpiresAt))
                        .addValue(PARAM_ROTATED_FROM,       hash));

        String accessJwt    = jwtService.generateAccessToken(existing.userId, existing.username);
        Instant accessExpiry = jwtService.getExpiry(accessJwt);

        return new TokenPair(accessJwt, accessExpiry, newToken.rawToken(), newExpiresAt, "Bearer");
    }

    @Override
    @Transactional(DataSourceConfig.SETUP_TX_MANAGER)
    public void logout(String refreshToken) {
        // Idempotent — null, blank, unknown, and already-revoked tokens all
        // produce silent no-ops. No cascade: logout is a voluntary action on a
        // specific session, not a compromise signal.
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        String hash = tokenGenerator.hash(refreshToken);
        setupJdbcTemplate.update(
                SQL_REVOKE_REFRESH_TOKEN,
                new MapSqlParameterSource()
                        .addValue(PARAM_TOKEN_HASH, hash)
                        .addValue(PARAM_REASON,     "LOGOUT"));
    }

    // Issues a brand-new chain (rotated_from = NULL) and returns the access +
    // refresh pair. Used by login(); not used by refresh() because refresh
    // continues an existing chain.
    private TokenPair issueFreshChain(UUID userId, String username, Instant now) {
        String accessJwt    = jwtService.generateAccessToken(userId, username);
        Instant accessExpiry = jwtService.getExpiry(accessJwt);

        GeneratedToken refresh = tokenGenerator.generate();
        Instant refreshExpiry = now.plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS);

        setupJdbcTemplate.update(
                SQL_INSERT_REFRESH_TOKEN,
                new MapSqlParameterSource()
                        .addValue(PARAM_TOKEN_HASH,         refresh.hash())
                        .addValue(PARAM_USER_ID,            userId)
                        .addValue(PARAM_SESSION_STARTED_AT, Timestamp.from(now))
                        .addValue(PARAM_EXPIRES_AT,         Timestamp.from(refreshExpiry))
                        .addValue(PARAM_ROTATED_FROM,       null));

        return new TokenPair(accessJwt, accessExpiry, refresh.rawToken(), refreshExpiry, "Bearer");
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

    private record RefreshTokenRow(
            UUID userId,
            String username,
            Instant sessionStartedAt,
            Instant expiresAt,
            Instant revokedAt) {}
}
