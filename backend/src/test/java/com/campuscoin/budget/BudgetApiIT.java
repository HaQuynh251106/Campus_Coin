package com.campuscoin.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-13 over the real HTTP stack: request, security filter, controller, service, repository, MySQL.
 *
 * <p>Every test registers its own student with a random address, so no test depends on another's
 * data. The seeded default categories are read but never modified; where a test needs a category it
 * can retire, it creates one of its own.
 *
 * <p><b>What this suite exists to prove, and what it deliberately leaves elsewhere.</b> The parts a
 * service-level test cannot reach are here: that another student's limit is unreachable through every
 * endpoint and indistinguishable from a missing one, that an administrator token is refused outright,
 * that the consumption figures come from {@code v_budget_consumption} rather than from a second
 * {@code SUM} written in Java, that BR-11's unique key holds when two requests race, that the row
 * lock makes two simultaneous edits settle on one value, and that BR-07 and BR-11 are refusals the
 * database makes for every caller rather than checks the service happens to perform.
 *
 * <p><b>UC-14 is not tested here.</b> A budget write raises no alert, and this module's own
 * documentation says so - the alert belongs to the transaction that crossed the threshold, which is
 * {@code TransactionApiIT}'s subject and is verified there. What this suite does verify about alerts
 * is the boundary: that creating, editing and deleting a limit writes no notification and no
 * {@code budget_alert_log} row. {@code NotificationApiIT} covers reading them.
 */
class BudgetApiIT extends AbstractBudgetApiIT {

    // ==================================================================
    //  UC-13 read: the list and the single read
    // ==================================================================

