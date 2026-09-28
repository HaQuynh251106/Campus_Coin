package com.campuscoin.categorisation.dto;

import com.campuscoin.categorisation.entity.RuleSource;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The keyword mapping this call left stored (UC-08 B6).")
public record LearnedCategoryRuleResponse(

        @Schema(description = "The description as the system stores it: trimmed and lower cased. The "
                + "student's own words, in the form the next match is compared against.",
                example = "campus cafe")
        String keyword,

        @Schema(description = "The category the mapping now points at - the category the record is "
                + "filed under.", example = "4")
        Long categoryId,

        @Schema(description = "That category's name.", example = "Food")
        String categoryName,

        @Schema(description = "`OVERRIDE` when the record sits in a different category from the one "
                + "suggested for it, so the filing corrected the system; `ACCEPTED` when the filing did "
                + "not contradict a suggestion - either it kept one, or none had been made.",
                example = "ACCEPTED")
        RuleSource source) {
}
