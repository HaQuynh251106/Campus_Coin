package com.campuscoin.imports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRowStatus;

import io.swagger.v3.oas.annotations.media.Schema;

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
