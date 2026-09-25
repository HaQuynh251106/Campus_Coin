package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One point of a spending series: a row of {@code v_daily_spending_current_month} or
 * {@code v_weekly_spending_current_month} (UC-15).
 *
 * <p>One record for both granularities, because the two views publish the same facts about different
 * bars: an interval, the expense in it and how many records made it up. {@code intervalStart} is the
 * day or the ISO week's Monday, and {@code intervalEnd} is the same day for {@code DAILY} and the
 * Sunday for {@code WEEKLY}. Keeping one shape means the client draws one chart and switches the
 * x-axis, rather than handling two payloads that differ only in how wide a bar is.
 *
 * <p><b>The two gaps the views leave, and what this module does about them.</b>
 *
 * <ul>
 *   <li><b>Only intervals with spending are returned.</b> Neither view zero-fills, so a day on which
 *       nothing was spent is a missing row rather than a row of zero. The points are passed through
 *       as they arrive: the response carries the window it covered ({@code from}/{@code to} on
 *       {@code ReportScope}) so a chart knows where to start and end without inventing a bar for
 *       every empty day. This is deliberately the opposite of the six-month trend, where BR-17
 *       <em>does</em> require the empty months - the schema decides which report zero-fills, and this
 *       module does not overrule it.</li>
 *   <li><b>A week's dates can reach outside the month, but its total cannot.</b>
 *       {@code v_weekly_spending_current_month} groups by ISO week ({@code YEARWEEK(date, 3)}) and
 *       reports each week's real Monday-to-Sunday, so a bar's {@code intervalStart} may fall in the
 *       previous month - intentional in the view, whose own comment says a month boundary never splits
 *       a week. What the view groups, however, is the same month-filtered set of records the daily
 *       view groups: both filter {@code txn_date} to the current month before aggregating, so a bar's
 *       dates can be wider than the month while its total cannot include spending from outside it, and
 *       the two granularities agree on the month's total. It is why the window published with the
 *       response is not a pair of dates a client should clip the bars to.</li>
 * </ul>
 *
 * <p>{@code totalExpense} is positive, and it is the same positive figure every other report uses:
 * {@code ck_txn_amount} requires {@code amount > 0}, so a record's direction is its category's type
 * (BR-05) and never a sign on the number. The view's {@code SUM(t.amount)} is therefore an amount
 * spent, not a movement, and the value is passed through untransformed so that this report and a
 * transaction list agree about the same record.
 */
public record SpendingPoint(
        LocalDate intervalStart,
        LocalDate intervalEnd,
        BigDecimal totalExpense,
        Long transactionCount) {
}
