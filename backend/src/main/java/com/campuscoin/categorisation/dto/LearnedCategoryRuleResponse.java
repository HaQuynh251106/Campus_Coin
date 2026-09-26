package com.campuscoin.categorisation.dto;

import com.campuscoin.categorisation.entity.RuleSource;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The keyword mapping this call left stored (UC-08 B6).
 *
 * <p><b>Published because the learning is otherwise invisible.</b> UC-08 B6 is "learn from corrections",
 * and the correction is a write the student never explicitly asked for - they filed a record, which they
 * have done many times, and a mapping was created or updated as a side effect. A response that reported
 * only the suggestion would leave the client unable to say "we will remember that", and would leave the
 * one behaviour that distinguishes this endpoint from a lookup genuinely unobservable through the API.
 * The module's manual test would otherwise have to read the database to confirm it happened.
 *
 * <p>{@code keyword} is the description as the system stores it - trimmed and lower cased - and is
 * published because it is the thing the next match is compared against. It carries nothing the student
 * did not type themselves, so there is no disclosure: it is their own words, handed back in the form
 * the system keeps them. {@code categoryId} and {@code categoryName} name the category the mapping now
 * points at, which is the category the record is filed under - again the student's own choice.
 *
 * <p><b>This is reported on every call that had a description to learn from, and that is deliberate.</b>
 * The mapping is written on each filing, so a student filing the same merchant a hundred times leaves
 * the same row a hundred times rather than a hundred rows - {@code uk_rule_user_keyword} makes that so.
 * Reporting the current state of the mapping rather than a diff is what lets {@code source} stay
 * truthful: a student who overrode a suggestion once and later kept it leaves the row marked
 * {@code ACCEPTED}, because the latest filing did not contradict anything, and a response that only
 * announced changes could not say so.
 *
 * <p>Omitted entirely when there was nothing to learn - see {@code CategorySuggestionResponse#learned}.
 */
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
