package com.campuscoin.reports.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.reports.entity.CategoryBreakdownRow;
import com.campuscoin.reports.entity.MonthlyTrendPoint;
import com.campuscoin.reports.entity.ReportTotals;
import com.campuscoin.reports.entity.SpendingPoint;

@Repository
public class ReportViewDao {

    private static final String SELECT_TOTALS = """
            SELECT v.period_month AS periodMonth,
                   v.total_income AS totalIncome,
                   v.total_expense AS totalExpense,
                   v.net_amount   AS netAmount,
                   v.txn_count    AS transactionCount
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
            """;

    private static final String SELECT_CATEGORY_TOTALS = """
            SELECT v.category_id    AS categoryId,
                   v.category_name  AS categoryName,
                   c.icon           AS categoryIcon,
                   c.color          AS categoryColor,
                   v.type           AS categoryType,
                   v.total_amount   AS totalAmount,
                   v.txn_count      AS transactionCount
              FROM v_category_month_totals v
              JOIN categories c ON c.id = v.category_id
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
             ORDER BY v.total_amount DESC, v.category_id ASC
            """;

    private static final String SELECT_TREND = """
            SELECT v.period_month  AS periodMonth,
                   v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense,
                   v.net_amount    AS netAmount
              FROM v_monthly_income_expense_6m v
             WHERE v.user_id = :userId
             ORDER BY v.period_month ASC
            """;

    private static final String SELECT_DAILY = """
            SELECT v.txn_date      AS intervalStart,
                   v.txn_date      AS intervalEnd,
                   v.total_expense AS totalExpense,
                   v.txn_count     AS transactionCount
              FROM v_daily_spending_current_month v
             WHERE v.user_id = :userId
               AND v.txn_date >= :from
               AND v.txn_date <= :to
             ORDER BY v.txn_date ASC
            """;

    private static final String SELECT_WEEKLY = """
            SELECT v.week_start    AS intervalStart,
                   v.week_end      AS intervalEnd,
                   v.total_expense AS totalExpense,
                   v.txn_count     AS transactionCount
              FROM v_weekly_spending_current_month v
             WHERE v.user_id = :userId
               AND v.week_start <= :to
               AND v.week_end   >= :from
             ORDER BY v.week_start ASC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<ReportTotals> findTotals(Long userId, LocalDate periodMonth) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOTALS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(ReportViewDao::toTotals).findFirst();
    }

    @Transactional(readOnly = true)
    public List<CategoryBreakdownRow> findCategoryTotals(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CATEGORY_TOTALS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(ReportViewDao::toCategoryRow).toList();
    }

    @Transactional(readOnly = true)
    public List<MonthlyTrendPoint> findSixMonthTrend(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TREND, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(ReportViewDao::toTrendPoint).toList();
    }

    @Transactional(readOnly = true)
    public List<SpendingPoint> findDailySpending(Long userId, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_DAILY, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream().map(ReportViewDao::toSpendingPoint).toList();
    }

    @Transactional(readOnly = true)
    public List<SpendingPoint> findWeeklySpending(Long userId, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_WEEKLY, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream().map(ReportViewDao::toSpendingPoint).toList();
    }

    private static ReportTotals toTotals(Tuple row) {
        return new ReportTotals(
                toLocalDate(row.get("periodMonth", java.sql.Date.class)),
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class),
                row.get("netAmount", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    private static CategoryBreakdownRow toCategoryRow(Tuple row) {
        return new CategoryBreakdownRow(
                row.get("categoryId", Long.class),
                row.get("categoryName", String.class),
                row.get("categoryIcon", String.class),
                row.get("categoryColor", String.class),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("totalAmount", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    private static MonthlyTrendPoint toTrendPoint(Tuple row) {
        return new MonthlyTrendPoint(
                toLocalDate(row.get("periodMonth", java.sql.Date.class)),
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class),
                row.get("netAmount", BigDecimal.class));
    }

    private static SpendingPoint toSpendingPoint(Tuple row) {
        return new SpendingPoint(
                toLocalDate(row.get("intervalStart", java.sql.Date.class)),
                toLocalDate(row.get("intervalEnd", java.sql.Date.class)),
                row.get("totalExpense", BigDecimal.class),
                row.get("transactionCount", Long.class));
    }

    private static LocalDate toLocalDate(java.sql.Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
