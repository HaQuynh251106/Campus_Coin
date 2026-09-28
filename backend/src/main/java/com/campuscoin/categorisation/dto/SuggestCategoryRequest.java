package com.campuscoin.categorisation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Names one of the caller's own transactions to be categorised (UC-08).")
public record SuggestCategoryRequest(

        @Schema(description = "The transaction's `id`. It must be one of the caller's own and not in "
                + "the trash; otherwise the answer is `404`, the same as for an id that does not exist.",
                example = "31", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Provide the id of the transaction to categorise.")
        Long transactionId) {
}