    @Test
    @DisplayName("UC-13: a student with no limits gets an empty list, not an error and not null")
    void emptyStateIsAnEmptyArray() throws Exception {
        // The empty state is one the screen has to render, and an endpoint that answered 404 or null
        // would make the client special-case a student who has simply not set anything up yet.
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, BUDGETS_URL, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(response);
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("UC-13: a newly set limit comes back with exactly the documented fields and no owner")
    void createdBudgetHasTheDocumentedShape() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        JsonNode budget = createBudget(token, categoryId, "300.00", thisMonth());

        // The field set is pinned rather than sampled: a leaked `userId` appearing here would be a
        // contract change the client would not know to ignore.
        assertThat(fieldNamesOf(budget)).isSubsetOf(DOCUMENTED_BUDGET_FIELDS);
        assertThat(fieldNamesOf(budget)).contains("id", "categoryId", "categoryName", "periodMonth",
                "limitAmount", "spentAmount", "remainingAmount", "consumedPct",
                "consumptionStatus");
        assertThat(fieldNamesOf(budget)).doesNotContain("userId", "createdAt", "updatedAt");

        // Two decimal places, matching the column, so the create response and the next read agree.
        assertThat(budget.get("limitAmount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(budget.get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));
        // A month with no spending is ON_TRACK at 0%, not absent and not a division error.
        assertThat(budget.get("spentAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(budget.get("remainingAmount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(budget.get("consumedPct").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(budget.get("consumptionStatus").asText()).isEqualTo("ON_TRACK");
    }

    @Test
    @DisplayName("UC-13: the category is flattened and its name, icon and colour come from it")
    void theCategoryIsFlattenedIntoTheResponse() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Rent", "EXPENSE", "home", "#EF4444");

        JsonNode budget = createBudget(token, categoryId, "150.00", thisMonth());

        // The presentation fields come from `categories`, joined in the query rather than published
        // by the view - which is why they are asserted here and not assumed.
        assertThat(budget.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(budget.get("categoryName").asText()).isEqualTo("Rent");
        assertThat(budget.get("categoryIcon").asText()).isEqualTo("home");
        assertThat(budget.get("categoryColor").asText()).isEqualTo("#EF4444");
    }

    @Test
    @DisplayName("UC-13: the list returns the month's limits, each with its own consumption")
    void theListCarriesOneRowPerCategory() throws Exception {
        String token = loginNewStudent();
        Long foodId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long transportId = defaultCategoryId(SECOND_EXPENSE_NAME);

        createBudget(token, foodId, "100.00", thisMonth());
        createBudget(token, transportId, "50.00", thisMonth());

        // Spending in one category must not be attributed to the other: the view's join is on
        // (user, category, month), so 60 against Food leaves Transport untouched.
        createTransaction(token, foodId, "60.00", today(), "groceries");

        JsonNode list = body(send(HttpMethod.GET, BUDGETS_URL, token, null));
        assertThat(list).hasSize(2);

        JsonNode food = rowFor(list, foodId);
        JsonNode transport = rowFor(list, transportId);
        assertThat(food.get("spentAmount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(food.get("consumedPct").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(food.get("consumptionStatus").asText()).isEqualTo("ON_TRACK");
        assertThat(transport.get("spentAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(transport.get("consumptionStatus").asText()).isEqualTo("ON_TRACK");

        // Ordered by category name, which is what a student scans by, with the id as a total
        // tie-break so two identical calls cannot return the rows in different orders.
        assertThat(list.get(0).get("categoryName").asText())
                .isEqualTo(DEFAULT_EXPENSE_NAME);
        assertThat(list.get(1).get("categoryName").asText())
                .isEqualTo(SECOND_EXPENSE_NAME);
    }

    @Test
    @DisplayName("UC-13: the list reports only the month asked for")
    void theListIsScopedToTheRequestedMonth() throws Exception {
        // ck_budget_month pins period_month to the first of a month, and the view joins the month's
        // transactions on the same value - so a limit set for September must not count October's
        // spending, and a request for September must not return October's limit.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        LocalDate thisMonth = thisMonth();
        LocalDate lastMonth = thisMonth.minusMonths(1);

        createBudget(token, categoryId, "100.00", lastMonth);
        createBudget(token, categoryId, "200.00", thisMonth);

        createTransaction(token, categoryId, "30.00", lastMonth.plusDays(2), "last month");
        createTransaction(token, categoryId, "50.00", thisMonth.plusDays(2), "this month");

        JsonNode current = body(send(HttpMethod.GET, BUDGETS_URL + "?month="
                + monthKey(thisMonth), token, null));
        assertThat(current).hasSize(1);
        assertThat(current.get(0).get("limitAmount").decimalValue())
                .isEqualByComparingTo("200.00");
        assertThat(current.get(0).get("spentAmount").decimalValue())
                .isEqualByComparingTo("50.00");

        JsonNode previous = body(send(HttpMethod.GET, BUDGETS_URL + "?month="
                + monthKey(lastMonth), token, null));
        assertThat(previous).hasSize(1);
        assertThat(previous.get(0).get("limitAmount").decimalValue())
                .isEqualByComparingTo("100.00");
        assertThat(previous.get(0).get("spentAmount").decimalValue())
                .isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("UC-13: a month that does not exist is a field error, not a silent wrong month")
    void anImpossibleMonthIsRefused() throws Exception {
        // `2026-13` matches the DTO's \d{4}-\d{2} pattern, so the service is what has to notice that
        // it names no real month. Accepting it would either throw inside YearMonth or, worse, be
        // coerced into a neighbouring month and report the wrong figures.
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, BUDGETS_URL + "?month=2026-13",
                token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("UC-13: reading one limit returns the same row the list would")
    void readingOneLimitMatchesTheList() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();
        createTransaction(token, categoryId, "25.00", today(), "lunch");

        JsonNode single = body(send(HttpMethod.GET, BUDGETS_URL + "/" + budgetId, token, null));

        assertThat(single.get("id").asLong()).isEqualTo(budgetId);
        assertThat(single.get("spentAmount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(single.get("remainingAmount").decimalValue()).isEqualByComparingTo("75.00");
        assertThat(single.get("consumedPct").decimalValue()).isEqualByComparingTo("25.00");
    }

    // ==================================================================
    //  UC-13: the consumption is the database's, and reflects BR-09
    // ==================================================================

    @Test
    @DisplayName("BR-09: a deleted record stops counting toward the limit, and the status follows")
    void deletedRecordsStopCounting() throws Exception {
        // The view sums only rows with is_deleted = 0, and the status is derived from that sum on
        // every read. So the limit moves back on its own, with nothing in this module recomputing it
        // - which is the whole reason the figures are read from the view rather than summed here.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();
        Long transactionId = createTransaction(token, categoryId, "90.00", today(), "textbooks");

        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("NEAR");
        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("90.00");

        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("0.00");
        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("ON_TRACK");
    }

    @Test
    @DisplayName("UC-13/VĐ-05: the status is read from the configured thresholds, not a constant")
    void theStatusFollowsTheConfiguredThresholds() throws Exception {
        // 85% of the limit is NEAR under the seeded 80% threshold. The classification is the view's
        // CASE over system_settings, so a student at 85% is NEAR here and 85% is also what the alert
        // procedure compares - one definition of "near", read from configuration (VĐ-05).
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "85.00", today(), "at 85 percent");

        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("NEAR");
        assertThat(budgetPctOf(token, budgetId)).isEqualByComparingTo("85.00");

        // Past the limit the label is EXCEEDED and the remaining amount goes negative, rather than
        // being clamped at zero - a student needs to see how far over they are.
        createTransaction(token, categoryId, "20.00", today(), "past the limit");

        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("EXCEEDED");
        assertThat(budgetRemainingOf(token, budgetId)).isEqualByComparingTo("-5.00");
        assertThat(budgetPctOf(token, budgetId)).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("UC-13: an income record does not count toward an expense limit")
    void incomeDoesNotCountTowardsALimit() throws Exception {
        // A budget can only exist on an expense category (BR-11), and the view's SUM carries no type
        // filter because that is already guaranteed. What this proves is the other direction: the
        // student's income rows live on income categories, so they can never be added to an expense
        // limit's total even by accident.
        String token = loginNewStudent();
        Long expenseId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);
        Long budgetId = createBudget(token, expenseId, "100.00", thisMonth()).get("id").asLong();

        createTransaction(token, incomeId, "500.00", today(), "allowance");

        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("0.00");
        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("ON_TRACK");
    }

    // ==================================================================
    //  UC-13: setting a limit
    // ==================================================================

    @Test
    @DisplayName("UC-13: a limit with no month applies to the current one")
    void anOmittedMonthDefaultsToTheCurrentOne() throws Exception {
        // The common case: a "set a budget" button on a dashboard means this month. The default is
        // resolved in the application zone (VĐ-10), so the API and the database agree which month
        // "now" is rather than differing by seven hours around a month boundary.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                Map.of("categoryId", categoryId, "limitAmount", "120.00"));

        assertThat(response.getStatusCode())
                .as("body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(body(response).get("periodMonth").asText()).isEqualTo(monthKey(today()));
    }

    @Test
    @DisplayName("UC-13: a limit may be set on one of the caller's own categories, not only defaults")
    void aPersonalCategoryCanBeLimited() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Coffee", "EXPENSE", "coffee", "#92400E");

        JsonNode budget = createBudget(token, categoryId, "40.00", thisMonth());

        assertThat(budget.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(budget.get("categoryName").asText()).isEqualTo("Coffee");
    }

    @Test
    @DisplayName("BR-11: a limit cannot be set on an income category")
    void anIncomeCategoryCannotBeLimited() throws Exception {
        // A budget measures spending, and there is nothing to measure against income. The rule is
        // enforced twice - here by the service naming the field, and underneath by
        // sp_validate_budget, whose refusal is proved separately below by writing the row by hand.
        String token = loginNewStudent();
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                Map.of("categoryId", incomeId, "limitAmount", "100.00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesOfValidationError(body(response))).contains("categoryId");
    }

    @Test
    @DisplayName("BR-07: a retired category cannot be given a new limit")
    void aRetiredCategoryCannotBeLimited() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        retire(token, categoryId, true);

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                Map.of("categoryId", categoryId, "limitAmount", "100.00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesOfValidationError(body(response))).contains("categoryId");
    }

    @Test
    @DisplayName("UC-13: the limit must be present and strictly positive")
    void theLimitIsValidated() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertFieldError(token, Map.of("categoryId", categoryId), "limitAmount");
        assertFieldError(token, Map.of("categoryId", categoryId, "limitAmount", "0.00"),
                "limitAmount");
        assertFieldError(token, Map.of("categoryId", categoryId, "limitAmount", "-5.00"),
                "limitAmount");
        assertFieldError(token, Map.of("limitAmount", "100.00"), "categoryId");
    }

    @Test
    @DisplayName("UC-13: the month must be in yyyy-MM form when it is sent")
    void theMonthFormatIsValidated() throws Exception {
        // The pattern is anchored, so a full date and a one-digit month are refused rather than
        // quietly truncated into a month the caller did not ask for.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        for (String malformed : new String[] {"2026-9", "2026-09-15", "September", "202609"}) {
            ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                    Map.of("categoryId", categoryId, "limitAmount", "10.00",
                            "periodMonth", malformed));
            assertThat(response.getStatusCode()).as("month=%s", malformed)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fieldNamesOfValidationError(body(response))).contains("periodMonth");
        }
    }

    @Test
    @DisplayName("BR-11: setting a limit for the same category and month twice is refused")
    void aSecondLimitForTheSameMonthIsRefused() throws Exception {
        // uk_budget_user_cat_month is the guarantee; this is what the caller sees. The remedy is the
        // update endpoint, and the message names the row that already holds the limit so the client
        // can offer to change it rather than guess.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long first = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                Map.of("categoryId", categoryId, "limitAmount", "150.00",
                        "periodMonth", monthKey(thisMonth())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(response)).isEqualTo("BUDGET_ALREADY_EXISTS");
        // The existing limit is unchanged: a refused request changes nothing.
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "/" + first, token, null))
                .get("limitAmount").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("BR-11: the same category may be limited in two different months")
    void theSameCategoryCanBeLimitedInTwoMonths() throws Exception {
        // The unique key is (user, category, month), not (user, category), so a limit is per month
        // and last month's does not block this month's.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        Long previous = createBudget(token, categoryId, "100.00", thisMonth().minusMonths(1)).get("id").asLong();
        Long current = createBudget(token, categoryId, "120.00", thisMonth()).get("id").asLong();

        assertThat(previous).isNotEqualTo(current);
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "?month="
                + monthKey(thisMonth().minusMonths(1)), token, null))).hasSize(1);
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "?month=" + monthKey(thisMonth()),
                token, null))).hasSize(1);
    }

    @Test
    @DisplayName("UC-13: setting a limit writes no alert, however far past it the month already is")
    void settingALimitRaisesNoAlert() throws Exception {
        // The boundary this module's documentation draws. An alert belongs to the transaction that
        // crossed a threshold (UC-14, BR-12); a limit is a target, and a target cannot be exceeded at
        // the moment it is set. So a student who sets a 10 limit in a month where they have already
        // spent 50 sees EXCEEDED at once - the status is recomputed on read - and receives no
        // message, because no transaction crossed anything.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        createTransaction(token, categoryId, "50.00", today(), "already spent");

        JsonNode budget = createBudget(token, categoryId, "10.00", thisMonth());

        assertThat(budget.get("consumptionStatus").asText()).isEqualTo("EXCEEDED");
        assertThat(budget.get("consumedPct").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(notificationCountFor(userId)).isZero();
        assertThat(alertRowsFor(budget.get("id").asLong())).isEmpty();
    }

    // ==================================================================
    //  UC-13: changing a limit
    // ==================================================================

    @Test
    @DisplayName("UC-13: changing a limit leaves the category and month alone")
    void changingALimitKeepsItsIdentity() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        ResponseEntity<String> response = send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId,
                token, Map.of("limitAmount", "250.00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = body(response);
        assertThat(updated.get("limitAmount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(updated.get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(updated.get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("250.00");
    }

    @Test
    @DisplayName("UC-13: lowering a limit below what is spent shows EXCEEDED without a duplicate "
            + "alert")
    void loweringALimitRaisesNoSecondAlert() throws Exception {
        // BR-12 in its most visible form. The status is recomputed on every read, so it moves
        // immediately; the notification is written once per threshold per budget and is not written
        // again. The student sees the new state on screen and is not told twice.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "50.00", today(), "half the limit");
        int notificationsAfterSpending = notificationCountFor(userId);

        assertThat(body(send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                Map.of("limitAmount", "20.00"))).get("consumptionStatus").asText())
                .isEqualTo("EXCEEDED");

        assertThat(notificationCountFor(userId)).isEqualTo(notificationsAfterSpending);
        assertThat(alertRowsFor(budgetId)).isEmpty();
    }

    @Test
    @DisplayName("UC-13: a change with no fields changes nothing and is not an error")
    void anEmptyChangeIsANoOp() throws Exception {
        // The documented behaviour of a partial update whose caller sent nothing. Worth pinning
        // because the alternative - refusing an empty body - would be a rule no other PATCH endpoint
        // in this API has, and a client that serialises an unchanged form would break on it.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        ResponseEntity<String> response = send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId,
                token, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("limitAmount").decimalValue())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("UC-13: a change cannot move a limit onto another category")
    void categoryIsNotEditable() throws Exception {
        // `categoryId` is not a field of the update request, so sending one is ignored rather than
        // honoured. Accepting it would be a second way to put a limit on an income category,
        // slipping past the BR-11 check the insert trigger makes - and the update trigger does not
        // repeat it for a value the row was created with.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                Map.of("limitAmount", "120.00", "categoryId", incomeId));

        assertThat(columnInDatabase(budgetId, "budgets", "category_id"))
                .isEqualTo(String.valueOf(categoryId));
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("120.00");
    }

    @Test
    @DisplayName("UC-13: the new limit is validated the same way the old one was")
    void anInvalidNewLimitIsRefused() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        for (String invalid : new String[] {"0.00", "-1.00"}) {
            ResponseEntity<String> response = send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId,
                    token, Map.of("limitAmount", invalid));
            assertThat(response.getStatusCode()).as("limit=%s", invalid)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fieldNamesOfValidationError(body(response))).contains("limitAmount");
        }

        // A refused change leaves the stored value as it was.
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("100.00");
    }

