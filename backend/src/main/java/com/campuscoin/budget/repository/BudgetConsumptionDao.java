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

@Repository
public class BudgetConsumptionDao {

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

    @Transactional(readOnly = true)
    public List<BudgetConsumption> findByUserAndMonth(Long userId, LocalDate periodMonth) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BY_MONTH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(BudgetConsumptionDao::toConsumption).toList();
    }

    @Transactional(readOnly = true)
    public Optional<BudgetConsumption> findOne(Long userId, Long budgetId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("budgetId", budgetId)
                .getResultList();

        return rows.stream().map(BudgetConsumptionDao::toConsumption).findFirst();
    }

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
