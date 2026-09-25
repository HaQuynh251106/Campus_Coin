package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One month's totals for one student: one row of {@code v_monthly_income_expense} (UC-15, BR-17).
 *
 * <p>{@code income}, {@code expense}, {@code net} and {@code transactionCount} are the view's own
 * figures, taken as they stand. The income/expense split is the <em>category's</em> type (BR-05),
 * not a column on the transaction, and the view counts only records that are not in the trash
 * (BR-09) - so this record never sees a deleted transaction and never has to filter one out.
 *
 * <p><b>The month is on the record because "no row" and "a row of zeroes" are different answers.</b>
 * {@code v_monthly_income_expense} returns one row per month that <em>has</em> data, so a month with
 * nothing in it produces no row at all. {@code ReportService} therefore supplies the requested month
 * and this record reports {@code null} for the four figures in that case - a genuinely empty month -
 * rather than fabricating a zero, which would state that the student had activity that netted to
 * nothing. The six-month trend is the one place where a zero <em>is</em> the correct answer, because
 * BR-17 requires all six rows; that is why the trend reads a different view.
 */
public record ReportTotals(
        LocalDate periodMonth,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        Long transactionCount) {

    /** A month with no recorded activity: the four figures are genuinely absent. */
    public static ReportTotals empty(LocalDate periodMonth) {
        return new ReportTotals(periodMonth, null, null, null, null);
    }
}
