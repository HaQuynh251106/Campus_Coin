package com.campuscoin.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
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
 * The fixtures UC-24's suite needs: identity, records to examine, and the database reads that can show
 * what the API computed.
 *
 * <p><b>Why the records are created through the API rather than inserted.</b> The detector reads the
 * student's own live records, so a fixture that inserted rows directly would have to invent the
 * ownership the queries narrow on and the {@code is_deleted} state they filter - and a test asserting
 * that another student's records are invisible would then be testing the fixture's insert rather than
 * the query. Going through {@code POST /api/v1/transactions} means the rows under test are rows a real
 * caller creates, with the triggers and the encryption boundary running over them.
 *
 * <p><b>Dates are back-dated.</b> The detector's two rules are relative to the dates and the amounts,
 * and one of them is a window - so a fixture that could only produce records dated today could not
 * express "the same price a month apart is not a duplicate". {@code POST /api/v1/transactions} refuses
 * a future date (BR-08) but accepts a past one, so the helper below takes the date as a parameter. Only
 * the <em>date</em> is chosen; the trigger stamps {@code created_at}, and nothing UC-24 reads depends
 * on it.
 *
 * <p><b>Everything the suite asserts about the stored state is read from the table.</b> Whether a flag
 * is set is visible in a response, but the <em>triple</em> - {@code is_flagged}, {@code flag_type},
 * {@code flag_note} - is not, and neither is whether a rescan wrote a history row. {@link #storedFlagOf}
 * and {@link #historyCountOf} are the reads that can show them.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows,
 * and the seeded accounts are read but never modified.
 */
abstract class AbstractAnomalyApiIT extends AbstractMySqlIntegrationTest {

    protected static final String ANOMALIES_URL = "/api/v1/anomalies";
    protected static final String SCAN_URL = ANOMALIES_URL + "/scan";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";

    protected static final String PASSWORD = "Student@123";

    /** A seeded shared default the fixture's records are filed under. */
    protected static final String FOOD = "Food";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties a flagged record may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the response fails a test instead
     * of quietly widening the published contract. There is no {@code userId}: the query already
     * applied ownership, and an entry says nothing about which account it belongs to.
     */
    protected static final List<String> DOCUMENTED_ENTRY_FIELDS = List.of(
            "transactionId", "categoryId", "categoryName", "categoryType",
            "amount", "txnDate", "description", "isFlagged", "flagType", "flagNote");

    /** The properties of the list response's wrapper. */
    protected static final List<String> DOCUMENTED_LIST_FIELDS = List.of("limit", "entries");

    /** The properties of the scan response. */
    protected static final List<String> DOCUMENTED_SCAN_FIELDS = List.of(
            "examined", "flagged", "cleared", "unchanged", "entries");

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

    /** The caller's flagged list, asserting the call succeeded first. */
    protected JsonNode flagged(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, ANOMALIES_URL, token, null);
        assertThat(response.getStatusCode())
                .as("anomalies body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** The caller's flagged list with an explicit limit, unasserted so a refusal can be examined. */
    protected ResponseEntity<String> flaggedWithLimit(String token, String limit) {
        return send(HttpMethod.GET, ANOMALIES_URL + "?limit=" + limit, token, null);
    }

    /** Asks for a scan, unasserted - most tests here are about what the scan did or refused. */
    protected ResponseEntity<String> scan(String token) {
        return send(HttpMethod.POST, SCAN_URL, token, null);
    }

    /** Asks for a scan and asserts it succeeded, which is the setup most tests need. */
    protected JsonNode scanExpectingOk(String token) throws Exception {
        ResponseEntity<String> response = scan(token);
        assertThat(response.getStatusCode())
                .as("scan body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
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

    protected static List<String> fieldNamesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The transaction ids in a response's {@code entries}, in the order they were returned. */
    protected static List<Long> entryTransactionIdsOf(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.get("entries").forEach(entry -> ids.add(entry.get("transactionId").asLong()));
        return ids;
    }

    /** The flag type of every entry, in the order they were returned. */
    protected static List<String> entryFlagTypesOf(JsonNode response) {
        List<String> types = new ArrayList<>();
        response.get("entries").forEach(entry -> types.add(entry.get("flagType").asText()));
        return types;
    }

    /** One entry of a response, found by the transaction it describes. */
    protected static JsonNode entryFor(JsonNode response, Long transactionId) {
        for (JsonNode entry : response.get("entries")) {
            if (entry.get("transactionId").asLong() == transactionId) {
                return entry;
            }
        }
        throw new AssertionError("no entry for transaction " + transactionId + " in " + response);
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

    /** A token for the seeded student, who carries the seeded transactions. */
    protected String seededStudentLogin() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", SEEDED_STUDENT_EMAIL, "password", SEEDED_STUDENT_PASSWORD));
        assertThat(response.getStatusCode())
                .as("seeded student login body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected Long userIdOf(String token) throws Exception {
        return body(send(HttpMethod.GET, PROFILE_URL, token, null)).get("id").asLong();
    }

    protected static String randomEmail() {
        return "anomaly.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  Records to examine
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Creates one transaction through the API, which is how the triggers and the queries see it. */
    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {
        // LinkedHashMap rather than Map.of, which refuses null values - and a record with no description
        // is a case this suite has to produce.
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

    /** A record dated today, in a seeded category, with a description worth decrypting. */
    protected Long aRecord(String token, String amount, String description) throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), amount, today(), description);
    }

    /** A record of the same amount and category as another, {@code daysAgo} earlier. */
    protected Long aRecordDaysAgo(String token, String amount, int daysAgo, String description)
            throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), amount,
                today().minusDays(daysAgo), description);
    }

    /** Changes a stored record through the endpoint the student uses (UC-10). */
    protected ResponseEntity<String> patchTransaction(String token, Long transactionId, Object body) {
        return send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token, body);
    }

    /**
     * Moves a record to the trash through the endpoint the student uses (BR-09).
     *
     * <p>Asserts the module 4 contract - {@code 204 No Content} - rather than leaving the status to
     * each test. Every caller here trashes a record it expects to remove, so a refusal is a broken
     * fixture rather than a case under examination, and failing here names the fixture instead of
     * surfacing as an unrelated assertion about a scan's counts.
     */
    protected void trash(String token, Long transactionId) {
        ResponseEntity<String> response =
                send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);
        assertThat(response.getStatusCode())
                .as("trash %d body=%s", transactionId, response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    /** The three flag columns exactly as the database holds them. */
    protected record StoredFlag(boolean isFlagged, String flagType, String flagNote) {
    }

    protected StoredFlag storedFlagOf(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT is_flagged, flag_type, flag_note FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("transaction %d exists", transactionId).isTrue();
                return new StoredFlag(row.getBoolean("is_flagged"),
                        row.getString("flag_type"), row.getString("flag_note"));
            }
        }
    }

    /**
     * {@code transactions.description} exactly as MySQL holds it.
     *
     * <p>Two encryptions of the same words are not the same string - the envelope carries a fresh random
     * IV - so a fixture can compare this against another envelope to prove the column was rewritten, and
     * decrypt it to prove the words are still recoverable.
     */
    protected String storedDescriptionOf(Long transactionId) throws Exception {
        return columnInDatabase(transactionId, "transactions", "description");
    }

    /** How many {@code transaction_history} rows a record has (BR-09). */
    protected int historyCountOf(Long transactionId) throws Exception {
        return countOf("SELECT COUNT(*) FROM transaction_history WHERE transaction_id = ?",
                transactionId);
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

    /** Binds fixture parameters, using the declared type for a date and a null-safe set for the rest. */
    private static void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            if (value instanceof LocalDate date) {
                statement.setDate(index + 1, java.sql.Date.valueOf(date));
            } else if (value == null) {
                statement.setNull(index + 1, Types.VARCHAR);
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }
}
