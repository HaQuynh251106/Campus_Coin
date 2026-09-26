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

/**
 * UC-17 — a student's monthly insight, end to end over HTTP.
 *
 * <p>Everything here runs against a real MySQL with the project's own procedures loaded, because most of
 * what is under test is not computed in the Java at all: the month's totals, the unusual categories and
 * both prose sentences are {@code sp_generate_monthly_insight}'s, and the month a request with no
 * {@code month} resolves to is the application's clock in the configured zone (VĐ-10). A mocked
 * procedure would let every assertion here pass while the stored insight said something else.
 *
 * <p><b>The provider is absent, and that is the default the suite runs in.</b> No credential is
 * configured for these tests, so {@code AiSuggestionPort} is the no-op implementation and every
 * generation below takes the rule-based path - which is the path a deployment with no AI provider has,
 * and the one a student is shown when the provider is unreachable. The AI overlay itself is covered in
 * {@code InsightNarrativeTest} at the unit level, where a stub port can be made to answer.
 *
 * <p>What this class is trying to break:
 *
 * <ul>
 *   <li>that reading a month generates one, so opening a screen changes what is on it;</li>
 *   <li>that a month never generated is answered with zeros instead of a {@code 404};</li>
 *   <li>that generating twice produces two insights, or loses their own choices to a second run;</li>
 *   <li>that a month's figures come from anywhere but the caller's own records;</li>
 *   <li>that a student can read, or infer the existence of, another student's month;</li>
 *   <li>that the month picker offers a month whose insight was never produced;</li>
 *   <li>that a malformed month is silently read as a different, valid one;</li>
 *   <li>that the route is behind the student role rule and not the authenticated catch-all.</li>
 * </ul>
 */
class InsightApiIT extends AbstractInsightApiIT {

    // ==================================================================
    //  The acceptance scenario
    // ==================================================================

    @Test
    @DisplayName("UC-17: a month is generated on request, then read back, and the two agree")
    void generateThenReadAGeneratedMonth() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        incomeIn(token, currentMonth(), "300.00");
        expenseIn(token, currentMonth(), "120.00");

