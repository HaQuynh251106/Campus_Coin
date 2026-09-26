package com.campuscoin.imports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRowStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of a previewed or committed batch (UC-11 B5).
 *
 * <p><b>Both the parsed values and the stored raw line are published, and the pair is the point.</b>
 * The preview's job is to show the student what the importer understood beside what the file actually
 * said, so they can tell "the importer read my amount wrong" apart from "my file has a wrong amount".
 * {@code rawData} is the line as a JSON object and the {@code parsed*} fields are its interpretation;
 * neither is derived from the other at read time, so the two cannot agree by construction and the
 * student is genuinely seeing two things.
 *
 * <p><b>{@code aiSuggestedCategoryId} is published and {@code resolvedCategoryId} is published
 * separately, and they are different questions.</b> The suggestion is UC-08's advisory note about
 * where this row would go (BR-13: advisory, never authoritative). The resolution is what the row will
 * actually be filed under - the student's own choice when they made one, and otherwise left for the
 * commit procedure to decide from the file's category name. So a row can carry a suggestion the
 * student overrode, and a client showing only one of the two would either hide the override or imply
 * the import had chosen a category it has not chosen yet.
 *
 * <p><b>{@code resolvedCategoryId} is null on a row the student has not overridden, even when the file
 * named a category that exists.</b> That is not the preview being unhelpful: the name is resolved at
 * the commit, by {@code sp_apply_csv_batch}, and resolving it here as well would be a second answer to
 * a question the procedure owns. What the student is shown is {@code parsedCategoryName} - what the
 * file said - and the resolution appears only once it is a real decision.
 *
 * <p><b>{@code errorMessage} carries the explanation for a {@code DUPLICATE} row as well as for an
 * {@code ERROR} one.</b> A duplicate is not an error in the row, but it is a row that will not be
 * imported, and UC-11 requires the student to be able to identify why. The two are told apart by
 * {@code rowStatus}, so a client can render them differently - a fixable row versus one they may
 * simply already have.
 *
 * <p>{@code transactionId} is null until the batch is committed, and then carries the record the row
 * became, so a client can link a preview line to the transaction it produced.
 */
@Schema(description = "One row of a CSV import, as the preview reached it (UC-11).")
public record ImportRowResponse(

        @Schema(description = "Identifier of this row, used to override its category.",
                example = "412")
        Long id,

        @Schema(description = "The row's line number in the file, counting the header as line 1. "
                + "This is the number the student sees in their spreadsheet.", example = "7")
        int csvRowNo,

        @Schema(description = "The line as it arrived, as a JSON object keyed by column name. "
                + "Published so the preview can show the file's own text beside the values the "
                + "importer read from it.")
        Object rawData,

        @Schema(description = "The date the importer read. Null when the row's date could not be "
                + "read.", example = "2026-09-24", nullable = true)
        LocalDate parsedDate,

        @Schema(description = "The amount the importer read. Null when it could not be read.",
                example = "12.50", nullable = true)
        BigDecimal parsedAmount,

        @Schema(description = "Whether the row is income or expense. Null when it could not be "
                + "read.", nullable = true)
        CategoryType parsedType,

        @Schema(description = "The description from the file.", nullable = true,
                example = "Lunch with the study group")
        String parsedDescription,

        @Schema(description = "The category name from the file, which the commit resolves when the "
                + "student has not chosen one.", nullable = true, example = "Food")
        String parsedCategoryName,

        @Schema(description = "The category the student chose for this row, if they chose one. When "
                + "set, the commit uses it as-is and never re-derives the category from the file. "
                + "Null when the row will be resolved from `parsedCategoryName` instead.",
                nullable = true)
        Long resolvedCategoryId,

        @Schema(description = "The category the system suggests for this row, from the student's own "
                + "learned rules (UC-08). Advisory only - filing a record here is the student's "
                + "decision, never this field's. Null when nothing was suggested.", nullable = true)
        Long aiSuggestedCategoryId,

        @Schema(description = "What will happen to this row: `VALID` (it will be imported), `ERROR` "
                + "(it cannot be), `DUPLICATE` (the student appears to have it already), `IMPORTED` "
                + "(it has been), or `SKIPPED`.",
                example = "VALID")
        ImportRowStatus rowStatus,

        @Schema(description = "Why this row will not be imported, in the student's terms. Present "
                + "for an `ERROR` row and for a `DUPLICATE` one; null otherwise.", nullable = true,
                example = "Amount must be a positive number, with at most 2 decimal places.")
        String errorMessage,

        @Schema(description = "The transaction this row became. Null until the batch is committed.",
                nullable = true)
        Long transactionId) {
}
