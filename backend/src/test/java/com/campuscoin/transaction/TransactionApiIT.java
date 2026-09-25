package com.campuscoin.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * UC-07 and UC-10 over the real HTTP stack: request, security filter, controller, service,
 * repository, MySQL.
 *
 * <p>Every test registers its own student with a random address, so no test depends on another's
 * data. The seeded default categories are read but never modified.
 *
 * <p><b>What this suite exists to prove, and what it deliberately leaves to other tests.</b> The
 * parts a service-level unit test cannot reach are here: that another student's transaction is
 * unreachable through every one of the six endpoints, that the soft-delete lifecycle is the stored
 * procedures' rather than the application's, that BR-09's history is written by the triggers and
 * says what it is supposed to say, that recording an expense raises the budget alert UC-14 asks for,
 * and that two simultaneous deletes produce one deletion rather than two successes.
 *
 * <p><b>The trigger's own refusals are proved by writing to the database directly, not through the
 * API.</b> {@code sp_validate_transaction} refuses six things with one undifferentiated
 * {@code SQLSTATE '45000'}, and every one of them is pre-checked by the service - so a client cannot
 * normally reach that refusal at all, and a test that tried to would have to race a concurrent
 * request to do it. Writing the offending row by hand is what shows the rule holds for <em>every</em>
 * caller rather than only for callers that went through the service. The translation of those
 * refusals into a {@code 409} is verified against the exact exception shapes in
 * {@code TransactionWriteFailureTest}, which is where the classification lives.
 */
class TransactionApiIT extends AbstractMySqlIntegrationTest {

    private static final String TRANSACTIONS_URL = "/api/v1/transactions";
    private static final String CATEGORIES_URL = "/api/v1/categories";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String PROFILE_URL = "/api/v1/profile/me";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    private static final String PASSWORD = "Student@123";

    /** Seeded as a shared default EXPENSE category, so every student may file under it. */
    private static final String DEFAULT_EXPENSE_NAME = "Food";

    /** Seeded as a shared default INCOME category. */
    private static final String DEFAULT_INCOME_NAME = "Allowance";

