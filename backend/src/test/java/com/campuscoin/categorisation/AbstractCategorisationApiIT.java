package com.campuscoin.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * The fixtures UC-08's suite needs: identity, a record to categorise, and the database reads that can
 * show what the endpoint wrote.
 *
 * <p><b>Every test registers a fresh student.</b> UC-08 <em>learns</em>, and what it learns is stored
 * per student in {@code category_rules}. A suite that reused one account would have each test teaching
 * the rules the next one then matches against, so a failure would depend on the order the tests ran in -
 * which is exactly the kind of coupling that makes a suite stop being evidence. A random address per
 * test costs a registration and buys independence.
 *
 * <p><b>Records are created through the API rather than inserted.</b> The endpoint reads the record
 * through the same tables module 4 writes, so a fixture that inserted rows directly would have to invent
 * the ownership, the {@code is_deleted} state and the description's envelope - and the tests that assert
 * one student's record is invisible to another would then be testing the fixture's insert rather than the
 * query. Going through {@code POST /api/v1/transactions} means the rows under test are rows a real caller
 * creates, with the triggers and the encryption boundary running over them.
 *
 * <p><b>The description is read back decrypted rather than assumed.</b> {@link #storedDescriptionOf}
 * returns what MySQL actually holds, and {@link #decryptedDescriptionOf} proves the words survive; a test
 * that compared a stored description with the plaintext it sent would be asserting that encryption did
 * not happen.
 */
abstract class AbstractCategorisationApiIT extends AbstractMySqlIntegrationTest {

    protected static final String SUGGEST_URL = "/api/v1/ai/suggest-category";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";

    protected static final String PASSWORD = "Student@123";

    /** A seeded shared default the fixture's records are filed under. */
    protected static final String FOOD = "Food";

    /** A second seeded shared default, for the filing that contradicts a suggestion. */
    protected static final String TRANSPORT = "Transport";

    /** The description the acceptance scenario revolves around. */
    protected static final String CAMPUS_CAFE = "Campus Cafe";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The complete set of properties the response may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the response fails a test instead of
     * quietly widening the published contract. There is no {@code userId} and no {@code categoryRules}
     * list: the answer is about one record and one mapping, and an entry says nothing about the account it
     * belongs to.
     */
    protected static final java.util.List<String> DOCUMENTED_RESPONSE_FIELDS = java.util.List.of(
            "transactionId", "source", "categoryId", "categoryName", "type", "confidence", "reason",
            "learned");

    /** The complete set of properties the learned mapping may carry. */
    protected static final java.util.List<String> DOCUMENTED_LEARNED_FIELDS = java.util.List.of(
            "keyword", "categoryId", "categoryName", "source");

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

    /** Asks for a suggestion, unasserted - most tests here are about what the answer says. */
    protected ResponseEntity<String> suggest(String token, Long transactionId) {
        return send(HttpMethod.POST, SUGGEST_URL, token, Map.of("transactionId", transactionId));
    }

    /** Asks for a suggestion and asserts it succeeded, which is the setup most tests need. */
    protected JsonNode suggestExpectingOk(String token, Long transactionId) throws Exception {
        ResponseEntity<String> response = suggest(token, transactionId);
        assertThat(response.getStatusCode())
                .as("suggest body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
    }

    /**
     * An error body with its timestamp removed, so two refusals can be compared field for field.
     *
     * <p>The timestamp is the one field that necessarily differs between two calls and it is carried in
     * every {@code ApiError}. Comparing the bodies without it is what turns "the two answers are the
     * same" into an assertion about the contract rather than a statement about the clock.
     */
    protected static String withoutTimestamp(JsonNode error) {
        return error.toString().replaceAll("\"timestamp\":\"[^\"]*\",?", "");
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

    /** A fresh student's token, on an account nobody else's tests have touched. */
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
        return "categorisation.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  Records to categorise
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Creates one transaction through the API, which is how the triggers and the queries see it. */
    protected Long createTransaction(String token, Long categoryId, String amount, LocalDate date,
                                     String description) throws Exception {
        // LinkedHashMap rather than Map.of, which refuses null values - and a record with no description
        // is a case this suite has to produce.
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

    /** A record in the named seeded shared category, described {@code description}. */
    protected Long aRecordIn(String token, String defaultCategoryName, String description)
            throws Exception {
        return createTransaction(token, defaultCategoryId(defaultCategoryName), "25000",
                today(), description);
    }

    /** A record in a category the student created themselves (UC-06), ided directly. */
    protected Long aRecordInCategory(String token, Long categoryId, String description)
            throws Exception {
        return createTransaction(token, categoryId, "25000", today(), description);
    }

    /**
     * Creates a category of the caller's own (UC-06) and returns its id.
     *
     * <p>The id is returned rather than looked up afterwards because a lookup by name is not scoped to an
     * owner: the suite shares one database, so a fixed name like "Campus Food" would also find a category
     * another test's student created. The id the create answered with is the only unambiguous handle.
     */
    protected Long createPersonalCategory(String token, String name) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token,
                Map.of("name", name, "type", "EXPENSE"));
        assertThat(response.getStatusCode())
                .as("create category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
    }

    /** A record with a description and no other claim on it - the ordinary case. */
    protected Long aRecord(String token, String description) throws Exception {
        return aRecordIn(token, FOOD, description);
    }

    /** Moves a stored record to another category, through the endpoint the student uses (UC-10). */
    protected ResponseEntity<String> moveToCategory(String token, Long transactionId,
                                                    Long categoryId) {
        return send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("categoryId", categoryId));
    }

    /** Moves a record and asserts the module 4 contract, because most callers expect it to work. */
    protected void moveToCategoryExpectingOk(String token, Long transactionId, Long categoryId) {
        ResponseEntity<String> response = moveToCategory(token, transactionId, categoryId);
        assertThat(response.getStatusCode())
                .as("move %d body=%s", transactionId, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /** Moves a record to the trash through the endpoint the student uses (BR-09). */
    protected void trash(String token, Long transactionId) {
        ResponseEntity<String> response =
                send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);
        assertThat(response.getStatusCode())
                .as("trash %d body=%s", transactionId, response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    /** The three suggestion columns exactly as the database holds them. */
    protected record StoredSuggestion(Long suggestedCategoryId, java.math.BigDecimal confidence,
                                      boolean overridden) {
    }

    protected StoredSuggestion storedSuggestionOf(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ai_suggested_category_id, ai_confidence, ai_overridden "
                             + "FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("transaction %d exists", transactionId).isTrue();
                Object suggested = row.getObject("ai_suggested_category_id");
                return new StoredSuggestion(
                        suggested == null ? null : ((Number) suggested).longValue(),
                        row.getBigDecimal("ai_confidence"),
                        row.getBoolean("ai_overridden"));
            }
        }
    }

    /** The category a record is filed under, which UC-08 must never change (BR-13). */
    protected Long storedCategoryIdOf(Long transactionId) throws Exception {
        return longValueFrom("SELECT category_id FROM transactions WHERE id = ?", transactionId);
    }

    /**
     * {@code transactions.description} exactly as MySQL holds it.
     *
     * <p>Two encryptions of the same words are not the same string - the envelope carries a fresh random
     * IV - so a fixture can compare this against another envelope to prove the column was rewritten, and
     * decrypt it to prove the words are still recoverable.
     */
    protected String storedDescriptionOf(Long transactionId) throws Exception {
        return columnInDatabase(transactionId, "transactions", "description");
    }

    /** How many {@code transaction_history} rows a record has (BR-09). */
    protected int historyCountOf(Long transactionId) throws Exception {
        return countOf("SELECT COUNT(*) FROM transaction_history WHERE transaction_id = ?",
                transactionId);
    }

    /**
     * The history row a categorisation write added, or {@code null} when none did.
     *
     * <p>Found by its {@code changed_fields} text rather than by counting, because a record's history
     * begins with the row its creation wrote - so "the count went up by one" is only meaningful once that
     * row is excluded. {@code trg_transactions_after_insert} records {@code changed_fields = 'created'},
     * and {@code trg_transactions_after_update} writes the columns that moved, so a row naming one of the
     * three {@code ai_} columns is the one this module caused and nothing else can be.
     */
    protected String categorisationHistoryFieldsOf(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT changed_fields FROM transaction_history "
                             + "WHERE transaction_id = ? "
                             + "AND (changed_fields LIKE '%aiSuggestedCategoryId%' "
                             + "     OR changed_fields LIKE '%aiConfidence%' "
                             + "     OR changed_fields LIKE '%aiOverridden%') "
                             + "ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString("changed_fields") : null;
            }
        }
    }

    /** The student's stored mapping for a keyword, or {@code null} if they have none (UC-08 B6). */
    protected String storedRuleCategoryNameOf(Long userId, String keyword) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT c.name FROM category_rules r "
                             + "JOIN categories c ON c.id = r.category_id "
                             + "WHERE r.user_id = ? AND r.keyword = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, keyword);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString("name") : null;
            }
        }
    }

    /** How many mapping rows the student has for a keyword, which the unique key should hold at one. */
    protected int storedRuleCountOf(Long userId, String keyword) throws Exception {
        return countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ? AND keyword = ?",
                userId, keyword);
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
