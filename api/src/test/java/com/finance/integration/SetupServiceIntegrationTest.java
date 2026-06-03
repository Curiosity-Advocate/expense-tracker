package com.finance.integration;

import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.service.AuthService;
import com.finance.service.UserSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SetupServiceIntegrationTest extends IntegrationTestBase {

    @Autowired AuthService authService;
    @Autowired UserSetupService userSetupService;

    @BeforeEach
    void wipeUserState() {
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, users RESTART IDENTITY CASCADE");
    }

    @Test
    void setupNewUser_createsCashAndCryptoSystemAccounts() {
        RegisteredUser user = authService.register(
                new RegisterCommand("ivan", "ivan@example.com", "password123"));

        userSetupService.setupNewUser(user.userId());

        var rows = setupJdbc().queryForList(
                "SELECT name, account_type, is_system FROM bank_accounts " +
                "WHERE user_id = ? ORDER BY name",
                user.userId());

        assertThat(rows).hasSize(2);

        Map<String, Object> cash = rows.get(0);
        assertThat(cash.get("name")).isEqualTo("Cash");
        assertThat(cash.get("account_type")).isEqualTo("CASH");
        assertThat(cash.get("is_system")).isEqualTo(true);

        Map<String, Object> crypto = rows.get(1);
        assertThat(crypto.get("name")).isEqualTo("Crypto");
        assertThat(crypto.get("account_type")).isEqualTo("CRYPTO");
        assertThat(crypto.get("is_system")).isEqualTo(true);
    }
}
