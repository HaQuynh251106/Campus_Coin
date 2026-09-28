package com.campuscoin.tips;

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

abstract class AbstractTipsApiIT extends AbstractMySqlIntegrationTest {

    protected static final String TIPS_URL = "/api/v1/tips";
    protected static final String TIP_MONTHS_URL = "/api/v1/tips/months";
    protected static final String TIPS_GENERATE_URL = "/api/v1/tips/generate";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";
    protected static final String BUDGETS_URL = "/api/v1/budgets";

    protected static final String PASSWORD = "Student@123";

    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";
    protected static final String ENTERTAINMENT = "Entertainment";
    protected static final String INCOME_CATEGORY = "Allowance";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final List<String> DOCUMENTED_TIP_FIELDS = List.of(
            "id", "categoryId", "title", "body", "potentialSaving", "state");

    protected static final List<String> DOCUMENTED_LIST_FIELDS = List.of("periodMonth", "tips");

    protected static final List<String> DOCUMENTED_MONTHS_FIELDS = List.of("months");

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

    protected JsonNode tips(String token, String month) throws Exception {
        String url = month == null ? TIPS_URL : TIPS_URL + "?month=" + month;
        ResponseEntity<String> response = send(HttpMethod.GET, url, token, null);
        assertThat(response.getStatusCode())
                .as("tips body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    protected JsonNode currentTips(String token) throws Exception {
        return tips(token, null);
    }

    protected JsonNode months(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, TIP_MONTHS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("months body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    protected ResponseEntity<String> generateTipsViaApi(String token) {
        return send(HttpMethod.POST, TIPS_GENERATE_URL, token, null);
    }

    protected ResponseEntity<String> changeTipState(String token, Long tipId, String state) {
        return send(HttpMethod.POST, TIPS_URL + "/" + tipId + "/state", token,
                Map.of("state", state));
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
    }

    protected static List<Long> tipIdsOf(JsonNode listResponse) {
        List<Long> ids = new ArrayList<>();
        listResponse.get("tips").forEach(tip -> ids.add(tip.get("id").asLong()));
        return ids;
    }

    protected static List<String> tipStatesOf(JsonNode listResponse) {
        List<String> states = new ArrayList<>();
        listResponse.get("tips").forEach(tip -> states.add(tip.get("state").asText()));
        return states;
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
        return "tips.test." + UUID.randomUUID() + "@student.campuscoin.edu";
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

    protected Long createCategory(String token, String name, String type) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, Map.of(
                "name", name, "type", type, "icon", "tag", "color", "#123456"));
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
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
        return body(response).get("id").asLong();
    }

    protected Long spendInCategory(String token, Long categoryId, String amount, LocalDate month)
            throws Exception {
        LocalDate date = month.withDayOfMonth(1);
        assertThat(date).as("cannot file spending in a month that has not started").isBeforeOrEqualTo(today());
        return createTransaction(token, categoryId, amount, date, "Fixture spending");
    }

    protected Long spendInCategoryWithoutBudget(String token, Long categoryId, String amount,
                                                LocalDate month) throws Exception {
        return spendInCategory(token, categoryId, amount, month);
    }

    protected void setBudget(String token, Long categoryId, String limitAmount, LocalDate month)
            throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token, Map.of(
                "categoryId", categoryId,
                "limitAmount", limitAmount,
                "periodMonth", asMonth(month)));
        assertThat(response.getStatusCode())
                .as("budget body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    protected void setSavingsGoal(String token, String amount) {
        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token,
                Map.of("monthlySavingsGoal", amount));
        assertThat(response.getStatusCode())
                .as("profile body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    protected void generateTipsFor(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

    protected String tipColumnOf(Long tipId, String column) throws Exception {
        return columnInDatabase(tipId, "user_tips", column);
    }

    protected List<Long> tipIdsFor(Long userId, LocalDate periodMonth) throws Exception {
        return longValuesFrom("SELECT id FROM user_tips WHERE user_id = ? AND period_month = ? "
                + "ORDER BY id", userId, periodMonth);
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
            bind(statement, parameters);
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

    protected String templateCodeOf(Long tipId) throws Exception {
        List<String> codes = stringValuesFrom(
                "SELECT tt.code FROM user_tips t JOIN tip_templates tt ON tt.id = t.tip_template_id "
                        + "WHERE t.id = ?", tipId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    protected Long tipIdFromTemplate(JsonNode listResponse, String templateCode) throws Exception {
        for (JsonNode tip : listResponse.get("tips")) {
            if (templateCode.equals(templateCodeOf(tip.get("id").asLong()))) {
                return tip.get("id").asLong();
            }
        }
        return null;
    }

    protected List<String> templateCodesOf(JsonNode listResponse) throws Exception {
        List<String> codes = new ArrayList<>();
        for (JsonNode tip : listResponse.get("tips")) {
            codes.add(templateCodeOf(tip.get("id").asLong()));
        }
        return codes;
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

    protected static LocalDate monthBefore(LocalDate month) {
        return month.minusMonths(1);
    }

    protected static String asMonth(LocalDate month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }
}
