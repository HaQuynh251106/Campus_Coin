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

/**
 * Produces the category UC-08 proposes for one description (UC-08).
 *
 * <p><b>The order is: the student's own rules, then the provider, then nothing.</b> The rules come
 * first for two reasons that point the same way.
 *
 * <ul>
 *   <li><b>A correction outranks a guess.</b> UC-08 B6 is "learn from edits". If the student has already
 *       told the system what "Campus Cafe" is, asking a model to guess again - and then showing the
 *       student something they have already corrected - would be failing at the one thing the use case
 *       names. The rule is the answer they gave.</li>
 *   <li><b>The provider is not consulted when the answer is already known.</b> Section 7 requires only
 *       what is necessary to leave the server, and a description the student has already filed is
 *       text that need not be sent anywhere at all. Consulting the provider last is also what makes the
 *       feature's cost scale with the student's <em>new</em> descriptions rather than with every keystroke
 *       they file.</li>
 * </ul>
 *
 * <p><b>Nothing the provider says is used as it arrives.</b> It proposes a name; this class resolves
 * that name against the categories the student may actually file under, discards the answer when the
 * name does not resolve, and only then is there anything to return. So a hallucinated category, a name
 * belonging to another student, and a name that does not exist all produce the same outcome - no
 * suggestion - and none of them can become a foreign key. That check is what
 * {@link CategorySuggestionRequest}'s "the service maps that name back to a category the student may
 * actually use" means, and it is the "backend validates the response" step of the required flow.
 *
 * <p><b>{@code ai.enabled} is not read here, deliberately.</b> The provider's own implementation
 * decides whether to call out, and returns empty when the setting is off - so the policy has one
 * definition instead of two, and this class cannot disagree with it. The same is true of
 * {@code ai.send_aggregates_only}: what a provider is willing to send is the provider's rule, and the
 * object built here carries a description and category names, never an amount, a date or an identifier.
 *
 * <p><b>A category that is no longer usable is not proposed.</b> Candidates are the caller's active
 * categories only, and a matched rule is checked against that same list - so a rule learned before its
 * category was retired does not resurrect it. Retiring a category is BR-07, and "you may not file new
 * records here" has to mean the system does not propose it either.
 *
 * <p>No repository and no transaction of its own: it takes rows and candidates that the calling service
 * has already read, which is what makes it unit-testable with plain lists and a stub port - the shape
 * {@code AnomalyDetector} has.
 */
@Component
public class CategorySuggester {

    /**
     * The confidence a suggestion carries when it comes from the student's own filing.
     *
     * <p>The certain value, and written rather than derived. A learned mapping is not a guess that has
     * been scored: it is what the student said, which is why {@link CategoryRuleRow} carries the
     * column's value and this class would report whatever it held. The value is stated here so the
     * comparison in {@code CategorisationService} - which skips a write that would change nothing - has
     * one place to read it from.
     */
    private static final BigDecimal RULE_CONFIDENCE = BigDecimal.valueOf(1).setScale(4);

    private final AiSuggestionPort aiSuggestionPort;
    private final CategoryRuleMatcher categoryRuleMatcher;

    public CategorySuggester(AiSuggestionPort aiSuggestionPort,
                             CategoryRuleMatcher categoryRuleMatcher) {
        this.aiSuggestionPort = aiSuggestionPort;
        this.categoryRuleMatcher = categoryRuleMatcher;
    }

    /**
     * UC-08: what the system proposes for this description.
     *
     * <p>Always answers, and the answer may be {@link SuggestionSource#NONE} - "we looked and have
     * nothing to propose" is a real result, not an error, and the caller needs it to be sayable. That
     * happens when the student has no rule for the description and either no provider is configured, or
     * the provider declined, or it named something that does not resolve.
     *
     * @param description what the student typed; may be blank, in which case nothing is proposed and no
     *                    provider is consulted
     * @param rules       the caller's own learned mappings
     * @param candidates  the categories the caller may file under, active ones only
     */
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

    /**
     * The category a provider's proposal refers to, or empty when it refers to none.
     *
     * <p>The name is compared case-insensitively, because a model asked for "Food" may answer "food"
     * and the difference is cosmetic; the type must agree as well, because BR-05 makes the category's
     * type the record's type and a proposal that names an income category for an expense description is
     * wrong in a way a name alone cannot catch. Both are {@code trim}med first, since leading or
     * trailing space in a generated string is noise rather than meaning.
     *
     * <p>A name that resolves to more than one category is resolved the way
     * {@code sp_apply_csv_batch} resolves it: the student's own row before the shared one. The rule is
     * not re-derived here - {@link SuggestionCandidate#isDefault} records that it is the same rule, and
     * {@code Comparator} applies it once.
     */
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

    /** The confidence a rule-based suggestion reports, from the rule the student taught. */
    private static BigDecimal confidenceOf(CategoryRuleRow rule) {
        return rule.confidence() == null ? RULE_CONFIDENCE : rule.confidence();
    }

    /** "We looked and have nothing to propose" - a member of the enum, not an absent answer. */
    private static CategoryAdvice nothing() {
        return new CategoryAdvice(SuggestionSource.NONE, null, null, null, null, null);
    }
}
