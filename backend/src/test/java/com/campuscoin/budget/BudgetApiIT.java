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

import com.campuscoin.common.setting.SettingReader;
import com.fasterxml.jackson.databind.JsonNode;

class BudgetApiIT extends AbstractBudgetApiIT {

    private static final String ADMIN_SETTINGS_URL = "/api/v1/admin/settings";

    @Test
    @DisplayName("UC-13: a student with no limits gets an empty list, not an error and not null")
    void emptyStateIsAnEmptyArray() throws Exception {

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

        assertThat(fieldNamesOf(budget)).isSubsetOf(DOCUMENTED_BUDGET_FIELDS);
        assertThat(fieldNamesOf(budget)).contains("id", "categoryId", "categoryName", "periodMonth",
                "limitAmount", "spentAmount", "remainingAmount", "consumedPct",
                "consumptionStatus");
        assertThat(fieldNamesOf(budget)).doesNotContain("userId", "createdAt", "updatedAt");

        assertThat(budget.get("limitAmount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(budget.get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));

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

        assertThat(list.get(0).get("categoryName").asText())
                .isEqualTo(DEFAULT_EXPENSE_NAME);
        assertThat(list.get(1).get("categoryName").asText())
                .isEqualTo(SECOND_EXPENSE_NAME);
    }

    @Test
    @DisplayName("UC-13: the list reports only the month asked for")
    void theListIsScopedToTheRequestedMonth() throws Exception {

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

    @Test
    @DisplayName("BR-09: a deleted record stops counting toward the limit, and the status follows")
    void deletedRecordsStopCounting() throws Exception {

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

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "85.00", today(), "at 85 percent");

        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("NEAR");
        assertThat(budgetPctOf(token, budgetId)).isEqualByComparingTo("85.00");

        createTransaction(token, categoryId, "20.00", today(), "past the limit");

        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("EXCEEDED");
        assertThat(budgetRemainingOf(token, budgetId)).isEqualByComparingTo("-5.00");
        assertThat(budgetPctOf(token, budgetId)).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("BR-12/VĐ-05: the administrator's exceeded threshold moves the student's status")
    void theExceededStatusFollowsTheConfiguredThreshold() throws Exception {

        String studentToken = loginNewStudent();
        String adminToken = adminLogin();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(studentToken, categoryId, "100.00", thisMonth()).get("id").asLong();

        String originalNear = settingValue(SettingReader.BUDGET_NEAR_THRESHOLD_PCT);
        String originalExceeded = settingValue(SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT);
        try {

            createTransaction(studentToken, categoryId, "80.00", today(), "four fifths of the limit");
            assertThat(budgetStatusOf(studentToken, budgetId))
                    .as("80%% is exactly budget.near_threshold_pct's seeded value")
                    .isEqualTo("NEAR");

            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, "50");
            assertThat(settingValue(SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT))
                    .as("the setting really holds 50 before the budget is read")
                    .isEqualTo("50");
            assertThat(budgetPctOf(studentToken, budgetId))
                    .as("the dependent read is the same row, unchanged: only the threshold moved")
                    .isEqualByComparingTo("80.00");
            assertThat(budgetStatusOf(studentToken, budgetId))
                    .as("80%% is past a 50%% exceeded bar")
                    .isEqualTo("EXCEEDED");

            createTransaction(studentToken, categoryId, "40.00", today(), "over the limit");
            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, "150");
            assertThat(budgetPctOf(studentToken, budgetId)).isEqualByComparingTo("120.00");
            assertThat(budgetStatusOf(studentToken, budgetId))
                    .as("120%% spent is under a 150%% exceeded bar: over the limit, not over the bar")
                    .isEqualTo("NEAR");

            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, "100");
            assertThat(budgetStatusOf(studentToken, budgetId))
                    .as("the classification follows the setting back, so it is not one-way")
                    .isEqualTo("EXCEEDED");
        } finally {

            patchSetting(adminToken, SettingReader.BUDGET_NEAR_THRESHOLD_PCT, originalNear);
            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, originalExceeded);
        }
    }

    @Test
    @DisplayName("BR-12/VĐ-05: a near threshold above the exceeded one still leaves the row EXCEEDED")
    void anInvertedNearThresholdDoesNotHideAnExceededBudget() throws Exception {

        String studentToken = loginNewStudent();
        String adminToken = adminLogin();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(studentToken, categoryId, "100.00", thisMonth()).get("id").asLong();

        String originalNear = settingValue(SettingReader.BUDGET_NEAR_THRESHOLD_PCT);
        String originalExceeded = settingValue(SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT);
        try {
            createTransaction(studentToken, categoryId, "90.00", today(), "ninety percent");

            patchSetting(adminToken, SettingReader.BUDGET_NEAR_THRESHOLD_PCT, "200");
            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, "50");

            assertThat(budgetPctOf(studentToken, budgetId)).isEqualByComparingTo("90.00");
            assertThat(budgetStatusOf(studentToken, budgetId))
                    .as("90%% is past both bars; the worse label is the correct one")
                    .isEqualTo("EXCEEDED");
        } finally {
            patchSetting(adminToken, SettingReader.BUDGET_NEAR_THRESHOLD_PCT, originalNear);
            patchSetting(adminToken, SettingReader.BUDGET_EXCEEDED_THRESHOLD_PCT, originalExceeded);
        }
    }

    @Test
    @DisplayName("UC-13: an income record does not count toward an expense limit")
    void incomeDoesNotCountTowardsALimit() throws Exception {

        String token = loginNewStudent();
        Long expenseId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long incomeId = defaultCategoryId(DEFAULT_INCOME_NAME);
        Long budgetId = createBudget(token, expenseId, "100.00", thisMonth()).get("id").asLong();

        createTransaction(token, incomeId, "500.00", today(), "allowance");

        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("0.00");
        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("ON_TRACK");
    }

    @Test
    @DisplayName("UC-13: a limit with no month applies to the current one")
    void anOmittedMonthDefaultsToTheCurrentOne() throws Exception {

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

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long first = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        ResponseEntity<String> response = send(HttpMethod.POST, BUDGETS_URL, token,
                Map.of("categoryId", categoryId, "limitAmount", "150.00",
                        "periodMonth", monthKey(thisMonth())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(response)).isEqualTo("BUDGET_ALREADY_EXISTS");

        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "/" + first, token, null))
                .get("limitAmount").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("BR-11: the same category may be limited in two different months")
    void theSameCategoryCanBeLimitedInTwoMonths() throws Exception {

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

        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("UC-13: removing a limit removes the row and answers 204")
    void removingALimitDeletesTheRow() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(budgetExists(budgetId)).isFalse();

        assertThat(send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-13: removing a limit leaves the records it measured untouched")
    void removingALimitKeepsTheTransactions() throws Exception {

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

    @Test
    @DisplayName("BR-02: another student's limit is unreachable through every endpoint")
    void anotherStudentsLimitIsUnreachable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(owner, categoryId, "100.00", thisMonth()).get("id").asLong();

        assertThat(body(send(HttpMethod.GET, BUDGETS_URL, intruder, null))).isEmpty();

        HttpMethod[] reaching = {HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE};
        for (HttpMethod method : reaching) {
            ResponseEntity<String> response = send(method, BUDGETS_URL + "/" + budgetId, intruder,
                    method == HttpMethod.PATCH ? Map.of("limitAmount", "1.00") : null);
            assertThat(response.getStatusCode()).as("%s must not reach another student's budget",
                            method)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

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

    @Test
    @DisplayName("BR-11: sp_validate_budget refuses an income category for every caller, not just "
            + "the service")
    void theTriggerRefusesAnIncomeCategoryForEveryCaller() throws Exception {

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

        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();

        retire(token, categoryId, true);

        ResponseEntity<String> response = send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId,
                token, Map.of("limitAmount", "250.00"));

        assertThat(errorCodeOf(response)).isEqualTo("CATEGORY_RETIRED");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("100.00");

        retire(token, categoryId, false);
        assertThat(send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token,
                Map.of("limitAmount", "250.00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("250.00");
    }

    @Test
    @DisplayName("BR-07: a retired category does not delete the limit, only freeze it")
    void retiringTheCategoryLeavesTheLimitReadable() throws Exception {

        String token = loginNewStudent();
        Long categoryId = createCategory(token, "Gym", "EXPENSE", "dumbbell", "#0EA5E9");
        Long budgetId = createBudget(token, categoryId, "100.00", thisMonth()).get("id").asLong();
        createTransaction(token, categoryId, "30.00", today(), "a session");

        retire(token, categoryId, true);

        assertThat(budgetExists(budgetId)).isTrue();
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "/" + budgetId, token, null))
                .get("spentAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(body(send(HttpMethod.GET, BUDGETS_URL, token, null)).size()).isEqualTo(1);

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

        assertThat(insertBudgetExpectingRefusal(userId, categoryId, thisMonth(), "200.00"))
                .isEqualTo("23000");
    }

    @Test
    @DisplayName("ck_budget_month: a month that is not the first of one is refused by the database")
    void theMonthCheckConstraintHoldsForEveryCaller() throws Exception {

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

    @Test
    @DisplayName("BR-11: two simultaneous sets of the same limit produce one limit, not two")
    void twoSimultaneousSetsProduceOneLimit() throws Exception {

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

                reported.add(body(response).get("limitAmount").decimalValue()
                        .setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        } finally {
            pool.shutdownNow();
        }

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

        assertThat(columnInDatabase(budgetId, "budgets", "limit_amount")).isEqualTo("30.00");
        assertThat(budgetSpentOf(token, budgetId)).isEqualByComparingTo("25.00");
        assertThat(budgetStatusOf(token, budgetId)).isEqualTo("NEAR");

        List<String> alerts = alertRowsFor(budgetId);
        assertThat(alerts).as("alerts=%s", alerts)
                .allMatch("NEAR|83.33"::equals);
        assertThat(alerts.size()).isLessThanOrEqualTo(1);

        assertThat(notificationCountFor(userId)).isEqualTo(alerts.size());
    }

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

    private void patchSetting(String adminToken, String key, String value) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, ADMIN_SETTINGS_URL + "/" + key,
                adminToken, Map.of("value", value));
        assertThat(response.getStatusCode())
                .as("PATCH %s=%s body=%s", key, value, response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("value").asText()).isEqualTo(value);
    }

    private String settingValue(String key) throws Exception {
        List<String> values = stringsFrom(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", key);
        assertThat(values).as("seeded setting %s exists", key).hasSize(1);
        return values.get(0);
    }
}
