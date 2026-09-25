package com.campuscoin.dashboard.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Everything the student's dashboard shows in one response (UC-12).
 *
 * <p><b>One request rather than four.</b> The four blocks are four views and could be four endpoints,
 * but a dashboard renders them together and every one of them describes the same month. Four calls
 * would let a client paint a month's totals beside the previous month's top category if the clock
 * crossed a boundary between two of them, and would make the screen's first paint wait on four
 * round-trips. This is a composition, not a new capability: each block is one of the four UC-12 views
 * read as it stands, and nothing here is computed that the schema does not already compute.
 *
 * <p><b>{@code periodMonth} is stated once, at the top, and applies to everything below it.</b> It is
 * {@code CURDATE()} as the database session sees it, read back from {@code v_dashboard_summary}
 * rather than taken from the application clock, so the totals, the top category and the tips all
 * describe the month the database itself thinks it is. The tips are the reason this matters: their
 * view has no time filter of its own, so the month this response publishes is the month they were
 * selected by. See {@code MODULE_07_DASHBOARD.md} §5.
 *
 * <p><b>{@code topCategory} is nullable; the other three blocks are always present.</b> A student who
 * has spent nothing this month has no highest-spending category, so the block is omitted rather than
 * sent empty - see {@link DashboardTopCategoryResponse}. {@code tips} and {@code announcements} are
 * arrays and are always sent, empty when there is nothing to show: an empty list is a meaningful
 * answer ("there is nothing here"), whereas an absent one would leave a client unable to tell that
 * from a field it forgot to read.
 *
 * <p><b>What is deliberately not here.</b> Budget progress bars are UC-13 and are published by
 * {@code GET /api/v1/budgets}, which reads {@code v_budget_consumption} - a fifth block would be a
 * second route to a capability that already has one, and the figures would be the same rows read
 * twice in one request. Notifications are UC-14 and are published by
 * {@code GET /api/v1/notifications}, for the same reason. The dashboard shows what is on the
 * dashboard; the things a student opens on their own are their own endpoints.
 */
@Schema(description = "The signed-in student's dashboard for the current month: totals, the "
        + "highest-spending category, saving tips and live announcements (UC-12).")
public record DashboardResponse(

        @Schema(description = "The month every figure and list in this response describes, as "
                + "`yyyy-MM`. Always the current month, as the database session determines it.",
                example = "2026-09")
        String periodMonth,

        @Schema(description = "The month's totals and saving-goal progress (UC-12 B1).")
        DashboardSummaryResponse summary,

        @Schema(description = "The highest-spending expense category this month (UC-12 B2). Omitted "
                + "when the student has recorded no spending this month.", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        DashboardTopCategoryResponse topCategory,

        @Schema(description = "Saving tips, pinned first and then by potential saving (UC-12 B3, "
                + "BR-14). Empty when the student has none.")
        List<DashboardTipResponse> tips,

        @Schema(description = "Live announcements addressed to students, newest first (UC-12 B3). "
                + "Empty when none are running.")
        List<DashboardAnnouncementResponse> announcements) {
}