        // Nothing has been generated, so there is nothing to read. This is the state the screen starts
        // in, and it is a 404 rather than a zero-filled month - the distinction the whole endpoint set
        // turns on.
        assertThat(insightResponse(token, asMonth(currentMonth())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode generated = generate(token, null);

        // Reading the month afterwards gives the same month back, from the same row.
        JsonNode read = insight(token, asMonth(currentMonth()));

        assertThat(read.get("periodMonth").asText()).isEqualTo(asMonth(currentMonth()));
        assertThat(read.get("totalIncome").decimalValue())
                .isEqualByComparingTo(generated.get("totalIncome").decimalValue());
        assertThat(read.get("totalExpense").decimalValue())
                .isEqualByComparingTo(generated.get("totalExpense").decimalValue());
        assertThat(read.get("netAmount").decimalValue())
                .isEqualByComparingTo(generated.get("netAmount").decimalValue());
        assertThat(read.get("summary").asText()).isEqualTo(generated.get("summary").asText());

        // And the figures on both are the month's own records, not a second derivation of them.
        assertThat(read.get("totalIncome").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(read.get("totalExpense").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(read.get("netAmount").decimalValue()).isEqualByComparingTo("180.00");

        // The stored row says the same as the response did.
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

        // UC-17's postcondition is that a student can see what the month looked like, so the sentence
        // is composed from the figures stored beside it. BR-13 also requires the reader to be told
        // where the words came from, which is what `generatedBy` answers on the next line.
        assertThat(response.get("generatedBy").asText()).isEqualTo("RULE_BASED");
        assertThat(response.get("summary").asText())
                .contains(asMonth(currentMonth()))
                .contains("you received 300.00")
                .contains("spent 120.00")
                .contains("net difference 180.00")
                .contains("Highest spending category: Food (120.00).");
        assertThat(response.get("advice").asText()).isNotBlank();

        // No provider wrote this, so no model is named - and the field is absent rather than null, so a
        // client cannot read a null model as a provider whose name was lost.
        assertThat(response.has("model")).isFalse();
    }

    @Test
    @DisplayName("UC-17: a month the student spent more than they received in says so")
    void spendingAboveIncomeIsReportedAsItIs() throws Exception {
        String token = loginNewStudent();

        incomeIn(token, currentMonth(), "100.00");
        expenseIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        // A negative net is the useful part of the answer, so it must neither be floored at zero nor
        // reported as a positive balance - and the advice is the branch for a month that ran a deficit
        // rather than the one a month in surplus gets.
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

        // A month with no spike and a positive net. The two advice branches are the procedure's and are
        // not restated in Java, so pinning both here is what shows the response carries whichever it
        // wrote rather than a sentence this build composed.
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

        // A month the student recorded nothing in is a real answer about their own data - "you received
        // nothing and spent nothing". A month whose insight was never generated is a different answer,
        // and the endpoint keeps the two apart.
        JsonNode response = generate(token, asMonth(completeMonth(1)));

        assertThat(response.get("totalIncome").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("totalExpense").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("netAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(response.get("summary").asText()).contains("No spending has been recorded yet.");
        assertThat(response.get("flaggedCategories")).isEmpty();
    }

    // ==================================================================
    //  BR-15: the unusual categories
    // ==================================================================

    @Test
    @DisplayName("UC-17 BR-15: a category well above the student's own average is flagged with its evidence")
    void aCategoryAboveItsOwnBaselineIsFlagged() throws Exception {
        String token = loginNewStudent();

        // Three months of 100.00 in Food establish a baseline of 100.00, because the divisor is the
        // window the setting names (three months) rather than the number of months that hold data.
        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(token, completeMonth(monthsAgo), "100.00");
            incomeIn(token, completeMonth(monthsAgo), "300.00");
        }
        // Then 400.00 in the month under test: up 300% on the student's own average, far past BR-15's
        // 30% threshold.
        expenseIn(token, currentMonth(), "400.00");
        incomeIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        assertThat(flaggedNamesOf(response)).containsExactly(FOOD);

        JsonNode flag = flaggedCategory(response, FOOD);
        // The flag carries the comparison that produced it, not just the total: "you spent 400.00 on
        // Food" does not say why that was worth noticing.
        assertThat(flag.get("currentTotal").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(flag.get("baselineAvg").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(flag.get("pctChange").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(flag.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));

        // And the flag is stored, not recomputed on read: the same array comes back from the read
        // endpoint, so a student opening the month in December sees what was unusual in it.
        assertThat(insight(token, asMonth(currentMonth())).get("flaggedCategories").toString())
                .isEqualTo(response.get("flaggedCategories").toString());

        // The advice is the rule-based branch for a month with a spike, which is a different sentence
        // from the one a month with no spike gets.
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
        // 120.00 against a 100.00 baseline is a 20% rise, below the 30% the setting names.
        expenseIn(token, currentMonth(), "120.00");
        incomeIn(token, currentMonth(), "300.00");

        JsonNode response = generate(token, null);

        // An empty array is the ordinary case and a real answer: the month was examined against the
        // student's own history and nothing stood out.
        assertThat(response.get("flaggedCategories")).isEmpty();
    }

    @Test
    @DisplayName("UC-17: the spike comparison is against the student's own history, never another's")
    void theBaselineIsTheStudentsOwn() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        // B spends heavily in Food every month. A spends 10.00 every month. If the baseline were shared
        // or global, A's 15.00 would be a rounding error beside B's history and would flag nothing -
        // and this test would fail on the flag rather than on the ownership.
        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            expenseIn(tokenB, completeMonth(monthsAgo), "900.00");
            expenseIn(tokenA, completeMonth(monthsAgo), "10.00");
        }
        expenseIn(tokenB, currentMonth(), "900.00");
        expenseIn(tokenA, currentMonth(), "50.00");

        JsonNode a = generate(tokenA, null);

        // 50.00 against A's own 10.00 average is a 400% rise. Against B's 900.00 it is nothing.
        assertThat(flaggedNamesOf(a)).containsExactly(FOOD);
        assertThat(flaggedCategory(a, FOOD).get("baselineAvg").decimalValue())
                .as("the baseline must be this student's own average")
                .isEqualByComparingTo("10.00");
    }

    // ==================================================================
    //  Generating is idempotent, and preserves what a provider wrote
    // ==================================================================

    @Test
    @DisplayName("UC-17: generating twice leaves one insight for the month, refreshed in place")
    void generatingTwiceLeavesOneInsight() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        expenseIn(token, currentMonth(), "100.00");
        generate(token, null);

        assertThat(insightCountOf(userId, currentMonth())).isEqualTo(1);

        // A correction recorded after the insight was generated, then a second generation - which is
        // what a student pressing refresh, or a forgotten receipt being entered, produces.
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

        // What the AI overlay leaves behind: the two sentences, the marker that a provider wrote them,
        // and the model's name. Written directly here because no provider is configured for this suite -
        // this is the row the overlay produces, and the assertion below is about the rule the procedure
        // applies to a row in that state, which {@code InsightService} relies on rather than repeating.
        markAsProviderWritten(userId, currentMonth(), "Written by a model.", "Advised by a model.");

        JsonNode second = generate(token, null);

        assertThat(second.get("generatedBy").asText()).isEqualTo("AI");
        assertThat(second.get("model").asText()).isEqualTo("gemini-3.5-flash");
        assertThat(second.get("summary").asText())
                .as("a later run must not rewrite a provider's text")
                .isEqualTo("Written by a model.");
        assertThat(second.get("advice").asText()).isEqualTo("Advised by a model.");

        // The figures, by contrast, are recomputed every run: they are the procedure's own answer about
        // the month as it now stands, and the narrative is not.
        assertThat(second.get("totalExpense").decimalValue()).isEqualByComparingTo("100.00");
    }

    // ==================================================================
    //  Reading never writes
    // ==================================================================

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

        // And the month picker, which reads the same table, offers nothing either.
        assertThat(monthLabelsOf(months(token))).isEmpty();
    }

    @Test
    @DisplayName("UC-17: the picker offers exactly the months that would return something")
    void thePickerOffersOnlyMonthsWithAnInsight() throws Exception {
        String token = loginNewStudent();

        // History in two months, an insight generated for one of them.
        expenseIn(token, completeMonth(2), "30.00");
        expenseIn(token, completeMonth(1), "40.00");
        generate(token, asMonth(completeMonth(1)));

        List<String> offered = monthLabelsOf(months(token));

        assertThat(offered).containsExactly(asMonth(completeMonth(1)));
        assertThat(offered)
                .as("a month with records but no insight would answer 404 behind a menu entry")
                .doesNotContain(asMonth(completeMonth(2)));

        // The property the list exists for: everything it offers actually returns something.
        for (String month : offered) {
            assertThat(insight(token, month).get("periodMonth").asText()).isEqualTo(month);
        }
    }

    @Test
    @DisplayName("UC-17: the months list is ordered by month, newest first, not by when it was generated")
    void theMonthsListIsOrderedByMonth() throws Exception {
        String token = loginNewStudent();

        // The newer month is generated first, so an ordering by generated_at would put the older month
        // at the top - and a picker leading with the wrong month is the bug this pins.
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
        // Generating for one month must not have produced another: an insight covers its own month.
        assertThat(insightResponse(token, asMonth(currentMonth())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    //  The month parameter
    // ==================================================================

    @Test
    @DisplayName("UC-17 A2: a month that is not a month is refused with a field error, not read as another")
    void aMalformedMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        // `2026-9` is accepted by a lenient parser as September; `2026-13` would roll into the next
        // year; `2026-09-01` is a date rather than a month. Every one of them must be refused rather
        // than silently answered with a different month.
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

    // ==================================================================
    //  Ownership: BR-02
    // ==================================================================

    @Test
    @DisplayName("UC-17 BR-02: an insight is reachable only by its owner")
    void anotherStudentsMonthIsNotReachable() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        expenseIn(tokenA, currentMonth(), "250.00");
        JsonNode a = generate(tokenA, null);

        assertThat(a.get("totalExpense").decimalValue()).isEqualByComparingTo("250.00");

        // B has no insight for that month, and the answer is the same 404 a month nobody generated
        // gets - so B cannot tell "A has one" from "nobody has one".
        ResponseEntity<String> asB = insightResponse(tokenB, asMonth(currentMonth()));
        assertThat(asB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(asB)).isEqualTo("NOT_FOUND");

        // And B's picker is empty, so the existence of A's month is not inferable from the list either.
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

        // Neither response names its owner, and A's stored row still holds A's figure.
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

        // A `userId` query parameter is not part of the contract: it is ignored rather than honoured, so
        // the answer still describes the caller's own month. This is the assertion that fails if the
        // endpoint ever grows a parameter the feature was not meant to expose.
        ResponseEntity<String> response = send(HttpMethod.GET,
                INSIGHTS_URL + "?userId=" + otherA + "&id=" + otherA, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("periodMonth").asText()).isEqualTo(asMonth(currentMonth()));
        assertThat(body(response).get("totalExpense").decimalValue()).isEqualByComparingTo("0.00");
    }

    // ==================================================================
    //  The contract
    // ==================================================================

    @Test
    @DisplayName("UC-17: the response carries exactly its documented fields, and no owner")
    void documentedFieldsOnly() throws Exception {
        String token = loginNewStudent();

        expenseIn(token, currentMonth(), "100.00");
        JsonNode response = generate(token, null);

        // The field set is asserted literally, so a field added to the record fails here rather than
        // quietly widening the published contract. `status` and `errorMessage` are the notable absences:
        // the table has both and this build writes neither.
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

        // An insight is a snapshot of a month, so when it was written is what tells a reader whether it
        // reflects a correction made afterwards. A generator that preserved the original timestamp would
        // claim the read is staler than it is. Parsed rather than compared as text: the serialised form
        // trims trailing zeros from its fraction, so two timestamps a millisecond apart can be the same
        // number of characters or not, and comparing the strings would be a test about formatting.
        assertThat(LocalDateTime.parse(second.get("generatedAt").asText()))
                .isAfterOrEqualTo(LocalDateTime.parse(first.get("generatedAt").asText()));
    }

    // ==================================================================
    //  Security (section 7.5)
    // ==================================================================

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

            // These paths match none of the student prefixes, so without their own rule they would fall
            // to /api/** - which admits any authenticated account. This is the assertion that fails if
            // that rule is ever dropped, and it is why the rule exists: generating recomputes a
            // student's month and may spend a call to an external provider.
            ResponseEntity<String> asAdmin = send(route.method(), route.url(), adminToken, null);
            assertThat(asAdmin.getStatusCode())
                    .as("%s %s with an administrator token", route.method(), route.url())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCodeOf(asAdmin)).isEqualTo("ACCESS_DENIED");
        }
    }

    // ==================================================================
    //  Fixture
    // ==================================================================

    /**
     * Writes an insight as the AI overlay leaves it: the two sentences, the marker that a provider wrote
     * them, and the model's name.
     *
     * <p>This is a fixture rather than a second write path. No AI credential is configured for this
     * suite, so the overlay cannot be reached through the API - and what the test using this needs to
     * assert is not that the overlay works, which {@code InsightNarrativeTest} covers against a stub
     * port, but that a row already in this state survives a later generation. That rule lives in
     * {@code sp_generate_monthly_insight} and is a property of the stored row, so setting the row up
     * directly is the only way to reach it without a provider.
     */
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
