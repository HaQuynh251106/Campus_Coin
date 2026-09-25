package com.campuscoin.recurring.repository;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls the stored procedure that turns due recurring rules into transactions (UC-09, BR-16).
 *
 * <p>This is the only place {@code sp_post_recurring_transactions} is invoked, and it is the reason
 * this module has a scheduler at all: a rule that never fires would make UC-09 a form that writes a
 * row nobody acts on.
 *
 * <p><b>Why the database does this and not Java.</b> Deciding which periods are due, deriving a
 * period key per frequency, catching up over several missed periods and advancing the cursor are all
 * the procedure's. Reimplementing any of it here would create a second answer to "has this period
 * been posted?" - and the unique key {@code uk_occurrence_rule_period} exists precisely because
 * exactly one answer is allowed (BR-16).
 *
 * <p><b>Why this is safe to run from more than one instance.</b> It is. The procedure inserts an
 * occurrence with {@code INSERT IGNORE} against a unique {@code (rule_id, period_key)} and only
 * creates a transaction when that insert actually inserted a row. Two callers racing on the same
 * period therefore produce one occurrence and one transaction, with the loser skipping ahead to
 * advance the cursor. Nothing here needs a distributed lock, and a lock would be worse than useless
 * - it would be the application claiming a guarantee the database already provides.
 *
 * <p>There is deliberately no {@code @Scheduled} annotation on this class. Keeping the invocation
 * separate from the schedule means the catch-up behaviour can be exercised directly, with an
 * explicit date, without waiting for a timer.
 */
@Repository
public class RecurringProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Posts every occurrence due on or before {@code asOf} (UC-09 A1).
     *
     * <p>The procedure walks each {@code ACTIVE} rule whose category is still in use and whose
     * {@code next_run_date} has arrived, looping over every missed period rather than only the most
     * recent one - which is what makes a rule keep its schedule after the application has been down
     * for a week. It stops at {@code end_date}, advances {@code next_run_date} and
     * {@code last_run_date}, and marks a rule {@code ENDED} once its cursor passes the end.
     *
     * <p>A rule whose category has been retired posts nothing. The procedure joins
     * {@code categories} on {@code is_active = 1} for that reason: without it, retiring a category
     * would leave the scheduler trying to write transactions against it and failing mid-loop.
     *
     * @param asOf the date to post up to, inclusive; {@code null} means the database's own current
     *             date. The scheduled run passes {@code null} on purpose, so "today" is decided by
     *             the {@code +07:00} session clock the views and constraints already use (VĐ-10)
     *             rather than by whichever zone the JVM happens to be in. Tests pass an explicit
     *             date so catch-up can be exercised deterministically.
     */
    @Transactional
    public void postDueOccurrences(LocalDate asOf) {
        entityManager.createNativeQuery("CALL sp_post_recurring_transactions(:asOf)")
                .setParameter("asOf", asOf)
                .executeUpdate();
    }
}
