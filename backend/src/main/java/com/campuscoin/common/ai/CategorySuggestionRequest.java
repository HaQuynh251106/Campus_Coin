package com.campuscoin.common.ai;

import java.util.List;

public record CategorySuggestionRequest(String description, List<Candidate> categoryNames) {

    public record Candidate(String name, String type) {
    }
}
