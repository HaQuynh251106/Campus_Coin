package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

abstract class AbstractAdminApiIT extends AbstractMySqlIntegrationTest {

    protected static final String ADMIN_USERS_URL = "/api/v1/admin/users";
    protected static final String ADMIN_CATEGORIES_URL = "/api/v1/admin/categories";
    protected static final String ADMIN_ANNOUNCEMENTS_URL = "/api/v1/admin/announcements";
    protected static final String ADMIN_TIP_TEMPLATES_URL = "/api/v1/admin/tip-templates";
    protected static final String ADMIN_SETTINGS_URL = "/api/v1/admin/settings";
    protected static final String ADMIN_STATS_URL = "/api/v1/admin/stats";
    protected static final String ADMIN_STATS_TOP_CATEGORIES_URL = ADMIN_STATS_URL + "/top-categories";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";

    protected static final List<String> MODULE_11_ROUTES = List.of(
            "GET " + ADMIN_USERS_URL,
            "POST " + ADMIN_USERS_URL + "/{id}/status",
            "POST " + ADMIN_USERS_URL + "/{id}/password-reset",
            "GET " + ADMIN_CATEGORIES_URL,
            "POST " + ADMIN_CATEGORIES_URL,
            "PATCH " + ADMIN_CATEGORIES_URL + "/{id}",
            "GET " + ADMIN_ANNOUNCEMENTS_URL,
            "POST " + ADMIN_ANNOUNCEMENTS_URL,
            "PATCH " + ADMIN_ANNOUNCEMENTS_URL + "/{id}",
            "GET " + ADMIN_TIP_TEMPLATES_URL,
            "POST " + ADMIN_TIP_TEMPLATES_URL,
            "PATCH " + ADMIN_TIP_TEMPLATES_URL + "/{id}",
            "GET " + ADMIN_SETTINGS_URL,
            "PATCH " + ADMIN_SETTINGS_URL + "/{key}",
            "GET " + ADMIN_STATS_URL,
            "GET " + ADMIN_STATS_TOP_CATEGORIES_URL);

    protected static final String PASSWORD = "Student@123";

    protected static final Set<String> FORBIDDEN_RESPONSE_KEYS = Set.of(
            "passwordHash", "password_hash", "password", "tokenVersion", "token_version",
            "refreshToken", "refresh_token", "resetToken", "reset_token", "sessionToken",
            "session_token", "tokenHash", "token_hash", "monthlyAllowanceBaseline",
            "monthly_allowance_baseline", "monthlySavingsGoal", "monthly_savings_goal",
            "themePref", "theme_pref", "fontScale", "font_scale", "aiEnabled", "ai_enabled",
            "emailVerifiedAt", "email_verified_at", "conditionParams", "condition_params");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TokenHashService tokenHashService;

    protected ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> get(String url, String token) {
        return send(HttpMethod.GET, url, token, null);
    }

    protected JsonNode ok(HttpMethod method, String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);
        assertThat(response.getStatusCode())
                .as("%s %s body=%s", method, url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected JsonNode created(String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, url, token, body);
        assertThat(response.getStatusCode())
                .as("POST %s body=%s", url, response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
    }

    protected JsonNode patched(String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, url, token, body);
        assertThat(response.getStatusCode())
                .as("PATCH %s body=%s", url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected JsonNode refused(HttpMethod method, String url, String token, Object body,
                               HttpStatus expectedStatus, String expectedCode) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);
        assertThat(response.getStatusCode())
                .as("%s %s body=%s", method, url, response.getBody())
                .isEqualTo(expectedStatus);
        JsonNode error = body(response);
        assertThat(error.get("errorCode").asText())
                .as("%s %s body=%s", method, url, response.getBody())
                .isEqualTo(expectedCode);
        return error;
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
    }

