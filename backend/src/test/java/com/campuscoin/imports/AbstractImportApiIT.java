package com.campuscoin.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The fixtures UC-11's suite needs: identity, a file to import, and the database reads that can show
 * what the endpoint stored.
 *
 * <p><b>Every test registers a fresh student.</b> An import is owned by the account that uploaded it,
 * its rows are reached through that batch, and the duplicate check compares the file against the
 * student's own records - so a suite that reused one account would have each test teaching the rules
 * and leaving the records the next one then compares against. A failure would then depend on the
 * order the tests ran in, which is exactly the kind of coupling that makes a suite stop being
 * evidence. A random address per test costs a registration and buys independence.
 *
 * <p><b>The file is sent as JSON text, not as a multipart upload.</b> That is the endpoint's own
 * contract, argued in {@code UploadCsvRequest}: this build has no multipart configuration and no
 * over-size handler, and adding both for one endpoint would leave its most likely refusal answered by
 * Spring's default rather than by this API's error contract. So every fixture here builds the file's
 * text and sends it in {@code content}.
 *
 * <p><b>Nothing is inserted into the import tables directly.</b> A fixture that wrote
 * {@code import_batches} and {@code import_rows} by hand would have to invent the counters the
 * preview's own loop computes and the verdicts the duplicate detector reaches - and a test asserting
 * that an invalid row is identifiable would then be testing the fixture's insert rather than the
 * reader. Every batch under test is a batch a real caller uploaded.
 *
 * <p><b>But a record the file is compared against <em>is</em> created through the API</b>, for the
 * same reason: {@code POST /api/v1/transactions} is how a row becomes one of the student's live
 * records, with the triggers and the ownership running over it, so "an earlier record of the same
 * amount in the same category" is the state the detector actually reads.
 */
abstract class AbstractImportApiIT extends AbstractMySqlIntegrationTest {

    protected static final String IMPORTS_URL = "/api/v1/imports";

    protected static final String REGISTER_URL = "/api/v1/auth/register";
    protected static final String LOGIN_URL = "/api/v1/auth/login";
    protected static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";
    protected static final String PROFILE_URL = "/api/v1/profile/me";
    protected static final String TRANSACTIONS_URL = "/api/v1/transactions";

    protected static final String PASSWORD = "Student@123";

    /** The seeded shared categories the fixtures file their records under. */
    protected static final String FOOD = "Food";
    protected static final String TRANSPORT = "Transport";

    /** The zone the application and the database session both run in (VĐ-10). */
    protected static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * A date far enough back that no fixture depends on when the suite runs.
     *
     * <p>UC-11 refuses a future date at the commit (BR-08), and a fixture dated "today" would move
     * every day - so the dates here are fixed. They are in the past relative to the project's own
     * timeline, which is all BR-08 asks.
     */
    protected static final LocalDate EARLIER = LocalDate.of(2026, 9, 18);
    protected static final LocalDate LATER = LocalDate.of(2026, 9, 21);

    /** A date no test may use for a record that has to import: BR-08 refuses it at the commit. */
    protected static final LocalDate IN_THE_FUTURE = LocalDate.of(2099, 1, 1);

    /**
     * The complete set of properties one previewed row may carry.
     *
     * <p>A literal rather than a reflected set, so a field added to the response fails a test instead
     * of quietly widening the published contract. There is no {@code userId}: {@code import_rows} has
     * no owner column at all - the owner is always the batch's - so the shape could not carry one.
     */
    protected static final List<String> DOCUMENTED_ROW_FIELDS = List.of(
            "id", "csvRowNo", "rawData", "parsedDate", "parsedAmount", "parsedType",
            "parsedDescription", "parsedCategoryName", "resolvedCategoryId",
            "aiSuggestedCategoryId", "rowStatus", "errorMessage", "transactionId");

    /** The complete set of properties the batch response may carry. */
    protected static final List<String> DOCUMENTED_BATCH_FIELDS = List.of(
            "id", "originalFilename", "status", "modifiable", "totalRows", "validRows", "errorRows",
            "duplicateRows", "importedRows", "createdAt", "committedAt", "rows");

