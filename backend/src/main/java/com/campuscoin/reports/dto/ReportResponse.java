package com.campuscoin.reports.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month's report for the signed-in student (UC-15).
 *
 * <p><b>One request rather than four.</b> The reports screen renders four blocks - the month's totals,
 * the two category breakdowns and the six-month trend - and they are read together because a student
 * reads them together: the totals set up the chart, the chart sets up the pie. Four calls would make
 * the screen's first paint wait on four round-trips and would let a client paint one month's totals
 * beside another month's slices if the clock crossed a boundary between two of them. This is a
 * composition, not a new capability: every block is one of UC-15's views read as it stands, and
 * nothing here is computed that the schema does not already compute except the one share figure
 * {@link ReportCategoryResponse} documents.
 *
 * <p><b>{@code periodMonth} names the month the totals and the breakdowns describe, and it is the one
 * the caller asked for.</b> Unlike the dashboard, this month is selectable: UC-15 is a report, and
 * {@code v_monthly_income_expense} and {@code v_category_month_totals} are keyed by
 * {@code period_month}, so any month the student has records for can be asked about. The daily and
 * weekly series are the exception - their views derive the month from the database's own clock and
 * cannot be pointed at another - which is why they are not in this response at all and have their own
 * endpoint, {@code GET /api/v1/reports/spending}.
 *
 * <p><b>{@code sixMonthTrend} is always the current six months, whatever {@code periodMonth} says,
 * and its points carry their own month so nothing is mislabelled.</b> BR-17 defines that report as the
 * last six months ending at the current one - it is a fixed window, not a parameter - so asking for
 * July's report returns July's totals and July's slices, with the six months to September as context.
 * A client draws the trend from the dates on its points, not from {@code periodMonth}.
 *
 * <p><b>{@code totals} is always present but its figures may be absent.</b> A month the student
 * recorded nothing in produces no row in {@code v_monthly_income_expense}, so the four figures are
 * omitted rather than reported as zero: "you recorded nothing in July" is a different statement from
 * "you recorded activity that netted to nothing". The two breakdown arrays are always sent, empty
 * when there is nothing to show, because an empty list is a meaningful answer a client can render and
 * an absent one is indistinguishable from a field it forgot to read.
 */
@Schema(description = "One month's financial report: totals, spending and income by category, and "
        + "the six-month trend (UC-15).")
public record ReportResponse(

        @Schema(description = "The month the totals and the category breakdowns describe, as "
                + "`yyyy-MM`. The month the request asked for, or the current one when none was "
                + "given.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The currency every amount in this response is in, as the account "
                + "stores it.", example = "USD")
        String currency,

        @Schema(description = "The month's totals (UC-15). Present even for a month with no "
                + "activity, in which case its four figures are absent.")
        ReportTotalsResponse totals,

        @Schema(description = "Spending per expense category for the month, largest first. Empty "
                + "when the student spent nothing in it.")
        List<ReportCategoryResponse> expenseByCategory,

        @Schema(description = "Income per income category for the month, largest first. Empty when "
                + "the student earned nothing in it.")
        List<ReportCategoryResponse> incomeByCategory,

        @Schema(description = "The last six months ending at the current one, oldest first, with "
                + "months that hold no data present as `0.00` (BR-17, UAT-09). Always exactly six "
                + "points, regardless of `periodMonth`.")
        List<ReportTrendPointResponse> sixMonthTrend) {
}
