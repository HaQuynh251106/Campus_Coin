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

abstract class AbstractCategorisationApiIT extends AbstractMySqlIntegrationTest {

    protected static final String SUGGEST_URL = "/api/v1/ai/suggest-category";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";
    protected static final String CATEGORIES_URL = "/api/v1/categories";

    protected static final String PASSWORD = "Student@123";

    protected static final String FOOD = "Food";

    protected static final String TRANSPORT = "Transport";

    protected static final String CAMPUS_CAFE = "Campus Cafe";

    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    protected static final java.util.List<String> DOCUMENTED_RESPONSE_FIELDS = java.util.List.of(
            "transactionId", "source", "categoryId", "categoryName", "type", "confidence", "reason",
            "learned");

    protected static final java.util.List<String> DOCUMENTED_LEARNED_FIELDS = java.util.List.of(
            "keyword", "categoryId", "categoryName", "source");

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

    protected ResponseEntity<String> suggest(String token, Long transactionId) {
        return send(HttpMethod.POST, SUGGEST_URL, token, Map.of("transactionId", transactionId));
    }

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

    protected static String withoutTimestamp(JsonNode error) {
        return error.toString().replaceAll("\"timestamp\":\"[^\"]*\",?", "");
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
        return "categorisation.test." + UUID.randomUUID() + "@student.campuscoin.edu";
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

    protected Long aRecordIn(String token, String defaultCategoryName, String description)
            throws Exception {
        return createTransaction(token, defaultCategoryId(defaultCategoryName), "25000",
                today(), description);
    }

    protected Long aRecordInCategory(String token, Long categoryId, String description)
            throws Exception {
        return createTransaction(token, categoryId, "25000", today(), description);
    }

    protected Long createPersonalCategory(String token, String name) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token,
                Map.of("name", name, "type", "EXPENSE"));
        assertThat(response.getStatusCode())
                .as("create category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
    }

    protected Long aRecord(String token, String description) throws Exception {
        return aRecordIn(token, FOOD, description);
    }

    protected ResponseEntity<String> moveToCategory(String token, Long transactionId,
                                                    Long categoryId) {
        return send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("categoryId", categoryId));
    }

    protected void moveToCategoryExpectingOk(String token, Long transactionId, Long categoryId) {
        ResponseEntity<String> response = moveToCategory(token, transactionId, categoryId);
        assertThat(response.getStatusCode())
                .as("move %d body=%s", transactionId, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

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

    protected Long storedCategoryIdOf(Long transactionId) throws Exception {
        return longValueFrom("SELECT category_id FROM transactions WHERE id = ?", transactionId);
    }

    protected String storedDescriptionOf(Long transactionId) throws Exception {
        return columnInDatabase(transactionId, "transactions", "description");
    }

    protected int historyCountOf(Long transactionId) throws Exception {
        return countOf("SELECT COUNT(*) FROM transaction_history WHERE transaction_id = ?",
                transactionId);
    }

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

    protected int storedRuleCountOf(Long userId, String keyword) throws Exception {
        return countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ? AND keyword = ?",
                userId, keyword);
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
