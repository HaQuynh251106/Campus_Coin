package com.campuscoin.imports.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.jdbc.JdbcValues;
import com.campuscoin.imports.entity.ImportBatchRow;
import com.campuscoin.imports.entity.ImportBatchStatus;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.entity.ImportRowStatus;
import com.campuscoin.imports.service.ImportDuplicateDetector;

@Repository
public class ImportViewDao {

    private static final String SELECT_BATCHES = """
            SELECT b.id              AS batchId,
                   b.original_filename AS originalFilename,
                   b.status          AS status,
                   b.total_rows      AS totalRows,
                   b.valid_rows      AS validRows,
                   b.error_rows      AS errorRows,
                   b.duplicate_rows  AS duplicateRows,
                   b.imported_rows   AS importedRows,
                   b.created_at      AS createdAt,
                   b.committed_at    AS committedAt
              FROM import_batches b
             WHERE b.user_id = :userId
             ORDER BY b.created_at DESC, b.id DESC
             LIMIT :limit
            """;

    private static final String SELECT_ONE_BATCH = """
            SELECT b.id              AS batchId,
                   b.original_filename AS originalFilename,
                   b.status          AS status,
                   b.total_rows      AS totalRows,
                   b.valid_rows      AS validRows,
                   b.error_rows      AS errorRows,
                   b.duplicate_rows  AS duplicateRows,
                   b.imported_rows   AS importedRows,
                   b.created_at      AS createdAt,
                   b.committed_at    AS committedAt
              FROM import_batches b
             WHERE b.user_id = :userId
               AND b.id = :batchId
            """;

    private static final String SELECT_ROWS = """
            SELECT r.id                       AS rowId,
                   r.csv_row_no               AS csvRowNo,
                   r.raw_data                 AS rawData,
                   r.parsed_date              AS parsedDate,
                   r.parsed_amount            AS parsedAmount,
                   r.parsed_type              AS parsedType,
                   r.parsed_description       AS parsedDescription,
                   r.parsed_category_name     AS parsedCategoryName,
                   r.resolved_category_id     AS resolvedCategoryId,
                   r.ai_suggested_category_id AS aiSuggestedCategoryId,
                   r.row_status               AS rowStatus,
                   r.error_message            AS errorMessage,
                   r.transaction_id           AS transactionId
              FROM import_rows r
              JOIN import_batches b ON b.id = r.batch_id
             WHERE b.user_id = :userId
               AND r.batch_id = :batchId
             ORDER BY r.csv_row_no
            """;

    private static final String SELECT_ONE_ROW = """
            SELECT r.id                       AS rowId,
                   r.csv_row_no               AS csvRowNo,
                   r.raw_data                 AS rawData,
                   r.parsed_date              AS parsedDate,
                   r.parsed_amount            AS parsedAmount,
                   r.parsed_type              AS parsedType,
                   r.parsed_description       AS parsedDescription,
                   r.parsed_category_name     AS parsedCategoryName,
                   r.resolved_category_id     AS resolvedCategoryId,
                   r.ai_suggested_category_id AS aiSuggestedCategoryId,
                   r.row_status               AS rowStatus,
                   r.error_message            AS errorMessage,
                   r.transaction_id           AS transactionId
              FROM import_rows r
              JOIN import_batches b ON b.id = r.batch_id
             WHERE b.user_id = :userId
               AND r.batch_id = :batchId
               AND r.id = :rowId
            """;

    private static final String SELECT_EXISTING_RECORDS = """
            SELECT t.id          AS transactionId,
                   t.category_id AS categoryId,
                   t.amount      AS amount,
                   t.txn_date    AS txnDate
              FROM transactions t
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
             ORDER BY t.id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<ImportBatchRow> findBatches(Long userId, int limit) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BATCHES, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(ImportViewDao::toBatchRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ImportBatchRow> findBatch(Long userId, Long batchId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE_BATCH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("batchId", batchId)
                .getResultList();

        return rows.stream().map(ImportViewDao::toBatchRow).findFirst();
    }

    @Transactional(readOnly = true)
    public List<ImportRow> findRows(Long userId, Long batchId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ROWS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("batchId", batchId)
                .getResultList();

        return rows.stream().map(ImportViewDao::toRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ImportRow> findRow(Long userId, Long batchId, Long rowId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE_ROW, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("batchId", batchId)
                .setParameter("rowId", rowId)
                .getResultList();

        return rows.stream().map(ImportViewDao::toRow).findFirst();
    }

    @Transactional(readOnly = true)
    public List<ImportDuplicateDetector.Candidate> findExistingRecords(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_EXISTING_RECORDS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        List<ImportDuplicateDetector.Candidate> existing = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            existing.add(new ImportDuplicateDetector.Candidate(
                    0,
                    ((Number) row.get("categoryId")).longValue(),
                    row.get("amount", BigDecimal.class),
                    row.get("txnDate", Date.class).toLocalDate()));
        }
        return List.copyOf(existing);
    }

    private static ImportBatchRow toBatchRow(Tuple row) {
        return new ImportBatchRow(
                ((Number) row.get("batchId")).longValue(),
                row.get("originalFilename", String.class),
                ImportBatchStatus.valueOf(row.get("status", String.class)),
                intOf(row.get("totalRows")),
                intOf(row.get("validRows")),
                intOf(row.get("errorRows")),
                intOf(row.get("duplicateRows")),
                intOf(row.get("importedRows")),
                JdbcValues.toLocalDateTime(row.get("createdAt", Timestamp.class)),
                JdbcValues.toLocalDateTime(row.get("committedAt", Timestamp.class)));
    }

    private static ImportRow toRow(Tuple row) {
        String parsedType = row.get("parsedType", String.class);

        return new ImportRow(
                ((Number) row.get("rowId")).longValue(),
                intOf(row.get("csvRowNo")),
                row.get("rawData", String.class),
                row.get("parsedDate", Date.class) == null
                        ? null : row.get("parsedDate", Date.class).toLocalDate(),
                row.get("parsedAmount", BigDecimal.class),
                parsedType == null ? null : CategoryType.valueOf(parsedType),
                row.get("parsedDescription", String.class),
                row.get("parsedCategoryName", String.class),
                longOrNull(row.get("resolvedCategoryId")),
                longOrNull(row.get("aiSuggestedCategoryId")),
                ImportRowStatus.valueOf(row.get("rowStatus", String.class)),
                row.get("errorMessage", String.class),
                longOrNull(row.get("transactionId")));
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
