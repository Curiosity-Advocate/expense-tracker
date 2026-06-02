package com.finance.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Integration tests for the CSV import connection CRUD endpoints.
// Exercises: POST (create) / GET (read) / PATCH (update) / DELETE (idempotent).
class CsvImportConnectionIntegrationTest extends WebIntegrationTestBase {

    private static final String PASSWORD = "pw_correct_12345";
    private static final String CSV_URL = "https://www.commbank.com.au/digital/your-statements";

    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("appDataSource") HikariDataSource appDataSource;

    private JdbcTemplate appJdbc;

    @BeforeEach
    void wipe() {
        appJdbc = new JdbcTemplate(appDataSource);
        appJdbc.execute("SET LOCAL app.current_user_id = '00000000-0000-0000-0000-000000000000'");
        appJdbc.execute(
                "TRUNCATE csv_imports, csv_import_connections, raw_bank_transactions, " +
                "dead_letters, bank_accounts, refresh_tokens, user_login_failures, users " +
                "RESTART IDENTITY CASCADE");
    }

    @Test
    void createReadUpdateDeleteCycle() {
        registerAndLogin("alice");
        UUID bankAccountId = insertBankAccount("alice", "CBA Everyday", "BANK");
        String token = login("alice");

        // POST — create
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.POST,
                new HttpEntity<>(body("bankId", "cba", "csvExportUrl", CSV_URL), bearer(token)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("bankId")).isEqualTo("cba");
        assertThat(created.getBody().get("csvExportUrl")).isEqualTo(CSV_URL);

        // GET — read
        ResponseEntity<Map> read = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().get("bankId")).isEqualTo("cba");

        // PATCH — update URL only (bankId unchanged)
        String newUrl = CSV_URL + "/updated";
        ResponseEntity<Map> patched = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.PATCH,
                new HttpEntity<>(body("csvExportUrl", newUrl), bearer(token)),
                Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("csvExportUrl")).isEqualTo(newUrl);
        assertThat(patched.getBody().get("bankId")).isEqualTo("cba");

        // DELETE — idempotent
        ResponseEntity<Void> deleted = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // GET after delete → 404
        ResponseEntity<Map> readAgain = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(readAgain.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // DELETE again → still 204 (idempotent)
        ResponseEntity<Void> deletedAgain = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
        assertThat(deletedAgain.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void createReturns409OnDuplicate() {
        registerAndLogin("alice");
        UUID bankAccountId = insertBankAccount("alice", "CBA Everyday", "BANK");
        String token = login("alice");

        // First create
        http.exchange("/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.POST,
                new HttpEntity<>(body("bankId", "cba"), bearer(token)),
                Map.class);

        // Second create on the same bank account → 409
        ResponseEntity<Map> dup = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.POST,
                new HttpEntity<>(body("bankId", "cba"), bearer(token)),
                Map.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> error = (Map<?, ?>) dup.getBody().get("error");
        assertThat(error.get("code")).isEqualTo("CSV_IMPORT_CONNECTION_EXISTS");
    }

    @Test
    void createReturns422OnUnknownBank() {
        registerAndLogin("alice");
        UUID bankAccountId = insertBankAccount("alice", "Some Account", "BANK");
        String token = login("alice");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.POST,
                new HttpEntity<>(body("bankId", "nonexistent-bank"), bearer(token)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<?, ?> error = (Map<?, ?>) r.getBody().get("error");
        assertThat(error.get("code")).isEqualTo("UNKNOWN_BANK_ID");
    }

    @Test
    void getReturns404WhenNoConnectionSetUp() {
        registerAndLogin("alice");
        UUID bankAccountId = insertBankAccount("alice", "CBA Everyday", "BANK");
        String token = login("alice");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> error = (Map<?, ?>) r.getBody().get("error");
        assertThat(error.get("code")).isEqualTo("CSV_IMPORT_NOT_CONFIGURED");
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    private void registerAndLogin(String username) {
        ResponseEntity<Map> r = http.postForEntity("/api/v1/auth/register",
                body("username", username, "email", username + "@x.com", "password", PASSWORD),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String login(String username) {
        ResponseEntity<Map> r = http.postForEntity("/api/v1/auth/login",
                body("username", username, "password", PASSWORD), Map.class);
        return (String) r.getBody().get("accessToken");
    }

    private UUID insertBankAccount(String username, String accountName, String accountType) {
        UUID userId = appJdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", UUID.class, username);
        UUID accountId = UUID.randomUUID();
        appJdbc.execute("SET LOCAL app.current_user_id = '" + userId + "'");
        appJdbc.update(
                "INSERT INTO bank_accounts (id, user_id, name, account_type, is_system) " +
                "VALUES (?, ?, ?, ?, FALSE)",
                accountId, userId, accountName, accountType);
        return accountId;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
