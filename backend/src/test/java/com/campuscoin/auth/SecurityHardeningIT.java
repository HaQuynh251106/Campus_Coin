package com.campuscoin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class SecurityHardeningIT extends AbstractMySqlIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    private static final String RESET_REQUEST_URL = "/api/v1/auth/password-reset/request";
    private static final String RESET_COMPLETE_URL = "/api/v1/auth/password-reset/complete";

    private static final String PASSWORD = "Student@123";

    private static final String DEFAULT_EXPENSE_NAME = "Food";

    private static final List<String> FORBIDDEN_RESPONSE_FIELDS = List.of(
            "passwordHash", "password_hash",
            "tokenVersion", "token_version",
            "sessionToken", "session_token", "refreshToken", "refresh_token",
            "tokenHash", "token_hash", "resetToken", "reset_token",
            "\"password\"");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Section 7.2: no response body carries a password, a hash or an internal token")
    void responsesCarryNoSensitiveField() throws Exception {
        String email = randomEmail();

        String registerBody = post(REGISTER_URL, Map.of(
                "fullName", "Hardening Student",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD)).getBody();

        String loginBody = post(LOGIN_URL, Map.of("email", email, "password", PASSWORD)).getBody();

        String resetRequestBody = post(RESET_REQUEST_URL, Map.of("email", email)).getBody();

        String failedLoginBody = post(LOGIN_URL,
                Map.of("email", email, "password", "WrongPass@123")).getBody();

        String duplicateBody = post(REGISTER_URL, Map.of(
                "fullName", "Again",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD)).getBody();

        for (Map.Entry<String, String> response : Map.of(
                "register", nullToEmpty(registerBody),
                "login", nullToEmpty(loginBody),
                "reset-request", nullToEmpty(resetRequestBody),
                "failed-login", nullToEmpty(failedLoginBody),
                "duplicate-register", nullToEmpty(duplicateBody)).entrySet()) {
            assertThat(response.getValue())
                    .as("%s must not expose a security field", response.getKey())
                    .doesNotContain(FORBIDDEN_RESPONSE_FIELDS.toArray(String[]::new));
        }

        assertThat(nullToEmpty(failedLoginBody)).doesNotContain("WrongPass@123");
        assertThat(nullToEmpty(duplicateBody)).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("Section 7.7: an error body carries no stack trace, SQL or driver detail")
    void errorBodiesCarryNoInternals() throws Exception {

        String email = randomEmail();
        post(REGISTER_URL, Map.of("fullName", "First", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD));
        String body = post(REGISTER_URL, Map.of("fullName", "Second", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD)).getBody();

        assertThat(nullToEmpty(body)).doesNotContain(
                "uk_users_email", "INSERT", "SELECT", "java.", "springframework",
                "SQLException", "stackTrace", "trace", "at com.campuscoin", "hibernate");

        JsonNode error = objectMapper.readTree(body);
        assertThat(error.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "errorCode", "message", "path");
    }

    @Test
    @DisplayName("BR-01: the stored password is a bcrypt hash, never the submitted value")
    void passwordIsStoredOnlyAsABcryptHash() throws Exception {
        String email = randomEmail();
        post(REGISTER_URL, Map.of("fullName", "Hash Check", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD));

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT password_hash FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                String stored = row.getString("password_hash");
                assertThat(stored).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);

                assertThat(stored).startsWith("$2");
            }
        }
    }

    @Test
    @DisplayName("BR-01: the seeded accounts keep their $2y hashes and still verify")
    void seededHashesStillVerify() throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT email, password_hash FROM users WHERE email IN (?, ?)")) {
            statement.setString(1, SEEDED_STUDENT_EMAIL);
            statement.setString(2, SEEDED_ADMIN_EMAIL);
            try (ResultSet rows = statement.executeQuery()) {
                int checked = 0;
                while (rows.next()) {

                    assertThat(rows.getString("password_hash")).startsWith("$2y$");
                    checked++;
                }
                assertThat(checked).as("both seeded accounts are present").isEqualTo(2);
            }
        }

        assertThat(post(LOGIN_URL, Map.of("email", SEEDED_STUDENT_EMAIL,
                "password", SEEDED_STUDENT_PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(ADMIN_LOGIN_URL, Map.of("email", SEEDED_ADMIN_EMAIL,
                "password", SEEDED_ADMIN_PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Section 7.7 / BR-04: the database holds only the reset token's hash")
    void resetTokenIsStoredHashedOnly() throws Exception {
        String email = randomEmail();
        post(REGISTER_URL, Map.of("fullName", "Token Check", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD));
        post(RESET_REQUEST_URL, Map.of("email", email));

        String rawToken = latestRawTokenFor(email);
        assertThat(rawToken).isNotBlank();

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT t.token_hash FROM password_reset_tokens t JOIN users u ON u.id = t.user_id "
                             + "WHERE u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();

                assertThat(row.getString("token_hash")).isNotEqualTo(rawToken).hasSize(64);
            }
        }
    }

    @Test
    @DisplayName("Section 7.6: neither the reset token nor the access token appears in the log")
    void tokensNeverReachTheLog(CapturedOutput output) throws Exception {
        String email = randomEmail();
        post(REGISTER_URL, Map.of("fullName", "Log Check", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD));

        String accessToken = objectMapper.readTree(
                post(LOGIN_URL, Map.of("email", email, "password", PASSWORD)).getBody())
                .get("accessToken").asText();

        post(RESET_REQUEST_URL, Map.of("email", email));
        String resetToken = latestRawTokenFor(email);

        post(RESET_COMPLETE_URL, Map.of("token", resetToken, "newPassword", "Student@456",
                "confirmPassword", "Student@456"));

        assertThat(accessToken).isNotBlank();
        assertThat(output.getOut() + output.getErr())
                .as("neither token may be written to the log")
                .doesNotContain(accessToken)
                .doesNotContain(resetToken);
    }

    @Test
    @DisplayName("Section 7.6: a refused category write logs no schema identifier")
    void categoryRefusalsLogNoSchemaIdentifiers(CapturedOutput output) throws Exception {

        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        createCategory(token, Map.of("name", "Log Probe", "type", "EXPENSE"));
        createCategory(token, Map.of("name", "Log Probe", "type", "EXPENSE"));
        createCategory(token, Map.of("name", DEFAULT_EXPENSE_NAME, "type", "EXPENSE"));

        String referenced = objectMapper.readTree(
                createCategory(token, Map.of("name", "Referenced Probe", "type", "EXPENSE"))
                        .getBody()).get("id").asText();
        insertTransaction(userId, Long.valueOf(referenced));
        patchCategoryType(token, referenced, "INCOME");

        String log = output.getOut() + output.getErr();
        assertThat(log)
                .as("a refused write must not name the schema in the log")
                .doesNotContain("uk_categories_scope_type_name")
                .doesNotContain("scope_key")
                .doesNotContain("Duplicate entry")
                .doesNotContain("trg_categories")
                .doesNotContain("SIGNAL SQLSTATE");

        assertThat(log).contains("Category write rejected");
    }

    @Test
    @DisplayName("Section 7.6: a refused transaction request keeps the schema out of the response")
    void transactionRefusalsKeepTheSchemaOutOfEverything(CapturedOutput output) throws Exception {

        String email = randomEmail();
        register(email);
        String token = login(email);

        String futureDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusDays(1).toString();
        String futureResponse = postTransaction(token, Map.of(
                "categoryId", seededCategoryId("Food"), "amount", "1.00",
                "txnDate", futureDate)).getBody();
        assertThat(futureResponse)
                .doesNotContain("sp_validate_transaction", "trg_transactions", "BR-08", "SIGNAL",
                        "txn_date", "sqlstate");

        String otherStudentsCategory = String.valueOf(anotherStudentsCategory());
        String foreignCategoryResponse = postTransaction(token, Map.of(
                "categoryId", otherStudentsCategory, "amount", "1.00",
                "txnDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toString())).getBody();
        assertThat(foreignCategoryResponse)
                .doesNotContain("sp_validate_transaction", "BR-02", "SIGNAL", "user_id",
                        "fk_txn_category", "categories");

        Long userId = userIdOf(email);
        Long transactionId = insertTransactionReturningId(userId, seededCategoryId("Allowance"));
        deleteTransaction(token, transactionId);

        String log = output.getOut() + output.getErr();

        List<String> moduleLines = log.lines()
                .filter(line -> line.contains("c.c.t.service.TransactionService"))
                .toList();

        assertThat(moduleLines)
                .as("the module's own log lines must not name the schema")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain("sp_validate_transaction")
                        .doesNotContain("sp_soft_delete_transaction")
                        .doesNotContain("sp_restore_transaction")
                        .doesNotContain("trg_transactions")
                        .doesNotContain("ck_txn_amount")
                        .doesNotContain("fk_txn_category")
                        .doesNotContain("SIGNAL SQLSTATE")
                        .doesNotContain("BR-02")
                        .doesNotContain("BR-08")
                        .doesNotContain("is_deleted"));

        assertThat(log).contains("Transaction soft-deleted");
    }

    @Test
    @DisplayName("Section 7.5: a role sent by the client is ignored, not honoured")
    void clientSuppliedRoleIsIgnored() throws Exception {
        String email = randomEmail();

        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Would Be Admin",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD,
                "role", "ADMIN",
                "status", "ACTIVE",
                "id", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, status, id FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("role")).as("UC-01 postcondition: STUDENT").isEqualTo("STUDENT");
                assertThat(row.getString("status")).isEqualTo("ACTIVE");
                assertThat(row.getLong("id")).as("the supplied id is not used").isNotEqualTo(1L);
            }
        }

        assertThat(post(ADMIN_LOGIN_URL, Map.of("email", email, "password", PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Section 7.5: identity comes from the token, so no body field can redirect a logout")
    void logoutIgnoresClientSuppliedIdentity() throws Exception {
        String victimEmail = randomEmail();
        String attackerEmail = randomEmail();
        register(victimEmail);
        register(attackerEmail);

        String victimSession = login(victimEmail);
        String attackerSession = login(attackerEmail);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(attackerSession);
        ResponseEntity<String> response = restTemplate.exchange(LOGOUT_URL, HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", userIdOf(victimEmail), "email", victimEmail),
                        headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(exchange(LOGOUT_URL, attackerSession).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(LOGOUT_URL, victimSession).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("Section 7.5: a missing token is 401 and a wrong role is 403")
    void unauthenticatedAndForbiddenAreDistinct() throws Exception {
        String email = randomEmail();
        register(email);
        String studentToken = login(email);

        assertThat(exchange(PROTECTED_ADMIN_ROUTE, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(exchange(PROTECTED_ADMIN_ROUTE, "garbage").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(exchange(PROTECTED_ADMIN_ROUTE, studentToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Section 7.10: repeated sign-in failures are answered with 429")
    void loginThrottleEventuallyRefuses() throws Exception {

        String email = "throttle." + UUID.randomUUID() + "@example.com";

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(post(LOGIN_URL, Map.of("email", email, "password", "WrongPass@123"))
                    .getStatusCode())
                    .as("attempt %d is under the limit", attempt + 1)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> throttled = post(LOGIN_URL,
                Map.of("email", email, "password", "WrongPass@123"));
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(objectMapper.readTree(throttled.getBody()).get("errorCode").asText())
                .isEqualTo("TOO_MANY_ATTEMPTS");

        assertThat(post(LOGIN_URL, Map.of("email", randomEmail(), "password", PASSWORD))
                .getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Section 7.10: a successful sign-in clears the failure counter")
    void successfulLoginClearsTheThrottle() throws Exception {
        String email = randomEmail();
        register(email);

        for (int attempt = 0; attempt < 4; attempt++) {
            post(LOGIN_URL, Map.of("email", email, "password", "WrongPass@123"));
        }

        assertThat(post(LOGIN_URL, Map.of("email", email, "password", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(post(LOGIN_URL, Map.of("email", email, "password", "WrongPass@123"))
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("Section 7.10 / UC-03 A2: reset requests are throttled for unknown addresses too")
    void resetRequestsAreThrottledRegardlessOfAccountExistence() throws Exception {

        String unknownEmail = "flood." + UUID.randomUUID() + "@example.com";

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(post(RESET_REQUEST_URL, Map.of("email", unknownEmail)).getStatusCode())
                    .as("request %d is under the limit", attempt + 1)
                    .isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> throttled = post(RESET_REQUEST_URL, Map.of("email", unknownEmail));
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(objectMapper.readTree(throttled.getBody()).get("errorCode").asText())
                .isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    @DisplayName("Section 7.8: an allowed origin is echoed, and the policy is never a wildcard")
    void allowedOriginIsEchoedAndNeverWildcarded() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setOrigin("http://localhost:4200");

        ResponseEntity<String> response = restTemplate.exchange(LOGOUT_URL, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:4200");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNotEqualTo("*");
    }

    @Test
    @DisplayName("Section 7.8: an unlisted origin is refused before the request is served")
    void unlistedOriginIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setOrigin("http://evil.example.com");

        ResponseEntity<String> response = restTemplate.exchange(LOGOUT_URL, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("Section 7.7: the health probe answers without a token and discloses no internals")
    void healthProbeIsReachableButSaysNothingInternal() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).doesNotContain("db", "database", "HikariPool", "diskSpace");
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("Section 7.7: a non-allow-listed actuator endpoint is not readable without a token")
    void actuatorEndpointsOutsideTheAllowListRequireAuthentication() {

        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.6: no default in-memory account is created, so no generated password is logged")
    void noDefaultInMemoryUserIsProvisioned() {

        assertThat(context.getBeansOfType(UserDetailsService.class))
                .as("no UserDetailsService is defined, so no generated password can be logged")
                .isEmpty();
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class))
                .as("the auto-configured in-memory user is absent")
                .isEmpty();
    }

    private static final String PROTECTED_ADMIN_ROUTE = "/api/v1/admin/auth/protected-route-probe";

    private static String randomEmail() {
        return "hardening." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void register(String email) {
        assertThat(post(REGISTER_URL, Map.of("fullName", "Hardening Student", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = post(LOGIN_URL, Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private Long userIdOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private String latestRawTokenFor(String email) throws Exception {
        List<String> lines = Files.readAllLines(RESET_SINK, StandardCharsets.UTF_8);
        return lines.stream()
                .filter(line -> line.contains(" | " + email + " | "))
                .reduce((first, second) -> second)
                .map(line -> line.substring(line.lastIndexOf("token=") + "token=".length()))
                .orElseThrow(() -> new AssertionError("No reset link was written for " + email));
    }

    private ResponseEntity<String> post(String url, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> createCategory(String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/categories", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void insertTransaction(Long userId, Long categoryId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO transactions (user_id, category_id, amount, txn_date, source) "
                             + "VALUES (?, ?, 10.00, CURDATE(), 'MANUAL')")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.executeUpdate();
        }
    }

    private void patchCategoryType(String token, String categoryId, String type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/v1/categories/" + categoryId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("type", type), headers), String.class);
    }

    private ResponseEntity<String> exchange(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postTransaction(String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/transactions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void deleteTransaction(String token, Long transactionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/v1/transactions/" + transactionId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }

    private Long seededCategoryId(String name) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM categories WHERE user_id IS NULL AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("seeded default category %s", name).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Long anotherStudentsCategory() throws Exception {
        String otherEmail = randomEmail();
        register(otherEmail);
        String otherToken = login(otherEmail);
        ResponseEntity<String> created = createCategory(otherToken,
                Map.of("name", "Not Yours " + otherEmail, "type", "EXPENSE"));
        return objectMapper.readTree(created.getBody()).get("id").asLong();
    }

    private Long insertTransactionReturningId(Long userId, Long categoryId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO transactions (user_id, category_id, amount, txn_date, source) "
                             + "VALUES (?, ?, 10.00, CURDATE(), 'MANUAL')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }
}
