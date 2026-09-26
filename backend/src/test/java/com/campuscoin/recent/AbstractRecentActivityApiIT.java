package com.campuscoin.recent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * The fixtures UC-26's suite needs: identity, a real transaction to view, and the database reads that
 * can show what the API withheld.
 *
 * <p><b>Why the fixtures record transactions through the API rather than inserting rows.</b> A
 * recent-activity entry points at a transaction, and the endpoint's own guard is the ownership check
 * inside {@code sp_touch_recent_activity} - which reads the transaction's {@code user_id}. A
 * hand-inserted row would have to invent that owner, and a test asserting "another student's
 * transaction is refused" would then be testing the fixture's choice rather than the procedure's check.
 * Going through {@code POST /api/v1/transactions} means the row under test is the row a real caller
 * creates.
 *
 * <p><b>{@code recent_activity} is read straight from the table, not through the API.</b> Three of the
 * things this suite must prove are invisible in a response: that a trashed transaction leaves the list
 * while its activity row survives, that re-viewing moves an entry instead of duplicating it, and that
 * the stored description is an envelope rather than the student's words. {@link #activityRowCount} and
 * {@link #storedDescriptionOf} are the reads that can show them.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows,
 * and the seeded accounts are read but never modified.
 */
abstract class AbstractRecentActivityApiIT extends AbstractMySqlIntegrationTest {

    protected static final String RECENT_URL = "/api/v1/recent-activity";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";

    protected static final String PASSWORD = "Student@123";

    /** A seeded shared default the fixture's spending is filed under. */
    protected static final String FOOD = "Food";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties a recent-activity entry may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the record fails a test instead of
     * quietly widening the published contract. There is no {@code userId} and no {@code id}: the query
     * already applied ownership, and the entry is identified by the transaction and the action rather
     * than by a surrogate key of its own.
     */
    protected static final List<String> DOCUMENTED_ENTRY_FIELDS = List.of(
            "transactionId", "action", "occurredAt", "categoryId", "categoryType",
            "amount", "description", "txnDate");

    /** The properties of the list response's wrapper. */
    protected static final List<String> DOCUMENTED_LIST_FIELDS = List.of("limit", "entries");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

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

    /** The caller's recent list, asserting the call succeeded first. */
    protected JsonNode recent(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, RECENT_URL, token, null);
        assertThat(response.getStatusCode())
                .as("recent body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** The caller's recent list, with an explicit limit. */
    protected ResponseEntity<String> recentWithLimit(String token, String limit) {
        return send(HttpMethod.GET, RECENT_URL + "?limit=" + limit, token, null);
    }

    /** Records a view or an edit, through the endpoint a client uses. */
    protected ResponseEntity<String> record(String token, Long transactionId, String action) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", transactionId);
        body.put("action", action);
        return send(HttpMethod.POST, RECENT_URL, token, body);
    }

    /** Records an action and asserts the call succeeded, returning the created entry. */
    protected JsonNode recordExpectingCreated(String token, Long transactionId, String action)
            throws Exception {
        ResponseEntity<String> response = record(token, transactionId, action);
        assertThat(response.getStatusCode())
                .as("record body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
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

    protected static List<Long> entryTransactionIdsOf(JsonNode listResponse) {
        List<Long> ids = new ArrayList<>();
        listResponse.get("entries").forEach(entry -> ids.add(entry.get("transactionId").asLong()));
        return ids;
    }

    protected static List<String> entryActionsOf(JsonNode listResponse) {
        List<String> actions = new ArrayList<>();
        listResponse.get("entries").forEach(entry -> actions.add(entry.get("action").asText()));
        return actions;
    }

    protected static List<String> fieldNamesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ==================================================================
    //  Identity
    // ==================================================================

    protected String register(String email) {
        ResponseEntity<String> response = send(HttpMethod.POST, REGISTER_URL, null, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD));
        assertThat(response.getStatusCode())
                .as("register body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return email;
    }

    protected String loginNewStudent() throws Exception {
        String email = randomEmail();
        register(email);
        return login(email);
    }

    protected String login(String email) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode())
                .as("login body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected String adminLogin() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, ADMIN_LOGIN_URL, null,
                Map.of("email", SEEDED_ADMIN_EMAIL, "password", SEEDED_ADMIN_PASSWORD));
        assertThat(response.getStatusCode())
                .as("admin login body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected Long userIdOf(String token) throws Exception {
        return body(send(HttpMethod.GET, PROFILE_URL, token, null)).get("id").asLong();
    }

    protected static String randomEmail() {
        return "recent.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  A real transaction to view
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Records one transaction through the API, which is how the triggers and views see it. */
    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {
        // LinkedHashMap rather than Map.of, which refuses null values - and a transaction with no
        // description is a case this suite has to produce: the response must omit the field rather
        // than carry null.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("amount", amount);
        body.put("txnDate", date.toString());
        body.put("description", description);
        ResponseEntity<String> response = send(HttpMethod.POST, TRANSACTIONS_URL, token, body);
        assertThat(response.getStatusCode())
                .as("transaction body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
    }

    /** A transaction dated today, in a seeded category, with a description worth decrypting. */
    protected Long aTransaction(String token, String description) throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), "25.00", today(), description);
    }

    /** Moves a transaction to the trash through the endpoint the student uses (BR-09). */
    protected ResponseEntity<String> trash(String token, Long transactionId) {
        return send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);
    }

    /** Brings a trashed transaction back through the endpoint the student uses. */
    protected ResponseEntity<String> restore(String token, Long transactionId) {
        return send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore", token, null);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    /** How many {@code recent_activity} rows a student holds for one transaction and action. */
    protected int activityRowCount(Long userId, Long transactionId, String action) throws Exception {
        return countOf("SELECT COUNT(*) FROM recent_activity WHERE user_id = ? "
                + "AND transaction_id = ? AND action = ?", userId, transactionId, action);
    }

    /** Every action recorded for one student and transaction, sorted, read as the database holds them. */
    protected List<String> actionsFor(Long userId, Long transactionId) throws Exception {
        return stringValuesFrom("SELECT action FROM recent_activity WHERE user_id = ? "
                + "AND transaction_id = ? ORDER BY action", userId, transactionId);
    }

    /**
     * {@code transactions.description} exactly as MySQL holds it.
     *
     * <p>The one read that can show the column is ciphertext rather than the student's words - the
     * mirror of {@code AbstractBookmarksApiIT#storedNoteOf}.
     */
    protected String storedDescriptionOf(Long transactionId) throws Exception {
        return columnInDatabase(transactionId, "transactions", "description");
    }

    protected String columnInDatabase(Long id, String table, String column) throws Exception {
        // The table and column names come from test literals only, never from a request, so
        // interpolating them is safe; the id is bound.
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("%s %d exists", table, id).isTrue();
                Object value = row.getObject(1);
                return value == null ? null : String.valueOf(value);
            }
        }
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

    /** Runs a one-off statement, for fixtures that have to reach past the API. */
    protected void runInDatabase(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    /** Binds fixture parameters, using the declared type for a date, as the other suites do. */
    private static void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            if (value instanceof LocalDate date) {
                statement.setDate(index + 1, java.sql.Date.valueOf(date));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }
}
