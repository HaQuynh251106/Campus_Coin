package com.campuscoin.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class InsightApiIT extends AbstractInsightApiIT {

    @Test
    @DisplayName("UC-17: a month is generated on request, then read back, and the two agree")
    void generateThenReadAGeneratedMonth() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        incomeIn(token, currentMonth(), "300.00");
        expenseIn(token, currentMonth(), "120.00");

        assertThat(insightResponse(token, asMonth(currentMonth())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode generated = generate(token, null);

        JsonNode read = insight(token, asMonth(currentMonth()));

        assertThat(read.get("periodMonth").asText()).isEqualTo(asMonth(currentMonth()));
        assertThat(read.get("totalIncome").decimalValue())
                .isEqualByComparingTo(generated.get("totalIncome").decimalValue());
        assertThat(read.get("totalExpense").decimalValue())
                .isEqualByComparingTo(generated.get("totalExpense").decimalValue());
        assertThat(read.get("netAmount").decimalValue())
                .isEqualByComparingTo(generated.get("netAmount").decimalValue());
        assertThat(read.get("summary").asText()).isEqualTo(generated.get("summary").asText());

        assertThat(read.get("totalIncome").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(read.get("totalExpense").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(read.get("netAmount").decimalValue()).isEqualByComparingTo("180.00");

        assertThat(storedTotalIncomeOf(userId, currentMonth())).isEqualByComparingTo("300.00");
        assertThat(insightCountOf(userId, currentMonth())).isEqualTo(1);

        assertThat(monthLabelsOf(months(token))).contains(asMonth(currentMonth()));
    }

    @Test
    @DisplayName("UC-17: the rule-based summary names the month, the figures, and the top category")
    void theSummaryIsWrittenByTheRulesWhenNoProviderIsConfigured() throws Exception {
        String token = loginNewStudent();

        incomeIn(token, currentMonth(), "300.00");
        expenseIn(token, currentMonth(), "120.00");

        JsonNode response = generate(token, null);

        assertThat(response.get("generatedBy").asText()).isEqualTo("RULE_BASED");
        assertThat(response.get("summary").asText())
                .contains(asMonth(currentMonth()))
                .contains("you received 300.00")
                .contains("spent 120.00")
                .contains("net difference 180.00")
                .contains("Highest spending category: Food (120.00).");
        assertThat(response.get("advice").asText()).isNotBlank();

        assertThat(response.has("model")).isFalse();
    }

    @Test
    @DisplayName("UC-17: a month the student spent more than they received in says so")
    void spendingAboveIncomeIsReportedAsItIs() throws Exception {
        String token = loginNewStudent();

        incomeIn(token, currentMonth(), "100.00");
        expenseIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        assertThat(response.get("netAmount").decimalValue()).isEqualByComparingTo("-200.00");
        assertThat(response.get("advice").asText())
                .isEqualTo("Total spending is higher than total income this month. "
                        + "Cut non-essential spending first.");
    }

    @Test
    @DisplayName("UC-17: a month in surplus gets the other branch of the rule-based advice")
    void aMonthInSurplusGetsThePositiveBranch() throws Exception {
        String token = loginNewStudent();

        incomeIn(token, currentMonth(), "400.00");
        expenseIn(token, currentMonth(), "50.00");

        JsonNode response = generate(token, null);

        assertThat(response.get("netAmount").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(response.get("advice").asText())
                .isEqualTo("You are keeping a positive balance. Consider moving the surplus toward "
                        + "your savings goal at the start of the month.");
    }

    @Test
    @DisplayName("UC-17: a month with a spike gets the branch that names it")
    void aMonthWithASpikeGetsTheSpikeBranch() throws Exception {
        String token = loginNewStudent();

        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(token, completeMonth(monthsAgo), "100.00");
        }
        expenseIn(token, currentMonth(), "400.00");

        JsonNode response = generate(token, null);

        assertThat(flaggedNamesOf(response)).containsExactly(FOOD);
        assertThat(response.get("advice").asText())
                .isEqualTo("One or more categories are rising above your usual level. Consider "
                        + "setting a weekly cap for those categories next month.");
    }

    @Test
    @DisplayName("UC-17: a month with no records still has an insight, and it is not the 404")
    void aMonthWithNoRecordsIsAnInsightRatherThanAnAbsence() throws Exception {
        String token = loginNewStudent();

        JsonNode response = generate(token, asMonth(completeMonth(1)));

        assertThat(response.get("totalIncome").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("totalExpense").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("netAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("summary").asText()).contains("No spending has been recorded yet.");
        assertThat(response.get("flaggedCategories")).isEmpty();
    }

    @Test
    @DisplayName("UC-17 BR-15: a category well above the student's own average is flagged with its evidence")
    void aCategoryAboveItsOwnBaselineIsFlagged() throws Exception {
        String token = loginNewStudent();

        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(token, completeMonth(monthsAgo), "100.00");
            incomeIn(token, completeMonth(monthsAgo), "300.00");
        }

        expenseIn(token, currentMonth(), "400.00");
        incomeIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        assertThat(flaggedNamesOf(response)).containsExactly(FOOD);

        JsonNode flag = flaggedCategory(response, FOOD);

        assertThat(flag.get("currentTotal").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(flag.get("baselineAvg").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(flag.get("pctChange").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(flag.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));

        assertThat(insight(token, asMonth(currentMonth())).get("flaggedCategories").toString())
                .isEqualTo(response.get("flaggedCategories").toString());

        assertThat(response.get("advice").asText()).isNotBlank();
    }

    @Test
    @DisplayName("UC-17 BR-15: a month inside the student's usual range flags nothing")
    void aMonthWithinTheUsualRangeFlagsNothing() throws Exception {
        String token = loginNewStudent();

        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(token, completeMonth(monthsAgo), "100.00");
            incomeIn(token, completeMonth(monthsAgo), "300.00");
        }

        expenseIn(token, currentMonth(), "120.00");
        incomeIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        assertThat(response.get("flaggedCategories")).isEmpty();
    }

    @Test
    @DisplayName("UC-17: the spike comparison is against the student's own history, never another's")
    void theBaselineIsTheStudentsOwn() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(tokenB, completeMonth(monthsAgo), "900.00");
            expenseIn(tokenA, completeMonth(monthsAgo), "10.00");
        }
        expenseIn(tokenB, currentMonth(), "900.00");
        expenseIn(tokenA, currentMonth(), "50.00");

        JsonNode a = generate(tokenA, null);

        assertThat(flaggedNamesOf(a)).containsExactly(FOOD);
        assertThat(flaggedCategory(a, FOOD).get("baselineAvg").decimalValue())
                .as("the baseline must be this student's own average")
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("UC-17: generating twice leaves one insight for the month, refreshed in place")
    void generatingTwiceLeavesOneInsight() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        expenseIn(token, currentMonth(), "100.00");
        generate(token, null);

        assertThat(insightCountOf(userId, currentMonth())).isEqualTo(1);

        expenseIn(token, currentMonth(), "50.00");
        JsonNode second = generate(token, null);

        assertThat(insightCountOf(userId, currentMonth()))
                .as("the unique key makes this an upsert, not a second month")
                .isEqualTo(1);
        assertThat(second.get("totalExpense").decimalValue())
                .as("the second run must see the correction")
                .isEqualByComparingTo("150.00");
        assertThat(second.get("summary").asText()).contains("150.00");
    }

    @Test
    @DisplayName("UC-17: a summary a provider wrote survives a later generation")
    void aProviderSummaryIsNotOverwrittenByARegeneration() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        expenseIn(token, currentMonth(), "100.00");
        generate(token, null);

        markAsProviderWritten(userId, currentMonth(), "Written by a model.", "Advised by a model.");

        JsonNode second = generate(token, null);

        assertThat(second.get("generatedBy").asText()).isEqualTo("AI");
        assertThat(second.get("model").asText()).isEqualTo("gemini-3.5-flash");
        assertThat(second.get("summary").asText())
                .as("a later run must not rewrite a provider's text")
                .isEqualTo("Written by a model.");
        assertThat(second.get("advice").asText()).isEqualTo("Advised by a model.");

        assertThat(second.get("totalExpense").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("UC-17: reading a month generates nothing, so opening a screen cannot change it")
    void readingDoesNotGenerate() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        expenseIn(token, currentMonth(), "42.00");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(insightResponse(token, asMonth(currentMonth())).getStatusCode())
                    .as("a month never generated stays absent however often it is read")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(insightTotalOf(userId)).isZero();

        assertThat(monthLabelsOf(months(token))).isEmpty();
    }

    @Test
    @DisplayName("UC-17: the picker offers exactly the months that would return something")
    void thePickerOffersOnlyMonthsWithAnInsight() throws Exception {
        String token = loginNewStudent();

        expenseIn(token, completeMonth(2), "30.00");
        expenseIn(token, completeMonth(1), "40.00");
        generate(token, asMonth(completeMonth(1)));

        List<String> offered = monthLabelsOf(months(token));

        assertThat(offered).containsExactly(asMonth(completeMonth(1)));
        assertThat(offered)
                .as("a month with records but no insight would answer 404 behind a menu entry")
                .doesNotContain(asMonth(completeMonth(2)));

        for (String month : offered) {
            assertThat(insight(token, month).get("periodMonth").asText()).isEqualTo(month);
        }
    }

    @Test
    @DisplayName("UC-17: the months list is ordered by month, newest first, not by when it was generated")
    void theMonthsListIsOrderedByMonth() throws Exception {
        String token = loginNewStudent();

        generate(token, asMonth(currentMonth()));
        generate(token, asMonth(completeMonth(1)));
        generate(token, asMonth(completeMonth(2)));

        assertThat(monthLabelsOf(months(token))).containsExactly(
                asMonth(currentMonth()),
                asMonth(completeMonth(1)),
                asMonth(completeMonth(2)));
    }

    @Test
    @DisplayName("UC-17: a named month is honoured, so a student can look back at a past one")
    void aNamedMonthIsHonoured() throws Exception {
        String token = loginNewStudent();

        expenseIn(token, completeMonth(2), "77.00");
        JsonNode response = generate(token, asMonth(completeMonth(2)));

        assertThat(response.get("periodMonth").asText()).isEqualTo(asMonth(completeMonth(2)));
        assertThat(response.get("totalExpense").decimalValue()).isEqualByComparingTo("77.00");

        assertThat(insightResponse(token, asMonth(currentMonth())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-17 A2: a month that is not a month is refused with a field error, not read as another")
    void aMalformedMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        for (String bad : new String[] {"2026-13", "2026-9", "not-a-month", "2026-09-01", "2026", "13-2026"}) {
            ResponseEntity<String> response = send(HttpMethod.GET, INSIGHTS_URL + "?month=" + bad,
                    token, null);

            assertThat(response.getStatusCode())
                    .as("month=%s body=%s", bad, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                    .isEqualTo("month");
        }
    }

    @Test
    @DisplayName("UC-17: the generator refuses a malformed month the same way the read does")
    void theGeneratorRefusesAMalformedMonth() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response =
                send(HttpMethod.POST, INSIGHTS_GENERATE_URL + "?month=2026-13", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(response).get("fieldErrors").get(0).get("field").asText()).isEqualTo("month");
    }

    @Test
    @DisplayName("UC-17: an absent month means the server's current month, not a client's")
    void anAbsentMonthMeansTheCurrentMonth() throws Exception {
        String token = loginNewStudent();

        JsonNode response = generate(token, null);

        assertThat(response.get("periodMonth").asText()).isEqualTo(asMonth(currentMonth()));
    }

    @Test
    @DisplayName("UC-17 BR-02: an insight is reachable only by its owner")
    void anotherStudentsMonthIsNotReachable() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        expenseIn(tokenA, currentMonth(), "250.00");
        JsonNode a = generate(tokenA, null);

        assertThat(a.get("totalExpense").decimalValue()).isEqualByComparingTo("250.00");

        ResponseEntity<String> asB = insightResponse(tokenB, asMonth(currentMonth()));
        assertThat(asB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(asB)).isEqualTo("NOT_FOUND");

        assertThat(monthLabelsOf(months(tokenB))).isEmpty();
        assertThat(insightTotalOf(userIdOf(tokenB))).isZero();
    }

    @Test
    @DisplayName("UC-17 BR-02: generating for the current month cannot touch another student's insight")
    void generatingIsScopedToTheCaller() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();
        Long userIdA = userIdOf(tokenA);

        expenseIn(tokenA, currentMonth(), "111.00");
        expenseIn(tokenB, currentMonth(), "222.00");

        JsonNode a = generate(tokenA, null);
        JsonNode b = generate(tokenB, null);

        assertThat(a.get("totalExpense").decimalValue()).isEqualByComparingTo("111.00");
        assertThat(b.get("totalExpense").decimalValue()).isEqualByComparingTo("222.00");

        assertThat(fieldNamesOf(a)).doesNotContain("userId", "email", "owner");
        assertThat(storedTotalIncomeOf(userIdA, currentMonth())).isNotNull();
        assertThat(countOf("SELECT COUNT(*) FROM insights WHERE user_id = ?", userIdA)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-17: no endpoint accepts a user id, so there is nothing to point at another account")
    void thereIsNothingForAClientToChoose() throws Exception {
        String token = loginNewStudent();
        Long otherA = userIdOf(loginNewStudent());

        generate(token, null);

        ResponseEntity<String> response = send(HttpMethod.GET,
                INSIGHTS_URL + "?userId=" + otherA + "&id=" + otherA, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("periodMonth").asText()).isEqualTo(asMonth(currentMonth()));
        assertThat(body(response).get("totalExpense").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-17: the response carries exactly its documented fields, and no owner")
    void documentedFieldsOnly() throws Exception {
        String token = loginNewStudent();

        expenseIn(token, currentMonth(), "100.00");
        JsonNode response = generate(token, null);

        assertThat(fieldNamesOf(response)).containsExactlyElementsOf(DOCUMENTED_RULE_BASED_FIELDS);
        assertThat(fieldNamesOf(response))
                .as("an insight states a student's income, expense and net balance")
                .doesNotContain("userId", "user_id", "email", "id", "insightId", "status",
                        "errorMessage", "flagged_categories");
    }

    @Test
    @DisplayName("UC-17: a flagged category carries exactly its documented fields, and no owner")
    void aFlaggedCategoryCarriesItsDocumentedFields() throws Exception {
        String token = loginNewStudent();

        for (int monthsAgo = 2; monthsAgo >= 1; monthsAgo--) {
            expenseIn(token, completeMonth(monthsAgo), "100.00");
        }
        expenseIn(token, currentMonth(), "500.00");

        JsonNode response = generate(token, null);

        assertThat(response.get("flaggedCategories")).isNotEmpty();
        response.get("flaggedCategories").forEach(flag -> assertThat(fieldNamesOf(flag))
                .containsExactlyElementsOf(DOCUMENTED_FLAGGED_FIELDS));
    }

    @Test
    @DisplayName("UC-17: the months response is an object with one array, and an empty one is a real answer")
    void theMonthsResponseHasTheDocumentedShape() throws Exception {
        String token = loginNewStudent();

        JsonNode empty = months(token);
        assertThat(fieldNamesOf(empty)).containsExactly("months");
        assertThat(empty.get("months")).isEmpty();

        generate(token, null);
        assertThat(fieldNamesOf(months(token))).containsExactly("months");
    }

    @Test
    @DisplayName("UC-17: the generatedAt of a regenerated month is the later run's")
    void generatedAtMovesWithTheRunThatWroteIt() throws Exception {
        String token = loginNewStudent();

        generate(token, null);
        JsonNode first = insight(token, null);

        generate(token, null);
        JsonNode second = insight(token, null);

        assertThat(LocalDateTime.parse(second.get("generatedAt").asText()))
                .isAfterOrEqualTo(LocalDateTime.parse(first.get("generatedAt").asText()));
    }

    @Test
    @DisplayName("Section 7.5: no token is 401, and an administrator token is 403 on every insights route")
    void roleAndTokenAreEnforced() throws Exception {
        String adminToken = adminLogin();

        record Route(HttpMethod method, String url) {
        }
        List<Route> routes = List.of(
                new Route(HttpMethod.GET, INSIGHTS_URL),
                new Route(HttpMethod.GET, INSIGHT_MONTHS_URL),
                new Route(HttpMethod.POST, INSIGHTS_GENERATE_URL));

        for (Route route : routes) {
            ResponseEntity<String> anonymous = send(route.method(), route.url(), null, null);
            assertThat(anonymous.getStatusCode())
                    .as("%s %s without a token", route.method(), route.url())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(anonymous)).isEqualTo("UNAUTHENTICATED");

            ResponseEntity<String> asAdmin = send(route.method(), route.url(), adminToken, null);
            assertThat(asAdmin.getStatusCode())
                    .as("%s %s with an administrator token", route.method(), route.url())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCodeOf(asAdmin)).isEqualTo("ACCESS_DENIED");
        }
    }

    private void markAsProviderWritten(Long userId, LocalDate month, String summary, String advice)
            throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE insights SET summary_text = ?, advice_text = ?, generated_by = 'AI', "
                             + "model_name = 'gemini-3.5-flash' "
                             + "WHERE user_id = ? AND period_month = ?")) {
            statement.setString(1, summary);
            statement.setString(2, advice);
            statement.setLong(3, userId);
            statement.setDate(4, Date.valueOf(month));
            assertThat(statement.executeUpdate()).as("the row to mark exists").isEqualTo(1);
        }
    }
}
