package com.campuscoin.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-25 — a student's projected next month, end to end over HTTP.
 *
 * <p>Everything here runs against a real MySQL with the project's own views loaded, because the figures
 * under test are not computed in the Java alone: the month totals are
 * {@code v_monthly_income_expense}'s, its live-only filter is what a trashed record tests, and the
 * month the baseline stops at is the application's clock in the configured zone (VĐ-10). A mocked
 * view would let every arithmetic assertion here pass while the query said something else.
 *
 * <p>What this class is trying to break:
 *
 * <ul>
 *   <li>that the projection includes the month in progress, which would make it depend on the day it
 *       was asked and fall as the month went on;</li>
 *   <li>that the window is unbounded, so a student with years of history is projected from all of it
 *       rather than from their recent months;</li>
 *   <li>that a month with no record is read as a month of zeroes, which would drag every projection
 *       down;</li>
 *   <li>that "no history" is reported as "0.00 projected" instead of as the absence it is;</li>
 *   <li>that a student can see, or be projected from, another student's months;</li>
 *   <li>that the path is behind the student role rule and not the authenticated catch-all.</li>
 * </ul>
 */
class ForecastApiIT extends AbstractForecastApiIT {

    // ==================================================================
    //  The projection
    // ==================================================================

    @Test
    @DisplayName("Three complete months project their average, and the months are the evidence")
    void projectsTheAverageOfThreeCompleteMonths() throws Exception {
        String token = loginNewStudent();

        buildMonth(token, completeMonth(3), "300.00", "100.00");
        buildMonth(token, completeMonth(2), "300.00", "200.00");
        buildMonth(token, completeMonth(1), "300.00", "300.00");

        JsonNode response = forecast(token);

        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(3);
        assertThat(response.get("projected").get("income").decimalValue())
                .isEqualByComparingTo("300.00");
        assertThat(response.get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("200.00");
        // Savings is the net of the projections, not the average of the monthly nets: 300 - 200. The
        // two differ whenever the months are uneven, so this pins which one is published.
        assertThat(response.get("projected").get("savings").decimalValue())
                .isEqualByComparingTo("100.00");

        assertThat(recentMonthLabelsOf(response)).containsExactly(
                MONTH.format(completeMonth(3)),
                MONTH.format(completeMonth(2)),
                MONTH.format(completeMonth(1)));
    }

    @Test
    @DisplayName("The month in progress is reported separately and left out of the baseline")
    void theCurrentMonthIsExcludedFromTheBaseline() throws Exception {
        String token = loginNewStudent();

        buildMonth(token, completeMonth(2), "300.00", "100.00");
        buildMonth(token, completeMonth(1), "300.00", "100.00");
        // A large expense this month. If it entered the baseline it would move the projection, so the
        // assertion below is what proves the exclusion rather than merely restating it.
        expenseIn(token, currentMonth(), "900.00");
        incomeIn(token, currentMonth(), "50.00");

        JsonNode response = forecast(token);

        assertThat(recentMonthLabelsOf(response)).doesNotContain(MONTH.format(currentMonth()));
        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(2);
        assertThat(response.get("projected").get("expense").decimalValue())
                .as("the 900.00 spent this month must not enter the baseline")
                .isEqualByComparingTo("100.00");
        assertThat(response.get("projected").get("income").decimalValue())
                .isEqualByComparingTo("300.00");

        // And the same figures are reported as the fact they are, under their own block.
        assertThat(response.get("currentMonthTotals").get("income").decimalValue())
                .isEqualByComparingTo("50.00");
        assertThat(response.get("currentMonthTotals").get("expense").decimalValue())
                .isEqualByComparingTo("900.00");
        assertThat(response.get("currentMonthTotals").get("net").decimalValue())
                .isEqualByComparingTo("-850.00");
    }

    @Test
    @DisplayName("One complete month is enough to project, and reports that it rests on one")
    void oneCompleteMonthIsEnough() throws Exception {
        String token = loginNewStudent();
        buildMonth(token, completeMonth(1), "400.00", "250.00");

        JsonNode response = forecast(token);

        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(1);
        assertThat(response.get("recentMonths")).hasSize(1);
        assertThat(response.get("projected").get("income").decimalValue())
                .isEqualByComparingTo("400.00");
        assertThat(response.get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("The window is capped at the three most recent complete months")
    void theWindowIsCappedAtThreeMonths() throws Exception {
        String token = loginNewStudent();

        // Four complete months, with expenses 10, 20, 30, 40 from oldest to newest. The three most
        // recent average 30.00; all four would average 25.00. So the figure below distinguishes a
        // bounded window that keeps the recent months from one that keeps the oldest or keeps all.
        buildMonth(token, completeMonth(4), "100.00", "10.00");
        buildMonth(token, completeMonth(3), "100.00", "20.00");
        buildMonth(token, completeMonth(2), "100.00", "30.00");
        buildMonth(token, completeMonth(1), "100.00", "40.00");

        JsonNode response = forecast(token);

        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(3);
        assertThat(response.get("recentMonths")).hasSize(3);
        assertThat(recentMonthLabelsOf(response))
                .as("the cap must keep the months nearest the one being predicted")
                .doesNotContain(MONTH.format(completeMonth(4)));
        assertThat(response.get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("The evidence is the student's own view rows, oldest first, with their nets")
    void theEvidenceMatchesTheDatabase() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        buildMonth(token, completeMonth(2), "500.00", "125.50");
        buildMonth(token, completeMonth(1), "200.00", "75.25");

        JsonNode response = forecast(token);

        for (JsonNode month : response.get("recentMonths")) {
            LocalDate period = LocalDate.parse(month.get("periodMonth").asText() + "-01");
            StoredMonth stored = monthTotalsOf(userId, period);

            assertThat(stored).as("view row for %s", period).isNotNull();
            assertThat(month.get("income").decimalValue())
                    .isEqualByComparingTo(stored.income());
            assertThat(month.get("expense").decimalValue())
                    .isEqualByComparingTo(stored.expense());
            assertThat(month.get("net").decimalValue())
                    .as("net is this month's income less this month's expense")
                    .isEqualByComparingTo(
                            month.get("income").decimalValue().subtract(month.get("expense").decimalValue()));
        }

        assertThat(recentMonthLabelsOf(response))
                .as("oldest first, so the window reads as a history")
                .isSorted();
    }

    @Test
    @DisplayName("Spending on course to outrun income projects a negative saving")
    void savingsMayBeNegative() throws Exception {
        String token = loginNewStudent();
        buildMonth(token, completeMonth(2), "100.00", "300.00");
        buildMonth(token, completeMonth(1), "100.00", "300.00");

        JsonNode response = forecast(token);

        // Floored at zero this would read as "you will break even", which is the opposite of the answer.
        assertThat(response.get("projected").get("savings").decimalValue())
                .isEqualByComparingTo("-200.00");
    }

    // ==================================================================
    //  Absence is meaningful
    // ==================================================================

    @Test
    @DisplayName("A student with no history has no projection at all, and no current month either")
    void noHistoryProjectsNothing() throws Exception {
        String token = loginNewStudent();

        JsonNode response = forecast(token);

        // The field set is asserted literally, so the two absent blocks are provably absent rather than
        // present-and-null: `currentMonthTotals` and `projected` are not in the response at all.
        assertThat(fieldNamesOf(response)).containsExactlyElementsOf(List.of(
                "nextMonth", "currentMonth", "basedOnMonths", "recentMonths"));

        assertThat(response.get("basedOnMonths").asInt()).isZero();
        assertThat(response.get("recentMonths")).isEmpty();
        // `get` answers Java null for a field the response does not carry at all, which is exactly the
        // shape being asserted: not `"projected": null` and not `"projected": {income: 0.00, ...}`, but
        // no `projected` key. A block of zeroes would read as a confident prediction of no spending.
        assertThat(response.get("projected"))
                .as("'no projection' must not be reported as '0.00 projected'")
                .isNull();
    }

    @Test
    @DisplayName("A student in their first month has a current month and still no projection")
    void aFirstMonthHasATotalAndNoProjection() throws Exception {
        String token = loginNewStudent();
        expenseIn(token, currentMonth(), "42.00");
        incomeIn(token, currentMonth(), "100.00");

        // A record dated today lands in the month in progress, which is not a complete month - so this
        // student has figures to report and nothing to project from. The two absences are independent,
        // and this is the case that shows it.
        JsonNode response = forecast(token);

        assertThat(response.get("currentMonthTotals").get("expense").decimalValue())
                .isEqualByComparingTo("42.00");
        assertThat(response.get("recentMonths")).isEmpty();
        assertThat(response.get("basedOnMonths").asInt()).isZero();
        assertThat(response.has("projected")).isFalse();
    }

    @Test
    @DisplayName("A month with no records is absent from the window, not counted as zero")
    void aGapMonthIsAbsentRatherThanZero() throws Exception {
        String token = loginNewStudent();

        // Months 3 and 1 hold records; month 2 holds none. The view emits no row for month 2, so the
        // projection averages the two months that exist. Treating the gap as a zero month would halve
        // the expense to 100.00, so the figure below is what pins the reading.
        buildMonth(token, completeMonth(3), "300.00", "100.00");
        buildMonth(token, completeMonth(1), "300.00", "300.00");

        JsonNode response = forecast(token);

        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(2);
        assertThat(response.get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("200.00");
        assertThat(recentMonthLabelsOf(response))
                .containsExactly(MONTH.format(completeMonth(3)), MONTH.format(completeMonth(1)));
    }

    // ==================================================================
    //  The month labels
    // ==================================================================

    @Test
    @DisplayName("The months come from the server's clock, and nextMonth follows currentMonth")
    void monthLabelsComeFromTheServer() throws Exception {
        String token = loginNewStudent();

        JsonNode response = forecast(token);

        assertThat(response.get("currentMonth").asText()).isEqualTo(MONTH.format(currentMonth()));
        assertThat(response.get("nextMonth").asText())
                .isEqualTo(MONTH.format(currentMonth().plusMonths(1)));
        // End to end that the two are consecutive, which a hard-coded pair could not be.
        assertThat(LocalDate.parse(response.get("nextMonth").asText() + "-01"))
                .isEqualTo(LocalDate.parse(response.get("currentMonth").asText() + "-01").plusMonths(1));
    }

    @Test
    @DisplayName("The endpoint takes no parameters, so a caller cannot choose a month or a window")
    void thereIsNothingForAClientToChoose() throws Exception {
        String token = loginNewStudent();
        buildMonth(token, completeMonth(1), "300.00", "100.00");

        // A `month` query parameter is not part of the contract: it is ignored rather than honoured, so
        // the response still describes the server's own current month. This is the assertion that fails
        // if the endpoint ever grows a parameter the feature was not meant to expose.
        ResponseEntity<String> response = send(org.springframework.http.HttpMethod.GET,
                FORECAST_URL + "?month=2020-01&months=99", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(response);
        assertThat(body.get("currentMonth").asText()).isEqualTo(MONTH.format(currentMonth()));
        assertThat(body.get("nextMonth").asText()).isEqualTo(MONTH.format(currentMonth().plusMonths(1)));
        assertThat(body.get("basedOnMonths").asInt()).isEqualTo(1);
    }

    // ==================================================================
    //  Ownership: BR-02
    // ==================================================================

    @Test
    @DisplayName("The forecast is built from the caller's own months and nobody else's")
    void theProjectionIsScopedToTheCaller() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        buildMonth(tokenA, completeMonth(1), "300.00", "100.00");
        buildMonth(tokenB, completeMonth(1), "900.00", "800.00");

        JsonNode a = forecast(tokenA);
        JsonNode b = forecast(tokenB);

        assertThat(a.get("projected").get("expense").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(b.get("projected").get("expense").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(recentMonthLabelsOf(a)).containsExactly(MONTH.format(completeMonth(1)));
        assertThat(recentMonthLabelsOf(b)).containsExactly(MONTH.format(completeMonth(1)));

        // Neither response names its owner, and neither can be made to describe the other student.
        assertThat(a.has("userId")).isFalse();
        assertThat(fieldNamesOf(a)).doesNotContain("email", "userId", "owner");
    }

    @Test
    @DisplayName("A trashed record leaves its month's totals")
    void aTrashedRecordLeavesTheBaseline() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        buildMonth(token, completeMonth(1), "300.00", "100.00");
        Long extra = expenseIn(token, completeMonth(1), "500.00");

        assertThat(forecast(token).get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("600.00");

        // The view sums live records only (is_deleted = 0), which is why this needs no repair step: the
        // trashed expense simply stops being part of the month.
        assertThat(send(org.springframework.http.HttpMethod.DELETE,
                TRANSACTIONS_URL + "/" + extra, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(monthTotalsOf(userId, completeMonth(1)).expense()).isEqualByComparingTo("100.00");
        assertThat(forecast(token).get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("A month emptied of live records drops out of the window")
    void anEmptiedMonthLeavesTheWindow() throws Exception {
        String token = loginNewStudent();

        Long income = incomeIn(token, completeMonth(2), "300.00");
        Long expense = expenseIn(token, completeMonth(2), "100.00");
        buildMonth(token, completeMonth(1), "300.00", "300.00");

        assertThat(forecast(token).get("basedOnMonths").asInt()).isEqualTo(2);

        for (Long id : List.of(income, expense)) {
            assertThat(send(org.springframework.http.HttpMethod.DELETE,
                    TRANSACTIONS_URL + "/" + id, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }

        // With both records trashed the view has no row for that month, so the window is one month and
        // the projection follows the month that is left. A zero-filled month would keep the count at 2
        // and halve the expense.
        JsonNode response = forecast(token);
        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(1);
        assertThat(recentMonthLabelsOf(response)).containsExactly(MONTH.format(completeMonth(1)));
        assertThat(response.get("projected").get("expense").decimalValue())
                .isEqualByComparingTo("300.00");
    }

    // ==================================================================
    //  The contract
    // ==================================================================

    @Test
    @DisplayName("Every present block carries exactly its documented fields, and no owner")
    void documentedFieldsOnly() throws Exception {
        String token = loginNewStudent();
        buildMonth(token, completeMonth(1), "300.00", "100.00");
        expenseIn(token, currentMonth(), "20.00");

        JsonNode response = forecast(token);

        assertThat(fieldNamesOf(response)).containsExactlyElementsOf(DOCUMENTED_TOP_FIELDS);
        assertThat(fieldNamesOf(response.get("currentMonthTotals")))
                .containsExactlyElementsOf(DOCUMENTED_CURRENT_FIELDS);
        assertThat(fieldNamesOf(response.get("projected")))
                .containsExactlyElementsOf(DOCUMENTED_PROJECTED_FIELDS);
        assertThat(response.get("recentMonths")).isNotEmpty();
        response.get("recentMonths")
                .forEach(month -> assertThat(fieldNamesOf(month))
                        .containsExactlyElementsOf(DOCUMENTED_MONTH_FIELDS));
    }

    @Test
    @DisplayName("basedOnMonths always equals the number of months in the evidence list")
    void basedOnMonthsMatchesTheEvidence() throws Exception {
        String token = loginNewStudent();
        buildMonth(token, completeMonth(3), "100.00", "10.00");
        buildMonth(token, completeMonth(1), "100.00", "20.00");

        JsonNode response = forecast(token);

        // The two are carried separately rather than derived, so this is the assertion that stops them
        // disagreeing: a client showing "based on 3 months" beside a two-month list would be a lie, and
        // a thin estimate is supposed to be visible rather than hidden.
        assertThat(response.get("basedOnMonths").asInt())
                .isEqualTo(response.get("recentMonths").size());
    }

    @Test
    @DisplayName("The seeded student's forecast agrees with the months the view holds for them")
    void theSeededStudentIsProjectedFromTheirOwnMonths() throws Exception {
        String token = seededStudentLogin();
        Long userId = userIdOf(token);

        JsonNode response = forecast(token);

        // A student with seeded history: whatever the seed contains, the response must be built from it
        // and only from it. Reading the count from the view rather than hard-coding it keeps this test
        // from breaking when the seed changes, while still proving the response was built from the rows.
        int monthsUpToWindow = Math.min(monthCountOf(userId), WINDOW_MONTHS);
        assertThat(response.get("basedOnMonths").asInt()).isEqualTo(monthsUpToWindow);
        assertThat(response.get("recentMonths")).hasSize(monthsUpToWindow);
    }

    // ==================================================================
    //  Security (section 7.5)
    // ==================================================================

    @Test
    @DisplayName("Section 7.5: no token is 401, and an administrator token is 403")
    void roleAndTokenAreEnforced() throws Exception {
        String adminToken = adminLogin();

        ResponseEntity<String> anonymous = forecastResponse(null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCodeOf(anonymous)).isEqualTo("UNAUTHENTICATED");

        // The path matches none of the student prefixes, so without its own rule it would fall to
        // /api/** - which admits any authenticated account. This is the assertion that fails if that
        // rule is ever dropped, and it is why the rule exists.
        ResponseEntity<String> asAdmin = forecastResponse(adminToken);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(asAdmin)).isEqualTo("ACCESS_DENIED");
    }
}
