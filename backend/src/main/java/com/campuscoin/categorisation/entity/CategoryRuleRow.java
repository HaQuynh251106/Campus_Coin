package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

public record CategoryRuleRow(
        Long ruleId,
        String keyword,
        RuleMatchMode matchMode,
        Long categoryId,
        BigDecimal confidence) {
}
