package com.campuscoin.forecast.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.forecast.dto.ForecastResponse;
import com.campuscoin.forecast.service.ForecastService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * A student's projected next month: UC-25.
 *
 * <p><b>One read and no parameters.</b> The forecast is about the caller and the current month, both
 * of which the server already knows - the caller from the token, the month from the clock - so there
 * is nothing for a request to name. That is deliberate: a parameterised month would let a caller probe
 * months the feature was not meant to project, and a limit or a window would put the shape of the
 * estimate under client control when it is a documented judgement rather than a client choice.
 *
 * <p><b>Why this is not under {@code /reports} or {@code /dashboard}.</b> It reads the same
 * {@code v_monthly_income_expense} those screens read, but its answer is a projection, not a figure
 * the database holds - a different kind of statement, from a different module with its own method (a
 * trailing average, in {@code Forecaster}). A route under {@code /api/v1/reports/**} would tell a
 * reader the response is recorded data; it is the module-12 capability the brief locks, and it lives
 * on its own path for the same reason {@code /recent-activity} does.
 */
@RestController
@RequestMapping("/api/v1/forecast")
@Tag(name = "Forecast",
        description = "A student's current-month totals and a projection for the next month (UC-25).")
@SecurityRequirement(name = "bearerAuth")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping
    @Operation(
            summary = "Project my next month from my recent months",
            description = """
                    Returns the caller's totals for the month in progress and an estimate for the \
                    next month, computed as the average of their last three **complete** months.

                    **The estimate is a trailing average, not a model.** It is the simplest thing \
                    that answers "roughly what will next month cost", every figure in it can be \
                    checked against the student's own history, and it introduces no machine-learning \
                    platform. `recentMonths` carries the months that were averaged so a client can \
                    show or check the evidence.

                    **The month in progress is excluded from the baseline.** Its figures are partial \
                    and would fall as the month went on, so averaging it in would make the projection \
                    depend on the day it was asked. It is reported separately under \
                    `currentMonthTotals`.

                    **Absent blocks are meaningful.** A student who has recorded nothing this month \
                    has no `currentMonthTotals` - a block of zeroes would state that they had \
                    activity that netted to nothing. A student with no complete month behind them has \
                    no `projected` block and `basedOnMonths` is `0`; that is not "0.00 projected", \
                    which would read as a confident prediction of no spending. `projected.savings` \
                    may be negative, meaning spending is on course to outrun income.

                    The month in progress comes from the server's clock, not from the request, so the \
                    response is the same for every caller asking at the same moment in the configured \
                    zone (VĐ-10).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's current month and next-"
                    + "month projection.",
                    content = @Content(schema = @Schema(implementation = ForecastResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ForecastResponse forecast(@AuthenticationPrincipal AuthenticatedUser principal) {
        return forecastService.forecast(principal);
    }
}
