package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AnnouncementResponse;
import com.campuscoin.admin.entity.AdminAnnouncementRow;

/**
 * Maps an announcement row to the API model (UC-21).
 *
 * <p>The only decision this class makes is that {@code id} leads the response. It is not decoration:
 * {@code PATCH /api/v1/admin/announcements/{id}} needs it, and the list is how an administrator finds
 * the id of the notice they want to withdraw. The dashboard's student-facing read deliberately
 * publishes no identifier, because no student action is addressed to an announcement; the two
 * responses describe the same table and are not the same model.
 *
 * <p>{@code createdBy} is not carried by {@link AdminAnnouncementRow} at all - the audit trail records
 * authorship - so there is nothing here to omit. The class exists as the one place the field order and
 * the null handling are visible.
 */
@Component
public class AdminAnnouncementMapper {

    /**
     * UC-21: one announcement as the administration screen shows it.
     *
     * <p>{@code endsAt} is nullable and {@code AnnouncementResponse} omits it when null, because an
     * announcement with no end is open-ended rather than faulty: the procedure's insert leaves
     * {@code ends_at} as the caller supplied it, and the property that such a notice "stays up until
     * deactivated" is a real state, not a missing value.
     */
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

    /** UC-21: the whole list, mapped row by row through {@link #toResponse}. */
    public List<AnnouncementResponse> toResponses(List<AdminAnnouncementRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
