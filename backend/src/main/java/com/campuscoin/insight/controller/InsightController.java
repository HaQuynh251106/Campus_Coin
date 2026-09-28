package com.campuscoin.insight.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.insight.dto.InsightMonthsResponse;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.service.InsightService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "Monthly Insights",
        description = "The monthly summary and advice generated for the signed-in student (UC-17).")
@SecurityRequirement(name = "bearerAuth")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    @Operation(
            summary = "Get my insight for a month",
            description = """
                    Returns the caller's insight for one month: the month's totals, the categories that \
                    ran above their own usual level, and the summary and advice written about them.

                    `month` is optional. Left out, it means the current month as the server judges it. \
                    It is written `yyyy-MM`.

                    Reading does **not** generate and does not call any AI provider. A month whose \
                    insight was never produced answers `404` - an insight is one object rather than a \
                    list, so there is nothing to return an empty version of. Generate one for that \
                    month with `POST /api/v1/insights/generate` first.

                    `generatedBy` says where the prose came from: `RULE_BASED` when the database composed \
                    it from the figures, `AI` when a configured provider wrote it. The text is a \
                    suggestion, not financial advice (BR-13).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The month's insight.",
                    content = @Content(schema = @Schema(implementation = MonthlyInsightResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`month` is not a valid month.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No insight exists for that month.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public MonthlyInsightResponse getInsight(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String month) {
        return insightService.getInsight(principal, month);
    }

    @GetMapping("/months")
    @Operation(
            summary = "List the months that have an insight",
            description = """
                    Returns the months the caller has an insight for, newest first, as `yyyy-MM`.

                    This exists so a month picker offers only months that would return something. \
                    Building the list from the student's transactions instead would offer months whose \
                    insight was never generated, and opening one would answer `404` behind a menu entry.

                    The list can be empty. A student whose insight has never been generated has no \
                    months at all, and the current month is not special-cased into the list - it appears \
                    only if an insight for it was actually produced.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The months with an insight.",
                    content = @Content(schema = @Schema(implementation = InsightMonthsResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public InsightMonthsResponse listMonths(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return insightService.listMonths(principal);
    }

    @PostMapping("/generate")
    @Operation(
            summary = "Generate my insight for a month",
            description = """
                    Recomputes the caller's insight for the named month - or the current month when none \
                    is named - and returns it.

                    The month's figures, its unusual categories and a summary and advice are computed \
                    and stored first. Only then, if an AI provider is configured and enabled, it is \
                    given the month's totals and asked to write the summary and advice; what it writes \
                    is stored over the rule-based text. **The database never sends anything to a \
                    provider by itself** - the application reads, filters to the caller's own month and \
                    sends aggregates only.

                    **Works with no AI provider.** When there is no credential, when `ai.enabled` is off, \
                    or when the provider is unreachable, the rule-based summary stands and the request \
                    still succeeds. A response therefore always has `generatedBy` set, and `model` only \
                    when a provider wrote the text.

                    **Safe to call repeatedly.** The month is stored once and refreshed in place, so \
                    repeating the call cannot produce a second insight; a summary a provider already \
                    wrote is preserved rather than overwritten by a later run.

                    Generating costs nothing to observe: reading the month afterwards does not generate \
                    again.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The month's insight, after generating.",
                    content = @Content(schema = @Schema(implementation = MonthlyInsightResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`month` is not a valid month.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public MonthlyInsightResponse generate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String month) {
        return insightService.generate(principal, month);
    }
}
