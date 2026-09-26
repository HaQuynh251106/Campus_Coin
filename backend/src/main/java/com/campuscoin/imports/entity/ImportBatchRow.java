package com.campuscoin.imports.entity;

import java.time.LocalDateTime;

/**
 * One CSV import batch as UC-11 reads it (UC-11).
 *
 * <p><b>{@code userId} is deliberately not carried.</b> Every query narrows on it, so it is the same
 * value on every row of an answer and a client has no use for it - the reasoning {@code InsightRow},
 * {@code FlaggedTransactionRow} and {@code RecentActivityRow} all record.
 *
 * <p><b>The five counters are published and are written twice by two different owners, which is worth
 * stating because a reader will otherwise assume one of them is wrong.</b>
 *
 * <ul>
 *   <li>At <em>preview</em> this application writes them: {@code total_rows} is every row in the file,
 *       and {@code valid_rows}, {@code error_rows} and {@code duplicate_rows} are the three verdicts
 *       the preview reached. {@code imported_rows} stays zero, because nothing has been imported.</li>
 *   <li>At <em>commit</em> {@code sp_apply_csv_batch} overwrites all five from its own walk:
 *       {@code valid_rows} becomes the number it actually generated a transaction for,
 *       {@code error_rows} the number it refused, and {@code duplicate_rows} everything else - which
 *       is {@code total - imported - errors}, and is why a duplicate is recorded with the member the
 *       procedure counts rather than left in a provisional state.</li>
 * </ul>
 *
 * <p>So a previewed batch's counters describe an intention and a committed batch's describe what
 * happened. Both are the row's own column values at the moment of the read, and the response never
 * recomputes either of them.
 *
 * <p><b>{@code fileHash} is deliberately not carried.</b> It exists so a re-upload of the same file is
 * recognisable, and nothing in this build consults it; publishing a checksum of the student's own file
 * would be a field no client has a use for - the reason {@code ImportRowRow} does not carry the batch
 * id either.
 *
 * <p>{@code errorReport} is not carried for a stronger reason: it is NULL in every row this build
 * writes. The report is per-row and lives in {@code import_rows}, so a batch-level JSON document would
 * be a second, competing place to say what went wrong with row 12.
 */
public record ImportBatchRow(
        Long batchId,
        String originalFilename,
        ImportBatchStatus status,
        int totalRows,
        int validRows,
        int errorRows,
        int duplicateRows,
        int importedRows,
        LocalDateTime createdAt,
        LocalDateTime committedAt) {
}
