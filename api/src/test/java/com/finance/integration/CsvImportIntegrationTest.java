package com.finance.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end CSV import test: upload → async processing → status poll →
// raw_bank_transactions verification → hash chain invariant → dedup → 7-day
// rate limit → empty-CSV → connection.last_* update.
class CsvImportIntegrationTest extends WebIntegrationTestBase {

    private static final String PASSWORD = "pw_correct_12345";
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(10);

    private static final String CBA_CSV = """
            07/07/2025,-500,Transfer to xxx567 from CBA app,300
            05/07/2025,1500.50,Salary deposit ACME PAYROLL,800.50
            03/07/2025,-42.50,Woolworths 1234 SYDNEY,1300.50
            """;

    @Autowired TestRestTemplate http;

    @BeforeEach
    void wipe() {
        setupJdbc().execute(
                "TRUNCATE csv_imports, csv_import_connections, raw_bank_transactions, " +
                "dead_letters, bank_accounts, refresh_tokens, user_login_failures, users " +
                "RESTART IDENTITY CASCADE");
    }

    @Test
    void uploadCompletesAndPersistsRows() {
        Setup s = setupUserAndConnection("alice", "cba");

        // Upload
        ResponseEntity<Map> upload = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID importId = UUID.fromString((String) upload.getBody().get("importId"));
        assertThat(upload.getBody().get("parserVersionTag")).isEqualTo("csv_cba_v1");

        // Poll until done
        Map<String, Object> finalStatus = waitForCompletion(importId, s.token);
        assertThat(finalStatus.get("status")).isEqualTo("COMPLETED");
        assertThat((Integer) finalStatus.get("importedCount")).isEqualTo(3);
        assertThat((Integer) finalStatus.get("dedupedCount")).isEqualTo(0);
        assertThat((Integer) finalStatus.get("parseErrorCount")).isEqualTo(0);

        // Verify rows persisted (setup pool bypasses RLS).
        Integer rowCount = setupJdbc().queryForObject(
                "SELECT COUNT(*) FROM raw_bank_transactions WHERE user_id = ?",
                Integer.class, s.userId);
        assertThat(rowCount).isEqualTo(3);

        // Connection updated
        String lastDate = setupJdbc().queryForObject(
                "SELECT last_date_to::text FROM csv_import_connections WHERE bank_account_id = ?",
                String.class, s.bankAccountId);
        assertThat(lastDate).isEqualTo("2025-07-07");
    }

    @Test
    void hashChainCurrentHashFollowsPrevHash() {
        Setup s = setupUserAndConnection("alice", "cba");
        ResponseEntity<Map> upload = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        UUID importId = UUID.fromString((String) upload.getBody().get("importId"));
        waitForCompletion(importId, s.token);

        List<Map<String, Object>> rows = setupJdbc().queryForList(
                "SELECT prev_hash, current_hash FROM raw_bank_transactions " +
                "WHERE user_id = ? ORDER BY fetched_at ASC",
                s.userId);
        assertThat(rows).isNotEmpty();
        // First row has prev_hash = NULL
        assertThat(rows.get(0).get("prev_hash")).isNull();
        // Subsequent rows have prev_hash = previous row's current_hash
        for (int i = 1; i < rows.size(); i++) {
            String prev = (String) rows.get(i).get("prev_hash");
            String prevCurrent = (String) rows.get(i - 1).get("current_hash");
            assertThat(prev)
                    .as("row " + i + " prev_hash should equal row " + (i - 1) + " current_hash")
                    .isEqualTo(prevCurrent);
        }
    }

    @Test
    void secondUploadWithinSevenDaysGets429() {
        Setup s = setupUserAndConnection("alice", "cba");

        ResponseEntity<Map> first = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        waitForCompletion(UUID.fromString((String) first.getBody().get("importId")), s.token);

        ResponseEntity<Map> second = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map<?, ?> error = (Map<?, ?>) second.getBody().get("error");
        assertThat(error.get("code")).isEqualTo("CSV_IMPORT_RATE_LIMITED");
        assertThat(error.get("nextAllowedAt")).isNotNull();
    }

