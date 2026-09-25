package com.campuscoin.reports.entity;

import java.time.LocalDate;

/**
 * The month and window one report response describes (UC-15).
 *
 * <p><b>One value, decided once, carried through the whole read.</b> Three of the four UC-15 read
 * paths are keyed by a month the caller can choose ({@code v_monthly_income_expense} and
 * {@code v_category_month_totals} by {@code period_month}), while the other two are not
 * ({@code v_monthly_income_expense_6m} is the last six months ending at the current one, and the
 * daily and weekly series are the current month). The report names its month once, in
 * {@link #periodMonth}, so a response can never mix one month's totals with another month's bars.
 *
 * <p>{@link #from} and {@link #to} are the window inside that month, and
 * {@link #granularity} says how it was broken down. They are what the response publishes so a client
 * can draw the x-axis without inferring it from the data: a month with no spending on its early days
 * returns no points for them, and the axis must still start at the first of the month.
 *
 * <p><b>What is deliberately absent.</b> There is no user id: the caller is the bearer token and a
 * user id here would be a field a caller could fill in. There is no currency either - the totals are
 * the account's currency, which is a property of the reader rather than of the window, and it is read
 * from the account once rather than repeated on every scope.
 */
public record ReportScope(
        LocalDate periodMonth,
        LocalDate from,
        LocalDate to,
        ReportGranularity granularity) {

    /** The window the daily and weekly views actually cover: the whole of {@link #periodMonth}. */
    public LocalDate monthEnd() {
        return periodMonth.withDayOfMonth(periodMonth.lengthOfMonth());
    }
}
