package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/tip-templates} (UC-21 B3): add a saving-tip template.
 *
 * <p><b>{@code code}, {@code titleTemplate} and {@code bodyTemplate} are required</b>, and the
 * procedure refuses an insert missing any of the three: a template with no code cannot be referred to
 * by a rule, and one with no text has nothing to render. The code is uppercased and trimmed by the
 * service before it is stored, so a client sending {@code over_budget} does not create a second
 * template that differs from {@code OVER_BUDGET} only in case - {@code uk_tip_template_code} is the
 * guarantee, but a collision it has to refuse is a worse outcome than a normalisation that avoids it.
 *
 * <p>{@code conditionType} defaults to {@code GENERIC}, matching the procedure's own {@code IFNULL}.
 * Unlike {@code severity} on an announcement this is not a safety default but a genuine one: a
 * template whose condition is not yet decided is advice that always applies, and {@code GENERIC} is
 * what the seeded catch-all uses.
 *
 * <p><b>The body is bounded at 65535 characters, which is the {@code TEXT} column's width.</b>
 * Nothing shorter would be defensible for a template carrying several placeholders and a sentence of
 * advice, and nothing longer can be stored.
 *
 * <p>The pattern check on the code is deliberately narrow - uppercase letters, digits and
 * underscores - because the code is an identifier that appears in the audit trail and in
 * {@code tip_templates} lookups, not display text. A code with a space or a slash in it would be a
 * value the schema accepts and no client can use.
 */
@Schema(description = "A saving-tip template to create (UC-21).")
public record CreateTipTemplateRequest(

        @Schema(description = "Stable identifier for the template. Letters, digits and "
                + "underscores; stored upper case. Must not already be in use.",
                example = "OVER_BUDGET", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Code is required.")
        @Pattern(regexp = "^[A-Za-z0-9_]{1,50}$",
                message = "Code must be 1 to 50 characters, using letters, digits and underscores "
                        + "only.")
        String code,

        @Schema(description = "Which condition the advice applies to. Defaults to `GENERIC`, which "
                + "always applies.", example = "OVER_BUDGET", nullable = true)
        TipConditionType conditionType,

        @Schema(description = "Headline template, rendered with the student's figures.",
                example = "You are over budget on {category}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Title template is required.")
        @Size(max = 200, message = "Title template must be at most 200 characters.")
        String titleTemplate,

        @Schema(description = "Advice template, rendered with the student's figures.",
                example = "You have spent {spent} of your {limit} limit for {category}.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Body template is required.")
        @Size(max = 65535, message = "Body template must be at most 65535 characters.")
        String bodyTemplate,

        @Schema(description = "Ordering weight. Lower is shown first when several tips apply. "
                + "Defaults to 100.", example = "10", nullable = true)
        @NotNull(message = "Default priority must be a whole number.")
        @Min(value = 0, message = "Default priority cannot be negative.")
        Integer defaultPriority,

        @Schema(description = "False creates the template already switched off, so it produces no "
                + "tips. Defaults to true.", example = "true", nullable = true)
        Boolean isActive) {
}
