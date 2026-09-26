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

/**
 * The fixtures UC-17's suite needs: identity, months of real records, and the database reads that can
 * show what the endpoint stored.
 *
 * <p><b>Why a month's figures are built from transactions rather than written into {@code insights}.</b>
 * An insight is not a row a test can sensibly invent: its totals, its flagged categories and both prose
 * sentences are {@code sp_generate_monthly_insight}'s answers about the student's own records, and its
 * summary names the month's highest-spending category. A hand-inserted row would test the API against
 * figures the procedure would never produce - a summary saying "you spent 100.00 on Food" over a month
 * with no Food in it. So every fixture here builds the <em>spending</em> through
 * {@code POST /api/v1/transactions}, and the endpoint under test generates the insight from it. What a
 * test then asserts is the procedure's own answer about its own rows.
 *
 * <p><b>Dates are back-dated into whole past months.</b> BR-15's flag compares a month against the
 * student's preceding months, so a fixture that could only produce records dated today could not produce
 * a baseline at all. {@code POST /api/v1/transactions} refuses a future date (BR-08) but accepts a past
 * one, so {@link #within} picks the 15th of a month - a day inside it, never a boundary - which keeps
 * these assertions from depending on the day the suite runs.
 *
 * <p><b>Stored values are read back from the table rather than assumed.</b> {@link #insightColumnOf}
 * returns what MySQL actually holds, so the tests that assert the AI overlay wrote {@code generated_by}
 * beside the text - and that the procedure preserved the text on a later run - are reading the column
 * rather than the response the same write produced.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's rows and
 * no test can see another's insight. The seeded accounts are read but never modified.
 */
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

    /** A seeded shared default the fixture's spending is filed under. */
    protected static final String FOOD = "Food";

    /** A seeded shared default that makes a record income rather than an expense (BR-05). */
    protected static final String ALLOWANCE = "Allowance";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** The month label the response uses, matching BR-17's {@code yyyy-MM}. */
    protected static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Everything the response may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the record fails a test instead of
     * quietly widening the published contract. There is no {@code userId} and no {@code email}: every
     * figure came from the caller's own token, and an insight states one student's income, expense and
     * net balance, so a response naming its owner would publish an identifier beside the most sensitive
     * figures in the system.
     *
     * <p>{@code status} and {@code errorMessage} are absent although the table has them: they exist so a
     * failed provider call can be recorded and this build writes neither, so publishing a field that is
     * always null would describe a state the feature does not have.
     */
    protected static final List<String> DOCUMENTED_TOP_FIELDS = List.of(
            "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
            "generatedBy", "model", "flaggedCategories", "generatedAt");

    /**
     * The same set as an insight with no provider behind it carries.
     *
     * <p>Everything but {@code model}, which is omitted rather than null when no provider wrote the text
     * - a deployment with no credential is the ordinary case, and one fact carried by two fields is a
     * second place for them to disagree.
     */
    protected static final List<String> DOCUMENTED_RULE_BASED_FIELDS = List.of(
            "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
            "generatedBy", "flaggedCategories", "generatedAt");

    /** The properties of one flagged category, as BR-15's flag carries its evidence. */
    protected static final List<String> DOCUMENTED_FLAGGED_FIELDS = List.of(
            "categoryId", "categoryName", "currentTotal", "baselineAvg", "pctChange");

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

    /** {@code GET /api/v1/insights}, unasserted, for the tests that examine a refusal. */
    protected ResponseEntity<String> insightResponse(String token, String month) {
        return send(HttpMethod.GET, withMonth(INSIGHTS_URL, month), token, null);
    }

    /** The caller's insight, asserting the call succeeded first. */
    protected JsonNode insight(String token, String month) throws Exception {
        ResponseEntity<String> response = insightResponse(token, month);
        assertThat(response.getStatusCode())
                .as("insight body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** {@code POST /api/v1/insights/generate}, unasserted. */
    protected ResponseEntity<String> generateResponse(String token, String month) {
        return send(HttpMethod.POST, withMonth(INSIGHTS_GENERATE_URL, month), token, null);
    }

    /** Generates the insight and asserts it succeeded, which is the setup most tests need. */
    protected JsonNode generate(String token, String month) throws Exception {
        ResponseEntity<String> response = generateResponse(token, month);
        assertThat(response.getStatusCode())
                .as("generate body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** {@code GET /api/v1/insights/months}. */
    protected JsonNode months(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, INSIGHT_MONTHS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("months body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    /** Adds the month query parameter only when one was named, so "no month" is a real absence. */
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

    /** The month labels in a months response, in the order they were returned. */
    protected static List<String> monthLabelsOf(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.get("months").forEach(month -> labels.add(month.asText()));
        return labels;
    }

    /** The category names in a flagged list, in the order they were returned. */
    protected static List<String> flaggedNamesOf(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.get("flaggedCategories").forEach(flag -> names.add(flag.get("categoryName").asText()));
        return names;
    }

    /** The one flagged category with the given name, or a failure naming what was there instead. */
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

    // ==================================================================
    //  The application's own idea of "now"
    // ==================================================================

    /** The month in progress, derived exactly as the service derives it. */
    protected static LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    /** The {@code n}-th whole month before the current one: 1 is last month, 2 the month before. */
    protected static LocalDate completeMonth(int monthsAgo) {
        return currentMonth().minusMonths(monthsAgo);
    }

    /**
     * A date inside a given month, mid-month so a month boundary is never straddled.
     *
     * <p>Any day inside the month would do - a month's totals do not depend on which day of it a record
     * falls - but staying off the first and last days keeps a record from ever landing in a neighbouring
     * month if the application's clock and the database's session clock were to disagree about the
     * instant the test ran. The 15th is used rather than "day 1" for that reason.
     */
    protected static LocalDate within(LocalDate month) {
        return month.withDayOfMonth(15);
    }

    /** A month as the API names it: {@code yyyy-MM}. */
    protected static String asMonth(LocalDate month) {
        return MONTH.format(month);
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
        return "insight.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  Months of real records
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Records one transaction through the API, which is how the views see it. */
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

    /** One expense in a month, in the seeded Food category. */
    protected Long expenseIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(FOOD), amount, within(month), "Insight fixture");
    }

    /** One income in a month, in the seeded Allowance category. */
    protected Long incomeIn(String token, LocalDate month, String amount) throws Exception {
        return createTransaction(token, defaultCategoryId(ALLOWANCE), amount, within(month),
                "Insight fixture");
    }

    /** One month's income and expense, so its totals are exactly the amounts passed. */
    protected void buildMonth(String token, LocalDate month, String income, String expense)
            throws Exception {
        incomeIn(token, month, income);
        expenseIn(token, month, expense);
    }

    // ==================================================================
    //  Database reads
    // ==================================================================

    /** How many insight rows the student holds for one month; {@code uk_insight_user_month} caps it at 1. */
    protected int insightCountOf(Long userId, LocalDate month) throws Exception {
        return countOf("SELECT COUNT(*) FROM insights WHERE user_id = ? AND period_month = ?",
                userId, month);
    }

    /** How many insight rows the student holds in total, which is how many months they cover. */
    protected int insightTotalOf(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM insights WHERE user_id = ?", userId);
    }

    /**
     * One stored column of the student's insight for a month.
     *
     * <p>Read as the column's own type so an assertion compares a figure rather than its formatting, and
     * so the tests that check the AI overlay moved {@code generated_by} are reading the table rather than
     * the response the write produced.
     */
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

    /** The stored prose, decrypted by nobody - the column is plaintext by design (OB-012). */
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
