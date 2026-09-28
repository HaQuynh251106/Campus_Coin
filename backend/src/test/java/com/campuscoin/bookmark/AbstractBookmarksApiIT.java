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

abstract class AbstractBookmarksApiIT extends AbstractMySqlIntegrationTest {

    protected static final String BOOKMARKS_URL = "/api/v1/bookmarks";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TIPS_URL = "/api/v1/tips";
    protected static final String TIPS_GENERATE_URL = "/api/v1/tips/generate";

    protected static final String PASSWORD = "Student@123";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final List<String> DOCUMENTED_BOOKMARK_FIELDS = List.of(
            "id", "itemType", "tipId", "tipTitle", "tipBody", "tipPotentialSaving",
            "tipState", "tipMonth", "note", "createdAt");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    protected ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    protected JsonNode bookmarks(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, BOOKMARKS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("bookmarks body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

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

    protected Long generateATip(String token) throws Exception {
        return generatedTip(token).get("id").asLong();
    }

    protected JsonNode saveATip(String token) throws Exception {
        return saveTipExpectingCreated(token, generateATip(token));
    }

    protected JsonNode saveTipExpectingCreated(String token, Long tipId) throws Exception {
        return saveTipExpectingCreated(token, tipId, null);
    }

    protected JsonNode saveTipExpectingCreated(String token, Long tipId, String note)
            throws Exception {
        ResponseEntity<String> response = saveTip(token, tipId, note);
        assertThat(response.getStatusCode())
                .as("save body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
    }

    protected Long saveATipId(String token) throws Exception {
        return saveATip(token).get("id").asLong();
    }

    protected ResponseEntity<String> changeTipState(String token, Long tipId, String state) {
        return send(HttpMethod.POST, TIPS_URL + "/" + tipId + "/state", token,
                Map.of("state", state));
    }

    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

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

    protected void generateTipsFor(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    protected static LocalDate monthBefore(LocalDate month) {
        return month.minusMonths(1);
    }

    protected static String asMonth(LocalDate month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }

    protected String storedNoteOf(Long bookmarkId) throws Exception {
        return columnInDatabase(bookmarkId, "bookmarks", "note");
    }

    protected String columnInDatabase(Long id, String table, String column) throws Exception {

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
