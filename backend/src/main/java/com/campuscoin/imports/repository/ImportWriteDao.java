package com.campuscoin.imports.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.imports.entity.ImportBatchStatus;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;

@Repository
public class ImportWriteDao {

    private static final String INSERT_BATCH = """
            INSERT INTO import_batches
              (user_id, original_filename, file_hash, status,
               total_rows, valid_rows, error_rows, duplicate_rows, imported_rows)
            VALUES
              (:userId, :filename, :fileHash, :status,
               :totalRows, :validRows, :errorRows, :duplicateRows, 0)
            """;

    private static final String SELECT_LAST_INSERT_ID = "SELECT LAST_INSERT_ID()";

    private static final String INSERT_ROW = """
            INSERT INTO import_rows
              (batch_id, csv_row_no, raw_data, parsed_date, parsed_amount, parsed_type,
               parsed_description, parsed_category_name, ai_suggested_category_id,
               row_status, error_message)
            VALUES
              (:batchId, :csvRowNo, NULLIF(:rawData, ''), NULLIF(:parsedDate, ''),
               NULLIF(:parsedAmount, ''), NULLIF(:parsedType, ''), NULLIF(:parsedDescription, ''),
               NULLIF(:parsedCategoryName, ''), NULLIF(:aiSuggestedCategoryId, ''), :rowStatus,
               NULLIF(:errorMessage, ''))
            """;

    private static final String UPDATE_ROW_CATEGORY = """
            UPDATE import_rows r
              JOIN import_batches b ON b.id = r.batch_id
               SET r.resolved_category_id = :categoryId,
                   r.error_message = NULL
             WHERE r.id = :rowId
               AND r.batch_id = :batchId
               AND b.user_id = :userId
               AND b.status IN ('UPLOADED', 'PREVIEWED')
            """;

    private static final String CANCEL_BATCH = """
            UPDATE import_batches
               SET status = 'CANCELLED'
             WHERE id = :batchId
               AND user_id = :userId
               AND status IN ('UPLOADED', 'PREVIEWED')
            """;

    private static final String UPDATE_ROW_VERDICT = """
            UPDATE import_rows r
              JOIN import_batches b ON b.id = r.batch_id
               SET r.row_status = :rowStatus,
                   r.error_message = NULLIF(:errorMessage, '')
             WHERE r.id = :rowId
               AND r.batch_id = :batchId
               AND b.user_id = :userId
               AND b.status IN ('UPLOADED', 'PREVIEWED')
            """;

    private static final String REFRESH_BATCH_COUNTERS = """
            UPDATE import_batches
               SET total_rows = (SELECT COUNT(*) FROM import_rows r WHERE r.batch_id = import_batches.id),
                   valid_rows = (SELECT COUNT(*) FROM import_rows r
                                  WHERE r.batch_id = import_batches.id AND r.row_status = 'VALID'),
                   error_rows = (SELECT COUNT(*) FROM import_rows r
                                  WHERE r.batch_id = import_batches.id AND r.row_status = 'ERROR'),
                   duplicate_rows = (SELECT COUNT(*) FROM import_rows r
                                      WHERE r.batch_id = import_batches.id
                                        AND r.row_status = 'DUPLICATE'),
                   imported_rows = (SELECT COUNT(*) FROM import_rows r
                                     WHERE r.batch_id = import_batches.id
                                       AND r.row_status = 'IMPORTED')
             WHERE id = :batchId
               AND user_id = :userId
               AND status IN ('UPLOADED', 'PREVIEWED')
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long createBatch(Long userId, String filename, String fileHash, int totalRows,
                            int validRows, int errorRows, int duplicateRows) {
        entityManager.createNativeQuery(INSERT_BATCH)
                .setParameter("userId", userId)
                .setParameter("filename", filename)
                .setParameter("fileHash", fileHash)
                .setParameter("status", ImportBatchStatus.PREVIEWED.name())
                .setParameter("totalRows", totalRows)
                .setParameter("validRows", validRows)
                .setParameter("errorRows", errorRows)
                .setParameter("duplicateRows", duplicateRows)
                .executeUpdate();

        Object insertedId = entityManager.createNativeQuery(SELECT_LAST_INSERT_ID).getSingleResult();
        return ((Number) insertedId).longValue();
    }

    @Transactional
    public void insertRows(long batchId, List<ImportRowDraft> drafts) {
        for (ImportRowDraft draft : drafts) {
            entityManager.createNativeQuery(INSERT_ROW)
                    .setParameter("batchId", batchId)
                    .setParameter("csvRowNo", draft.csvRowNo())
                    .setParameter("rawData", draft.rawData())
                    .setParameter("parsedDate", text(draft.parsedDate()))
                    .setParameter("parsedAmount", text(draft.parsedAmount()))
                    .setParameter("parsedType", text(draft.parsedType()))
                    .setParameter("parsedDescription", draft.parsedDescription())
                    .setParameter("parsedCategoryName", draft.parsedCategoryName())
                    .setParameter("aiSuggestedCategoryId", text(draft.aiSuggestedCategoryId()))
                    .setParameter("rowStatus", draft.rowStatus().name())
                    .setParameter("errorMessage", draft.errorMessage())
                    .executeUpdate();
        }
    }

    @Transactional
    public int setResolvedCategory(Long userId, Long batchId, Long rowId, Long categoryId) {
        return entityManager.createNativeQuery(UPDATE_ROW_CATEGORY)
                .setParameter("categoryId", categoryId)
                .setParameter("rowId", rowId)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    public int cancelBatch(Long userId, Long batchId) {
        return entityManager.createNativeQuery(CANCEL_BATCH)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    public int setRowVerdict(Long userId, Long batchId, Long rowId, ImportRowStatus rowStatus,
                             String errorMessage) {
        return entityManager.createNativeQuery(UPDATE_ROW_VERDICT)
                .setParameter("rowStatus", rowStatus.name())
                .setParameter("errorMessage", errorMessage)
                .setParameter("rowId", rowId)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    public int refreshBatchCounters(Long userId, Long batchId) {
        return entityManager.createNativeQuery(REFRESH_BATCH_COUNTERS)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
