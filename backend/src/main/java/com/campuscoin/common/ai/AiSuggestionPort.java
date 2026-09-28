package com.campuscoin.common.ai;

import java.util.Optional;

public interface AiSuggestionPort {

    Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request);

    Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request);

    boolean isExternalProvider();
}
