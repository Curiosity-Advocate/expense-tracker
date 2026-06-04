package com.finance.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end test of the D1 → D2 → D3 → S5 delegation chain via real HTTP.
// Extends WebIntegrationTestBase (RANDOM_PORT) to exercise the full filter
// chain — JwtAuthenticationFilter → AsUserIdFilter → controller dispatch.
class DelegationIntegrationTest extends WebIntegrationTestBase {

    private static final String CORRECT_PW = "pw_correct";
    private static final String READ_WRITE = "READ_WRITE";

    @Autowired TestRestTemplate http;

    @BeforeEach
    void wipe() {
        setupJdbc().execute("TRUNCATE user_login_failures, bank_accounts, sudo_tokens, "
                + "access_grants, users RESTART IDENTITY CASCADE");
        // TRUNCATE users CASCADE also wipes the V14-seeded global system
        // categories (categories.user_id FKs to users). Re-seed the one the
        // delegated-expense test uses so the category lookup resolves.
        setupJdbc().update("INSERT INTO categories (id, user_id, name, description) "
                + "VALUES (gen_random_uuid(), NULL, 'Uncategorised', 'Default for unsorted expenses') "
                + "ON CONFLICT DO NOTHING");
    }

    // ── Helpers — HTTP-driven setup ──────────────────────────────────────────

