package com.campuscoin.admin.dto;

import com.campuscoin.admin.entity.TipConditionType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One saving-tip template as the administration screen shows it (UC-21).
 *
 * <p>{@code titleTemplate} and {@code bodyTemplate} are templates, not finished prose: the database
 * renders them with the student's own figures when {@code sp_generate_tips} produces a tip, which is
 * why they are named "template" rather than "title" and "body". An administrator editing one is
 * editing what every student in that condition will be told.
 *
 * <p>{@code conditionParams} is deliberately not published. The column is a {@code JSON} blob whose
 * meaning the schema does not document and which nothing in this build reads, so exposing it would be
 * offering a field neither side can interpret. It is recorded as a follow-up in
 * {@code docs/OVERNIGHT_BLOCKERS.md} rather than guessed at.
 *
 * <p>Deliberately absent: {@code createdBy}, and the timestamps. The audit trail records who changed
 * a template and when, which is the record UC-22 B5 asks for.
 */
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
