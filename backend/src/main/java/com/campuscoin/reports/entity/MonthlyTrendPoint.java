package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One month of the six-month trend: one row of {@code v_monthly_income_expense_6m} (UC-15, BR-17).
 *
 * <p><b>Absent figures are impossible here, and that is the point of the view.</b> BR-17 and UAT-09
 * require the report to return all six months with the empty ones as {@code 0} rather than omitting
 * them. {@code v_monthly_income_expense} itself returns only months that hold data, so a trend built
 * from it would draw five bars or four and leave a client unable to tell a quiet month from a missing
 * one. The 6-month view joins {@code dim_month} to the students table and left-joins the
 * per-month totals, so every student always has exactly six rows and the empty months are zero.
 *
 * <p>That is also why the three money fields are plain {@link BigDecimal} rather than nullable, while
 * {@link ReportTotals}' are not: {@code IFNULL(..., 0)} has already decided that a month with no
 * activity <em>is</em> zero for this report. The two records differ because the two views answer
 * different questions - "what happened in September" and "what did the last six months look like" -
 * and flattening them into one shape would force one of the answers to be wrong.
 *
 * <p>{@code periodMonth} is the view's {@code period_month}, a first-of-month {@code DATE}, and it is
 * what makes the series ordered and complete rather than a bag of six figures.
 */
public record MonthlyTrendPoint(
        LocalDate periodMonth,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net) {
}
