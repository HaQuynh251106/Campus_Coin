package com.campuscoin.recent.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.recent.entity.RecentAction;

/**
 * Writes UC-26's activity rows through {@code sp_touch_recent_activity}.
 *
 * <p><b>Why a procedure and not a {@code JpaRepository#save}.</b> The application's database account
 * holds direct grants on {@code recent_activity}, so an insert from here would work - and it would be
 * the wrong write. The procedure is where three rules live that this module must not restate:
 *
 * <ul>
 *   <li>that {@code action} is one of {@code VIEWED}/{@code EDITED};</li>
 *   <li>that the transaction exists at all;</li>
 *   <li>that it belongs to the caller. This is the one that matters: {@code fk_recent_txn} proves only
 *       that the row exists, not whose it is, so without this check a student could create an activity
 *       row pointing at somebody else's transaction - and the recent-activity list would then expose
 *       that transaction's amount, date and description through a route that was never meant to name
 *       another student's record. That is BR-02, and it is enforced in the database rather than in a
 *       service, so it holds for every caller including a hand-run {@code CALL}.</li>
 * </ul>
 *
 * <p>{@code ON DUPLICATE KEY UPDATE occurred_at = NOW()} in the procedure is the fourth thing Java
 * must not reimplement: recording the same view twice moves the existing row rather than inserting a
 * second one, which is what "recently viewed" means. A {@code save} would have to read the row, decide
 * whether it exists, and update or insert - three statements and a race, replaced by one.
 *
 * <p><b>Not {@code @Transactional(readOnly = true)}.</b> MySQL refuses to execute a {@code CALL} on a
 * read-only connection outright, so the plain annotation is required here for the same reason
 * {@code TransactionProcedureDao} and {@code TipGenerationDao} use it - it is a property of the
 * driver, not a claim that the call is read-only.
 */
@Repository
public class RecentActivityProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-26: records that the caller viewed or edited one of their own transactions.
     *
     * <p>Returns nothing, because the procedure returns nothing. What the caller gets back is the
     * entry as it now reads, which the service loads from the view afterwards - so the response is
     * rendered from the same query that produces the list rather than from a second, hand-built
     * object that could disagree with it.
     *
     * <p>Parameters are bound by name and never interpolated. The action is sent as the enum's
     * {@code name()}, which is the member the column's {@code ENUM} stores - never its ordinal, whose
     * meaning would change if the constants were ever reordered.
     */
    @Transactional
    public void touch(Long userId, Long transactionId, RecentAction action) {
        entityManager.createNativeQuery(
                        "CALL sp_touch_recent_activity(:userId, :transactionId, :action)")
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("action", action.name())
                .executeUpdate();
    }
}
