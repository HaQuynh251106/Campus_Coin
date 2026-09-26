package com.campuscoin.imports.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.campuscoin.imports.entity.ImportBatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One import: the batch and its rows (UC-11 B5).
 *
 * <p><b>The rows are nested rather than served by a second endpoint.</b> UC-11's preview screen is one
 * screen - the file's rows and what will happen to each - so a client that had to make two calls would
 * render a batch it could not yet describe, and the counters and the rows could be read from two
 * different moments. A committed batch's rows are returned by the same shape, which is what makes
 * "what did that import do?" answerable from the same URL as "what will it do?".
 *
 * <p><b>A batch's rows are bounded by the upload, and that is what makes the nesting defensible.</b>
 * {@code ImportService} refuses a file with more rows than it will accept, so this list is a few
 * hundred entries at most and never an unbounded page - the same reasoning {@code ImportViewDao} gives
 * for not paging the row read. A batch whose rows could grow without bound would need the rows on their
 * own endpoint.
 *
 * <p><b>{@code modifiable} is published so a client can hide the controls, and it is derived from the
 * status rather than being a second fact.</b> It says whether the rows can still be changed or the
 * batch committed - which is exactly what {@code ImportBatchStatus#isOpen} answers, and the same set
 * {@code sp_apply_csv_batch} accepts. Stating it in the response means the client does not have to
 * reimplement the rule from the status enum, and there is nowhere for the two to disagree because this
 * field is computed from that method.
 *
 * <p><b>The five counters are the row's own columns and are never recomputed here.</b> They are written
 * twice by two owners - see {@code ImportBatchRow} - so a previewed batch's numbers describe an
 * intention and a committed batch's describe what happened. Recomputing either from the nested rows
 * would produce a number that disagreed with the table for at least one of the two states, and the
 * committed case is the one where the difference matters: the procedure's own walk is the record of
 * what it did.
 */
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
