package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A system announcement to publish (UC-21).")
public record CreateAnnouncementRequest(

        @Schema(description = "Headline. Cannot be blank.",
                example = "Scheduled maintenance this weekend",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Title is required.")
        @Size(max = 150, message = "Title must be at most 150 characters.")
        String title,

        @Schema(description = "The notice itself. Cannot be blank.",
                example = "Campus Coin will be unavailable on Saturday from 02:00 to 04:00 while "
                        + "the database is upgraded.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Body is required.")
        String body,

        @Schema(description = "How prominently to show it. Defaults to `INFO` when omitted.",
                example = "INFO", nullable = true)
        AnnouncementSeverity severity,

        @Schema(description = "Who the notice is for. Defaults to `STUDENTS` when omitted, which "
                + "is the safe direction: `ALL` must be asked for explicitly.",
                example = "STUDENTS", nullable = true)
        AnnouncementAudience audience,

        @Schema(description = "When the notice starts being shown. Defaults to the current time "
                + "when omitted.", example = "2026-09-26T02:00:00", nullable = true)
        LocalDateTime startsAt,

        @Schema(description = "When it stops being shown. Omit for a notice that stays up until it "
                + "is deactivated. Must be after `startsAt` when both are given.",
                example = "2026-09-27T04:00:00", nullable = true)
        LocalDateTime endsAt) {
}
