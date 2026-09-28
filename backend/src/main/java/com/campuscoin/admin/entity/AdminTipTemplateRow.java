package com.campuscoin.admin.entity;

public record AdminTipTemplateRow(
        Long id,
        String code,
        TipConditionType conditionType,
        String titleTemplate,
        String bodyTemplate,
        Integer defaultPriority,
        Boolean isActive) {
}
