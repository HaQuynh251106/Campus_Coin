package com.campuscoin.common.ai;

import java.util.Optional;

/**
 * The port with no provider behind it, and the default when none is configured.
 *
 * <p>Both methods return {@link Optional#empty()}, which every caller treats as an ordinary outcome
 * rather than a failure. The effect is that UC-08 and UC-17 work with no AI provider at all: UC-08
 * falls back to the student's own {@code category_rules} keyword match, and UC-17 keeps the
 * {@code RULE_BASED} summary {@code sp_generate_monthly_insight} already wrote. Nothing is
 * half-built and nothing is faked - the feature is present and deterministic, and configuring a
 * provider makes it richer.
 *
 * <p>It is deliberately not a mock that invents plausible output. A stand-in that guessed a category
 * would be indistinguishable in the database from a real suggestion: {@code
 * transactions.ai_suggested_category_id} would be populated either way, and the student would be
 * shown a confident-looking answer no provider produced. Empty is the honest answer, and the
 * {@code generated_by} column records {@code RULE_BASED} rather than {@code AI}.
 *
 * <p>Nothing here logs at info level. A deployment with no provider would otherwise write a line per
 * request saying so.
 */
public class NoopAiSuggestionPort implements AiSuggestionPort {

    @Override
    public Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request) {
        return Optional.empty();
    }

    @Override
    public Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request) {
        return Optional.empty();
    }

    @Override
    public boolean isExternalProvider() {
        return false;
    }
}
