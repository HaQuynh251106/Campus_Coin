package com.campuscoin.tips.repository;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.tips.entity.TipRow;
import com.campuscoin.tips.entity.TipState;

@Repository
public class TipViewDao {

    private static final String SELECT_TIPS = """
            SELECT v.tip_id           AS tipId,
                   v.category_id      AS categoryId,
                   v.title            AS title,
                   v.body             AS body,
                   v.potential_saving AS potentialSaving,
                   v.state            AS state
              FROM v_dashboard_tips v
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
             ORDER BY (v.state = 'PINNED') DESC, v.display_order ASC, v.tip_id ASC
            """;

    private static final String SELECT_MONTHS = """
            SELECT DISTINCT v.period_month AS periodMonth
              FROM v_dashboard_tips v
             WHERE v.user_id = :userId
             ORDER BY periodMonth DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<TipRow> findTips(Long userId, LocalDate periodMonth) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TIPS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(TipViewDao::toTipRow).toList();
    }

    @Transactional(readOnly = true)
    public List<LocalDate> findMonthsWithTips(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_MONTHS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream()
                .map(row -> row.get("periodMonth", java.sql.Date.class).toLocalDate())
                .toList();
    }

    private static TipRow toTipRow(Tuple row) {
        return new TipRow(
                ((Number) row.get("tipId")).longValue(),
                row.get("categoryId") == null ? null : ((Number) row.get("categoryId")).longValue(),
                row.get("title", String.class),
                row.get("body", String.class),
                row.get("potentialSaving", java.math.BigDecimal.class),
                TipState.valueOf(row.get("state", String.class)));
    }
}
