package com.campuscoin.admin.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.AdminTopCategoryRow;
import com.campuscoin.admin.entity.AdminUsageStats;
import com.campuscoin.category.entity.CategoryType;

/**
 * Reads the two administration figures views (UC-23).
 *
 * <p>Two views, two methods, because they are two shapes: {@code v_admin_usage_stats} is one row of
 * scalar aggregates, {@code v_admin_top_categories} is a ranked list including categories nobody has
 * used. Neither is derived from the other and combining them would mean nesting a list inside a scalar
 * row or dropping the category identifier.
 *
 * <p><b>Both money figures are {@code SUM(amount)} over plaintext transaction amounts.</b> That is a
 * recorded decision, not an oversight: amounts are the one sensitive field the encryption pass left in
 * the clear because every reporting, budgeting and tip rule sums them in SQL, and re-deriving those
 * aggregates in the application was out of scope (OB-013). Nothing this module can do changes that, so
 * it serves the figures and records the exposure rather than hiding it. The property that keeps it
 * defensible is that every money value here is an aggregate across the whole system: no route in this
 * module returns one student's amount.
 */
@Repository
public class AdminStatsViewDao {

    /** UC-23: the single aggregate row. Every column the view computes is selected. */
    private static final String SELECT_USAGE_STATS = """
            SELECT v.total_students           AS totalStudents,
                   v.active_students          AS activeStudents,
                   v.disabled_students        AS disabledStudents,
                   v.active_users_30d         AS activeUsers30d,
                   v.total_transactions       AS totalTransactions,
                   v.total_expense_logged     AS totalExpenseLogged,
                   v.total_income_logged      AS totalIncomeLogged,
                   v.total_budgets            AS totalBudgets,
                   v.total_tips_generated     AS totalTipsGenerated,
                   v.total_insights_generated AS totalInsightsGenerated
              FROM v_admin_usage_stats v
            """;

    /**
     * UC-23: every category, ranked by use.
     *
     * <p><b>The {@code ORDER BY} is this class's addition, and without it the ranking would not be a
     * ranking.</b> The view defines no order at all, and MySQL is free to return the grouped rows in
     * any sequence - so two identical calls could hand back two different lists, and a client that
     * cached or diffed them would see movement that never happened. The order here is
     * {@code txn_count DESC, total_amount DESC, category_id ASC}: most-used first, then largest total,
     * and the id last so the order is total and a tie cannot swap.
     *
     * <p><b>No {@code LIMIT}.</b> The view is a {@code LEFT JOIN} from {@code categories} for the
     * express purpose of including rows nobody has used yet - a zero count is a real answer about a
     * category that exists, and UC-23's usage report is exactly where an unused category needs to be
     * visible. Truncating the list would turn "this category is unused" into "this category is absent".
     */
    private static final String SELECT_TOP_CATEGORIES = """
            SELECT v.category_id   AS categoryId,
                   v.category_name AS categoryName,
                   v.type          AS type,
                   v.scope         AS scope,
                   v.txn_count     AS txnCount,
                   v.total_amount  AS totalAmount,
                   v.distinct_users AS distinctUsers
              FROM v_admin_top_categories v
             ORDER BY v.txn_count DESC, v.total_amount DESC, v.category_id ASC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The system-wide usage figures (UC-23).
     *
     * <p>Returns an {@link Optional} although the view always yields exactly one row - it is an
     * unfiltered aggregate scan, so it cannot return zero. Empty is therefore a "this cannot happen"
     * case, and the service reports it as a server-side fault rather than inventing a row of zeroes,
     * because a zero row would be indistinguishable from a genuinely empty system.
     */
    @Transactional(readOnly = true)
    public Optional<AdminUsageStats> findUsageStats() {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload returns a raw `Query`. Confined to this local
        // declaration - the row is mapped through toUsageStats, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_USAGE_STATS, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminStatsViewDao::toUsageStats).findFirst();
    }

    /**
     * Every category with its system-wide usage, most-used first (UC-23).
     *
     * <p>An empty list cannot happen against the real schema - the seeded default categories are
     * always present - but it is a list rather than a missing resource, so the service does not turn it
     * into a {@code 404}.
     */
    @Transactional(readOnly = true)
    public List<AdminTopCategoryRow> findTopCategories() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOP_CATEGORIES, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminStatsViewDao::toTopCategoryRow).toList();
    }

    /**
     * The aggregate row as {@link AdminUsageStats} carries it.
     *
     * <p>Every count is read through {@code Number}: {@code COUNT()} is a {@code BIGINT} in MySQL and
     * the driver's Java type for it is not this class's business. The two money columns are
     * {@code BigDecimal} - the view wraps each {@code SUM} in {@code IFNULL(..., 0)}, so neither is
     * ever null and neither is read as nullable.
     */
    private static AdminUsageStats toUsageStats(Tuple row) {
        return new AdminUsageStats(
                toLong(row.get("totalStudents")),
                toLong(row.get("activeStudents")),
                toLong(row.get("disabledStudents")),
                toLong(row.get("activeUsers30d")),
                toLong(row.get("totalTransactions")),
                row.get("totalExpenseLogged", BigDecimal.class),
                row.get("totalIncomeLogged", BigDecimal.class),
                toLong(row.get("totalBudgets")),
                toLong(row.get("totalTipsGenerated")),
                toLong(row.get("totalInsightsGenerated")));
    }

    /**
     * One ranking row as the projection carries it.
     *
     * <p>{@code scope} is read as the plain string the view produces - {@code 'DEFAULT'} or
     * {@code 'PERSONAL'} - and deliberately not resolved to an enum: it is derived from whether
     * {@code user_id} is null rather than being a column of {@code categories}, and inventing a Java
     * enum for two computed values would be a second vocabulary beside the schema's own.
     * {@code type} <em>is</em> a column, so it uses the shared {@code CategoryType} and a value the
     * enum does not know about fails here rather than being read as some other member.
     */
    private static AdminTopCategoryRow toTopCategoryRow(Tuple row) {
        return new AdminTopCategoryRow(
                ((Number) row.get("categoryId")).longValue(),
                row.get("categoryName", String.class),
                CategoryType.valueOf(row.get("type", String.class)),
                row.get("scope", String.class),
                toLong(row.get("txnCount")),
                row.get("totalAmount", BigDecimal.class),
                toLong(row.get("distinctUsers")));
    }

    /** A MySQL count as a {@link Long}; null only if the view's {@code IFNULL} was bypassed. */
    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
