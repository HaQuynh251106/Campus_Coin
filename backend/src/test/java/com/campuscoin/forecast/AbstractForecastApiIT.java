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

/**
 * The fixtures UC-25's suite needs: identity, months of real records to average, and the database
 * reads that can show what the API computed.
 *
 * <p><b>Why the months are built from transactions rather than written into a view.</b> The figure the
 * forecast predicts is the figure the reports screen shows, and that figure is
 * {@code v_monthly_income_expense}'s - which sums live transactions and nothing else. A fixture that
 * supplied the DAO with a hand-made total would be asserting arithmetic over numbers the API never
 * sees, and the assertion that matters - "the projection is the average of what this student actually
 * spent" - would be untestable. So the months are built the way a student builds them, through
 * {@code POST /api/v1/transactions}, and the suite's expectation is computed from the same records.
 *
 * <p><b>Dates are back-dated into whole past months.</b> A forecast is a statement about complete
 * months, so a fixture that could only produce records dated today could not produce even one complete
 * month. {@code POST /api/v1/transactions} refuses a future date (BR-08) but accepts a past one, so the
 * helpers below take a month and pick a day inside it. Because every month used is at least one month
 * behind the current one, "the whole month is behind us" holds regardless of the day the suite runs -
 * which is what keeps these assertions from being date-dependent.
 *
 * <p><b>Totals are read from the view, not recomputed in Java.</b> {@link #monthTotalsOf} reads
 * {@code v_monthly_income_expense} directly, so a test asserting that the projection is the average of
 * the student's months is comparing the response against the database's own answer to "what did this
 * student spend" rather than against a second implementation of it.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows,
 * and the seeded accounts are read but never modified. (The seeded student carries transactions in the
 * seeded months, which is why only the two tests that need a user with history use their own account.)
 */
abstract class AbstractForecastApiIT extends AbstractMySqlIntegrationTest {

    protected static final String FORECAST_URL = "/api/v1/forecast";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";

    protected static final String PASSWORD = "Student@123";

    /** A seeded shared default the fixture's records are filed under. */
    protected static final String FOOD = "Food";

    /** A seeded shared default that makes a record income rather than an expense (BR-05). */
    protected static final String ALLOWANCE = "Allowance";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** The month label the response uses, matching BR-17's {@code yyyy-MM}. */
    protected static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * The complete set of properties the response may carry at its top level.
     *
     * <p>A literal rather than a reflected set, so a field added to the record fails a test instead of
     * quietly widening the published contract. There is no {@code userId} and no {@code email}: every
     * figure came from the caller's own token, and a response that named its owner would be publishing
     * an identifier the forecast has no use for.
     */
    protected static final List<String> DOCUMENTED_TOP_FIELDS = List.of(
            "nextMonth", "currentMonth", "basedOnMonths", "recentMonths", "currentMonthTotals",
            "projected");

    /** The properties of one evidence month. */
    protected static final List<String> DOCUMENTED_MONTH_FIELDS = List.of(
            "periodMonth", "income", "expense", "net");

    /** The properties of the current-month block. */
    protected static final List<String> DOCUMENTED_CURRENT_FIELDS = List.of("income", "expense", "net");

    /** The properties of the projection. */
    protected static final List<String> DOCUMENTED_PROJECTED_FIELDS = List.of(
            "income", "expense", "savings");

    /** How many complete months the projection averages. Pinned here so the fixture can build them. */
    protected static final int WINDOW_MONTHS = 3;

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

    /** The caller's forecast, asserting the call succeeded first. */
    protected JsonNode forecast(String token) throws Exception {
        ResponseEntity<String> response = forecastResponse(token);
        assertThat(response.getStatusCode())
                .as("forecast body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** The caller's forecast, unasserted, for the tests that examine a refusal. */
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

    /** The month labels in the evidence list, in the order they were returned. */
    protected static List<String> recentMonthLabelsOf(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.get("recentMonths").forEach(month -> labels.add(month.get("periodMonth").asText()));
        return labels;
    }

    // ==================================================================
    //  The application's own idea of "now"
    // ==================================================================

    /** The month in progress, derived exactly as the service derives it. */
    protected static LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    /** The {@code n}-th complete month before the current one: 1 is last month, 2 the month before. */
    protected static LocalDate completeMonth(int monthsAgo) {
        return currentMonth().minusMonths(monthsAgo);
    }

    /**
     * A date inside a given month, a day past the first so a month boundary is never straddled.
     *
     * <p>Any day inside the month would do - a month's totals do not depend on which day of it a record
     * falls - but staying off the first and last days keeps a record from ever landing in a neighbouring
     * month if the application's clock and the database's session clock were to disagree about the
     * instant the test ran. The 15th is used rather than "day 1" for that reason.
     */
    protected static LocalDate within(LocalDate month) {
        return month.withDayOfMonth(15);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
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

    /** A token for the seeded student, who carries transactions in the seeded months. */
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

    // ==================================================================
    //  Months of real records
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Creates one transaction through the API, which is how the view sees it. */
    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {
        // LinkedHashMap rather than Map.of, which refuses null values - and a record with no description
        // is something a real caller can create.
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

    /** One expense in a complete month, in the seeded Food category. */
    protected Long expenseIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), amount, within(month), "Forecast fixture");
    }

    /** One income in a complete month, in the seeded Allowance category. */
    protected Long incomeIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(ALLOWANCE), amount, within(month),
                "Forecast fixture");
    }

    /**
     * Builds {@code monthsAgo} complete months of the given income and expense for one student.
     *
     * <p>Each month gets one income and one expense record, so the month's totals are exactly the amounts
     * passed - which is what lets a test assert the projection against arithmetic it can state in one
     * line rather than against a second query.
     */
    protected void buildMonth(String token, LocalDate month, String income, String expense)
            throws Exception {
        incomeIn(token, month, income);
        expenseIn(token, month, expense);
    }

    // ==================================================================
    //  Database reads
    // ==================================================================

    /** One month's totals as {@code v_monthly_income_expense} holds them, or null when absent. */
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
                // BigDecimal rather than the driver's string form, so an assertion compares the figure
                // and not the scale: MySQL may report 100.0000 where the response says 100.00, and
                // comparing text would make this test about decimal formatting instead.
                return new StoredMonth(row.getBigDecimal("total_income"),
                        row.getBigDecimal("total_expense"));
            }
        }
    }

    /** How many rows the view holds for a student, which is how many months they have any history in. */
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
