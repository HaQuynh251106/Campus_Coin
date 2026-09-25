package com.campuscoin.dashboard.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.dashboard.dto.DashboardResponse;
import com.campuscoin.dashboard.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The student's dashboard: UC-12.
 *
 * <p>One endpoint. The screen has four blocks and they are four of the UC-12 views, but they describe
 * one student's one month, so they are read together rather than at four URLs - see
 * {@link DashboardResponse} for why, and for what the composition does and does not add.
 *
 * <p><b>There is no parameter, and the absence is deliberate.</b> A {@code ?month=} would look
 * harmless and would be a lie: {@code v_dashboard_summary} and {@code v_top_category_current_month}
 * both derive their month from {@code CURDATE()} inside the database session and cannot be asked
 * about any other, so a caller sending {@code 2026-01} would silently receive September's figures
 * under the January heading it asked for. Rather than accept a parameter that only one value honours,
 * the endpoint takes none and says which month it answered for in the response. A month-selectable
 * view is a report, UC-15's, and it reads different views in module 8.
 *
 * <p><b>There is no write on the screen, and no write endpoint here to add one.</b> Marking a
 * notification read is UC-14's endpoint, pinning or dismissing a tip is UC-18's, and neither is
 * something a dashboard render should cause. Every row this endpoint returns is read-only, and the
 * only state a student can change from the dashboard is changed through the endpoint that owns it.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, which is what makes reading another student's
 * dashboard impossible rather than merely refused.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard",
        description = "The student's home screen: current-month totals, highest-spending category, "
                + "saving tips and live announcements (UC-12).")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(
            summary = "Get my dashboard",
            description = """
                    Returns the four blocks the home screen renders, all describing the **current \
                    month**: the month's totals and saving-goal progress (UC-12 B1), the \
                    highest-spending expense category (UC-12 B2), the saving tips to show and the \
                    live announcements (UC-12 B3).

                    **The month is always the current one and is not selectable.** `periodMonth` in \
                    the response states which month was reported, as `yyyy-MM`. The totals and the \
                    top category are computed by database views that derive their month from the \
                    database's own clock, so there is no other month they could answer about; a \
                    month-selectable report is UC-15's and lives in a later module.

                    **`summary.savingsGoalPct` is absent when no saving goal is set**, rather than \
                    zero: a goal of zero has no progress to report, and reporting `0` would read as \
                    "made no progress". It may be negative when the month is net-negative.

                    **`topCategory` is absent when the student has recorded no spending this month.** \
                    There is no highest-spending category to name, which is a different fact from \
                    having one on which nothing was spent.

                    **`tips` is ordered and filtered by the database**, pinned first and then by how \
                    much each could save (BR-14); dismissed tips never appear. A client renders the \
                    array in order. Its contents are not affected by this endpoint - pinning and \
                    dismissing are UC-18's.

                    `announcements` contains only notices that are active, inside their start/end \
                    window, and addressed to students or to everyone. A notice written for \
                    administrators is not returned here.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The dashboard for the current month.",
                    content = @Content(schema = @Schema(implementation = DashboardResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403",
                    description = "The token belongs to an account that is not a student "
                            + "(ACCESS_DENIED).",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public DashboardResponse getDashboard(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getDashboard(principal);
    }
}
