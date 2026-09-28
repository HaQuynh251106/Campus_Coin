package com.campuscoin.dashboard.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

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
