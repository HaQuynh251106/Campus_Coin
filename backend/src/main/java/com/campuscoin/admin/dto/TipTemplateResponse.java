package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An administrator-managed saving-tip template (UC-21).")
public record TipTemplateResponse(

        @Schema(description = "Identifier.", example = "1")
        Long id,

        @Schema(description = "Stable code identifying the template. Immutable once the template "
                + "exists: it cannot be changed through this API.", example = "OVER_BUDGET")
        String code,

        @Schema(description = "Which condition the template's advice applies to. The generated "
                + "tip's rule, not merely a label.", example = "OVER_BUDGET")
        TipConditionType conditionType,

        @Schema(description = "Headline template, rendered with the student's figures.",
                example = "You are over budget on {category}")
        String titleTemplate,

        @Schema(description = "Advice template, rendered with the student's figures.",
                example = "You have spent {spent} of your {limit} limit for {category}.")
        String bodyTemplate,

        @Schema(description = "Ordering weight. Lower is shown first when several tips apply.",
                example = "10")
        Integer defaultPriority,

        @Schema(description = "False stops this template producing tips without deleting it.",
                example = "true")
        Boolean isActive) {
}
