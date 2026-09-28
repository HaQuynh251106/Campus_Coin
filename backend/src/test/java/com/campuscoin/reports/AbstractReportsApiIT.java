package com.campuscoin.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

abstract class AbstractReportsApiIT extends AbstractMySqlIntegrationTest {

    protected static final String REPORTS_URL = "/api/v1/reports";
    protected static final String SPENDING_URL = "/api/v1/reports/spending";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";
    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    protected static final String PASSWORD = "Student@123";

    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";
    protected static final String ENTERTAINMENT = "Entertainment";
    protected static final String INCOME_CATEGORY = "Allowance";
    protected static final String SECOND_INCOME_CATEGORY = "Scholarship";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final List<String> DOCUMENTED_REPORT_FIELDS = List.of(
            "periodMonth", "currency", "totals", "expenseByCategory", "incomeByCategory",
            "sixMonthTrend");

    protected static final List<String> DOCUMENTED_TOTALS_FIELDS = List.of(
            "income", "expense", "net", "transactionCount");

    protected static final List<String> DOCUMENTED_CATEGORY_FIELDS = List.of(
            "categoryId", "categoryName", "categoryIcon", "categoryColor", "type", "total",
            "percentage", "transactionCount");

    protected static final List<String> DOCUMENTED_TREND_FIELDS = List.of(
            "periodMonth", "income", "expense", "net");

    protected static final List<String> DOCUMENTED_SERIES_FIELDS = List.of(
            "granularity", "from", "to", "currency", "totalExpense", "points");

    protected static final List<String> DOCUMENTED_POINT_FIELDS = List.of(
            "intervalStart", "intervalEnd", "totalExpense", "transactionCount");

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

    protected JsonNode report(String token) throws Exception {
        return report(token, null);
    }

    protected JsonNode report(String token, String month) throws Exception {
        String url = month == null ? REPORTS_URL : REPORTS_URL + "?month=" + month;
        ResponseEntity<String> response = send(HttpMethod.GET, url, token, null);
        assertThat(response.getStatusCode())
                .as("report body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    protected ResponseEntity<String> reportResponse(String token, String query) throws Exception {
        return send(HttpMethod.GET, query == null ? REPORTS_URL : REPORTS_URL + "?" + query,
                token, null);
    }

    protected ResponseEntity<String> spendingResponse(String token, String query) throws Exception {
        return send(HttpMethod.GET, query == null ? SPENDING_URL : SPENDING_URL + "?" + query,
                token, null);
    }

    protected JsonNode spending(String token, String query) throws Exception {
        ResponseEntity<String> response = spendingResponse(token, query);
        assertThat(response.getStatusCode())
                .as("spending body=%s", response.getBody())
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

    protected void deleteTransaction(String token, Long transactionId) throws Exception {
        ResponseEntity<String> response =
                send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);
        assertThat(response.getStatusCode())
                .as("delete body=%s", response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected Long createCategory(String token, String prefix, String type) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, Map.of(
                "name", prefix + " " + UUID.randomUUID().toString().substring(0, 8),
                "type", type,
                "icon", "tag",
                "color", "#123456"));
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
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

    protected static LocalDate monthBefore(int monthsBack) {
        return thisMonth().minusMonths(monthsBack);
    }

    protected static LocalDate thisMonthOn(int day) {
        LocalDate candidate = thisMonth().withDayOfMonth(day);
        return candidate.isAfter(today()) ? today() : candidate;
    }

    protected static LocalDate monthBeforeOn(int monthsBack, int day) {
        return monthBefore(monthsBack).withDayOfMonth(day);
    }

    protected static String monthKey(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    protected static String lastDayOfMonthKey(LocalDate month) {
        return month.withDayOfMonth(month.lengthOfMonth())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    protected String randomEmail() {
        return "reports." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    protected static Map<String, Object> requestOf(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            body.put((String) keyValues[index], keyValues[index + 1]);
        }
        return body;
    }
}
