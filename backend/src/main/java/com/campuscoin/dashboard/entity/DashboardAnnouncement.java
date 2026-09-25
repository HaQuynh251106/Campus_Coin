package com.campuscoin.dashboard.entity;

import java.time.LocalDateTime;

/**
 * One row of {@code v_active_announcements}: a notice currently inside its active window (UC-12 B3).
 *
 * <p><b>The window is already applied by the view; the audience is not.</b> The view selects on
 * {@code is_active = 1 AND starts_at <= NOW() AND (ends_at IS NULL OR ends_at >= NOW())}, so every row
 * here is live. It does not filter on {@code audience}, which means "live" and "meant for you" are two
 * different questions and only the first is answered by the view. The DAO therefore narrows to the
 * audiences a student may see; that filter is stated on
 * {@code DashboardViewDao#findAnnouncementsForStudent} rather than here, because it is part of asking
 * the question rather than part of the answer.
 *
 * <p><b>{@code audience} is not carried on this record.</b> After the filter it can only be
 * {@code ALL} or {@code STUDENTS}, and nothing a dashboard renders depends on which - the notice is
 * for the reader either way. It is used to select rows and then dropped, which is why no enum for it
 * exists in this module. {@code created_by} is absent for the usual reason: the API does not publish
 * who owns a row, and an announcement's author is an administrator's identity.
 *
 * <p>{@code style} carries the database's {@code severity} under the name a presentation layer reads
 * it by, so a dashboard can style the banner without a lookup table of its own.
 */
public record DashboardAnnouncement(
        Long id,
        String title,
        String body,
        AnnouncementSeverity severity,
        LocalDateTime startsAt,
        LocalDateTime endsAt) {
}