    // ==================================================================
    //  UC-13: removing a limit
    // ==================================================================

    @Test
    @DisplayName("UC-13: removing a limit removes the row and answers 204")
    void removingALimitDeletesTheRow() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(budgetExists(budgetId)).isFalse();
        // A second delete finds nothing, which is the honest answer rather than a second success.
        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-13: removing a limit leaves the records it measured untouched")
    void removingALimitKeepsTheTransactions() throws Exception {
        // The spending belongs to the transactions, not to the limit. A student who deletes a budget
        // has not deleted their history, and the records must still be there - including in the
        // month's figures for any other limit.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long otherCategoryId = defaultCategoryId(SECOND_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();
        Long transactionId = createTransaction(token, categoryId, "40.00", today(), "bus pass");
        createBudget(token, otherCategoryId, "50.00", thisMonth());

        send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null);

        assertThat(body(send(HttpMethod.GET, TRANSACTIONS_URL + "/" + transactionId, token, null))
                .get("isDeleted").asBoolean()).isFalse();
        JsonNode list = body(send(HttpMethod.GET, BUDGETS_URL, token, null));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("categoryId").asLong()).isEqualTo(otherCategoryId);
    }

    @Test
    @DisplayName("UC-13: removing a limit does not remove the notifications it produced")
    void removingALimitKeepsItsNotifications() throws Exception {
        // A notification is a message the student received. Deleting the limit it was about should
        // not erase the fact that they were told - so the message survives, even though the alert
        // log row that produced it cascades away with the budget.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "10.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "9.00", today(), "over the near threshold");
        assertThat(notificationCountFor(userId)).isEqualTo(1);

        send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null);

        assertThat(notificationCountFor(userId)).isEqualTo(1);
        assertThat(notificationsFor(userId).get(0).title())
                .isEqualTo("Approaching budget limit: " + DEFAULT_EXPENSE_NAME);
    }

    // ==================================================================
    //  Ownership, roles and reachability
    // ==================================================================

    @Test
    @DisplayName("BR-02: another student's limit is unreachable through every endpoint")
    void anotherStudentsLimitIsUnreachable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(owner, categoryId, "100.00", thisMonth()).get("id").asLong();

        // The list is filtered by the token's account, so the row simply is not there.
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL, intruder, null))).isEmpty();

        HttpMethod[] reaching = {HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE};
        for (HttpMethod method : reaching) {
            ResponseEntity<String> response = send(method, BUDGETS_URL + "/" + budgetId, intruder,
                    method == HttpMethod.PATCH ? Map.of("limitAmount", "1.00") : null);
            assertThat(response.getStatusCode()).as("%s must not reach another student's budget",
                            method)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        // And nothing was changed by the attempts.
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("Section 7.5: another student's limit and a missing one look identical")
    void notYoursAndNotFoundAreIndistinguishable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long foreignBudget = createBudget(owner, categoryId, "100.00", thisMonth()).get("id").asLong();

        JsonNode foreign = body(send(HttpMethod.GET, BUDGETS_URL + "/" + foreignBudget, intruder,
                null));
        JsonNode missing = body(send(HttpMethod.GET, BUDGETS_URL + "/999999999", intruder, null));

        // A response that distinguished them would let a client probe for the existence of other
        // students' limits, which is itself information about their spending.
        assertThat(foreign.get("errorCode").asText()).isEqualTo(missing.get("errorCode").asText());
        assertThat(foreign.get("message").asText()).isEqualTo(missing.get("message").asText());
    }

    @Test
    @DisplayName("BR-02: a limit cannot be set on another student's category")
    void anotherStudentsCategoryCannotBeLimited() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long foreignCategoryId = createCategory(owner, "Owner Only", "EXPENSE", "lock", "#111111");

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, intruder,
                Map.of("categoryId", foreignCategoryId, "limitAmount", "50.00"));

        // Not found rather than forbidden: `findVisibleById` is the same set GET /categories
        // returns, so a category the caller cannot see is one that does not exist as far as they
        // are concerned. The database reaches the same conclusion through sp_validate_budget.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Section 7.5: no token is refused on every budget route")
    void noTokenIsRefused() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST,
                HttpMethod.PATCH, HttpMethod.DELETE}) {
            ResponseEntity<String> response = send(method, BUDGETS_URL + "/1", null,
                    method == HttpMethod.POST || method == HttpMethod.PATCH
                            ? Map.of("limitAmount", "1.00") : null);
            assertThat(response.getStatusCode()).as("%s without a token", method)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    @DisplayName("UC-05 E1: an administrator token cannot reach a student's limits")
    void anAdministratorTokenIsRefused() throws Exception {
        // UC-13 is a student acting on their own data. The administrator routes to reporting live
        // under /api/v1/admin/**, and nothing there sets a limit on a student's behalf - so the role
        // rule refuses rather than letting a limit owned by an administrator be written through a
        // student-facing API.
        String adminToken = adminLogin();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertThat(send(HttpMethod.GET, BUDGETS_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, BUDGETS_URL, adminToken,
                Map.of("categoryId", categoryId, "limitAmount", "10.00")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.GET, BUDGETS_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PATCH, BUDGETS_URL + "/1", adminToken,
                Map.of("limitAmount", "10.00")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Section 7.5: a budget that does not exist is a 404 on every path that names it")
    void aMissingBudgetIsNotFound() throws Exception {
        String token = loginNewStudent();

        assertThat(send(HttpMethod.GET, BUDGETS_URL + "/999999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, BUDGETS_URL + "/999999999", token,
                Map.of("limitAmount", "10.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/999999999", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    //  BR-07 and BR-11 at the database, where no service check can hide them
    // ==================================================================

    @Test
    @DisplayName("BR-11: sp_validate_budget refuses an income category for every caller, not just "
            + "the service")
    void theTriggerRefusesAnIncomeCategoryForEveryCaller() throws Exception {
        // The service checks this first, so no API request reaches the refusal - and a test that
        // tried to would only prove the service's check. Writing the row by hand is what shows the
        // rule holds for every writer, which is what makes it the authority.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);

        assertThat(insertBudgetExpectingRefusal(userId, incomeId, thisMonth(), "100.00"))
                .isEqualTo("45000");
        assertThat(signalledMessageOf(userId, incomeId, thisMonth(), "100.00"))
                .contains("BR-11");
    }

    @Test
    @DisplayName("BR-02: sp_validate_budget refuses another student's category for every caller")
    void theTriggerRefusesAnotherStudentsCategoryForEveryCaller() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long intruderId = userIdOf(intruder);
        Long foreignCategoryId = createCategory(owner, "Owner Only", "EXPENSE", "lock", "#111111");

        assertThat(insertBudgetExpectingRefusal(intruderId, foreignCategoryId, thisMonth(), "10.00"))
                .isEqualTo("45000");
        assertThat(signalledMessageOf(intruderId, foreignCategoryId, thisMonth(), "10.00"))
                .contains("BR-02");
    }

    @Test
    @DisplayName("BR-07: sp_validate_budget refuses a retired category for every caller")
    void theTriggerRefusesARetiredCategoryForEveryCaller() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        retire(token, categoryId, true);

        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth(), "10.00"))
                .isEqualTo("45000");
        assertThat(signalledMessageOf(userId, categoryId, thisMonth(), "10.00"))
                .contains("BR-07");
    }

    @Test
    @DisplayName("BR-07: retiring the category afterwards freezes the existing limit's edit too")
    void retiringTheCategoryFreezesTheExistingLimit() throws Exception {
        // The case that decides whether this module's PATCH can be described the way module 4's can.
        // The retired-category check is not confined to creation: trg_budgets_before_update calls
        // sp_validate_budget on every UPDATE, and that procedure has no "skip the active check"
        // escape - unlike the transaction trigger, which exempts an update that does not move the
        // category. So a budget filed under a category retired afterwards is frozen exactly as
        // module 5's rules are, and NOT editable the way module 4's transactions are.
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        retire(token, categoryId, true);

        ResponseEntity<String> response = send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId,
                token, Map.of("limitAmount", "250.00"));

        // Named, not generic: the caller is told which rule refused them. Answering DATA_CONFLICT
        // here would be the same 409 with a message that says only "reload and try again", which is
        // advice that cannot work while the category stays retired.
        assertThat(errorCodeOf(response)).isEqualTo("CATEGORY_RETIRED");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The refusal rolled back with its transaction: the row still holds the limit it had.
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("100.00");

        // Re-enabling the category makes it editable again, which is the remedy the message implies
        // and the behaviour module 5 documents for the same constraint. Asserted so that "frozen"
        // is not mistaken for "broken permanently".
        retire(token, categoryId, false);
        assertThat(send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                Map.of("limitAmount", "250.00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("250.00");
    }

    @Test
    @DisplayName("BR-07: a retired category does not delete the limit, only freeze it")
    void retiringTheCategoryLeavesTheLimitReadable() throws Exception {
        // The other half of the constraint, and the one a client has to render: the row is still
        // there, still readable, and still reports its consumption - so a budgets screen keeps
        // showing a limit the student can see but not edit until they restore the category.
        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();
        createTransaction(token, categoryId, "30.00", today(), "a session");

        retire(token, categoryId, true);

        assertThat(budgetExists(budgetId)).isTrue();
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "/" + budgetId, token, null))
                .get("spentAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL, token, null)).size()).isEqualTo(1);

        // Removing it is still allowed, and that is the asymmetry the freeze has to have: a delete
        // fires no BEFORE UPDATE trigger, so the student can always get out of the state by removing
        // the limit - which is the one action that leaves the category alone. Without this the only
        // exit would be restoring the category.
        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(budgetExists(budgetId)).isFalse();
    }

    @Test
    @DisplayName("BR-11: the unique key refuses a second limit for the month for every caller")
    void theUniqueKeyRefusesASecondLimitForEveryCaller() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        insertBudgetDirectly(userId, categoryId, thisMonth(), "100.00");

        // 1062, SQLSTATE 23000: a duplicate key rather than a SIGNAL, which is why the write-failure
        // classification has two branches - a trigger refuses with 45000 and a key refuses with
        // 23000, and treating one as the other would misreport both.
        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth(), "200.00"))
                .isEqualTo("23000");
    }

    @Test
    @DisplayName("ck_budget_month: a month that is not the first of one is refused by the database")
    void theMonthCheckConstraintHoldsForEveryCaller() throws Exception {
        // BR-11's unique key is (user, category, period_month), so the month has to be canonical or
        // a budget for the 1st and one for the 15th would be two limits on one month. The CHECK is
        // what makes it canonical.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth().plusDays(14),
                "100.00")).isEqualTo("HY000");
    }

    @Test
    @DisplayName("ck_budget_limit: a non-positive limit is refused by the database")
    void theLimitCheckConstraintHoldsForEveryCaller() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth(), "0.00"))
                .isEqualTo("HY000");
        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth(), "-5.00"))
                .isEqualTo("HY000");
    }

    // ==================================================================
    //  Concurrency
    // ==================================================================

    @Test
    @DisplayName("BR-11: two simultaneous sets of the same limit produce one limit, not two")
    void twoSimultaneousSetsProduceOneLimit() throws Exception {
        // The unique key is the guarantee, and this is the case that actually needs it: both
        // requests look for an existing row, both find none, and both insert. Without the key the
        // month would end up with two limits and the view would report whichever the join matched.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        String month = monthKey(thisMonth());

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                String limit = attempt == 0 ? "100.00" : "200.00";
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.POST, BUDGETS_URL, token,
                            Map.of("categoryId", categoryId, "limitAmount", limit,
                                    "periodMonth", month));
                }));
            }

            int created = 0;
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                    continue;
                }
                // The loser is told the limit already exists - the same answer a serial second
                // attempt receives - rather than something went wrong.
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(errorCodeOf(response)).isEqualTo("BUDGET_ALREADY_EXISTS");
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOf("SELECT COUNT(*) FROM budgets WHERE user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-13: two simultaneous changes to one limit settle on one value, and both report it")
    void twoSimultaneousChangesSettleOnOneValue() throws Exception {
        // The row lock is what makes this deterministic. Both requests take SELECT ... FOR UPDATE,
        // so they are serialised: the second reads the row as the first left it, writes its own
        // value, and returns it. Without the lock both would read the original, and both would
        // report success while one silently overwrote the other - with the response the loser
        // received describing a state the row no longer has.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<String> reported = new ArrayList<>();
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                String limit = attempt == 0 ? "300.00" : "350.00";
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                            Map.of("limitAmount", limit));
                }));
            }
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode())
                        .as("both edits are serialised by the row lock, so both succeed: %s",
                                response.getBody())
                        .isEqualTo(HttpStatus.OK);
                // Compared as decimals rather than as the serialised text: the wire form of a
                // BigDecimal is not fixed to the column's two places, so "300.0" and "300.00" name
                // the same amount, and asserting the text would be asserting the serialiser.
                reported.add(body(response).get("limitAmount").decimalValue()
                        .setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        } finally {
            pool.shutdownNow();
        }

        // Both responses are honest: each reports a value the row actually held. The last write is
        // what stands, and it is one of the two the callers asked for - not a blend, and not the
        // stale original.
        String stored = new BigDecimal(columnInDatabase(budgetId, "budgets", "limit_amount"))
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        assertThat(stored).isIn("300.00", "350.00");
        assertThat(reported).contains(stored);
    }

    @Test
    @DisplayName("UC-13: two simultaneous deletes of one limit produce one deletion")
    void twoSimultaneousDeletesProduceOneDeletion() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null);
                }));
            }

            int deleted = 0;
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
                    deleted++;
                    continue;
                }
                // The loser is told the limit is gone, not that something went wrong.
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
            assertThat(deleted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(budgetExists(budgetId)).isFalse();
    }

    @Test
    @DisplayName("UC-13: a lowered limit racing a record settles without the alert contradicting "
            + "the screen")
    void aLoweredLimitRacingARecordSettlesConsistently() throws Exception {
        // The two writers touch different tables - the edit holds the `budgets` row, the record
        // inserts a `transactions` row whose trigger calls sp_check_budget_alerts - so this is
        // genuinely racy in a way the two tests above are not, and the assertions have to say what
        // is actually guaranteed.
        //
        // The trigger's read of the limit is an ordinary consistent read. It therefore sees the
        // limit as of the last commit: either the original 100, if the record's transaction read
        // before the edit committed, or the new 30 afterwards. Both are defensible - a record
        // written while the limit was still 100 and 25% used had crossed nothing - so the alert may
        // or may not exist, and only its *content* is fixed.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                        Map.of("limitAmount", "30.00"));
            }));
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return send(HttpMethod.POST, TRANSACTIONS_URL, token, Map.of(
                        "categoryId", categoryId, "amount", "25.00",
                        "txnDate", today().toString(), "description", "racing spend"));
            }));
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The edit is unconditional, so the limit is the new value however the two interleaved, and
        // the record is always there. 25 of 30 is 83.33%, so the screen reads NEAR either way - the
        // figure a student sees does not depend on the race.
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("30.00");
        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("25.00");
        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("NEAR");

        // What must not happen is an alert that contradicts the screen. The only alert this race can
        // produce is NEAR at 83.33% - the figure the screen now reports. An EXCEEDED row, or a NEAR
        // at the 25% the old limit implied, would be the trigger having fired against a limit that
        // never existed at that percentage.
        List<String> alerts = alertRowsFor(budgetId);
        assertThat(alerts).as("alerts=%s", alerts)
                .allMatch("NEAR|83.33"::equals);
        assertThat(alerts.size()).isLessThanOrEqualTo(1);
        // BR-12 still holds, and the messages agree with the log row for row.
        assertThat(notificationCountFor(userId)).isEqualTo(alerts.size());
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /**
     * Asserts one create request is refused for the named field and returns the parsed error.
     */
    private void assertFieldError(String token, Map<String, Object> request, String field)
            throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token, request);

        assertThat(response.getStatusCode()).as("body=%s", request)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesOfValidationError(body(response))).as("body=%s", request)
                .contains(field);
    }

    private static JsonNode rowFor(JsonNode list, Long categoryId) {
        for (JsonNode row : list) {
            if (row.get("categoryId").asLong() == categoryId) {
                return row;
            }
        }
        throw new AssertionError("no budget row for category " + categoryId);
    }

    private BigDecimal budgetSpentOf(String token, Long budgetId) throws Exception {
        return budgetFieldOf(token, budgetId, "spentAmount").decimalValue();
    }

    private BigDecimal budgetRemainingOf(String token, Long budgetId) throws Exception {
        return budgetFieldOf(token, budgetId, "remainingAmount").decimalValue();
    }

    private BigDecimal budgetPctOf(String token, Long budgetId) throws Exception {
        return budgetFieldOf(token, budgetId, "consumedPct").decimalValue();
    }

    private String budgetStatusOf(String token, Long budgetId) throws Exception {
        return budgetFieldOf(token, budgetId, "consumptionStatus").asText();
    }

    private JsonNode budgetFieldOf(String token, Long budgetId, String field) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, BUDGETS_URL + "/" + budgetId, token,
                null);
        assertThat(response.getStatusCode()).as("body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get(field);
    }
}
