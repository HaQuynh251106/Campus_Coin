package com.campuscoin.transaction.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.transaction.entity.Transaction;

/**
 * MySQL access for {@code transactions} (UC-07, UC-10).
 *
 * <p>Every query here takes the owning student's id. There is no method that can fetch a
 * transaction by id alone, so no service method can accidentally act on another student's record.
 * BR-02 requires ownership to be enforced server-side, and making it a property of the queries
 * rather than a comparison someone has to remember is the version of that which cannot be
 * forgotten.
 *
 * <p>The three single-row lookups split two ways, and both distinctions matter.
 *
 * <p><b>Which state is visible.</b>
 *
 * <ul>
 *   <li>{@link #findActiveByIdAndUserId} sees only rows that are not soft-deleted. It is what
 *       {@code GET} uses, so a record the student has deleted is simply not there to read - which is
 *       what "deleted" has to mean for the read to be honest.</li>
 *   <li>{@link #findAnyByIdAndUserIdForUpdate} sees a row in either state. {@code DELETE} needs it so
 *       that deleting a record the student already deleted can answer "already done" instead of "not
 *       found", and restore needs it because the row it acts on is by definition deleted.</li>
 * </ul>
 *
 * <p><b>Whether the row is locked.</b> Every lookup the write paths use adds
 * {@code SELECT ... FOR UPDATE}, and the no-lock variant of the either-state lookup deliberately does
 * not exist: the only reason to read a deleted row is to act on it, so a caller that asked for one
 * without a lock would be making a write decision from a state another request can change underneath
 * it. The reasoning is on the queries themselves, below; the short version is that a state check made
 * from a lock-free read can be made twice, and two deletes that both see a live row would both report
 * success.
 *
 * <p>No query here mentions {@code transaction_history}. BR-09's history is written by
 * {@code trg_transactions_after_insert} and {@code trg_transactions_after_update}, and nothing in
 * UC-07 or UC-10 asks the student to read it back, so mapping the table would be dead weight.
 *
 * <p>Nothing here writes {@code is_deleted} or {@code deleted_at} either. Those two columns belong
 * to {@code sp_soft_delete_transaction} and {@code sp_restore_transaction}, and the entity maps
 * them as not updatable so that no JPA statement can compete with the procedures.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * The caller's transactions, newest first, within an optional date range (UC-10).
     *
     * <p>The category is fetched in the same statement. Every list row renders the category's name,
     * icon, colour and type - the last of which is the only thing that says whether the record is
     * income or expense (BR-05, since {@code transactions} has no type column) - so leaving it
     * lazy would mean one extra query per row. {@code open-in-view} is false, so it would also be
     * an outright failure rather than a slow success.
     *
     * <p>The range is expressed as two non-null bounds rather than as nullable parameters: the
     * service substitutes the earliest and latest representable dates when the client omits one.
     * A predicate of the form {@code :from IS NULL OR ...} reads well but makes the statement
     * unindexable and forces the driver to infer a type for a null it cannot see, where two
     * concrete bounds use {@code ix_txn_user_date (user_id, txn_date, is_deleted)} as it was
     * designed to be used.
     *
     * <p>Ordering by {@code txn_date} descending is what "newest first" means here, and the id
     * makes the order total: several records commonly share a date, and without a final tie-break
     * MySQL may return equal rows in either order, so the list would appear to shuffle between two
     * identical calls.
     *
     * @param includeDeleted when true, soft-deleted rows are returned as well, each one carrying
     *                       {@code isDeleted}. UC-10 A1 needs this: a student can only restore a
     *                       record they can see.
     * @param from           the earliest date to include, inclusive
     * @param to             the latest date to include, inclusive
     */
    @Query("""
            SELECT t FROM Transaction t
            JOIN FETCH t.category
             WHERE t.userId = :userId
               AND (t.isDeleted = false OR :includeDeleted = true)
               AND t.txnDate >= :from
               AND t.txnDate <= :to
             ORDER BY t.txnDate DESC, t.id DESC
            """)
    List<Transaction> findForStudent(@Param("userId") Long userId,
                                     @Param("includeDeleted") boolean includeDeleted,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    /**
     * One of the caller's own transactions that has not been deleted, for reading.
     *
     * <p>Empty both when the row does not exist, when it belongs to someone else, and when the
     * caller has already deleted it. The caller cannot tell those apart, which is the point: a
     * response that distinguished them would let a client probe for the existence of other
     * students' records (section 7.5).
     */
    @Query("""
            SELECT t FROM Transaction t
            JOIN FETCH t.category
             WHERE t.id = :id AND t.userId = :userId AND t.isDeleted = false
            """)
    Optional<Transaction> findActiveByIdAndUserId(@Param("id") Long id,
                                                  @Param("userId") Long userId);

    // ------------------------------------------------------------------
    //  Locking lookups, for the operations that write
    // ------------------------------------------------------------------
    //
    // Both say the same thing as the lookup above and add `SELECT ... FOR UPDATE`. They exist
    // because a write decision that is made from a lock-free read can be made twice.
    //
    // The case that forces it: deleting a record twice, concurrently. Both requests read
    // `is_deleted = 0`, both conclude the delete applies, and both call
    // `sp_soft_delete_transaction`. MySQL serialises the two UPDATEs, but the second one no longer
    // sees a live row - so its trigger records an `isDeleted`/`deletedAt` change rather than a
    // DELETE, and the client is told the delete succeeded twice when it succeeded once. The row
    // lock is what makes the state the second request reads the state the first one left, so the
    // second request answers 409 instead.
    //
    // PATCH uses the active-only variant for the same reason: without the lock it can edit a record
    // that a concurrent delete has just moved to the trash.
    //
    // Deliberately no `JOIN FETCH` here, unlike the read above. `SELECT ... FOR UPDATE` on a join
    // locks rows in every table the join touches, so fetching the category would take a lock on the
    // category row as well - and a category is shared by every record filed under it, so two
    // students editing transactions in the same category would block each other for no reason. The
    // category is loaded lazily instead, inside the same transaction, which costs one query on the
    // write paths only.
    //
    // Reads take no lock: `GET` is answered from a plain query, and making it lock would turn a
    // list of one student's records into a queue.

    /** The active-only lookup above, with the row locked for the duration of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.id = :id AND t.userId = :userId AND t.isDeleted = false
            """)
    Optional<Transaction> findActiveByIdAndUserIdForUpdate(@Param("id") Long id,
                                                           @Param("userId") Long userId);

    /**
     * One of the caller's own transactions in either state, with the row locked.
     *
     * <p>Used by delete, so that repeating a delete answers "already done" rather than "not found",
     * and by restore, whose whole subject is a deleted row. Ownership is enforced here exactly as in
     * the lookup above, so an id belonging to another student is still simply absent.
     *
     * <p>There is no lock-free twin of this one, and that is deliberate. Reading a deleted row has
     * exactly one purpose - to change its state - so a caller that wanted one without a lock would be
     * deciding a write from a state that another request can move underneath it. Leaving the method
     * out means that mistake cannot be made.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.id = :id AND t.userId = :userId
            """)
    Optional<Transaction> findAnyByIdAndUserIdForUpdate(@Param("id") Long id,
                                                        @Param("userId") Long userId);
}
