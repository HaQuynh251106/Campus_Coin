package com.campuscoin.imports.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.imports.entity.ImportBatchStatus;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;

/**
 * Stores the batch and rows the preview decided (UC-11).
 *
 * <p><b>These are the only writes in this project that go to a table directly rather than through a
 * stored procedure, and the reason is that there is no procedure to go through.</b> Every other write
 * path here routes through SQL because the procedures carry rules Java must not restate - ownership,
 * authorisation, transitions. An audit of {@code db/03_procedures.sql} finds no procedure that
 * inserts into {@code import_batches} or {@code import_rows}; the one procedure that touches them,
 * {@code sp_apply_csv_batch}, only reads rows and updates their status. So the statements below are
 * the schema's own constraints applied honestly: {@code uk_import_row} makes a row number unique
 * within a batch, {@code fk_batch_user} gives the batch an owner, {@code fk_import_row_batch}
 * requires the parent, and the {@code ENUM}s and {@code CHECK}-like widths bound every value written.
 * Nothing below could store a row the schema does not describe.
 *
 * <p><b>Every statement that touches an existing row narrows on the owner through the batch.</b>
 * {@code import_rows} has no {@code user_id} - see {@code ImportViewDao} - so ownership can only be
 * expressed as a join, and each multi-table {@code UPDATE} below carries one. That is what makes BR-02
 * a property of the statement rather than a check at a call site: a row id belonging to another
 * student matches nothing and the affected-row count is zero. The five statements that address a row
 * or a batch the student already has - the category override, the two verdict writers and the counter
 * refresh, and the cancel - all carry both the join and an open-batch predicate; the two inserts do
 * not need one, because the first creates the batch under the caller's own id and the second is only
 * ever reached with the id that insert returned.
 *
 * <p><b>Nullable columns are written as SQL {@code NULLIF} over a text parameter rather than as bound
 * nulls.</b> A Hibernate native query cannot bind a null without a declared type -
 * {@code AdminCategoryProcedureDao} records the limitation and works around it by declaring every
 * parameter of a {@code StoredProcedureQuery}. That route is not open here: these are plain
 * statements, not procedure calls, so there is no signature to declare against. Sending every value as
 * text and letting each column coerce it keeps the binding unambiguous - a non-null String always has
 * an inferable type - and an empty string becomes a {@code NULL} in the column, which is how "no
 * value" is spelled for a {@code DATE}, a {@code DECIMAL}, an {@code ENUM} or a {@code JSON} column.
 * The conversion is MySQL's own and is the one an {@code INSERT} of a quoted literal would get.
 *
 * <p>{@code rawData} relies on that implicit conversion too: a valid JSON document inserted into a
 * {@code JSON} column is parsed by the server. The value is built by {@code ImportRowReader}, which
 * escapes every cell, so it is well formed by construction - and a value that was not would be a
 * refused {@code INSERT} rather than a wrong one.
 */
@Repository
public class ImportWriteDao {

    /**
     * UC-11 A1: the batch row, created already in {@link ImportBatchStatus#PREVIEWED}.
     *
     * <p><b>Inserted as {@code PREVIEWED} rather than as the column's {@code UPLOADED} default, and
     * this is the honest value rather than a shortcut.</b> The whole upload - the batch, its rows, the
     * counters - is one transaction, so an {@code UPLOADED} batch is a state no caller could ever
     * observe; it would exist only between two statements. By the time the transaction commits the
     * rows are stored and the response describes them, which is exactly what {@code PREVIEWED} means,
     * and it is the state {@code ImportBatchStatus} records this application as owning. The
     * procedure's guard accepts both, so nothing downstream depends on the distinction.
     *
     * <p>{@code file_hash} is written because this is the only moment the file's bytes are in hand:
     * {@code CHAR(64)} is a SHA-256 hex digest, and nothing could compute it later.
     */
    private static final String INSERT_BATCH = """
            INSERT INTO import_batches
              (user_id, original_filename, file_hash, status,
               total_rows, valid_rows, error_rows, duplicate_rows, imported_rows)
            VALUES
              (:userId, :filename, :fileHash, :status,
               :totalRows, :validRows, :errorRows, :duplicateRows, 0)
            """;

    /**
     * {@code LAST_INSERT_ID()} read back immediately after the batch insert, inside the same
     * transaction.
     *
     * <p><b>Safe here in a way it is not for the administrative procedures.</b> Those cannot use it -
     * the module 11 findings record why - because each procedure writes its {@code admin_audit_log}
     * row after its target, so the session's last insert id belongs to the audit row by the time the
     * call returns. Nothing at all runs between the statement above and this one, so the function
     * reports the batch that was just inserted. The read-back is what the administrative services do
     * differently rather than what this one does wrong: they have no unbroken interval to read in.
     *
     * <p>Kept as a separate statement rather than folded in with {@code RETURNING} - MySQL has no such
     * clause - and not replaced by a natural key, because {@code import_batches} has none: a student
     * may legitimately upload two files with the same name in the same second, and inventing a unique
     * key to find the row again would be constraining their data to suit this code.
     */
    private static final String SELECT_LAST_INSERT_ID = "SELECT LAST_INSERT_ID()";

