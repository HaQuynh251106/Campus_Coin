package com.campuscoin.budget.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.budget.dto.NotificationResponse;
import com.campuscoin.budget.service.NotificationService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications",
        description = "Reading budget alerts and other messages addressed to the signed-in student, "
                + "and marking them read (UC-14).")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(
            summary = "List my notifications",
            description = """
                    Returns every notification addressed to the caller, newest first.

                    Read and unread are returned together: the screen this serves is a "what have I \
                    missed" list, where hiding the read ones would remove the context for the unread \
                    ones. Each row carries `isRead` so the client decides how to present it, and \
                    `readAt` when it has been read.

                    Budget alerts (`BUDGET_NEAR`, `BUDGET_EXCEEDED`) are raised by the database when \
                    a transaction crosses a threshold (BR-12). The other `type` values belong to \
                    later modules but are returned here, so a client can render the list without \
                    knowing which module produced a message.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's notifications."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<NotificationResponse> listNotifications(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return notificationService.listNotifications(principal);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one of my notifications",
            description = """
                    Returns one notification addressed to the caller, so a client can open a single \
                    message - for example from a link in an email or a deep link - without loading \
                    the whole list.

                    This does **not** mark it read. Opening a message and acknowledging it are \
                    different actions, and a client that merely links to one should not silently \
                    clear the unread marker; the read endpoint is separate for that reason.

                    Another student's notification is not reachable here, and neither is one that does \
                    not exist. Both answer `404`, so a client cannot use this endpoint to discover \
                    which notification identifiers exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's notification.",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such notification of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public NotificationResponse getNotification(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        return notificationService.getNotification(principal, id);
    }

    @PostMapping("/{id}/read")
    @Operation(
            summary = "Mark one of my notifications as read",
            description = """
                    Marks the notification read and records when. Both are set together, which is \
                    what the `is_read`/`read_at` pair requires.

                    **Marking an already-read notification is not an error.** The response carries the \
                    notification either way, and the timestamp is the one from when it was actually \
                    read rather than the most recent attempt - so a retry, or two devices marking the \
                    same message, settle on the first time. This is the same treatment a recurring \
                    rule gives a status it already has: a request for the state the row is already in \
                    changes nothing and is not refused.

                    The transition is one-way. There is no endpoint that marks a notification unread \
                    - a message that has been seen has been seen.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The notification, now read.",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such notification of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public NotificationResponse markRead(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        return notificationService.markRead(principal, id);
    }
}
