package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One announcement as the administration screen shows it (UC-21).
 *
 * <p><b>The fields are the union of what a student sees and what only an administrator may see.</b>
 * A student's dashboard response carries the severity, the prose and the window; this one adds
 * {@code audience} and {@code isActive}, and both are needed to administer a notice. Without
 * {@code audience} an administrator could not tell a student-facing notice from one written for
 * other administrators, and without {@code isActive} there would be no way to see which notices are
 * currently suppressed. Neither is published to a student: the dashboard answers "is this notice for
 * me" in its own query and never says why.
 *
 * <p>{@code endsAt} is omitted when null rather than sent as {@code null}, matching how the other
 * responses in this API treat a nullable field with a meaning: absent means open-ended, and a notice
 * with no end date is one meant to stay up until it is deactivated.
 *
 * <p>Deliberately absent: {@code createdBy}. The API does not publish an author's identity - the same
 * position {@code DashboardViewDao} takes - and the audit trail records who published what.
 */
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
