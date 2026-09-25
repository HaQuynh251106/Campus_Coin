package com.campuscoin.budget.dto;

import java.time.LocalDateTime;

import com.campuscoin.budget.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One notification as the client sees it (UC-14).
 *
 * <p><b>{@code userId} is absent.</b> It is the field a client would send to read somebody else's
 * notifications, and it has no other use - the list is always the caller's own, filtered server-side
 * by the account in the token. This is the same reason the other responses omit their owner column,
 * and it matters more here than usual: a notification's title and body are readable prose, so a
 * single missing filter would leak a message rather than an identifier.
 *
 * <p><b>{@code refEntityType} and {@code refEntityId} are published.</b> They are the pointer back
 * to whatever raised the message - for a budget alert, {@code BUDGET} and the budget's id - and a
 * client uses them to open the right screen from the notification. Publishing them is safe for the
 * same reason the notifications themselves are: a row only ever exists for its own owner, so the
 * reference can only ever name something the caller can already reach.
 *
 * <p><b>{@code isRead} and {@code readAt} are both published and always agree</b>, because
 * {@code ck_notif_read} requires it: a message is unread with no timestamp, or read with one. A
 * client needs only {@code isRead} to style a row, but {@code readAt} answers "when", which is what
 * a "mark all as read" summary needs, and publishing the pair makes the database's own invariant
 * visible rather than implied.
 *
 * <p>Nullable prose fields ({@code body}, {@code linkUrl}, {@code refEntityType},
 * {@code refEntityId}, {@code readAt}) are omitted when unset rather than serialised as null,
 * following the convention the earlier modules set.
 */
@Schema(description = "A notification addressed to the signed-in student (UC-14).")
public record NotificationResponse(

        @Schema(description = "Identifier, as the database assigned it.", example = "17")
        Long id,

        @Schema(description = "What kind of message this is. Budget alerts are `BUDGET_NEAR` and "
                + "`BUDGET_EXCEEDED`; the other values belong to later modules but are listed so a "
                + "client can switch exhaustively.", example = "BUDGET_NEAR")
        NotificationType type,

        @Schema(description = "Short headline.", example = "Approaching budget limit: Food & Drinks")
        String title,

        @Schema(description = "The message itself. Omitted when the notification has no body.",
                example = "You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String body,

        @Schema(description = "Where the message points, such as `/budgets`. Omitted when unset.",
                example = "/budgets", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String linkUrl,

        @Schema(description = "The kind of row the message is about. Omitted when unset.",
                example = "BUDGET", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refEntityType,

        @Schema(description = "The id of that row. Omitted when unset.", example = "3",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long refEntityId,

        @Schema(description = "False until the student reads it.", example = "false")
        Boolean isRead,

        @Schema(description = "When it was read. Present if and only if `isRead` is true.",
                example = "2026-09-25T09:30:00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime readAt,

        @Schema(description = "When it was raised. The list is ordered by this, newest first.",
                example = "2026-09-24T18:02:11")
        LocalDateTime createdAt) {
}
