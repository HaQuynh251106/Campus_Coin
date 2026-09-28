package com.campuscoin.forecast.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.forecast.entity.MonthTotals;

@Repository
public class ForecastViewDao {

    private static final String SELECT_COMPLETED_MONTHS = """
            SELECT v.period_month  AS periodMonth,
                   v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month < :currentMonth
             ORDER BY v.period_month DESC
             LIMIT :windowMonths
            """;

    private static final String SELECT_CURRENT_MONTH = """
            SELECT v.total_income  AS totalIncome,
                   v.total_expense AS totalExpense
              FROM v_monthly_income_expense v
             WHERE v.user_id = :userId
               AND v.period_month = :currentMonth
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<MonthTotals> findCompletedMonths(Long userId, LocalDate currentMonth, int windowMonths) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_COMPLETED_MONTHS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("currentMonth", Date.valueOf(currentMonth))
                .setParameter("windowMonths", windowMonths)
                .getResultList();

        List<MonthTotals> months = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            months.add(toMonthTotals(row, row.get("periodMonth", Date.class).toLocalDate()));
        }
        Collections.reverse(months);

        return months;
    }

    @Transactional(readOnly = true)
    public Optional<MonthTotals> findCurrentMonth(Long userId, LocalDate currentMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CURRENT_MONTH, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("currentMonth", Date.valueOf(currentMonth))
                .getResultList();

        return rows.stream().map(row -> toMonthTotals(row, currentMonth)).findFirst();
    }

    private static MonthTotals toMonthTotals(Tuple row, LocalDate periodMonth) {
        return new MonthTotals(
                periodMonth,
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class));
    }
}
