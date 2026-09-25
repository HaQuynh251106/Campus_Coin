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

/**
 * The fixtures module 7's dashboard suite needs: identity, categories, transactions, and the direct
 * writes the API deliberately has no route for.
 *
 * <p><b>Why the direct writes are necessary here.</b> A dashboard reads four views that are written
 * by other modules' procedures - an announcement by {@code sp_admin_create_announcement} (UC-21, a
 * later module), a tip by {@code sp_generate_tips} (UC-18), a transaction by this API. Two of those
 * writers have no endpoint yet, so the only way to test what a dashboard does with an
 * administrator-only announcement or with a pinned tip is to produce the row the way the owning
 * procedure eventually will. Where the API can write the row, the fixture uses the API.
 *
 * <p>The pattern each subclass follows is the same as the earlier modules': register a fresh student
 * with a random address so no test depends on another's rows, read the seeded default categories but
 * never modify them, and leave the seeded accounts alone.
 */
abstract class AbstractDashboardApiIT extends AbstractMySqlIntegrationTest {

    protected static final String DASHBOARD_URL = "/api/v1/dashboard";
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
    protected static final String SUBSCRIPTIONS = "Subscriptions";
    protected static final String INCOME_CATEGORY = "Allowance";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties the dashboard response may carry at the top level.
     *
     * <p>A literal rather than a reflected set, so adding a field to the record fails a test instead
     * of quietly widening the published contract. There is no {@code userId} and no month-scoped
     * parameter echoed back beyond {@code periodMonth} itself.
     */
    protected static final List<String> DOCUMENTED_TOP_LEVEL_FIELDS = List.of(
            "periodMonth", "summary", "topCategory", "tips", "announcements");

    /** The complete set of properties {@code summary} may carry (UC-12 B1). */
    protected static final List<String> DOCUMENTED_SUMMARY_FIELDS = List.of(
            "currency", "totalIncome", "totalExpense", "netAmount", "monthlyAllowanceBaseline",
            "monthlySavingsGoal", "savingsGoalPct");

    /** The complete set of properties {@code topCategory} may carry (UC-12 B2). */
    protected static final List<String> DOCUMENTED_TOP_CATEGORY_FIELDS = List.of(
            "categoryId", "categoryName", "categoryIcon", "categoryColor", "totalAmount");

    /** The complete set of properties a tip may carry (UC-12 B3). */
    protected static final List<String> DOCUMENTED_TIP_FIELDS = List.of(
            "id", "categoryId", "title", "body", "potentialSaving", "state");

    /** The complete set of properties an announcement may carry (UC-12 B3). */
    protected static final List<String> DOCUMENTED_ANNOUNCEMENT_FIELDS = List.of(
            "id", "title", "body", "severity", "startsAt", "endsAt");

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

    /** The dashboard as a parsed tree, asserting the call succeeded first. */
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

    // ==================================================================
    //  Writing the rows the API has no route for yet
    // ==================================================================

    /**
     * Generates tips for one student and month the way UC-18's procedure will.
     *
     * <p>The API has no route that generates a tip - {@code sp_generate_tips} is called by the module
     * that owns UC-18 - so a dashboard test that needs tips to exist must produce them the way the
     * real caller does. Calling the procedure rather than inserting rows keeps the fixture honest:
     * the data a dashboard reads is the data the generator actually writes, ranking and all.
     */
    protected void generateTips(Long userId, LocalDate periodMonth, int maxTips) throws Exception {
        runInDatabase("CALL sp_generate_tips(?, ?, ?)", userId, periodMonth, maxTips);
    }

    /** One tip row's state, as the database holds it. */
    protected String tipStateOf(Long tipId) throws Exception {
        return columnInDatabase(tipId, "user_tips", "state");
    }

    /** UC-18's pin: the transition the dashboard's ordering responds to but does not perform. */
    protected void pinTip(Long tipId) throws Exception {
        runInDatabase("UPDATE user_tips SET state = 'PINNED', pinned_at = NOW() WHERE id = ?", tipId);
    }

    /** UC-18's dismiss: the transition that must remove a tip from the dashboard for good. */
    protected void dismissTip(Long tipId) throws Exception {
        runInDatabase("UPDATE user_tips SET state = 'DISMISSED', dismissed_at = NOW() WHERE id = ?",
                tipId);
    }

    protected List<Long> tipIdsFor(Long userId, LocalDate periodMonth) throws Exception {
        return longValuesFrom("SELECT id FROM user_tips WHERE user_id = ? AND period_month = ? "
                + "ORDER BY id", userId, periodMonth);
    }

    /**
     * Posts one announcement exactly as UC-21 will.
     *
     * <p>Written directly because the administrator endpoint that creates an announcement belongs to
     * module 11 and does not exist yet. {@code ck_ann_window} still applies - {@code ends_at} must be
     * after {@code starts_at} - so a fixture that got the window backwards would fail here rather than
     * produce a row the schema forbids.
     *
     * @param audience {@code ALL}, {@code STUDENTS} or {@code ADMINS}
     */
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

    /** Removes an announcement a test created, so the shared container keeps its seeded state. */
    protected void deleteAnnouncement(Long announcementId) throws Exception {
        runInDatabase("DELETE FROM announcements WHERE id = ?", announcementId);
    }

    // ==================================================================
    //  Database helpers
    // ==================================================================

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

    /**
     * Binds the parameters of a fixture statement.
     *
     * <p>{@link java.sql.Date} and {@link java.time.LocalDate} are distinguished on purpose. The
     * driver sends a {@link LocalDate} as a character value and lets the server cast it, which works
     * for a {@code DATE} column but silently coerces anything else - including the temporal arguments
     * {@code sp_generate_tips} declares as {@code DATE}. Converting here means a procedure is called
     * with the type it declares, so the results a test reads are the results a real caller would get.
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

    /** A date inside the current month, guaranteed not to be in the future (BR-08). */
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

    /** A request body with the given fields, in a stable order, for the mass-assignment probes. */
    protected static Map<String, Object> requestOf(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            body.put((String) keyValues[index], keyValues[index + 1]);
        }
        return body;
    }
}
