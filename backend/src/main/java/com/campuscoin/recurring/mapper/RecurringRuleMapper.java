package com.campuscoin.recurring.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.entity.Category;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.recurring.dto.RecurringRuleResponse;
import com.campuscoin.recurring.entity.RecurringRule;

@Component
public class RecurringRuleMapper {

    private final EncryptionService encryptionService;

    public RecurringRuleMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public RecurringRuleResponse toResponse(RecurringRule rule) {
        Category category = rule.getCategory();

        return new RecurringRuleResponse(
                rule.getId(),
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getType(),
                rule.getAmount(),
                encryptionService.decryptStored(rule.getDescription()),
                rule.getFrequency(),
                rule.getIntervalCount(),
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getNextRunDate(),
                rule.getLastRunDate(),
                rule.getStatus());
    }
}
