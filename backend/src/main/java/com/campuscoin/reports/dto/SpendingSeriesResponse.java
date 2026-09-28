package com.campuscoin.reports.dto;

import java.util.List;

import com.campuscoin.reports.entity.ReportGranularity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The student's spending over a window, by day or by ISO week (UC-15).")
public record SpendingSeriesResponse(

        @Schema(description = "`DAILY` for one point per calendar day, `WEEKLY` for one point per "
                + "ISO week.", example = "DAILY")
        ReportGranularity granularity,

        @Schema(description = "The first day of the window covered, as `yyyy-MM-dd`.", example = "2026-09-01")
        String from,

        @Schema(description = "The last day of the window covered, as `yyyy-MM-dd`. A weekly bar's "
                + "dates may extend past this, because an ISO week is not split by a month boundary.",
                example = "2026-09-30")
        String to,

        @Schema(description = "The currency every amount is in, as the account stores it.",
                example = "USD")
        String currency,

        @Schema(description = "The window's total spending, as the sum of the points. Over a whole "
                + "month the two granularities agree, because both count only records inside it; a "
                + "weekly total can exceed the daily one over a narrowed window, because a bar that "
                + "merely overlaps the window is returned whole.", example = "189.00")
        java.math.BigDecimal totalExpense,

        @Schema(description = "The breakdown, oldest interval first. Intervals with no spending are "
                + "absent rather than zero - the window above says where the axis ends.")
        List<SpendingPointResponse> points) {
}
