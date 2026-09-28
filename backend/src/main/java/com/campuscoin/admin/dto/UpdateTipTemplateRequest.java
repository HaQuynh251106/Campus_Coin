package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
