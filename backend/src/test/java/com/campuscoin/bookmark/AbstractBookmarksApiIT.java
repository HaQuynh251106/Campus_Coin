package com.campuscoin.bookmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * The fixtures module 10's suite needs: identity, a real tip to save, and the two database reads an
 * encryption assertion needs.
 *
 * <p><b>Why the fixtures generate a real tip rather than inserting a {@code user_tips} row.</b> A
 * bookmark points at a tip, and a hand-inserted row would be a tip the generator would never produce -
 * a title naming a category the student never spent in, or a {@code rank_score} violating BR-14's
 * ordering. The bookmark's whole content is the advice it saves, so the fixture builds the
 * <em>spending</em> and lets {@code sp_generate_tips} produce the row, exactly as module 9's fixtures
 * do. The one difference is that this suite does not care which rule fired: it needs a tip that exists
 * and belongs to the caller, so a fresh student's "too little data" tip (UC-18 A1) is enough, and no
 * transaction needs recording at all.
 *
 * <p><b>The note is read straight from the column, not through the API.</b>
 * {@link #storedNoteOf} reads {@code bookmarks.note} as the database holds it, which is the only way to
 * show that what is stored is an envelope and not the student's words. Asserting through the response
 * would prove only that the round trip works.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows,
 * and the seeded accounts are read but never modified. The seeded student is left alone deliberately:
 * other suites read their tips and dashboard.
 */
abstract class AbstractBookmarksApiIT extends AbstractMySqlIntegrationTest {

    protected static final String BOOKMARKS_URL = "/api/v1/bookmarks";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TIPS_URL = "/api/v1/tips";
    protected static final String TIPS_GENERATE_URL = "/api/v1/tips/generate";

    protected static final String PASSWORD = "Student@123";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties a bookmark response may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the record fails a test instead of
     * quietly widening the published contract. There is no {@code userId}: the query already applied
     * ownership and a client has no use for the owner. There is no {@code insightId} either, because the
     * insight branch is UC-17 and this build cannot create one.
     */
    protected static final List<String> DOCUMENTED_BOOKMARK_FIELDS = List.of(
            "id", "itemType", "tipId", "tipTitle", "tipBody", "tipPotentialSaving",
            "tipState", "tipMonth", "note", "createdAt");

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

    /** The caller's saved items, asserting the call succeeded first. */
    protected JsonNode bookmarks(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, BOOKMARKS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("bookmarks body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** Saves a tip through the endpoint a client uses. */
    protected ResponseEntity<String> saveTip(String token, Long tipId) {
        return saveTip(token, tipId, null);
    }

    protected ResponseEntity<String> saveTip(String token, Long tipId, String note) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("itemType", "TIP");
        body.put("itemId", tipId);
        if (note != null) {
            body.put("note", note);
        }
        return send(HttpMethod.POST, BOOKMARKS_URL, token, body);
    }

    protected ResponseEntity<String> setNote(String token, Long bookmarkId, String note) {
        return send(HttpMethod.PATCH, BOOKMARKS_URL + "/" + bookmarkId, token, Map.of("note", note));
    }

    /**
     * The same call with {@code note} written as an explicit JSON {@code null}.
     *
     * <p>Separate from {@link #setNote} because {@code Map.of} refuses a null value - which is
     * itself the reason the distinction needs its own test: "absent" and "present but null" are two
     * spellings of the same instruction, and both have to reach the service as "leave it alone".
     */
    protected ResponseEntity<String> setNoteToNull(String token, Long bookmarkId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("note", null);
        return send(HttpMethod.PATCH, BOOKMARKS_URL + "/" + bookmarkId, token, body);
    }

    protected ResponseEntity<String> unmark(String token, Long bookmarkId) {
        return send(HttpMethod.DELETE, BOOKMARKS_URL + "/" + bookmarkId, token, null);
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
    }

    /** The {@code fieldErrors[].field} names of a validation response, in the order returned. */
    protected static List<String> fieldNamesIn(JsonNode error) {
        JsonNode fieldErrors = error.get("fieldErrors");
        if (fieldErrors == null || fieldErrors.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        fieldErrors.forEach(fieldError -> names.add(fieldError.get("field").asText()));
        return names;
    }

    protected static List<Long> bookmarkIdsOf(JsonNode listResponse) {
        List<Long> ids = new ArrayList<>();
        listResponse.forEach(bookmark -> ids.add(bookmark.get("id").asLong()));
        return ids;
    }

    protected static List<Long> savedTipIdsOf(JsonNode listResponse) {
        List<Long> ids = new ArrayList<>();
        listResponse.forEach(bookmark -> ids.add(bookmark.get("tipId").asLong()));
        return ids;
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
        return "bookmarks.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  A real tip to save
    // ==================================================================

    /**
     * Generates the caller's tips for the current month and returns the first one.
     *
     * <p>The endpoint, not the procedure, because this suite has no reason to reach a month the
     * endpoint cannot: a student with no records this month gets the "too little data" tip (UC-18 A1)
     * when the generator runs, and that tip is a perfectly good thing to save. It keeps the fixture
     * honest - the row under test is the row a real caller would bookmark - and it keeps this suite from
     * duplicating module 9's spending fixtures to produce a tip nobody here inspects.
     *
     * <p>The whole node rather than the id, so a test can hold the bookmark to the tip's own words
     * without asking the tips endpoint a second time.
     */
    protected JsonNode generatedTip(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, TIPS_GENERATE_URL, token, null);
        assertThat(response.getStatusCode())
                .as("generate body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode tips = body(response).get("tips");
        assertThat(tips.isArray()).as("generate body=%s", response.getBody()).isTrue();
        assertThat(tips).as("the generator produced no tip to save").isNotEmpty();
        return tips.get(0);
    }

    /** Generates the caller's tips for the current month and returns the first one's id. */
    protected Long generateATip(String token) throws Exception {
        return generatedTip(token).get("id").asLong();
    }

    /** Generates the caller's tips and saves the first one, returning the created entry. */
    protected JsonNode saveATip(String token) throws Exception {
        return saveTipExpectingCreated(token, generateATip(token));
    }

    /** Saves a tip and asserts the call succeeded, returning the created entry. */
    protected JsonNode saveTipExpectingCreated(String token, Long tipId) throws Exception {
        return saveTipExpectingCreated(token, tipId, null);
    }

    /** The same, with a note. */
    protected JsonNode saveTipExpectingCreated(String token, Long tipId, String note)
            throws Exception {
        ResponseEntity<String> response = saveTip(token, tipId, note);
        assertThat(response.getStatusCode())
                .as("save body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
    }

    /** Saves a tip and returns the new bookmark's id. */
    protected Long saveATipId(String token) throws Exception {
        return saveATip(token).get("id").asLong();
    }

    /**
     * Pins or dismisses one of the caller's tips, through the endpoint the tips screen uses.
     *
     * <p>Used to show that a bookmark is independent of the tip's display state (VĐ-03): the same row
     * is reachable through two different acts, and neither changes the other's column.
     */
    protected ResponseEntity<String> changeTipState(String token, Long tipId, String state) {
        return send(HttpMethod.POST, TIPS_URL + "/" + tipId + "/state", token,
                Map.of("state", state));
    }

    /** The state of a bookmark's tip, read from {@code user_tips} rather than through the API. */
    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

    /**
     * A second, distinct tip for the same student, produced for a month the generate endpoint cannot
     * reach.
     *
     * <p>A test that needs two bookmarks needs two tips, because {@code uk_bookmark_dedupe} refuses
     * the same item twice. {@code POST /api/v1/tips/generate} only ever runs for the current month,
     * so a student with no records has exactly one tip available through the API; the second comes
     * from the same procedure with the month named, which is the route module 9's own fixtures
     * document.
     */
    protected Long generateATipFor(Long userId, LocalDate periodMonth) throws Exception {
        generateTipsFor(userId, periodMonth, 3);

        List<Long> tipIds = longValuesFrom(
                "SELECT id FROM user_tips WHERE user_id = ? AND period_month = ? ORDER BY id",
                userId, periodMonth);
        assertThat(tipIds)
                .as("the generator produced no tip for %s", periodMonth)
                .isNotEmpty();
        return tipIds.get(0);
    }

    /** Generates tips the way the scheduled run does, for a month the generate endpoint cannot reach. */
    protected void generateTipsFor(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    /** The first of the current month, the value the views derive from {@code CURDATE()}. */
    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    protected static LocalDate monthBefore(LocalDate month) {
        return month.minusMonths(1);
    }

    /** A month as the API names it: {@code yyyy-MM}. */
    protected static String asMonth(LocalDate month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    /**
     * {@code bookmarks.note} exactly as MySQL holds it.
     *
     * <p>The one read that can show the column is ciphertext rather than the student's words. Null when
     * the row has no note, which is a state the API produces on purpose.
     */
    protected String storedNoteOf(Long bookmarkId) throws Exception {
        return columnInDatabase(bookmarkId, "bookmarks", "note");
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

    /**
     * A {@code bookmarks} row's owner as the database holds it.
     *
     * <p>Used to prove a fabricated row really belongs to the other student, so an assertion about the
     * trigger's refusal is testing the trigger rather than the fixture.
     */
    protected Long bookmarksOwner(Long bookmarkId) throws Exception {
        return longValueFrom("SELECT user_id FROM bookmarks WHERE id = ?", bookmarkId);
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

    protected List<Long> longValuesFrom(String sql, Object... parameters) throws Exception {
        List<Long> values = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getLong(1));
                }
            }
        }
        return values;
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

    /**
     * Binds the parameters of a fixture statement.
     *
     * <p>{@link LocalDate} is bound as a {@link java.sql.Date} on purpose. The driver sends a
     * {@code LocalDate} as a character value and lets the server cast it, which works for a {@code DATE}
     * column but silently coerces anything else - including the temporal argument
     * {@code sp_generate_tips} declares as {@code DATE}. Binding the declared type means a procedure is
     * called the way a real caller calls it.
     */
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
