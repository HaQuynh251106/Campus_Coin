package com.campuscoin.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class ReportsApiIT extends AbstractReportsApiIT {

    @Test
    @DisplayName("UC-15: a new student's report is valid, for the current month, and reports nothing")
    void newStudentReportIsEmptyButValid() throws Exception {
        String token = loginNewStudent();

        JsonNode report = report(token);

        assertThat(report.get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));
        assertThat(report.get("currency").asText()).isEqualTo("USD");

        JsonNode totals = report.get("totals");
        assertThat(fieldNamesOf(totals)).isEmpty();

        assertThat(report.get("expenseByCategory")).isEmpty();
        assertThat(report.get("incomeByCategory")).isEmpty();
    }

    @Test
    @DisplayName("UC-15: the totals are the month's live transactions, split by category type")
    void totalsCountIncomeAndExpenseByCategoryType() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "12.00", thisMonthOn(7),
                "Monthly bus pass");

        JsonNode totals = report(token).get("totals");

        assertThat(new BigDecimal(totals.get("income").asText())).isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(totals.get("expense").asText())).isEqualByComparingTo("36.00");
        assertThat(new BigDecimal(totals.get("net").asText())).isEqualByComparingTo("164.00");
        assertThat(totals.get("transactionCount").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("UC-15, BR-09: a record moved to the trash leaves the report's totals")
    void deletedTransactionsAreExcludedFromTheTotals() throws Exception {
        String token = loginNewStudent();

        Long food = defaultCategoryId(FOOD);
        createTransaction(token, food, "24.00", thisMonthOn(6), "Campus Cafe");
        Long toDelete = createTransaction(token, food, "30.00", thisMonthOn(7), "Bookshop");

        assertThat(new BigDecimal(report(token).at("/totals/expense").asText()))
                .isEqualByComparingTo("54.00");

        deleteTransaction(token, toDelete);

        JsonNode totals = report(token).get("totals");
        assertThat(new BigDecimal(totals.get("expense").asText())).isEqualByComparingTo("24.00");
        assertThat(totals.get("transactionCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-15: the report splits categories into an expense block and an income block")
    void categoriesAreSplitByTheirType() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "12.00", thisMonthOn(7), "Bus pass");
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

        JsonNode report = report(token);

        assertThat(categoryNames(report.get("expenseByCategory")))
                .containsExactlyInAnyOrder("Food", "Transport");
        assertThat(categoryNames(report.get("incomeByCategory")))
                .containsExactly("Allowance");

        for (JsonNode slice : report.get("expenseByCategory")) {
            assertThat(slice.get("type").asText()).isEqualTo("EXPENSE");
        }
        for (JsonNode slice : report.get("incomeByCategory")) {
            assertThat(slice.get("type").asText()).isEqualTo("INCOME");
        }
    }

    @Test
    @DisplayName("UC-15: a category slice carries its icon, colour, total, count and share")
    void categorySlicesCarryThePresentationColumnsAndTheirShare() throws Exception {
        String token = loginNewStudent();

        Long food = defaultCategoryId(FOOD);
        createTransaction(token, food, "30.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, food, "10.00", thisMonthOn(8), "Canteen");
        createTransaction(token, defaultCategoryId(TRANSPORT), "60.00", thisMonthOn(7), "Bus pass");

        JsonNode report = report(token);
        JsonNode expense = report.get("expenseByCategory");

        assertThat(categoryNames(expense)).containsExactly("Transport", "Food");

        JsonNode foodSlice = expense.get(1);
        assertThat(new BigDecimal(foodSlice.get("total").asText())).isEqualByComparingTo("40.00");
        assertThat(foodSlice.get("transactionCount").asLong()).isEqualTo(2);

        assertThat(foodSlice.get("categoryIcon").asText()).isEqualTo("utensils");
        assertThat(foodSlice.get("categoryColor").asText()).isEqualTo("#F97316");

        assertThat(new BigDecimal(foodSlice.get("percentage").asText()))
                .isEqualByComparingTo("40.00");
        assertThat(new BigDecimal(expense.get(0).get("percentage").asText()))
                .isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("UC-15: each share is its own category's, rounded to two decimals")
    void categorySharesAreEachTheirOwnAndAreRoundedIndependently() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(FOOD), "10.00", thisMonthOn(6), "Canteen");
        createTransaction(token, defaultCategoryId(TRANSPORT), "10.00", thisMonthOn(7), "Bus");
        createTransaction(token, defaultCategoryId(ENTERTAINMENT), "10.00", thisMonthOn(8), "Cinema");

        List<BigDecimal> shares = new ArrayList<>();
        for (JsonNode slice : report(token).get("expenseByCategory")) {
            shares.add(new BigDecimal(slice.get("percentage").asText()));
        }

        assertThat(shares).hasSize(3);
        assertThat(shares).allSatisfy(share -> assertThat(share).isEqualByComparingTo("33.33"));

        BigDecimal sum = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("99.99");
        assertThat(new BigDecimal("100.00").subtract(sum))
                .isLessThan(new BigDecimal("0.01").multiply(BigDecimal.valueOf(shares.size())));
    }

    @Test
    @DisplayName("UC-15: a category created by the student appears in the report like a default one")
    void personalCategoriesAppearInTheBreakdown() throws Exception {
        String token = loginNewStudent();

        Long personal = createCategory(token, "Coffee Runs", "EXPENSE");
        createTransaction(token, personal, "15.00", thisMonthOn(5), "Flat white");

        JsonNode expense = report(token).get("expenseByCategory");

        assertThat(expense).hasSize(1);
        assertThat(expense.get(0).get("categoryId").asLong()).isEqualTo(personal);

        assertThat(expense.get(0).get("categoryIcon").asText()).isEqualTo("tag");
        assertThat(expense.get(0).get("categoryColor").asText()).isEqualTo("#123456");
    }

    @Test
    @DisplayName("UC-15: an earlier month can be reported on, and is not the current month's figures")
    void anyMonthWithRecordsCanBeReportedOn() throws Exception {
        String token = loginNewStudent();

        Long food = defaultCategoryId(FOOD);
        createTransaction(token, food, "24.00", thisMonthOn(6), "This month");
        createTransaction(token, food, "99.00", monthBeforeOn(2, 10), "Two months ago");

        JsonNode earlier = report(token, monthKey(monthBefore(2)));

        assertThat(earlier.get("periodMonth").asText()).isEqualTo(monthKey(monthBefore(2)));
        assertThat(new BigDecimal(earlier.at("/totals/expense").asText()))
                .isEqualByComparingTo("99.00");
        assertThat(earlier.at("/totals/transactionCount").asLong()).isEqualTo(1);

        assertThat(new BigDecimal(report(token).at("/totals/expense").asText()))
                .isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("UC-15: a month with no records reports no figures rather than zeroes")
    void anEmptyMonthHasAbsentFiguresRatherThanZeroes() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");

        JsonNode empty = report(token, monthKey(monthBefore(3)));

        assertThat(empty.get("periodMonth").asText()).isEqualTo(monthKey(monthBefore(3)));

        assertThat(fieldNamesOf(empty.get("totals"))).isEmpty();
        assertThat(empty.get("expenseByCategory")).isEmpty();
        assertThat(empty.get("incomeByCategory")).isEmpty();
    }

    @Test
    @DisplayName("UC-15: a future month is answered honestly rather than refused")
    void aFutureMonthReportsNothing() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");

        JsonNode future = report(token, monthKey(thisMonth().plusMonths(1)));

        assertThat(fieldNamesOf(future.get("totals"))).isEmpty();
        assertThat(future.get("expenseByCategory")).isEmpty();
    }

    @Test
    @DisplayName("UC-15: a malformed month is refused with a field error naming the parameter")
    void malformedMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = reportResponse(token, "month=2026-13");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                .isEqualTo("month");
    }

    @Test
    @DisplayName("UC-15: a month that is not a month at all is refused, not parsed loosely")
    void unparseableMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        for (String month : List.of("September", "2026-9", "202609", "2026-09-01")) {
            ResponseEntity<String> response = reportResponse(token, "month=" + month);
            assertThat(response.getStatusCode())
                    .as("month=%s", month)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("BR-17, UAT-09: the six-month report returns exactly six rows, oldest first")
    void theTrendAlwaysHasSixMonthsOldestFirst() throws Exception {
        String token = loginNewStudent();

        JsonNode trend = report(token).get("sixMonthTrend");

        assertThat(trend).hasSize(6);
        for (int index = 0; index < 6; index++) {
            assertThat(trend.get(index).get("periodMonth").asText())
                    .isEqualTo(monthKey(monthBefore(5 - index)));
        }
    }

    @Test
    @DisplayName("BR-17, UAT-09: months with no data are present as 0.00, not omitted")
    void emptyMonthsInTheTrendAreZeroNotMissing() throws Exception {
        String token = loginNewStudent();

        createTransaction(token, defaultCategoryId(FOOD), "24.00", monthBeforeOn(3, 10),
                "Three months ago");

        JsonNode trend = report(token).get("sixMonthTrend");

        assertThat(trend).hasSize(6);
        for (JsonNode point : trend) {

            assertThat(fieldNamesOf(point))
                    .containsExactlyInAnyOrderElementsOf(DOCUMENTED_TREND_FIELDS);
        }

        JsonNode populated = trend.get(2);
        assertThat(populated.get("periodMonth").asText()).isEqualTo(monthKey(monthBefore(3)));
        assertThat(new BigDecimal(populated.get("expense").asText()))
                .isEqualByComparingTo("24.00");

        JsonNode quiet = trend.get(0);
        assertThat(new BigDecimal(quiet.get("income").asText())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(quiet.get("expense").asText())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(quiet.get("net").asText())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("BR-17: the trend is the current six months whatever month the report is for")
    void theTrendIsUnaffectedByTheSelectedMonth() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");

        List<String> currentTrend = monthKeys(report(token).get("sixMonthTrend"));
        List<String> earlierTrend = monthKeys(report(token, monthKey(monthBefore(4)))
                .get("sixMonthTrend"));

        assertThat(earlierTrend).isEqualTo(currentTrend);
        assertThat(currentTrend).containsExactly(
                monthKey(monthBefore(5)), monthKey(monthBefore(4)), monthKey(monthBefore(3)),
                monthKey(monthBefore(2)), monthKey(monthBefore(1)), monthKey(thisMonth()));
    }

    @Test
    @DisplayName("BR-02: a report only ever contains the caller's own records")
    void aReportContainsOnlyTheCallersRecords() throws Exception {
        String alice = loginNewStudent();
        String bob = loginNewStudent();

        createTransaction(alice, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Alice's canteen");
        createTransaction(bob, defaultCategoryId(FOOD), "400.00", thisMonthOn(6), "Bob's rent");

        JsonNode aliceReport = report(alice);
        JsonNode bobReport = report(bob);

        assertThat(new BigDecimal(aliceReport.at("/totals/expense").asText()))
                .isEqualByComparingTo("24.00");
        assertThat(new BigDecimal(bobReport.at("/totals/expense").asText()))
                .isEqualByComparingTo("400.00");

        assertThat(totalExpense(aliceReport.get("sixMonthTrend")))
                .isEqualByComparingTo("24.00");
        assertThat(totalExpense(bobReport.get("sixMonthTrend")))
                .isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("BR-02: no report parameter can name another student")
    void noParameterCanNameAnotherStudent() throws Exception {
        String alice = loginNewStudent();
        String bob = loginNewStudent();
        Long bobId = userIdOf(bob);

        createTransaction(bob, defaultCategoryId(FOOD), "400.00", thisMonthOn(6), "Bob's rent");

        JsonNode report = report(alice, null);
        assertThat(fieldNamesOf(report.get("totals"))).isEmpty();

        ResponseEntity<String> response = reportResponse(alice, "userId=" + bobId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNamesOf(body(response).get("totals"))).isEmpty();
    }

    @Test
    @DisplayName("Section 7.2: no report response carries a field the contract does not document")
    void theResponseCarriesExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

        JsonNode report = report(token);

        assertThat(fieldNamesOf(report))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_REPORT_FIELDS);
        assertThat(fieldNamesOf(report.get("totals")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_TOTALS_FIELDS);
        assertThat(fieldNamesOf(report.get("expenseByCategory").get(0)))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_CATEGORY_FIELDS);
        assertThat(fieldNamesOf(report.get("incomeByCategory").get(0)))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_CATEGORY_FIELDS);
    }

    @Test
    @DisplayName("Section 7: reading a report is refused without a token and for an administrator")
    void aReportRequiresAStudentToken() throws Exception {
        assertThat(send(HttpMethod.GET, REPORTS_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String adminToken = adminLogin();
        ResponseEntity<String> asAdmin = send(HttpMethod.GET, REPORTS_URL, adminToken, null);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(asAdmin)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("UC-15: reading a report changes nothing")
    void readingAReportChangesNoState() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        int before = countOf("SELECT COUNT(*) FROM transactions WHERE user_id = ?", userId);

        report(token);
        report(token);

        assertThat(countOf("SELECT COUNT(*) FROM transactions WHERE user_id = ?", userId))
                .isEqualTo(before);
    }

    private static List<String> categoryNames(JsonNode slices) {
        return slices.findValuesAsText("categoryName");
    }

    private static List<String> monthKeys(JsonNode points) {
        return points.findValuesAsText("periodMonth");
    }

    private static BigDecimal totalExpense(JsonNode points) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode point : points) {
            sum = sum.add(new BigDecimal(point.get("expense").asText()));
        }
        return sum;
    }
}