    /**
     * UC-11 A1: one row of the file, as the preview decided it.
     *
     * <p>One statement per row, executed in a loop, and the bound on that loop is what makes it
     * defensible: {@code ImportService} refuses a file with more rows than it will accept, so this is
     * at most a few hundred statements in one transaction - not an unbounded walk. The alternative -
     * a mapped entity - would need {@code raw_data} mapped as a JSON column, and a Hibernate type
     * annotation would put the one column this table is unusual about into the entity model for no
     * benefit, since nothing reads these rows back through it. The alternative for a single statement -
     * building a {@code VALUES} list of unknown length - is dynamic SQL assembled from student-supplied
     * values, which is the thing this project does not do anywhere.
     *
     * <p>{@code resolved_category_id} and {@code transaction_id} are not written: a row is stored
     * {@code VALID}, {@code ERROR} or {@code DUPLICATE}, and the first of those three is what the
     * commit procedure later walks. {@code resolved_category_id} is written only by the override
     * endpoint and by the procedure, and {@code transaction_id} only by the procedure - so the insert
     * leaves both at their schema default, which is {@code NULL}.
     */
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

    /**
     * UC-11 B6: records the category the student chose for one row of the preview.
     *
     * <p><b>A multi-table {@code UPDATE} rather than a read, a check and a write.</b> The owner is
     * reached through the row's batch - {@code import_rows} has no owner of its own - and the join is
     * inside the statement, so a row id belonging to another student simply matches nothing. Doing it
     * in three steps would put the ownership decision in a service where a later change could drop it;
     * doing it here means the affected-row count is the answer to "was this the caller's row", and the
     * service has nothing to re-check.
     *
     * <p>The batch's status is part of the predicate for the same reason: only an open batch can be
     * changed, which is the rule {@code ImportBatchStatus#isOpen} states in Java and
     * {@code sp_apply_csv_batch} states in SQL for the commit. A settled batch matches nothing here,
     * so the service's own check and this one cannot disagree - and this one holds for a caller that
     * reached the statement some other way.
     *
     * <p>{@code error_message} is cleared, because the sentence that explained a duplicate is no longer
     * true once the student has chosen a category for the row: the choice is what makes it importable
     * again. Rows the preview rejected as malformed are not touched by this statement - the service
     * refuses to override them - so a message that explains a bad date is never silently removed.
     *
     * @return the number of rows changed: one when the row is the caller's and the batch is open,
     *         zero otherwise
     */
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

    /**
     * UC-11 A2: the student abandons the batch, so nothing is imported and nothing will be.
     *
     * <p>{@code user_id} and the status set are both predicates. The first is BR-02 - another student's
     * batch matches nothing. The second is what makes the transition safe to repeat and impossible to
     * apply to a settled batch: cancelling a committed batch would leave its rows marked and its
     * transactions in place, which is the one inconsistency this statement could create.
     *
     * <p>The rows are deliberately left alone. A cancelled batch keeps its rows so the student can see
     * what they had - and so the report survives the decision not to import it. Nothing reads a
     * cancelled batch's rows as importable, because {@code sp_apply_csv_batch} refuses the status
     * before it opens its cursor.
     */
    private static final String CANCEL_BATCH = """
            UPDATE import_batches
               SET status = 'CANCELLED'
             WHERE id = :batchId
               AND user_id = :userId
               AND status IN ('UPLOADED', 'PREVIEWED')
            """;

    /**
     * UC-11 B6: rewrites one row's verdict after the duplicate check has been re-run.
     *
     * <p><b>This statement exists because a verdict is not a property of the row alone.</b> Whether a
     * row is a duplicate depends on the category it will be filed under, and the student can change
     * that with {@link #UPDATE_ROW_CATEGORY} - so {@code ImportPreviewer} re-decides and this writes
     * the answer back. Without it, a row the student corrected would keep the verdict the old category
     * earned it: a row falsely flagged would stay excluded with no way to rescue it, and a row that
     * became a duplicate of another would still be promised to the student as importable. Either way
     * the preview would show a decision the commit does not share.
     *
     * <p>Both columns move together and the reason is that they are one fact. {@code error_message} is
     * the explanation for a row that will not be imported, so it is written exactly when the new status
     * is {@code DUPLICATE} and cleared otherwise - which is the same relationship
     * {@code ImportRowStatus} records for the pair. A row going back to {@code VALID} has its sentence
     * removed because the sentence is no longer true.
     *
     * <p><b>{@code row_status} is written but {@code resolved_category_id} is not</b>: this statement
     * answers "is this row a duplicate?", and the category is the input to that question rather than
     * its result. The two are separate statements for that reason, and each is idempotent on its own.
     *
     * <p>The ownership join and the open-batch predicate are the same ones
     * {@link #UPDATE_ROW_CATEGORY} carries, for the same reason: a row id belonging to another student
     * matches nothing, and a batch that has been settled cannot have its verdicts rewritten behind the
     * procedure that settled them. {@code IMPORTED} is therefore unreachable here in practice as well
     * as by the caller's own filter - the batch it belongs to is closed.
     *
     * @return the number of rows changed: one when the row is the caller's and the batch is open
     */
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

