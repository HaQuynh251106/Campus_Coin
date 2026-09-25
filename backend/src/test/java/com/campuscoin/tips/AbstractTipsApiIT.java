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

/**
 * The fixtures module 9's tips suite needs: identity, the spending a tip is generated from, and the
 * two states the API can be asked for.
 *
 * <p><b>Why the fixtures write transactions rather than tips.</b> A tip is not something a test can
 * sensibly invent: which tips exist, what they say and what they are worth are {@code
 * sp_generate_tips}'s answers about the student's own records, so a hand-inserted {@code user_tips}
 * row would test the API against data the generator would never produce - a tip whose title names a
 * category the student never spent in, or a {@code rank_score} that does not match BR-14's ordering.
 * Every fixture here therefore builds the <em>spending</em> and then either lets the endpoint generate
 * the tips or calls the procedure directly, so the rows under test are the rows the real caller gets.
 *
 * <p>The two direct procedure calls are a convenience, not a way round the API: {@link
 * #generateTipsFor} exists so a test can set up a month the generate endpoint cannot reach (the
 * endpoint only ever runs for the current month), and {@link #generateTipsViaApi} is the endpoint
 * itself - the same generator, reached the way a client reaches it.
 *
 * <p>Each test registers a fresh student with a random address, so no test depends on another's
 * rows, and the seeded accounts are read but never modified. The seeded student is left alone
 * deliberately: several other suites read their tips through the dashboard.
 */
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

    /** Seeded shared defaults a tip can be generated about. */
    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";
    protected static final String ENTERTAINMENT = "Entertainment";
    protected static final String INCOME_CATEGORY = "Allowance";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties a tip response may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the record fails a test instead
     * of quietly widening the published contract. There is no {@code userId}, no {@code rankScore}
     * and no {@code periodMonth} - the month is named once on the wrapper, and the score is the
     * ordering the array already carries.
     */
    protected static final List<String> DOCUMENTED_TIP_FIELDS = List.of(
            "id", "categoryId", "title", "body", "potentialSaving", "state");

    /** The properties of the list response's wrapper (UC-18 B1). */
    protected static final List<String> DOCUMENTED_LIST_FIELDS = List.of("periodMonth", "tips");

    /** The properties of the months response. */
    protected static final List<String> DOCUMENTED_MONTHS_FIELDS = List.of("months");

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

    /** The caller's tips for a month, asserting the call succeeded first. */
    protected JsonNode tips(String token, String month) throws Exception {
        String url = month == null ? TIPS_URL : TIPS_URL + "?month=" + month;
        ResponseEntity<String> response = send(HttpMethod.GET, url, token, null);
        assertThat(response.getStatusCode())
                .as("tips body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** The caller's tips for the current month. */
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

    /** Asks the endpoint to generate the caller's tips for the current month. */
    protected ResponseEntity<String> generateTipsViaApi(String token) {
        return send(HttpMethod.POST, TIPS_GENERATE_URL, token, null);
    }

    /** Moves one tip to a state through the endpoint the student uses. */
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

    /** The {@code id} of every tip in a list response, in the order returned. */
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
        return "tips.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  Spending, and the categories a tip can be about
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

    protected Long createCategory(String token, String name, String type) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, Map.of(
                "name", name, "type", type, "icon", "tag", "color", "#123456"));
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
    }

    /** Records one transaction through the API, which is how the views see it. */
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

    /**
     * Records one transaction dated on the first of a month.
     *
     * <p>The first of the month is used as the day. BR-08 refuses a future date on a manual record,
     * so a month that has not started cannot be spent in - the fixture asserts that rather than
     * silently moving the date, because a transaction filed in the wrong month would put the tip in
     * a month the test did not ask about.
     *
     * <p>This is the general primitive every tip fixture builds on: a rule fires or does not fire
     * according to the records and budgets that exist, so the fixtures create those and let the
     * procedure decide. What a test then asserts is the procedure's answer about its own rows.
     */
    protected Long spendInCategory(String token, Long categoryId, String amount, LocalDate month)
            throws Exception {
        LocalDate date = month.withDayOfMonth(1);
        assertThat(date).as("cannot file spending in a month that has not started").isBeforeOrEqualTo(today());
        return createTransaction(token, categoryId, amount, date, "Fixture spending");
    }

    /**
     * Records spending in a category the test has <em>not</em> budgeted, so rule 4 fires for it.
     *
     * <p>Named for the fixture's intent rather than for a guarantee: rule 4 ({@code NO_BUDGET_SET})
     * fires for an expense category with spending in the month and no {@code budgets} row, so a test
     * that later calls {@link #setBudget} for the same category has changed which rule applies. That
     * is exactly what the budget tests want, and it is why the budget fixtures exist.
     */
    protected Long spendInCategoryWithoutBudget(String token, Long categoryId, String amount,
                                                LocalDate month) throws Exception {
        return spendInCategory(token, categoryId, amount, month);
    }

    /**
     * Sets a monthly limit on an expense category, which is what makes rules 1 and 2 reachable.
     *
     * <p>The order matters and is the point: the limit is set <em>after</em> the spending, so the
     * month already holds more than the limit and the category is over it. Rule 2's band is
     * {@code [near, exceeded)} and rule 1's is {@code [exceeded, ∞)}, both read from
     * {@code v_budget_consumption}.
     */
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

    /**
     * Sets the monthly savings goal rule 5 compares the month's net against (VĐ-04).
     *
     * <p>A goal is what makes rule 5 reachable, and it is set high enough that the month's net falls
     * below it - the tip's {@code potentialSaving} is then the shortfall exactly.
     */
    protected void setSavingsGoal(String token, String amount) {
        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token,
                Map.of("monthlySavingsGoal", amount));
        assertThat(response.getStatusCode())
                .as("profile body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    // ==================================================================
    //  Generating tips
    // ==================================================================

    /**
     * Generates tips the way the scheduled run does, for a month the generate endpoint cannot reach.
     *
     * <p>{@code POST /api/v1/tips/generate} only ever runs for the current month, by design. A test
     * that needs a month with no tips, or two months to prove the months list, has to produce them
     * another way - so it calls the same procedure the endpoint calls, with the month named. The rows
     * are identical to the ones the endpoint produces because it is the same procedure.
     *
     * <p>{@code p_max_tips} is passed explicitly here, unlike the production callers: a fixture that
     * wants exactly one tip says so rather than depending on the seeded {@code tips.max_dashboard}
     * setting, which a test must not be able to change by accident.
     */
    protected void generateTipsFor(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

    /** A {@code user_tips} column as the database holds it, for the timestamp pair. */
    protected String tipColumnOf(Long tipId, String column) throws Exception {
        return columnInDatabase(tipId, "user_tips", column);
    }

    protected List<Long> tipIdsFor(Long userId, LocalDate periodMonth) throws Exception {
        return longValuesFrom("SELECT id FROM user_tips WHERE user_id = ? AND period_month = ? "
                + "ORDER BY id", userId, periodMonth);
    }

    protected String columnInDatabase(Long id, String table, String column) throws Exception {
        // The table and column names come from test literals only, never from a request, so
        // interpolating them is safe; the id is bound.
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

    /**
     * The {@code tip_templates.code} a tip was generated from, or {@code null} for a tip with no
     * template.
     *
     * <p>This is how a rule is identified in an assertion. The alternative - matching the tip's
     * rendered title - would be asserting {@code fn_render_template}'s output, and the alternative
     * of re-deriving {@code potential_saving} would be restating the rule's arithmetic. The template
     * a row records is the database's own statement of which rule produced it, and it is what the
     * uniqueness key is built from, so it is the fact a test can hold the API to.
     */
    protected String templateCodeOf(Long tipId) throws Exception {
        List<String> codes = stringValuesFrom(
                "SELECT tt.code FROM user_tips t JOIN tip_templates tt ON tt.id = t.tip_template_id "
                        + "WHERE t.id = ?", tipId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    /** The id of the one tip produced by a given template, or {@code null} if no tip used it. */
    protected Long tipIdFromTemplate(JsonNode listResponse, String templateCode) throws Exception {
        for (JsonNode tip : listResponse.get("tips")) {
            if (templateCode.equals(templateCodeOf(tip.get("id").asLong()))) {
                return tip.get("id").asLong();
            }
        }
        return null;
    }

    /** Every template code among a list response's tips, in the order returned. */
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

    /**
     * Binds the parameters of a fixture statement.
     *
     * <p>{@link LocalDate} is bound as a {@link java.sql.Date} on purpose. The driver sends a
     * {@code LocalDate} as a character value and lets the server cast it, which works for a
     * {@code DATE} column but silently coerces anything else - including the temporal argument
     * {@code sp_generate_tips} declares as {@code DATE}. Binding the declared type means the
     * procedure is called the way a real caller calls it.
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

    /** The first of the current month, which is the value the views derive from {@code CURDATE()}. */
    protected static LocalDate thisMonth() {
        return today().withDayOfMonth(1);
    }

    protected static LocalDate monthBefore(LocalDate month) {
        return month.minusMonths(1);
    }

    /** A month as the API names it: {@code yyyy-MM}. */
    protected static String asMonth(LocalDate month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }
}
