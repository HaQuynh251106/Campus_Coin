package com.campuscoin.admin.entity;

import java.time.LocalDateTime;

import com.campuscoin.dashboard.entity.AnnouncementSeverity;

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
