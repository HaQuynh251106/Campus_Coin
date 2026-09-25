package com.campuscoin.recurring.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.recurring.entity.RecurringRule;

/**
 * MySQL access for {@code recurring_rules} (UC-09).
 *
 * <p>Every query here that looks up a single row takes the owning student's id as well as the rule's.
 * There is no method that can fetch a rule by id alone, so no service method can act on another
 * student's row by mistake. BR-02 requires ownership to be enforced server-side, and making it a
 * property of the queries rather than a comparison someone has to remember is the version of that
 * which cannot be forgotten.
 *
 * <p>Nothing here advances {@code next_run_date} or writes {@code last_run_date}. Both belong to
 * {@code sp_post_recurring_transactions}, which is the single writer of the scheduler's cursor; a
 * second writer would be a second opinion on which period comes next, and BR-16's unique key exists
 * to stop two opinions turning into two transactions.
 */
public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    /**
     * Every rule the caller owns, whatever its status, soonest-to-run first.
     *
     * <p>The category is fetched in the same statement. Every list row renders the category's name,
     * icon, colour and type - the last of which is the only thing that says whether the rule posts
     * income or expense (BR-05) - so leaving it lazy would mean one extra query per row, and with
     * {@code open-in-view} false it would be an outright failure rather than a slow success.
     *
     * <p>Retired, paused and ended rules are all included. A paused rule is one the student intends
     * to resume, and hiding it would leave no way to find it again; the response carries
     * {@code status} so the client decides how to show it. There is no status filter, for the same
     * reason the category list has no type filter: the client already knows what it wants to show,
     * and a second way to ask the same question becomes a duplicate endpoint later.
     *
     * <p>Ordered by {@code next_run_date}, then id. Soonest first is the useful order - the rule about
     * to run is the one a student is looking for - and {@code id} makes the order total, since
     * several rules commonly share a date and MySQL may otherwise return equal rows in either order,
     * making the list appear to shuffle between two identical calls.
     */
    @Query("""
            SELECT r FROM RecurringRule r
            JOIN FETCH r.category
             WHERE r.userId = :userId
             ORDER BY r.nextRunDate ASC, r.id ASC
            """)
    List<RecurringRule> findForStudent(@Param("userId") Long userId);

    /**
     * One of the caller's own rules.
     *
     * <p>Returns empty both when the rule does not exist and when it belongs to someone else. The
     * caller cannot tell those apart, which is the point: a response that distinguished them would
     * let a client probe for the existence of other students' rules (section 7.5).
     */
    @Query("""
            SELECT r FROM RecurringRule r
            JOIN FETCH r.category
             WHERE r.id = :id AND r.userId = :userId
            """)
    Optional<RecurringRule> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * As above, with the row locked for the rest of the transaction.
     *
     * <p>Used by the update and delete paths. The lock matters for the same reason it does on
     * {@code Transaction}: a state decision made from a lock-free read can be made twice, and here
     * the row is also written by the scheduler. Holding it means a status change decided here is
     * decided against a cursor the scheduler cannot move underneath the request.
     *
     * <p>Deliberately no {@code JOIN FETCH}. {@code SELECT ... FOR UPDATE} on a join locks rows in
     * every table the join touches, so fetching the category would take a lock on the category row as
     * well - and a category is shared by every rule and transaction filed under it, so two students
     * editing rules in the same category would block each other for no reason. The category is loaded
     * lazily instead, inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM RecurringRule r
             WHERE r.id = :id AND r.userId = :userId
            """)
    Optional<RecurringRule> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                       @Param("userId") Long userId);

    /**
     * How many transactions were generated from this rule - in either soft-delete state.
     *
     * <p><b>This is the one query in the module that reads a table another module owns, and it is
     * here because the database has no guard to lean on.</b> {@code transactions.recurring_rule_id}
     * carries no foreign key by design (see {@code docs/DB_DESIGN.md} section 4.8), so deleting a rule
     * does not cascade and nothing at the schema level notices. What it does leave behind is worse
     * than a broken reference: {@code sp_validate_transaction} refuses an UPDATE whose
     * {@code recurring_rule_id} names a row that does not exist, so every later edit, soft delete or
     * restore of those transactions would be refused with {@code BR-02: recurring rule does not
     * exist}. A real delete is therefore safe only while no transaction points at the rule, and that
     * is the question this answers.
     *
     * <p>Both delete states are counted deliberately. A soft-deleted transaction still carries the
     * pointer and is still restored through the same trigger, so counting only live rows would report
     * a rule as removable and leave the restored row permanently uneditable.
     *
     * <p>The count, not {@code EXISTS}, so the caller can say how many records are involved rather
     * than only that there are some - and so the mapping is a plain number rather than a
     * database-specific boolean projection.
     */
    @Query(value = "SELECT COUNT(*) FROM transactions WHERE recurring_rule_id = :ruleId",
            nativeQuery = true)
    long countTransactionsGeneratedBy(@Param("ruleId") Long ruleId);
}
