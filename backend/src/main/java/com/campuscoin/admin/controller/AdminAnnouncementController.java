package com.campuscoin.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.AnnouncementResponse;
import com.campuscoin.admin.dto.CreateAnnouncementRequest;
import com.campuscoin.admin.dto.UpdateAnnouncementRequest;
import com.campuscoin.admin.service.AdminAnnouncementService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The notices shown on dashboards: UC-21 B1 and B2.
 *
 * <p>Three endpoints: list, publish and switch on or off. There is no edit and no delete. An
 * announcement is a thing that was published - students may already have read it - so a correction is a
 * new notice with the old one withdrawn, and the withdrawn one stays in the record rather than
 * vanishing from under an audit trail. That is also why every write here goes through a stored
 * procedure: there is no unaudited path to this table, which is the invariant
 * {@code docs/OVERNIGHT_BLOCKERS.md} OB-005 states.
 *
 * <p><b>{@code GET} is not the student dashboard's announcement slice.</b> That one reads
 * {@code v_active_announcements}: only notices that are live right now and addressed to students or to
 * everyone. An administrator must see the expired, the not-yet-started, the withdrawn and the
 * {@code ADMINS}-only rows - the first three are the ones the toggle exists to act on, and the last
 * would otherwise be invisible to the people it is written for. Same table, different question, so a
 * different reader rather than a filter over the student one.
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "Administration - announcements",
        description = "Publish, list and withdraw system announcements (UC-21). Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminAnnouncementController {

    private final AdminAnnouncementService adminAnnouncementService;

    public AdminAnnouncementController(AdminAnnouncementService adminAnnouncementService) {
        this.adminAnnouncementService = adminAnnouncementService;
    }

    @GetMapping
    @Operation(
            summary = "List every announcement",
            description = """
                    Returns every announcement, newest first, **including** ones that have expired, \
                    have not started yet, have been switched off, or are addressed only to \
                    administrators.

                    That is deliberate and different from what a student's dashboard receives. A \
                    notice that is not currently showing is exactly the one an administrator needs to \
                    find in order to switch it back on, and a notice addressed to administrators \
                    would otherwise be invisible to them.

                    Each row carries its `isActive` flag and its window, so the list says why a notice \
                    is not showing: switched off, not started, or finished.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every announcement, newest first."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<AnnouncementResponse> listAnnouncements() {
        return adminAnnouncementService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Publish an announcement",
            description = """
                    Publishes a notice and returns it, already active.

                    `audience` decides who sees it, and the default is the narrow one: omitted means \
                    `STUDENTS`. `ALL` has to be asked for, because a notice that reaches every account \
                    is the one worth being deliberate about; `ADMINS` reaches only administrators and is \
                    never shown on a student dashboard.

                    `severity` is presentation only - `INFO`, `WARNING` or `SUCCESS` - and defaults to \
                    `INFO`. `startsAt` defaults to now, and `endsAt` may be omitted for a notice that \
                    stays up until it is switched off.

                    **`endsAt` must be later than `startsAt`.** Sending an end at or before the start \
                    is `400` with a field error on `endsAt`, rather than being stored and silently \
                    never appearing.

                    Content is written once. There is no edit endpoint: correcting a notice means \
                    publishing the corrected text and switching this one off.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The announcement that was published.",
                    content = @Content(schema = @Schema(implementation = AnnouncementResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`title` or `body` is blank, or the end time is not after the start.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AnnouncementResponse createAnnouncement(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateAnnouncementRequest request,
            HttpServletRequest httpRequest) {
        return adminAnnouncementService.create(
                request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Show or withdraw an announcement",
            description = """
                    Switches one announcement on or off and returns it.

                    **This writes one field.** An announcement's text, audience and window are fixed \
                    when it is published: withdrawing it and publishing a corrected notice is how a \
                    mistake is fixed, so that what students actually read stays on the record.

                    `isActive: false` withdraws the notice immediately; `isActive: true` shows it \
                    again - subject to its window, so re-activating a notice that has already ended \
                    does not put it back on dashboards.

                    Sending the state it is already in is accepted and recorded, and answers `200` \
                    with the row.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The announcement, in its new state.",
                    content = @Content(schema = @Schema(implementation = AnnouncementResponse.class))),
            @ApiResponse(responseCode = "400", description = "`isActive` is missing.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No announcement has this id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The announcement changed during the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AnnouncementResponse updateAnnouncement(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnnouncementRequest request,
            HttpServletRequest httpRequest) {
        return adminAnnouncementService.setActive(
                id, request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }
}
