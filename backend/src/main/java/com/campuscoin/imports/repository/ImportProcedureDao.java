package com.campuscoin.imports.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a previewed batch through {@code sp_apply_csv_batch} (UC-11).
 *
 * <p><b>This is the whole of the commit step, and the reason it is one call.</b> UC-11 B9 asks the
 * import to generate a transaction for every row the preview left importable, to leave the rows it
 * could not import marked with a reason, and to report the counts. All three happen inside the
 * procedure, in one pass over the rows, and none of them could be reproduced here without restating
 * rules the database already owns:
 *
 * <ul>
 *   <li><b>Which rows are attempted.</b> {@code WHERE batch_id = p_batch_id AND row_status = 'VALID'},
 *       which is the same membership {@code ImportRowStatus#isImportable} states in Java. A duplicate
 *       and an error are both left alone, and they are left alone for the reason
 *       {@code ImportRowStatus} records: the student's remedy differs, so the preview has to say which
 *       one it is.</li>
 *   <li><b>Which category each row is filed under.</b> The student's own choice from the preview is
 *       authoritative and is never re-derived; a name from the file is resolved only when there was no
 *       choice; and only then is the type's default category used. Rewriting that chain here would be
 *       a second definition of it, and the two could disagree about a row - which is exactly the
 *       correction {@code ImportService}'s override endpoint exists to preserve.</li>
 *   <li><b>That the row's owner is the batch's owner.</b> Read from {@code import_batches.user_id},
 *       never from anything a caller sent.</li>
 *   <li><b>That one bad row does not end the batch.</b> Each row is inserted inside a block with its
 *       own {@code EXIT HANDLER}, so a row the database refuses - a future date under BR-08, a retired
 *       category under BR-07, a category belonging to somebody else under BR-02 - becomes an
 *       {@code ERROR} row with a sentence, and the walk continues. Reproducing that isolation in Java
 *       would mean one transaction per row and a partial batch when one failed, which is the opposite
 *       of what the procedure does.</li>
 *   <li><b>That the counters describe what happened.</b> The procedure rewrites all five from its own
 *       counts at the end, and marks the batch {@code COMMITTED} in the same statement. See
 *       {@code ImportBatchRow} for why a previewed batch's counters and a committed batch's counters
 *       are written by two different owners.</li>
 * </ul>
 *
 * <p><b>The procedure takes only a batch id, and that is not an oversight to be worked around.</b> A
 * stored procedure has no caller, so it cannot compare the batch's owner with anybody - it reads the
 * owner from the batch and proceeds. That means it would happily commit another student's batch if it
 * were handed the id, and the ownership check that stops that lives one layer up: the service reads
 * the batch through {@code ImportViewDao#findBatch}, which narrows on {@code user_id}, before it calls
 * anything here. A batch that is not the caller's is not found, and there is nothing to pass on.
 *
 * <p><b>Not {@code @Transactional(readOnly = true)}.</b> MySQL refuses to execute a {@code CALL} on a
 * read-only connection outright, so the plain annotation is required for the same reason
 * {@code RecentActivityProcedureDao} and {@code TransactionProcedureDao} use it - it is a property of
 * the driver rather than a claim that the call does nothing.
 */
@Repository
public class ImportProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-11 B9: generates the transactions for one batch's importable rows.
     *
     * <p>Returns nothing, because the procedure has no {@code OUT} parameter. Everything the caller
     * needs to report afterwards - the counters, the status of each row, the transaction each row
     * became - is read back through {@code ImportViewDao} inside the same transaction, so the response
     * describes the rows the table holds rather than a second tally kept here that could drift from
     * them.
     *
     * <p>The parameter is bound by name and never interpolated.
     *
     * @param batchId the batch to commit; the caller has already established that it is the caller's
     *                own and that it is still open
     */
    @Transactional
    public void applyBatch(Long batchId) {
        entityManager.createNativeQuery("CALL sp_apply_csv_batch(:batchId)")
                .setParameter("batchId", batchId)
                .executeUpdate();
    }
}
