package com.campuscoin.budget.repository;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.budget.entity.Budget;

/**
 * MySQL access for {@code budgets} (UC-13).
 *
 * <p>Every query that looks up a single row takes the owning student's id as well as the budget's.
 * There is no method that can fetch a budget by id alone, so no service method can act on another
 * student's row by mistake. BR-02 requires ownership to be enforced server-side, and making it a
 * property of the queries rather than a comparison someone has to remember is the version of that
 * which cannot be forgotten.
 *
 * <p><b>What is deliberately not here: any query that reads {@code transactions}.</b> How much has
 * been spent against a limit is not this interface's business. It is computed once, in
 * {@code v_budget_consumption}, and read through {@link BudgetConsumptionDao}. A second
 * {@code SUM(amount)} over {@code transactions} here would be a second definition of the spent
 * amount, and the two could disagree about whether a soft-deleted row counts (BR-09) or which month
 * a dated row belongs to.
 */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /**
     * One of the caller's own budgets, without locking it.
     *
     * <p>Returns empty both when the budget does not exist and when it belongs to someone else. The
     * caller cannot tell those apart, which is the point: a response distinguishing them would let a
     * client probe for the existence of other students' budgets (section 7.5).
     *
     * <p>The category is <em>not</em> fetched here, and that is deliberate rather than an oversight.
     * No write path reads this method's result through to the category: the response is built from
     * {@link BudgetConsumptionDao}'s projection, which already carries the category's name, icon and
     * colour from the view's own join. Loading the category here would be a query with no reader.
     */
    @Query("""
            SELECT b FROM Budget b
             WHERE b.id = :id AND b.userId = :userId
            """)
    Optional<Budget> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * As above, with the row locked for the rest of the transaction.
     *
     * <p>Used by the update path. The lock matters because the limit is the only field a caller can
     * change, and two concurrent edits made from lock-free reads both succeed with the second
     * silently overwriting the first - "set it to 300" and "set it to 350" arriving together would
     * report success twice and leave one number. Holding the row means the value written is the one
     * decided against the row as it stands, not as it stood when the request began.
     *
     * <p>No {@code JOIN FETCH}, for the reason the recurring-rule repository records:
     * {@code SELECT ... FOR UPDATE} on a join locks rows in every table the join touches, and a
     * category is shared by every budget and transaction filed under it, so fetching it would let
     * two students editing limits in the same category block each other for no reason.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM Budget b
             WHERE b.id = :id AND b.userId = :userId
            """)
    Optional<Budget> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                @Param("userId") Long userId);

    /**
     * The id of an existing limit for this student, category and month, if there is one.
     *
     * <p>BR-11 allows at most one, and {@code uk_budget_user_cat_month} is the guarantee -
     * {@code (user_id, category_id, period_month)} is exactly the unique key. This lookup exists so
     * the caller receives a {@code 409} that names the collision, rather than the generic conflict a
     * refused write produces: a student who sets a limit twice is not making a mistake the database
     * needs to shout about, they are updating a value they already set, and the message should say
     * which endpoint does that.
     *
     * <p>Returns the id rather than a boolean so the message can be specific, and looks it up before
     * the write rather than relying on the constraint, because the constraint's error is
     * indistinguishable from any other integrity failure once it reaches the driver.
     */
    @Query("""
            SELECT b.id FROM Budget b
             WHERE b.userId = :userId
               AND b.category.id = :categoryId
               AND b.periodMonth = :periodMonth
            """)
    Optional<Long> findExistingId(@Param("userId") Long userId,
                                  @Param("categoryId") Long categoryId,
                                  @Param("periodMonth") LocalDate periodMonth);
}