    private void register(String username) {
        ResponseEntity<Map> r = http.postForEntity("/api/v1/auth/register",
                body("username", username, "email", username + "@x.com", "password", CORRECT_PW),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String login(String username) {
        ResponseEntity<Map> r = http.postForEntity("/api/v1/auth/login",
                body("username", username, "password", CORRECT_PW), Map.class);
        return (String) r.getBody().get("accessToken");
    }

    private UUID userIdByUsername(String username) {
        return setupJdbc().queryForObject("SELECT id FROM users WHERE username = ?",
                UUID.class, username);
    }

    private void markDiscoverable(String username) {
        setupJdbc().update("UPDATE users SET is_discoverable = TRUE WHERE username = ?", username);
    }

    private UUID createGrant(String grantorToken, String granteeUsername) {
        HttpHeaders h = bearer(grantorToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.exchange("/api/v1/users/me/access-grants",
                HttpMethod.POST,
                new HttpEntity<>(body("granteeUsername", granteeUsername,
                                      "accessLevel", READ_WRITE,
                                      "expiresInDays", 7), h),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private String mintSudoToken(String granteeToken, UUID grantId) {
        HttpHeaders h = bearer(granteeToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.exchange("/api/v1/auth/sudo-tokens",
                HttpMethod.POST,
                new HttpEntity<>(body("grantId", grantId.toString(),
                                      "password", CORRECT_PW), h),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("sudoToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── Test 1: no asUserId → normal request flow ────────────────────────────

    @Test
    void requestWithoutAsUserId_passesThroughUnchanged() {
        register("alice");
        String aToken = login("alice");

        ResponseEntity<Map> r = http.exchange("/api/v1/expenses",
                HttpMethod.GET, new HttpEntity<>(bearer(aToken)), Map.class);

        // The list endpoint should succeed (200) even with no expenses — proving
        // the filter chain passed through without rejecting on a missing
        // asUserId param.
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ── Test 2: full chain — B creates an expense for A via delegation ───────

    @Test
    void delegatedExpenseCreate_storesOwnerAsGrantor_andAuditAsGrantee() {
        register("alice");
        register("bob");
        markDiscoverable("bob");

        UUID aliceId = userIdByUsername("alice");
        UUID bobId   = userIdByUsername("bob");
        String aToken = login("alice");
        String bToken = login("bob");

        UUID grantId = createGrant(aToken, "bob");
        String sudo  = mintSudoToken(bToken, grantId);

        // Alice has a Cash bank account from DefaultUserSetupService at registration.
        UUID aliceCashAccount = setupJdbc().queryForObject(
                "SELECT id FROM bank_accounts WHERE user_id = ? AND name = 'Cash'",
                UUID.class, aliceId);

        // Bob creates an expense FOR ALICE via delegation.
        HttpHeaders h = bearer(bToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Sudo-Token", sudo);

        Map<String, Object> expense = body(
                "amount", new BigDecimal("10.00"),
                "merchantName", "TestMerchant",
                "expenseDate", LocalDate.now().toString(),
                "paymentMethod", "CASH",
                "bankAccountId", aliceCashAccount.toString(),
                "categories", List.of("Uncategorised"),
                "idempotencyKey", UUID.randomUUID().toString());

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + aliceId,
                HttpMethod.POST,
                new HttpEntity<>(expense, h),
                Map.class);

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();

        // Verify the row landed under Alice's user_id with Bob as the actor.
        Map<String, Object> row = setupJdbc().queryForMap(
                "SELECT user_id, created_by, modified_by FROM expenses "
                        + "WHERE user_id = ? ORDER BY expense_date DESC LIMIT 1",
                aliceId);
        assertThat((UUID) row.get("user_id")).isEqualTo(aliceId);
        assertThat((UUID) row.get("created_by")).isEqualTo(bobId);
        assertThat((UUID) row.get("modified_by")).isEqualTo(bobId);
    }

    // ── Test 3: non-delegation endpoint with asUserId → 403 ──────────────────

    @Test
    void asUserIdOnNonDelegationEndpoint_returns403() {
        register("alice");
        register("bob");
        markDiscoverable("bob");
        UUID aliceId = userIdByUsername("alice");
        String aToken = login("alice");
        String bToken = login("bob");
        UUID grantId  = createGrant(aToken, "bob");
        String sudo   = mintSudoToken(bToken, grantId);

        HttpHeaders h = bearer(bToken);
        h.set("X-Sudo-Token", sudo);

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/categories?asUserId=" + aliceId,
                HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((Map<?, ?>) r.getBody().get("error")).get("code"))
                .isEqualTo("ASUSER_NOT_ALLOWED_HERE");
    }

    // ── Test 4: asUserId without sudo-token header → 401 ─────────────────────

    @Test
    void asUserIdWithoutSudoTokenHeader_returns401() {
        register("alice");
        register("bob");
        UUID aliceId = userIdByUsername("alice");
        String bToken = login("bob");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + aliceId,
                HttpMethod.GET, new HttpEntity<>(bearer(bToken)), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((Map<?, ?>) r.getBody().get("error")).get("code"))
                .isEqualTo("INVALID_SUDO_TOKEN");
    }

    // ── Test 5: asUserId with random sudo token → 401 ────────────────────────

    @Test
    void asUserIdWithUnknownSudoToken_returns401() {
        register("alice");
        register("bob");
        UUID aliceId = userIdByUsername("alice");
        String bToken = login("bob");

        HttpHeaders h = bearer(bToken);
        h.set("X-Sudo-Token", "not-a-real-token-just-random-bytes");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + aliceId,
                HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Test 6: grant revoked between mint and delegated request → 401 ──────

    @Test
    void grantRevokedAfterSudoTokenMint_returns401() {
        register("alice");
        register("bob");
        markDiscoverable("bob");
        UUID aliceId = userIdByUsername("alice");
        String aToken = login("alice");
        String bToken = login("bob");
        UUID grantId  = createGrant(aToken, "bob");
        String sudo   = mintSudoToken(bToken, grantId);

        // Alice revokes the grant after Bob has minted the sudo token.
        HttpHeaders revokeH = bearer(aToken);
        ResponseEntity<Void> revokeR = http.exchange(
                "/api/v1/users/me/access-grants/" + grantId,
                HttpMethod.DELETE, new HttpEntity<>(revokeH), Void.class);
        assertThat(revokeR.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Bob's previously-minted sudo token must now fail verify.
        HttpHeaders h = bearer(bToken);
        h.set("X-Sudo-Token", sudo);
        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + aliceId,
                HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Test 7: sudo token's grantor mismatches asUserId → 401 ──────────────

    @Test
    void grantorMismatchBetweenSudoTokenAndAsUserId_returns401() {
        register("alice");
        register("carol");
        register("bob");
        markDiscoverable("bob");
        UUID carolId = userIdByUsername("carol");

        String aToken = login("alice");
        String bToken = login("bob");

        // Alice grants Bob access, Bob mints a sudo token for that grant.
        UUID aliceGrantId = createGrant(aToken, "bob");
        String sudo = mintSudoToken(bToken, aliceGrantId);

        // Bob tries to use it claiming to act as Carol (whose grant doesn't exist).
        HttpHeaders h = bearer(bToken);
        h.set("X-Sudo-Token", sudo);
        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + carolId,
                HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Test 8: asUserId equals self → pass-through, no substitution ─────────

    @Test
    void asUserIdEqualsSelf_passesThroughWithoutSubstitution() {
        register("alice");
        UUID aliceId = userIdByUsername("alice");
        String aToken = login("alice");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=" + aliceId,
                HttpMethod.GET, new HttpEntity<>(bearer(aToken)), Map.class);

        // Self-delegation = no-op. Should succeed without the X-Sudo-Token
        // header, just like a normal non-delegated request.
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ── Test 9: malformed UUID → 400 ─────────────────────────────────────────

    @Test
    void asUserIdNotAUuid_returns400() {
        register("alice");
        String aToken = login("alice");

        ResponseEntity<Map> r = http.exchange(
                "/api/v1/expenses?asUserId=not-a-uuid",
                HttpMethod.GET, new HttpEntity<>(bearer(aToken)), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) r.getBody().get("error")).get("code"))
                .isEqualTo("VALIDATION_ERROR");
    }
}
