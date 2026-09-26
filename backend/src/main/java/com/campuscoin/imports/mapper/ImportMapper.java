package com.campuscoin.imports.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.imports.dto.ImportBatchResponse;
import com.campuscoin.imports.dto.ImportBatchListResponse;
import com.campuscoin.imports.dto.ImportRowResponse;
import com.campuscoin.imports.dto.ImportSummaryResponse;
import com.campuscoin.imports.entity.ImportBatchRow;
import com.campuscoin.imports.entity.ImportRow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns stored import rows into the responses UC-11 publishes.
 *
 * <p><b>This class is the single place that decides what leaves the server on this route, and that is
 * the load-bearing statement about the module.</b> Everything UC-11 reads is admitted or excluded here
 * and nowhere else: the batch's counters, its filename, and for each row its line number, its parsed
 * values, its stored raw line, its status and its reason. Nothing is copied through
 * {@code ImportBatchRow} or {@code ImportRow} that a client is then expected to ignore.
 *
 * <p><b>What is deliberately not published, and why each one is not.</b>
 *
 * <ul>
 *   <li><b>{@code file_hash}.</b> It exists so a re-upload of the same file is recognisable, nothing in
 *       this build consults it, and a checksum of the student's own file is a field no client can act
 *       on. {@code ImportBatchRow} does not carry it, so this class could not publish it - the
 *       exclusion is structural rather than a decision made here.</li>
 *   <li><b>{@code error_report}.</b> The same: it is NULL in every row this build writes, because the
 *       per-row report lives in {@code import_rows}, and a batch-level JSON document would be a second
 *       competing place to say what went wrong with row 12.</li>
 *   <li><b>{@code user_id}.</b> It is the caller's own and every read narrows on it, so publishing it
 *       would hand a client an identifier it already knows. {@code ImportRow} has no such field and
 *       could not have one - {@code import_rows} has no owner column at all.</li>
 * </ul>
 *
 * <p><b>{@code raw_data} is parsed rather than forwarded, and the failure is answered with a null.</b>
 * The column is {@code JSON}, so MySQL has already validated that the stored value is a document - a
 * malformed one cannot be there. It is parsed at all so the response carries an object rather than a
 * string containing an object: a client that received the text would have to parse it again to render
 * it, and a client that rendered it unparsed would show a student braces and escapes. The null branch
 * exists because the column is nullable - a row stored by a hand-run {@code INSERT} may have no raw
 * line - and because a JSON column can hold a well-formed document that is not an object, which would
 * be a value this API has no shape for. Neither is worth a {@code 500} on a screen whose purpose is
 * showing the student their own file.
 *
 * <p>{@code import_batches.error_report} is read nowhere, so the {@code ObjectMapper} is used for one
 * column; that is the same arrangement {@code InsightViewDao} has, and the class is the container's
 * bean rather than one this class builds.
 */
@Component
public class ImportMapper {

    private final ObjectMapper objectMapper;

    public ImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * UC-11 B5: the batch and its rows.
     *
     * <p>{@code modifiable} comes from the status rather than from a second decision: it is
     * {@code ImportBatchStatus#isOpen}, which is the same set {@code sp_apply_csv_batch} accepts. A
     * client told {@code true} can override a row and commit; one told {@code false} can only read.
     */
    public ImportBatchResponse toBatchResponse(ImportBatchRow batch, List<ImportRow> rows) {
        return new ImportBatchResponse(
                batch.batchId(),
                batch.originalFilename(),
                batch.status(),
                batch.status().isOpen(),
                batch.totalRows(),
                batch.validRows(),
                batch.errorRows(),
                batch.duplicateRows(),
                batch.importedRows(),
                batch.createdAt(),
                batch.committedAt(),
                rows.stream().map(this::toRowResponse).toList());
    }

    /** UC-11: the list of the caller's imports, with the limit the server applied. */
    public ImportBatchListResponse toListResponse(int limit, List<ImportBatchRow> batches) {
        return new ImportBatchListResponse(limit, toSummaries(batches));
    }

    /** UC-11: one batch's summary line. Counters only - see {@code ImportSummaryResponse}. */
    public List<ImportSummaryResponse> toSummaries(List<ImportBatchRow> batches) {
        return batches.stream().map(this::toSummary).toList();
    }

    /** UC-11: one previewed row, published on its own after a category override. */
    public ImportRowResponse toRowResponse(ImportRow row) {
        return new ImportRowResponse(
                row.rowId(),
                row.csvRowNo(),
                rawData(row.rawData()),
                row.parsedDate(),
                row.parsedAmount(),
                row.parsedType(),
                row.parsedDescription(),
                row.parsedCategoryName(),
                row.resolvedCategoryId(),
                row.aiSuggestedCategoryId(),
                row.rowStatus(),
                row.errorMessage(),
                row.transactionId());
    }

    private ImportSummaryResponse toSummary(ImportBatchRow batch) {
        return new ImportSummaryResponse(
                batch.batchId(),
                batch.originalFilename(),
                batch.status(),
                batch.status().isOpen(),
                batch.totalRows(),
                batch.validRows(),
                batch.errorRows(),
                batch.duplicateRows(),
                batch.importedRows(),
                batch.createdAt(),
                batch.committedAt());
    }

    /**
     * The stored raw line as an object, or null when it is absent or not an object.
     *
     * <p>The catch is broad on purpose. A value that is not a JSON object is not a fault in this
     * application's writing - {@code ImportRowReader} builds an object and escapes every cell - so a
     * non-object here came from somewhere else, and the screen's job is still to render the row's
     * parsed values. Answering it with a null leaves the student looking at their own amount and date;
     * answering it with a {@code 500} would take the whole preview away because one row's annotation
     * is unusual.
     */
    private Object rawData(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
