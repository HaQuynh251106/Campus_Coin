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

@Component
public class ImportMapper {

    private final ObjectMapper objectMapper;

    public ImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

    public ImportBatchListResponse toListResponse(int limit, List<ImportBatchRow> batches) {
        return new ImportBatchListResponse(limit, toSummaries(batches));
    }

    public List<ImportSummaryResponse> toSummaries(List<ImportBatchRow> batches) {
        return batches.stream().map(this::toSummary).toList();
    }

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
