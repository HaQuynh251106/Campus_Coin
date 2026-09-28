package com.campuscoin.categorisation.dto;

import java.math.BigDecimal;

import com.campuscoin.categorisation.entity.SuggestionSource;
import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The category proposed for one of the signed-in student's records, and the "
        + "keyword rule this call taught if any (UC-08).")
public record CategorySuggestionResponse(

        @Schema(description = "The record this answer is about - the id the request named.",
                example = "31")
        Long transactionId,

        @Schema(description = "Where the proposal came from: `RULE` when it matches a mapping the "
                + "student taught the system, `AI` when the configured provider proposed it and the "
                + "answer resolved to a category the student may use, `NONE` when there is no "
                + "proposal.", example = "RULE")
        SuggestionSource source,

        @Schema(description = "The proposed category's `id`, one the student may file under. Omitted "
                + "when `source` is `NONE`.", example = "4", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long categoryId,

        @Schema(description = "The proposed category's name. Omitted when `source` is `NONE`.",
                example = "Food", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryName,

        @Schema(description = "The proposed category's type. Omitted when `source` is `NONE`.",
                example = "EXPENSE", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        CategoryType type,

        @Schema(description = "How sure the source says it is, between 0 and 1. A mapping the student "
                + "taught carries the certain value. Advisory only - it is stored and shown, and never "
                + "decides anything. Omitted when `source` is `NONE`.", example = "1.0000", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal confidence,

        @Schema(description = "A short phrase explaining an `AI` proposal, in the provider's own words. "
                + "Never present for a `RULE` proposal, whose reason is the source itself, and never "
                + "presented as advice (BR-13). Omitted when there is none.", nullable = true,
                example = "looks like a cafe purchase")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reason,

        @Schema(description = "The keyword rule this call created or updated, if it taught one.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LearnedCategoryRuleResponse learned) {
}
