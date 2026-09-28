package com.campuscoin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

class AdminAuthApiIT extends AbstractMySqlIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String STUDENT_LOGIN_URL = "/api/v1/auth/login";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    private static final String PASSWORD = "Student@123";

    private static final String PROTECTED_ADMIN_ROUTE = "/api/v1/admin/auth/protected-route-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("UC-05 B3: the seeded administrator signs in and receives an ADMIN token")
    void adminSignsIn() throws Exception {
        ResponseEntity<String> response = post(ADMIN_LOGIN_URL, Map.of(
                "email", SEEDED_ADMIN_EMAIL,
                "password", SEEDED_ADMIN_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("user").get("role").asText()).isEqualTo("ADMIN");

        assertThat(body.toString()).doesNotContain("passwordHash", "tokenVersion");
    }

    @Test
    @DisplayName("UC-05 A1: a student account is refused by the administrator portal")
    void studentIsRefusedByTheAdminPortal() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);

        ResponseEntity<String> response = post(ADMIN_LOGIN_URL,
                Map.of("email", email, "password", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("ACCESS_DENIED");

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id = s.user_id "
                             + "WHERE u.email = ?")) {
            statement.setString(1, email);
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).as("no session on a refused sign-in").isZero();
            }
        }
    }

    @Test
    @DisplayName("UC-05: the administrator portal is not a way to learn which addresses are admins")
    void adminPortalDoesNotRevealWhichAddressesAreAdmins() throws Exception {
        String studentEmail = randomEmail();
        registerStudent(studentEmail, PASSWORD);

        ResponseEntity<String> student = post(ADMIN_LOGIN_URL,
                Map.of("email", studentEmail, "password", PASSWORD));
        ResponseEntity<String> unknown = post(ADMIN_LOGIN_URL,
                Map.of("email", "unknown." + UUID.randomUUID() + "@example.com", "password", PASSWORD));

        assertThat(student.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-05: a wrong password on the administrator portal is a generic 401")
    void adminWrongPasswordIsGeneric() throws Exception {
        ResponseEntity<String> response = post(ADMIN_LOGIN_URL, Map.of(
                "email", SEEDED_ADMIN_EMAIL,
                "password", "WrongAdmin@123"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("BR-03: a disabled administrator cannot sign in, and existing access stops")
    void disabledAdminIsRefused() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        promoteToAdmin(email);

        String token = loginAt(ADMIN_LOGIN_URL, email, PASSWORD);

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET status = 'DISABLED' WHERE email = ?")) {
            statement.setString(1, email);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        ResponseEntity<String> freshLogin = post(ADMIN_LOGIN_URL,
                Map.of("email", email, "password", PASSWORD));
        assertThat(freshLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(freshLogin.getBody()).get("errorCode").asText())
                .isEqualTo("ACCOUNT_DISABLED");

        assertThat(exchange(PROTECTED_ADMIN_ROUTE, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-05 E1: a student token is refused by an admin route with 403, not 401")
    void studentTokenIsForbiddenOnAdminRoutes() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        String studentToken = loginAt(STUDENT_LOGIN_URL, email, PASSWORD);

        ResponseEntity<String> response = exchange(PROTECTED_ADMIN_ROUTE, studentToken, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("UC-05 E1: an admin route with no token answers 401")
    void adminRouteWithoutTokenIsUnauthenticated() throws Exception {
        ResponseEntity<String> response = exchange(PROTECTED_ADMIN_ROUTE, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("UC-05: an admin token works on a student route, since both are authenticated")
    void adminTokenWorksOnStudentRoutes() throws Exception {
        String adminToken = loginAt(ADMIN_LOGIN_URL, SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD);

        assertThat(exchange("/api/v1/auth/logout", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static String randomEmail() {
        return "admin." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    private void registerStudent(String email, String password) {
        ResponseEntity<String> response = post(REGISTER_URL, Map.of(
                "fullName", "Portal Student",
                "email", email,
                "password", password,
                "confirmPassword", password));
        assertThat(response.getStatusCode()).as("registration must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private void promoteToAdmin(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET role = 'ADMIN' WHERE email = ?")) {
            statement.setString(1, email);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String loginAt(String url, String email, String password) throws Exception {
        ResponseEntity<String> response = post(url, Map.of("email", email, "password", password));
        assertThat(response.getStatusCode()).as("sign-in must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private ResponseEntity<String> post(String url, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> exchange(String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
