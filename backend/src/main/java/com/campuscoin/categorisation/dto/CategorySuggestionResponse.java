package com.campuscoin.categorisation.dto;

import java.math.BigDecimal;

import com.campuscoin.categorisation.entity.SuggestionSource;
import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the system proposes for one of the student's records, and what it learned from them (UC-08).
 *
 * <p><b>{@code source} is the honest part of this response, and {@code NONE} is a real answer.</b> BR-13
 * requires every proposal to be presented as a suggestion the student may override, and a client cannot
 * present one honestly without knowing where it came from: "we remember how you filed this before" and
 * "the AI service thinks this looks like Food" are different claims, and a UI that showed the second
 * when it meant the first would be attributing a conclusion to a system that reached it by looking up
 * the student's own table. {@code NONE} - there is no rule and no provider had anything to say - is a
 * member rather than an error, because "we looked and have nothing to propose" has to be sayable.
 *
 * <p><b>The category fields are omitted together when there is no proposal.</b> A suggestion is either
 * wholly present - an id, a name, a type, a confidence - or wholly absent, and emitting an id with no
 * name or a confidence with no category would be describing a suggestion that does not exist. They
 * follow the project-wide {@code @JsonInclude(NON_NULL)} convention, so an absent field and a null one
 * look the same to a client.
 *
 * <p><b>{@code confidence} is advisory and gates nothing.</b> It is published because UC-08 asks for the
 * suggestion to be shown with a sense of how sure the system is, and it is stored beside the record as
 * {@code transactions.ai_confidence}. No branch anywhere in this module reads it back: whether the
 * student overrode the suggestion is decided by comparing the suggested category with the filed one,
 * not by how confident anything claimed to be.
 *
 * <p><b>Nothing here is applied.</b> The response proposes; the record keeps whatever category the
 * student filed it under, and nothing about the money moving changes because this endpoint was called.
 * That is BR-13, and it is why the endpoint can safely write to the record at all.
 *
 * <p>{@code learned} is omitted when this call taught nothing - a record with no description, or one
 * whose category was already the mapping stored for it.
 */
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
