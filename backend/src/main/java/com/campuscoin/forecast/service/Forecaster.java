package com.campuscoin.forecast.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.forecast.entity.MonthTotals;

/**
 * Projects a student's next month from their recent months (UC-25).
 *
 * <p><b>The method is a trailing average, and that is a decision rather than a placeholder.</b> UC-25
 * asks a student to be prepared for roughly what next month will cost; it does not ask for a
 * statistically defended estimate. A trailing average over the last few complete months is the
 * simplest thing that answers the question, it is the same shape as the six-month trend the reports
 * screen already shows (BR-17), and every figure in it is one the student can reproduce by hand from
 * their own history - so the projection can be explained rather than merely asserted. The brief
 * forbids introducing an ML platform for this, and it is right to: a model would be harder to test,
 * harder to justify to the student, and no more honest than "last three months, averaged".
 *
 * <p><b>Only complete months are averaged, and the caller supplies them that way.</b> The month in
 * progress holds only the records entered so far, so it is excluded from the baseline (see
 * {@code ForecastViewDao}) - a partial figure would make the prediction depend on the day it was
 * asked and would fall as the month went on, which is the opposite of a forecast. The window is
 * whatever the caller fetched; this class does not re-filter, so the one place that decides what
 * counts as a usable month is the query beside the service that calls it.
 *
 * <p><b>A month with no data is absent, not zero.</b> The view emits a row only for a month holding a
 * live record, so a student's gap month is simply not in the list. Averaging over the months that are
 * present is the honest reading: a month the student recorded nothing in does not tell us they spent
 * nothing, and treating it as zero would pull every future forecast down. {@link Projection} reports
 * {@code basedOnMonths} so the response can say how thin the evidence is, which is what stops "0.00
 * projected" from reading as a confident answer when it is an absence of data.
 *
 * <p><b>An empty window projects nothing rather than zero.</b> With no complete month to average
 * there is no projection at all, and {@link #project} answers empty; the response then omits the
 * projected figures instead of stating that the student will spend nothing. A brand-new student has
 * a current month and no forecast, and the contract says so.
 *
 * <p><b>This class is a pure function of the rows it is given</b>, so the same history always yields
 * the same projection and a test can pin the arithmetic exactly - the reason the division lives here
 * rather than in SQL, the treatment {@code AnomalyDetector} chose for UC-24.
 */
@Component
public class Forecaster {

    /**
     * UC-25: how many complete months the projection averages over.
     *
     * <p>Three, which is the window the SRS's own spike baseline uses ({@code
     * insight.spike_baseline_months}) and enough to smooth one unusual month without hiding a
     * genuine change. It is a constant rather than a setting because UC-25 documents no key for it;
     * inventing one would be specifying behaviour the requirements do not.
     */
    static final int WINDOW_MONTHS = 3;

    /** Money is compared and published at two decimal places, as every amount in the system is. */
    private static final int SCALE = 2;

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * UC-25: what the student's next month is projected to look like, and how much history it rests
     * on.
     *
     * <p>{@code projectedSavings} is the projected income less the projected expense - the net the
     * student would be left with - and it can be negative, which is a real and useful answer: it says
     * spending is on course to outrun income.
     *
     * <p>{@code basedOnMonths} is the count of complete months actually averaged, which equals the
     * list the service passed in. It is carried on the projection rather than recomputed by the
     * response so the two cannot disagree.
     */
    public record Projection(BigDecimal projectedIncome,
                             BigDecimal projectedExpense,
                             BigDecimal projectedSavings,
                             int basedOnMonths) {
    }

    /**
     * UC-25: the projection from a window of complete months, or empty when there is nothing to
     * average.
     *
     * <p>An empty window is the only case that produces empty. A window of months that all net to
     * zero still produces a projection of zero, because zero is what those months actually show -
     * the distinction between "no data" and "data that is zero" is the whole reason this returns an
     * {@code Optional} instead of a zero-filled projection.
     */
    public Optional<Projection> project(List<MonthTotals> completeMonths) {
        if (completeMonths.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal projectedIncome = average(completeMonths, MonthTotals::totalIncome);
        BigDecimal projectedExpense = average(completeMonths, MonthTotals::totalExpense);

        return Optional.of(new Projection(
                projectedIncome,
                projectedExpense,
                projectedIncome.subtract(projectedExpense),
                completeMonths.size()));
    }

    /**
     * The mean of one figure across the window, rounded to whole cents.
     *
     * <p>Rounded once, at the end, rather than per month: rounding each month's figure first and then
     * averaging would let three months of {@code 10.005} produce {@code 10.00} or {@code 10.01}
     * depending on the order, and the projection would stop being a function of the history alone.
     * {@code HALF_UP} is the rounding the rest of the system uses for money.
     */
    private static BigDecimal average(List<MonthTotals> months,
                                      java.util.function.Function<MonthTotals, BigDecimal> figure) {
        BigDecimal total = months.stream()
                .map(figure)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return total.divide(BigDecimal.valueOf(months.size()), SCALE, ROUNDING);
    }
}