    /**
     * UC-11 B6: brings the batch's counters back into agreement with its rows.
     *
     * <p><b>This statement exists because a verdict is not a property of the batch alone either.</b>
     * Whether a row is a duplicate rests on its category, and {@link #UPDATE_ROW_CATEGORY} is how the
     * student changes that - so a re-verdict can move a row between {@code VALID} and {@code DUPLICATE}
     * and leave the batch's own {@code valid_rows} and {@code duplicate_rows} describing a state the rows
     * no longer hold. Without this, the preview screen would show a row marked "already recorded" beside
     * a counter saying nothing was - the same contradiction {@link #UPDATE_ROW_VERDICT} prevents one row
     * down, one level up.
     *
     * <p><b>The counts are read from the rows rather than computed from the verdicts</b>, so there is one
     * definition of each counter and it is the same one {@code sp_apply_csv_batch} uses when it rewrites
     * these five columns at the commit. A count taken from the caller's list of verdicts would be a
     * second arithmetic that could disagree with the table it is describing.
     *
     * <p>The subqueries read {@code import_rows}, never {@code import_batches}, which is what keeps this
     * a legal single-table {@code UPDATE} in MySQL. The ownership and open-batch predicates are
     * {@link #CANCEL_BATCH}'s, for the same reason: a settled batch's counters are the procedure's own
     * account of what it did, and nothing here may rewrite them behind it.
     *
     * @return the number of rows changed - one when the batch is the caller's and still open
     */
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

    /**
     * UC-11 A1: creates the batch and returns its id.
     *
     * <p>The counters are the preview's own: every row of the file, and the three verdicts it reached.
     * {@code imported_rows} is written as zero by the statement rather than passed in, because nothing
     * has been imported yet and a caller able to pass a number here could pass a wrong one.
     */
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

    /**
     * UC-11 A1: stores the file's rows against the batch.
     *
     * <p>One statement per row, in the order the preview produced them. The order is not what decides
     * anything - {@code csv_row_no} is stored on each row and every read orders by it - but writing
     * them in file order keeps a hand inspection of the table readable, which for a table whose whole
     * purpose is showing the student their file is worth the nothing it costs.
     *
     * <p>Every value is sent as text: see the class note for why, and {@code ImportRowDraft} for what
     * each one means. {@code csv_row_no} is the file's own line number, so the unique key
     * {@code uk_import_row} is satisfied by construction - the parser numbers a record once and never
     * repeats it.
     */
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

    /**
     * UC-11 B6: records the caller's category choice for one row.
     *
     * @return the number of rows changed - one when the row belongs to the caller and its batch is
     *         still open, zero otherwise. The caller decides what zero means; it is deliberately not
     *         answered here, because the same zero covers "not your row" and "the batch is settled"
     *         and those two are answered differently.
     */
    @Transactional
    public int setResolvedCategory(Long userId, Long batchId, Long rowId, Long categoryId) {
        return entityManager.createNativeQuery(UPDATE_ROW_CATEGORY)
                .setParameter("categoryId", categoryId)
                .setParameter("rowId", rowId)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * UC-11 A2: marks the caller's open batch cancelled.
     *
     * @return the number of rows changed - one when the batch is the caller's and still open, zero
     *         otherwise
     */
    @Transactional
    public int cancelBatch(Long userId, Long batchId) {
        return entityManager.createNativeQuery(CANCEL_BATCH)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * UC-11 B6: writes back the duplicate verdict the preview re-reached for one row.
     *
     * <p>Called with the findings of {@code ImportPreviewer#verdicts}, one statement per row whose
     * verdict could be asked. Both values come from that class rather than from the request: a caller
     * able to name a status here could mark a malformed row importable, which is the one thing the
     * preview exists to tell the student about. Nothing a client sends reaches this statement.
     *
     * @return the number of rows changed - one when the row belongs to the caller and its batch is
     *         still open, zero otherwise. The caller does not branch on this: it has already read the
     *         row and the batch through ownership-narrowed queries, so zero would mean the batch was
     *         committed by a concurrent request, and the read-back that follows reports the state the
     *         table actually holds.
     */
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

    /**
     * UC-11 B6: recomputes the batch's counters from its rows.
     *
     * <p>Called after the re-verdicts have been written, so the counters describe the rows as they now
     * stand. The caller does not read this return value: zero means the batch stopped being open while
     * the request was running, and the read-back that follows reports the status the table actually
     * holds - which is the same handling {@link #setRowVerdict} records for the same zero.
     *
     * @return the number of batches changed - one when the batch is the caller's and still open
     */
    @Transactional
    public int refreshBatchCounters(Long userId, Long batchId) {
        return entityManager.createNativeQuery(REFRESH_BATCH_COUNTERS)
                .setParameter("batchId", batchId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /** A value as text, with {@code null} and empty both becoming the empty string. */
    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
