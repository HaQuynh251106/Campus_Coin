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

/**
 * Reads the CSV import batches and rows UC-11 serves (UC-11).
 *
 * <p><b>There is no view over these two tables, and the reads name them directly.</b> That is unusual
 * for this project and it is deliberate, in the way {@code InsightViewDao} and {@code AnomalyViewDao}
 * record for their own tables: {@code db/02_views.sql} holds no view over {@code import_batches} or
 * {@code import_rows} because nothing has ever read them - UC-11 is module 12 and the tables were
 * written into the schema when it was designed. Adding a view now would be adding one for a single
 * feature rather than because two readers share it, which is the test every other view in this project
 * passes.
 *
 * <p><b>Every read that names a batch narrows on the owner through the batch, and that is not a
 * formality.</b> {@code import_rows} has no {@code user_id} column at all - deliberately, as the
 * schema's own comment and {@code DB_DESIGN.md} §4.8 record - so ownership of a row can only be
 * expressed as a join to {@code import_batches.user_id}. Every query below that touches a row carries
 * that join, which makes BR-02 a property of the query rather than a check at a call site.
 *
 * <p><b>The commit path is the reason this matters most.</b> {@code sp_apply_csv_batch} takes only a
 * batch id and reads the owner from the batch itself - it never compares that owner with a caller,
 * because a stored procedure has no caller. So the procedure would happily commit another student's
 * batch if it were handed the id, and the ownership check that stops that lives here: the service reads
 * the batch through {@link #findBatch} before it calls, and a batch that is not the caller's is
 * indistinguishable from one that does not exist.
 *
 * <p><b>{@code parsed_description} is read as stored and is NOT decrypted, because it is not
 * encrypted.</b> The column is marked {@code KNOWN PLAINTEXT - RESIDUAL EXPOSURE, DELIBERATE} in
 * {@code db/01_schema.sql} and named in OB-012; {@code ImportRow} records why and what the consequence
 * is. Passing it to {@code EncryptionService#decryptStored} anyway would work - that method returns a
 * non-envelope value unchanged - but it would also mean a future reader could not tell from this class
 * whether the column was encrypted, which is exactly the confusion the schema comment exists to prevent.
 *
 * <p>Projected by alias rather than mapped as entities, for the reason {@code CategoryRuleDao} gives:
 * a projection cannot be flushed, so no JPA write can compete with the statements
 * {@code ImportWriteDao} runs over the same rows.
 */
@Repository
public class ImportViewDao {

    /**
     * UC-11: the caller's batches, newest first.
     *
     * <p>Ordered by {@code created_at} and then {@code id}. The timestamp has second precision, so two
     * batches uploaded inside one second would otherwise be free to swap between two identical calls;
     * the id makes the order total. Descending on both, because the list is "my recent imports" and the
     * one just uploaded is the one the student wants.
     */
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

    /**
     * UC-11: one batch of the caller's.
     *
     * <p>{@code user_id} is a predicate rather than a check afterwards, so another student's batch and a
     * batch that does not exist produce the same empty answer and the same {@code 404} - the
     * indistinguishability section 7.5 requires, and what makes the commit path safe (see the class
     * note).
     */
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

    /**
     * UC-11: every row of one of the caller's batches, in file order.
     *
     * <p>Ordered by {@code csv_row_no}, which is the order the student sees in their spreadsheet and the
     * order the commit procedure walks. It is unique within a batch - {@code uk_import_row} says so - so
     * the order is total and the list cannot appear to shuffle.
     *
     * <p>Unbounded, because a batch is bounded by the upload itself: {@code ImportService} refuses a file
     * with more rows than it will accept, so this read cannot return an unbounded list. Paging it would
     * mean the preview showed part of the file, and the preview's whole job is to show what will happen
     * to all of it.
     *
     * <p>The join to {@code import_batches} is what makes this the caller's batch: there is no owner
     * column on a row to narrow on instead.
     */
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

