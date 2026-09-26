package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

/**
 * A category the system proposes for one description, resolved to a category the student may file
 * under (UC-08).
 *
 * <p><b>This is the only shape a suggestion takes, and it is advisory by construction.</b> It carries
 * a category id rather than a category name, because resolving a name to an id against the categories
 * the student may actually use is the step that makes an answer from an external provider safe - and
 * once that step has run, the unresolved name has no further use. BR-13 requires the result to be shown
 * as something the student reviews: this record is stored beside a transaction as
 * {@code ai_suggested_category_id} and {@code ai_confidence}, and it is never written as the
 * transaction's category.
 *
 * <p><b>{@code confidence} is advisory and gates nothing.</b> It is published to the client and stored
 * on the record, and no branch anywhere in this module reads it - the identity of the suggested
 * category is what decides whether the student overrode it, not how sure the system said it was.
 *
 * <p><b>{@code reason} is prose for the student and only for the latter.</b> It is non-null only for an
 * {@link SuggestionSource#AI} suggestion, because a provider supplies one and a rule match has nothing
 * to say beyond "you filed this under that category before" - which the source already states. It is
 * never presented as advice (BR-13).
 */
public record CategoryAdvice(
        SuggestionSource source,
        Long categoryId,
        String categoryName,
        CategoryType type,
        BigDecimal confidence,
        String reason) {
}
