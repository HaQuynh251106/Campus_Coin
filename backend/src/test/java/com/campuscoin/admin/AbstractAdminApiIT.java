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

/**
 * The fixtures module 11's suite needs: the sixteen routes, an administrator token, a student to act
 * on, and the database reads an audit assertion needs.
 *
 * <p><b>The second administrator is created through the database, and that is the fiddliest part of
 * this class.</b> UC-22's routes need a target that is neither the caller nor the seeded
 * administrator: disabling the caller is refused by design (so that test would pass for the wrong
 * reason), and disabling the shared seeded administrator would leave every other suite in the run
 * signing in as a disabled account. There is no API that creates an administrator -
 * {@code AuthService} fixes the role to {@code STUDENT}, which is UC-01's rule and not something this
 * module may change - so the fixture inserts the row directly, reusing the seeded administrator's own
 * password hash through a {@code SELECT} rather than inventing a bcrypt string. The account is then
 * reachable through the real administrator sign-in endpoint, so what the tests drive is the real
 * flow from that point on.
 *
 * <p>A second helper, {@link #promoteToAdmin}, serves the security suite: it needs several
 * independent administrator identities and does not care about their passwords. Both are here rather
 * than duplicated per class because "how do you get a second administrator" must have one answer.
 *
 * <p><b>Every test that writes leaves its own rows behind.</b> That is deliberate and matches the
 * other suites: the container is shared, each test creates what it needs and asserts on its own
 * identifiers, and only the seeded administrator's own status is treated as shared state - every test
 * that disables an administrator re-enables it in the same test, so the suite does not depend on
 * class order.
 */
abstract class AbstractAdminApiIT extends AbstractMySqlIntegrationTest {

    // ------------------------------------------------------------------
    //  Routes (inventory 46-61)
    // ------------------------------------------------------------------

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

    /**
     * Every module 11 operation, as {@code METHOD /path}, for the route-level security sweep.
     *
     * <p>Written out rather than derived from the controllers, because the point of the sweep is that
     * a route which exists but is not in this list is not silently skipped - and a list derived from
     * the same source as the routes could not show that. The two path templates are instantiated
     * below with a real id, since that is what a caller sends.
     */
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

    /**
     * Response field names that must never appear anywhere in a module 11 payload.
     *
     * <p>The same set {@code OpenApiContractIT} applies to the document, applied here to live
     * responses: the schema test proves a field was never declared, this proves one was never
     * selected either. {@code token_version} is the one worth naming - it is the security meaning of
     * a JWT's {@code tv} claim, so publishing it would let an attacker decide whether a stolen token
     * is still live.
     */
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

    /**
     * The production hasher, so a test can prove a stored digest is the hash <em>of</em> a token it
     * holds rather than merely hash-shaped.
     *
     * <p>Injected rather than re-implemented: a test that computed the digest itself would agree with
     * itself and could disagree with the application, which is the one thing such a test exists to
     * rule out.
     */
    @Autowired
    protected TokenHashService tokenHashService;

    // ==================================================================
    //  HTTP
    // ==================================================================

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

    /** A call the test expects to succeed, with the body parsed and the failure message attached. */
    protected JsonNode ok(HttpMethod method, String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);
        assertThat(response.getStatusCode())
                .as("%s %s body=%s", method, url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** A create the test expects to succeed, with the {@code 201} and the body both asserted. */
    protected JsonNode created(String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, url, token, body);
        assertThat(response.getStatusCode())
                .as("POST %s body=%s", url, response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
    }

    /** A write the test expects to succeed with {@code 200}, the counterpart of {@link #created}. */
    protected JsonNode patched(String url, String token, Object body) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, url, token, body);
        assertThat(response.getStatusCode())
                .as("PATCH %s body=%s", url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** A call the test expects to be refused, with the status and the error code both asserted. */
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

    /** The value of the {@code fieldErrors[].field} names of a validation response. */
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

    /**
     * Every object key anywhere in a response, however deeply nested.
     *
     * <p>Collected rather than checked per field, so the sweep a test performs is one assertion over
     * the whole payload: a field nested inside an array element is exactly the kind of place a
     * hand-written per-field assertion forgets to look.
     */
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

    // ==================================================================
    //  Identity
    // ==================================================================

    /** A live administrator token for the seeded administrator. */
    protected String adminToken() throws Exception {
        return loginAt(ADMIN_LOGIN_URL, SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD);
    }

    /** The seeded administrator's id, for the one request that has to name it: the self-disable. */
    protected Long seededAdminId() throws Exception {
        return longValueFrom("SELECT id FROM users WHERE email = ?", SEEDED_ADMIN_EMAIL);
    }

    /**
     * The raw reset token of the most recent link issued for an address, read from the development
     * sink.
     *
     * <p>The only place the raw value exists outside the message that was sent: the database holds
     * a hash and the response holds neither. Reading it here is how a person driving the flow by
     * hand would, and it is what lets a test prove the two halves are of the same token.
     */
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

    /** A freshly registered student's token, for the refusal sweeps. */
    protected String studentToken() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        return loginAt(LOGIN_URL, email, PASSWORD);
    }

