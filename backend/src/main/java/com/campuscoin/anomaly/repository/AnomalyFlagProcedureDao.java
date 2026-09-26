package com.campuscoin.anomaly.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.anomaly.entity.AnomalyFlagType;

/**
 * Writes UC-24's flags through {@code sp_flag_transaction}.
 *
 * <p><b>Why a procedure and not an {@code UPDATE} from here.</b> The application's database account
 * holds direct grants on {@code transactions}, so a statement from this class would work - and it
 * would be the wrong write. Three rules live in the procedure that this module must not restate:
 *
 * <ul>
 *   <li>that the flag type is one of {@code NONE}/{@code DUPLICATE}/{@code UNUSUAL_AMOUNT};</li>
 *   <li>that the record exists at all;</li>
 *   <li>that it belongs to the caller. This is the one that matters: {@code fk_txn_user} proves only
 *       that the record exists, not whose it is, so without this check a student could flag another
 *       student's record - marking somebody else's spending for them and leaving a note behind on it.
 *       That is BR-02, and it is enforced in the database rather than in a service, so it holds for
 *       every caller including a hand-run {@code CALL}.</li>
 * </ul>
 *
 * <p>It is also the argument {@code docs/DB_DESIGN.md} makes for every write in this project: the
 * application is not granted a legitimate direct write path, and a service that issued one would be
 * the first exception to a rule the rest of the schema keeps.
 *
 * <p><b>{@code flagType} is never taken from a request.</b> It is what the detector concluded and what
 * the service handed down - see {@code AnomalyService}. A route that accepted one would let a client
 * mark its own record as reviewed, which is exactly the signal this feature exists to raise.
 *
 * <p>The write fires {@code trg_transactions_after_update}, whose {@code changed_fields} expression
 * names all three flag columns, so the {@code transaction_history} row BR-09 requires is appended when
 * - and only when - one of them actually moved. A rescan reaching the same conclusion therefore writes
 * no history at all. That is also why the service compares before it writes: the trigger makes the
 * history correct, but only skipping the call makes the round trip unnecessary.
 *
 * <p><b>Not {@code @Transactional(readOnly = true)}.</b> MySQL refuses to execute a {@code CALL} on a
 * read-only connection outright, so the plain annotation is required here for the same reason
 * {@code TransactionProcedureDao}, {@code TipGenerationDao} and {@code RecentActivityProcedureDao} use
 * it - it is a property of the driver, not a claim that the call is read-only.
 */
@Repository
public class AnomalyFlagProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-24: sets or clears the caller's own anomaly flag on one of their transactions.
     *
     * <p>Returns nothing, because the procedure returns nothing. What a caller is shown back is the
     * record as it now reads, which {@code AnomalyViewDao} supplies - so the response is rendered from
     * the same query the list uses rather than from a second, hand-built object that could disagree
     * with it. It is the shape {@code RecentActivityProcedureDao#touch} has for the same reason.
     *
     * <p>Parameters are bound by name and never interpolated. The flag type is sent as the enum's
     * {@code name()}, which is the member the column's {@code ENUM} stores - never its ordinal, whose
     * meaning would change if the constants were ever reordered.
     *
     * @param userId   the caller, whose ownership the procedure checks against the row's own
     * @param flagType what the detector concluded; {@link AnomalyFlagType#NONE} clears the flag and
     *                 nulls the note, so "not flagged" has one stored representation
     * @param flagNote why, as prose the student reads; ignored and nulled when clearing
     */
    @Transactional
    public void flag(Long userId, Long transactionId, AnomalyFlagType flagType, String flagNote) {
        entityManager.createNativeQuery(
                        "CALL sp_flag_transaction(:transactionId, :userId, :flagType, :flagNote)")
                .setParameter("transactionId", transactionId)
                .setParameter("userId", userId)
                .setParameter("flagType", flagType.name())
                .setParameter("flagNote", flagNote)
                .executeUpdate();
    }
}
