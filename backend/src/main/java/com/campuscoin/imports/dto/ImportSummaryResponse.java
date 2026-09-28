package com.campuscoin.imports.dto;

import java.time.LocalDateTime;

import com.campuscoin.imports.entity.ImportBatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One CSV import in the student's list (UC-11).")
public record ImportSummaryResponse(

        @Schema(description = "Identifier of this batch, to be passed to the detail endpoint.",
                example = "12")
        Long id,

        @Schema(description = "The file's name as the student supplied it.", example = "expenses.csv")
        String originalFilename,

        @Schema(description = "Where the batch has got to.", example = "COMMITTED")
        ImportBatchStatus status,

        @Schema(description = "Whether the rows can still be changed and the batch committed.",
                example = "false")
        boolean modifiable,

        @Schema(description = "Every row of the file.", example = "180")
        int totalRows,

        @Schema(description = "Before a commit, the rows that will be imported. After one, the rows "
                + "that were.", example = "175")
        int validRows,

        @Schema(description = "Rows that could not be imported.", example = "3")
        int errorRows,

        @Schema(description = "Rows the student appears to have recorded already.", example = "2")
        int duplicateRows,

        @Schema(description = "Rows that became transactions. Zero until the batch is committed.",
                example = "175")
        int importedRows,

        @Schema(description = "When the file was uploaded.", example = "2026-09-26T09:14:03")
        LocalDateTime createdAt,

        @Schema(description = "When the batch was committed. Null until then.", nullable = true,
                example = "2026-09-26T09:16:41")
        LocalDateTime committedAt) {
}