    /**
     * The complete set of properties a list entry may carry.
     *
     * <p>Pinned here rather than in {@code OpenApiContractIT}, which says so in as many words: this
     * shape is never a response of its own - it is the element type of {@code ImportBatchListResponse}
     * - and the contract test reads the response object rather than descending into a referenced
     * schema's own properties, so listing it there would assert something about a reference that does
     * not exist.
     */
    protected static final List<String> DOCUMENTED_SUMMARY_FIELDS = List.of(
            "id", "originalFilename", "status", "modifiable", "totalRows", "validRows", "errorRows",
            "duplicateRows", "importedRows", "createdAt", "committedAt");

    /** The properties of the list response's wrapper. */
    protected static final List<String> DOCUMENTED_LIST_FIELDS = List.of("limit", "entries");

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

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return body(response).get("errorCode").asText();
    }

    protected static List<String> fieldNamesIn(JsonNode error) {
        JsonNode fieldErrors = error.get("fieldErrors");
        if (fieldErrors == null || fieldErrors.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        fieldErrors.forEach(fieldError -> names.add(fieldError.get("field").asText()));
        return names;
    }

    protected static List<String> fieldNamesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ==================================================================
    //  The file
    // ==================================================================

    /**
     * A CSV file whose header names the three required columns.
     *
     * <p>{@code date,amount,type} are required and the other two are optional - a file without a
     * {@code category} column still imports, every row falling back to the default category for its
     * type. Every fixture here includes both, because the interesting cases are about values.
     */
    protected static String csv(String... rows) {
        return csvWithHeader("date,amount,type,description,category", rows);
    }

    protected static String csvWithHeader(String header, String... rows) {
        return header + "\n" + String.join("\n", rows) + "\n";
    }

    /** One well-formed data row. */
    protected static String rowOf(LocalDate date, String amount, String type, String description,
                                  String category) {
        return date + "," + amount + "," + type + "," + description + "," + category;
    }

    /** A file of {@code rowCount} identical well-formed rows, for the size bound. */
    protected static String csvWithNumberOfRows(int rowCount) {
        StringBuilder content = new StringBuilder("date,amount,type,description,category\n");
        for (int index = 0; index < rowCount; index++) {
            content.append(rowOf(EARLIER, "10.00", "EXPENSE", "Bulk row", FOOD)).append('\n');
        }
        return content.toString();
    }

    // ==================================================================
    //  The endpoints
    // ==================================================================

    /** Uploads a file, unasserted - most tests here are about what the upload answered or refused. */
    protected ResponseEntity<String> upload(String token, String filename, String content) {
        return send(HttpMethod.POST, IMPORTS_URL, token,
                Map.of("filename", filename, "content", content));
    }

    /** Uploads a file and asserts it became a preview, which is the setup most tests need. */
    protected JsonNode uploadExpectingCreated(String token, String content) throws Exception {
        ResponseEntity<String> response = upload(token, "records.csv", content);
        assertThat(response.getStatusCode())
                .as("upload body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response);
    }

    protected ResponseEntity<String> listWithLimit(String token, String limit) {
        return send(HttpMethod.GET, IMPORTS_URL + "?limit=" + limit, token, null);
    }

    protected JsonNode list(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, IMPORTS_URL, token, null);
        assertThat(response.getStatusCode())
                .as("list body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> getBatch(String token, Long batchId) {
        return send(HttpMethod.GET, IMPORTS_URL + "/" + batchId, token, null);
    }

    protected JsonNode getBatchExpectingOk(String token, Long batchId) throws Exception {
        ResponseEntity<String> response = getBatch(token, batchId);
        assertThat(response.getStatusCode())
                .as("get body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> fileRow(String token, Long batchId, Long rowId, Long categoryId) {
        return send(HttpMethod.PATCH, IMPORTS_URL + "/" + batchId + "/rows/" + rowId, token,
                Map.of("categoryId", categoryId));
    }

    protected JsonNode fileRowExpectingOk(String token, Long batchId, Long rowId, Long categoryId)
            throws Exception {
        ResponseEntity<String> response = fileRow(token, batchId, rowId, categoryId);
        assertThat(response.getStatusCode())
                .as("file row body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> commit(String token, Long batchId) {
        return send(HttpMethod.POST, IMPORTS_URL + "/" + batchId + "/commit", token, null);
    }

    protected JsonNode commitExpectingOk(String token, Long batchId) throws Exception {
        ResponseEntity<String> response = commit(token, batchId);
        assertThat(response.getStatusCode())
                .as("commit body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    protected ResponseEntity<String> cancel(String token, Long batchId) {
        return send(HttpMethod.POST, IMPORTS_URL + "/" + batchId + "/cancel", token, null);
    }

    protected JsonNode cancelExpectingOk(String token, Long batchId) throws Exception {
        ResponseEntity<String> response = cancel(token, batchId);
        assertThat(response.getStatusCode())
                .as("cancel body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response);
    }

    // ==================================================================
    //  Reading a preview
    // ==================================================================

    protected static long batchIdOf(JsonNode batch) {
        return batch.get("id").asLong();
    }

    /** One entry of a preview, found by the file line it came from. */
    protected static JsonNode rowAt(JsonNode batch, int csvRowNo) {
        for (JsonNode row : batch.get("rows")) {
            if (row.get("csvRowNo").asInt() == csvRowNo) {
                return row;
            }
        }
        throw new AssertionError("no row " + csvRowNo + " in " + batch);
    }

    protected static long rowIdAt(JsonNode batch, int csvRowNo) {
        return rowAt(batch, csvRowNo).get("id").asLong();
    }

    protected static String rowStatusAt(JsonNode batch, int csvRowNo) {
        return rowAt(batch, csvRowNo).get("rowStatus").asText();
    }

    protected static String errorMessageAt(JsonNode batch, int csvRowNo) {
        JsonNode message = rowAt(batch, csvRowNo).get("errorMessage");
        return message == null || message.isNull() ? null : message.asText();
    }

    /** The transaction ids of every imported row, in file order. */
    protected static List<Long> importedTransactionIds(JsonNode batch) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : batch.get("rows")) {
            if ("IMPORTED".equals(row.get("rowStatus").asText())) {
                ids.add(row.get("transactionId").asLong());
            }
        }
        return ids;
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
        return "imports.test." + UUID.randomUUID() + "@student.campuscoin.edu";
    }

    // ==================================================================
    //  Records to compare against
    // ==================================================================

    protected Long defaultCategoryId(String name) throws Exception {
        return longValueFrom("SELECT id FROM categories WHERE user_id IS NULL AND name = ?", name);
    }

    /** Creates one transaction through the API, which is how the triggers and the queries see it. */
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

    /** The student's existing record that a file's row may turn out to duplicate. */
    protected Long anExistingRecord(String token, String categoryName, String amount, LocalDate date,
                                    String description) throws Exception {
        return createTransaction(token, defaultCategoryId(categoryName), amount, date, description);
    }

    /** How many transactions the student has on the books, live ones only. */
    protected int liveTransactionCountOf(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM transactions WHERE user_id = ? AND is_deleted = 0",
                userId);
    }

    // ==================================================================
    //  Database assertions
    // ==================================================================

    protected String storedBatchStatusOf(Long batchId) throws Exception {
        return columnInDatabase(batchId, "import_batches", "status");
    }

    protected String storedRowStatusOf(Long batchId, int csvRowNo) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT row_status FROM import_rows WHERE batch_id = ? AND csv_row_no = ?")) {
            statement.setLong(1, batchId);
            statement.setInt(2, csvRowNo);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("row %d of batch %d exists", csvRowNo, batchId).isTrue();
                return row.getString("row_status");
            }
        }
    }

    /** How many rows a batch has, whatever state they are in - a cancel keeps them. */
    protected int storedRowCountOf(Long batchId) throws Exception {
        return countOf("SELECT COUNT(*) FROM import_rows WHERE batch_id = ?", batchId);
    }

    /** How many batches the student has, which is what the row cap is measured against. */
    protected int storedBatchCountOf(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM import_batches WHERE user_id = ?", userId);
    }

    /** The student's stored mapping for a keyword, or null when they have none (UC-08 B6). */
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

    /** The source the rule learner recorded, which is {@code IMPORT} for a mapping a commit taught. */
    protected String storedRuleSourceOf(Long userId, String keyword) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT source FROM category_rules WHERE user_id = ? AND keyword = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, keyword);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString("source") : null;
            }
        }
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
