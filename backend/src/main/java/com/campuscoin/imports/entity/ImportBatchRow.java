package com.campuscoin.imports.entity;

import java.time.LocalDateTime;

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
