package com.finance.service.impl;

import com.finance.config.DataSourceConfig;
import com.finance.service.UserSetupService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Creates the system bank accounts that every user needs before they can
// log their first expense. Called immediately after successful registration.
// CASH is the default for any expense where no bank account is specified.
//
// Runs on the setup pool — no UserPrincipal exists yet so user_isolation RLS
// would otherwise reject the INSERTs. See ADR-0011.
@Service
public class DefaultUserSetupService implements UserSetupService {

    private static final String PARAM_ID      = "id";
    private static final String PARAM_USER_ID = "userId";

    private static final String SQL_INSERT_BANK_ACCOUNT =
            "INSERT INTO bank_accounts (id, user_id, name, account_type, is_system) " +
            "VALUES (:id, :userId, :name, :accountType, true)";

    private final NamedParameterJdbcTemplate setupJdbcTemplate;

    public DefaultUserSetupService(NamedParameterJdbcTemplate setupJdbcTemplate) {
        this.setupJdbcTemplate = setupJdbcTemplate;
    }

    @Override
    @Transactional(DataSourceConfig.SETUP_TX_MANAGER)
    public void setupNewUser(UUID userId) {
        createSystemAccount(userId, "Cash",   "CASH");
        createSystemAccount(userId, "Crypto", "CRYPTO");
    }

    private void createSystemAccount(UUID userId, String name, String type) {
        setupJdbcTemplate.update(
                SQL_INSERT_BANK_ACCOUNT,
                new MapSqlParameterSource()
                        .addValue(PARAM_ID,      UUID.randomUUID())
                        .addValue(PARAM_USER_ID, userId)
                        .addValue("name",        name)
                        .addValue("accountType", type));
    }
}
