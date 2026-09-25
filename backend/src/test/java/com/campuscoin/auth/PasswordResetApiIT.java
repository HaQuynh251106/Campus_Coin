package com.campuscoin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * UC-03 over the real HTTP stack, with the three reset procedures doing the token work.
 *
 * <p>The raw token is read from the development sink, which is where the application "delivers"
 * the link. That is how a developer drives the flow by hand, so the tests exercise the same path
 * rather than reaching into the database for a token the API never produced.
 */
class PasswordResetApiIT extends AbstractMySqlIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String REQUEST_URL = "/api/v1/auth/password-reset/request";
    private static final String VERIFY_URL = "/api/v1/auth/password-reset/verify";
    private static final String COMPLETE_URL = "/api/v1/auth/password-reset/complete";

    private static final String OLD_PASSWORD = "Student@123";
    private static final String NEW_PASSWORD = "Student@456";

    /** UC-03 B3 fixes this wording; every request must return exactly it. */
    private static final String GENERIC_MESSAGE = "If the email exists, a reset link has been sent.";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    //  UC-03 B3 / A2: the request step reveals nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-03 B3: a registered address gets the generic message and a usable link")
    void requestResetForKnownEmailIssuesToken() throws Exception {
        String email = randomEmail();
        register(email);

        ResponseEntity<String> response = post(REQUEST_URL, Map.of("email", email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
                .isEqualTo(GENERIC_MESSAGE);

        // The link really was issued, and the row holds a hash rather than the token.
        String rawToken = lastTokenFor(email);
        assertThat(rawToken).isNotBlank();

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT t.token_hash, t.used_at FROM password_reset_tokens t "
                             + "JOIN users u ON u.id = t.user_id WHERE u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                // BR-04 / section 7.7: the database must never hold the usable value.
                assertThat(row.getString("token_hash")).isNotEqualTo(rawToken);
                assertThat(row.getString("token_hash")).hasSize(64);
                assertThat(row.getTimestamp("used_at")).isNull();
            }
        }
    }

    @Test
    @DisplayName("UC-03 A2: an unknown address gets the identical message and no token")
    void requestResetForUnknownEmailIsIndistinguishable() throws Exception {
        String knownEmail = randomEmail();
        register(knownEmail);

        ResponseEntity<String> known = post(REQUEST_URL, Map.of("email", knownEmail));
        ResponseEntity<String> unknown = post(REQUEST_URL,
                Map.of("email", "no.such.account." + UUID.randomUUID() + "@example.com"));

        assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode());
        assertThat(unknown.getBody()).isEqualTo(known.getBody());
        assertThat(unknown.getBody()).doesNotContain("no.such.account");
    }

    @Test
    @DisplayName("UC-03: a malformed address is still rejected as a field error")
    void requestResetValidatesEmailFormat() throws Exception {
        ResponseEntity<String> response = post(REQUEST_URL, Map.of("email", "not-an-email"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    //  UC-03 B5: the verification step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-03 B5: a freshly issued token verifies and is not consumed by verifying")
    void verifyAcceptsFreshTokenRepeatedly() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseEntity<String> response = post(VERIFY_URL, Map.of("token", token));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(response.getBody()).get("valid").asBoolean()).isTrue();
        }

        // Verifying twice must leave the token consumable, so a student who opens the link and
        // then reloads the page is not locked out of their own reset.
        assertThat(completeReset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-03 A1: an unknown token is refused with INVALID_RESET_TOKEN")
    void verifyRejectsUnknownToken() throws Exception {
        ResponseEntity<String> response = post(VERIFY_URL, Map.of("token", "not-a-real-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    @DisplayName("BR-04: an expired token is refused")
    void verifyRejectsExpiredToken() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        expireTokenOf(email);

        ResponseEntity<String> verify = post(VERIFY_URL, Map.of("token", token));
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(verify.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_RESET_TOKEN");

        // UC-03 A1: the student is told to ask for another link, and doing so works.
        requestReset(email);
        assertThat(post(VERIFY_URL, Map.of("token", lastTokenFor(email))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-03: a new request invalidates the previous unused link")
    void newRequestSupersedesTheOldToken() throws Exception {
        String email = randomEmail();
        register(email);

        requestReset(email);
        String firstToken = lastTokenFor(email);
        requestReset(email);
        String secondToken = lastTokenFor(email);

        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(post(VERIFY_URL, Map.of("token", firstToken)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(VERIFY_URL, Map.of("token", secondToken)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    //  UC-03 B7 / BR-04: the completion step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-03 B7: completing sets the new password and retires the token")
    void completeResetChangesPassword() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        ResponseEntity<String> response = completeReset(token, NEW_PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
                .contains("Your password has been reset");

        // The new password works and the old one no longer does.
        assertThat(post(LOGIN_URL, Map.of("email", email, "password", NEW_PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(LOGIN_URL, Map.of("email", email, "password", OLD_PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UAT-03 / BR-04: a used link is rejected and a new one can be requested")
    void completeRejectsReusedToken() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        assertThat(completeReset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> reuse = completeReset(token, "Student@789");
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(reuse.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_RESET_TOKEN");

        // The second attempt must not have changed the password.
        assertThat(post(LOGIN_URL, Map.of("email", email, "password", NEW_PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // UC-03 A1: the student can recover by requesting another link.
        requestReset(email);
        assertThat(completeReset(lastTokenFor(email), "Student@789").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-03 A1: an unknown or expired token cannot complete a reset")
    void completeRejectsUnknownAndExpiredTokens() throws Exception {
        assertThat(completeReset("not-a-real-token", NEW_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);
        expireTokenOf(email);

        ResponseEntity<String> response = completeReset(token, NEW_PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    @DisplayName("UC-03: mismatched confirmation is a field error, and the token survives")
    void completeValidatesConfirmation() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        ResponseEntity<String> response = post(COMPLETE_URL, Map.of(
                "token", token,
                "newPassword", NEW_PASSWORD,
                "confirmPassword", "Student@999"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("VALIDATION_ERROR");

        // The rejected attempt must not have burned the link.
        assertThat(completeReset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-03: a weak new password is refused before the token is consumed")
    void completeRefusesWeakPassword() throws Exception {
        String email = randomEmail();
        register(email);
        requestReset(email);
        String token = lastTokenFor(email);

        ResponseEntity<String> response = completeReset(token, "weakpassword");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(completeReset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    //  BR-03: what a completed reset does to the account
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-03 / UC-03 BA note: a reset revokes every open session at once")
    void completeResetRevokesAllSessions() throws Exception {
        String email = randomEmail();
        register(email);
        String firstToken = login(email);
        String secondToken = login(email);

        requestReset(email);
        assertThat(completeReset(lastTokenFor(email), NEW_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Both devices are signed out, even though neither token had expired.
        assertThat(exchange(LOGOUT_URL, firstToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(LOGOUT_URL, secondToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id = s.user_id "
                             + "WHERE u.email = ? AND s.revoked_at IS NULL")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("BR-03: a reset increments token_version, invalidating older tokens")
    void completeResetBumpsTokenVersion() throws Exception {
        String email = randomEmail();
        register(email);
        login(email);

        int before = tokenVersionOf(email);

        requestReset(email);
        assertThat(completeReset(lastTokenFor(email), NEW_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(tokenVersionOf(email)).as("BR-03 bumps the version").isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static String randomEmail() {
        return "reset." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    private void register(String email) {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Reset Student",
                "email", email,
                "password", OLD_PASSWORD,
                "confirmPassword", OLD_PASSWORD));
        assertThat(response.getStatusCode()).as("registration must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = post(LOGIN_URL,
                Map.of("email", email, "password", OLD_PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private void requestReset(String email) throws Exception {
        ResponseEntity<String> response = post(REQUEST_URL, Map.of("email", email));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
                .isEqualTo(GENERIC_MESSAGE);
    }

    private ResponseEntity<String> completeReset(String token, String newPassword) {
        return post(COMPLETE_URL, Map.of(
                "token", token,
                "newPassword", newPassword,
                "confirmPassword", newPassword));
    }

    /**
     * The raw token of the most recent link issued for an address, taken from the development
     * sink. This is the only place the raw value exists outside the response that was never sent,
     * so reading it here is how a person would drive the flow by hand.
     */
    private String lastTokenFor(String email) throws IOException {
        assertThat(Files.exists(RESET_SINK))
                .as("the development reset sink must exist at %s", RESET_SINK)
                .isTrue();

        List<String> lines = Files.readAllLines(RESET_SINK, StandardCharsets.UTF_8);
        return lines.stream()
                .filter(line -> line.contains(" | " + email + " | "))
                .reduce((first, second) -> second)
                .map(line -> line.substring(line.lastIndexOf("token=") + "token=".length()))
                .orElseThrow(() -> new AssertionError("No reset link was written for " + email));
    }

    /** Moves the account's unused tokens into the past, which the API deliberately cannot do. */
    private void expireTokenOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE password_reset_tokens t JOIN users u ON u.id = t.user_id "
                             + "SET t.expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                             + "WHERE u.email = ? AND t.used_at IS NULL")) {
            statement.setString(1, email);
            assertThat(statement.executeUpdate()).as("an unused token must exist to expire")
                    .isEqualTo(1);
        }
    }

    private int tokenVersionOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT token_version FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private ResponseEntity<String> post(String url, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> exchange(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }
}
