package com.campuscoin.reports.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

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
