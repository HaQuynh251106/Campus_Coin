package com.campuscoin.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.campuscoin.recurring.repository.RecurringProcedureDao;
import com.campuscoin.recurring.scheduler.RecurringScheduler;
import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RecurringRuleApiIT extends AbstractMySqlIntegrationTest {

    private static final String RULES_URL = "/api/v1/recurring-rules";
    private static final String CATEGORIES_URL = "/api/v1/categories";
    private static final String TRANSACTIONS_URL = "/api/v1/transactions";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String PROFILE_URL = "/api/v1/profile/me";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    private static final String PASSWORD = "Student@123";

    private static final String DEFAULT_EXPENSE_NAME = "Subscriptions";

    private static final String DEFAULT_INCOME_NAME = "Allowance";

    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
            "amount", "description", "frequency", "intervalCount", "startDate", "endDate",
            "nextRunDate", "lastRunDate", "status");

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecurringProcedureDao recurringProcedureDao;

    @Test
    @DisplayName("UC-09: a student with no rules gets an empty list, not an error and not null")
    void emptyStateIsAnEmptyArray() throws Exception {

        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("UC-09: a created rule comes back with exactly the documented fields and no owner")
    void createdRuleHasTheDocumentedShape() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        JsonNode rule = createRule(token, categoryId, "8.00", "MONTHLY", today());

        assertThat(fieldNamesOf(rule)).isSubsetOf(DOCUMENTED_FIELDS);
        assertThat(fieldNamesOf(rule)).contains("id", "categoryId", "amount", "frequency",
                "intervalCount", "startDate", "nextRunDate", "status");
        assertThat(fieldNamesOf(rule)).doesNotContain("userId", "createdAt", "updatedAt");

        assertThat(rule.get("amount").decimalValue()).isEqualByComparingTo("8.00");

        assertThat(rawRule(token, rule.get("id").asLong())).contains("\"amount\":8.00");
        assertThat(rule.get("frequency").asText()).isEqualTo("MONTHLY");
        assertThat(rule.get("intervalCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-09: the category is flattened and its name, icon, colour and type come from it")
    void theCategoryIsFlattenedIntoTheResponse() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Rent", "EXPENSE", "home", "#EF4444");

        JsonNode rule = createRule(token, categoryId, "150.00", "MONTHLY", today());

        assertThat(rule.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(rule.get("categoryName").asText()).isEqualTo("Rent");
        assertThat(rule.get("categoryIcon").asText()).isEqualTo("home");
        assertThat(rule.get("categoryColor").asText()).isEqualTo("#EF4444");
        assertThat(rule.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(rule.has("category")).as("the category must be flattened, not nested").isFalse();
    }

    @Test
    @DisplayName("UC-09: nullable fields are omitted rather than serialised as null")
    void absentValuesAreOmitted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "No frills", "EXPENSE", null, null);

        JsonNode rule = createRule(token, categoryId, "5.00", "MONTHLY", today());

        assertThat(rule.has("categoryIcon")).isFalse();
        assertThat(rule.has("categoryColor")).isFalse();
        assertThat(rule.has("description")).isFalse();
        assertThat(rule.has("endDate")).isFalse();
        assertThat(rule.has("lastRunDate")).isFalse();
    }

    @Test
    @DisplayName("UC-09: a new rule is ACTIVE and has no lastRunDate, which is how a client tells it apart")
    void aNewRuleIsActiveAndHasNeverRun() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        JsonNode rule = createRule(token, categoryId, "8.00", "MONTHLY", today());

        assertThat(rule.get("status").asText()).isEqualTo("ACTIVE");

        assertThat(rule.has("lastRunDate")).isFalse();
    }

    @Test
    @DisplayName("UC-09: nextRunDate defaults to startDate, so a monthly rule first runs on the 1st")
    void nextRunDateDefaultsToStartDate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_INCOME_NAME);
        LocalDate start = today().plusDays(3);

        JsonNode rule = createRule(token, categoryId, "200.00", "MONTHLY", start);

        assertThat(rule.get("startDate").asText()).isEqualTo(start.toString());
        assertThat(rule.get("nextRunDate").asText()).isEqualTo(start.toString());
    }

    @Test
    @DisplayName("UC-09: nextRunDate may be set later than startDate, which prepares a rule in advance")
    void nextRunDateCanBeLaterThanStartDate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_INCOME_NAME);
        LocalDate start = today();
        LocalDate firstRun = today().plusMonths(2);

        JsonNode rule = createRule(token, categoryId, "200.00", "MONTHLY", start,
                optional("nextRunDate", firstRun.toString()));

        assertThat(rule.get("startDate").asText()).isEqualTo(start.toString());
        assertThat(rule.get("nextRunDate").asText()).isEqualTo(firstRun.toString());
    }

    @Test
    @DisplayName("UC-09: intervalCount defaults to 1 and an interval of 2 is stored as sent")
    void intervalCountDefaultsToOneAndIsHonouredWhenSent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        JsonNode defaulted = createRule(token, categoryId, "8.00", "MONTHLY", today());
        assertThat(defaulted.get("intervalCount").asInt()).isEqualTo(1);

        JsonNode everyOther = createRule(token, categoryId, "8.00", "MONTHLY", today(),
                optional("intervalCount", 2));
        assertThat(everyOther.get("intervalCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-09: rules are ordered by nextRunDate, and a shared date is ordered by id")
    void rulesAreOrderedByNextRunDateThenId() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate base = today();

        Long later = createRuleAndReturnId(token, categoryId, "1.00", "MONTHLY", base.plusDays(20));

        Long sameDayFirst = createRuleAndReturnId(token, categoryId, "2.00", "MONTHLY", base.plusDays(5));
        Long sameDaySecond = createRuleAndReturnId(token, categoryId, "3.00", "MONTHLY", base.plusDays(5));
        Long soonest = createRuleAndReturnId(token, categoryId, "4.00", "MONTHLY", base);

        assertThat(ids(listRules(token)))
                .containsExactly(soonest, sameDayFirst, sameDaySecond, later);
    }

    @Test
    @DisplayName("UC-09: paused and ended rules stay in the list, because the client filters on status")
    void pausedAndEndedRulesAreStillListed() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long active = createRuleAndReturnId(token, categoryId, "1.00", "MONTHLY", today());
        Long paused = createRuleAndReturnId(token, categoryId, "2.00", "MONTHLY", today().plusDays(1));
        Long ended = createRuleAndReturnId(token, categoryId, "3.00", "MONTHLY", today().plusDays(2));

        assertThat(patchRule(token, paused, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchRule(token, ended, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ids(listRules(token))).containsExactlyInAnyOrder(active, paused, ended);
    }

    @Test
    @DisplayName("UC-09: the list is the caller's own, with no way to ask for another student's")
    void theListIsScopedToTheCaller() throws Exception {
        String mine = loginNewStudent();
        String theirs = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long myRule = createRuleAndReturnId(mine, categoryId, "1.00", "MONTHLY", today());
        createRuleAndReturnId(theirs, categoryId, "2.00", "MONTHLY", today());

        assertThat(ids(listRules(mine))).containsExactly(myRule);

        ResponseEntity<String> attempted = send(HttpMethod.GET,
                RULES_URL + "?userId=" + userIdOfNewStudent(theirs), mine, null);
        assertThat(attempted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(objectMapper.readTree(attempted.getBody()))).containsExactly(myRule);
    }

    @Test
    @DisplayName("UC-09: a rule can be read on its own, which is what refreshes one row after an edit")
    void aRuleCanBeReadById() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "8.00", "MONTHLY", today());

        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL + "/" + ruleId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rule = objectMapper.readTree(response.getBody());
        assertThat(rule.get("id").asLong()).isEqualTo(ruleId);
        assertThat(rule.get("categoryId").asLong()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("BR-02: another student's rule is 404 on every endpoint that names it")
    void anotherStudentsRuleIsUnreachable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(owner, categoryId, "8.00", "MONTHLY", today());

        assertThat(send(HttpMethod.GET, RULES_URL + "/" + ruleId, intruder, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patchRule(intruder, ruleId, Map.of("amount", "1.00")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, intruder, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(columnInDatabase(ruleId, "recurring_rules", "amount")).isEqualTo("8.00");
        assertThat(ids(listRules(intruder))).isEmpty();
    }

    @Test
    @DisplayName("BR-02: another student's category cannot be used, and is not distinguishable from missing")
    void anotherStudentsCategoryIsNotFoundRatherThanForbidden() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long foreignCategory = createCategory(owner, "Their category", "EXPENSE", null, null);

        assertFieldError(intruder, HttpMethod.POST, RULES_URL,
                createBody(foreignCategory, "10.00", "MONTHLY", today().toString()),
                null, HttpStatus.NOT_FOUND);

        assertThat(send(HttpMethod.POST, RULES_URL, intruder,
                createBody(999_999_999L, "10.00", "MONTHLY", today().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("BR-05: the type comes from the category, and a type sent by the client is ignored")
    void theTypeIsTheCategorysAndAClientSuppliedOneIsIgnored() throws Exception {
        String token = loginNewStudent();
        Long expenseId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);

        Map<String, Object> body = createBody(expenseId, "10.00", "MONTHLY", today().toString());
        body.put("type", "INCOME");
        JsonNode created = objectMapper.readTree(
                send(HttpMethod.POST, RULES_URL, token, body).getBody());
        assertThat(created.get("type").asText()).isEqualTo("EXPENSE");

        assertThat(columnInDatabase(created.get("id").asLong(), "recurring_rules", "type"))
                .isEqualTo("EXPENSE");

        Long incomeRule = createRuleAndReturnId(token, incomeId, "200.00", "MONTHLY", today());
        assertThat(patchRule(token, incomeRule, Map.of("categoryId", expenseId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ruleField(token, incomeRule, "type")).isEqualTo("EXPENSE");
        assertThat(ruleField(token, incomeRule, "categoryName")).isEqualTo(DEFAULT_EXPENSE_NAME);
    }

    @Test
    @DisplayName("BR-05: the stored type is kept in step with the category on every move")
    void movingACategoryRewritesTheStoredType() throws Exception {
        String token = loginNewStudent();
        Long incomeCategory = createCategory(token, "Job", "INCOME", null, null);
        Long expenseCategory = createCategory(token, "Rent", "EXPENSE", null, null);

        Long ruleId = createRuleAndReturnId(token, incomeCategory, "500.00", "MONTHLY", today());

        assertThat(patchRule(token, ruleId, Map.of("categoryId", expenseCategory)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "type")).isEqualTo("EXPENSE");

        assertThat(patchRule(token, ruleId, Map.of("categoryId", incomeCategory)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "type")).isEqualTo("INCOME");
    }

    @Test
    @DisplayName("UC-09: the four required fields are each reported by name when missing")
    void requiredFieldsAreEachReported() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(null, "10.00", "MONTHLY", start),
                "categoryId", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, null, "MONTHLY", start),
                "amount", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", null, start),
                "frequency", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", null),
                "startDate", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("ck_recurring_amount: an amount of zero or below is a field error, not a refused write")
    void amountMustBePositive() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        for (String amount : new String[] {"0.00", "0", "-5.00"}) {
            assertFieldError(token, HttpMethod.POST, RULES_URL,
                    createBody(categoryId, amount, "MONTHLY", start),
                    "amount", HttpStatus.BAD_REQUEST);
        }

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "0.01", "MONTHLY", start))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("amount DECIMAL(15,2): more precision or more digits than the column holds is refused")
    void amountMustFitTheColumn() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "1.005", "MONTHLY", start),
                "amount", HttpStatus.BAD_REQUEST);

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10000000000000.00", "MONTHLY", start),
                "amount", HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "9999999999999.99", "MONTHLY", start))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("ck_recurring_interval: the interval is bounded on both sides")
    void intervalIsBounded() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("intervalCount", 0)),
                "intervalCount", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("intervalCount", -1)),
                "intervalCount", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("intervalCount", 1000)),
                "intervalCount", HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("intervalCount", 999)))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Schedule validation: a frequency outside the column's ENUM is a field error")
    void frequencyMustBeOneOfTheFiveMembers() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        for (String frequency : new String[] {"BIWEEKLY", "monthly", "", "DAILYX", "0"}) {
            assertFieldError(token, HttpMethod.POST, RULES_URL,
                    createBody(categoryId, "10.00", frequency, start),
                    "frequency", HttpStatus.BAD_REQUEST);
        }

        for (String frequency : new String[] {"DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"}) {
            assertThat(send(HttpMethod.POST, RULES_URL, token,
                    createBody(categoryId, "10.00", frequency, start))
                    .getStatusCode()).as("frequency=%s", frequency)
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    @DisplayName("A JSON number is never accepted where an enum is expected")
    void enumNumbersAreRejected() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", 2, start),
                "frequency", HttpStatus.BAD_REQUEST);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());
        assertFieldError(token, HttpMethod.PATCH, RULES_URL + "/" + ruleId,
                Map.of("status", 1), "status", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("description is bounded at 255 characters, the width of the column")
    void descriptionIsBounded() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        String tooLong = "x".repeat(256);
        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("description", tooLong)),
                "description", HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("description", "x".repeat(255))))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("endDate accepts a date or an empty string, and nothing else")
    void endDateShapeIsValidated() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        for (String endDate : new String[] {"01/06/2027", "2027-6-1", "tomorrow", "2027-06-01T00:00"}) {
            assertFieldError(token, HttpMethod.POST, RULES_URL,
                    createBody(categoryId, "10.00", "MONTHLY", start, optional("endDate", endDate)),
                    "endDate", HttpStatus.BAD_REQUEST);
        }

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("endDate", "")))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("A well-formed but non-existent date is a field error, not an internal error")
    void aNonExistentDateIsAFieldError() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String start = today().toString();

        ResponseEntity<String> response = send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start, optional("endDate", "2027-02-30")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesOfValidationError(error)).contains("endDate");
    }

    @Test
    @DisplayName("ck_recurring_dates: an end date before the start date is refused by field name")
    void endDateCannotPrecedeStartDate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today();

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start.toString(),
                        optional("endDate", start.minusDays(1).toString())),
                "endDate", HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start.toString(),
                        optional("endDate", start.toString())))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("UC-09: a next occurrence after the end date is refused, or the rule would never post")
    void nextRunDateCannotBeAfterEndDate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today();
        LocalDate end = start.plusMonths(3);

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", start.toString(),
                        optional("endDate", end.toString()), optional("nextRunDate", end.plusDays(1).toString())),
                "nextRunDate", HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, RULES_URL, token,
                createBody(categoryId, "10.00", "MONTHLY", start.toString(),
                        optional("endDate", end.toString()), optional("nextRunDate", end.toString())))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("UC-09: a rule in the past is accepted, because the scheduler catches up")
    void aRuleInThePastIsAccepted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(3);

        JsonNode rule = createRule(token, categoryId, "10.00", "MONTHLY", start,
                optional("endDate", start.plusMonths(2).toString()));

        assertThat(rule.get("startDate").asText()).isEqualTo(start.toString());
        assertThat(rule.get("endDate").asText()).isEqualTo(start.plusMonths(2).toString());
    }

    @Test
    @DisplayName("UC-09: a client cannot choose the owner, the state or the run history of a new rule")
    void serverOwnedFieldsAreIgnoredOnCreate() throws Exception {
        String token = loginNewStudent();
        String victim = loginNewStudent();
        Long victimId = userIdOfNewStudent(victim);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Map<String, Object> body = createBody(categoryId, "10.00", "MONTHLY", today().toString());

        body.put("userId", victimId);
        body.put("status", "ENDED");
        body.put("lastRunDate", "2020-01-01");

        JsonNode created = objectMapper.readTree(
                send(HttpMethod.POST, RULES_URL, token, body).getBody());

        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.has("lastRunDate")).isFalse();

        assertThat(ids(listRules(victim))).isEmpty();
        assertThat(ids(listRules(token))).containsExactly(created.get("id").asLong());
    }

    @Test
    @DisplayName("UC-09: startDate cannot be changed, because the posted periods are derived from it")
    void startDateIsNotEditable() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today();

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", start.minusYears(1).toString());
        ResponseEntity<String> response = patchRule(token, ruleId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "startDate")).isEqualTo(start.toString());
        assertThat(columnInDatabase(ruleId, "recurring_rules", "start_date"))
                .isEqualTo(start.toString());
    }

    @Test
    @DisplayName("UC-09: an update changes only the fields it sends and leaves the rest alone")
    void updateChangesOnlyWhatItSends() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        String before = rowSnapshot(ruleId);

        assertThat(patchRule(token, ruleId, Map.of("amount", "22.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(rawRule(token, ruleId)).contains("\"amount\":22.00");
        assertThat(jsonRule(token, ruleId).get("amount").decimalValue())
                .isEqualByComparingTo("22.00");
        assertThat(ruleField(token, ruleId, "frequency")).isEqualTo("MONTHLY");
        assertThat(ruleField(token, ruleId, "intervalCount")).isEqualTo("1");
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ACTIVE");
        assertThat(ruleField(token, ruleId, "categoryId")).isEqualTo(categoryId.toString());

        assertThat(withoutAmountAndTimestamp(rowSnapshot(ruleId)))
                .isEqualTo(withoutAmountAndTimestamp(before));
    }

    @Test
    @DisplayName("UC-09: an empty update body changes nothing and is not an error")
    void anEmptyUpdateIsANoOp() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        String before = rowSnapshot(ruleId);
        assertThat(patchRule(token, ruleId, new LinkedHashMap<String, Object>()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowSnapshot(ruleId)).isEqualTo(before);
    }

    @Test
    @DisplayName("UC-09: pausing, resuming and ending are all the status field")
    void pauseResumeAndEndRunThroughStatus() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("PAUSED");
        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("PAUSED");

        assertThat(patchRule(token, ruleId, Map.of("status", "ACTIVE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ACTIVE");

        assertThat(patchRule(token, ruleId, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ENDED");

        for (String suffix : new String[] {"/pause", "/resume", "/end"}) {
            assertThat(send(HttpMethod.POST, RULES_URL + "/" + ruleId + suffix, token, Map.of())
                    .getStatusCode().is4xxClientError())
                    .as("no /%s route may exist", suffix)
                    .isTrue();
        }

        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("ENDED");
        assertThat(patchRule(token, ruleId, Map.of("status", "ACTIVE")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("UC-09: a status outside the column's ENUM is a field error")
    void anUnknownStatusIsRefused() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertFieldError(token, HttpMethod.PATCH, RULES_URL + "/" + ruleId,
                Map.of("status", "STOPPED"), "status", HttpStatus.BAD_REQUEST);
        assertFieldError(token, HttpMethod.PATCH, RULES_URL + "/" + ruleId,
                Map.of("status", "active"), "status", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("UC-09: an end date can be set and then removed, which is why it is sent as a string")
    void endDateCanBeSetAndCleared() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        LocalDate end = today().plusMonths(6);
        assertThat(patchRule(token, ruleId, Map.of("endDate", end.toString())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "endDate")).isEqualTo(end.toString());

        assertThat(patchRule(token, ruleId, Map.of("endDate", "")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jsonRule(token, ruleId).has("endDate")).isFalse();
        assertThat(columnInDatabase(ruleId, "recurring_rules", "end_date")).isNull();

        assertThat(patchRule(token, ruleId, Map.of("amount", "11.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "end_date")).isNull();
    }

    @Test
    @DisplayName("UC-09: description can be cleared with an empty string and is trimmed")
    void descriptionCanBeClearedAndIsTrimmed() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        JsonNode created = createRule(token, categoryId, "10.00", "MONTHLY", today(),
                optional("description", "  Music streaming  "));
        Long ruleId = created.get("id").asLong();
        assertThat(created.get("description").asText()).isEqualTo("Music streaming");

        assertThat(patchRule(token, ruleId, Map.of("description", "Watching")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "description")).isEqualTo("Watching");

        assertThat(patchRule(token, ruleId, Map.of("description", "   ")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "description")).isNull();
    }

    @Test
    @DisplayName("UC-09: an update against a rule that does not exist is 404, not 500")
    void updatingAMissingRuleIsNotFound() throws Exception {
        String token = loginNewStudent();

        assertThat(patchRule(token, 999_999_999L, Map.of("amount", "1.00")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, RULES_URL + "/999999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, RULES_URL + "/999999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-09: a non-numeric id is a bad request rather than an internal error")
    void aNonNumericIdIsABadRequest() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL + "/abc", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("BR-07: a retired category cannot be chosen for a new rule, and the error names the field")
    void aRetiredCategoryCannotBeChosenOnCreate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Seasonal", "EXPENSE", null, null);
        retire(token, categoryId, true);

        assertFieldError(token, HttpMethod.POST, RULES_URL,
                createBody(categoryId, "10.00", "MONTHLY", today().toString()),
                "categoryId", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("BR-07: a retired category cannot be moved onto, and the error names the field")
    void aRetiredCategoryCannotBeMovedOnto() throws Exception {
        String token = loginNewStudent();
        Long live = createCategory(token, "Live", "EXPENSE", null, null);
        Long retired = createCategory(token, "Retired", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(token, live, "10.00", "MONTHLY", today());
        retire(token, retired, true);

        assertFieldError(token, HttpMethod.PATCH, RULES_URL + "/" + ruleId,
                Map.of("categoryId", retired), "categoryId", HttpStatus.BAD_REQUEST);

        assertThat(columnInDatabase(ruleId, "recurring_rules", "category_id"))
                .isEqualTo(live.toString());
    }

    @Test
    @DisplayName("BR-07: retiring a rule's own category freezes every change to it, including ending it")
    void aRetiredCategoryBlocksEveryUpdateToItsRules() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Frozen", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        retire(token, categoryId, true);

        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("amount", "20.00"),
                Map.of("status", "PAUSED"),
                Map.of("status", "ENDED"),
                Map.of("description", "anything"))) {
            ResponseEntity<String> response = patchRule(token, ruleId, body);

            assertThat(response.getStatusCode()).as("body=%s", body).isEqualTo(HttpStatus.CONFLICT);
            assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                    .as("body=%s", body).isEqualTo("CATEGORY_RETIRED");
        }

        assertThat(columnInDatabase(ruleId, "recurring_rules", "amount")).isEqualTo("10.00");
        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("ACTIVE");

        Long live = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        assertThat(patchRule(token, ruleId, Map.of("categoryId", live)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("BR-07: the rule survives with its old data when its category is retired")
    void retiringACategoryDoesNotChangeTheRule() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Temporarily off", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        String before = rowSnapshot(ruleId);
        retire(token, categoryId, true);

        assertThat(rowSnapshot(ruleId)).isEqualTo(before);
        assertThat(ids(listRules(token))).contains(ruleId);
    }

    @Test
    @DisplayName("UC-09: a rule that has never posted can be deleted outright")
    void aRuleThatHasNeverPostedCanBeDeleted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today().plusDays(1));

        ResponseEntity<String> deleted = send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).isNull();
        assertThat(ruleExists(ruleId)).isFalse();
        assertThat(ids(listRules(token))).isEmpty();

        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-09: a rule that has generated transactions cannot be deleted - end it instead")
    void aRuleThatHasPostedCannotBeDeleted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY",
                today().minusMonths(2));
        postDue(today());
        assertThat(generatedTransactionCount(ruleId)).isGreaterThan(0);

        ResponseEntity<String> response = send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("RECURRING_RULE_IN_USE");

        assertThat(error.get("message").asText()).containsIgnoringCase("end it");

        assertThat(ruleExists(ruleId)).isTrue();
    }

    @Test
    @DisplayName("UC-09: a soft-deleted transaction still blocks the delete, because it is still restorable")
    void aSoftDeletedTransactionStillBlocksTheDelete() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY",
                today().minusMonths(1));
        postDue(today());

        Long transactionId = generatedTransactionIds(ruleId).get(0);
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(columnInDatabase(transactionId, "transactions", "is_deleted")).isEqualTo("1");

        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("UC-09: ending a rule is the safe alternative, and its transactions stay editable")
    void endingARuleLeavesItsTransactionsEditable() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY",
                today().minusMonths(1));
        postDue(today());
        Long transactionId = generatedTransactionIds(ruleId).get(0);

        assertThat(patchRule(token, ruleId, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(patchTransaction(token, transactionId, Map.of("amount", "99.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.POST, TRANSACTIONS_URL + "/" + transactionId + "/restore",
                token, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(ruleExists(ruleId)).isTrue();
        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("BR-02: a rule another student's transaction points at is not the caller's to delete")
    void deleteIsOwnershipCheckedBeforeTheGeneratedCount() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(owner, categoryId, "10.00", "MONTHLY",
                today().minusMonths(1));
        postDue(today());

        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, intruder, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ruleExists(ruleId)).isTrue();
    }

    @Test
    @DisplayName("BR-09: deleting a rule never deletes the transactions it generated")
    void deletingARuleNeverTouchesTransactions() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today().plusDays(1));
        Long manual = createTransaction(token, categoryId, "5.00", today());

        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(transactionExists(manual)).isTrue();
        assertThat(occurrenceCount(ruleId)).isZero();
    }

    @Test
    @DisplayName("UC-09: a due rule is turned into a transaction carrying the rule's category and amount")
    void aDueRuleIsPosted() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_INCOME_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "200.00", "MONTHLY", today());

        postDue(today());

        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
        List<Long> transactionIds = generatedTransactionIds(ruleId);
        assertThat(transactionIds).hasSize(1);

        assertThat(columnInDatabase(transactionIds.get(0), "transactions", "source"))
                .isEqualTo("RECURRING");
        assertThat(columnInDatabase(transactionIds.get(0), "transactions", "amount"))
                .isEqualTo("200.00");
        assertThat(columnInDatabase(transactionIds.get(0), "transactions", "category_id"))
                .isEqualTo(categoryId.toString());
        assertThat(columnInDatabase(transactionIds.get(0), "transactions", "user_id"))
                .isEqualTo(userId.toString());
        assertThat(columnInDatabase(transactionIds.get(0), "transactions", "txn_date"))
                .isEqualTo(today().toString());

        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(today().plusMonths(1).toString());
        assertThat(columnInDatabase(ruleId, "recurring_rules", "last_run_date"))
                .isEqualTo(today().toString());
        assertThat(ruleField(token, ruleId, "lastRunDate")).isEqualTo(today().toString());
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BR-09: a scheduled transaction writes its history row like any other")
    void aScheduledTransactionIsAuditedByTheSameTrigger() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "12.00", "MONTHLY", today());
        postDue(today());
        Long transactionId = generatedTransactionIds(ruleId).get(0);

        assertThat(historyActions(transactionId)).containsExactly("CREATE");
        assertThat(historyJson(transactionId, "new_values")).contains("\"source\": \"RECURRING\"");
        assertThat(historyJson(transactionId, "new_values"))
                .contains("\"recurringRuleId\": " + ruleId);
    }

    @Test
    @DisplayName("UC-09: a posted transaction's description is readable through the API and still "
            + "not plaintext on disk")
    void postedTransactionDescriptionSurvivesTheScheduler() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRule(token, categoryId, "12.00", "MONTHLY", today(),
                optional("description", "Music streaming")).get("id").asLong();

        postDue(today());
        Long transactionId = generatedTransactionIds(ruleId).get(0);

        assertThat(columnInDatabase(transactionId, "transactions", "description"))
                .isNotEqualTo("Music streaming");
        assertThat(decryptField(columnInDatabase(transactionId, "transactions", "description")))
                .isEqualTo("Music streaming");
        assertThat(send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getBody()).contains("Music streaming");
    }

    @Test
    @DisplayName("BR-16: running the scheduler twice over the same period posts nothing the second time")
    void runningTwicePostsOneOccurrencePerPeriod() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        postDue(today());
        int afterFirst = occurrenceCount(ruleId);
        String cursorAfterFirst = columnInDatabase(ruleId, "recurring_rules", "next_run_date");

        postDue(today());

        assertThat(afterFirst).isEqualTo(1);
        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
        assertThat(generatedTransactionCount(ruleId)).isEqualTo(1);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(cursorAfterFirst);
    }

    @Test
    @DisplayName("UC-09 A1: a run after downtime posts every missed period, in order, exactly once each")
    void catchUpPostsEveryMissedPeriod() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(4);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);

        postDue(today());

        assertThat(occurrenceCount(ruleId)).isEqualTo(5);
        assertThat(generatedTransactionIds(ruleId)).hasSize(5);
        assertThat(txnDatesFor(ruleId)).containsExactly(
                start.toString(),
                start.plusMonths(1).toString(),
                start.plusMonths(2).toString(),
                start.plusMonths(3).toString(),
                today().toString());
        assertThat(columnInDatabase(ruleId, "recurring_rules", "last_run_date"))
                .isEqualTo(today().toString());
    }

    @Test
    @DisplayName("BR-16: concurrent runs of the scheduler still produce one transaction per period")
    void concurrentSchedulerRunsAreIdempotent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY",
                today().minusMonths(2));

        recurringProcedureDao.postDueOccurrences(today());
        int afterSerialRun = occurrenceCount(ruleId);
        assertThat(afterSerialRun).isEqualTo(3);

        int attempts = 4;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        recurringProcedureDao.postDueOccurrences(today());
                    } catch (RuntimeException ex) {

                        return ex.getClass().getSimpleName();
                    }
                    return null;
                }));
            }
            List<String> failures = new ArrayList<>();
            for (Future<?> result : results) {
                String failure = (String) result.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            assertThat(failures).as("only lock contention may be tolerated")
                    .allMatch(name -> name.equals("CannotAcquireLockException"));
        } finally {
            pool.shutdownNow();
        }

        assertThat(occurrenceCount(ruleId)).isEqualTo(afterSerialRun);
        assertThat(generatedTransactionCount(ruleId)).isEqualTo(afterSerialRun);
    }

    @Test
    @DisplayName("UC-09: a paused rule posts nothing, and resumes where it left off")
    void aPausedRulePostsNothing() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        postDue(today());
        assertThat(occurrenceCount(ruleId)).isZero();

        assertThat(patchRule(token, ruleId, Map.of("status", "ACTIVE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        postDue(today());

        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
        assertThat(txnDatesFor(ruleId)).containsExactly(today().toString());
    }

    @Test
    @DisplayName("UC-09: a pause freezes the cursor, so the periods it covered are posted when the "
            + "rule resumes")
    void pauseDefersPeriodsRatherThanSkippingThem() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(3);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);

        postDue(start);
        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        postDue(start.plusMonths(2));
        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
        assertThat(generatedTransactionCount(ruleId)).isEqualTo(1);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(start.plusMonths(1).toString());

        assertThat(patchRule(token, ruleId, Map.of("status", "ACTIVE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        postDue(today());

        assertThat(periodKeys(ruleId)).containsExactly(
                monthKey(start), monthKey(start.plusMonths(1)),
                monthKey(start.plusMonths(2)), monthKey(start.plusMonths(3)));
        assertThat(generatedTransactionCount(ruleId)).isEqualTo(4);
        assertThat(txnDatesFor(ruleId)).containsExactly(
                start.toString(), start.plusMonths(1).toString(),
                start.plusMonths(2).toString(), start.plusMonths(3).toString());
    }

    @Test
    @DisplayName("UC-09: catch-up is bounded at 500 periods and the rule is left advanced, not stuck")
    void catchUpIsBoundedAndSettles() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        LocalDate start = today().minusDays(700);
        Long ruleId = createRuleAndReturnId(token, categoryId, "1.00", "DAILY", start);

        postDue(today());

        assertThat(occurrenceCount(ruleId)).isEqualTo(500);
        String cursorAfterFirst = columnInDatabase(ruleId, "recurring_rules", "next_run_date");
        assertThat(cursorAfterFirst).isEqualTo(start.plusDays(500).toString());

        postDue(today());
        assertThat(occurrenceCount(ruleId)).isEqualTo(701);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(today().plusDays(1).toString());

        int afterSettling = occurrenceCount(ruleId);
        postDue(today());
        assertThat(occurrenceCount(ruleId)).isEqualTo(afterSettling);
    }

    @Test
    @DisplayName("UC-09: an ended rule posts nothing, even if its cursor is still in the past")
    void anEndedRulePostsNothing() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(patchRule(token, ruleId, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        postDue(today());

        assertThat(occurrenceCount(ruleId)).isZero();
        assertThat(generatedTransactionCount(ruleId)).isZero();
    }

    @Test
    @DisplayName("UC-09: a rule whose category is retired posts nothing until the category returns")
    void aRuleOnARetiredCategoryPostsNothing() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Paused category", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        retire(token, categoryId, true);
        postDue(today());

        assertThat(occurrenceCount(ruleId)).isZero();
        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(today().toString());

        retire(token, categoryId, false);
        postDue(today());
        assertThat(occurrenceCount(ruleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-09: the scheduler stops at the end date and marks the rule ENDED")
    void theSchedulerHonoursTheEndDate() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(4);
        LocalDate end = today().minusMonths(2);

        JsonNode created = createRule(token, categoryId, "10.00", "MONTHLY", start,
                optional("endDate", end.toString()));
        Long ruleId = created.get("id").asLong();

        postDue(today());

        assertThat(txnDatesFor(ruleId)).containsExactly(
                start.toString(), start.plusMonths(1).toString(), end.toString());

        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("ENDED");
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("UC-09: the scheduler never writes a row dated after the day it was given")
    void theSchedulerNeverWritesTheFuture() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "DAILY", today().minusDays(3));

        postDue(today());

        assertThat(txnDatesFor(ruleId)).containsExactly(
                today().minusDays(3).toString(),
                today().minusDays(2).toString(),
                today().minusDays(1).toString(),
                today().toString());

        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(today().plusDays(1).toString());

        JsonNode listed = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL, token, null).getBody());
        assertThat(ids(listed)).hasSize(4);
    }

    @Test
    @DisplayName("UC-09: a rule that is not due yet is left alone")
    void aRuleThatIsNotDueIsNotPosted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today().plusDays(5));

        postDue(today());

        assertThat(occurrenceCount(ruleId)).isZero();
        assertThat(columnInDatabase(ruleId, "recurring_rules", "next_run_date"))
                .isEqualTo(today().plusDays(5).toString());
    }

    @Test
    @DisplayName("UC-09: a catch-up run posts many periods as separate transactions, one per month")
    void catchUpCreatesOneTransactionPerPeriodKey() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(3);
        Long ruleId = createRuleAndReturnId(token, categoryId, "7.50", "MONTHLY", start);

        postDue(today());

        Long otherRule = createRuleAndReturnId(token, categoryId, "7.50", "MONTHLY", start);
        postDue(today());

        assertThat(occurrenceCount(ruleId)).isEqualTo(4);
        assertThat(occurrenceCount(otherRule)).isEqualTo(4);
        assertThat(periodKeys(ruleId)).hasSize(4);
    }

    @Test
    @DisplayName("UC-09: an interval of 2 skips a period, and the skipped period is never posted")
    void aSkippedIntervalIsNeverPosted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(4);
        JsonNode created = createRule(token, categoryId, "10.00", "MONTHLY", start,
                optional("intervalCount", 2));
        Long ruleId = created.get("id").asLong();

        postDue(today());

        assertThat(txnDatesFor(ruleId)).containsExactly(
                start.toString(), start.plusMonths(2).toString(), start.plusMonths(4).toString());
    }

    @Test
    @DisplayName("VĐ-10: the scheduler's date comes from the database's +07:00 clock, not the JVM's")
    void theSchedulerUsesTheDatabaseClock() throws Exception {

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT CURDATE()");
             ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject(1).toString()).isEqualTo(today().toString());
        }
    }

    @Test
    @DisplayName("VĐ-10: the scheduler's own cron is in Asia/Ho_Chi_Minh, so its default run is "
            + "aligned with the database clock")
    void theSchedulerCronIsPinnedToTheApplicationZone() throws Exception {

        Scheduled scheduled = RecurringScheduler.class.getMethod("postDueOccurrences")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).as("@Scheduled must be present on the timer method").isNotNull();
        assertThat(scheduled.zone())
                .as("the cron must be evaluated in the application's zone, not the JVM default")
                .isEqualTo(APPLICATION_ZONE.getId());

        assertThat(scheduled.cron())
                .isEqualTo("${campuscoin.recurring.scheduler.cron:0 5 0 * * *}");
    }

    @Test
    @DisplayName("BR-05 / BR-02 / BR-07: the trigger refuses a bad rule written straight to the table")
    void theTriggerIsTheAuthorityNotASecondOpinion() throws Exception {
        String owner = loginNewStudent();
        String other = loginNewStudent();
        Long ownerId = userIdOfNewStudent(owner);
        Long otherId = userIdOfNewStudent(other);
        Long ownCategory = createCategory(owner, "Mine", "EXPENSE", null, null);
        Long foreignCategory = createCategory(other, "Theirs", "EXPENSE", null, null);
        Long retiredCategory = createCategory(owner, "Retired", "EXPENSE", null, null);
        retire(owner, retiredCategory, true);

        LocalDate start = today();

        assertThat(insertRuleDirectly(ownerId, ownCategory, "INCOME", start))
                .as("BR-05: the type must match the category's")
                .contains("45000");
        assertThat(insertRuleDirectly(ownerId, foreignCategory, "EXPENSE", start))
                .as("BR-02: the category belongs to another student")
                .contains("45000");
        assertThat(insertRuleDirectly(ownerId, retiredCategory, "EXPENSE", start))
                .as("BR-07: the category has been disabled")
                .contains("45000");
        assertThat(insertRuleDirectly(ownerId, 999_999_999L, "EXPENSE", start))
                .as("BR-05: the category does not exist")
                .contains("45000");

        assertThat(ruleCountForUser(ownerId)).isZero();
        assertThat(ruleCountForUser(otherId)).isZero();
    }

    @Test
    @DisplayName("ck_recurring_amount / ck_recurring_interval / ck_recurring_dates refuse a bad row directly")
    void theCheckConstraintsHoldForEveryCaller() throws Exception {
        String owner = loginNewStudent();
        Long ownerId = userIdOfNewStudent(owner);
        Long categoryId = createCategory(owner, "Checks", "EXPENSE", null, null);
        LocalDate start = today();

        assertThat(insertRow(ownerId, categoryId, "0.00", 1, start, null))
                .as("ck_recurring_amount requires a positive amount").contains("ck_recurring_amount");
        assertThat(insertRow(ownerId, categoryId, "10.00", 0, start, null))
                .as("ck_recurring_interval requires at least one period")
                .contains("ck_recurring_interval");
        assertThat(insertRow(ownerId, categoryId, "10.00", 1, start, start.minusDays(1)))
                .as("ck_recurring_dates requires the end date on or after the start")
                .contains("ck_recurring_dates");

        assertThat(ruleCountForUser(ownerId)).isZero();
    }

    @Test
    @DisplayName("A refused row inside a transaction rolls back the whole statement, not just itself")
    void aRefusedWriteRollsBackWithinItsTransaction() throws Exception {
        String owner = loginNewStudent();
        Long ownerId = userIdOfNewStudent(owner);
        Long categoryId = createCategory(owner, "Atomic", "EXPENSE", null, null);
        LocalDate start = today();

        try (Connection connection = openDatabaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement first = connection.prepareStatement(
                    "INSERT INTO recurring_rules (user_id, category_id, type, amount, frequency, "
                            + "interval_count, start_date, next_run_date, status) "
                            + "VALUES (?, ?, 'EXPENSE', 10.00, 'MONTHLY', 1, ?, ?, 'ACTIVE')")) {
                first.setLong(1, ownerId);
                first.setLong(2, categoryId);
                first.setObject(3, start);
                first.setObject(4, start);
                assertThat(first.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement second = connection.prepareStatement(
                    "INSERT INTO recurring_rules (user_id, category_id, type, amount, frequency, "
                            + "interval_count, start_date, next_run_date, status) "
                            + "VALUES (?, ?, 'INCOME', 10.00, 'MONTHLY', 1, ?, ?, 'ACTIVE')")) {
                second.setLong(1, ownerId);
                second.setLong(2, categoryId);
                second.setObject(3, start);
                second.setObject(4, start);
                second.executeUpdate();
                throw new AssertionError("the trigger must refuse a rule whose type disagrees");
            } catch (SQLException expected) {
                connection.rollback();
            }
        }

        assertThat(ruleCountForUser(ownerId)).isZero();
    }

    @Test
    @DisplayName("Section 7.5: no endpoint here is reachable without a token")
    void everyEndpointRequiresAToken() throws Exception {
        assertThat(send(HttpMethod.GET, RULES_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, RULES_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, RULES_URL, null, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, RULES_URL + "/1", null, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, RULES_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.5: an administrator is refused here, because UC-09 is a student's own rules")
    void anAdminTokenIsForbidden() throws Exception {
        String adminToken = adminLogin();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertThat(send(HttpMethod.GET, RULES_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, RULES_URL, adminToken,
                createBody(categoryId, "10.00", "MONTHLY", today().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.GET, RULES_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patchRule(adminToken, 1L, Map.of("amount", "1.00")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.DELETE, RULES_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("BR-03: a revoked session cannot read or write rules")
    void aRevokedTokenIsRejected() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(send(HttpMethod.POST, LOGOUT_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(send(HttpMethod.GET, RULES_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, RULES_URL + "/" + ruleId, token, Map.of("amount", "1.00"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(columnInDatabase(ruleId, "recurring_rules", "amount")).isEqualTo("10.00");
    }

    @Test
    @DisplayName("UC-02 A2: a disabled account cannot read or write rules")
    void aDisabledAccountIsRejected() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        setAccountStatus(userId, "DISABLED");

        assertThat(send(HttpMethod.GET, RULES_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ruleExists(ruleId)).isTrue();
    }

    @Test
    @DisplayName("Section 7.7: no response here exposes an owner, a token or an internal identifier")
    void noResponseLeaksInternals() throws Exception {
        String token = loginNewStudent();
        String email = "recurring.leak." + UUID.randomUUID() + "@student.campuscoin.edu";
        String ownerToken = loginNewStudent();
        Long categoryId = createCategory(ownerToken, "Leak check", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(ownerToken, categoryId, "10.00", "MONTHLY", today());
        assertThat(email).isNotBlank();

        String listBody = send(HttpMethod.GET, RULES_URL, ownerToken, null).getBody();
        assertThat(listBody).doesNotContain("passwordHash", "password_hash", "tokenVersion",
                "recurring_rules", "user_id", "\"userId\"");

        String singleBody = send(HttpMethod.GET, RULES_URL + "/" + ruleId, ownerToken, null).getBody();
        assertThat(singleBody).doesNotContain("passwordHash", "tokenVersion", "\"userId\"");

        String errorBody = send(HttpMethod.PATCH, RULES_URL + "/" + ruleId,
                ownerToken, Map.of("status", "NOPE")).getBody();
        assertThat(errorBody).doesNotContain("recurring_rules", "SQLSTATE", "Exception",
                "org.springframework", "java.");
    }

    @Test
    @DisplayName("Section 19: every error here uses the documented shape and the `field` property")
    void errorShapeIsConsistent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        JsonNode validation = objectMapper.readTree(
                patchRule(token, ruleId, Map.of("amount", "-1.00")).getBody());
        assertThat(fieldNamesOfValidationError(validation)).contains("amount");

        JsonNode notFound = objectMapper.readTree(
                send(HttpMethod.GET, RULES_URL + "/999999999", token, null).getBody());
        assertThat(notFound.get("status").asInt()).isEqualTo(404);
        assertThat(notFound.get("errorCode").asText()).isEqualTo("NOT_FOUND");
        assertThat(notFound.get("path").asText()).isEqualTo(RULES_URL + "/999999999");
        assertThat(notFound.get("timestamp").isTextual()).isTrue();

        JsonNode conflict = objectMapper.readTree(
                send(HttpMethod.DELETE, RULES_URL + "/999999999", token, null).getBody());
        assertThat(conflict.get("errorCode").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Section 7.7: one read of another student's rule and one of a missing rule look identical")
    void notFoundAndNotYoursAreIndistinguishable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long foreignRule = createRuleAndReturnId(owner, categoryId, "10.00", "MONTHLY", today());

        String foreignBody = send(HttpMethod.GET, RULES_URL + "/" + foreignRule, intruder, null)
                .getBody();
        String missingBody = send(HttpMethod.GET, RULES_URL + "/999999999", intruder, null)
                .getBody();

        assertThat(objectMapper.readTree(foreignBody).get("errorCode").asText())
                .isEqualTo(objectMapper.readTree(missingBody).get("errorCode").asText());
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo(objectMapper.readTree(missingBody).get("message").asText());
    }

    @Test
    @DisplayName("UC-09: two simultaneous deletes of a never-posted rule produce one deletion")
    void twoSimultaneousDeletesProduceOneDeletion() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today().plusDays(2));

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null);
                }));
            }

            int deleted = 0;
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
                    deleted++;
                    continue;
                }

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
            assertThat(deleted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ruleExists(ruleId)).isFalse();
    }

    @Test
    @DisplayName("UC-09: two simultaneous pauses of one rule both report the state that won")
    void twoSimultaneousUpdatesBothSucceedOnTheLockedRow() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return patchRule(token, ruleId, Map.of("status", "PAUSED"));
                }));
            }

            for (Future<ResponseEntity<String>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("UC-09: creating the same rule twice makes two rules, because a schedule is not unique")
    void creatingTheSameRuleTwiceIsAllowedAndIsNotIdempotent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today();

        Long first = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);
        Long second = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);

        assertThat(first).isNotEqualTo(second);
        assertThat(ids(listRules(token))).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("UC-09: a pause racing a scheduler run neither posts what was paused nor loses the "
            + "status")
    void aPauseRacingTheSchedulerSettlesConsistently() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        ResponseEntity<String>[] pauseResult = new ResponseEntity[1];
        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                pauseResult[0] = patchRule(token, ruleId, Map.of("status", "PAUSED"));
                return null;
            }));
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    recurringProcedureDao.postDueOccurrences(today());
                } catch (RuntimeException ex) {

                    return ex.getClass().getSimpleName();
                }
                return null;
            }));
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(pauseResult[0].getStatusCode()).isEqualTo(HttpStatus.OK);

        postDue(today());

        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("PAUSED");

        String nextRunDate = columnInDatabase(ruleId, "recurring_rules", "next_run_date");
        if (today().toString().equals(nextRunDate)) {
            assertThat(occurrenceCount(ruleId))
                    .as("a cursor that has not moved means the run posted nothing")
                    .isZero();
        } else {
            assertThat(nextRunDate).isEqualTo(today().plusMonths(1).toString());
            assertThat(occurrenceCount(ruleId))
                    .as("a cursor that has moved means the run committed its period")
                    .isEqualTo(1);
        }
        assertThat(orphanOccurrenceCount(ruleId)).isZero();
    }

    @Test
    @DisplayName("UC-09: ending a rule racing a scheduler run never posts a period after the end")
    void anEndRacingTheSchedulerNeverPostsAfterEnding() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY",
                today().minusMonths(3));

        ResponseEntity<String>[] endResult = new ResponseEntity[1];
        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                endResult[0] = patchRule(token, ruleId, Map.of("status", "ENDED"));
                return null;
            }));
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    recurringProcedureDao.postDueOccurrences(today());
                } catch (RuntimeException ex) {
                    return ex.getClass().getSimpleName();
                }
                return null;
            }));
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(endResult[0].getStatusCode()).isEqualTo(HttpStatus.OK);

        int postedWhileRacing = occurrenceCount(ruleId);

        postDue(today());
        postDue(today().plusMonths(1));
        assertThat(columnInDatabase(ruleId, "recurring_rules", "status")).isEqualTo("ENDED");
        assertThat(occurrenceCount(ruleId)).isEqualTo(postedWhileRacing);
        assertThat(orphanOccurrenceCount(ruleId)).isZero();
    }

    @Test
    @DisplayName("UC-09: deleting a rule racing a scheduler run leaves no occurrence without a "
            + "transaction")
    void aDeleteRacingTheSchedulerLeavesNoOrphanOccurrence() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        ResponseEntity<String>[] deleteResult = new ResponseEntity[1];
        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                deleteResult[0] = send(HttpMethod.DELETE, RULES_URL + "/" + ruleId, token, null);
                return null;
            }));
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    recurringProcedureDao.postDueOccurrences(today());
                } catch (RuntimeException ex) {
                    return ex.getClass().getSimpleName();
                }
                return null;
            }));
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        HttpStatus deleteStatus = (HttpStatus) deleteResult[0].getStatusCode();
        assertThat(deleteStatus).isIn(HttpStatus.NO_CONTENT, HttpStatus.CONFLICT);

        if (deleteStatus == HttpStatus.NO_CONTENT) {

            assertThat(occurrenceCount(ruleId)).isZero();
            assertThat(ruleExists(ruleId)).isFalse();
        } else {

            assertThat(ruleExists(ruleId)).isTrue();
            assertThat(generatedTransactionCount(ruleId)).isEqualTo(occurrenceCount(ruleId));
            assertThat(orphanOccurrenceCount(ruleId)).isZero();
        }
    }

    @Test
    @DisplayName("Section 16: the recurring module does not disturb the transaction endpoints")
    void transactionEndpointsStillBehaveAfterScheduledPosting() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        Long before = createTransaction(token, categoryId, "5.00", today().minusDays(1));
        postDue(today());
        Long after = createTransaction(token, categoryId, "6.00", today());

        JsonNode listed = objectMapper.readTree(
                send(HttpMethod.GET, TRANSACTIONS_URL, token, null).getBody());
        assertThat(ids(listed)).contains(after, before,
                generatedTransactionIds(ruleId).get(0));

        Long scheduled = generatedTransactionIds(ruleId).get(0);
        assertThat(patchTransaction(token, scheduled, Map.of("amount", "11.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Section 16: retiring a category through module 3 still blocks and unblocks its rules")
    void categoryRetirementInteractsAsDocumented() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Interop", "EXPENSE", null, null);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/" + categoryId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        retire(token, categoryId, true);
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ACTIVE");
        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("UC-09 B4: a transaction the scheduler creates raises the same budget alert as a "
            + "hand-entered one")
    void aScheduledTransactionRaisesTheBudgetAlertLikeAnyOther() throws Exception {

        String token = loginNewStudent();
        Long userId = userIdOfNewStudent(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate today = today();

        Long budgetId = createBudget(userId, categoryId, today.withDayOfMonth(1),
                new BigDecimal("30.00"));

        Long firstRule = createRuleAndReturnId(token, categoryId, "24.00", "MONTHLY", today);
        Long secondRule = createRuleAndReturnId(token, categoryId, "24.00", "MONTHLY", today);
        postDue(today());

        for (Long ruleId : List.of(firstRule, secondRule)) {
            Long scheduledTransaction = generatedTransactionIds(ruleId).get(0);
            assertThat(columnInDatabase(scheduledTransaction, "transactions", "source"))
                    .isEqualTo("RECURRING");
        }

        assertThat(alertRowsFor(budgetId))
                .containsExactly("NEAR|80.00", "EXCEEDED|160.00");
        assertThat(notificationTypesFor(userId))
                .containsExactly("BUDGET_NEAR", "BUDGET_EXCEEDED");
    }

    @Test
    @DisplayName("BR-16 / UC-09 A2: editing a rule after an occurrence leaves that occurrence's "
            + "transaction alone and applies the new values to later periods")
    void editingARuleAfterAnOccurrenceAppliesToFuturePeriodsOnly() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate start = today().minusMonths(2);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", start);

        postDue(start.plusMonths(1));
        List<Long> posted = generatedTransactionIds(ruleId);
        assertThat(posted).hasSize(2);
        Long firstTransaction = posted.get(0);
        Long secondTransaction = posted.get(1);

        assertThat(patchRule(token, ruleId, Map.of("amount", "25.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(columnInDatabase(firstTransaction, "transactions", "amount")).isEqualTo("10.00");
        assertThat(columnInDatabase(secondTransaction, "transactions", "amount")).isEqualTo("10.00");
        assertThat(historyActions(firstTransaction)).containsExactly("CREATE");

        postDue(start.plusMonths(2));
        List<Long> all = generatedTransactionIds(ruleId);
        assertThat(all).hasSize(3);
        assertThat(columnInDatabase(all.get(2), "transactions", "amount")).isEqualTo("25.00");
        assertThat(all.subList(0, 2)).containsExactlyElementsOf(posted);
        assertThat(occurrenceCount(ruleId)).isEqualTo(3);
    }

    @Test
    @DisplayName("UC-09: the lifecycle is ACTIVE<->PAUSED, either to ENDED, and ENDED is terminal")
    void theLifecycleAllowsExactlyTheDocumentedTransitions() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ACTIVE");
        assertThat(patchRule(token, ruleId, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchRule(token, ruleId, Map.of("status", "ACTIVE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Long pausedThenEnded = createRuleAndReturnId(token, categoryId, "11.00", "MONTHLY",
                today().plusDays(1));
        assertThat(patchRule(token, pausedThenEnded, Map.of("status", "PAUSED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchRule(token, pausedThenEnded, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, pausedThenEnded, "status")).isEqualTo("ENDED");

        Long activeThenEnded = createRuleAndReturnId(token, categoryId, "12.00", "MONTHLY",
                today().plusDays(2));
        assertThat(patchRule(token, activeThenEnded, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        for (String target : new String[] {"ACTIVE", "PAUSED"}) {
            ResponseEntity<String> response =
                    patchRule(token, activeThenEnded, Map.of("status", target));
            assertThat(response.getStatusCode()).as("target=%s", target)
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                    .as("target=%s", target).isEqualTo("RECURRING_RULE_ENDED");
            assertThat(columnInDatabase(activeThenEnded, "recurring_rules", "status"))
                    .isEqualTo("ENDED");
        }

        assertThat(patchRule(token, activeThenEnded, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-09: an ended rule still refuses edits that are not a status change")
    void anEndedRuleStillAcceptsEditsThatAreNotAStatusChange() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long ruleId = createRuleAndReturnId(token, categoryId, "10.00", "MONTHLY", today());

        assertThat(patchRule(token, ruleId, Map.of("status", "ENDED")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(patchRule(token, ruleId, Map.of("description", "kept for the record"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ruleField(token, ruleId, "status")).isEqualTo("ENDED");
    }

    @SafeVarargs
    private JsonNode createRule(String token, Long categoryId, String amount, String frequency,
                                LocalDate startDate, Map.Entry<String, Object>... extra)
            throws Exception {
        Map<String, Object> body = createBody(categoryId, amount, frequency, startDate.toString(),
                extra);

        ResponseEntity<String> response = send(HttpMethod.POST, RULES_URL, token, body);
        assertThat(response.getStatusCode())
                .as("create body=%s", body)
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private static Map.Entry<String, Object> optional(String key, Object value) {
        return Map.entry(key, value);
    }

    private Long createRuleAndReturnId(String token, Long categoryId, String amount,
                                       String frequency, LocalDate startDate) throws Exception {
        return createRule(token, categoryId, amount, frequency, startDate).get("id").asLong();
    }

    @SafeVarargs
    private static Map<String, Object> createBody(Long categoryId, Object amount, Object frequency,
                                                 String startDate,
                                                 Map.Entry<String, Object>... extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        if (amount != null) {
            body.put("amount", amount);
        }
        if (frequency != null) {
            body.put("frequency", frequency);
        }
        if (startDate != null) {
            body.put("startDate", startDate);
        }

        for (Map.Entry<String, Object> entry : extra) {
            if (entry != null) {
                body.put(entry.getKey(), entry.getValue());
            }
        }
        return body;
    }

    private ResponseEntity<String> patchRule(String token, Long ruleId, Object body) {
        return send(HttpMethod.PATCH, RULES_URL + "/" + ruleId, token, body);
    }

    private ResponseEntity<String> patchTransaction(String token, Long transactionId, Object body) {
        return send(HttpMethod.PATCH, TRANSACTIONS_URL + "/" + transactionId, token, body);
    }

    private Long createTransaction(String token, Long categoryId, String amount, LocalDate date) {
        ResponseEntity<String> response = send(HttpMethod.POST, TRANSACTIONS_URL, token,
                Map.of("categoryId", categoryId, "amount", amount, "txnDate", date.toString()));
        assertThat(response.getStatusCode())
                .as("transaction body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(response.getBody()).get("id").asLong();
        } catch (Exception ex) {
            throw new AssertionError("unreadable transaction response", ex);
        }
    }

    private JsonNode listRules(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private String rawRule(String token, Long ruleId) {
        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL + "/" + ruleId, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode jsonRule(String token, Long ruleId) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, RULES_URL + "/" + ruleId, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private String ruleField(String token, Long ruleId, String field) throws Exception {
        JsonNode value = jsonRule(token, ruleId).get(field);
        assertThat(value).as("field %s must be present", field).isNotNull();
        return value.asText();
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(entry -> ids.add(entry.get("id").asLong()));
        return ids;
    }

    private static List<String> fieldNamesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertFieldError(String token, HttpMethod method, String url, Object body,
                                  String expectedField, HttpStatus expectedStatus) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);

        assertThat(response.getStatusCode()).as("body=%s", body).isEqualTo(expectedStatus);
        JsonNode parsed = objectMapper.readTree(response.getBody());

        if (expectedField == null) {

            assertThat(parsed.get("errorCode").asText()).isEqualTo("NOT_FOUND");
            return;
        }

        assertThat(parsed.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        assertThat(fieldNamesOfValidationError(parsed)).as("body=%s", body).contains(expectedField);
    }

    private static List<String> fieldNamesOfValidationError(JsonNode error) {
        List<String> names = new ArrayList<>();
        error.get("fieldErrors").forEach(fieldError -> names.add(fieldError.get("field").asText()));
        return names;
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

    private Long userIdOfNewStudent(String token) throws Exception {
        return objectMapper.readTree(
                send(HttpMethod.GET, PROFILE_URL, token, null).getBody()).get("id").asLong();
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

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

    private void retire(String token, Long categoryId, boolean inactive) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("isActive", !inactive));
        assertThat(response.getStatusCode())
                .as("retire body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(categoryId, "categories", "is_active"))
                .isEqualTo(inactive ? "0" : "1");
    }

    private Long defaultCategoryId(String name) throws Exception {
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

    private Long createBudget(Long userId, Long categoryId, LocalDate periodMonth, BigDecimal limit)
            throws Exception {
        runInDatabase("CALL sp_validate_budget(?, ?)", userId, categoryId);
        runInDatabase("INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                + "VALUES (?, ?, ?, ?)", userId, categoryId, periodMonth, limit);
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

    private List<String> alertRowsFor(Long budgetId) throws Exception {
        return stringsFrom("SELECT CONCAT(threshold_type, '|', consumed_pct) FROM budget_alert_log "
                + "WHERE budget_id = ? ORDER BY id", budgetId);
    }

    private List<String> notificationTypesFor(Long userId) throws Exception {
        return stringsFrom("SELECT type FROM notifications WHERE user_id = ? ORDER BY id", userId);
    }

    private void setAccountStatus(Long userId, String status) throws Exception {
        runInDatabase("UPDATE users SET status = ? WHERE id = ?", status, userId);
    }

    private void postDue(LocalDate asOf) {
        recurringProcedureDao.postDueOccurrences(asOf);
    }

    private int occurrenceCount(Long ruleId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM recurring_occurrences WHERE rule_id = ?")) {
            statement.setLong(1, ruleId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private List<String> periodKeys(Long ruleId) throws Exception {
        return stringsFrom("SELECT period_key FROM recurring_occurrences WHERE rule_id = ? "
                + "ORDER BY scheduled_date, id", ruleId);
    }

    private int orphanOccurrenceCount(Long ruleId) throws Exception {
        return countOf("SELECT COUNT(*) FROM recurring_occurrences "
                + "WHERE rule_id = ? AND transaction_id IS NULL", ruleId);
    }

    private List<Long> generatedTransactionIds(Long ruleId) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM transactions WHERE recurring_rule_id = ? "
                             + "ORDER BY txn_date, id")) {
            statement.setLong(1, ruleId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
        }
        return ids;
    }

    private List<String> txnDatesFor(Long ruleId) throws Exception {
        return stringsFrom("SELECT txn_date FROM transactions WHERE recurring_rule_id = ? "
                + "ORDER BY txn_date, id", ruleId);
    }

    private int generatedTransactionCount(Long ruleId) throws Exception {
        return generatedTransactionIds(ruleId).size();
    }

    private List<String> historyActions(Long transactionId) throws Exception {
        return stringsFrom("SELECT action FROM transaction_history WHERE transaction_id = ? "
                + "ORDER BY id", transactionId);
    }

    private String historyJson(Long transactionId, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM transaction_history WHERE transaction_id = ? "
                             + "ORDER BY id LIMIT 1")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private boolean ruleExists(Long ruleId) throws Exception {
        return countOf("SELECT COUNT(*) FROM recurring_rules WHERE id = ?", ruleId) > 0;
    }

    private boolean transactionExists(Long transactionId) throws Exception {
        return countOf("SELECT COUNT(*) FROM transactions WHERE id = ?", transactionId) > 0;
    }

    private int ruleCountForUser(Long userId) throws Exception {
        return countOf("SELECT COUNT(*) FROM recurring_rules WHERE user_id = ?", userId);
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

    private String rowSnapshot(Long ruleId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT user_id, category_id, type, amount, description, frequency, "
                             + "interval_count, start_date, end_date, next_run_date, last_run_date, "
                             + "status FROM recurring_rules WHERE id = ?")) {
            statement.setLong(1, ruleId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                StringBuilder snapshot = new StringBuilder();
                for (int index = 1; index <= 12; index++) {
                    snapshot.append(row.getString(index)).append('|');
                }
                return snapshot.toString();
            }
        }
    }

    private static String withoutAmountAndTimestamp(String snapshot) {
        String[] parts = snapshot.split("\\|", -1);
        parts[3] = "";
        return String.join("|", parts);
    }

    private int countOf(String sql, Object parameter) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private List<String> stringsFrom(String sql, Object parameter) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private void runInDatabase(String sql, Object... parameters) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private String insertRuleDirectly(Long userId, Long categoryId, String type, LocalDate startDate)
            throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO recurring_rules (user_id, category_id, type, amount, frequency, "
                             + "interval_count, start_date, next_run_date, status) "
                             + "VALUES (?, ?, ?, 10.00, 'MONTHLY', 1, ?, ?, 'ACTIVE')")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setString(3, type);
            statement.setObject(4, startDate);
            statement.setObject(5, startDate);
            statement.executeUpdate();
            return "inserted";
        } catch (SQLException ex) {

            return ex.getSQLState();
        }
    }

    private String insertRow(Long userId, Long categoryId, String amount, int intervalCount,
                             LocalDate startDate, LocalDate endDate) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO recurring_rules (user_id, category_id, type, amount, frequency, "
                             + "interval_count, start_date, end_date, next_run_date, status) "
                             + "VALUES (?, ?, 'EXPENSE', ?, 'MONTHLY', ?, ?, ?, ?, 'ACTIVE')")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setBigDecimal(3, new BigDecimal(amount));
            statement.setInt(4, intervalCount);
            statement.setObject(5, startDate);
            statement.setObject(6, endDate);
            statement.setObject(7, startDate);
            statement.executeUpdate();
            return "inserted";
        } catch (SQLException ex) {
            return ex.getMessage() == null ? "" : ex.getMessage();
        }
    }

    private static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    private static String monthKey(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private String randomEmail() {
        return "recurring." + UUID.randomUUID() + "@student.campuscoin.edu";
    }
}
