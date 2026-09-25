package com.campuscoin.reports.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.reports.dto.ReportCategoryResponse;
import com.campuscoin.reports.dto.ReportResponse;
import com.campuscoin.reports.dto.ReportTotalsResponse;
import com.campuscoin.reports.dto.ReportTrendPointResponse;
import com.campuscoin.reports.dto.SpendingPointResponse;
import com.campuscoin.reports.dto.SpendingSeriesResponse;
import com.campuscoin.reports.entity.CategoryBreakdownRow;
import com.campuscoin.reports.entity.MonthlyTrendPoint;
import com.campuscoin.reports.entity.ReportScope;
import com.campuscoin.reports.entity.ReportTotals;
import com.campuscoin.reports.entity.SpendingPoint;

/**
 * Turns the UC-15 view projections into the responses (UC-15).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. Every view here
 * carries {@code user_id}, and centralising the projection means a column can be added to a view
 * without silently appearing in a response.
 *
 * <p><b>Almost nothing is recalculated.</b> Every total, every net and the order of every list arrive
 * from the database already decided, and are passed through unaltered. Rounding a number again or
 * re-sorting a list is how the reports screen would start to disagree with the dashboard reading the
 * same view - the reasoning {@code BudgetMapper} and {@code DashboardMapper} give.
 *
 * <p><b>The one figure this class computes is the category share</b>, because nothing in the schema
 * computes a share of a month's spending and a pie chart needs one. It is computed from the totals in
 * the block it belongs to, so each share is that slice's proportion of its own block, and it is
 * rounded to two decimals in the schema's own convention rather than Java's default. Because each is
 * rounded on its own, the shares of a block do not in general total exactly 100 - see
 * {@link #toCategoryResponses} for why that is the honest figure to publish.
 *
 * <p>The other conversion owned here is the month: the database stores it as the {@code DATE} of the
 * first day and a client names it as {@code yyyy-MM}. It is done once, in {@link #toMonthString}, so
 * no caller has to remember the convention, and it is the same conversion {@code BudgetMapper} and
 * {@code DashboardMapper} make - a report month, a budget month and a dashboard month must be the
 * same string for the same month.
 */
@Component
public class ReportMapper {

    /**
     * UC-15: the month's report.
     *
     * <p>{@code totals} is never null - a month with no records still has a totals block, whose own
     * figures are absent - so the response always states whether the month held anything rather than
     * omitting the block and leaving the client to guess.
     */
    public ReportResponse toResponse(ReportScope scope,
                                     String currency,
                                     ReportTotals totals,
                                     List<CategoryBreakdownRow> expenseByCategory,
                                     List<CategoryBreakdownRow> incomeByCategory,
                                     List<MonthlyTrendPoint> trend) {
        return new ReportResponse(
                toMonthString(scope.periodMonth()),
                currency,
                toTotalsResponse(totals),
                toCategoryResponses(expenseByCategory),
                toCategoryResponses(incomeByCategory),
                trend.stream().map(this::toTrendPointResponse).toList());
    }

    /**
     * UC-15: one month's totals, with absent figures where the month held nothing.
     *
     * <p>{@code ReportTotals.empty} carries nulls deliberately and they are passed straight through:
     * the DTO omits them, so the response states "no records" rather than "0.00", which are different
     * claims. Nothing is substituted here.
     */
    public ReportTotalsResponse toTotalsResponse(ReportTotals totals) {
        return new ReportTotalsResponse(
                totals.income(),
                totals.expense(),
                totals.net(),
                totals.transactionCount());
    }

