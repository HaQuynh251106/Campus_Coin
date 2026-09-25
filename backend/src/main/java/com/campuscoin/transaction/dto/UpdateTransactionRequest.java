package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PATCH /api/v1/transactions/{id}} (UC-10): change some fields of one of the
 * student's own transactions.
 *
 * <p>Every field is optional and a field left out is not changed, so a client sends only what it
 * edited. An explicit {@code null} means the same thing. That leaves {@code description} - the one
 * nullable column UC-10 edits - needing a way to be cleared, and it uses the empty string for it,
 * matching the convention the profile and category modules already established. {@code categoryId},
 * {@code amount} and {@code txnDate} have no empty state, so a blank there is a validation error
 * rather than a silent no-op.
 *
 * <p>{@code type} is absent for the same reason it is absent from create: it is not a column.
 * Changing the category is how a record changes type (BR-05), and that is exactly what
 * {@code categoryId} does - which is why the field is here at all rather than being fixed at
 * creation. Moving an expense to an income category is a legitimate correction of a misfiled
 * record.
 *
 * <p>Also absent, as on create: {@code userId}, {@code source}, {@code isDeleted},
 * {@code deletedAt}, the AI columns, the anomaly flags and the two origin links. In particular
 * there is no way to un-delete through this endpoint - that is {@code POST .../restore}, which goes
 * through {@code sp_restore_transaction} and writes the {@code RESTORE} row BR-09 requires. Allowing
 * a {@code PATCH} to clear {@code isDeleted} would change the state without the log entry, which is
 * the one thing the soft-delete design exists to prevent.
 */
@Schema(description = "The transaction fields to change. Omit a field to leave it as it is (UC-10).")
public record UpdateTransactionRequest(

        @Schema(description = "Move the record to another category, which also changes whether it "
                + "counts as income or expense (BR-05).", example = "2", nullable = true)
        Long categoryId,

        @Schema(description = "New amount. Strictly greater than zero.", example = "15.00",
                nullable = true)
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")
        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "New date. Cannot be in the future (BR-08).", example = "2026-09-20",
                nullable = true)
        LocalDate txnDate,

        @Schema(description = "Note. Send an empty string to clear it.",
                example = "Lunch with the study group", nullable = true)
        // (?s) so a multi-line note is accepted; "" passes the optional group and is how the field
        // is cleared.
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description) {
}
