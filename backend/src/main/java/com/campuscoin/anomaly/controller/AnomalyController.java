package com.campuscoin.anomaly.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.anomaly.dto.AnomalyScanResponse;
import com.campuscoin.anomaly.dto.FlaggedTransactionListResponse;
import com.campuscoin.anomaly.service.AnomalyService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/anomalies")
@Tag(name = "Anomaly detection",
        description = "Records in the signed-in student's own history that look like duplicates or "
                + "unusually large amounts (UC-24).")
@SecurityRequirement(name = "bearerAuth")
public class AnomalyController {

    private final AnomalyService anomalyService;

    public AnomalyController(AnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @GetMapping
    @Operation(
            summary = "List my flagged records",
            description = """
                    Returns the caller's records that the anomaly check has marked, most recent first, \
                    each entry carrying the record's own details - category, amount, date, description \
                    - beside the mark and the detector's explanation of it.

                    **Reading does not examine anything.** This endpoint reports the flags as they \
                    stand, so opening the screen cannot change what is on it. The request that \
                    recomputes them is the scan.

                    **Two kinds of mark.** `DUPLICATE` means another of the student's own records has \
                    the same amount in the same category within a few days. `UNUSUAL_AMOUNT` means the \
                    amount is several times what the student usually spends in that category. Both \
                    thresholds are deployment settings, not values this API lets a client choose.

                    **Nothing flagged is the ordinary case.** An empty array means the student's own \
                    records were examined and none look wrong - which is different from the list not \
                    existing, so this endpoint does not answer `404`.

                    `limit` defaults to 20 and may be at most 100. A larger value is refused with \
                    `400` rather than reduced, because the response reports the limit it applied and a \
                    silently reduced answer would make that field untrue.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The caller's flagged records, most recent first."),
            @ApiResponse(responseCode = "400", description = "`limit` is out of range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public FlaggedTransactionListResponse listFlagged(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "How many entries to return. Defaults to 20; at most 100.")
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return anomalyService.list(principal, limit);
    }

    @PostMapping("/scan")
    @Operation(
            summary = "Examine my records and update the marks",
            description = """
                    Examines the caller's own records, works out which of them look wrong, and applies \
                    the difference - then returns what it did and the resulting list.

                    **The client states nothing.** There is no request body: the caller asks for the \
                    check and the server decides what each record's mark should be. A client cannot \
                    mark a record, and in particular cannot mark its own record as reviewed, which is \
                    the whole point of the feature.

                    **Safe to call repeatedly, and provably so.** Only differences are written: a \
                    second scan over unchanged records finds the same conclusion for each one, writes \
                    nothing at all, and reports every record under `unchanged`. So refreshing on every \
                    screen visit cannot accumulate history rows.

                    **It also clears marks.** A record the student has since corrected, or whose twin \
                    has been put in the trash, loses its mark on the next scan - so the counts report \
                    `cleared` separately from `flagged`, and \
                    `examined = flagged + cleared + unchanged`.

                    **What "wrong" means.** A record is a `DUPLICATE` when another of the student's \
                    own records has the same amount and category within the configured number of days. \
                    It is `UNUSUAL_AMOUNT` when it is at least the configured multiple of the student's \
                    own average in that category, counting their other records and not this one - so a \
                    single large record cannot raise the average it is measured against. Both figures \
                    are deployment settings and are not parameters here.

                    **Trashed records take no part.** A record in the trash is not examined, is not \
                    counted, and is not a baseline. Putting one of a duplicate pair in the trash \
                    therefore clears the other on the next scan, with no separate step.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "What the scan examined and changed, with the resulting list.",
                    content = @Content(schema = @Schema(implementation = AnomalyScanResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500",
                    description = "The scan read a record it could not write a mark for, which means "
                            + "the student's own data changed mid-request. Reload and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AnomalyScanResponse scanForAnomalies(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return anomalyService.scan(principal);
    }
}
