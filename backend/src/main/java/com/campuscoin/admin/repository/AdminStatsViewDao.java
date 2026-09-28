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

@Repository
public class AdminStatsViewDao {

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

    @Transactional(readOnly = true)
    public Optional<AdminUsageStats> findUsageStats() {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_USAGE_STATS, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminStatsViewDao::toUsageStats).findFirst();
    }

    @Transactional(readOnly = true)
    public List<AdminTopCategoryRow> findTopCategories() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOP_CATEGORIES, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminStatsViewDao::toTopCategoryRow).toList();
    }

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

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
