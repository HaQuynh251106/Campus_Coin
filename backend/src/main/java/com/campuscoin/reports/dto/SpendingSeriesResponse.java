package com.campuscoin.reports.dto;

import java.util.List;

import com.campuscoin.reports.entity.ReportGranularity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The student's spending over one window, broken down by day or by ISO week (UC-15).
 *
 * <p><b>Its own endpoint, and not part of {@code ReportResponse}, because the two have different
 * months.</b> The totals and the category breakdowns can be asked about any month the student has
 * records for; the daily and weekly series cannot. {@code v_daily_spending_current_month} and
 * {@code v_weekly_spending_current_month} both derive their range from {@code CURDATE()} inside the
 * database session, so they answer about the current month and no other. Putting them in the same
 * response as a selectable month's totals would mean one payload whose parts describe two different
 * months, and no field would reveal it - every number would be individually correct. They are
 * therefore a separate read, and the request refuses a window outside the current month rather than
 * accepting one it would have to narrow silently.
 *
 * <p><b>{@code from} and {@code to} are published even though the view returns only the intervals that
 * have spending.</b> They are the window the read covered, so a chart knows where its axis starts and
 * ends without inferring it from the points: a month whose first days were quiet returns no point for
 * them, and the axis must still begin at the first. They are the range the caller asked for, clamped
 * to the current month - and for a weekly series they are <em>not</em> a pair of dates the bars can be
 * clipped to, because each bar is a real Monday-to-Sunday whose dates may reach past either end, even
 * though its total counts only records inside the month.
 *
 * <p>{@code points} is always sent, empty when nothing was spent in the window. An empty series is a
 * meaningful answer - "you spent nothing in this window" - whereas an absent field would be
 * indistinguishable from one the client forgot to read.
 */
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
