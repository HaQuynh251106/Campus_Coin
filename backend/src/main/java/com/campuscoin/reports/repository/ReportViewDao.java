package com.campuscoin.reports.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.reports.entity.CategoryBreakdownRow;
import com.campuscoin.reports.entity.MonthlyTrendPoint;
import com.campuscoin.reports.entity.ReportTotals;
import com.campuscoin.reports.entity.SpendingPoint;

/**
 * Reads the seven UC-15 views (UC-15).
 *
 * <p><b>Why the database answers all of this and Java answers none of it.</b> Every figure UC-15
 * publishes already has a definition in the schema, and each definition is shared with something
 * that must agree with it. {@code v_category_month_totals} is the same view the dashboard's
 * "highest-spending category" widget ranks, so the slice a student sees on the reports screen and
 * the category named on their home screen are one computation. {@code v_monthly_income_expense}
 * splits income from expense by the category's type (BR-05) and counts only live records (BR-09),
 * and {@code v_monthly_income_expense_6m} builds the six-month trend out of it with BR-17's
 * zero-fill. Adding up a transaction here, or deciding which months to show, would be a second
 * answer to a question the schema answers once - and the two would eventually disagree about a month
 * boundary, a deleted record or a currency, which is exactly the drift a report cannot afford.
 *
 * <p><b>Five queries rather than one statement joining the views.</b> The views are not keyed alike:
 * the totals are one row per student-month, the category breakdown is one row per category, the trend
 * is exactly six rows, and the daily and weekly series are one row per interval. A join would
 * multiply the totals row by the number of categories and require the sums to be
 * {@code DISTINCT}-ed or re-aggregated, undoing the guarantee that each figure comes from its view
 * unaltered. Five reads of indexed views is the cheaper and clearer arrangement, and it is why this
 * is a DAO with several methods rather than one query.
 *
 * <p><b>Three of the five views carry every student, so the caller's id is what narrows them.</b>
 * {@code v_monthly_income_expense}, {@code v_category_month_totals} and
 * {@code v_monthly_income_expense_6m} each publish one row per student (per month, per category, or
 * per month again); {@code v_daily_spending_current_month} and
 * {@code v_weekly_spending_current_month} are per student too. No method here takes a user id from a
 * caller - it is always the verified token's - which is what makes reading another student's report
 * impossible rather than merely refused (BR-02).
 *
 * <p><b>Projected by alias rather than mapped as entities.</b> {@code createNativeQuery(..., Tuple)}
 * lets each column be read by the name the query gave it, so a column added to or reordered in a
 * view cannot break this class, and the records only carry what this module publishes - the same
 * reason {@code BudgetConsumptionDao} and {@code DashboardViewDao} are written this way.
 *
 * <p>A DAO rather than a Spring Data repository because every query is native and every result is a
 * projection rather than a managed entity.
 */
@Repository
public class ReportViewDao {

    /**
     * UC-15: one month's totals for the caller (BR-17).
     *
     * <p>The view is keyed by {@code (user_id, period_month)}, so both predicates are exact and the
     * result is at most one row. {@code periodMonth} is bound as a first-of-month {@code DATE},
     * which is how the view's own {@code CAST(DATE_FORMAT(txn_date, '%Y-%m-01') ...)} stores it - a
     * client's {@code yyyy-MM} turned into that value is the service's job, not this query's.
     *
     * <p><b>An empty result is a real answer.</b> The view emits one row per month that holds data,
     * so a month the student recorded nothing in matches no row. That is deliberately different from
     * the six-month trend below, whose view zero-fills: "September, which was empty" and "the last
     * six months, one of which was empty" are different questions, and the schema answers them
     * differently.
     */
    private static final String SELECT_TOTALS = """
            SELECT v.period_month AS periodMonth,
                   v.total_income AS totalIncome,
                   v.total_expense AS totalExpense,
                   v.net_amount   AS netAmount,
                   v.txn_count    AS transactionCount
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
            """;

