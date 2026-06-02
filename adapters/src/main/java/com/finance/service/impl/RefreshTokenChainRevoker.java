package com.finance.service.impl;

import com.finance.config.DataSourceConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Cascade-revokes every active refresh token for a user — fired by reuse
// detection in PostgresAuthService.refresh(). Lives in its own bean so Spring's
// proxy can apply REQUIRES_NEW: the cascade must commit even when the outer
// refresh() transaction rolls back via RefreshTokenReuseException.
//
// Without REQUIRES_NEW the cascade UPDATE would be part of refresh()'s own
// transaction and would roll back when the exception propagates, defeating
// the security signal.
@Component
public class RefreshTokenChainRevoker {

    private static final String SQL_REVOKE_USER_ACTIVE_CHAINS =
            "UPDATE refresh_tokens " +
            "SET revoked_at = NOW(), revoke_reason = 'REUSE_DETECTED' " +
            "WHERE user_id = :userId AND revoked_at IS NULL";

    private final NamedParameterJdbcTemplate setupJdbcTemplate;

    public RefreshTokenChainRevoker(NamedParameterJdbcTemplate setupJdbcTemplate) {
        this.setupJdbcTemplate = setupJdbcTemplate;
    }

    @Transactional(value = DataSourceConfig.SETUP_TX_MANAGER,
                   propagation = Propagation.REQUIRES_NEW)
    public void cascadeRevoke(UUID userId) {
        setupJdbcTemplate.update(
                SQL_REVOKE_USER_ACTIVE_CHAINS,
                new MapSqlParameterSource("userId", userId));
    }
}
