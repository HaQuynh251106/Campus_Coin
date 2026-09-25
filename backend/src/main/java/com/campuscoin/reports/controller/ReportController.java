package com.campuscoin.reports.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.reports.dto.ReportResponse;
import com.campuscoin.reports.dto.SpendingSeriesResponse;
import com.campuscoin.reports.entity.ReportGranularity;
import com.campuscoin.reports.service.ReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The student's financial reports: UC-15, and the reads UC-16 exports.
 *
 * <p>Two endpoints, and the split between them is the module's central decision rather than a
 * preference. Most of a report can be asked about <em>any</em> month: {@code v_monthly_income_expense}
 * and {@code v_category_month_totals} are keyed by {@code period_month}, so a student can review
 * September in December. The breakdown by day or by week cannot: both of its views derive their range
 * from {@code CURDATE()} inside the database session and answer about the current month only. Serving
 * the two together would mean one payload whose month was selectable for its totals and fixed for its
 * bars, and no field of that payload would reveal it - every number would be individually correct.
 * So they are two reads, and the second refuses a month it cannot honour instead of quietly ignoring
 * one.
 *
 * <p><b>UC-16 is not a third endpoint.</b> BR-18 requires that an export file be produced only when
 * the user asks and that nothing be buffered - "the file is generated at call time" - which is exactly
 * what these two reads already do. An export route returning the same figures as CSV would be a
 * second way to ask one question (section 13), and its payload would be the one these endpoints
 * return, formatted differently. The reports a client exports are the reports it fetched; see
 * {@code MODULE_08_REPORTS.md} §4 for the full reasoning, including what was deliberately not built.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, which is what makes reading another student's
 * report impossible rather than merely refused (BR-02).
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports",
        description = "A student's monthly totals, category breakdowns and spending series, and the "
                + "figures an export is generated from (UC-15, UC-16).")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(
            summary = "Get my report for a month",
            description = """
                    Returns one month's report: the month's totals (UC-15), its spending and income \
                    broken down by category, and the six-month trend.

                    `month` selects the month as `yyyy-MM` and defaults to the current one. Any month \
                    the caller has records for may be asked for, including a month with none - the \
                    totals block is present either way, and its four figures are **absent** rather \
                    than zero when the month is empty, because "recorded nothing" and "recorded \
                    activity that netted to nothing" are different statements.

                    `sixMonthTrend` is the exception to the selected month: BR-17 defines it as the \
                    last six months ending at the **current** one, a fixed window, so it is the same \
                    six points whatever `month` says, and each point carries its own month. Months \
                    that hold no data are present as `0.00` rather than omitted (BR-17, UAT-09).

                    `percentage` on a category is that category's own share of the block it appears \
                    in, rounded to two decimals: a Food slice of `25.00` in a block of `100.00` is \
                    `25.00`. The shares of one block therefore do **not** in general add up to exactly \
                    100 - each is rounded on its own, so three equal thirds each read `33.33` and \
                    total `99.99`. No slice is adjusted to absorb a remainder, because a slice's \
                    percentage is a property of that slice and a report is the wrong place to publish \
                    a number that is not the category's share. `total` is exact, and it is what a \
                    whole pie should be drawn from. It is the one figure this API computes, because \
                    no database view computes a share of a month.

                    The breakdown by day or by week is **not** here - it cannot be asked about another \
                    month, so it has its own endpoint, `GET /api/v1/reports/spending`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The month's report.",
                    content = @Content(schema = @Schema(implementation = ReportResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`month` is not in `yyyy-MM` form or names no real month "
                            + "(VALIDATION_ERROR).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403",
                    description = "The token belongs to an account that is not a student "
                            + "(ACCESS_DENIED).",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ReportResponse getReport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "The month to report on, as `yyyy-MM`. Defaults to the current "
                    + "month.", example = "2026-09")
            @RequestParam(required = false) String month) {
        return reportService.getReport(principal, month);
    }

    @GetMapping("/spending")
    @Operation(
            summary = "Get my spending by day or by week",
            description = """
                    Returns the caller's spending for the **current month**, broken down either by \
                    calendar day or by ISO week.

                    **This is the one report that cannot be asked about another month.** Both views \
                    behind it derive their range from the database's own clock, so `month` is \
                    accepted only as the current month and any other value is refused with \
                    `400 VALIDATION_ERROR` rather than answered from the wrong month. `from` and `to` \
                    narrow the window **within** the current month and are refused if they fall \
                    outside it; omitting them covers the whole month.

                    Intervals with no spending are **absent** rather than zero, and the response \
                    publishes the window it covered (`from`/`to`) so a chart knows where its axis \
                    starts and ends. That is deliberately the opposite of the six-month trend, which \
                    zero-fills because BR-17 requires all six months - the schema decides which \
                    report fills its gaps, and this endpoint does not overrule it.

                    For `WEEKLY`, each point is a real Monday-to-Sunday ISO week, so a bar's dates \
                    may reach outside the current month — a month boundary never splits an ISO week \
                    (VĐ-10). The bar's **total**, however, counts only records inside the month, \
                    exactly as the daily breakdown does, so the two granularities report the same \
                    month total. What an ISO week changes is a **narrowed** window: a bar that \
                    merely overlaps `from`/`to` is returned whole, so a weekly series can exceed the \
                    daily series over the same narrow window.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The window's spending series.",
                    content = @Content(schema = @Schema(implementation = SpendingSeriesResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "A parameter is malformed, names a month other than the current "
                            + "one, falls outside it, or the window is inverted (VALIDATION_ERROR).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403",
                    description = "The token belongs to an account that is not a student "
                            + "(ACCESS_DENIED).",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SpendingSeriesResponse getSpendingSeries(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "The month to report on. Only the current month is available; "
                    + "any other value is refused.", example = "2026-09")
            @RequestParam(required = false) String month,
            @Parameter(description = "First day of the window, `yyyy-MM-dd`, within the current "
                    + "month. Defaults to the first of the month.", example = "2026-09-01")
            @RequestParam(required = false) String from,
            @Parameter(description = "Last day of the window, `yyyy-MM-dd`, within the current "
                    + "month. Defaults to the last day of the month.", example = "2026-09-30")
            @RequestParam(required = false) String to,
            @Parameter(description = "`DAILY` for one point per day, `WEEKLY` for one point per ISO "
                    + "week. Defaults to `DAILY`.", example = "DAILY")
            @RequestParam(required = false, defaultValue = "DAILY") String granularity) {
        return reportService.getSpendingSeries(principal, month, from, to,
                parseGranularity(granularity));
    }

    /**
     * Reads the granularity parameter.
     *
     * <p>Parsed here rather than bound as an enum so the failure is a field-level validation error
     * naming the value that was wrong, in the same shape as every other validation problem the API
     * reports - rather than a type-mismatch message that describes Java's binding instead of the
     * request.
     */
    private ReportGranularity parseGranularity(String granularity) {
        try {
            return ReportGranularity.valueOf(granularity.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RequestValidationException(
                    "The granularity is not a supported value.",
                    List.of(new ApiError.FieldError("granularity",
                            "Choose DAILY or WEEKLY.")));
        }
    }
}
