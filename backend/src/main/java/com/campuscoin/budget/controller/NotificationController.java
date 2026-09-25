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

/**
 * Reading a student's notifications and marking them read: UC-14.
 *
 * <p>Three endpoints: list, read one, and mark one read. There is no create and no delete: every
 * notification is written by the procedure that owns it - a budget alert by
 * {@code sp_check_budget_alerts}, an announcement by the administrator procedure in module 11, a tip
 * or insight by later modules - and none of them is the student's to author. Giving a student a way
 * to write a notification would be a way to write one to somebody else; giving them a way to delete
 * one would erase a record of having been warned.
 *
 * <p><b>Marking read is a {@code POST} to a {@code /read} sub-path, not a {@code PATCH} of a
 * field.</b> Read-state is a one-way transition the database performs in a single statement that also
 * proves ownership ({@code sp_mark_notification_read}); a request body able to set {@code isRead}
 * would move the row without that predicate and would also allow un-reading, which the schema's
 * {@code ck_notif_read} pair and the notification's meaning both treat as not a thing that happens.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, so there is no way to reach another student's
 * messages - which matters more here than elsewhere, because a notification's body is readable prose
 * about one student's spending.
 */
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
