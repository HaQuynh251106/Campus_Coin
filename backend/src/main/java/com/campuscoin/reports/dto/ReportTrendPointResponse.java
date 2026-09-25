package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month of the six-month trend (UC-15, BR-17, UAT-09).
 *
 * <p><b>Always present, always six, and never absent figures.</b> BR-17 requires the six-month report
 * to return all six months with the empty ones as zero, and {@code v_monthly_income_expense_6m}
 * satisfies that by joining {@code dim_month} to the students table and left-joining the per-month
 * totals. So unlike {@code ReportTotalsResponse}, whose absent figures mean "this month held no
 * records", a point here always carries a number - {@code 0.00} for a month with nothing in it. The
 * two shapes differ because the two views answer different questions, and the view has already made
 * the decision this DTO only documents.
 *
 * <p><b>{@code periodMonth} is on the point rather than implied by the response.</b> The trend is a
 * fixed window - the last six months ending at the current one - and it is returned beside a report
 * whose totals may be for an earlier month the caller selected. A client therefore draws the x-axis
 * from the dates on these points and not from {@code ReportResponse.periodMonth}, which names the
 * selected month and not the trend's range. The list is ordered oldest first, so the chart reads
 * left to right.
 */
@Schema(description = "One month of the six-month trend (UC-15, BR-17).")
public record ReportTrendPointResponse(

        @Schema(description = "The month this point describes, as `yyyy-MM`.", example = "2026-09")
        String periodMonth,

        @Schema(description = "Total income that month. `0.00` when the month holds no records.",
                example = "260.00")
        BigDecimal income,

        @Schema(description = "Total spending that month. `0.00` when the month holds no records.",
                example = "189.00")
        BigDecimal expense,

        @Schema(description = "Income minus spending. `0.00` when the month holds no records.",
                example = "71.00")
        BigDecimal net) {
}
