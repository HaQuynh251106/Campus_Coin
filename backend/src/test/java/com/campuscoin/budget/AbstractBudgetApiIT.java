package com.campuscoin.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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

abstract class AbstractBudgetApiIT extends AbstractMySqlIntegrationTest {

    protected static final String BUDGETS_URL = "/api/v1/budgets";
    protected static final String NOTIFICATIONS_URL = "/api/v1/notifications";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";
    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    protected static final String PASSWORD = "Student@123";

    protected static final String DEFAULT_EXPENSE_NAME = "Food";

    protected static final String SECOND_EXPENSE_NAME = "Transport";

    protected static final String DEFAULT_INCOME_NAME = "Allowance";

    protected static final List<String> DOCUMENTED_BUDGET_FIELDS = List.of(
            "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "periodMonth",
            "limitAmount", "spentAmount", "remainingAmount", "consumedPct", "consumptionStatus");

    protected static final List<String> DOCUMENTED_NOTIFICATION_FIELDS = List.of(
            "id", "type", "title", "body", "linkUrl", "refEntityType", "refEntityId", "isRead",
            "readAt", "createdAt");

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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

    protected static List<String> fieldNamesOfValidationError(JsonNode error) {
        List<String> names = new ArrayList<>();
        error.get("fieldErrors").forEach(fieldError -> names.add(fieldError.get("field").asText()));
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

    protected Long createCategory(String token, String name, String type, String icon, String color)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        if (icon != null) {
            body.put("icon", icon);
        }
        if (color != null) {
            body.put("color", color);
        }

        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, body);
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    protected void retire(String token, Long categoryId, boolean inactive) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("isActive", !inactive));
        assertThat(response.getStatusCode())
                .as("retire body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(booleanInDatabase(categoryId, "categories", "is_active"))
                .isEqualTo(!inactive);
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

    protected JsonNode createBudget(String token, Long categoryId, String limit,
                                    LocalDate periodMonth) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token, Map.of(
                "categoryId", categoryId,
                "limitAmount", limit,
                "periodMonth", monthKey(periodMonth)));
        assertThat(response.getStatusCode())
                .as("create budget body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    protected List<String> alertRowsFor(Long budgetId) throws Exception {
        return stringsFrom("SELECT CONCAT(threshold_type, '|', consumed_pct) FROM budget_alert_log "
                + "WHERE budget_id = ? ORDER BY id", budgetId);
    }

    protected record NotificationRow(String type, String title, String body, String linkUrl,
                                     String refEntityType, Long refEntityId, boolean isRead,
                                     String readAt) {
    }

    protected List<NotificationRow> notificationsFor(Long userId) throws Exception {
        List<NotificationRow> rows = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT type, title, body, link_url, ref_entity_type, ref_entity_id, is_read, "
                             + "read_at FROM notifications WHERE user_id = ? ORDER BY id")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new NotificationRow(
                            result.getString("type"),
                            result.getString("title"),
                            result.getString("body"),
                            result.getString("link_url"),
                            result.getString("ref_entity_type"),
                            result.getObject("ref_entity_id") == null
                                    ? null : result.getLong("ref_entity_id"),
                            result.getBoolean("is_read"),
                            result.getObject("read_at") == null
                                    ? null : result.getTimestamp("read_at").toString()));
                }
            }
        }
        return rows;
    }

    protected int notificationCountFor(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM notifications WHERE user_id = ?", userId);
    }

    protected boolean budgetExists(Long budgetId) throws Exception {
        return countOf("SELECT COUNT(*) FROM budgets WHERE id = ?", budgetId) > 0;
    }

    protected Long insertBudgetDirectly(Long userId, Long categoryId, LocalDate periodMonth,
                                        String limit) throws Exception {
        runInDatabase("CALL sp_validate_budget(?, ?)", userId, categoryId);
        runInDatabase("INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                + "VALUES (?, ?, ?, ?)", userId, categoryId, periodMonth, new BigDecimal(limit));
        return budgetIdOf(userId, categoryId, periodMonth);
    }

    protected Long budgetIdOf(Long userId, Long categoryId, LocalDate periodMonth) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM budgets WHERE user_id = ? AND category_id = ? "
                             + "AND period_month = ?")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setObject(3, periodMonth);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("a budget exists for that student, category and month")
                        .isTrue();
                return row.getLong(1);
            }
        }
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

    protected boolean booleanInDatabase(Long id, String table, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("%s %d exists", table, id).isTrue();
                return row.getBoolean(1);
            }
        }
    }

    protected int countOf(String sql, Object parameter) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    protected List<String> stringsFrom(String sql, Object parameter) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
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
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    protected String insertBudgetExpectingRefusal(Long userId, Long categoryId, LocalDate periodMonth,
                                                  String limit) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setObject(3, periodMonth);
            statement.setBigDecimal(4, new BigDecimal(limit));
            statement.executeUpdate();
            return "inserted";
        } catch (SQLException ex) {
            return ex.getSQLState();
        }
    }

    protected String signalledMessageOf(Long userId, Long categoryId, LocalDate periodMonth,
                                        String limit) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setObject(3, periodMonth);
            statement.setBigDecimal(4, new BigDecimal(limit));
            statement.executeUpdate();
            return "inserted";
        } catch (SQLException ex) {
            return ex.getMessage() == null ? "" : ex.getMessage();
        }
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    protected static String monthKey(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    protected String randomEmail() {
        return "budget." + UUID.randomUUID() + "@student.campuscoin.edu";
    }
}
