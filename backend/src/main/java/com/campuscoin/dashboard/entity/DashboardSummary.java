package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code v_dashboard_summary}: the current month's totals for one student (UC-12 B1).
 *
 * <p><b>Why the month is carried here rather than taken from the application clock.</b> The view
 * derives {@code period_month} from {@code CURDATE()} inside the database session, which is pinned to
 * {@code +07:00} (VĐ-10). Every other figure in the payload is scoped to that same month - the top
 * category by the view itself, and the tips by this record's value. Reading the month back out of the
 * row is what makes them all describe one month: if Java computed "the current month" from its own
 * clock, the totals and the tips could fall either side of a month boundary while the response
 * presented them as one picture. It is not a timezone guarantee, it is a single source for one value.
 *
 * <p>{@code savingsGoalPct} is nullable because the view computes it only when the goal is above
 * zero: a student who has set no saving goal has no percentage to report, and a substituted zero
 * would read as "made no progress" rather than "no goal set". It may be negative - a month that spent
 * more than it earned is a real answer to the same question.
 */
public record DashboardSummary(
        LocalDate periodMonth,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal netAmount,
        BigDecimal monthlyAllowanceBaseline,
        BigDecimal monthlySavingsGoal,
        BigDecimal savingsGoalPct) {
}
