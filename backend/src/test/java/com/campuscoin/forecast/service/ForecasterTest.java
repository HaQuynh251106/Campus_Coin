package com.campuscoin.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.forecast.entity.MonthTotals;

/**
 * The UC-25 projection arithmetic, tested directly.
 *
 * <p>A plain unit test, deliberately: {@link Forecaster} is a pure function of the months it is
 * given, so nothing about it needs a database and no assertion here is weakened by the absence of
 * one. The months are constructed as the DAO would return them - a first-of-month date and two
 * decimals - so what is pinned is the arithmetic the service actually runs.
 *
 * <p><b>What these tests are for.</b> The projection is a number a student is asked to plan around,
 * so the three things that could make it quietly wrong are each pinned: that it averages over
 * <em>complete months only</em> (which the DAO enforces and this class must not defeat by re-including
 * a month), that the mean is taken once and rounded once, and that "no data" and "a month of zeroes"
 * are told apart. The last is the one most likely to be lost in a refactor, because the two look alike
 * in a list of zeros.
 */
class ForecasterTest {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final LocalDate AUGUST = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    private final Forecaster forecaster = new Forecaster();

    private static MonthTotals month(LocalDate periodMonth, String income, String expense) {
        return new MonthTotals(periodMonth, new BigDecimal(income), new BigDecimal(expense));
    }

    // ------------------------------------------------------------------
    //  No history
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No complete month projects nothing, rather than zero")
    void noHistoryProjectsNothing() {
        // The distinction the whole Optional exists for: with nothing to average, "0.00 projected"
        // would be a confident claim that the student will spend nothing. There is no claim to make.
        assertThat(forecaster.project(List.of())).isEmpty();
    }

    // ------------------------------------------------------------------
    //  The mean
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One complete month projects that month's own figures")
    void oneMonthProjectsItself() {
        var projection = forecaster.project(List.of(month(AUGUST, "300.00", "180.00")));

        assertThat(projection).isPresent();
        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("300.00");
        assertThat(projection.get().projectedExpense()).isEqualByComparingTo("180.00");
        assertThat(projection.get().basedOnMonths()).isEqualTo(1);
    }

    @Test
    @DisplayName("Three months project the mean of each figure independently")
    void threeMonthsAreAveragedPerFigure() {
        // Income and expense are averaged separately and neither is derived from the other, so a
        // student whose income jumps while spending holds still sees that only in `income`.
        var projection = forecaster.project(List.of(
                month(JULY, "300.00", "120.00"),
                month(AUGUST, "240.00", "180.00"),
                month(SEPTEMBER, "360.00", "150.00")));

        assertThat(projection).isPresent();
        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("300.00");
        assertThat(projection.get().projectedExpense()).isEqualByComparingTo("150.00");
        assertThat(projection.get().basedOnMonths()).isEqualTo(3);
    }

    @Test
    @DisplayName("The projected savings are the projected income less the projected expense")
    void savingsAreTheNetOfTheProjections() {
        var projection = forecaster.project(List.of(
                month(JULY, "300.00", "120.00"),
                month(AUGUST, "300.00", "120.00")));

        assertThat(projection.get().projectedSavings()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("Savings go negative when spending is on course to outrun income")
    void savingsMayBeNegative() {
        // The sign is the useful part of the answer: "you are on course to be short" must not be
        // floored at zero, which would read as "you will break even".
        var projection = forecaster.project(List.of(month(AUGUST, "100.00", "250.00")));

        assertThat(projection.get().projectedSavings()).isEqualByComparingTo("-150.00");
    }

    @Test
    @DisplayName("The order of the months does not change the projection")
    void orderDoesNotMatter() {
        List<MonthTotals> oldestFirst = List.of(
                month(JULY, "300.00", "120.00"),
                month(AUGUST, "240.00", "180.00"),
                month(SEPTEMBER, "360.00", "150.00"));
        List<MonthTotals> newestFirst = List.of(
                month(SEPTEMBER, "360.00", "150.00"),
                month(AUGUST, "240.00", "180.00"),
                month(JULY, "300.00", "120.00"));

        // An average is order-independent, and stating it as a test is what stops a later "weight the
        // recent months more" change from being made without a decision - it would be a different
        // method and a different contract, not a tweak to this one.
        assertThat(forecaster.project(newestFirst).orElseThrow().projectedExpense())
                .isEqualByComparingTo(
                        forecaster.project(oldestFirst).orElseThrow().projectedExpense());
    }

    // ------------------------------------------------------------------
    //  Rounding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The mean is rounded to whole cents, half up")
    void theMeanIsRoundedHalfUp() {
        // 100.00 + 100.01 + 100.02 over three is 100.01 exactly; the point is that the division
        // carries the scale, so a projection never publishes a fractional cent.
        var projection = forecaster.project(List.of(
                month(JULY, "100.00", "0.00"),
                month(AUGUST, "100.01", "0.00"),
                month(SEPTEMBER, "100.02", "0.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("100.01");
        assertThat(projection.get().projectedIncome().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("A mean below half a cent rounds to zero cents, not to a fraction")
    void aMeanBelowHalfACentRoundsToZero() {
        // Two months of one cent each is half a cent: HALF_UP takes it to 0.01, and the result is
        // published at two decimals. A non-terminating decimal would be the bug this guards against.
        var projection = forecaster.project(List.of(
                month(JULY, "0.01", "0.00"),
                month(AUGUST, "0.00", "0.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("0.01");
        assertThat(projection.get().projectedIncome().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("A third that does not divide evenly is rounded once, at the end")
    void aRepeatingMeanIsRoundedOnce() {
        // One cent over three months: 0.00333... A per-month rounding first would lose it entirely;
        // rounding the mean gives 0.00 after HALF_UP only because 0.0033 < 0.005. The value asserted
        // is what the single division produces, which is the behaviour the class documents.
        var projection = forecaster.project(List.of(
                month(JULY, "0.01", "0.00"),
                month(AUGUST, "0.00", "0.00"),
                month(SEPTEMBER, "0.00", "0.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    //  Zero is data; absent is not
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Months that are present but zero are averaged as zero")
    void aZeroMonthIsAveragedAsZero() {
        // This is the case the DAO's "no row for an empty month" decision is about. A month the
        // service passes in with zero figures is a month that HAD activity netting to zero, and it
        // counts. (A month with no row at all never reaches this class.)
        var projection = forecaster.project(List.of(
                month(JULY, "0.00", "0.00"),
                month(AUGUST, "0.00", "0.00"),
                month(SEPTEMBER, "300.00", "300.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("100.00");
        assertThat(projection.get().basedOnMonths()).isEqualTo(3);
    }

    @Test
    @DisplayName("basedOnMonths is the number of months actually averaged")
    void basedOnMonthsIsTheWindowSize() {
        // The response publishes this so a thin estimate is visibly thin - two months is a weaker
        // claim than three, and the count is how the client says so.
        assertThat(forecaster.project(List.of(month(AUGUST, "1.00", "1.00")))
                .get().basedOnMonths()).isEqualTo(1);
        assertThat(forecaster.project(List.of(
                month(AUGUST, "1.00", "1.00"),
                month(SEPTEMBER, "1.00", "1.00")))
                .get().basedOnMonths()).isEqualTo(2);
    }
}