    /**
     * UC-15: one block of category slices, with each slice's own share of the block.
     *
     * <p><b>Why the denominator is the sum of the rows rather than an independent total.</b> The share
     * is a proportion of the block it appears in, so it must be computed against exactly the rows
     * being published; taking it from, say, the month's total expense would make the shares of the
     * two blocks sum differently and would be a second way to compute the same month's spending.
     * Because the rows come from one view read, their sum <em>is</em> the block total, and no row can
     * be counted twice.
     *
     * <p><b>The shares of a block are each that slice's own proportion, and therefore do not in
     * general add up to exactly 100.</b> Each is rounded independently to two decimals, because a
     * percentage of a slice is a property of that slice; three equal thirds each publish as
     * {@code 33.33}, and {@code 33.33 x 3} is {@code 99.99}. Adjusting one slice to absorb the
     * remainder - the usual "largest slice takes the difference" trick - would publish a number that
     * is not that category's share, and a report is exactly where such a figure cannot be afforded.
     * The shortfall is bounded instead: each rounded share is within half a unit of the last decimal
     * place of the true share, so no single figure is wrong and the block as a whole is within that
     * bound per slice. A client that needs a whole pie should draw from {@code total}, which is exact.
     *
     * <p><b>{@code RoundingMode.HALF_UP} rather than {@code BigDecimal}'s default.</b> Java's default
     * for a division with no exact representation is {@code ArithmeticException}, and a chart must not
     * fail because a total does not divide evenly. {@code HALF_UP} is also the mode MySQL's
     * {@code ROUND} uses, so a share computed here and a percentage computed by a view round the same
     * way - the convention the schema's {@code consumed_pct} and {@code savings_goal_pct} follow.
     *
     * <p>A block with no rows returns an empty list without dividing. The guard is not defensive
     * decoration: {@code SUM} over an empty list is zero, and dividing by it would be the one input
     * that could throw.
     *
     * @param rows one block's rows, largest total first, as the DAO ordered them
     */
    public List<ReportCategoryResponse> toCategoryResponses(List<CategoryBreakdownRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        BigDecimal blockTotal = rows.stream()
                .map(CategoryBreakdownRow::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return rows.stream()
                .map(row -> toCategoryResponse(row, blockTotal))
                .toList();
    }

    private ReportCategoryResponse toCategoryResponse(CategoryBreakdownRow row, BigDecimal blockTotal) {
        return new ReportCategoryResponse(
                row.categoryId(),
                row.categoryName(),
                row.categoryIcon(),
                row.categoryColor(),
                row.type(),
                row.totalAmount(),
                toPercentage(row.totalAmount(), blockTotal),
                row.transactionCount());
    }

    /**
     * One category's share of its block, to two decimals.
     *
     * <p>A zero block total cannot arise from a real row - a category appears in the view only because
     * it has records, and each record's amount is positive - but the check keeps the arithmetic total
     * rather than relying on that, because a {@code BigDecimal} division by zero is an exception and
     * not a wrong number.
     */
    private static BigDecimal toPercentage(BigDecimal amount, BigDecimal blockTotal) {
        if (blockTotal.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(blockTotal, 2, RoundingMode.HALF_UP);
    }

    public ReportTrendPointResponse toTrendPointResponse(MonthlyTrendPoint point) {
        return new ReportTrendPointResponse(
                toMonthString(point.periodMonth()),
                point.income(),
                point.expense(),
                point.net());
    }

    /**
     * UC-15: a spending series and the window it covered.
     *
     * <p>{@code totalExpense} is the sum of the points, computed here rather than read from a view.
     * That is a deliberate exception to the rule that figures come from the database, and it is safe
     * because it is not a second definition of anything: the view has already decided what each bar
     * contains, and adding the bars together is arithmetic on one view's own output, not a re-reading
     * of the transactions behind it. There is no view that publishes a window total, and asking the
     * database for it would be a second query over the same rows.
     *
     * <p>Over a whole month the two granularities therefore agree on the total, because both views
     * group the same month-filtered records - only the grouping differs. Over a narrowed window they
     * can differ: a weekly bar that merely overlaps the window is returned whole, so a weekly series
     * can be larger than the daily one for the same {@code from}/{@code to}. That is stated on the
     * response rather than hidden.
     */
    public SpendingSeriesResponse toSeriesResponse(ReportScope scope, String currency,
                                                  List<SpendingPoint> points) {
        BigDecimal total = points.stream()
                .map(SpendingPoint::totalExpense)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SpendingSeriesResponse(
                scope.granularity(),
                scope.from().toString(),
                scope.to().toString(),
                currency,
                total,
                points.stream().map(this::toSpendingPointResponse).toList());
    }

    public SpendingPointResponse toSpendingPointResponse(SpendingPoint point) {
        return new SpendingPointResponse(
                point.intervalStart().toString(),
                point.intervalEnd().toString(),
                point.totalExpense(),
                point.transactionCount());
    }

    /**
     * A first-of-month {@code DATE} as the {@code yyyy-MM} a client uses.
     *
     * <p>{@link YearMonth#from} rather than slicing the string: every {@code period_month} the views
     * produce is the first of its month, so the year and the month are the whole of what it carries,
     * and taking them through the date type cannot produce a value the calendar disagrees with.
     *
     * @param periodMonth a date the database guarantees is the first of its month, or null
     * @return the month as {@code yyyy-MM}, or null when the value was null
     */
    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
