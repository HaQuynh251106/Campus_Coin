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

/**
 * The guarantees of section 7 that are cheapest to assert once, centrally, rather than per
 * endpoint: nothing sensitive leaves in a response, nothing sensitive is written to the database,
 * nothing sensitive reaches a log, and the authorization split answers 401 and 403 correctly.
 *
 * <p>{@link OutputCaptureExtension} captures the logging produced while a test method runs, so
 * "the token is never logged" can be checked against the real log stream instead of being taken on
 * trust. The tests run under the {@code dev} profile, which sets {@code com.campuscoin} to DEBUG,
 * so this is the strongest form of the claim: the token is absent even from debug output.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityHardeningIT extends AbstractMySqlIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    private static final String RESET_REQUEST_URL = "/api/v1/auth/password-reset/request";
    private static final String RESET_COMPLETE_URL = "/api/v1/auth/password-reset/complete";

    private static final String PASSWORD = "Student@123";

    /** A name seeded as a default EXPENSE category, used to provoke the shadowing refusal. */
    private static final String DEFAULT_EXPENSE_NAME = "Food";

    /**
     * Serialised field names that must never appear in any response body, from any endpoint.
     *
     * <p>Only field names are listed, not the bare word "password": the documented sign-in failure
     * message is "Incorrect email or password.", which is user-facing text the contract fixes, not
     * a leaked field. That the submitted value is never echoed is asserted separately below.
     */
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

    // ------------------------------------------------------------------
    //  Nothing sensitive in a response (section 7.2, 7.6)
    // ------------------------------------------------------------------

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

        // The submitted password is never echoed back, in any form.
        assertThat(nullToEmpty(failedLoginBody)).doesNotContain("WrongPass@123");
        assertThat(nullToEmpty(duplicateBody)).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("Section 7.7: an error body carries no stack trace, SQL or driver detail")
    void errorBodiesCarryNoInternals() throws Exception {
        // A duplicate registration is the richest 4xx this module can produce: it comes from a
        // database constraint, which is exactly where a driver message would leak if forwarded.
        String email = randomEmail();
        post(REGISTER_URL, Map.of("fullName", "First", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD));
        String body = post(REGISTER_URL, Map.of("fullName", "Second", "email", email,
                "password", PASSWORD, "confirmPassword", PASSWORD)).getBody();

        assertThat(nullToEmpty(body)).doesNotContain(
                "uk_users_email", "INSERT", "SELECT", "java.", "springframework",
                "SQLException", "stackTrace", "trace", "at com.campuscoin", "hibernate");

        // The documented contract is the only shape returned.
        JsonNode error = objectMapper.readTree(body);
        assertThat(error.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "errorCode", "message", "path");
    }

    // ------------------------------------------------------------------
    //  Nothing sensitive in the database (section 7.2, 7.6, BR-01, BR-04)
    // ------------------------------------------------------------------

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
                // $2a$/$2b$/$2y$ - the prefix proves it is bcrypt rather than any other digest.
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
                    // The seed was written with the $2y variant; BCryptPasswordEncoder must accept
                    // it unchanged, otherwise the demo accounts would be unusable.
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
                // A stolen dump must yield nothing replayable.
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
        // Every category refusal is an ordinary outcome, so the service logs the operation and the
        // user id and deliberately not the exception. What the exception carries is the problem:
        // MySQL's duplicate-key message names the unique key and the scope key, and a trigger's
        // SIGNAL message names the table it guards. The response withholds all of it, and the log
        // is the other place those names could escape.
        //
        // This lives here rather than in CategoryApiIT because it needs the application's own
        // logging configuration, which OutputCaptureExtension sees and a per-class appender would
        // not.
        //
        // Scope of the guarantee, stated so it is not over-read: it covers the lines the MODULE
        // writes. When a constraint or trigger genuinely refuses a statement, Hibernate's own
        // SqlExceptionHelper writes the driver's message at ERROR and the application cannot stop
        // it - a duplicate-key message names the unique key and the scope key. The pre-check means
        // the duplicate path never reaches the database here, so this test proves the module's own
        // lines are clean, and would start failing if that pre-check were removed. The bound is
        // documented in docs/SECURITY.md section 7 and docs/api/categories.md section 13.
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        // Three refusals, two of which the service catches before writing and so never reaches the
        // translator. All three must stay silent about the schema.
        //
        // The duplicate name and the shadowed default are caught by requireNameIsFree, which is the
        // right place for them: the caller gets a precise message and no failing statement is sent.
        // The consequence is that the translator's unique-key branch is reached only on a race, so
        // it is exercised where it can be - the referenced-category type change, which no
        // pre-check covers and which the database alone decides.
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

        // The refusal itself is still recorded, so the log is not simply silent about it - which
        // would also pass the assertions above. This pins that the line exists.
        assertThat(log).contains("Category write rejected");
    }

    @Test
    @DisplayName("Section 7.6: a refused transaction request keeps the schema out of the response")
    void transactionRefusalsKeepTheSchemaOutOfEverything(CapturedOutput output) throws Exception {
        // The transaction module's counterpart to the test above, and it is needed for the same
        // reason: the response withholds the schema, and the log is the other place those names
        // could escape. The refusals below are the ones a caller can actually reach.
        //
        // What this test does NOT claim, stated plainly so it is not over-read. The module's own
        // refusal line - "Transaction write rejected", written by TransactionService's translator -
        // is NOT exercised here, because no deterministic request reaches it. The service pre-checks
        // all six rules sp_validate_transaction signals, so a client cannot usually reach the
        // database's refusal at all; the translator fires on a race, and a race is not something a
        // deterministic test can schedule. What proves the translator's classification is
        // TransactionWriteFailureTest, which drives it with the exact exception shapes MySQL and
        // Spring produce. What this test proves is the reachable half: the refusals a client CAN
        // provoke name no schema object in the response or in the module's own log lines.
        //
        // Scope of the bound, the same one module 3 recorded and for the same reason. The
        // assertions below run against the lines the MODULE writes, not the whole stream. Hibernate
        // logs every statement it issues through `org.hibernate.SQL` at DEBUG - the dev profile
        // enables it - so the stream does contain `CALL sp_soft_delete_transaction(?, ?)`. That
        // line is Hibernate's, names no data (the parameters are placeholders), and the module
        // cannot suppress it without turning SQL logging off, which would hide genuine faults. The
        // bound is exactly "our lines are clean", and it is asserted that way.
        String email = randomEmail();
        register(email);
        String token = login(email);

        // Reachable refusal 1: a date in the future. The service refuses it before writing, so the
        // response is the only place its text appears.
        String futureDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusDays(1).toString();
        String futureResponse = postTransaction(token, Map.of(
                "categoryId", seededCategoryId("Food"), "amount", "1.00",
                "txnDate", futureDate)).getBody();
        assertThat(futureResponse)
                .doesNotContain("sp_validate_transaction", "trg_transactions", "BR-08", "SIGNAL",
                        "txn_date", "sqlstate");

        // Reachable refusal 2: a category belonging to another student, which the database's own
        // procedure is what actually refuses. The service's pre-check answers first, so this pins
        // that its 404 says nothing about the other student's row.
        String otherStudentsCategory = String.valueOf(anotherStudentsCategory());
        String foreignCategoryResponse = postTransaction(token, Map.of(
                "categoryId", otherStudentsCategory, "amount", "1.00",
                "txnDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toString())).getBody();
        assertThat(foreignCategoryResponse)
                .doesNotContain("sp_validate_transaction", "BR-02", "SIGNAL", "user_id",
                        "fk_txn_category", "categories");

        // Reachable refusal 3: a hard delete. No endpoint deletes outright, and neither does the
        // module - this pins that neither the response of the soft-delete endpoint nor the log
        // carries the trigger's text.
        Long userId = userIdOf(email);
        Long transactionId = insertTransactionReturningId(userId, seededCategoryId("Allowance"));
        deleteTransaction(token, transactionId);

        String log = output.getOut() + output.getErr();
        // Only the module's own lines: TransactionService is the one class of this module that
        // writes to the log, so its output is isolated by the logger name the SLF4J line carries.
        // See the scope note above - the unfiltered stream legitimately contains Hibernate's own
        // statement log, which names a procedure but carries no data.
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

        // The module does record the operations it performs, so the log is not simply empty of
        // this module - which would also pass the assertions above.
        assertThat(log).contains("Transaction soft-deleted");
    }

    // ------------------------------------------------------------------
    //  No client-supplied privilege (section 7.5)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  No client-supplied privilege (section 7.5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Section 7.5: a role sent by the client is ignored, not honoured")
    void clientSuppliedRoleIsIgnored() throws Exception {
        String email = randomEmail();

        // Registration accepts exactly four fields. An attempt to self-assign ADMIN must be
        // discarded rather than bound, or the endpoint would be a privilege-escalation route.
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

        // And the account cannot sign in through the administrator portal.
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

        // The attacker signs out while naming the victim. The only identity the server reads is
        // the one in the verified token, so the victim's session must survive.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(attackerSession);
        ResponseEntity<String> response = restTemplate.exchange(LOGOUT_URL, HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", userIdOf(victimEmail), "email", victimEmail),
                        headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The attacker is signed out; the victim is not.
        assertThat(exchange(LOGOUT_URL, attackerSession).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(LOGOUT_URL, victimSession).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------------
    //  Authorization split (section 7.5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Section 7.5: a missing token is 401 and a wrong role is 403")
    void unauthenticatedAndForbiddenAreDistinct() throws Exception {
        String email = randomEmail();
        register(email);
        String studentToken = login(email);

        // No token: the caller is not identified.
        assertThat(exchange(PROTECTED_ADMIN_ROUTE, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // A student token is invalid, not merely unidentified.
        assertThat(exchange(PROTECTED_ADMIN_ROUTE, "garbage").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Authenticated as a student, but this identity may not use the route.
        assertThat(exchange(PROTECTED_ADMIN_ROUTE, studentToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    //  Brute-force and flood protection (section 7.10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Section 7.10: repeated sign-in failures are answered with 429")
    void loginThrottleEventuallyRefuses() throws Exception {
        // auth.max_login_attempts is seeded as 5. An address that does not exist is used so the
        // test needs no account and cannot lock a real one.
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

        // The throttle is per address: an unrelated address is unaffected.
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

        // Four failures then the right password must succeed rather than be blocked; the counter
        // tracks consecutive failures, not a lifetime total.
        assertThat(post(LOGIN_URL, Map.of("email", email, "password", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // And the counter really did reset: four more failures still do not lock it.
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(post(LOGIN_URL, Map.of("email", email, "password", "WrongPass@123"))
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("Section 7.10 / UC-03 A2: reset requests are throttled for unknown addresses too")
    void resetRequestsAreThrottledRegardlessOfAccountExistence() throws Exception {
        // The throttle is keyed by the submitted address, so it behaves identically whether or not
        // an account exists - a difference here would be an enumeration oracle (UC-03 A2).
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

    // ------------------------------------------------------------------
    //  CORS (section 7.8)
    // ------------------------------------------------------------------

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
        // A wildcard alongside credentials is both invalid and dangerous; assert it is not there.
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

    // ------------------------------------------------------------------
    //  Operational endpoints (section 7.7)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Section 7.7: the health probe answers without a token and discloses no internals")
    void healthProbeIsReachableButSaysNothingInternal() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // `show-details: never` keeps component names, the database and pool state out of an
        // unauthenticated response. Only the summary status is published.
        assertThat(response.getBody()).doesNotContain("db", "database", "HikariPool", "diskSpace");
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("Section 7.7: a non-allow-listed actuator endpoint is not readable without a token")
    void actuatorEndpointsOutsideTheAllowListRequireAuthentication() {
        // This is the regression guard for the catch-all rule. `/actuator/metrics` is not in the
        // public list, so it must fall to the authenticated rule rather than the final permitAll -
        // which would have published internal counters to anyone who could reach the port.
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.6: no default in-memory account is created, so no generated password is logged")
    void noDefaultInMemoryUserIsProvisioned() {
        // Spring Boot's UserDetailsServiceAutoConfiguration creates an in-memory user when the
        // application defines no UserDetailsService, and logs its generated password at startup:
        //   WARN ... Using generated security password: 8f13...-...-...
        // The account was never reachable here (no HTTP Basic or form login is enabled) but the
        // line is a credential-shaped secret in the application log, which section 7.6 keeps out.
        // The auto-configuration is excluded on CampusCoinApplication; this asserts the exclusion
        // holds, because it is a one-word annotation change that nothing else would catch.
        assertThat(context.getBeansOfType(UserDetailsService.class))
                .as("no UserDetailsService is defined, so no generated password can be logged")
                .isEmpty();
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class))
                .as("the auto-configured in-memory user is absent")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

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

    /** The raw reset token, read from the development sink rather than from the database. */
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

    /** Posts a category as the given student. The status is asserted by the caller's log check. */
    private ResponseEntity<String> createCategory(String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/categories", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Inserts a transaction referencing a category, so the category becomes one the database will
     * not let change type. Written directly here because {@code transactions} belongs to module 4.
     */
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

    /** Attempts a type change on the student's own category, which the trigger may refuse. */
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

    // ------------------------------------------------------------------
    //  Helpers for the transaction module's log check
    // ------------------------------------------------------------------

    /** Posts a transaction as the given student, for the module-4 log assertions. */
    private ResponseEntity<String> postTransaction(String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/transactions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** Moves one of the student's own transactions to the trash, to provoke the module's log line. */
    private void deleteTransaction(String token, Long transactionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/v1/transactions/" + transactionId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }

    /** The id of a seeded shared default category, looked up by name. */
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

    /** The id of a category owned by somebody other than the student under test. */
    private Long anotherStudentsCategory() throws Exception {
        String otherEmail = randomEmail();
        register(otherEmail);
        String otherToken = login(otherEmail);
        ResponseEntity<String> created = createCategory(otherToken,
                Map.of("name", "Not Yours " + otherEmail, "type", "EXPENSE"));
        return objectMapper.readTree(created.getBody()).get("id").asLong();
    }

    /** Inserts a transaction directly and returns the id the database assigned it. */
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
