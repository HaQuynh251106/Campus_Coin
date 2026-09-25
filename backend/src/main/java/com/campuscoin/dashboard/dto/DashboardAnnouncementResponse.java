package com.campuscoin.dashboard.dto;

import java.time.LocalDateTime;

import com.campuscoin.dashboard.entity.AnnouncementSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A system announcement shown to the signed-in student (UC-12 B3).
 *
 * <p><b>Every row here is live and addressed to the reader.</b> Two separate filters have already run:
 * {@code v_active_announcements} keeps only notices that are active and inside their
 * {@code starts_at}/{@code ends_at} window, and the DAO additionally keeps only the audiences a
 * student may see, because the view does not filter on {@code audience}. A notice written for
 * administrators is therefore never reachable from this endpoint.
 *
 * <p><b>{@code audience} is not published.</b> After the filter it can only have been {@code ALL} or
 * {@code STUDENTS}, and nothing about how a dashboard renders the banner depends on which. Publishing
 * it would expose the value the server used to decide rather than the notice itself.
 *
 * <p><b>The window is published in full rather than as a boolean.</b> {@code startsAt} and
 * {@code endsAt} let a client expire the banner on its own clock without re-asking, which matters for
 * a dashboard left open. {@code endsAt} is absent for an open-ended announcement -
 * {@code ck_ann_window} permits {@code ends_at IS NULL}, and the view reads that as "still running".
 *
 * <p>These are read-only here. Creating, editing and deactivating an announcement is UC-21 and
 * belongs to the administration module; this endpoint has no counterpart that writes one.
 */
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
