package com.campuscoin.anomaly.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One record the detector marked, as the client sees it (UC-24).
 *
 * <p><b>Enough to render the row and to explain the mark, and no more.</b> UC-24's postcondition is
 * that a student can see that something looks wrong and do something about it, so an entry carries the
 * record's own details - the category, the amount, the date, the student's description - beside the
 * flag. A client handed only a transaction id would fetch each row back one at a time, which is the
 * request this response exists to answer; a client handed only the flag would have nothing to decide
 * with.
 *
 * <p><b>{@code flagType} is published and is never accepted back.</b> A client has to render why the
 * row is marked, and the distinction between "this looks like a duplicate" and "this is large for
 * this category" is what the student acts on. It appears in no request type in this module: the type
 * is the detector's conclusion, and a client able to state it could mark its own record as reviewed,
 * which is exactly the signal this feature exists to raise ({@code docs/api/transactions.md}).
 *
 * <p><b>{@code isFlagged} is always true in this response, and it is published anyway.</b> Every
 * entry here passed {@code WHERE t.is_flagged = 1}, so the field carries no information the endpoint's
 * existence does not already give. It is included because the field set is then exactly
 * {@code TransactionResponse}'s - a client that renders a flagged record can reuse the mapper it
 * already has for a transaction, rather than branching on a shape difference that is not a real one -
 * and because it means the response says outright what it is showing rather than implying it.
 *
 * <p><b>{@code flagNote} is omitted when there is none.</b> The detector always writes one for a
 * finding, so in practice a flagged entry carries it; the field is nullable because the column is, and
 * a hand-run {@code UPDATE} could have set a type without a note. Following the convention
 * {@code TransactionResponse} and {@code BookmarkResponse} set, an absent note and a null one look the
 * same to the client.
 *
 * <p><b>{@code description} is decrypted before it reaches this type.</b> As stored, the column is an
 * AES-256-GCM envelope; {@code AnomalyMapper} reads it back with the tolerant {@code decryptStored},
 * so a row written before encryption was enabled still reads rather than failing the whole list. This
 * record only ever holds plaintext.
 *
 * <p>Absent, deliberately: {@code userId}, for the usual reason - the query already applied ownership
 * - and {@code aiSuggestedCategoryId} and the two origin links, which belong to UC-08, UC-09 and UC-11
 * and are not this endpoint's subject even where they are populated.
 */
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
