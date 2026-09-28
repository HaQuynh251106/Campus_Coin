package com.campuscoin.imports.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryAdvice;
import com.campuscoin.categorisation.entity.SuggestionCandidate;
import com.campuscoin.categorisation.service.CategorySuggester;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.category.repository.CategoryRepository;

@Component
public class ImportCategoryResolver {

    private final CategoryRepository categoryRepository;
    private final CategorySuggester categorySuggester;

    public ImportCategoryResolver(CategoryRepository categoryRepository,
                                  CategorySuggester categorySuggester) {
        this.categoryRepository = categoryRepository;
        this.categorySuggester = categorySuggester;
    }

    public List<Category> visibleCategories(Long userId) {
        return categoryRepository.findVisibleToUser(userId).stream()
                .filter(category -> !Boolean.FALSE.equals(category.getIsActive()))
                .toList();
    }

    public Optional<Long> resolveByName(List<Category> visible, String categoryName,
                                        CategoryType type) {
        if (categoryName == null || categoryName.isBlank() || type == null) {
            return Optional.empty();
        }
        String name = categoryName.trim();

        return visible.stream()
                .filter(category -> category.getType() == type)
                .filter(category -> name.equalsIgnoreCase(category.getName()))
                .min(Comparator.comparing(Category::isDefault))
                .map(Category::getId);
    }

    public Optional<Category> findVisible(Long userId, Long categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return categoryRepository.findVisibleById(categoryId, userId);
    }

    public Long suggestedCategoryId(List<Category> visible, List<com.campuscoin.categorisation.entity.CategoryRuleRow> rules,
                                    String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        CategoryAdvice advice = categorySuggester.advise(description, rules, toCandidates(visible));
        Long proposed = advice.categoryId();
        if (proposed == null) {
            return null;
        }

        return visible.stream()
                .anyMatch(category -> proposed.equals(category.getId()))
                ? proposed
                : null;
    }

    private static List<SuggestionCandidate> toCandidates(List<Category> visible) {
        return visible.stream()
                .map(category -> new SuggestionCandidate(
                        category.getId(), category.getName(), category.getType(), category.isDefault()))
                .toList();
    }
}