    @Test
    void reuploadAfterArtificiallyAgedConnectionDedupes() {
        // Verify dedup logic by manually ageing the connection past the 7-day
        // rate limit, re-uploading the same CSV, and checking dedupedCount == 3.
        Setup s = setupUserAndConnection("alice", "cba");

        ResponseEntity<Map> first = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        waitForCompletion(UUID.fromString((String) first.getBody().get("importId")), s.token);

        // Age the prior import past 7 days so rate-limit doesn't block
        // (setup pool bypasses RLS).
        setupJdbc().update("UPDATE csv_imports SET completed_at = NOW() - INTERVAL '8 days'");

        ResponseEntity<Map> second = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> status = waitForCompletion(
                UUID.fromString((String) second.getBody().get("importId")), s.token);
        assertThat(status.get("status")).isEqualTo("COMPLETED");
        assertThat((Integer) status.get("importedCount")).isEqualTo(0);
        assertThat((Integer) status.get("dedupedCount")).isEqualTo(3);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.finance.bankintegration.service.CsvImportStartupRecovery recovery;

    // Named-param template on the setup pool, for the named INSERT below.
    // (Distinct from the base class's setupJdbc() JdbcTemplate accessor.)
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("setupJdbcTemplate")
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate setupNamedJdbc;

    @Test
    void startupRecoveryResetsStaleRunningRowAndCompletes() {
        Setup s = setupUserAndConnection("alice", "cba");

        // Insert a stale RUNNING row directly via the setup pool (bypasses RLS).
        UUID importId = UUID.randomUUID();
        setupNamedJdbc.update("""
                INSERT INTO csv_imports
                    (id, bank_account_id, user_id, status, exported_on_date,
                     parser_version_tag, raw_csv_bytes,
                     submitted_at, started_at)
                VALUES
                    (:id, :bankAccountId, :userId, 'RUNNING', '2025-07-07',
                     'csv_cba_v1', :bytes,
                     NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '20 minutes')
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("id",            importId)
                        .addValue("bankAccountId", s.bankAccountId)
                        .addValue("userId",        s.userId)
                        .addValue("bytes",         CBA_CSV.getBytes(StandardCharsets.UTF_8)));

        // Trigger recovery — should reset to PENDING and re-kick-off async.
        recovery.run(null);

        Map<String, Object> finalStatus = waitForCompletion(importId, s.token);
        assertThat(finalStatus.get("status")).isEqualTo("COMPLETED");
        assertThat((Integer) finalStatus.get("importedCount")).isEqualTo(3);
    }

    @Test
    void emptyCsvCompletesButDoesNotTripRateLimit() {
        Setup s = setupUserAndConnection("alice", "cba");

        ResponseEntity<Map> upload = uploadCsv(s.bankAccountId, s.token, "".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> status = waitForCompletion(
                UUID.fromString((String) upload.getBody().get("importId")), s.token);
        assertThat(status.get("status")).isEqualTo("COMPLETED");
        assertThat((Integer) status.get("importedCount")).isEqualTo(0);

        // Empty completed import shouldn't trip the 7-day rate-limit window.
        ResponseEntity<Map> second = uploadCsv(s.bankAccountId, s.token, CBA_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ── Test setup helpers ──────────────────────────────────────────────

    private record Setup(UUID userId, UUID bankAccountId, String token) {}

    private Setup setupUserAndConnection(String username, String bankId) {
        registerUser(username);
        UUID userId = userIdByUsername(username);
        UUID bankAccountId = insertBankAccount(userId, "Test Account", "BANK");
        String token = login(username);
        createCsvImportConnection(bankAccountId, bankId, token);
        return new Setup(userId, bankAccountId, token);
    }

    private void registerUser(String username) {
        http.postForEntity("/api/v1/auth/register",
                body("username", username, "email", username + "@x.com", "password", PASSWORD),
                Map.class);
    }

    private UUID userIdByUsername(String username) {
        return setupJdbc().queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    private String login(String username) {
        ResponseEntity<Map> r = http.postForEntity("/api/v1/auth/login",
                body("username", username, "password", PASSWORD), Map.class);
        return (String) r.getBody().get("accessToken");
    }

    private UUID insertBankAccount(UUID userId, String name, String type) {
        UUID id = UUID.randomUUID();
        setupJdbc().update(
                "INSERT INTO bank_accounts (id, user_id, name, account_type, is_system) " +
                "VALUES (?, ?, ?, ?, FALSE)",
                id, userId, name, type);
        return id;
    }

    private void createCsvImportConnection(UUID bankAccountId, String bankId, String token) {
        http.exchange("/api/v1/bank-accounts/" + bankAccountId + "/csv-import-connection",
                HttpMethod.POST,
                new HttpEntity<>(body("bankId", bankId), jsonBearer(token)),
                Map.class);
    }

    private ResponseEntity<Map> uploadCsv(UUID bankAccountId, String token, byte[] csvBytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvBytes) {
            @Override public String getFilename() { return "test.csv"; }
        });
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/v1/bank-accounts/" + bankAccountId + "/csv-import",
                new HttpEntity<>(body, headers), Map.class);
    }

    private Map<String, Object> waitForCompletion(UUID importId, String token) {
        Instant deadline = Instant.now().plus(COMPLETION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> r = http.exchange(
                    "/api/v1/bank-data/csv-imports/" + importId,
                    HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
            String status = (String) r.getBody().get("status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) r.getBody();
                return body;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Import " + importId + " did not complete within " + COMPLETION_TIMEOUT);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders jsonBearer(String token) {
        HttpHeaders h = bearer(token);
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
