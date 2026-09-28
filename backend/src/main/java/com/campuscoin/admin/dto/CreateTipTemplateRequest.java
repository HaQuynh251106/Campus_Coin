package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
