package com.campuscoin.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
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

abstract class AbstractInsightApiIT extends AbstractMySqlIntegrationTest {

    protected static final String INSIGHTS_URL = "/api/v1/insights";
    protected static final String INSIGHT_MONTHS_URL = "/api/v1/insights/months";
    protected static final String INSIGHTS_GENERATE_URL = "/api/v1/insights/generate";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";

    protected static final String PASSWORD = "Student@123";

    protected static final String FOOD = "Food";

    protected static final String ALLOWANCE = "Allowance";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    protected static final List<String> DOCUMENTED_TOP_FIELDS = List.of(
            "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
            "generatedBy", "model", "flaggedCategories", "generatedAt");

    protected static final List<String> DOCUMENTED_RULE_BASED_FIELDS = List.of(
            "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
            "generatedBy", "flaggedCategories", "generatedAt");

    protected static final List<String> DOCUMENTED_FLAGGED_FIELDS = List.of(
            "categoryId", "categoryName", "currentTotal", "baselineAvg", "pctChange");

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

    protected ResponseEntity<String> insightResponse(String token, String month) {
        return send(HttpMethod.GET, withMonth(INSIGHTS_URL, month), token, null);
    }

    protected JsonNode insight(String token, String month) throws Exception {
        ResponseEntity<String> response = insightResponse(token, month);
        assertThat(response.getStatusCode())
                .as("insight body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> generateResponse(String token, String month) {
        return send(HttpMethod.POST, withMonth(INSIGHTS_GENERATE_URL, month), token, null);
    }

    protected JsonNode generate(String token, String month) throws Exception {
        ResponseEntity<String> response = generateResponse(token, month);
        assertThat(response.getStatusCode())
                .as("generate body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected JsonNode months(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, INSIGHT_MONTHS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("months body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    private static String withMonth(String url, String month) {
        return month == null ? url : url + "?month=" + month;
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

    protected static List<String> monthLabelsOf(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.get("months").forEach(month -> labels.add(month.asText()));
        return labels;
    }

    protected static List<String> flaggedNamesOf(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.get("flaggedCategories").forEach(flag -> names.add(flag.get("categoryName").asText()));
        return names;
    }

    protected static JsonNode flaggedCategory(JsonNode response, String categoryName) {
        for (JsonNode flag : response.get("flaggedCategories")) {
            if (categoryName.equals(flag.get("categoryName").asText())) {
                return flag;
            }
        }
        throw new AssertionError("No flagged category named " + categoryName + " in "
                + response.get("flaggedCategories") + "; the response flagged "
                + flaggedNamesOf(response));
    }

    protected static LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    protected static LocalDate completeMonth(int monthsAgo) {
        return currentMonth().minusMonths(monthsAgo);
    }

    protected static LocalDate within(LocalDate month) {
        return month.withDayOfMonth(15);
    }

    protected static String asMonth(LocalDate month) {
        return MONTH.format(month);
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
        return "insight.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {

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

    protected Long expenseIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), amount, within(month), "Insight fixture");
    }

    protected Long incomeIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(ALLOWANCE), amount, within(month),
                "Insight fixture");
    }

    protected void buildMonth(String token, LocalDate month, String income, String expense)
            throws Exception {
        incomeIn(token, month, income);
        expenseIn(token, month, expense);
    }

    protected int insightCountOf(Long userId, LocalDate month) throws Exception {
        return countOf("SELECT COUNT(*) FROM insights WHERE user_id = ? AND period_month = ?",
                userId, month);
    }

    protected int insightTotalOf(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM insights WHERE user_id = ?", userId);
    }

    protected Object insightColumnOf(Long userId, LocalDate month, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM insights WHERE user_id = ? AND period_month = ?")) {
            statement.setLong(1, userId);
            statement.setDate(2, java.sql.Date.valueOf(month));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("%s for %s", column, month).isTrue();
                return row.getObject(1);
            }
        }
    }

    protected String storedSummaryOf(Long userId, LocalDate month) throws Exception {
        return (String) insightColumnOf(userId, month, "summary_text");
    }

    protected BigDecimal storedTotalIncomeOf(Long userId, LocalDate month) throws Exception {
        return (BigDecimal) insightColumnOf(userId, month, "total_income");
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
