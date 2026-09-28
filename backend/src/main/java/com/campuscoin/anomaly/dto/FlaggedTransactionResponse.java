package com.campuscoin.anomaly.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One of the signed-in student's records that the anomaly check marked (UC-24).")
public record FlaggedTransactionResponse(

        @Schema(description = "The transaction's `id`. It is the identifier the transactions "
                + "endpoints use, so the record can be opened or corrected directly.", example = "31")
        Long transactionId,

        @Schema(description = "Identifier of the category the record is filed under (BR-05).",
                example = "4")
        Long categoryId,

        @Schema(description = "Name of that category. The detector's own note names it too, because "
                + "the note is read on its own where the flag is displayed.", example = "Food")
        String categoryName,

        @Schema(description = "`EXPENSE` or `INCOME`, read from the category rather than stored on "
                + "the record.", example = "EXPENSE")
        CategoryType categoryType,

        @Schema(description = "How much moved. Always positive; `categoryType` says which direction.",
                example = "95.00")
        BigDecimal amount,

        @Schema(description = "The date the transaction was recorded against.", example = "2026-09-24")
        LocalDate txnDate,

        @Schema(description = "The student's own description of the record, decrypted. Omitted when "
                + "there is none.", example = "Campus cafe - lunch with Linh", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "Always `true` on this endpoint - every entry was selected because it "
                + "is flagged. Published so the field set matches a transaction's own shape.",
                example = "true")
        Boolean isFlagged,

        @Schema(description = "Why the record is marked: `DUPLICATE` when another of your records "
                + "matches it, `UNUSUAL_AMOUNT` when it is several times your usual amount in that "
                + "category. Read-only; never accepted in a request.", example = "DUPLICATE")
        AnomalyFlagType flagType,

        @Schema(description = "Plain-language explanation of the mark, written by the detector, with "
                + "the figures it compared. Omitted when there is none.", nullable = true,
                example = "This looks like a record you already entered: the same amount in Food on "
                        + "2026-09-23. Check whether it was recorded twice.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String flagNote) {
}
