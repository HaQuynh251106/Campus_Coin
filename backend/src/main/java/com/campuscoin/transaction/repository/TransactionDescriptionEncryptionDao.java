package com.campuscoin.transaction.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restores the one storage form {@code transactions.description} is documented to have, for rows a
 * stored procedure inserted (OB-018).
 *
 * <p><b>The problem this exists to close.</b> {@code docs/SECURITY.md} §12.2 lists
 * {@code transactions.description} as <em>Encrypted</em>, and {@code db/01_schema.sql} says the same
 * of the column. Two write paths can reach it. {@code TransactionService} encrypts before storing, so
 * everything the API writes is an envelope. {@code sp_apply_csv_batch} does not and cannot - a MySQL
 * procedure has no access to the application key, which is the whole point of the design - so an
 * imported row arrives holding plaintext. The row is readable either way, because every read goes
 * through {@code decryptStored}, but the column's documented state is then only true of <em>some</em>
 * rows: two rows of the same batch disagree, one having been re-encrypted by a later edit and one
 * not. OB-018 records the finding and the three candidate resolutions.
 *
 * <p><b>Which resolution this is, and why.</b> OB-018's answer 2 - the encrypting writer owns the
 * column's storage form. Not answer 1 (the procedure owns it), because that leaves
 * {@code SECURITY.md}'s "Encrypted" row false for every imported record and leaves the residual
 * exposure OB-012 describes open forever. Not answer 3 (stop calling it an encrypted column),
 * because that reverses a recorded security position to accommodate an implementation limitation.
 * Answer 2 restores the documented state and moves no security position.
 *
 * <p><b>Why the repair happens in Java rather than in the procedure.</b> It must: the envelope can
 * only be produced where the key is, and the key is deliberately never sent to MySQL. This class is
 * therefore not a workaround for a procedure that could have done the job - it is the only place the
 * job can be done at all, and it is the same boundary {@code TransactionService} already writes
 * through.
 *
 * <p><b>Why one {@code UPDATE} for the batch rather than one per row.</b> The values are already in
 * the caller's hands - {@code ImportService} read the rows back to learn from them - so the statement
 * is built from the batch's own row ids and their already-encrypted envelopes. The row count is
 * bounded by the same cap the previewer applies, so this is at most a few hundred assignments in the
 * transaction the commit already opened, and the enclosing {@code @Transactional} on the commit means
 * a failure here rolls the whole import back rather than leaving half a batch in plaintext.
 *
 * <p><b>{@code user_id} is a predicate, not a convenience.</b> The commit already established that the
 * batch is the caller's, but this statement is reachable on its own and the ownership belongs in the
 * statement for the same reason {@code ImportWriteDao} puts it there: an id that is not the caller's
 * matches nothing rather than being caught by a check a later change could drop. For the same reason
 * the predicate requires {@code source = 'CSV'}: an API-written row is already an envelope, and
 * touching it would replace one valid envelope with another and append a {@code transaction_history}
 * row that records a change no student made.
 *
 * <p>Not {@code readOnly} - nothing about it is a read.
 */
@Repository
public class TransactionDescriptionEncryptionDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Rewrites the description of the named transactions, which must be CSV-sourced and the
     * caller's own.
     *
     * <p>Each entry is a transaction id and the envelope the description should hold. The pair is
     * passed positionally as a two-element array rather than as a map, because the ids are what the
     * statement's {@code IN} clause needs and the envelopes are only ever looked up by them.
     *
     * @param userId       the owning student; rows belonging to anybody else are not matched
     * @param transactionIds the imported transactions to re-encode; empty is a no-op
     * @param envelopes    the Base64 envelopes, in the same order as {@code transactionIds}
     * @return the number of rows changed, so the caller can assert the rewrite really happened
     */
    @Transactional
    public int reencryptImportedDescriptions(Long userId,
                                             List<Long> transactionIds,
                                             List<String> envelopes) {
        if (transactionIds.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("""
                UPDATE transactions
                   SET description = CASE id
                """);
        for (int i = 0; i < transactionIds.size(); i++) {
            sql.append("        WHEN :id").append(i).append(" THEN :env").append(i).append('\n');
        }
        sql.append("""
                       END
                 WHERE user_id = :userId
                   AND source = 'CSV'
                   AND id IN (:ids)
                """);

        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("userId", userId)
                .setParameter("ids", transactionIds);
        for (int i = 0; i < transactionIds.size(); i++) {
            query.setParameter("id" + i, transactionIds.get(i));
            query.setParameter("env" + i, envelopes.get(i));
        }
        return query.executeUpdate();
    }
}
