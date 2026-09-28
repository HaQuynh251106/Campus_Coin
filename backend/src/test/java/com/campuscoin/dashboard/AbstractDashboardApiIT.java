package com.campuscoin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

abstract class AbstractDashboardApiIT extends AbstractMySqlIntegrationTest {

    protected static final String DASHBOARD_URL = "/api/v1/dashboard";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";
    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    protected static final String PASSWORD = "Student@123";

    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";
    protected static final String SUBSCRIPTIONS = "Subscriptions";
    protected static final String INCOME_CATEGORY = "Allowance";
    protected static final String ACADEMICS = "Academics";
    protected static final String ENTERTAINMENT = "Entertainment";
    protected static final String MISCELLANEOUS = "Miscellaneous";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final List<String> DOCUMENTED_TOP_LEVEL_FIELDS = List.of(
            "periodMonth", "summary", "topCategory", "tips", "announcements");

    protected static final List<String> DOCUMENTED_SUMMARY_FIELDS = List.of(
            "currency", "totalIncome", "totalExpense", "netAmount", "monthlyAllowanceBaseline",
            "monthlySavingsGoal", "savingsGoalPct");

    protected static final List<String> DOCUMENTED_TOP_CATEGORY_FIELDS = List.of(
            "categoryId", "categoryName", "categoryIcon", "categoryColor", "totalAmount");

    protected static final List<String> DOCUMENTED_TIP_FIELDS = List.of(
            "id", "categoryId", "title", "body", "potentialSaving", "state");

    protected static final List<String> DOCUMENTED_ANNOUNCEMENT_FIELDS = List.of(
            "id", "title", "body", "severity", "startsAt", "endsAt");

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

    protected JsonNode dashboard(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, DASHBOARD_URL, token, null);
        assertThat(response.getStatusCode())
                .as("dashboard body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected Long userIdOf(String token) throws Exception {
        return body(send(HttpMethod.GET, PROFILE_URL, token, null)).get("id").asLong();
    }

    protected Long defaultCategoryId(String name) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM categories WHERE user_id IS NULL AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("seeded category %s exists", name).isTrue();
                return row.getLong(1);
            }
        }
    }

    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, TRANSACTIONS_URL, token, Map.of(
                "categoryId", categoryId,
                "amount", amount,
                "txnDate", date.toString(),
                "description", description));
        assertThat(response.getStatusCode())
                .as("transaction body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    protected Long createCategory(String token, String name, String type) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, Map.of(
                "name", name, "type", type, "icon", "tag", "color", "#123456"));
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    protected void generateTips(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

    protected void pinTip(Long tipId) throws Exception {
        runInDatabase("UPDATE user_tips SET state = 'PINNED', pinned_at = NOW() WHERE id = ?", tipId);
    }

    protected void dismissTip(Long tipId) throws Exception {
        runInDatabase("UPDATE user_tips SET state = 'DISMISSED', dismissed_at = NOW() WHERE id = ?",
                tipId);
    }

    protected List<Long> tipIdsFor(Long userId, LocalDate periodMonth) throws Exception {
        return longValuesFrom("SELECT id FROM user_tips WHERE user_id = ? AND period_month = ? "
                + "ORDER BY id", userId, periodMonth);
    }

    protected List<Long> rankedTipIdsFor(Long userId, LocalDate periodMonth) throws Exception {
        return longValuesFrom("SELECT tip_id FROM v_dashboard_tips WHERE user_id = ? "
                + "AND period_month = ? ORDER BY display_order", userId, periodMonth);
    }

    protected Long insertAnnouncement(String title, String severity, String audience,
                                      LocalDateTime startsAt, LocalDateTime endsAt,
                                      boolean active) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO announcements (title, body, severity, audience, starts_at, "
                             + "ends_at, is_active, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, "
                             + "(SELECT id FROM users WHERE email = ?))",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, title);
            statement.setString(2, "Announcement body for " + title);
            statement.setString(3, severity);
            statement.setString(4, audience);
            statement.setObject(5, startsAt);
            statement.setObject(6, endsAt);
            statement.setBoolean(7, active);
            statement.setString(8, SEEDED_ADMIN_EMAIL);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    protected void deleteAnnouncement(Long announcementId) throws Exception {
        runInDatabase("DELETE FROM announcements WHERE id = ?", announcementId);
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

    protected int countOf(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
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

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    protected static LocalDate thisMonthOn(int day) {
        LocalDate candidate = thisMonth().withDayOfMonth(day);
        return candidate.isAfter(today()) ? today() : candidate;
    }

    protected static String monthKey(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    protected String randomEmail() {
        return "dashboard." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    protected static Map<String, Object> requestOf(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            body.put((String) keyValues[index], keyValues[index + 1]);
        }
        return body;
    }
}
