package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/admin/announcements/{id}} (UC-21 B2): show or withdraw a notice.
 *
 * <p><b>One field, and content is create-once.</b> The schema has no procedure that updates an
 * announcement's title, body, severity, audience or window - while {@code tip_templates} does have an
 * upsert, so the asymmetry between the two resources is the database's own and this module follows
 * it rather than working around it. A typo is corrected by publishing a replacement notice and
 * deactivating the original, which keeps the record of what students were actually told intact
 * instead of rewriting it after the fact.
 *
 * <p><b>The single field keeps every administrative write on a procedure, and that is the point.</b>
 * This write goes through {@code sp_admin_set_announcement_active}, which calls
 * {@code sp_require_admin} and appends the {@code admin_audit_log} row. Publishing content through a
 * Hibernate {@code UPDATE} would be the one administrative path that skipped both, and OB-005 exists
 * to assert there is no such path.
 *
 * <p>Required rather than optional for the same reason {@link SetUserStatusRequest#status} is:
 * there is exactly one meaningful value and "I forgot the field" should be a validation error
 * rather than a call that changes nothing.
 */
@Schema(description = "Show or withdraw an announcement (UC-21 B2).")
public record UpdateAnnouncementRequest(

        @Schema(description = "True shows the notice on every dashboard it is addressed to; false "
                + "withdraws it without deleting it. Whether it actually appears also depends on "
                + "its start and end times.", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose whether the announcement is active: true or false.")
        Boolean isActive) {
}