    /**
     * UC-11: one row of one of the caller's batches.
     *
     * <p>Backs the response to a category override: the row a client is handed afterwards is the stored
     * row rather than an echo of the request, so the response cannot describe a state the table does not
     * hold - the shape {@code RecentActivityViewDao#findOne} gives the same problem.
     */
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

    /**
     * UC-11: the records the caller already has, for the duplicate check to compare against.
     *
     * <p>Four columns of {@code transactions}, read directly rather than through a view. There is no
     * view over an imported row's comparison data - {@code v_user_recent_activity} was built for a
     * different question and carries a description and an action this check does not need - and adding
     * one for a single reader would be the duplication this project's views are meant to avoid.
     * {@code AnomalyViewDao} names the table directly for the same reason.
     *
     * <p><b>Only {@code (category_id, amount, txn_date)} is projected, and that is the disclosure
     * boundary.</b> The duplicate rule compares those three and needs nothing else, so the description,
     * the source and the flags are never read on this path. The result is fed to
     * {@link ImportDuplicateDetector} and never leaves the server.
     *
     * <p>{@code is_deleted = 0}, so a record in the trash is not a duplicate of anything: the student has
     * already removed it, and the reasoning {@code AnomalyViewDao} records for the same predicate applies
     * here - trashing one of a pair clears the other on the next scan, with no repair step.
     *
     * <p>Ordered by {@code id}, which is creation order. The detector only asks whether an earlier record
     * matches, so a stable order is what makes which one it names reproducible.
     */
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

    /** UC-11: the caller's batches, newest first. An empty list is a real answer for a new student. */
    @Transactional(readOnly = true)
    public List<ImportBatchRow> findBatches(Long userId, int limit) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // every row is mapped through toBatchRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BATCHES, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(ImportViewDao::toBatchRow).toList();
    }

    /** UC-11: one of the caller's batches, or empty - for another student's, the same empty answer. */
    @Transactional(readOnly = true)
    public Optional<ImportBatchRow> findBatch(Long userId, Long batchId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE_BATCH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("batchId", batchId)
                .getResultList();

        return rows.stream().map(ImportViewDao::toBatchRow).findFirst();
    }

    /** UC-11: every row of one of the caller's batches, in file order. */
    @Transactional(readOnly = true)
    public List<ImportRow> findRows(Long userId, Long batchId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ROWS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("batchId", batchId)
                .getResultList();

        return rows.stream().map(ImportViewDao::toRow).toList();
    }

    /**
     * UC-11: one row of one of the caller's batches, or empty.
     *
     * <p>Empty covers three cases that are answered identically and deliberately: the batch is not the
     * caller's, the row is not in that batch, and the row does not exist. Telling them apart would let a
     * caller discover other students' batch and row identifiers (section 7.5).
     */
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

    /**
     * UC-11: the caller's existing records, as the duplicate check wants them.
     *
     * <p>Returns detector candidates rather than a projection record: the shape the check consumes is
     * the only shape this data has, so a separate row type would exist only to be converted once.
     * {@code lineNumber} is meaningless here - a record already in the database came from no line of
     * this file - so it is zero, and the detector never reports it because it only ever names the
     * earlier date of a duplicate, never the earlier candidate's line.
     */
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

    // ------------------------------------------------------------------
    //  Projections
    // ------------------------------------------------------------------

    /**
     * One batch as the projection carries it.
     *
     * <p>{@code status} is resolved by name through {@link ImportBatchStatus#valueOf}, so a member the
     * enum does not know is a fault rather than a silently substituted one - the reasoning
     * {@code AnomalyViewDao#toRow} records for {@code flag_type}.
     */
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

    /**
     * One row as the projection carries it.
     *
     * <p>Both enums are resolved by name. {@code parsed_type} is nullable - a row whose type could not be
     * read stores a null - so it is resolved only when present; {@code row_status} is {@code NOT NULL}
     * with a default, so a null there would mean a hand-run statement and is left to throw rather than
     * being read as {@code VALID}, which would claim a row is importable on the strength of a missing
     * value.
     */
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
