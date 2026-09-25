package com.campuscoin.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-15's spending breakdown over the real HTTP stack: request, security filter, controller, service,
 * DAO, MySQL.
 *
 * <p>This suite exists separately from {@link ReportsApiIT} because the endpoint's defining property
 * is a refusal. The daily and weekly views derive their range from {@code CURDATE()} inside the
 * database session, so unlike the rest of a report they cannot be asked about another month. The
 * suite therefore spends most of its assertions on the cases that must fail - a previous month, a
 * window outside the current one, an inverted window, an unknown granularity - because the failure
 * mode being guarded against is a request answered from the wrong month, and no successful response
 * would reveal it.
 *
 * <p>Each test registers its own student with a random address, so no test depends on another's rows.
 */
class SpendingReportApiIT extends AbstractReportsApiIT {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================================================================
    //  The window
    // ==================================================================

    @Test
    @DisplayName("UC-15: with no parameters the window is the whole of the current month")
    void theDefaultWindowIsTheWholeMonth() throws Exception {
        String token = loginNewStudent();

        JsonNode series = spending(token, null);

        assertThat(series.get("granularity").asText()).isEqualTo("DAILY");
        assertThat(series.get("from").asText()).isEqualTo(thisMonth().format(DAY));
        assertThat(series.get("to").asText()).isEqualTo(lastDayOfMonthKey(thisMonth()));
        assertThat(series.get("currency").asText()).isEqualTo("USD");
        assertThat(series.get("points")).isEmpty();
        assertThat(new BigDecimal(series.get("totalExpense").asText()))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-15: a window may be narrowed within the month, and the response says so")
    void aWindowWithinTheMonthIsHonoured() throws Exception {
        String token = loginNewStudent();

        LocalDate inside = thisMonthOn(6);
        LocalDate outside = thisMonthOn(1).equals(inside) ? null : thisMonthOn(1);

        createTransaction(token, defaultCategoryId(FOOD), "24.00", inside, "Campus Cafe");
        if (outside != null) {
            createTransaction(token, defaultCategoryId(FOOD), "99.00", outside, "Earlier");
        }

        String query = "from=" + inside.format(DAY) + "&to=" + inside.format(DAY);
        JsonNode series = spending(token, query);

        assertThat(series.get("from").asText()).isEqualTo(inside.format(DAY));
        assertThat(series.get("to").asText()).isEqualTo(inside.format(DAY));
        assertThat(new BigDecimal(series.get("totalExpense").asText()))
                .isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("UC-15: naming the current month explicitly is accepted, not refused")
    void theCurrentMonthMayBeNamedExplicitly() throws Exception {
        String token = loginNewStudent();

        JsonNode series = spending(token, "month=" + monthKey(thisMonth()));

        assertThat(series.get("from").asText()).isEqualTo(thisMonth().format(DAY));
    }

    // ==================================================================
    //  The refusals - a month the views cannot answer about
    // ==================================================================

    @Test
    @DisplayName("UC-15: another month is refused rather than answered from the current one")
    void anotherMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response =
                spendingResponse(token, "month=" + monthKey(monthBefore(1)));

        // The refusal is the point. Answering 200 with the current month's bars under the requested
        // month's heading would be a wrong answer, and nothing in the payload would reveal it.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                .isEqualTo("month");
    }

    @Test
    @DisplayName("UC-15: a future month is refused for the same reason")
    void aFutureMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response =
                spendingResponse(token, "month=" + monthKey(thisMonth().plusMonths(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("UC-15: a window reaching outside the current month is refused")
    void aWindowOutsideTheMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        LocalDate lastMonth = monthBefore(1);
        ResponseEntity<String> fromOutside = spendingResponse(token,
                "from=" + lastMonth.withDayOfMonth(15).format(DAY));
        ResponseEntity<String> toOutside = spendingResponse(token,
                "to=" + thisMonth().plusMonths(1).withDayOfMonth(2).format(DAY));

        assertThat(fromOutside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(fromOutside).get("fieldErrors").get(0).get("field").asText())
                .isEqualTo("from");
        assertThat(toOutside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(toOutside).get("fieldErrors").get(0).get("field").asText())
                .isEqualTo("to");
    }

    @Test
    @DisplayName("UC-15: an inverted window is refused")
    void anInvertedWindowIsRefused() throws Exception {
        String token = loginNewStudent();

        LocalDate from = thisMonthOn(8);
        LocalDate to = thisMonthOn(6);

        // Only meaningful when the two clamp to different days; on the first day of a month both are
        // the same date and the request is valid, so the test skips rather than asserting a refusal
        // the input does not warrant.
        if (from.equals(to)) {
            assertThat(spendingResponse(token, "from=" + from.format(DAY) + "&to=" + to.format(DAY))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            return;
        }

        ResponseEntity<String> response = spendingResponse(token,
                "from=" + from.format(DAY) + "&to=" + to.format(DAY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                .isEqualTo("from");
    }

    @Test
    @DisplayName("UC-15: an unknown granularity is refused with a field error naming it")
    void anUnknownGranularityIsRefused() throws Exception {
        String token = loginNewStudent();

        for (String granularity : List.of("MONTHLY", "hourly", "DAILY-")) {
            ResponseEntity<String> response =
                    spendingResponse(token, "granularity=" + granularity);
            assertThat(response.getStatusCode())
                    .as("granularity=%s", granularity)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                    .isEqualTo("granularity");
        }
    }

    @Test
    @DisplayName("UC-15: a malformed window edge is refused, not coerced")
    void aMalformedWindowEdgeIsRefused() throws Exception {
        String token = loginNewStudent();

        for (String edge : List.of("2026-9-1", "01/09/2026", "yesterday")) {
            ResponseEntity<String> response = spendingResponse(token, "from=" + edge);
            assertThat(response.getStatusCode())
                    .as("from=%s", edge)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        }
    }

    // ==================================================================
    //  DAILY
    // ==================================================================

    @Test
    @DisplayName("UC-15: a daily point is one day's spending, and quiet days are absent")
    void dailyPointsCoverOnlyTheDaysWithSpending() throws Exception {
        String token = loginNewStudent();

        LocalDate day = thisMonthOn(6);
        createTransaction(token, defaultCategoryId(FOOD), "20.00", day, "Campus Cafe");
        Long second = createTransaction(token, defaultCategoryId(FOOD), "4.00", day, "Canteen");

        JsonNode series = spending(token, "granularity=DAILY");
        JsonNode points = series.get("points");

        // One transaction on one day is one bar - the month's other days are missing rather than
        // zero, so a client draws its axis from `from`/`to` and not from the array's length.
        assertThat(points).hasSize(1);
        assertThat(points.get(0).get("intervalStart").asText()).isEqualTo(day.format(DAY));
        assertThat(points.get(0).get("intervalEnd").asText()).isEqualTo(day.format(DAY));
        assertThat(new BigDecimal(points.get(0).get("totalExpense").asText()))
                .isEqualByComparingTo("24.00");
        assertThat(points.get(0).get("transactionCount").asLong()).isEqualTo(2);

        // The window total is the sum of the bars.
        assertThat(new BigDecimal(series.get("totalExpense").asText()))
                .isEqualByComparingTo("24.00");

        deleteTransaction(token, second);
        assertThat(spending(token, "granularity=DAILY").get("points")).hasSize(1);
    }

    @Test
    @DisplayName("UC-15: one bar per day, with the day's records summed and ordered oldest first")
    void dailyPointsSumPerDayAndAreOrdered() throws Exception {
        String token = loginNewStudent();

        LocalDate first = thisMonthOn(4);
        LocalDate second = thisMonthOn(9);
        Set<LocalDate> distinct = new LinkedHashSet<>(List.of(first, second));

        createTransaction(token, defaultCategoryId(FOOD), "10.00", first, "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "6.00", first, "Bus pass");
        createTransaction(token, defaultCategoryId(FOOD), "30.00", second, "Groceries");

        JsonNode points = spending(token, "granularity=DAILY").get("points");

        assertThat(points).hasSize(distinct.size());

        BigDecimal expectedTotal = BigDecimal.ZERO;
        LocalDate previous = null;
        for (JsonNode point : points) {
            LocalDate start = LocalDate.parse(point.get("intervalStart").asText());
            if (previous != null) {
                assertThat(start).isAfter(previous);
            }
            previous = start;
            expectedTotal = expectedTotal.add(new BigDecimal(point.get("totalExpense").asText()));
        }

        // 10 + 6 on the first day, 30 on the second, unless the two clamped to the same day.
        BigDecimal expected = distinct.size() == 1 ? new BigDecimal("46.00") : new BigDecimal("46.00");
        assertThat(expectedTotal).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("UC-15, BR-09: a deleted record leaves the daily series")
    void deletedRecordsLeaveTheDailySeries() throws Exception {
        String token = loginNewStudent();

        LocalDate day = thisMonthOn(6);
        createTransaction(token, defaultCategoryId(FOOD), "20.00", day, "Campus Cafe");
        Long deleted = createTransaction(token, defaultCategoryId(FOOD), "9.00", day, "Canteen");

        deleteTransaction(token, deleted);

        JsonNode points = spending(token, "granularity=DAILY").get("points");
        assertThat(points).hasSize(1);
        assertThat(new BigDecimal(points.get(0).get("totalExpense").asText()))
                .isEqualByComparingTo("20.00");
        assertThat(points.get(0).get("transactionCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-15: income is not spending, so the series ignores it")
    void incomeIsNotPartOfTheSpendingSeries() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

        JsonNode series = spending(token, "granularity=DAILY");

        assertThat(series.get("points")).isEmpty();
        assertThat(new BigDecimal(series.get("totalExpense").asText()))
                .isEqualByComparingTo("0.00");
    }

    // ==================================================================
    //  WEEKLY
    // ==================================================================

    @Test
    @DisplayName("UC-15, VĐ-10: a weekly point is a real Monday-to-Sunday ISO week")
    void weeklyPointsAreRealIsoWeeks() throws Exception {
        String token = loginNewStudent();

        LocalDate day = thisMonthOn(6);
        createTransaction(token, defaultCategoryId(FOOD), "24.00", day, "Campus Cafe");

        JsonNode points = spending(token, "granularity=WEEKLY").get("points");

        assertThat(points).hasSize(1);

        LocalDate start = LocalDate.parse(points.get(0).get("intervalStart").asText());
        LocalDate end = LocalDate.parse(points.get(0).get("intervalEnd").asText());

        // The week is the ISO week the day falls in, and its boundaries are the database's own:
        // the Monday on or before the day, and six days later.
        LocalDate expectedMonday = day.minusDays(day.getDayOfWeek().getValue() - 1L);
        assertThat(start).isEqualTo(expectedMonday);
        assertThat(start.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(end).isEqualTo(expectedMonday.plusDays(6));

        assertThat(new BigDecimal(points.get(0).get("totalExpense").asText()))
                .isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("UC-15, VĐ-10: a week is never split by the month boundary")
    void aWeekMayReachOutsideTheMonthAndIsNotClipped() throws Exception {
        String token = loginNewStudent();

        LocalDate firstOfMonth = thisMonth();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", firstOfMonth, "Rent share");

        JsonNode points = spending(token, "granularity=WEEKLY").get("points");

        assertThat(points).hasSize(1);
        LocalDate start = LocalDate.parse(points.get(0).get("intervalStart").asText());

        // The first of the month falls in the ISO week beginning on its own Monday, which may be in
        // the previous month. The view reports the real week rather than clipping it to the month, so
        // the point is whole and its start is the Monday - not the first of the month.
        assertThat(start).isEqualTo(
                firstOfMonth.minusDays(firstOfMonth.getDayOfWeek().getValue() - 1L));
        assertThat(start.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        // The view filters transactions to the month before grouping, so the bar's dates may be wider
        // than the month while the amount it carries cannot be. When the month starts on a Monday
        // there is nothing outside it, and both cases are correct for a report whose unit is the week.
        if (firstOfMonth.getDayOfWeek() != DayOfWeek.MONDAY) {
            assertThat(start).isBefore(firstOfMonth);
        }
    }

    @Test
    @DisplayName("UC-15: over the whole month the two granularities report the same spending")
    void dailyAndWeeklyAgreeOverTheWholeMonth() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "12.00", thisMonthOn(7), "Bus pass");

        BigDecimal daily = new BigDecimal(spending(token, "granularity=DAILY")
                .get("totalExpense").asText());
        BigDecimal weekly = new BigDecimal(spending(token, "granularity=WEEKLY")
                .get("totalExpense").asText());

        // Both views filter the transactions to the current month before grouping, so the only
        // difference between them is how wide a bar is - not which records are counted. A weekly
        // bar's dates may reach outside the month, but the amount it carries cannot.
        assertThat(daily).isEqualByComparingTo("36.00");
        assertThat(weekly).isEqualByComparingTo("36.00");
    }

    @Test
    @DisplayName("UC-15: over a narrowed window a weekly series is returned whole, so it can exceed "
            + "the daily one")
    void aNarrowedWindowReturnsWholeWeeks() throws Exception {
        String token = loginNewStudent();

        LocalDate day = thisMonthOn(24);
        LocalDate monday = day.minusDays(day.getDayOfWeek().getValue() - 1L);

        // The bar is the ISO week, so a second record earlier in that same week is inside the bar but
        // outside a window that names only `day`.
        boolean weekStartsThisMonth = !monday.isBefore(thisMonth()) && monday.isBefore(day);

        createTransaction(token, defaultCategoryId(FOOD), "30.00", day, "Campus Cafe");
        if (weekStartsThisMonth) {
            createTransaction(token, defaultCategoryId(TRANSPORT), "10.00", monday, "Bus pass");
        }

        String window = "from=" + day.format(DAY) + "&to=" + day.format(DAY);
        BigDecimal daily = new BigDecimal(spending(token, "granularity=DAILY&" + window)
                .get("totalExpense").asText());
        BigDecimal weekly = new BigDecimal(spending(token, "granularity=WEEKLY&" + window)
                .get("totalExpense").asText());

        assertThat(daily).isEqualByComparingTo("30.00");

        // The overlap predicate returns a bar that merely touches the window, with all of its own
        // week's spending - which is why a weekly total can be larger than the daily one over the
        // same narrow window, though neither can exceed the month's expense.
        if (weekStartsThisMonth) {
            assertThat(weekly).isEqualByComparingTo("40.00");
            assertThat(weekly).isGreaterThan(daily);
        } else {
            assertThat(weekly).isEqualByComparingTo(daily);
        }
    }

    // ==================================================================
    //  The published contract and access
    // ==================================================================

    @Test
    @DisplayName("Section 7.2: the series response carries exactly the documented fields")
    void theSeriesCarriesExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");

        JsonNode series = spending(token, "granularity=WEEKLY");

        assertThat(fieldNamesOf(series))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_SERIES_FIELDS);
        assertThat(fieldNamesOf(series.get("points").get(0)))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_POINT_FIELDS);
    }

    @Test
    @DisplayName("Section 7: the spending series needs a student's token like every other report")
    void theSpendingSeriesRequiresAStudentToken() throws Exception {
        assertThat(send(HttpMethod.GET, SPENDING_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> asAdmin = send(HttpMethod.GET, SPENDING_URL, adminLogin(), null);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(asAdmin)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("BR-02: the spending series only ever contains the caller's own records")
    void theSpendingSeriesIsScopedToTheCaller() throws Exception {
        String alice = loginNewStudent();
        String bob = loginNewStudent();

        createTransaction(alice, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Alice's canteen");
        createTransaction(bob, defaultCategoryId(FOOD), "400.00", thisMonthOn(6), "Bob's rent");

        assertThat(new BigDecimal(spending(alice, null).get("totalExpense").asText()))
                .isEqualByComparingTo("24.00");
        assertThat(new BigDecimal(spending(bob, null).get("totalExpense").asText()))
                .isEqualByComparingTo("400.00");
    }
}
