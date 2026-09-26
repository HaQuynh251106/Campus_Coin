package com.campuscoin.categorisation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/ai/suggest-category} (UC-08): "what would you file this record under?"
 *
 * <p><b>One field, and no second.</b> There is deliberately no {@code description} and no
 * {@code categoryId}. The record already holds both - the student typed the description through module
 * 4 and filed the record under a category there - so a request that restated them would be offering the
 * client a way to disagree with the record it is asking about. Reading them from the row instead is
 * what makes every fact the learning is built on a fact the database already attested: the keyword is
 * the description the student actually saved, and the category the system compares it against is the
 * category the record is actually in. A client that could send either would be able to teach the system
 * a mapping nothing in the student's history supports.
 *
 * <p>There is no {@code userId} either, for the reason {@code RecordRecentActivityRequest} records:
 * ownership is the bearer token's subject and never something a client states about itself. The read
 * that loads the row narrows on {@code user_id}, so another student's record is answered with the same
 * {@code 404} a record that does not exist gets, and no response distinguishes the two.
 *
 * <p><b>{@code transactionId} is required</b> and is not defaulted. UC-08 B6 learns from a filing, so
 * the request is about a record that exists; a body without one would be asking for a suggestion about
 * a description that has not been filed, which is a different operation this build does not offer - see
 * {@code CategorisationController}.
 */
@Schema(description = "Names one of the caller's own transactions to be categorised (UC-08).")
public record SuggestCategoryRequest(

        @Schema(description = "The transaction's `id`. It must be one of the caller's own and not in "
                + "the trash; otherwise the answer is `404`, the same as for an id that does not exist.",
                example = "31", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Provide the id of the transaction to categorise.")
        Long transactionId) {
}
