package com.campuscoin.imports.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.campuscoin.imports.entity.ImportBatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A CSV import, its counters and its rows (UC-11).")
public record ImportBatchResponse(

        @Schema(description = "Identifier of this batch.", example = "12")
        Long id,

        @Schema(description = "The file's name as the student supplied it.", example = "expenses.csv")
        String originalFilename,

        @Schema(description = "Where the batch has got to: `UPLOADED`, `PREVIEWED`, `COMMITTED`, "
                + "`CANCELLED` or `FAILED`.", example = "PREVIEWED")
        ImportBatchStatus status,

        @Schema(description = "Whether the rows can still be changed and the batch committed. True "
                + "for `UPLOADED` and `PREVIEWED`; false once the batch is settled.",
                example = "true")
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
                example = "0")
        int importedRows,

        @Schema(description = "When the file was uploaded.", example = "2026-09-26T09:14:03")
        LocalDateTime createdAt,

        @Schema(description = "When the batch was committed. Null until then.", nullable = true,
                example = "2026-09-26T09:16:41")
        LocalDateTime committedAt,

        @Schema(description = "The rows, in file order.")
        List<ImportRowResponse> rows) {
}
