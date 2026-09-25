package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/announcements} (UC-21 B1): publish a system announcement.
 *
 * <p><b>{@code title} and {@code body} are required; everything else has a default.</b>
 * {@code sp_admin_create_announcement} refuses a null title or body, because a notice with no
 * headline cannot be scanned and one with no text says nothing. Validating here turns that refusal
 * into a field error naming the input.
 *
 * <p>The three absent-means-default fields mirror the procedure's own {@code IFNULL} fallbacks
 * exactly - {@code severity} to {@code INFO}, {@code audience} to {@code STUDENTS}, and
 * {@code startsAt} to {@code NOW()} - so a client that omits them gets the same notice the database
 * would have produced, and the documented default and the stored one are the same value.
 *
 * <p><b>{@code audience} defaults to {@code STUDENTS} and that is the safe direction.</b>
 * {@code ADMINS} is a deliberate choice a caller must make; defaulting to {@code ALL} would publish
 * an administrator's message to every student, which is a disclosure rather than a cosmetic slip.
 * The database defaults the same way, for the same reason.
 *
 * <p><b>The window is validated here before the procedure sees it.</b> {@code ck_ann_window}
 * requires {@code ends_at IS NULL OR ends_at > starts_at}, and a CHECK violation arrives as a
 * generic conflict with nothing a caller can act on. An end that is not after the start is refused
 * as a field error on {@code endsAt} instead - the rule is the schema's, and this only decides how
 * it is reported.
 *
 * <p>Deliberately absent: {@code isActive}. A notice is created live; UC-21 B2's deactivate is the
 * transition that withdraws it, and creating one switched off would be an unusual state to express
 * through the create call rather than a second step.
 */
@Schema(description = "A system announcement to publish (UC-21).")
public record CreateAnnouncementRequest(

        @Schema(description = "Headline. Cannot be blank.",
                example = "Scheduled maintenance this weekend",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Title is required.")
        @Size(max = 150, message = "Title must be at most 150 characters.")
        String title,

        @Schema(description = "The notice itself. Cannot be blank.",
                example = "Campus Coin will be unavailable on Saturday from 02:00 to 04:00 while "
                        + "the database is upgraded.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Body is required.")
        String body,

        @Schema(description = "How prominently to show it. Defaults to `INFO` when omitted.",
                example = "INFO", nullable = true)
        AnnouncementSeverity severity,

        @Schema(description = "Who the notice is for. Defaults to `STUDENTS` when omitted, which "
                + "is the safe direction: `ALL` must be asked for explicitly.",
                example = "STUDENTS", nullable = true)
        AnnouncementAudience audience,

        @Schema(description = "When the notice starts being shown. Defaults to the current time "
                + "when omitted.", example = "2026-09-26T02:00:00", nullable = true)
        LocalDateTime startsAt,

        @Schema(description = "When it stops being shown. Omit for a notice that stays up until it "
                + "is deactivated. Must be after `startsAt` when both are given.",
                example = "2026-09-27T04:00:00", nullable = true)
        LocalDateTime endsAt) {
}
