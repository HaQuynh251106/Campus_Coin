package com.campuscoin.admin.entity;

/**
 * One saving-tip template in the administrator's list (UC-21).
 *
 * <p>{@code conditionParams} is deliberately not carried. The column is a {@code JSON} blob whose
 * meaning the schema does not document and which nothing in this build reads - {@code sp_generate_tips}
 * decides its conditions from the student's own data, not from this column - so publishing it would
 * be exposing a value nobody can interpret. It is recorded in {@code docs/OVERNIGHT_BLOCKERS.md} as a
 * follow-up rather than guessed at.
 *
 * <p>{@code createdBy} is absent for the reason {@code AdminCategoryRow} omits it: the audit trail is
 * the record of who did what, and a per-row author column would be a second one.
 */
public record AdminTipTemplateRow(
        Long id,
        String code,
        TipConditionType conditionType,
        String titleTemplate,
        String bodyTemplate,
        Integer defaultPriority,
        Boolean isActive) {
}
