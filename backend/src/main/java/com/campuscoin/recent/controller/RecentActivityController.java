package com.campuscoin.recent.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.recent.dto.RecordRecentActivityRequest;
import com.campuscoin.recent.dto.RecentActivityListResponse;
import com.campuscoin.recent.dto.RecentActivityResponse;
import com.campuscoin.recent.service.RecentActivityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The transactions a student recently viewed or edited: UC-26.
 *
 * <p>Two endpoints. The list is a read; recording is a write, and it is a request of its own rather
 * than a side effect of reading a transaction - see {@code RecentActivityService} for why that
 * boundary is where it is.
 *
 * <p><b>Why this is not under {@code /transactions}.</b> The collection belongs to module 12, its
 * write goes through {@code sp_touch_recent_activity} rather than through module 4's procedures, and
 * its entries are not transactions - they are references to them, carrying an action and a time. A
 * route under {@code /api/v1/transactions/**} would tell a reader that these are transaction
 * operations, and it would put a module-12 capability behind module 4's URL. The one thing the
 * transaction module would have had to do differently is record a view on its own read path, and it
 * deliberately does not.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, and the one identifier either endpoint does
 * accept - a transaction - is checked against the caller's ownership by the database, so no request can
 * record activity against, or read back, another student's record.
 */
@RestController
@RequestMapping("/api/v1/recent-activity")
@Tag(name = "Recent activity",
        description = "The transactions a student recently opened or changed (UC-26).")
@SecurityRequirement(name = "bearerAuth")
public class RecentActivityController {

    private final RecentActivityService recentActivityService;

    public RecentActivityController(RecentActivityService recentActivityService) {
        this.recentActivityService = recentActivityService;
    }

    @GetMapping
    @Operation(
            summary = "List my recent activity",
            description = """
                    Returns the transactions the caller most recently viewed or edited, most recent \
                    first, each entry carrying enough to render a line without a second request: the \
                    category, the amount, the date and the student's own description.

                    **One transaction can appear twice.** An entry is identified by the transaction \
                    *and* the action, so a record the student both read and changed has one entry \
                    with `action` `VIEWED` and another with `action` `EDITED`. They are different \
                    facts and the list keeps them apart.

                    **A transaction in the trash is not listed.** Moving a record to the trash takes \
                    it off this list, and restoring it puts it back - the activity itself is never \
                    deleted, so the entry reappears rather than being re-recorded.

                    `limit` defaults to 10 and may be at most 50. A larger value is refused with \
                    `400` rather than reduced, because the response reports the limit it applied and \
                    a silently reduced answer would make that field untrue.

                    An empty array is a real answer: a student who has not opened anything yet has an \
                    empty list, which is different from the list not existing.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The caller's recent entries, most recent first."),
            @ApiResponse(responseCode = "400", description = "`limit` is out of range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RecentActivityListResponse listRecentActivity(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "How many entries to return. Defaults to 10; at most 50.")
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return recentActivityService.list(principal, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record that I viewed or edited a transaction",
            description = """
                    Records that the caller has just opened or changed one of their own \
                    transactions, and returns the entry as the list would show it.

                    **This is an explicit request rather than something a read does for you.** \
                    Opening a transaction does not record anything on its own - the transaction \
                    read stays a pure read, so a retry or a prefetch never writes. The client sends \
                    this request when the student actually acts.

                    **The entry is recorded even when the client sends nothing back**, but a client \
                    that never calls this endpoint will show an empty list. The record is the \
                    client's statement about what the student just did; the server cannot infer it \
                    from a read.

                    `transactionId` must be one of the caller's own. BR-02 is enforced by the \
                    database: a transaction belonging to another student is refused with the same \
                    `404` a transaction that does not exist gets, so this endpoint cannot be used to \
                    discover other students' transaction identifiers.

                    **Recording the same view twice moves the entry rather than adding one.** The \
                    list holds one entry per transaction and action, so re-opening something the \
                    student looked at earlier brings it back to the top with a new time - which is \
                    what "recently viewed" means.

                    `occurredAt` is not accepted and is not sent. The time comes from the database's \
                    clock, so the order of the list is the order the requests arrived in rather than \
                    the order of a client's clock.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded entry.",
                    content = @Content(schema = @Schema(implementation = RecentActivityResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`transactionId` or `action` is missing, or `action` is not "
                            + "`VIEWED` or `EDITED`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such transaction of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RecentActivityResponse recordRecentActivity(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RecordRecentActivityRequest request) {
        return recentActivityService.record(principal, request);
    }
}