    protected static List<String> fieldNamesIn(JsonNode error) {
        JsonNode fieldErrors = error.get("fieldErrors");
        if (fieldErrors == null || fieldErrors.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        fieldErrors.forEach(fieldError -> names.add(fieldError.get("field").asText()));
        return names;
    }

    protected static List<String> fieldNamesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    protected static List<Long> idsOf(JsonNode listResponse) {
        List<Long> ids = new ArrayList<>();
        listResponse.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    protected static Set<String> allKeysIn(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> found) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                found.add(entry.getKey());
                collectKeys(entry.getValue(), found);
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectKeys(element, found));
        }
    }

    protected String adminToken() throws Exception {
        return loginAt(ADMIN_LOGIN_URL, SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD);
    }

    protected Long seededAdminId() throws Exception {
        return longValueFrom("SELECT id FROM users WHERE email = ?", SEEDED_ADMIN_EMAIL);
    }

    protected String lastRawTokenFor(String email) throws Exception {
        assertThat(java.nio.file.Files.exists(RESET_SINK))
                .as("the development reset sink must exist at %s", RESET_SINK)
                .isTrue();

        java.util.List<String> lines =
                java.nio.file.Files.readAllLines(RESET_SINK, java.nio.charset.StandardCharsets.UTF_8);
        return lines.stream()
                .filter(line -> line.contains(" | " + email + " | "))
                .reduce((first, second) -> second)
                .map(line -> line.substring(line.lastIndexOf("token=") + "token=".length()))
                .orElseThrow(() -> new AssertionError("No reset link was written for " + email));
    }

    protected String loginAt(String url, String email, String password) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, url, null,
                Map.of("email", email, "password", password));
        assertThat(response.getStatusCode())
                .as("sign-in for %s body=%s", email, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected void registerStudent(String email, String password) {
        ResponseEntity<String> response = send(HttpMethod.POST, REGISTER_URL, null, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", password,
                "confirmPassword", password));
        assertThat(response.getStatusCode())
                .as("register body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    protected String studentToken() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        return loginAt(LOGIN_URL, email, PASSWORD);
    }

    protected Long userIdOf(String token) throws Exception {
        return body(get(PROFILE_URL, token)).get("id").asLong();
    }

    protected Long secondAdmin(String email) throws Exception {
        runInDatabase(
                "INSERT INTO users (email, password_hash, full_name, role, status) "
                        + "SELECT ?, password_hash, 'Second Administrator', 'ADMIN', 'ACTIVE' "
                        + "  FROM users WHERE email = ?",
                email, SEEDED_ADMIN_EMAIL);
        return longValueFrom("SELECT id FROM users WHERE email = ?", email);
    }

    protected Long promoteToAdmin(String email) throws Exception {
        runInDatabase("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        return longValueFrom("SELECT id FROM users WHERE email = ?", email);
    }

    protected Long registeredStudentId(String email) throws Exception {
        registerStudent(email, PASSWORD);
        return longValueFrom("SELECT id FROM users WHERE email = ?", email);
    }

    protected static String randomEmail() {
        return "admin.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    protected static String randomTipTemplateCode() {
        return "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected int countOf(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    protected Long longValueFrom(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("a row for: %s", sql).isTrue();
                return row.getLong(1);
            }
        }
    }

    protected String stringValueFrom(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("a row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    protected List<String> stringValuesFrom(String sql, Object... parameters) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    protected void runInDatabase(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    protected Long latestAuditId(String action, Long targetId) throws Exception {
        return longValueFrom(
                "SELECT id FROM admin_audit_log "
                        + " WHERE action = ? AND (target_id <=> ?) ORDER BY id DESC LIMIT 1",
                action, targetId);
    }

    protected Long latestAuditIdFor(String action, String targetEntity, Long targetId)
            throws Exception {
        return longValueFrom(
                "SELECT id FROM admin_audit_log "
                        + " WHERE action = ? AND target_entity = ? AND target_id = ? "
                        + " ORDER BY id DESC LIMIT 1",
                action, targetEntity, targetId);
    }

    protected Long noSuchIdIn(String table) throws Exception {
        return longValueFrom("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table);
    }

    protected String auditActionOf(Long auditId) throws Exception {
        return stringValueFrom("SELECT action FROM admin_audit_log WHERE id = ?", auditId);
    }

    protected String auditTargetEntityOf(Long auditId) throws Exception {
        return stringValueFrom("SELECT target_entity FROM admin_audit_log WHERE id = ?", auditId);
    }

    protected Long auditActorOf(Long auditId) throws Exception {
        return longValueFrom("SELECT admin_user_id FROM admin_audit_log WHERE id = ?", auditId);
    }

    protected String auditDetailFieldOf(Long auditId, String field) throws Exception {
        return stringValueFrom(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, ?)) FROM admin_audit_log WHERE id = ?",
                "$." + field, auditId);
    }

    protected int auditRowCount(String action) throws Exception {
        return countOf("SELECT COUNT(*) FROM admin_audit_log WHERE action = ?", action);
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }
}
