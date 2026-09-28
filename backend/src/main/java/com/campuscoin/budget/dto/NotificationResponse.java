package com.campuscoin.budget.dto;

import java.time.LocalDateTime;

import com.campuscoin.budget.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A notification addressed to the signed-in student (UC-14).")
public record NotificationResponse(

        @Schema(description = "Identifier, as the database assigned it. The value in this example "
                + "is an illustration of the type, not a row that exists: a notification is written "
                + "by the procedure that owns it, never by a client, so take the id from a response.",
                example = "17")
        Long id,

        @Schema(description = "What kind of message this is. Budget alerts are `BUDGET_NEAR` and "
                + "`BUDGET_EXCEEDED`; the other values belong to later modules but are listed so a "
                + "client can switch exhaustively.", example = "BUDGET_NEAR")
        NotificationType type,

        @Schema(description = "Short headline. The wording is built by the procedure that raises "
                + "the alert (`sp_check_budget_alerts`), using the category's own name.",
                example = "Approaching budget limit: Food")
        String title,

        @Schema(description = "The message itself. Omitted when the notification has no body.",
                example = "You have used 80.00% of your Food budget (24.00 of 30.00).",
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
