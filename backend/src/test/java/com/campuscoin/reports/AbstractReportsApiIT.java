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

/**
 * The fixtures module 8's reports suite needs: identity, categories, transactions, and the reads a
 * report is checked against.
 *
 * <p><b>Everything a report reads is written through the API.</b> A report has no writer of its own -
 * it has no table, no procedure and no migration - so unlike the dashboard suite, this one needs no
 * direct database writes to produce its input. Every figure it asserts against is put there by
 * {@code POST /api/v1/transactions} and read back through HTTP, which means the suite exercises the
 * path a real caller takes rather than one only a test can construct. The direct connection is used
 * only to <em>read</em>, to confirm that a response matches the row the schema holds.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows
 * and the seeded accounts - including the demo account with three months of history - are never
 * modified.
 */
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

    /** Seeded shared defaults every student may file under. */
    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";
    protected static final String ENTERTAINMENT = "Entertainment";
    protected static final String INCOME_CATEGORY = "Allowance";
    protected static final String SECOND_INCOME_CATEGORY = "Scholarship";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties the report response may carry at the top level.
     *
     * <p>A literal rather than a reflected set, so adding a field to the record fails a test instead
     * of quietly widening the published contract. There is no {@code userId}, no {@code from}/{@code to}
     * pair - those belong to the spending series - and no echoed request parameter.
     */
    protected static final List<String> DOCUMENTED_REPORT_FIELDS = List.of(
            "periodMonth", "currency", "totals", "expenseByCategory", "incomeByCategory",
            "sixMonthTrend");

    /** The complete set of properties the totals block may carry (UC-15). */
    protected static final List<String> DOCUMENTED_TOTALS_FIELDS = List.of(
            "income", "expense", "net", "transactionCount");

    /** The complete set of properties a category slice may carry (UC-15). */
    protected static final List<String> DOCUMENTED_CATEGORY_FIELDS = List.of(
            "categoryId", "categoryName", "categoryIcon", "categoryColor", "type", "total",
            "percentage", "transactionCount");

    /** The complete set of properties a trend point may carry (UC-15, BR-17). */
    protected static final List<String> DOCUMENTED_TREND_FIELDS = List.of(
            "periodMonth", "income", "expense", "net");

    /** The complete set of properties the spending series response may carry (UC-15). */
    protected static final List<String> DOCUMENTED_SERIES_FIELDS = List.of(
            "granularity", "from", "to", "currency", "totalExpense", "points");

    /** The complete set of properties a spending point may carry (UC-15). */
    protected static final List<String> DOCUMENTED_POINT_FIELDS = List.of(
            "intervalStart", "intervalEnd", "totalExpense", "transactionCount");

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

    /** The current month's report as a parsed tree, asserting the call succeeded first. */
    protected JsonNode report(String token) throws Exception {
        return report(token, null);
    }

    /** A named month's report, or the current one when {@code month} is null. */
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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(response).get("accessToken").asText();
    }

    protected Long userIdOf(String token) throws Exception {
        return body(send(HttpMethod.GET, PROFILE_URL, token, null)).get("id").asLong();
    }

    // ==================================================================
    //  Categories and transactions
    // ==================================================================

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

    /** Records one transaction through the API, which is how the report views see it. */
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

    /** Soft-deletes one transaction through the API, so BR-09's exclusion is what is under test. */
    protected void deleteTransaction(String token, Long transactionId) throws Exception {
        ResponseEntity<String> response =
                send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);
        assertThat(response.getStatusCode())
                .as("delete body=%s", response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Creates a personal category with a name no other test can collide with.
     *
     * <p>The name is suffixed with a random token because a student's categories are unique per
     * {@code (owner, type, name)} <em>and</em> the seeded defaults share that namespace - so a second
     * test creating "Food" would collide on {@code uk_categories_scope_type_name} rather than
     * exercise the report.
     */
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

    // ==================================================================
    //  Database reads
    // ==================================================================

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

    /**
     * Binds the parameters of a fixture statement.
     *
     * <p>{@link LocalDate} is sent as {@link java.sql.Date} so the server receives the type the
     * column and the views expect, rather than a character value it has to coerce.
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

    // ==================================================================
    //  Time
    // ==================================================================

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    /** The first of the current month, which is what the {@code CURDATE()} views derive. */
    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    /** The first of a month {@code monthsBack} before the current one. */
    protected static LocalDate monthBefore(int monthsBack) {
        return thisMonth().minusMonths(monthsBack);
    }

    /** A date inside the current month, guaranteed not to be in the future (BR-08). */
    protected static LocalDate thisMonthOn(int day) {
        LocalDate candidate = thisMonth().withDayOfMonth(day);
        return candidate.isAfter(today()) ? today() : candidate;
    }

    /** A date inside a previous month. Any day is safe: past dates are always permitted (BR-08). */
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

    /** A request body with the given fields, in a stable order, for the mass-assignment probes. */
    protected static Map<String, Object> requestOf(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            body.put((String) keyValues[index], keyValues[index + 1]);
        }
        return body;
    }
}
