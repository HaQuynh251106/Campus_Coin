package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/transactions} (UC-07): record one income or expense.
 *
 * <p><b>There is no {@code type} field, and that is the central decision of this module.</b>
 * {@code transactions} has no type column, because BR-05 requires a record's type to match its
 * category's and the only way to guarantee that is to keep one source of truth. The category is
 * chosen here, and {@code categories.type} answers the question. A client that sends a {@code type}
 * still gets the category's answer - the field is simply ignored - which is why the Angular model's
 * {@code type} is documented in {@code docs/api/transactions.md} as derived, not sent.
 *
 * <p><b>Deliberately absent, all of them writable only by the module that owns them:</b>
 *
 * <ul>
 *   <li>{@code userId} - the owner is the account in the bearer token. A client-supplied owner
 *       would be a direct BR-02 bypass.</li>
 *   <li>{@code source} - provenance. A client able to claim {@code RECURRING} would be able to
 *       record a future-dated transaction, because that is the one source
 *       {@code sp_validate_transaction} exempts from the BR-08 date check. Every row this endpoint
 *       creates is {@code MANUAL}.</li>
 *   <li>{@code isDeleted} / {@code deletedAt} - UC-10's soft delete owns these, and they move
 *       through {@code sp_soft_delete_transaction}, never through a request body.</li>
 *   <li>{@code aiSuggestedCategoryId}, {@code aiConfidence}, {@code aiOverridden} - UC-08,
 *       module 12.</li>
 *   <li>{@code isFlagged}, {@code flagType}, {@code flagNote} - UC-24's duplicate and unusual-amount
 *       detection, module 12. A client able to set {@code flagType} could mark its own record as
 *       reviewed, which is exactly the signal the feature exists to raise.</li>
 *   <li>{@code recurringRuleId}, {@code importBatchId} - UC-09 and UC-11. {@code
 *       sp_validate_transaction} checks that either one points at a row of the same student, so
 *       accepting them here would need an ownership lookup this module has no other use for;
 *       leaving them out means the correctness question cannot arise.</li>
 * </ul>
 *
 * <p>Sending any of them changes nothing, which is asserted by a test.
 *
 * <p>The text limit is applied to the value <em>after trimming</em>, because the service trims
 * before storing: a plain {@code @Size} would measure the raw input and could reject padding that
 * lands inside the column, or accept a value that trims outside it.
 */
@Schema(description = "A transaction to record (UC-07).")
public record CreateTransactionRequest(

        @Schema(description = "The category to file this under. It decides whether the record is "
                + "income or expense (BR-05), and must be one of your own categories or a shared "
                + "default one.", example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId,

        @Schema(description = "How much moved. Strictly greater than zero, at most two decimal "
                + "places.", example = "12.50")
        @NotNull(message = "Amount is required.")
        // ck_txn_amount requires > 0, so 0 is refused here rather than by the database - the
        // caller gets a field error instead of a generic conflict.
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")
        // amount DECIMAL(15,2): thirteen digits before the point and two after. Checking the shape
        // here means an over-large value is a field error rather than a refused write.
        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "The date the money moved. Cannot be in the future (BR-08).",
                example = "2026-09-24")
        @NotNull(message = "Date is required.")
        LocalDate txnDate,

        @Schema(description = "Optional note. An empty string stores no description.",
                example = "Lunch with the study group", nullable = true)
        // (?s) so a multi-line note is accepted, matching what the message says and what the
        // VARCHAR(255) column can hold. See the same pattern in the category module.
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description) {
}
