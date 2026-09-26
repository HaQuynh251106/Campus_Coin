package com.campuscoin.forecast.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.forecast.entity.MonthTotals;

/**
 * Reads the monthly totals a forecast is computed from (UC-25).
 *
 * <p><b>The figures come from {@code v_monthly_income_expense}, which this module does not own.</b>
 * The reports module reads the same view for a single month and the dashboard's six-month trend is
 * built from it too, so adding up a student's transactions here would be a second answer to a
 * question the schema already answers once - and the two would eventually disagree about a month
 * boundary, a trashed record or an income/expense split. A forecast that averaged its own sum would
 * be predicting a number the reports screen does not show; reading the view means it predicts the
 * same figure the student can check (BR-17's view, UC-15/UC-17).
 *
 * <p><b>No trend is computed here.</b> The view publishes one row per month that has data and says
 * nothing about slopes or averages; a forecast is a judgement over a window, and the window and the
 * divisor belong in one place a test can pin - {@code Forecaster} - rather than split between a query
 * and a service. This class answers exactly "what are the student's complete months", and the
 * arithmetic lives in Java.
 *
 * <p><b>The caller's id is never taken from the request.</b> The only parameter is
 * {@code :userId}, and every call site passes the verified token's id; the view itself is keyed by
 * {@code user_id}, so the narrowing is the query's rather than a filter applied to its output (BR-02).
 *
 * <p>Projected by alias rather than mapped as an entity, so a column added to or reordered in the
 * view cannot break this class - the treatment {@code ReportViewDao} and {@code DashboardViewDao}
 * each chose for the same view.
 */
@Repository
public class ForecastViewDao {

    /**
     * UC-25: the caller's complete monthly totals up to and including this month, oldest first.
     *
     * <p><b>Why the current month is excluded, and read separately.</b> The month in progress has only
     * the records entered so far, so its expense total is a partial figure that grows until the month
     * ends. Averaging it into the history would make the prediction depend on the day of the month it
     * was asked for, and would make the same request return a different forecast tomorrow without any
     * new spending habit behind the change. So the trailing window is drawn from <em>completed</em>
     * months only - {@code period_month < :currentMonth} - and the current month is a separate read
     * whose figures the forecast is reported against. That split is what lets
     * {@code ForecastResponse} state the current month as a fact and the next month as an estimate,
     * instead of blending the two.
     *
     * <p>{@code periodMonth} is bound as a first-of-month {@code DATE}, which is how the view's own
     * {@code CAST(DATE_FORMAT(txn_date, '%Y-%m-01') ...)} stores it; turning the application's clock
     * into that value is the service's job, not this query's.
     *
     * <p>Ordered oldest first so the caller can walk the window without re-sorting, and so a limit on
     * the window keeps the <em>most recent</em> months - the ones nearest the month being predicted.
     * Rows are limited in the query rather than after fetching, so a student with years of history does
     * not pay for all of it to compute a three-month average.
     */
    private static final String SELECT_COMPLETED_MONTHS = """
            SELECT v.period_month  AS periodMonth,
                   v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month < :currentMonth
             ORDER BY v.period_month DESC
             LIMIT :windowMonths
            """;

    /**
     * UC-25: the caller's totals for the month in progress, which may be absent.
     *
     * <p>The view is keyed by {@code (user_id, period_month)}, so both predicates are exact and the
     * result is at most one row. An empty result is a real answer - a student who has recorded nothing
     * this month - and the service reads it as "no figures yet" rather than as a month of zeroes, the
     * distinction {@code ReportViewDao} draws for the same view.
     */
    private static final String SELECT_CURRENT_MONTH = """
            SELECT v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month = :currentMonth
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-25: up to {@code windowMonths} of the caller's most recent completed months, oldest first.
     *
     * <p>Fewer rows than {@code windowMonths} is expected and correct for a student with a short
     * history; the caller reports how many it received rather than treating a short window as an
     * error.
     */
    @Transactional(readOnly = true)
    public List<MonthTotals> findCompletedMonths(Long userId, LocalDate currentMonth, int windowMonths) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one declaration -
        // every row is mapped through toMonthTotals, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_COMPLETED_MONTHS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("currentMonth", Date.valueOf(currentMonth))
                .setParameter("windowMonths", windowMonths)
                .getResultList();

        // The query orders newest first so the limit keeps the most recent months; the arithmetic
        // wants them oldest first, and reversing here is cheaper than a second sort over the view.
        List<MonthTotals> months = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            months.add(toMonthTotals(row, row.get("periodMonth", Date.class).toLocalDate()));
        }
        Collections.reverse(months);

        return months;
    }

    /**
     * UC-25: the caller's totals for the month in progress, or empty when nothing is recorded yet.
     *
     * <p>{@code Optional} rather than a zero-filled row, because "nothing recorded this month" and
     * "recorded activity that netted to zero" are different answers and the response distinguishes
     * them.
     */
    @Transactional(readOnly = true)
    public Optional<MonthTotals> findCurrentMonth(Long userId, LocalDate currentMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CURRENT_MONTH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("currentMonth", Date.valueOf(currentMonth))
                .getResultList();

        return rows.stream().map(row -> toMonthTotals(row, currentMonth)).findFirst();
    }

    private static MonthTotals toMonthTotals(Tuple row, LocalDate periodMonth) {
        return new MonthTotals(
                periodMonth,
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class));
    }
}
