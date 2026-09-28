package com.campuscoin.categorisation.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;

@Component
public class CategoryRuleMatcher {

    static final int MAX_KEYWORD_LENGTH = 80;

    public static String normalise(String description) {
        return description == null ? "" : description.trim().toLowerCase(Locale.ROOT);
    }

    public static String learnableKeyword(String description) {
        String normalised = normalise(description);

        if (normalised.isEmpty() || normalised.length() > MAX_KEYWORD_LENGTH) {
            return null;
        }
        return normalised;
    }

    public Optional<CategoryRuleRow> match(String description, List<CategoryRuleRow> rules) {
        String normalised = normalise(description);

        if (normalised.isEmpty()) {
            return Optional.empty();
        }

        for (CategoryRuleRow rule : rules) {
            if (rule.matchMode() == RuleMatchMode.EXACT && normalised.equals(rule.keyword())) {
                return Optional.of(rule);
            }
        }

        CategoryRuleRow longest = null;
        for (CategoryRuleRow rule : rules) {
            if (rule.matchMode() != RuleMatchMode.CONTAINS || !contains(normalised, rule.keyword())) {
                continue;
            }
            if (longest == null
                    || rule.keyword().length() > longest.keyword().length()
                    || (rule.keyword().length() == longest.keyword().length()
                            && Long.compare(rule.ruleId(), longest.ruleId()) < 0)) {
                longest = rule;
            }
        }

        return Optional.ofNullable(longest);
    }

    private static boolean contains(String normalised, String keyword) {
        return keyword != null && !keyword.isEmpty() && normalised.contains(keyword);
    }
}
