package com.campuscoin.tips.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month's tips, with the month named once (UC-18).
 *
 * <p><b>Why the month is on the wrapper and not on each tip.</b> {@code v_dashboard_tips} carries
 * every month a student has tips for, so the month is what each query narrows on; stating it once on
 * the response means a client cannot read a tip's month from anywhere but the one field that says
 * which month was asked for, and a copy per row would be the same string repeated. It is the shape
 * {@code DashboardResponse} uses for the same reason.
 *
 * <p><b>{@code tips} is present and empty when the month has nothing to show</b> - a month with no
 * generated tips, or whose tips were all dismissed. An empty array is the honest answer: the caller
 * asked about a real month, and that month has no tips. The endpoint does not answer {@code 404},
 * because there is no missing resource - the student simply has no advice to show for that month.
 *
 * <p><b>The month is echoed back rather than assumed.</b> When {@code month} is omitted the server
 * chooses the current month, and the response says which one it chose; a client that asked for
 * September can confirm it got September, and one that let the server choose learns what "now" was
 * without a second call.
 */
@Schema(description = "One month's saving tips, already ranked (UC-18).")
public record TipListResponse(

        @Schema(description = "The month these tips belong to, as `yyyy-MM`.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The tips to show, pinned first and then by potential saving (BR-14). "
                + "Empty when the month has no visible tips.")
        List<TipResponse> tips) {
}
