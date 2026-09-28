package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A system announcement (UC-21).")
public record AnnouncementResponse(

        @Schema(description = "Identifier, used by the activate/deactivate action.", example = "1")
        Long id,

        @Schema(description = "Headline.", example = "Scheduled maintenance this weekend")
        String title,

        @Schema(description = "The notice itself.", example = "Campus Coin will be unavailable on "
                + "Saturday from 02:00 to 04:00 while the database is upgraded.")
        String body,

        @Schema(description = "How prominently to show it: `INFO`, `WARNING` or `SUCCESS`.",
                example = "INFO")
        AnnouncementSeverity severity,

        @Schema(description = "Who the notice is for: `ALL`, `STUDENTS` or `ADMINS`. A student's "
                + "dashboard never receives an `ADMINS` notice.", example = "STUDENTS")
        AnnouncementAudience audience,

        @Schema(description = "When the notice starts being shown.", example = "2026-09-26T02:00:00")
        LocalDateTime startsAt,

        @Schema(description = "When it stops being shown. Omitted when the notice is open-ended, "
                + "which means it stays up until it is deactivated.", example = "2026-09-27T04:00:00",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime endsAt,

        @Schema(description = "False suppresses the notice without deleting it (UC-21 B2). Content "
                + "is create-once: a correction is a new notice with the old one deactivated.",
                example = "true")
        Boolean isActive,

        @Schema(description = "When the notice was created.", example = "2026-09-25T11:00:00")
        LocalDateTime createdAt) {
}
