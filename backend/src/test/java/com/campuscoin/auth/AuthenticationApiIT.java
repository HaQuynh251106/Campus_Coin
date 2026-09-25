package com.campuscoin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.JwtService;
import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * UC-01 and UC-02 over the real HTTP stack: request, security filter, controller, service,
 * repository, MySQL.
 *
 * <p>Each test registers its own account with a random address so the suite does not depend on
 * execution order and the in-memory throttle counters cannot leak between tests. The seeded
 * accounts are only read.
 */
class AuthenticationApiIT extends AbstractMySqlIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";

    private static final String VALID_PASSWORD = "Student@123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    // ------------------------------------------------------------------
    //  UC-01 Registration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-01: a new student is created as ACTIVE with a bcrypt hash and no session")
    void registerCreatesActiveStudent() throws Exception {
        String email = randomEmail();

        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "New Student",
                "email", email,
                "password", VALID_PASSWORD,
                "confirmPassword", VALID_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // UC-01 B6: registration does not sign the student in, so there is no token to return.
        assertThat(response.getBody()).isNullOrEmpty();

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, status, password_hash, currency FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("the account row must exist").isTrue();
                assertThat(row.getString("role")).isEqualTo("STUDENT");
                assertThat(row.getString("status")).isEqualTo("ACTIVE");
                assertThat(row.getString("currency")).isNotBlank();

                String storedPassword = row.getString("password_hash");
                assertThat(storedPassword).as("BR-01: the password is hashed, never stored").isNotEqualTo(VALID_PASSWORD);
                assertThat(storedPassword).as("BR-01: bcrypt hash").startsWith("$2");
            }
        }

        // UC-02 B3: no session was opened by registering.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id = s.user_id WHERE u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).as("no session is created at registration").isZero();
            }
        }
    }

    @Test
    @DisplayName("UC-01 A1 / UAT-01: a duplicate email is refused and no second account is created")
    void registerRefusesDuplicateEmail() throws Exception {
        String email = randomEmail();
        register(email);

        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Impostor",
                "email", email,
                "password", VALID_PASSWORD,
                "confirmPassword", VALID_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).as("exactly the original account remains").isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("UC-01 A2: invalid input is rejected field by field")
    void registerValidatesInput() throws Exception {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "",
                "email", "not-an-email",
                "password", "weak",
                "confirmPassword", "different"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        // Every offending field is reported, so the form can highlight each one.
        assertThat(fieldNames(body)).contains("fullName", "email", "password");
    }

    @Test
    @DisplayName("UC-01 B3: a password that fails the strength policy is rejected")
    void registerRefusesWeakPassword() throws Exception {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Weak Password",
                "email", randomEmail(),
                "password", "alllowercase1",
                "confirmPassword", "alllowercase1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNames(objectMapper.readTree(response.getBody()))).contains("password");
    }

    @Test
    @DisplayName("UC-01 B3: mismatched password confirmation is a field error on confirmPassword")
    void registerRefusesMismatchedConfirmation() throws Exception {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Mismatch",
                "email", randomEmail(),
                "password", VALID_PASSWORD,
                "confirmPassword", "Student@124"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNames(body)).contains("confirmPassword");
    }

    // ------------------------------------------------------------------
    //  UC-02 Sign-in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-02 B2/B3: valid credentials return a token, a session and the account summary")
    void loginSucceedsAndOpensSession() throws Exception {
        String email = randomEmail();
        register(email);

        ResponseEntity<String> response = post(LOGIN_URL,
                Map.of("email", email, "password", VALID_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        // auth.session_ttl_minutes is seeded as 120.
        assertThat(body.get("expiresIn").asLong()).isEqualTo(7200);

        JsonNode user = body.get("user");
        assertThat(user.get("email").asText()).isEqualTo(email);
        assertThat(user.get("fullName").asText()).isEqualTo("Test Student");
        assertThat(user.get("role").asText()).isEqualTo("STUDENT");
        assertThat(user.get("id").asLong()).isPositive();

        // The session row is what makes sign-out possible, and it stores only the token's hash.
        String token = body.get("accessToken").asText();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT session_token_hash, refresh_token_hash, revoked_at FROM user_sessions s "
                             + "JOIN users u ON u.id = s.user_id WHERE u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("a session row must exist").isTrue();
                assertThat(row.getString("session_token_hash")).isNotEqualTo(token);
                assertThat(row.getString("session_token_hash")).hasSize(64);
                // No refresh flow is defined, so the column stays NULL.
                assertThat(row.getString("refresh_token_hash")).isNull();
                assertThat(row.getTimestamp("revoked_at")).isNull();
            }
        }

        // UC-23 reads this column, so signing in must record it.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_login_at FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getTimestamp("last_login_at")).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("UC-02 B2: the seeded accounts still sign in, proving the $2y hashes verify")
    void seededAccountsCanSignIn() throws Exception {
        ResponseEntity<String> response = post(LOGIN_URL, Map.of(
                "email", SEEDED_STUDENT_EMAIL,
                "password", SEEDED_STUDENT_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("user").get("role").asText())
                .isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("UC-02 A1: a wrong password and an unknown email are indistinguishable")
    void loginDoesNotRevealWhetherTheAccountExists() throws Exception {
        String email = randomEmail();
        register(email);

        ResponseEntity<String> wrongPassword = post(LOGIN_URL,
                Map.of("email", email, "password", "WrongPass@123"));
        ResponseEntity<String> unknownEmail = post(LOGIN_URL,
                Map.of("email", "does.not.exist." + UUID.randomUUID() + "@example.com",
                        "password", VALID_PASSWORD));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode wrongPasswordBody = objectMapper.readTree(wrongPassword.getBody());
        JsonNode unknownEmailBody = objectMapper.readTree(unknownEmail.getBody());

        // Identical code and message: neither tells the caller which half was wrong.
        assertThat(wrongPasswordBody.get("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmailBody.get("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrongPasswordBody.get("message").asText())
                .isEqualTo(unknownEmailBody.get("message").asText());
    }

    @Test
    @DisplayName("UC-02 A2 / BR-03: a disabled account is refused and told why")
    void loginRefusesDisabledAccount() throws Exception {
        String email = randomEmail();
        register(email);
        setStatus(email, "DISABLED");

        ResponseEntity<String> response = post(LOGIN_URL,
                Map.of("email", email, "password", VALID_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = objectMapper.readTree(response.getBody());
        // A2 requires the student to be told the account is disabled so they can contact an
        // administrator; this is only reachable by someone who knows the password.
        assertThat(body.get("errorCode").asText()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    @DisplayName("UC-02: the account summary never exposes a security field")
    void loginResponseExposesNoSensitiveField() throws Exception {
        String email = randomEmail();
        register(email);

        String body = post(LOGIN_URL, Map.of("email", email, "password", VALID_PASSWORD)).getBody();

        assertThat(body).doesNotContain(
                "passwordHash", "password_hash", "tokenVersion", "token_version",
                "sessionToken", "session_token", "refreshToken", "refresh_token");
    }

    // ------------------------------------------------------------------
    //  UC-02 Sign-out and token handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-02 B5: signing out revokes the session and the token stops working")
    void logoutRevokesTheToken() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> logout = exchange(LOGOUT_URL, HttpMethod.POST, token, null);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The token's own expiry has not passed, so this proves the session check is what
        // rejects it (UC-02 A3).
        ResponseEntity<String> afterLogout = exchange(LOGOUT_URL, HttpMethod.POST, token, null);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revoked_reason FROM user_sessions s JOIN users u ON u.id = s.user_id "
                             + "WHERE u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("revoked_reason")).isEqualTo("LOGOUT");
            }
        }
    }

    @Test
    @DisplayName("UC-02 E1: a protected endpoint without a token answers 401")
    void logoutWithoutTokenIsUnauthorized() throws Exception {
        ResponseEntity<String> response = exchange(LOGOUT_URL, HttpMethod.POST, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("UC-02 E1: a malformed or tampered token answers 401")
    void malformedAndTamperedTokensAreUnauthorized() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> malformed = exchange(LOGOUT_URL, HttpMethod.POST, "not-a-jwt", null);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Flip the final character of the signature: the token is well-formed but no longer
        // verifies against the signing key.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        ResponseEntity<String> tamperedResponse = exchange(LOGOUT_URL, HttpMethod.POST, tampered, null);
        assertThat(tamperedResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-02 A3: an expired access token is rejected")
    void expiredTokenIsUnauthorized() throws Exception {
        String email = randomEmail();
        register(email);
        login(email);

        // Minting with a negative lifetime produces a correctly signed but already expired token,
        // which is the one case the public API cannot create.
        User user = userRepository.findById(userIdOf(email)).orElseThrow();
        JwtService.IssuedToken expired = jwtService.issue(user, -1, Instant.now());

        ResponseEntity<String> response = exchange(LOGOUT_URL, HttpMethod.POST, expired.token(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-02 A3: a token whose session has expired is rejected")
    void expiredSessionIsUnauthorized() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        // Move the session's expiry into the past; the token itself is untouched.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE user_sessions s JOIN users u ON u.id = s.user_id "
                             + "SET s.expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE u.email = ?")) {
            statement.setString(1, email);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        ResponseEntity<String> response = exchange(LOGOUT_URL, HttpMethod.POST, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BR-03: bumping token_version immediately invalidates existing tokens")
    void staleTokenVersionIsRejected() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        // This is what a password reset or an account disable does in the database.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET token_version = token_version + 1 WHERE email = ?")) {
            statement.setString(1, email);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        ResponseEntity<String> response = exchange(LOGOUT_URL, HttpMethod.POST, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-02: signing out is idempotent")
    void logoutTwiceDoesNotFail() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertThat(exchange(LOGOUT_URL, HttpMethod.POST, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // The second call carries a revoked token, so it is answered as unauthenticated rather
        // than as a conflict; either way the client ends up signed out.
        assertThat(exchange(LOGOUT_URL, HttpMethod.POST, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-02: signing out one session leaves another session usable")
    void logoutAffectsOnlyTheCurrentSession() throws Exception {
        String email = randomEmail();
        register(email);
        String firstToken = login(email);
        String secondToken = login(email);

        assertThat(exchange(LOGOUT_URL, HttpMethod.POST, firstToken, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // A second device must not be signed out by the first device's sign-out.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id = s.user_id "
                             + "WHERE u.email = ? AND s.revoked_at IS NULL")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).as("the other session is still open").isEqualTo(1);
            }
        }
        assertThat(secondToken).isNotEqualTo(firstToken);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static String randomEmail() {
        return "test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    private void register(String email) throws Exception {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", VALID_PASSWORD,
                "confirmPassword", VALID_PASSWORD));
        assertThat(response.getStatusCode()).as("registration must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = post(LOGIN_URL,
                Map.of("email", email, "password", VALID_PASSWORD));
        assertThat(response.getStatusCode()).as("sign-in must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private void setStatus(String email, String status) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET status = ? WHERE email = ?")) {
            statement.setString(1, status);
            statement.setString(2, email);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
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

    private List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("fieldErrors").forEach(error -> names.add(error.get("field").asText()));
        return names;
    }

    private ResponseEntity<String> post(String url, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> exchange(String url, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }
}
