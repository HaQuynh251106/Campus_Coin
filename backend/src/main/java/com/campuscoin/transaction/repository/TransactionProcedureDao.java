package com.campuscoin.transaction.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.transaction.entity.Transaction;

/**
 * Calls the stored procedures that own a transaction's soft-delete lifecycle (UC-10, BR-09).
 *
 * <p>{@code is_deleted} and {@code deleted_at} are not written from Java at all. The schema puts
 * that transition behind {@code sp_soft_delete_transaction} and {@code sp_restore_transaction}, both
 * of which re-check ownership and state in the database, and {@code trg_transactions_before_delete}
 * refuses a hard {@code DELETE} outright. The entity maps both columns as not updatable for the same
 * reason, so there is no second writer to disagree with the procedures.
 *
 * <p>What is <em>not</em> here, deliberately: the procedures' own rules. That the row exists, that it
 * belongs to the caller and that the state transition applies are all checked by the procedure, and
 * restating them in Java would create a second source of truth able to drift. The service does read
 * the current state before calling, but for a different purpose - to answer "already deleted" and
 * "not deleted" precisely, which the procedure cannot do because it signals the same
 * {@code SQLSTATE '45000'} for every refusal.
 *
 * <p>There is no {@code TransactionHistory} entity. BR-09's history is written by
 * {@code trg_transactions_after_insert} and {@code trg_transactions_after_update}, so the table has
 * one writer and it is not the application; mapping it would invite a second one.
 */
@Repository
public class TransactionProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Moves a transaction to the trash (UC-10, BR-09).
     *
     * <p>The row is not removed. {@code is_deleted} is set and {@code deleted_at} stamped, and every
     * report view filters on {@code is_deleted = 0}, which is what makes the record disappear from
     * the student's figures without its history being lost (BR-09).
     *
     * <p>{@code trg_transactions_after_update} fires on this {@code UPDATE} and appends a
     * {@code DELETE} row to {@code transaction_history}, so the change is logged by the database and
     * not by this class.
     *
     * <p>Not marked {@code readOnly}: nothing about it is a read.
     *
     * @param transactionId the row to delete, already confirmed to be the caller's and not deleted
     * @param userId        the owning student, passed so the procedure re-checks ownership itself
     */
    @Transactional
    public void softDelete(Long transactionId, Long userId) {
        entityManager.createNativeQuery("CALL sp_soft_delete_transaction(:id, :userId)")
                .setParameter("id", transactionId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * Brings a soft-deleted transaction back (UC-10 A1).
     *
     * <p>{@code trg_transactions_after_update} appends a {@code RESTORE} row, so the history reads
     * {@code CREATE} → {@code DELETE} → {@code RESTORE} rather than being rewritten. The earlier rows
     * are left exactly as they were, which is what BR-09 requires of a log.
     *
     * <p><b>The entity is refreshed before this returns, and that is not tidiness.</b> The procedure
     * changes {@code is_deleted} and {@code deleted_at} with SQL that Hibernate never sees, so the
     * managed instance still holds the pre-restore values - and both columns are mapped
     * {@code updatable = false} precisely so no JPA write competes with the procedure. Without the
     * refresh, a caller that mapped the entity straight to a response would report the record as
     * still deleted immediately after restoring it.
     *
     * <p>Taking the managed {@link Transaction} rather than an id is what makes the refresh possible
     * here. The service has already loaded and ownership-checked the row in the same persistence
     * context, so this reuses that instance instead of adding a second lookup.
     *
     * @param transaction the managed row to restore, already confirmed to be the caller's and deleted
     * @param userId      the owning student, passed so the procedure re-checks ownership itself
     */
    @Transactional
    public void restore(Transaction transaction, Long userId) {
        entityManager.createNativeQuery("CALL sp_restore_transaction(:id, :userId)")
                .setParameter("id", transaction.getId())
                .setParameter("userId", userId)
                .executeUpdate();

        entityManager.refresh(transaction);
    }
}
