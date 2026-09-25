package com.campuscoin.admin.entity;

import java.time.LocalDateTime;

import com.campuscoin.dashboard.entity.AnnouncementSeverity;

/**
 * One announcement in the administrator's list (UC-21).
 *
 * <p><b>Every row of the table is returned, not only the live ones.</b> The student dashboard reads
 * {@code v_active_announcements}, which applies the time window; an administration screen must not,
 * because the notice an administrator needs to act on is precisely the one outside its window or
 * already switched off - those are the rows UC-21 B2's toggle exists for. Filtering here would leave
 * a deactivated notice unreachable and therefore unrecoverable.
 *
 * <p>{@code createdBy} is not carried. The API does not publish an author's identity, the same
 * position {@code DashboardViewDao} takes for the student-facing read, and the audit trail already
 * records who published what.
 *
 * <p>A projection rather than a managed entity: nothing in this module changes an announcement's
 * content, and the one column that moves - {@code is_active} - is written by
 * {@code sp_admin_set_announcement_active}, not through a Hibernate {@code UPDATE}.
 */
public record AdminAnnouncementRow(
        Long id,
        String title,
        String body,
        AnnouncementSeverity severity,
        AnnouncementAudience audience,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Boolean isActive,
        LocalDateTime createdAt) {
}
