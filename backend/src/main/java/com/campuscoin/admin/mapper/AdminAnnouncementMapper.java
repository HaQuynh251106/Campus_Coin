package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AnnouncementResponse;
import com.campuscoin.admin.entity.AdminAnnouncementRow;

@Component
public class AdminAnnouncementMapper {

    public AnnouncementResponse toResponse(AdminAnnouncementRow row) {
        return new AnnouncementResponse(
                row.id(),
                row.title(),
                row.body(),
                row.severity(),
                row.audience(),
                row.startsAt(),
                row.endsAt(),
                row.isActive(),
                row.createdAt());
    }

    public List<AnnouncementResponse> toResponses(List<AdminAnnouncementRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