    protected Long userIdOf(String token) throws Exception {
        return body(get(PROFILE_URL, token)).get("id").asLong();
    }

    /**
     * A second administrator, created through the database, and its id.
     *
     * <p>Its password is the seeded administrator's, copied by {@code SELECT} rather than written as
     * a literal hash - so a test can sign in as it through the real endpoint without the suite
     * carrying a second bcrypt string that could drift from {@code db/05_seed.sql}. {@code
     * last_login_at} is left null on purpose: a test that asserts on the field then has a target that
     * has never signed in until it does.
     */
    protected Long secondAdmin(String email) throws Exception {
        runInDatabase(
                "INSERT INTO users (email, password_hash, full_name, role, status) "
                        + "SELECT ?, password_hash, 'Second Administrator', 'ADMIN', 'ACTIVE' "
                        + "  FROM users WHERE email = ?",
                email, SEEDED_ADMIN_EMAIL);
        return longValueFrom("SELECT id FROM users WHERE email = ?", email);
    }

    /** A student who is not the caller, promoted to administrator, for the security sweep. */
    protected Long promoteToAdmin(String email) throws Exception {
        runInDatabase("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        return longValueFrom("SELECT id FROM users WHERE email = ?", email);
    }

    /** A fresh student account, registered through the API, and its id. */
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

    // ==================================================================
    //  Database assertions
    // ==================================================================

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

    // ------------------------------------------------------------------
    //  Audit rows
    // ------------------------------------------------------------------

    /**
     * The id of the newest {@code admin_audit_log} row of this action naming this target.
     *
     * <p>Read back rather than assumed: the procedures write their own audit row and none of them
     * returns its id, so the row is located by what is known about it. The target is matched as a
     * string because {@code target_id} is nullable - a {@code SETTING_CHANGED} row has none - and
     * {@code NULL = NULL} is not true in SQL.
     */
    protected Long latestAuditId(String action, Long targetId) throws Exception {
        return longValueFrom(
                "SELECT id FROM admin_audit_log "
                        + " WHERE action = ? AND (target_id <=> ?) ORDER BY id DESC LIMIT 1",
                action, targetId);
    }

    /**
     * The newest audit row of this action, naming this entity and this id.
     *
     * <p>The scoped form, for the actions whose {@code target_id} is not unique across the suite.
     * {@code USER_DISABLED} and {@code PASSWORD_RESET_SENT} name a {@code users} row, and a student
     * id is never reused, so the two-argument form above is safe for them. An audit action naming a
     * category is not: every other suite in the run creates personal categories, ids are drawn from
     * one shared sequence, and two suites' ids cannot collide - but a *category* row id and a
     * *transaction* row id can, and {@code CATEGORY_UPDATED}'s {@code target_id} is only meaningful
     * together with {@code target_entity}. Asking both is what makes the lookup exact.
     */
    protected Long latestAuditIdFor(String action, String targetEntity, Long targetId)
            throws Exception {
        return longValueFrom(
                "SELECT id FROM admin_audit_log "
                        + " WHERE action = ? AND target_entity = ? AND target_id = ? "
                        + " ORDER BY id DESC LIMIT 1",
                action, targetEntity, targetId);
    }

    /**
     * An id that belongs to no row of this table, derived from the table rather than guessed.
     *
     * <p>{@code MAX(id) + 1} rather than a literal such as {@code 999999}: a literal is an
     * assumption about how far the seed script and the rest of the run advance the sequence, and
     * {@code users} alone carries ids in the thousands by the end of a full suite - the security
     * sweep registers hundreds of accounts. A literal that was overtaken would make a
     * "no such account" test pass while actually naming a real one, which is the failure mode this
     * avoids for the cost of one query.
     *
     * <p>The value is unused at the moment it is read and, because the suite's tests run one at a
     * time against the shared container, nothing can take it between the read and the request.
     */
    protected Long noSuchIdIn(String table) throws Exception {
        return longValueFrom("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table);
    }

    protected String auditActionOf(Long auditId) throws Exception {
        return stringValueFrom("SELECT action FROM admin_audit_log WHERE id = ?", auditId);
    }

    protected String auditTargetEntityOf(Long auditId) throws Exception {
        return stringValueFrom("SELECT target_entity FROM admin_audit_log WHERE id = ?", auditId);
    }

    /** The audit row's {@code admin_user_id}. */
    protected Long auditActorOf(Long auditId) throws Exception {
        return longValueFrom("SELECT admin_user_id FROM admin_audit_log WHERE id = ?", auditId);
    }

    /** One value out of the audit row's JSON {@code detail} column. */
    protected String auditDetailFieldOf(Long auditId, String field) throws Exception {
        return stringValueFrom(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, ?)) FROM admin_audit_log WHERE id = ?",
                "$." + field, auditId);
    }

    protected int auditRowCount(String action) throws Exception {
        return countOf("SELECT COUNT(*) FROM admin_audit_log WHERE action = ?", action);
    }

    // ------------------------------------------------------------------
    //  Fixture helpers
    // ------------------------------------------------------------------

    private static void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }
}