    /**
     * The complete set of properties {@code TransactionResponse} may send.
     *
     * <p>Listed as a literal rather than derived from the record, so adding a field to the DTO fails
     * this test instead of quietly widening the published contract. {@code createdAt} and
     * {@code updatedAt} are deliberately absent, matching the category module.
     */
    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
            "amount", "txnDate", "description", "source", "isDeleted", "deletedAt");

    /** The zone the application and the database session both run in (VĐ-10). */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    //  UC-10 read: the list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-10: a student with no records gets an empty list, not an error and not null")
    void emptyStateIsAnEmptyArray() throws Exception {
        // The empty state is a real state the dashboard has to render, and an endpoint that
        // answered 404 or null for it would make the client special-case a student who simply has
        // not started yet.
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, TRANSACTIONS_URL, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("UC-10: records come back newest first, and a shared date is ordered by id descending")
    void recordsAreOrderedNewestFirstWithATotalOrder() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long older = createAndReturnId(token, categoryId, "10.00", today.minusDays(5), "older");
        // Two records on the same day: only the id tie-break makes the order total, and without it
        // two identical calls could return them either way round.
        Long sameDayFirst = createAndReturnId(token, categoryId, "20.00", today.minusDays(1), "a");
        Long sameDaySecond = createAndReturnId(token, categoryId, "30.00", today.minusDays(1), "b");
        Long newest = createAndReturnId(token, categoryId, "40.00", today, "newest");

        JsonNode list = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL, token, null).getBody());

        List<Long> ids = new ArrayList<>();
        list.forEach(entry -> ids.add(entry.get("id").asLong()));

        assertThat(ids).containsExactly(newest, sameDaySecond, sameDayFirst, older);
    }

    @Test
    @DisplayName("UC-10: the date range is inclusive at both ends and either bound may be omitted")
    void dateRangeIsInclusiveAndEitherBoundMayBeOmitted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long farPast = createAndReturnId(token, categoryId, "1.00", today.minusDays(10), "far");
        Long onFromBound = createAndReturnId(token, categoryId, "2.00", today.minusDays(3), "from");
        Long inside = createAndReturnId(token, categoryId, "3.00", today.minusDays(2), "inside");
        Long onToBound = createAndReturnId(token, categoryId, "4.00", today.minusDays(1), "to");

        // Both bounds given: the record dated exactly on each boundary is included, and the one
        // outside is not. An exclusive bound here would silently drop a record the student can see
        // on the day they filtered for.
        JsonNode both = list(token, today.minusDays(3), today.minusDays(1), null);
        assertThat(ids(both)).containsExactly(onToBound, inside, onFromBound);

        // Only an upper bound: "everything up to here".
        JsonNode upperOnly = list(token, null, today.minusDays(2), null);
        assertThat(ids(upperOnly)).containsExactly(inside, onFromBound, farPast);

        // Only a lower bound: the upper end defaults to today. For every record this API can create
        // that loses nothing, because BR-08 refuses a future date here - but see
        // futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven, which pins the limit that default
        // implies for module 5's rows.
        JsonNode lowerOnly = list(token, today.minusDays(3), null, null);
        assertThat(ids(lowerOnly)).containsExactly(onToBound, inside, onFromBound);
    }

    @Test
    @DisplayName("UC-10: the default upper bound of today hides a future-dated recurring row")
    void futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven() throws Exception {
        // BR-08 exempts RECURRING, and module 5's scheduler writes those rows ahead of their date. So
        // the claim "the default upper bound cannot hide anything" is false in general, and this pins
        // what actually happens: the row is absent from an unbounded list and present once the client
        // gives a `to`. Recording the limit as a test is what stops a later module inheriting the
        // omission without noticing it.
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate future = today().plusDays(5);

        // Written the way the scheduler will write it - source RECURRING, which is what lets the
        // trigger accept a date ahead of today.
        insertTransaction(userId, categoryId, "RECURRING", new BigDecimal("42.00"), future,
                "Next month's membership");

        assertThat(list(token, null, null, null))
                .as("an unbounded list stops at today, so a future occurrence is not in it")
                .isEmpty();

        JsonNode bounded = list(token, null, future, null);
        assertThat(bounded).hasSize(1);
        assertThat(bounded.get(0).get("txnDate").asText()).isEqualTo(future.toString());
        assertThat(bounded.get(0).get("source").asText()).isEqualTo("RECURRING");
    }

    @Test
    @DisplayName("UC-10: an inverted range is a 400 naming `from`, not an empty list")
    void invertedRangeIsAFieldError() throws Exception {
        // A client that wrote the two bounds the wrong way round has a bug. Answering "no records"
        // would let that bug look like an empty month and hide it.
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET,
                TRANSACTIONS_URL + "?from=2026-09-20&to=2026-09-01", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesOf(error)).contains("from");
    }

    @Test
    @DisplayName("Section 7.7: a malformed date parameter is a 400 with no fieldErrors and no internals")
    void malformedDateParameterIsRejected() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET,
                TRANSACTIONS_URL + "?from=not-a-date", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        // A query parameter is not a body field, so there is nothing to name: the contract module 1
        // established answers a mistyped parameter as INVALID_REQUEST without a fieldErrors array.
        assertThat(error.get("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(error.has("fieldErrors")).isFalse();
        assertThat(response.getBody()).doesNotContain("java.", "springframework", "SQLException");
    }

    @Test
    @DisplayName("UC-10: one transaction can be read back by id, with the full published field set")
    void ownTransactionCanBeReadById() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Coffee", "EXPENSE", "coffee", "#f59e0b");
        Long transactionId = createAndReturnId(token, categoryId, "12.50", today(), "Latte");

        ResponseEntity<String> response =
                send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("id").asLong()).isEqualTo(transactionId);
        assertThat(body.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(body.get("categoryName").asText()).isEqualTo("Coffee");
        assertThat(body.get("categoryIcon").asText()).isEqualTo("coffee");
        assertThat(body.get("categoryColor").asText()).isEqualTo("#F59E0B");
        assertThat(body.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(body.get("amount").asDouble()).isEqualTo(12.50);
        assertThat(body.get("txnDate").asText()).isEqualTo(today().toString());
        assertThat(body.get("description").asText()).isEqualTo("Latte");
        assertThat(body.get("source").asText()).isEqualTo("MANUAL");
        assertThat(body.get("isDeleted").asBoolean()).isFalse();

        // The agreed contract, checked as a closed set in the direction that matters: every field
        // present is one the DTO declares. The owner, the row timestamps and every AI or anomaly
        // column are absent, not merely null.
        assertThat(body.fieldNames()).toIterable().isSubsetOf(DOCUMENTED_FIELDS);
        assertThat(body.fieldNames()).toIterable()
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt",
                        "aiSuggestedCategoryId", "aiConfidence", "aiOverridden",
                        "isFlagged", "flagType", "flagNote",
                        "recurringRuleId", "importBatchId");
    }

    @Test
    @DisplayName("UC-10: a nullable field with no value is omitted, and an absent one is not null")
    void absentNullableFieldsAreOmitted() throws Exception {
        // The Angular model marks description optional and deletedAt optional, which is only
        // truthful if the API omits them rather than filling them with null.
        String token = loginNewStudent();
        // A category of the student's own with no icon and no colour: the seeded default categories
        // all carry both, so this is the only way to exercise an absent optional value on the
        // category side of the response.
        Long categoryId = createCategory(token, "Plain", "EXPENSE", null, null);
        Long transactionId = createAndReturnId(token, categoryId, "5.00", today(), null);

        JsonNode live = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null).getBody());

        // description and deletedAt have no value, so their keys are absent rather than null. The
        // category's icon and colour are in the same state on this record, for the same reason.
        assertThat(live.fieldNames()).toIterable()
                .doesNotContain("description", "deletedAt", "categoryIcon", "categoryColor");
        // isDeleted is not nullable, so it is always present - false here.
        assertThat(live.has("isDeleted")).isTrue();
        assertThat(live.get("isDeleted").isNull()).isFalse();

        // An explicitly cleared description is absent too, so the client has one rule to apply
        // rather than two: "no value" always means the key is missing.
        send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("description", "  temporary  "));
        JsonNode populated = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null).getBody());
        assertThat(populated.get("description").asText()).isEqualTo("temporary");

        send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("description", ""));
        JsonNode cleared = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null).getBody());
        assertThat(cleared.has("description")).isFalse();
    }

    // ------------------------------------------------------------------
    //  UC-10: the trash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-10: a deleted record leaves the list, and includeDeleted brings it back")
    void deletedRecordsAreHiddenUnlessAskedFor() throws Exception {
        // UC-10 A1 needs the record to be findable again in order to restore it, so the list has a
        // switch rather than the record simply vanishing.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long kept = createAndReturnId(token, categoryId, "10.00", today(), "kept");
        Long trashed = createAndReturnId(token, categoryId, "11.00", today(), "trashed");

        delete(token, trashed);

        assertThat(ids(list(token, null, null, null))).containsExactly(kept);
        assertThat(ids(list(token, null, null, true))).containsExactlyInAnyOrder(kept, trashed);
    }

    @Test
    @DisplayName("UC-10: a deleted record cannot be read by id, and its row survives with a stamp")
    void deletedRecordIsNotFoundById() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), null);

        delete(token, transactionId);

        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // "Not found" here means "not in your records", not "gone": BR-09 keeps the row and stamps
        // it, which is what makes it restorable and what makes the deletion auditable.
        assertThat(transactionExists(transactionId)).isTrue();
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("1");
        assertThat(columnInDatabase(transactionId, "deleted_at")).isNotNull();

        // Reading it back through the list, with the trash included, shows the whole published
        // shape of a deleted record - isDeleted true and deletedAt present.
        JsonNode trashed = list(token, null, null, true);
        assertThat(ids(trashed)).containsExactly(transactionId);
        JsonNode row = trashed.get(0);
        assertThat(row.get("isDeleted").asBoolean()).isTrue();
        assertThat(row.get("deletedAt").asText()).isNotBlank();
        assertThat(row.fieldNames()).toIterable().isSubsetOf(DOCUMENTED_FIELDS);
    }

    @Test
    @DisplayName("UC-10: an unknown identifier is a 404 on every one of the four id-addressed endpoints")
    void unknownIdentifierIsNotFound() throws Exception {
        String token = loginNewStudent();

        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/99999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/99999999", token,
                Map.of("amount", "1.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/99999999", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/99999999/restore", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-10: an identifier that is not a number, or that overflows the column, is a 400")
    void malformedIdentifierIsBadRequest() throws Exception {
        String token = loginNewStudent();

        for (String id : List.of("not-a-number", "1.5", "99999999999999999999999")) {
            ResponseEntity<String> response =
                    send(HttpMethod.GET, TRANSACTIONS_URL + "/" + id, token, null);

            assertThat(response.getStatusCode()).as("id=%s", id).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                    .isEqualTo("INVALID_REQUEST");
        }
    }

    // ------------------------------------------------------------------
    //  UC-07 create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-07: a recorded expense carries the category's type, MANUAL provenance and no flags")
    void creatingAnExpenseDerivesEverythingFromTheCategory() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        ResponseEntity<String> response =
                createTransaction(token, categoryId, "24.00", today(), "Lunch");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        Long transactionId = body.get("id").asLong();

        // BR-05: there is no type column, so this is the category's type being read back.
        assertThat(body.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(body.get("source").asText()).isEqualTo("MANUAL");
        assertThat(body.get("isDeleted").asBoolean()).isFalse();

        // The response is what was written; the row is the authority.
        assertThat(columnInDatabase(transactionId, "user_id")).isEqualTo(String.valueOf(userId));
        assertThat(columnInDatabase(transactionId, "source")).isEqualTo("MANUAL");
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "flag_type")).isEqualTo("NONE");
        assertThat(columnInDatabase(transactionId, "is_flagged")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "ai_overridden")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "deleted_at")).isNull();
    }

    @Test
    @DisplayName("UC-07: the same body filed under an income category is income")
    void theCategoryAloneDecidesIncomeOrExpense() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_INCOME_NAME);

        JsonNode body = objectMapper.readTree(
                createTransaction(token, categoryId, "500.00", today(), "Allowance").getBody());

        assertThat(body.get("type").asText()).isEqualTo("INCOME");
        assertThat(body.get("categoryName").asText()).isEqualTo(DEFAULT_INCOME_NAME);
    }

    @Test
    @DisplayName("UC-07: the amount-only body uses the column defaults, and the response is unchanged")
    void minimalBodyUsesTheColumnDefaults() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        JsonNode body = objectMapper.readTree(send(HttpMethod.POST, TRANSACTIONS_URL, token,
                Map.of("categoryId", categoryId,
                        "amount", "1.00",
                        "txnDate", today.toString())).getBody());

        Long transactionId = body.get("id").asLong();
        // description VARCHAR(255) NULL with no default: the factory writes an explicit null, and
        // the response omits the field rather than serialising null.
        assertThat(columnInDatabase(transactionId, "description")).isNull();
        assertThat(body.has("description")).isFalse();
        // Nothing the later modules own is touched by a row created here.
        assertThat(columnInDatabase(transactionId, "recurring_rule_id")).isNull();
        assertThat(columnInDatabase(transactionId, "import_batch_id")).isNull();
        assertThat(columnInDatabase(transactionId, "ai_suggested_category_id")).isNull();
    }

    @Test
    @DisplayName("UC-07: the description is trimmed, and one that is blank stores null")
    void descriptionIsTrimmedAndBlankBecomesNull() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long padded = createAndReturnId(token, categoryId, "1.00", today, "  padded note  ");
        assertThat(columnInDatabase(padded, "description")).isEqualTo("padded note");

        Long blank = createAndReturnId(token, categoryId, "2.00", today, "   ");
        assertThat(columnInDatabase(blank, "description")).isNull();
        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/" + blank, token, null).getBody())
                .doesNotContain("description");
    }

    @Test
    @DisplayName("UC-07: a multi-line note is accepted and its length is still bounded")
    void multiLineDescriptionIsAccepted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long accepted = createAndReturnId(token, categoryId, "1.00", today, "line one\nline two");
        assertThat(columnInDatabase(accepted, "description")).isEqualTo("line one\nline two");

        // 256 characters cannot fit VARCHAR(255), and the check is applied after trimming because
        // the service trims before storing.
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId,
                        "amount", "1.00",
                        "txnDate", today.toString(),
                        "description", "x".repeat(256)),
                "description");
    }

    @Test
    @DisplayName("BR-08: the amount must be strictly positive and at most two decimal places")
    void amountBoundariesMatchTheColumn() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        // The smallest value the DECIMAL(15,2) column and ck_txn_amount accept.
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, token, Map.of(
                "categoryId", categoryId, "amount", "0.01", "txnDate", today.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // ck_txn_amount requires > 0. Checked here so the caller gets a field error instead of a
        // generic conflict.
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "0.00", "txnDate", today.toString()),
                "amount");
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "-5.00", "txnDate", today.toString()),
                "amount");

        // Three decimal places would be silently rounded by MySQL, so the value the student typed
        // and the value stored would differ. Refused instead.
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "1.005", "txnDate", today.toString()),
                "amount");

        // DECIMAL(15,2): thirteen digits before the point. A larger value is a field error rather
        // than a driver error on the way to the database.
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "10000000000000.00",
                        "txnDate", today.toString()),
                "amount");
    }

    @Test
    @DisplayName("BR-08: today is accepted and tomorrow is a field error")
    void theDateBoundaryIsTodayInTheApplicationZone() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        // The boundary itself is inclusive: BR-08 forbids the future, not the present.
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, token, Map.of(
                "categoryId", categoryId, "amount", "1.00", "txnDate", today.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "1.00",
                        "txnDate", today.plusDays(1).toString()),
                "txnDate");
    }

    @Test
    @DisplayName("BR-08: the database refuses a future date even when the service is bypassed")
    void futureDateIsRefusedByTheDatabaseToo() throws Exception {
        // The service's check exists so the caller gets a field error. This shows the rule is not
        // the service's to relax: a row written by any other caller - a script, a later module, a
        // hand-run statement - is refused by trg_transactions_before_insert just the same.
        Long userId = registerAndReturnId();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertThatThrownBy(() -> insertTransaction(userId, categoryId, "MANUAL",
                new BigDecimal("10.00"), today().plusDays(1), "future"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("BR-08");
    }

    @Test
    @DisplayName("BR-02: the database refuses a category belonging to another student")
    void anotherStudentsCategoryIsRefusedByTheDatabaseToo() throws Exception {
        String ownerToken = loginNewStudent();
        Long ownerCategoryId = createCategory(ownerToken, "Owner Only", "EXPENSE", null, null);
        Long otherUserId = registerAndReturnId();

        // fk_txn_category proves the row exists, not who owns it - so the check has to be explicit,
        // and sp_validate_transaction is where it lives.
        assertThatThrownBy(() -> insertTransaction(otherUserId, ownerCategoryId, "MANUAL",
                new BigDecimal("10.00"), today(), "not mine"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("BR-02");
    }

    @Test
    @DisplayName("UC-07: a category that is not the caller's, or does not exist, is a 404")
    void unusableCategoryIsNotFound() throws Exception {
        // 404 rather than 403 in both cases, so the endpoint cannot be used to discover which
        // category identifiers exist.
        String ownerToken = loginNewStudent();
        String otherToken = loginNewStudent();
        Long ownerCategoryId = createCategory(ownerToken, "Private", "EXPENSE", null, null);

        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, otherToken, Map.of(
                "categoryId", ownerCategoryId, "amount", "1.00", "txnDate", today().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, otherToken, Map.of(
                "categoryId", 99999999L, "amount", "1.00", "txnDate", today().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("BR-07: a retired category cannot be filed under, and the field is named")
    void retiredCategoryCannotBeUsedForANewRecord() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Retired", "EXPENSE", null, null);
        retire(token, categoryId, true);

        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "1.00", "txnDate", today().toString()),
                "categoryId");
    }

    @Test
    @DisplayName("UC-07: categoryId, amount and txnDate are all required")
    void missingRequiredFieldsAreReported() throws Exception {
        String token = loginNewStudent();

        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL, Map.of(), "categoryId");
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", defaultCategoryId(DEFAULT_EXPENSE_NAME)), "amount");

        // txnDate is NOT NULL with no default, so a body that omits it must be refused rather than
        // defaulted to today - the date is a fact about when the money moved, not a server choice.
        JsonNode error = objectMapper.readTree(send(HttpMethod.POST, TRANSACTIONS_URL, token,
                Map.of("categoryId", defaultCategoryId(DEFAULT_EXPENSE_NAME), "amount", "1.00"))
                .getBody());
        assertThat(fieldNamesOf(error)).contains("txnDate");

        // Sending null is the same as omitting it.
        Map<String, Object> nullDate = new LinkedHashMap<>();
        nullDate.put("categoryId", defaultCategoryId(DEFAULT_EXPENSE_NAME));
        nullDate.put("amount", "1.00");
        nullDate.put("txnDate", null);
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL, nullDate, "txnDate");
    }

    // ------------------------------------------------------------------
    //  Section 7.5: fields a client must not be able to set
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Section 7.5: a create body cannot set the owner, the provenance, the state or the flags")
    void createBodyCannotSetServerOwnedFields() throws Exception {
        // Every field here is one the schema owns or a later module owns. They are absent from
        // CreateTransactionRequest, so Jackson discards them; this test is what keeps that true if
        // someone later adds one "for convenience".
        String token = loginNewStudent();
        Long victimId = registerAndReturnId();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long otherCategoryId = defaultCategoryId(DEFAULT_INCOME_NAME);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("amount", "10.00");
        body.put("txnDate", today().toString());
        body.put("userId", victimId);
        body.put("source", "RECURRING");
        body.put("isDeleted", true);
        body.put("deletedAt", "2020-01-01T00:00:00");
        body.put("type", "INCOME");
        body.put("aiSuggestedCategoryId", otherCategoryId);
        body.put("aiConfidence", "0.9900");
        body.put("aiOverridden", true);
        body.put("isFlagged", true);
        body.put("flagType", "DUPLICATE");
        body.put("flagNote", "written by the client");
        body.put("recurringRuleId", 1L);
        body.put("importBatchId", 1L);

        ResponseEntity<String> response = send(HttpMethod.POST, TRANSACTIONS_URL, token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long transactionId = objectMapper.readTree(response.getBody()).get("id").asLong();

        // The owner is the account in the token, not the id the client sent.
        assertThat(columnInDatabase(transactionId, "user_id"))
                .isNotEqualTo(String.valueOf(victimId));
        assertThat(columnInDatabase(transactionId, "user_id"))
                .isEqualTo(String.valueOf(userIdOfNewStudent(token)));
        // Provenance is MANUAL, not the RECURRING the client claimed.
        assertThat(columnInDatabase(transactionId, "source")).isEqualTo("MANUAL");
        // The row starts live, not deleted.
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "deleted_at")).isNull();
        // The type is the category's, not the client's.
        assertThat(columnInDatabase(transactionId, "category_id"))
                .isEqualTo(String.valueOf(categoryId));
        // No AI column and no flag was written.
        assertThat(columnInDatabase(transactionId, "ai_suggested_category_id")).isNull();
        assertThat(columnInDatabase(transactionId, "ai_confidence")).isNull();
        assertThat(columnInDatabase(transactionId, "ai_overridden")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "is_flagged")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "flag_type")).isEqualTo("NONE");
        assertThat(columnInDatabase(transactionId, "flag_note")).isNull();
        assertThat(columnInDatabase(transactionId, "recurring_rule_id")).isNull();
        assertThat(columnInDatabase(transactionId, "import_batch_id")).isNull();
    }

    @Test
    @DisplayName("Section 7.5: claiming the RECURRING source does not make a future date acceptable")
    void claimingRecurringDoesNotUnlockFutureDates() throws Exception {
        // This is why `source` is not accepted at all. sp_validate_transaction exempts RECURRING
        // from the BR-08 date check, so a client able to claim it could record a transaction dated
        // in the future - and a future-dated expense would corrupt every balance until the date
        // arrived. The field is simply not read, so the claim changes nothing.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("amount", "10.00");
        body.put("txnDate", today().plusDays(30).toString());
        body.put("source", "RECURRING");

        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL, body, "txnDate");
    }

    @Test
    @DisplayName("Section 7.5: a PATCH cannot set the owner, un-delete, or mark a record as reviewed")
    void updateBodyCannotSetServerOwnedFields() throws Exception {
        String token = loginNewStudent();
        Long victimId = registerAndReturnId();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "original");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", victimId);
        body.put("source", "CSV");
        body.put("isDeleted", true);
        body.put("deletedAt", "2020-01-01T00:00:00");
        body.put("isFlagged", true);
        body.put("flagType", "UNUSUAL_AMOUNT");
        body.put("aiOverridden", true);

        ResponseEntity<String> response =
                send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token, body);

        // The request is accepted - the fields are simply not part of the contract - and nothing
        // changes. Refusing it would be defensible too, but accepting and ignoring matches the
        // "a field that is not in the contract changes nothing" rule the module documents.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(transactionId, "user_id"))
                .isEqualTo(String.valueOf(userIdOfNewStudent(token)));
        assertThat(columnInDatabase(transactionId, "source")).isEqualTo("MANUAL");
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "is_flagged")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "ai_overridden")).isEqualTo("0");

        // And a PATCH cannot reach a deleted record at all, so there is no way to un-delete through
        // this endpoint - that is the restore endpoint, which writes the RESTORE row BR-09 wants.
        delete(token, transactionId);
        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("amount", "1.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    //  UC-10 update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-10: a partial update changes the fields it names and leaves the rest alone")
    void partialUpdateLeavesTheOtherFieldsAlone() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "original");

        ResponseEntity<String> response = send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token, Map.of("amount", "99.99"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("amount").asDouble()).isEqualTo(99.99);
        assertThat(body.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(body.get("description").asText()).isEqualTo("original");
        assertThat(body.get("txnDate").asText()).isEqualTo(today().toString());
        assertThat(body.get("source").asText()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("UC-10: an empty update body changes nothing and is not an error")
    void emptyUpdateBodyChangesNothing() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "keep me");
        String before = rowSnapshot(transactionId);

        ResponseEntity<String> response = send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowSnapshot(transactionId)).isEqualTo(before);
        // A request that changed nothing must not add noise to the log BR-09 keeps: UAT-06 counts
        // history rows, so a no-op UPDATE that still wrote one would fail it.
        assertThat(historyRowsFor(transactionId)).hasSize(1);
    }

    @Test
    @DisplayName("UC-10: an empty string clears the description; leaving it out keeps it")
    void emptyStringClearsTheDescription() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "clear me");

        JsonNode cleared = objectMapper.readTree(send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token, Map.of("description", ""))
                .getBody());
        assertThat(columnInDatabase(transactionId, "description")).isNull();
        assertThat(cleared.has("description")).isFalse();

        // And an omitted field is "not changed", not "cleared".
        JsonNode untouched = objectMapper.readTree(send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token, Map.of("amount", "1.00")).getBody());
        assertThat(untouched.has("description")).isFalse();
        assertThat(columnInDatabase(transactionId, "description")).isNull();
    }

    @Test
    @DisplayName("UC-10/BR-05: moving a record to another category changes whether it is income or expense")
    void movingToAnotherCategoryChangesTheType() throws Exception {
        // This is the legitimate correction of a misfiled record. It is a different operation from
        // changing a category's own type, which trg_categories_before_update refuses once anything
        // references it.
        String token = loginNewStudent();
        Long expenseCategoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long incomeCategoryId = defaultCategoryId(DEFAULT_INCOME_NAME);
        Long transactionId = createAndReturnId(token, expenseCategoryId, "10.00", today(), "misfiled");

        JsonNode moved = objectMapper.readTree(send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("categoryId", incomeCategoryId)).getBody());

        assertThat(moved.get("categoryId").asLong()).isEqualTo(incomeCategoryId);
        assertThat(moved.get("type").asText()).isEqualTo("INCOME");
        assertThat(moved.get("categoryName").asText()).isEqualTo(DEFAULT_INCOME_NAME);

        // The move is a change, so it is in the log - with both the old and the new category.
        List<String> history = historyRowsFor(transactionId);
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).startsWith("UPDATE").contains("categoryId");
    }

    @Test
    @DisplayName("BR-07: a record already filed under a retired category stays editable")
    void recordUnderARetiredCategoryStaysEditable() throws Exception {
        // The insert trigger enforces the active rule with require_active = 1; the update trigger
        // deliberately skips it, because it cannot tell an unchanged category from a newly chosen
        // one. The service can tell, so it applies the rule exactly when the student is making a
        // new filing decision. Without this asymmetry, retiring a category would freeze every
        // record inside it - including the correction of a typo.
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gone Quiet", "EXPENSE", null, null);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "typo heer");
        retire(token, categoryId, true);

        // Editing without touching the category is allowed.
        ResponseEntity<String> amountOnly = send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token, Map.of("amount", "11.00"));
        assertThat(amountOnly.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amountOnly.getBody()).contains("Gone Quiet");

        // Sending the same category back is not a move, so it is not a new filing decision either.
        ResponseEntity<String> sameCategory = send(HttpMethod.PATCH,
                TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("categoryId", categoryId, "description", "typo here"));
        assertThat(sameCategory.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Moving to a *different* retired category is a filing decision, and is refused.
        Long otherRetired = createCategory(token, "Also Retired", "EXPENSE", null, null);
        retire(token, otherRetired, true);
        assertFieldError(token, HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId,
                Map.of("categoryId", otherRetired), "categoryId");

        // A new record under the retired category is refused, which is the rule the asymmetry keeps
        // intact.
        assertFieldError(token, HttpMethod.POST, TRANSACTIONS_URL,
                Map.of("categoryId", categoryId, "amount", "1.00", "txnDate", today().toString()),
                "categoryId");
    }

    @Test
    @DisplayName("BR-08: an update cannot move a record into the future")
    void futureDateIsRefusedOnUpdate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), null);

        assertFieldError(token, HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId,
                Map.of("txnDate", today().plusDays(1).toString()), "txnDate");

        // The row is untouched by the refused edit.
        assertThat(columnInDatabase(transactionId, "txn_date")).isEqualTo(today().toString());
    }

    @Test
    @DisplayName("UC-10: repeating an identical update is idempotent and logs nothing the second time")
    void repeatedIdenticalUpdatesAreIdempotent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), null);

        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("amount", "42.00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyRowsFor(transactionId)).hasSize(2);

        // The second identical request changes no column, so @DynamicUpdate issues no statement and
        // the history trigger has nothing to record.
        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("amount", "42.00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyRowsFor(transactionId)).hasSize(2);
    }

    // ------------------------------------------------------------------
    //  UC-10 / BR-09: delete and restore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-09: deleting is soft - the row survives, is stamped, and leaves every figure")
    void deleteIsSoftAndKeepsTheRecord() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "gone soon");

        assertThat(expenseTotal(token)).isEqualTo(new BigDecimal("10.00"));

        ResponseEntity<String> response = send(HttpMethod.DELETE,
                TRANSACTIONS_URL + "/" + transactionId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        // The row is still there, which is what makes the record recoverable - and what BR-09 means
        // by "preserve the history" rather than "remove the record".
        assertThat(transactionExists(transactionId)).isTrue();
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("1");
        assertThat(columnInDatabase(transactionId, "deleted_at")).isNotNull();
        assertThat(columnInDatabase(transactionId, "amount")).isEqualTo("10.00");

        // It is out of the list and out of the reporting views, which are what every later module
        // reads its figures from.
        assertThat(ids(list(token, null, null, null))).isEmpty();
        assertThat(expenseTotal(token)).isEqualByComparingTo(BigDecimal.ZERO);

        // And the log reads CREATE then DELETE.
        assertThat(historyRowsFor(transactionId)).hasSize(2);
        assertThat(historyRowsFor(transactionId).get(1)).startsWith("DELETE");
    }

    @Test
    @DisplayName("BR-09: a hard delete is refused by the database, for every caller")
    void hardDeleteIsRefusedByTheDatabase() throws Exception {
        // There is no endpoint that hard-deletes, and this is why there cannot be one: the trigger
        // refuses the statement itself, so the rule does not depend on the API being careful.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), null);

        assertThatThrownBy(() -> {
            try (Connection connection = openDatabaseConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM transactions WHERE id = ?")) {
                statement.setLong(1, transactionId);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("BR-09");

        assertThat(transactionExists(transactionId)).isTrue();
    }

    @Test
    @DisplayName("UC-10: deleting twice is a 409 saying already deleted, not a second 204")
    void deletingTwiceIsAConflict() throws Exception {
        // Reporting success for a request that did nothing would tell the client something happened
        // that did not, and "not found" would be false - the row is right there.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), null);

        delete(token, transactionId);
        ResponseEntity<String> second =
                send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(second.getBody()).get("errorCode").asText())
                .isEqualTo("TRANSACTION_ALREADY_DELETED");
        // No second DELETE row: the procedure never ran, because the state check refused first.
        assertThat(historyRowsFor(transactionId)).hasSize(2);
    }

    @Test
    @DisplayName("UC-10 A1: restore brings the record back and appends a RESTORE row")
    void restoreBringsTheRecordBack() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "10.00", today(), "back soon");
        delete(token, transactionId);

        ResponseEntity<String> response = send(HttpMethod.POST,
                TRANSACTIONS_URL + "/" + transactionId + "/restore", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        // The response describes the row as it now is. sp_restore_transaction changes is_deleted by
        // SQL that Hibernate never sees, so without the refresh in the DAO this would still say
        // true - the record would be reported as deleted immediately after being restored.
        assertThat(body.get("isDeleted").asBoolean()).isFalse();
        assertThat(body.has("deletedAt")).isFalse();
        assertThat(body.get("amount").asDouble()).isEqualTo(10.00);

        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");
        assertThat(columnInDatabase(transactionId, "deleted_at")).isNull();

        // Back in the list and back in the figures.
        assertThat(ids(list(token, null, null, null))).containsExactly(transactionId);
        assertThat(expenseTotal(token)).isEqualByComparingTo(new BigDecimal("10.00"));

        // The log reads CREATE, DELETE, RESTORE - the earlier rows are left as they were, which is
        // what makes it a log rather than a current-state snapshot.
        List<String> actions = historyActions(transactionId);
        assertThat(actions).containsExactly("CREATE", "DELETE", "RESTORE");
    }

    @Test
    @DisplayName("UC-10 A1: restoring a record that is not deleted is a 409")
    void restoringALiveRecordIsAConflict() throws Exception {
        // sp_restore_transaction would accept this and change nothing, since it only sets
        // is_deleted = 0 on a row that is already 0. The state is checked first so the client is
        // not told a change happened when none did.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), null);

        ResponseEntity<String> response = send(HttpMethod.POST,
                TRANSACTIONS_URL + "/" + transactionId + "/restore", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("TRANSACTION_NOT_DELETED");
        // The trigger would have written no history row for an UPDATE that changed nothing, so the
        // log still holds only the CREATE row.
        assertThat(historyRowsFor(transactionId)).hasSize(1);
    }

    @Test
    @DisplayName("BR-09: the whole lifecycle is idempotent in the states a client can reach")
    void deleteAndRestoreAreIdempotentInEffect() throws Exception {
        // Repeating an operation that succeeded the first time must not change the outcome, whether
        // the client is retrying a timed-out request or a user double-clicked.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), null);

        delete(token, transactionId);
        // The second attempt is refused rather than repeated: the state it reports is already the
        // state the caller asked for, but the request did not do anything, and saying it did would
        // be false. The row is left alone either way.
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("1");

        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore", token,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore", token,
                null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");

        // One CREATE, one DELETE, one RESTORE - no duplicate rows from the repeated attempts.
        assertThat(historyActions(transactionId)).containsExactly("CREATE", "DELETE", "RESTORE");
    }

    // ------------------------------------------------------------------
    //  BR-09: the change history
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-09: creating a record writes one CREATE row holding the full business payload")
    void createWritesAFullHistoryRow() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(token, categoryId, "24.00", today(), "history probe");

        List<String> rows = historyRowsFor(transactionId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isEqualTo("CREATE|created");

        // The snapshot is complete enough to rebuild the previous state, which is what BR-09 asks
        // for. Read as JSON rather than as text: the payload's spacing and the way MySQL renders a
        // TINYINT are not the contract, the values are.
        JsonNode newValues = objectMapper.readTree(historyJson(transactionId, "new_values"));
        assertThat(newValues.get("amount").decimalValue()).isEqualByComparingTo("24.00");
        assertThat(newValues.get("description").asText()).isEqualTo("history probe");
        assertThat(newValues.get("isDeleted").asInt()).isZero();
        assertThat(newValues.get("categoryId").asLong()).isEqualTo(categoryId);
        // `type` is in the snapshot as the category's type at this moment, not as a reference: a
        // later rename or retype of the category does not rewrite what the log says happened.
        assertThat(newValues.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(newValues.get("source").asText()).isEqualTo("MANUAL");
        // Every column the trigger records is present, so the payload is a snapshot rather than a
        // subset that happens to hold the fields this module writes.
        assertThat(newValues.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "categoryId", "type", "amount", "description", "txnDate", "source",
                "aiSuggestedCategoryId", "aiConfidence", "aiOverridden",
                "recurringRuleId", "importBatchId",
                "isFlagged", "flagType", "flagNote", "isDeleted", "deletedAt");
        // The columns the later modules own are null in the snapshot, because they are null on the
        // row - a CREATE row records what was written, not what could have been.
        assertThat(newValues.get("aiSuggestedCategoryId").isNull()).isTrue();
        assertThat(newValues.get("recurringRuleId").isNull()).isTrue();
        assertThat(newValues.get("importBatchId").isNull()).isTrue();

        assertThat(historyChangedBy(transactionId)).isEqualTo(userId);
    }

    @Test
    @DisplayName("BR-09: an edit records only the columns that actually changed")
    void updateRecordsOnlyWhatChanged() throws Exception {
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), "before");

        send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                Map.of("amount", "11.00", "description", "after"));

        List<String> rows = historyRowsFor(transactionId);
        assertThat(rows).hasSize(2);
        // Both changed columns are named, and nothing else is - UAT-06 counts these entries, so a
        // trigger that listed every column would fail it even though the data was right.
        assertThat(rows.get(1)).startsWith("UPDATE");
        assertThat(rows.get(1)).contains("amount").contains("description");
        assertThat(rows.get(1)).doesNotContain("txnDate", "categoryId", "source");

        // Both halves of the snapshot are present, so the previous state can be reconstructed.
        JsonNode oldValues = objectMapper.readTree(historyJson(transactionId, "old_values"));
        JsonNode newValues = objectMapper.readTree(historyJson(transactionId, "new_values"));
        assertThat(oldValues.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(oldValues.get("description").asText()).isEqualTo("before");
        assertThat(newValues.get("amount").decimalValue()).isEqualByComparingTo("11.00");
        assertThat(newValues.get("description").asText()).isEqualTo("after");

        // The columns the edit did not touch are identical in both halves, so the diff the log
        // names is the whole of what changed.
        assertThat(oldValues.get("txnDate").asText()).isEqualTo(newValues.get("txnDate").asText());
        assertThat(oldValues.get("categoryId").asLong())
                .isEqualTo(newValues.get("categoryId").asLong());
    }

    // ------------------------------------------------------------------
    //  Ownership (BR-02) and authorization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-02: another student's transaction is unreachable through all six endpoints")
    void anotherStudentsTransactionIsUnreachable() throws Exception {
        String ownerToken = loginNewStudent();
        String otherToken = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transactionId = createAndReturnId(ownerToken, categoryId, "10.00", today(), "mine");

        // READ: 404 rather than 403, so the response does not confirm the record exists.
        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, otherToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // It is absent from the other student's list too, even with the trash included.
        assertThat(ids(list(otherToken, null, null, true))).isEmpty();

        // UPDATE: refused, and the row is untouched.
        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, otherToken,
                Map.of("amount", "9999.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(columnInDatabase(transactionId, "amount")).isEqualTo("10.00");
        assertThat(columnInDatabase(transactionId, "description")).isEqualTo("mine");

        // DELETE: refused, and the record is still live.
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, otherToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("0");

        // RESTORE: refused on a live record, and - the case that matters - on a deleted one too.
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore",
                otherToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        delete(ownerToken, transactionId);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore",
                otherToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("1");

        // The stored procedures re-check ownership themselves, so the refusal does not depend on
        // the service having got it right. Proved by calling one directly for the wrong student.
        assertThatThrownBy(() -> {
            try (Connection connection = openDatabaseConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "CALL sp_soft_delete_transaction(?, ?)")) {
                statement.setLong(1, transactionId);
                statement.setLong(2, userIdOfNewStudent(otherToken));
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("BR-02");

        // The owner can still see and restore it.
        assertThat(ids(list(ownerToken, null, null, true))).containsExactly(transactionId);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore",
                ownerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Section 7.5: every transaction endpoint refuses an anonymous caller")
    void anonymousCallerIsRefused() {
        // Checked before anything else, so no endpoint leaks whether a record exists by answering a
        // request that carried no identity.
        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, null, Map.of("categoryId", 1))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/1", null, Map.of("amount", "1.00"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/1/restore", null, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The error code is the one module 1 defined for "no token at all", which is what the
        // Angular interceptor switches on to redirect to the sign-in page.
        assertThat(errorCodeOf(send(HttpMethod.GET, TRANSACTIONS_URL, null, null)))
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Section 7.5: a malformed or tampered token is refused as UNAUTHENTICATED")
    void malformedTokenIsRefused() {
        for (String token : List.of("not-a-jwt", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.bogus.bogus")) {
            assertThat(send(HttpMethod.GET, TRANSACTIONS_URL, token, null).getStatusCode())
                    .as("token=%s", token)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("Section 7.5: an administrator token is refused by the student transaction API")
    void administratorTokenIsRefused() throws Exception {
        // Administrators read aggregates through /api/v1/admin/** (UC-21, UC-22). They have no
        // endpoint that writes a transaction, and refusing the role here is what stops a
        // transaction owned by an administrator from being created through a student-facing API.
        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL, adminToken, Map.of(
                "categoryId", defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "amount", "1.00",
                "txnDate", today().toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("BR-03: signing out revokes the session, and the token stops working here")
    void revokedSessionIsRefused() throws Exception {
        // The distinction the filter makes: a token whose signature is valid but whose session row
        // has been revoked is not authenticated at all.
        String token = loginNewStudent();
        createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME), "10.00", today(), "x");

        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(send(HttpMethod.POST, LOGOUT_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterLogout = send(HttpMethod.GET, TRANSACTIONS_URL, token, null);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCodeOf(afterLogout)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("BR-03: a disabled account is refused here, and the refusal is not ACCOUNT_DISABLED")
    void disabledAccountIsRefusedAsUnauthenticated() throws Exception {
        // ACCOUNT_DISABLED belongs to the sign-in endpoints, where UC-02 A2 asks for a distinct
        // message. On a token-guarded endpoint the filter rejects before the request reaches the
        // controller, and the entry point always answers UNAUTHENTICATED - the same answer as an
        // expired token, so a disabled student's session cannot be distinguished from a stale one.
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);

        setAccountStatus(userId, "DISABLED");

        ResponseEntity<String> response = send(HttpMethod.GET, TRANSACTIONS_URL, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCodeOf(response)).isEqualTo("UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------
    //  UC-14: the budget alert side effect of recording an expense
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-14: recording an expense raises a NEAR alert, and a second one crossing 100%")
    void recordingAnExpenseRaisesTheBudgetAlerts() throws Exception {
        // UAT-07's scenario. The client never asks for this and cannot suppress it: the alerts are
        // raised by trg_transactions_after_insert calling sp_check_budget_alerts, in the same
        // database transaction as the insert.
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long budgetId = createBudget(userId, categoryId, today.withDayOfMonth(1),
                new BigDecimal("30.00"));

        // 24 of 30 is exactly 80%, which is the seeded near threshold (VĐ-05: configuration, never a
        // constant in the code).
        createAndReturnId(token, categoryId, "24.00", today, "reaching the threshold");

        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|80.00");
        assertThat(notificationTypesFor(userId)).containsExactly("BUDGET_NEAR");
        assertThat(notificationTitlesFor(userId))
                .containsExactly("Approaching budget limit: " + DEFAULT_EXPENSE_NAME);
        // The message carries the figures, so the student does not have to open the budget screen.
        assertThat(notificationBodiesFor(userId).get(0))
                .contains("80.00%").contains("24.00").contains("30.00");

        // A further 7 takes the total to 31 of 30. Only the EXCEEDED alert is raised: the ELSEIF in
        // the procedure means a jump straight past 100% does not also emit a NEAR.
        createAndReturnId(token, categoryId, "7.00", today, "past the limit");

        assertThat(alertRowsFor(budgetId)).containsExactlyInAnyOrder("NEAR|80.00", "EXCEEDED|103.33");
        assertThat(notificationTypesFor(userId))
                .containsExactly("BUDGET_NEAR", "BUDGET_EXCEEDED");
        assertThat(notificationTitlesFor(userId).get(1))
                .isEqualTo("Budget exceeded: " + DEFAULT_EXPENSE_NAME);
    }

    @Test
    @DisplayName("BR-12: each threshold alerts once per budget, however many times the record is edited")
    void budgetAlertsAreNotDuplicated() throws Exception {
        // BR-12 says each threshold may alert once per category per month. The guarantee is
        // uk_alert_budget_threshold (budget_id, threshold_type) plus INSERT IGNORE, and the
        // notification is only written when ROW_COUNT() shows the insert actually happened.
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long budgetId = createBudget(userId, categoryId, today.withDayOfMonth(1),
                new BigDecimal("100.00"));

        Long transactionId = createAndReturnId(token, categoryId, "90.00", today, "at 90%");
        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|90.00");
        assertThat(notificationTypesFor(userId)).containsExactly("BUDGET_NEAR");

        // Editing recomputes the budget through trg_transactions_after_update, and the recomputation
        // must not raise a second alert for a threshold already logged.
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                    Map.of("description", "edit " + attempt)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(alertRowsFor(budgetId)).hasSize(1);
        assertThat(notificationTypesFor(userId)).hasSize(1);

        // Deleting the record does not retract the alert - the alert log records what the student
        // was told, not what is true right now - and it does not raise a new one.
        delete(token, transactionId);
        assertThat(alertRowsFor(budgetId)).hasSize(1);
        assertThat(notificationTypesFor(userId)).hasSize(1);
    }

    @Test
    @DisplayName("UC-14: a budget is only touched by records in its own category and month")
    void budgetAlertsRespectTheCategoryAndTheMonth() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long foodCategoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transportCategoryId = defaultCategoryId("Transport");
        LocalDate today = today();

        Long budgetId = createBudget(userId, foodCategoryId, today.withDayOfMonth(1),
                new BigDecimal("30.00"));

        // A different expense category, and the same category in an earlier month: neither counts.
        createAndReturnId(token, transportCategoryId, "25.00", today, "not food");
        createAndReturnId(token, foodCategoryId, "25.00",
                today.withDayOfMonth(1).minusMonths(1), "food, last month");

        assertThat(alertRowsFor(budgetId)).isEmpty();
        assertThat(notificationTypesFor(userId)).isEmpty();

        // The current month for that category does count.
        createAndReturnId(token, foodCategoryId, "25.00", today, "food, this month");
        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|83.33");
    }

    @Test
    @DisplayName("UC-14: the alert belongs to the student who recorded the expense, not to anyone else")
    void budgetAlertsReachOnlyTheOwner() throws Exception {
        // The notification is addressed from the row's own user_id, so a second student's activity
        // cannot raise an alert on the first student's budget. The budget identifier is global, so
        // this also shows the trigger looks the budget up by (user, category, month) rather than by
        // the record alone.
        String token = loginNewStudent();
        String otherToken = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long otherUserId = userIdOfNewStudent(otherToken);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate day = today();

        Long budgetId = createBudget(userId, categoryId, day.withDayOfMonth(1),
                new BigDecimal("10.00"));

        // The other student spends well past the same category's limit, but has no budget of their
        // own - so nothing is raised, for either of them.
        createAndReturnId(otherToken, categoryId, "500.00", day, "someone else's spending");

        assertThat(alertRowsFor(budgetId)).isEmpty();
        assertThat(notificationTypesFor(otherUserId)).isEmpty();
        assertThat(notificationTypesFor(userId)).isEmpty();

        // The budget holder's own spending raises it, addressed to them.
        createAndReturnId(token, categoryId, "9.00", day, "my spending");
        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|90.00");
        assertThat(notificationTypesFor(userId)).containsExactly("BUDGET_NEAR");
        assertThat(notificationTypesFor(otherUserId)).isEmpty();
    }

    // ------------------------------------------------------------------
    //  Duplicates and concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-07: two identical records are two records, because a ledger has no natural key")
    void identicalRecordsAreNotDeduplicated() throws Exception {
        // Unlike a category name, two expenses of the same amount on the same day in the same
        // category are an ordinary thing - two coffees, two bus fares. There is no idempotency key
        // in UC-07 and none is invented here; a retried POST produces a second record, which is why
        // the client should not retry blindly.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long first = createAndReturnId(token, categoryId, "2.50", today, "coffee");
        Long second = createAndReturnId(token, categoryId, "2.50", today, "coffee");

        assertThat(first).isNotEqualTo(second);
        assertThat(ids(list(token, null, null, null))).containsExactlyInAnyOrder(first, second);
        assertThat(historyRowsFor(first)).hasSize(1);
        assertThat(historyRowsFor(second)).hasSize(1);
    }

    @Test
    @DisplayName("UC-10: simultaneous deletes of one record produce exactly one deletion")
    void simultaneousDeletesProduceOneDeletion() throws Exception {
        // The race the locking read exists for. Without it both requests read is_deleted = 0, both
        // conclude the delete applies, both call sp_soft_delete_transaction and both answer 204 -
        // and because the second call finds no live row, trg_transactions_after_update computes
        // v_action from an is_deleted transition that did not happen and logs an UPDATE instead of a
        // DELETE. The student would be told the delete succeeded twice, once.
        //
        // With the row locked, the second request waits, then reads the state the first one left and
        // answers 409.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), "raced");

        int attempts = 6;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token,
                            null);
                }));
            }

            int deleted = 0;
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
                    deleted++;
                    continue;
                }
                assertThat(response.getStatusCode())
                        .as("body=%s", response.getBody())
                        .isEqualTo(HttpStatus.CONFLICT);
                assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                        .isEqualTo("TRANSACTION_ALREADY_DELETED");
            }
            assertThat(deleted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // One deletion, and one DELETE row in the log - not a DELETE plus a junk UPDATE.
        assertThat(columnInDatabase(transactionId, "is_deleted")).isEqualTo("1");
        assertThat(historyActions(transactionId)).containsExactly("CREATE", "DELETE");
    }

    @Test
    @DisplayName("UC-10: simultaneous edits of one record do not lose an update or corrupt the log")
    void simultaneousEditsAreSerialised() throws Exception {
        // Every edit path takes the row lock, so the edits are applied one after another rather than
        // from a shared stale copy. Without the lock, Hibernate's reads would all start from the
        // same row state and all but one edit would be written over - and @DynamicUpdate limits the
        // statement to changed columns, so the damaged edit would be the only trace.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), "raced");

        int attempts = 4;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                String description = "editor " + attempt;
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token,
                            Map.of("description", description));
                }));
            }
            for (Future<ResponseEntity<String>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        // Four edits, four history rows, one CREATE row: the log accounts for every change that was
        // applied and nothing that was not.
        assertThat(historyActions(transactionId)).hasSize(5);
        assertThat(historyActions(transactionId).get(0)).isEqualTo("CREATE");
        assertThat(historyActions(transactionId).subList(1, 5))
                .containsOnly("UPDATE");
        assertThat(columnInDatabase(transactionId, "description")).startsWith("editor ");
    }

    @Test
    @DisplayName("Section 7.7: a malformed body is a 400 with no internals in the response")
    void malformedBodyIsRejectedWithoutLeakingInternals() throws Exception {
        String token = loginNewStudent();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(TRANSACTIONS_URL, HttpMethod.POST,
                new HttpEntity<>("{ this is not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = response.getBody();
        assertThat(body).doesNotContain("java.", "springframework", "hibernate", "SQLException",
                "at com.campuscoin", "stackTrace");
        assertThat(objectMapper.readTree(body).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "errorCode", "message", "path");
    }

    @Test
    @DisplayName("Section 7.6: no internal identifier reaches the response when a write is refused")
    void refusalsDoNotLeakDatabaseInternals() throws Exception {
        // Every refusal in this module is a normal outcome of normal use, so the response carries a
        // message the student can act on and none of the schema. What must not appear: the
        // procedure's SIGNAL text, the trigger or constraint name, the column, or the driver's own
        // wording. The log half of this is asserted in SecurityHardeningIT, where the log stream is
        // captured.
        String token = loginNewStudent();
        Long transactionId = createAndReturnId(token, defaultCategoryId(DEFAULT_EXPENSE_NAME),
                "10.00", today(), null);

        delete(token, transactionId);
        String alreadyDeleted = send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId,
                token, null).getBody();
        assertThat(alreadyDeleted).doesNotContain("sp_soft_delete_transaction", "SIGNAL", "BR-09",
                "is_deleted", "transactions.");
        // It does say what happened, in the student's terms.
        assertThat(alreadyDeleted).contains("already been deleted");

        String notDeleted = send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId
                + "/restore", token, null).getBody();
        assertThat(notDeleted).doesNotContain("sp_restore_transaction", "SIGNAL", "is_deleted");

        Long retiredCategory = createCategory(token, "Retired Response", "EXPENSE", null, null);
        retire(token, retiredCategory, true);
        String retired = createTransaction(token, retiredCategory, "1.00", today(), null).getBody();
        assertThat(retired).doesNotContain("sp_validate_transaction", "trg_transactions", "BR-07",
                "SIGNAL", "is_active");

        // An unknown category must not reveal whether the id exists for somebody else.
        String unknown = createTransaction(token, 99999999L, "1.00", today(), null).getBody();
        assertThat(unknown).doesNotContain("categories", "foreign key", "fk_txn_category", "SQL");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** The caller's own id, read from the profile endpoint rather than guessed. */
    private Long userIdOfNewStudent(String token) throws Exception {
        return objectMapper.readTree(
                send(HttpMethod.GET, PROFILE_URL, token, null).getBody()).get("id").asLong();
    }

    private ResponseEntity<String> createTransaction(String token, Long categoryId, String amount,
                                                     LocalDate date, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("amount", amount);
        body.put("txnDate", date.toString());
        if (description != null) {
            body.put("description", description);
        }
        return send(HttpMethod.POST, TRANSACTIONS_URL, token, body);
    }

    private Long createAndReturnId(String token, Long categoryId, String amount, LocalDate date,
                                   String description) throws Exception {
        ResponseEntity<String> response =
                createTransaction(token, categoryId, amount, date, description);
        assertThat(response.getStatusCode())
                .as("create body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    private void delete(String token, Long transactionId) {
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private JsonNode list(String token, LocalDate from, LocalDate to, Boolean includeDeleted)
            throws Exception {
        StringBuilder url = new StringBuilder(TRANSACTIONS_URL);
        if (from != null) {
            url.append("?from=").append(from);
        }
        if (to != null) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append("to=").append(to);
        }
        if (includeDeleted != null) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?")
                    .append("includeDeleted=").append(includeDeleted);
        }
        ResponseEntity<String> response = send(HttpMethod.GET, url.toString(), token, null);
        assertThat(response.getStatusCode()).as("url=%s", url).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(entry -> ids.add(entry.get("id").asLong()));
        return ids;
    }

    private void assertFieldError(String token, HttpMethod method, String url, Object body,
                                  String expectedField) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);

        assertThat(response.getStatusCode())
                .as("body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode parsed = objectMapper.readTree(response.getBody());
        assertThat(parsed.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        // `field` is the canonical property name project-wide (section 19). A `path` key here would
        // break the Angular error renderer.
        assertThat(fieldNamesOf(parsed)).as("body=%s", body).contains(expectedField);
    }

    private static List<String> fieldNamesOf(JsonNode error) {
        List<String> names = new ArrayList<>();
        error.get("fieldErrors").forEach(fieldError -> names.add(fieldError.get("field").asText()));
        return names;
    }

    private String errorCodeOf(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).get("errorCode").asText();
        } catch (Exception ex) {
            throw new AssertionError("The response body is not the documented error shape: "
                    + response.getBody(), ex);
        }
    }

    private String register(String email) {
        ResponseEntity<String> response = send(HttpMethod.POST, REGISTER_URL, null, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    /** Registers a student and returns a bearer token for them. */
    private String loginNewStudent() throws Exception {
        String email = randomEmail();
        register(email);
        return login(email);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private String adminLogin() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, ADMIN_LOGIN_URL, null,
                Map.of("email", SEEDED_ADMIN_EMAIL, "password", SEEDED_ADMIN_PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Creates one of the caller's own categories.
     *
     * <p>Standalone categories rather than the shared defaults, wherever a test needs to retire one
     * or to attach an icon and a colour: a default category belongs to every student and must not be
     * modified by a test.
     */
    private Long createCategory(String token, String name, String type, String icon, String color)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        if (icon != null) {
            body.put("icon", icon);
        }
        if (color != null) {
            body.put("color", color);
        }

        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, body);
        assertThat(response.getStatusCode())
                .as("category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    /** BR-07: retires or un-retires one of the caller's own categories. */
    private void retire(String token, Long categoryId, boolean inactive) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("isActive", !inactive));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(categoryId, "categories", "is_active"))
                .isEqualTo(inactive ? "0" : "1");
    }

    // --- database access --------------------------------------------------------------------

    /**
     * Inserts a transaction straight into the table, bypassing the API.
     *
     * <p>This is how the trigger's refusals are reached: {@code sp_validate_transaction} runs on the
     * way in and refuses for <em>every</em> caller, including a hand-run statement, which is what
     * makes it the authority rather than a second opinion behind the service's checks.
     */
    private void insertTransaction(Long userId, Long categoryId, String source, BigDecimal amount,
                                   LocalDate date, String description) throws SQLException {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO transactions (user_id, category_id, amount, description, txn_date, "
                             + "source) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setBigDecimal(3, amount);
            statement.setString(4, description);
            statement.setObject(5, date);
            statement.setString(6, source);
            statement.executeUpdate();
        }
    }

    /**
     * Runs one statement against the real database, outside the application.
     *
     * <p>Used to set up the two things this module does not own: a budget, which belongs to module 6,
     * and a disabled account, which belongs to module 11. Both are written the way the database
     * itself would write them - through {@code sp_validate_budget} for a budget, and straight to
     * {@code users.status} for an account - so the fixtures obey the same rules as the real
     * operations rather than being a shortcut around them.
     */
    private void runInDatabase(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    /** BR-11: a budget may only exist on an EXPENSE category the student may file under. */
    private Long createBudget(Long userId, Long categoryId, LocalDate periodMonth, BigDecimal limit)
            throws Exception {
        runInDatabase("CALL sp_validate_budget(?, ?)", userId, categoryId);
        runInDatabase("INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                + "VALUES (?, ?, ?, ?)", userId, categoryId, periodMonth, limit);
        return budgetIdFor(userId, categoryId, periodMonth);
    }

    /** The budget's own id, read back rather than assumed: uk_budget_user_cat_month makes it unique. */
    private Long budgetIdFor(Long userId, Long categoryId, LocalDate periodMonth) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM budgets WHERE user_id = ? AND category_id = ? "
                             + "AND period_month = ?")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setObject(3, periodMonth);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /** Every {@code budget_alert_log} row for a budget, as {@code THRESHOLD|consumed_pct}. */
    private List<String> alertRowsFor(Long budgetId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT threshold_type, consumed_pct FROM budget_alert_log WHERE budget_id = ? "
                             + "ORDER BY id")) {
            statement.setLong(1, budgetId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> alerts = new ArrayList<>();
                while (rows.next()) {
                    alerts.add(rows.getString(1) + "|" + rows.getString(2));
                }
                return alerts;
            }
        }
    }

    private List<String> notificationTypesFor(Long userId) throws Exception {
        return notificationsFor(userId, "type");
    }

    private List<String> notificationTitlesFor(Long userId) throws Exception {
        return notificationsFor(userId, "title");
    }

    private List<String> notificationBodiesFor(Long userId) throws Exception {
        return notificationsFor(userId, "body");
    }

    private List<String> notificationsFor(Long userId, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM notifications WHERE user_id = ? ORDER BY id")) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    /**
     * What the student's expense total is, read from the view every report is built on.
     *
     * <p>Not counted from the {@code transactions} table: the point of the assertion is that a
     * soft-deleted record leaves the figures the rest of the system reads (BR-09), and that is the
     * view's job rather than the table's.
     */
    private BigDecimal expenseTotal(String token) throws Exception {
        Long userId = userIdOfNewStudent(token);
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT IFNULL(SUM(total_expense), 0) FROM v_monthly_income_expense "
                             + "WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getBigDecimal(1);
            }
        }
    }

    /** Every history row for a transaction as {@code ACTION|changed_fields}, in insertion order. */
    private List<String> historyRowsFor(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT action, changed_fields FROM transaction_history "
                             + "WHERE transaction_id = ? ORDER BY id")) {
            statement.setLong(1, transactionId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(rows.getString(1) + "|" + rows.getString(2));
                }
                return entries;
            }
        }
    }

    private List<String> historyActions(Long transactionId) throws Exception {
        List<String> actions = new ArrayList<>();
        for (String row : historyRowsFor(transactionId)) {
            actions.add(row.substring(0, row.indexOf('|')));
        }
        return actions;
    }

    private String historyJson(Long transactionId, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM transaction_history WHERE transaction_id = ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private Long historyChangedBy(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT changed_by FROM transaction_history WHERE transaction_id = ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private boolean transactionExists(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private String columnInDatabase(Long id, String column) throws Exception {
        return columnInDatabase(id, "transactions", column);
    }

    private String columnInDatabase(Long id, String table, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** Every business column of a transaction row, so an unwanted change is visible. */
    private String rowSnapshot(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT category_id, amount, description, txn_date, source, is_deleted "
                             + "FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                StringBuilder snapshot = new StringBuilder();
                for (int index = 1; index <= 6; index++) {
                    snapshot.append(row.getString(index)).append('|');
                }
                return snapshot.toString();
            }
        }
    }

    /** The id of a seeded default category by name, used to prove students cannot touch them. */
    private Long defaultCategoryId(String name) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM categories WHERE user_id IS NULL AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Long userIdOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Long registerAndReturnId() throws Exception {
        String email = randomEmail();
        register(email);
        return userIdOf(email);
    }

    /**
     * Sets {@code users.status} directly, as an administrator's disable would.
     *
     * <p>Written straight to the column because UC-22 belongs to module 11 and the procedure that
     * owns this change is not reachable yet. The status is what the token filter reads, so setting
     * the column exercises the same path the real operation will.
     */
    private void setAccountStatus(Long userId, String status) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    private String randomEmail() {
        return "transaction." + UUID.randomUUID() + "@student.campuscoin.edu";
    }
}
