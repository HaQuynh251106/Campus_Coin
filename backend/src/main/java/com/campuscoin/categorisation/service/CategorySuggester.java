package com.campuscoin.categorisation.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryAdvice;
import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.SuggestionCandidate;
import com.campuscoin.categorisation.entity.SuggestionSource;
import com.campuscoin.common.ai.AiSuggestionPort;
import com.campuscoin.common.ai.CategorySuggestion;
import com.campuscoin.common.ai.CategorySuggestionRequest;

@Component
public class CategorySuggester {

    private static final BigDecimal RULE_CONFIDENCE = BigDecimal.valueOf(1).setScale(4);

    private final AiSuggestionPort aiSuggestionPort;
    private final CategoryRuleMatcher categoryRuleMatcher;

    public CategorySuggester(AiSuggestionPort aiSuggestionPort,
                             CategoryRuleMatcher categoryRuleMatcher) {
        this.aiSuggestionPort = aiSuggestionPort;
        this.categoryRuleMatcher = categoryRuleMatcher;
    }

    public CategoryAdvice advise(String description, List<CategoryRuleRow> rules,
                                 List<SuggestionCandidate> candidates) {
        Map<Long, SuggestionCandidate> byCategoryId = candidates.stream()
                .collect(Collectors.toMap(SuggestionCandidate::categoryId, Function.identity(),
                        (first, second) -> first));

        Optional<CategoryRuleRow> matched = categoryRuleMatcher.match(description, rules);
        if (matched.isPresent()) {
            SuggestionCandidate candidate = byCategoryId.get(matched.get().categoryId());
            if (candidate != null) {
                return new CategoryAdvice(SuggestionSource.RULE, candidate.categoryId(),
                        candidate.categoryName(), candidate.type(),
                        confidenceOf(matched.get()), null);
            }
        }

        if (CategoryRuleMatcher.normalise(description).isEmpty()) {
            return nothing();
        }

        List<CategorySuggestionRequest.Candidate> offered = candidates.stream()
                .map(candidate -> new CategorySuggestionRequest.Candidate(
                        candidate.categoryName(), candidate.type().name()))
                .toList();

        Optional<CategorySuggestion> proposal =
                aiSuggestionPort.suggestCategory(new CategorySuggestionRequest(description, offered));

        return proposal.flatMap(suggestion -> resolve(suggestion, candidates))
                .map(resolved -> new CategoryAdvice(SuggestionSource.AI, resolved.categoryId(),
                        resolved.categoryName(), resolved.type(),
                        BigDecimal.valueOf(proposal.orElseThrow().confidence()), proposal.orElseThrow().reason()))
                .orElseGet(CategorySuggester::nothing);
    }

    private static Optional<SuggestionCandidate> resolve(CategorySuggestion suggestion,
                                                         List<SuggestionCandidate> candidates) {
        if (suggestion.categoryName() == null || suggestion.type() == null) {
            return Optional.empty();
        }
        String name = suggestion.categoryName().trim();
        String type = suggestion.type().trim();

        return candidates.stream()
                .filter(candidate -> candidate.categoryName().equalsIgnoreCase(name))
                .filter(candidate -> candidate.type().name().equalsIgnoreCase(type))
                .min(Comparator.comparing(SuggestionCandidate::isDefault));
    }

    private static BigDecimal confidenceOf(CategoryRuleRow rule) {
        return rule.confidence() == null ? RULE_CONFIDENCE : rule.confidence();
    }

    private static CategoryAdvice nothing() {
        return new CategoryAdvice(SuggestionSource.NONE, null, null, null, null, null);
    }
}
