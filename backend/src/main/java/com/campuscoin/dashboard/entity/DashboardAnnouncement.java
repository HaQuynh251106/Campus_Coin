package com.campuscoin.dashboard.entity;

import java.time.LocalDateTime;

public record DashboardAnnouncement(
        Long id,
        String title,
        String body,
        AnnouncementSeverity severity,
        LocalDateTime startsAt,
        LocalDateTime endsAt) {
}
