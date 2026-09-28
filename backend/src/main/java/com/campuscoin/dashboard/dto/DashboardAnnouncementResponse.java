package com.campuscoin.dashboard.dto;

import java.time.LocalDateTime;

import com.campuscoin.dashboard.entity.AnnouncementSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A live system announcement addressed to students (UC-12 B3).")
public record DashboardAnnouncementResponse(

        @Schema(description = "Identifier of the announcement.", example = "1")
        Long id,

        @Schema(description = "The headline.", example = "Welcome to Campus Coin")
        String title,

        @Schema(description = "The notice itself.")
        String body,

        @Schema(description = "How prominently to show it: `INFO`, `WARNING` or `SUCCESS`.",
                example = "INFO")
        AnnouncementSeverity severity,

        @Schema(description = "When the announcement became visible.", example = "2026-09-01T00:00:00")
        LocalDateTime startsAt,

        @Schema(description = "When it stops being visible. Absent for an open-ended "
                + "announcement.", example = "2026-12-01T00:00:00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime endsAt) {
}
