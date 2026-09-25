package com.campuscoin.budget.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.budget.entity.BudgetConsumption;
import com.campuscoin.budget.entity.ConsumptionStatus;

/**
 * Reads {@code v_budget_consumption} - the budget rows a student sees, with the month's spending
 * already worked out (UC-13).
 *
 * <p><b>Why the database computes this and not Java.</b> The view's own {@code SUM} over live
 * transactions and its {@code CASE} over the two thresholds are not a convenience: they are the same
 * comparison {@code sp_check_budget_alerts} makes when it decides whether to write an alert. If this
 * class recomputed a percentage, the alert log and the screen could disagree - a student shown
 * "on track" while the database had already recorded a warning is precisely the kind of drift that
 * makes a budget feature untrustworthy. Reading the view means there is one definition of "80%" and
 * it is the schema's.
 *
 * <p><b>Projected by alias rather than mapped as an entity.</b> {@code createNativeQuery(..., Tuple)}
 * lets each column be read by the name the query gave it, so the class cannot be broken by a column
 * being added to or reordered in the view, and the {@link BudgetConsumption} record only carries what
 * this module publishes.
 *
 * <p><b>Why the query joins {@code categories} on top of the view.</b> The view publishes the
 * consumption figures but not the category's icon and colour, so those two presentation columns are
 * taken from {@code categories} directly. The join is on the budget's own
 * {@code category_id}, so it adds one row's worth of decoration to the row that was already there -
 * it cannot widen the result or re-scope which student is being reported on, and every figure the
 * response carries still comes from the view. The alternative, adding the two columns to the view,
 * would change the database to suit a DTO (section 4); this keeps the schema as it stands.
 *
 * <p>This is a DAO rather than a Spring Data repository because the query is native and its result is
 * a projection rather than a managed entity - the same reason {@code RecurringProcedureDao} and
 * {@code TransactionProcedureDao} exist.
 */
@Repository
public class BudgetConsumptionDao {

    /**
     * The view, narrowed to one student and one month.
     *
     * <p>The month is matched on {@code period_month}, which is a {@code DATE} pinned to the first of
     * the month, so the parameter must already be a first-of-month date. The service produces it from
     * the client's {@code yyyy-MM}, and the view's own join is on the same value - so a budget for
     * September is compared against September's transactions and nothing else.
     *
     * <p>Ordered by category name then id. Name is what a student scans by, and the id makes the
     * order total: two categories may legitimately share a name across different types, and without
     * a tie-break MySQL could return them in either order, making the list appear to shuffle between
     * two identical calls.
     */
    private static final String SELECT_BY_MONTH = """
            SELECT v.budget_id          AS budgetId,
                   v.category_id        AS categoryId,
                   v.category_name      AS categoryName,
                   c.icon               AS categoryIcon,
                   c.color              AS categoryColor,
                   v.period_month       AS periodMonth,
                   v.limit_amount       AS limitAmount,
                   v.spent_amount       AS spentAmount,
                   v.remaining_amount   AS remainingAmount,
                   v.consumed_pct       AS consumedPct,
                   v.consumption_status AS consumptionStatus
              FROM v_budget_consumption v
              JOIN categories c ON c.id = v.category_id
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
             ORDER BY v.category_name ASC, v.budget_id ASC
            """;

    /** The same projection for a single budget, still scoped to the owner. */
    private static final String SELECT_ONE = """
            SELECT v.budget_id          AS budgetId,
                   v.category_id        AS categoryId,
                   v.category_name      AS categoryName,
                   c.icon               AS categoryIcon,
                   c.color              AS categoryColor,
                   v.period_month       AS periodMonth,
                   v.limit_amount       AS limitAmount,
                   v.spent_amount       AS spentAmount,
                   v.remaining_amount   AS remainingAmount,
                   v.consumed_pct       AS consumedPct,
                   v.consumption_status AS consumptionStatus
              FROM v_budget_consumption v
              JOIN categories c ON c.id = v.category_id
             WHERE v.budget_id = :budgetId
               AND v.user_id = :userId
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-13: the caller's limits for one month, with that month's spending.
     *
     * <p>A month with no budgets is an empty list rather than an error - the endpoint answers "what
     * have I set?", and "nothing" is a valid answer to it.
     */
    @Transactional(readOnly = true)
    public List<BudgetConsumption> findByUserAndMonth(Long userId, LocalDate periodMonth) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration
        // - the result is immediately mapped through toConsumption, so no Tuple escapes this method
        // and nothing downstream depends on the cast having been right.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BY_MONTH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(BudgetConsumptionDao::toConsumption).toList();
    }

    /**
     * UC-13: one of the caller's own budgets with its consumption.
     *
     * <p>Returns empty for a budget that does not exist and for one belonging to another student
     * alike, so the caller cannot tell those apart (section 7.5).
     */
    @Transactional(readOnly = true)
    public Optional<BudgetConsumption> findOne(Long userId, Long budgetId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("budgetId", budgetId)
                .getResultList();

        return rows.stream().map(BudgetConsumptionDao::toConsumption).findFirst();
    }

    /**
     * Reads one projected row.
     *
     * <p>{@code consumptionStatus} is read as its name and parsed rather than cast: the view returns
     * the {@code CASE}'s text, and parsing it means a value outside the enum fails loudly here rather
     * than arriving at a client that silently matched no branch.
     */
    private static BudgetConsumption toConsumption(Tuple row) {
        return new BudgetConsumption(
                row.get("budgetId", Long.class),
                row.get("categoryId", Long.class),
                row.get("categoryName", String.class),
                row.get("categoryIcon", String.class),
                row.get("categoryColor", String.class),
                row.get("periodMonth", java.sql.Date.class) == null
                        ? null
                        : row.get("periodMonth", java.sql.Date.class).toLocalDate(),
                row.get("limitAmount", BigDecimal.class),
                row.get("spentAmount", BigDecimal.class),
                row.get("remainingAmount", BigDecimal.class),
                row.get("consumedPct", BigDecimal.class),
                ConsumptionStatus.valueOf(row.get("consumptionStatus", String.class)));
    }
}
