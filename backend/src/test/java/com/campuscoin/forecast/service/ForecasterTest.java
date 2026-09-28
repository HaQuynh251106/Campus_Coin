package com.campuscoin.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.forecast.entity.MonthTotals;

class ForecasterTest {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final LocalDate AUGUST = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    private final Forecaster forecaster = new Forecaster();

    private static MonthTotals month(LocalDate periodMonth, String income, String expense) {
        return new MonthTotals(periodMonth, new BigDecimal(income), new BigDecimal(expense));
    }

    @Test
    @DisplayName("No complete month projects nothing, rather than zero")
    void noHistoryProjectsNothing() {

        assertThat(forecaster.project(List.of())).isEmpty();
    }

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

        assertThat(forecaster.project(newestFirst).orElseThrow().projectedExpense())
                .isEqualByComparingTo(
                        forecaster.project(oldestFirst).orElseThrow().projectedExpense());
    }

    @Test
    @DisplayName("The mean is rounded to whole cents, half up")
    void theMeanIsRoundedHalfUp() {

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

        var projection = forecaster.project(List.of(
                month(JULY, "0.01", "0.00"),
                month(AUGUST, "0.00", "0.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("0.01");
        assertThat(projection.get().projectedIncome().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("A third that does not divide evenly is rounded once, at the end")
    void aRepeatingMeanIsRoundedOnce() {

        var projection = forecaster.project(List.of(
                month(JULY, "0.01", "0.00"),
                month(AUGUST, "0.00", "0.00"),
                month(SEPTEMBER, "0.00", "0.00")));

        assertThat(projection.get().projectedIncome()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Months that are present but zero are averaged as zero")
    void aZeroMonthIsAveragedAsZero() {

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

        assertThat(forecaster.project(List.of(month(AUGUST, "1.00", "1.00")))
                .get().basedOnMonths()).isEqualTo(1);
        assertThat(forecaster.project(List.of(
                month(AUGUST, "1.00", "1.00"),
                month(SEPTEMBER, "1.00", "1.00")))
                .get().basedOnMonths()).isEqualTo(2);
    }
}