    /**
     * UC-15: the caller's totals per category for one month - the pie chart's source.
     *
     * <p>The join to {@code categories} adds the two presentation columns the view does not publish,
     * so a report can draw each slice with the same icon and swatch the categories screen uses. It is
     * on the row's own {@code category_id}, so one row in, one row out: it cannot widen the result or
     * re-scope which student is being reported on, and every figure still comes from the view. The
     * alternative - adding the two columns to the view - would change the database to suit a DTO
     * (section 4); this keeps the schema as it stands, which is the same choice
     * {@code BudgetConsumptionDao} and {@code DashboardViewDao} each made.
     *
     * <p><b>{@code type} is selected, not filtered.</b> The view groups income and expense categories
     * alike, and the report publishes them as two separate blocks. Filtering to {@code EXPENSE} here
     * would make an income category's total unreachable and would be this class deciding which half
     * of UC-15 to answer; the service splits them because the response has a place for each.
     *
     * <p>Ordered by amount descending then id, so the largest slice leads and the order is total: two
     * categories can legitimately share a total, and without the id tie-break MySQL could return them
     * either way round between two identical calls, making the chart appear to reshuffle.
     */
    private static final String SELECT_CATEGORY_TOTALS = """
            SELECT v.category_id    AS categoryId,
                   v.category_name  AS categoryName,
                   c.icon           AS categoryIcon,
                   c.color          AS categoryColor,
                   v.type           AS categoryType,
                   v.total_amount   AS totalAmount,
                   v.txn_count      AS transactionCount
              FROM v_category_month_totals v
              JOIN categories c ON c.id = v.category_id
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
             ORDER BY v.total_amount DESC, v.category_id ASC
            """;

    /**
     * UC-15, BR-17, UAT-09: the caller's last six months, empty months included as zero.
     *
     * <p><b>There is no month parameter, and it must not be given one.</b> The view derives its range
     * from {@code CURDATE()} inside the database session - the six months ending at the current one -
     * and cannot be asked about any other window. So this report does not accept one; see
     * {@code ReportService#getReport} for why the endpoint refuses a range that would pretend
     * otherwise rather than accepting a value it would have to ignore.
     *
     * <p>The view already restricts itself to {@code role = 'STUDENT'} and zero-fills through
     * {@code dim_month}, so this only narrows to the caller and orders the six rows. The order is
     * named here rather than relied upon: SQL makes no promise that a view's ordering survives into
     * the selecting statement's result, and a trend chart drawn from an unordered bag of six months
     * would plot them in whatever order the plan produced.
     */
    private static final String SELECT_TREND = """
            SELECT v.period_month  AS periodMonth,
                   v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense,
                   v.net_amount    AS netAmount
              FROM v_monthly_income_expense_6m v
             WHERE v.user_id = :userId
             ORDER BY v.period_month ASC
            """;

    /**
     * UC-15: the caller's spending per day, for the days of the current month that have any.
     *
     * <p>The view is already scoped to the current month by {@code CURDATE()} and counts only live
     * expense records, so the window predicates here do not re-scope it - they narrow the rows it
     * returns to the window the caller asked for. That is the only thing a {@code from}/{@code to}
     * can mean for this view, and it is why the service refuses a window outside the current month
     * instead of silently answering a subset of it.
     *
     * <p><b>Days with no spending are absent rather than zero, and this query does not invent
     * them.</b> The view returns one row per day that has a record, so a quiet Tuesday is a missing
     * row. Filling it in would mean generating a date series here and left-joining the view to it - a
     * second definition of "every day of the month" beside the one {@code dim_month} holds, and a
     * shape the response's own window already conveys. The response publishes the window it covered,
     * so a client knows where the axis starts and ends without a bar for every empty day. Contrast
     * the trend above, where BR-17 <em>does</em> require the empty months: the schema decides which
     * report zero-fills, and this module does not overrule it.
     */
    private static final String SELECT_DAILY = """
            SELECT v.txn_date      AS intervalStart,
                   v.txn_date      AS intervalEnd,
                   v.total_expense AS totalExpense,
                   v.txn_count     AS transactionCount
              FROM v_daily_spending_current_month v
             WHERE v.user_id = :userId
               AND v.txn_date >= :from
               AND v.txn_date <= :to
             ORDER BY v.txn_date ASC
            """;

