package com.campuscoin.budget.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

import com.campuscoin.category.entity.Category;

/**
 * A row of {@code budgets}: one spending limit for one student, one expense category and one month
 * (UC-13, BR-11).
 *
 * <p><b>This entity carries the limit and nothing else this module publishes.</b> How much has been
 * spent, what percentage that is and whether it counts as on track, near or exceeded are
 * <em>derived</em> values, and the database already derives them in {@code v_budget_consumption}.
 * They are read from that view through {@code BudgetRepository} rather than recomputed here, so the
 * API and the database cannot disagree about what "80% consumed" means - the arithmetic lives in
 * exactly one place, and it is the one the alert trigger also uses.
 *
 * <p><b>{@code period_month} is a {@code DATE} that is always the first of a month.</b>
 * {@code ck_budget_month} enforces {@code DAYOFMONTH(period_month) = 1}. The API presents it as a
 * {@code yyyy-MM} string, because the unit a student sets a limit for is a month and the day
 * carries no information; the translation between the two happens in {@code BudgetMapper} and
 * {@code BudgetService}, never in the client.
 *
 * <p><b>{@code @DynamicUpdate} is the correctness requirement it is on every other editable row
 * here.</b> A request changes one column - {@code limit_amount} - and a plain Hibernate
 * {@code UPDATE} would write every mapped column back with the values read when the request began.
 * That would let a concurrent edit to the same row be silently reverted. {@code @DynamicInsert} is
 * not applied: the INSERT must name every column, which is why the factory sets all of them.
 *
 * <p><b>Columns deliberately left unmapped.</b> {@code created_at} and {@code updated_at} are the
 * database's bookkeeping and no use case shows them, matching every other entity in the project.
 */
@Entity
@Table(name = "budgets")
@DynamicUpdate
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning student.
     *
     * <p>A plain identifier rather than an association, as on {@code Transaction} and
     * {@code RecurringRule}: nothing here needs the owner's row, and a {@code @ManyToOne} would load
     * a full {@code User} - including its password hash and token version - to answer a question
     * about ownership. Ownership is enforced in the query instead: every lookup takes the caller's
     * id alongside the budget's.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The expense category the limit applies to.
     *
     * <p>An association because the category supplies the name, icon and colour every budget row
     * renders. {@code FetchType.LAZY} is explicit - {@code @ManyToOne} defaults to EAGER - and the
     * queries that need it say so, because {@code spring.jpa.open-in-view} is false.
     *
     * <p>Only an expense category can be here: {@code trg_budgets_before_insert} calls
     * {@code sp_validate_budget}, which raises {@code BR-11} for an income category.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** The first day of the month this limit covers. {@code ck_budget_month} pins the day to 1. */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    /**
     * The limit itself. {@code ck_budget_limit} requires it to be positive.
     *
     * <p>The DTO refuses zero before the write, so the constraint is a second line rather than the
     * first. It is not redundant: it holds for hand-run statements too, which is the project's rule
     * for any invariant that does not depend on identity.
     */
    @Column(name = "limit_amount", nullable = false)
    private BigDecimal limitAmount;

    protected Budget() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    /**
     * UC-13: change the limit for this month.
     *
     * <p>The only writable field. The other three columns are the row's identity - the student, the
     * category and the month - and the unique key {@code uk_budget_user_cat_month} is built from
     * exactly them (BR-11). Changing one would not be an edit of this budget but a request for a
     * different one, which is what a delete followed by a create already expresses; a writable
     * {@code categoryId} here would additionally be a second way to move a limit onto an income
     * category, bypassing the BR-11 check the insert trigger makes.
     */
    public void setLimitAmount(BigDecimal limitAmount) {
        this.limitAmount = limitAmount;
    }

    /**
     * Builds a limit owned by one student, ready for the insert trigger to validate.
     *
     * <p>{@code user_id} is fixed here rather than left to a caller: the owner comes from the
     * bearer token and nowhere else (BR-02). Whether the category is one the student may use, is an
     * expense category and is still active (BR-11, BR-02, BR-07) is decided by
     * {@code sp_validate_budget} through {@code trg_budgets_before_insert} - the service asks the
     * same questions first, but only so the caller receives an error naming the field.
     *
     * @param userId      the owning student, never null
     * @param category    an active expense category the student may use, pre-checked by the service
     * @param periodMonth the first day of the month the limit covers
     * @param limitAmount strictly positive
     */
    public static Budget newBudget(Long userId,
                                   Category category,
                                   LocalDate periodMonth,
                                   BigDecimal limitAmount) {
        Budget budget = new Budget();
        budget.userId = userId;
        budget.category = category;
        budget.periodMonth = periodMonth;
        budget.limitAmount = limitAmount;
        return budget;
    }
}
