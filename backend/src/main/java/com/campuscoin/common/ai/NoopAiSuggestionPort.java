package com.campuscoin.common.ai;

import java.util.Optional;

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
