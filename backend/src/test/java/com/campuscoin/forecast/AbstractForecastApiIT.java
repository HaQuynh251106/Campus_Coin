package com.campuscoin.forecast;

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

abstract class AbstractForecastApiIT extends AbstractMySqlIntegrationTest {

    protected static final String FORECAST_URL = "/api/v1/forecast";

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
            "nextMonth", "currentMonth", "basedOnMonths", "recentMonths", "currentMonthTotals",
            "projected");

    protected static final List<String> DOCUMENTED_MONTH_FIELDS = List.of(
            "periodMonth", "income", "expense", "net");

    protected static final List<String> DOCUMENTED_CURRENT_FIELDS = List.of("income", "expense", "net");

    protected static final List<String> DOCUMENTED_PROJECTED_FIELDS = List.of(
            "income", "expense", "savings");

    protected static final int WINDOW_MONTHS = 3;

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

    protected JsonNode forecast(String token) throws Exception {
        ResponseEntity<String> response = forecastResponse(token);
        assertThat(response.getStatusCode())
                .as("forecast body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> forecastResponse(String token) {
        return send(HttpMethod.GET, FORECAST_URL, token, null);
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

    protected static List<String> recentMonthLabelsOf(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.get("recentMonths").forEach(month -> labels.add(month.get("periodMonth").asText()));
        return labels;
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

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
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
        return "forecast.test." + UUID.randomUUID() + "@student.campuscoin.edu";
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
        return createTransaction(token, defaultCategoryId(FOOD), amount, within(month), "Forecast fixture");
    }

    protected Long incomeIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(ALLOWANCE), amount, within(month),
                "Forecast fixture");
    }

    protected void buildMonth(String token, LocalDate month, String income, String expense)
            throws Exception {
        incomeIn(token, month, income);
        expenseIn(token, month, expense);
    }

    protected record StoredMonth(BigDecimal income, BigDecimal expense) {
    }

    protected StoredMonth monthTotalsOf(Long userId, LocalDate month) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT total_income, total_expense FROM v_monthly_income_expense "
                             + "WHERE user_id = ? AND period_month = ?")) {
            statement.setLong(1, userId);
            statement.setDate(2, java.sql.Date.valueOf(month));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }

                return new StoredMonth(row.getBigDecimal("total_income"),
                        row.getBigDecimal("total_expense"));
            }
        }
    }

    protected int monthCountOf(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM v_monthly_income_expense WHERE user_id = ?", userId);
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
