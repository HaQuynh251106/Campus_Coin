package com.campuscoin.forecast.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One complete month's income and expense for one student: one row of
 * {@code v_monthly_income_expense} (UC-25).
 *
 * <p>The two figures are the view's own, taken as they stand. The income/expense split is the
 * <em>category's</em> type (BR-05) and the view counts only records not in the trash (BR-09), so this
 * record never sees a deleted transaction and never has to filter one out - the same guarantee
 * {@code ReportTotals} relies on for the reports module.
 *
 * <p><b>A month with no records produces no row at all, and that is treated as "no data" rather than
 * as a month of zero spending."</b> The view emits a row only for a month that holds a live record, so
 * an empty month is absent from the list a forecast averages over. Counting it as zero would drag the
 * average down and state that the student deliberately spent nothing, which is not something the data
 * says; leaving it out and reporting how many months were actually used - see
 * {@code ForecastResponse#basedOnMonths} - is the honest alternative. It is the same distinction
 * {@code ReportTotals.empty} draws for a single month: absent and zero are different answers.
 */
public record MonthTotals(LocalDate periodMonth, BigDecimal totalIncome, BigDecimal totalExpense) {
}
