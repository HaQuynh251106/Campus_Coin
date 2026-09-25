package com.campuscoin.tips.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.tips.entity.UserTip;

import jakarta.persistence.LockModeType;

/**
 * MySQL access for {@code user_tips} (UC-18).
 *
 * <p><b>Reads, and the one state write.</b> No method here inserts a tip: every row is produced by
 * {@code sp_generate_tips}, reached through {@code TipGenerationDao}, and the fields that procedure
 * computes have no setter on the entity. What this repository loads is the row whose state is about
 * to change, so the change can be made through the entity and flushed - the arrangement the
 * recurring-rule module uses for a rule's {@code status}.
 *
 * <p>Every query takes the owning student's id. There is no method that can load a tip by id alone,
 * so no service method can act on another student's tip by mistake. BR-02 requires ownership
 * server-side, and making it a property of the query rather than a check at the call site is the
 * version that cannot be forgotten - a tip's title and body are readable prose about the student's
 * own spending, so a missing filter leaks a sentence rather than a number.
 */
public interface UserTipRepository extends JpaRepository<UserTip, Long> {

    /**
     * One of the caller's own tips, for a state change, locked for the rest of the transaction.
     *
     * <p>Returns empty both when the tip does not exist and when it belongs to someone else - the two
     * are indistinguishable from the outside, on purpose (section 7.5). Loaded as a managed entity,
     * not projected, because the caller intends to change it and Hibernate has to be able to see the
     * change on flush.
     *
     * <p><b>This is the ownership check for every tip action.</b> The subsequent write is a plain
     * Hibernate update with no ownership predicate of its own - the schema provides no procedure that
     * could fold one in - so a tip is reachable only through a query that already named the caller.
     *
     * <p><b>Why the row is locked rather than merely read.</b> A state change is decided from the
     * state the row holds: pin, dismiss and clear are all answers to "what is it now", and dismissal
     * is refused once the row is {@code DISMISSED}. Two decisions taken from lock-free reads can both
     * be taken - a pin and a dismiss could each see {@code NEW}, each conclude it is allowed, and the
     * later write silently overwrite the earlier one, so a request answered {@code 200 PINNED} leaves
     * a dismissed row. Holding the row for the transaction makes the second request wait and then
     * decide against the state the first actually wrote. This is the arrangement
     * {@code RecurringRuleService} uses for a rule's status, and it matters more here than a lost
     * update normally would because the last writer's decision is not equivalent to the first: one of
     * the two states is terminal.
     *
     * <p>Nothing else writes this row except the generator, which only inserts rows that do not exist
     * yet - so the lock is not contending with the scheduled run, only with another request from the
     * same student.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM UserTip t
             WHERE t.id = :id AND t.userId = :userId
            """)
    Optional<UserTip> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * How many tips the caller has for one month, in any state.
     *
     * <p>Distinct from the visible count {@code v_dashboard_tips} would give: this counts dismissed
     * tips too, which is what tells "this month was never generated" apart from "this month's tips
     * were all dismissed". The two lead to different answers when a month is asked for, and only one
     * of them is a month that has anything to show.
     */
    @Query("""
            SELECT COUNT(t) FROM UserTip t
             WHERE t.userId = :userId AND t.periodMonth = :periodMonth
            """)
    long countForMonth(@Param("userId") Long userId, @Param("periodMonth") LocalDate periodMonth);
}