    /**
     * UC-15: the caller's spending per ISO week, for the weeks that touch the window.
     *
     * <p><b>The predicate is an overlap, not a containment, and that is deliberate.</b> A bar's
     * {@code week_start} and {@code week_end} are the real Monday and Sunday of an ISO week, which
     * may fall outside the current month - the view's own comment explains that a month boundary
     * never splits a week. A week is therefore included when it overlaps the window at either end
     * ({@code week_start <= :to AND week_end >= :from}), not only when it sits wholly inside it: a
     * containment test would drop a bar whose Monday is in the previous month even though the bar
     * carries spending from the requested window.
     *
     * <p><b>What the view counts, and what it does not.</b> The view's own {@code WHERE} filters
     * transactions to the current month exactly as {@code v_daily_spending_current_month} does, and
     * groups the identical set of rows - only the grouping differs, one by ISO week rather than by
     * day. So a bar's <em>dates</em> may extend outside the month while its <em>total</em> cannot
     * include a day of spending from outside it, and the two series agree on the month's total by
     * construction. What an overlap predicate does mean is that a <em>narrowed</em> window can return
     * a weekly total larger than the daily one over that same window: a bar that merely touches the
     * window is returned whole, with all of its own week's spending. That is correct for a report
     * whose unit is the week, and it is stated on the response rather than left for a client to
     * discover.
     *
     * <p>{@code week_end} is the view's own {@code DATE_ADD(MIN(week_start), INTERVAL 6 DAY)} and is
     * read back rather than recomputed: it is the view that defined Sunday as the week's last day,
     * and a client should be told the boundary the database used rather than one derived here.
     */
    private static final String SELECT_WEEKLY = """
            SELECT v.week_start    AS intervalStart,
                   v.week_end      AS intervalEnd,
                   v.total_expense AS totalExpense,
                   v.txn_count     AS transactionCount
              FROM v_weekly_spending_current_month v
             WHERE v.user_id = :userId
               AND v.week_start <= :to
               AND v.week_end   >= :from
             ORDER BY v.week_start ASC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /** UC-15: the caller's totals for one month, or empty when that month holds no records. */
    @Transactional(readOnly = true)
    public Optional<ReportTotals> findTotals(Long userId, LocalDate periodMonth) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // the result is immediately mapped through toTotals, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOTALS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(ReportViewDao::toTotals).findFirst();
    }

    /** UC-15: the caller's per-category totals for one month, largest first. */
    @Transactional(readOnly = true)
    public List<CategoryBreakdownRow> findCategoryTotals(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CATEGORY_TOTALS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(ReportViewDao::toCategoryRow).toList();
    }

    /** UC-15: the caller's last six months, always six rows, oldest first (BR-17). */
    @Transactional(readOnly = true)
    public List<MonthlyTrendPoint> findSixMonthTrend(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TREND, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(ReportViewDao::toTrendPoint).toList();
    }

    /** UC-15: the caller's spending per day inside the window. */
    @Transactional(readOnly = true)
    public List<SpendingPoint> findDailySpending(Long userId, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_DAILY, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream().map(ReportViewDao::toSpendingPoint).toList();
    }

    /** UC-15: the caller's spending per ISO week for the weeks overlapping the window. */
    @Transactional(readOnly = true)
    public List<SpendingPoint> findWeeklySpending(Long userId, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_WEEKLY, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream().map(ReportViewDao::toSpendingPoint).toList();
    }

    // ------------------------------------------------------------------
    //  Row mapping
    // ------------------------------------------------------------------

    /**
     * Reads one totals row.
     *
     * <p>{@code periodMonth} is a {@code DATE}, which the driver reports as {@link java.sql.Date}, so
     * it is converted here rather than left as a driver type. The figures are read as their own types
     * - {@code SUM} over {@code DECIMAL(15,2)} arrives as {@link BigDecimal} and the count as a
     * {@link Long} - so no value is narrowed on the way out.
     */
    private static ReportTotals toTotals(Tuple row) {
        return new ReportTotals(
                toLocalDate(row.get("periodMonth", java.sql.Date.class)),
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class),
                row.get("netAmount", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    /**
     * Reads one category row.
     *
     * <p>{@code categoryType} is parsed rather than cast, so a value outside {@link CategoryType}
     * fails loudly here instead of reaching a client that would file an expense under income. The
     * column is a MySQL {@code ENUM} of exactly these two members, so the parse cannot fail while the
     * schema stands - and if it ever grew a third, this is where it would be noticed.
     */
    private static CategoryBreakdownRow toCategoryRow(Tuple row) {
        return new CategoryBreakdownRow(
                row.get("categoryId", Long.class),
                row.get("categoryName", String.class),
                row.get("categoryIcon", String.class),
                row.get("categoryColor", String.class),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("totalAmount", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    private static MonthlyTrendPoint toTrendPoint(Tuple row) {
        return new MonthlyTrendPoint(
                toLocalDate(row.get("periodMonth", java.sql.Date.class)),
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class),
                row.get("netAmount", BigDecimal.class));
    }

    private static SpendingPoint toSpendingPoint(Tuple row) {
        return new SpendingPoint(
                toLocalDate(row.get("intervalStart", java.sql.Date.class)),
                toLocalDate(row.get("intervalEnd", java.sql.Date.class)),
                row.get("totalExpense", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    private static LocalDate toLocalDate(java.sql.Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
