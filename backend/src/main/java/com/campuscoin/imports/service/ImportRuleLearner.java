package com.campuscoin.imports.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.RuleSource;
import com.campuscoin.categorisation.repository.CategoryRuleDao;
import com.campuscoin.categorisation.service.CategoryRuleMatcher;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.entity.ImportRowStatus;

@Component
public class ImportRuleLearner {

    private final CategoryRuleDao categoryRuleDao;

    public ImportRuleLearner(CategoryRuleDao categoryRuleDao) {
        this.categoryRuleDao = categoryRuleDao;
    }

    public int learn(Long userId, List<ImportRow> rows, List<Category> visible) {
        Map<Long, CategoryType> typeByCategoryId = typesById(visible);
        int learned = 0;

        for (ImportRow row : rows) {
            if (row.rowStatus() != ImportRowStatus.IMPORTED) {
                continue;
            }

            String keyword = CategoryRuleMatcher.learnableKeyword(row.parsedDescription());
            if (keyword == null) {
                continue;
            }

            CategoryType type = typeByCategoryId.get(row.resolvedCategoryId());
            if (type == null) {
                continue;
            }

            categoryRuleDao.upsert(userId, keyword, type, row.resolvedCategoryId(), RuleSource.IMPORT);
            learned++;
        }

        return learned;
    }

    private static Map<Long, CategoryType> typesById(List<Category> visible) {
        Map<Long, CategoryType> types = new HashMap<>();
        for (Category category : visible) {
            types.putIfAbsent(category.getId(), category.getType());
        }
        return types;
    }
}
