package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/admin/tip-templates/{id}} (UC-21 B4): edit a saving-tip template.
 *
 * <p>Absent and {@code null} both mean "leave this field as it is", so a client sends only what it
 * edited. As on the default-category update, no field can be <em>cleared</em>:
 * {@code sp_admin_upsert_tip_template} writes each column as {@code IFNULL(p_value, column)}, so a
 * parameter either replaces a value or leaves it, and the four text columns are {@code NOT NULL}
 * anyway - there is no absent state for them to be moved into. The patterns below therefore require
 * at least one non-space character rather than accepting an empty string that would be stored as one.
 *
 * <p><b>{@code code} is accepted but immutable, and this is the field to be careful with.</b>
 * {@code sp_admin_upsert_tip_template}'s update branch does not mention {@code code} at all, so a
 * request carrying a different one would <em>succeed</em> while the stored code stayed as it was - a
 * client told "saved" with nothing changed, which is the worst kind of refusal to get wrong because
 * nothing signals it. The service therefore compares this field with the stored code and answers
 * {@code 409 TIP_TEMPLATE_CODE_IMMUTABLE} when they differ. Sending the code unchanged is accepted,
 * so a client may round-trip a full representation through this endpoint without special-casing the
 * field. Omitting it entirely is also accepted and means the same thing.
 *
 * <p>Immutability is a decision rather than a limitation: {@code code} identifies which rule a
 * template belongs to and appears in the audit trail, so renaming one would make every earlier
 * {@code TIP_TEMPLATE_SAVED} row refer to something that no longer exists under that name. A
 * template that genuinely needs a different code is a new template.
 */
@Schema(description = "The tip template fields to change. Omit a field to leave it as it is "
        + "(UC-21 B4).")
public record UpdateTipTemplateRequest(

        @Schema(description = "Must equal the template's current code when sent. The code cannot "
                + "be changed: sending a different one is refused with `409`. Omit it if you are "
                + "not round-tripping the whole template.", example = "OVER_BUDGET", nullable = true)
        @Pattern(regexp = "^[A-Za-z0-9_]{1,50}$",
                message = "Code must be 1 to 50 characters, using letters, digits and underscores "
                        + "only.")
        String code,

        @Schema(description = "New condition. This is what ties the template to a rule, so changing "
                + "it changes which students receive the advice.", example = "OVER_BUDGET",
                nullable = true)
        TipConditionType conditionType,

        @Schema(description = "New headline template.", example = "You are over budget on {category}",
                nullable = true)
        @Pattern(regexp = "(?s)^\\s*\\S.{0,199}\\s*$",
                message = "Title template must be between 1 and 200 characters.")
        String titleTemplate,

        @Schema(description = "New advice template.",
                example = "You have spent {spent} of your {limit} limit for {category}.",
                nullable = true)
        @Pattern(regexp = "(?s)^\\s*\\S.{0,65534}\\s*$",
                message = "Body template must be between 1 and 65535 characters.")
        String bodyTemplate,

        @Schema(description = "New ordering weight. Lower is shown first when several tips apply.",
                example = "10", nullable = true)
        @Min(value = 0, message = "Default priority cannot be negative.")
        Integer defaultPriority,

        @Schema(description = "False stops this template producing tips without deleting it.",
                example = "false", nullable = true)
        Boolean isActive) {
}
