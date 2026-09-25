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

/**
 * Reads the tips to show, and the months that have any, from {@code v_dashboard_tips} (UC-18).
 *
 * <p><b>Why the view answers this and Java does not.</b> Which tips are visible and in what order is
 * already defined by {@code v_dashboard_tips}: {@code WHERE state <> 'DISMISSED'} is BR-14's "a
 * dismissed tip never comes back", and {@code ORDER BY (state = 'PINNED') DESC, rank_score DESC} is
 * its "a pinned tip leads, then the ones worth most". The dashboard reads the same view, so the tip a
 * student sees second on their home screen and the tip they see second on their tips screen are one
 * computation - which is what stops the two screens disagreeing about a ranking neither of them
 * invented. Re-sorting here, or filtering dismissed rows here, would be a second answer to a
 * question the view answers once.
 *
 * <p><b>The view has no time filter, so the caller's month is what narrows it.</b>
 * {@code v_dashboard_tips} partitions by {@code period_month} for its {@code ROW_NUMBER} but returns
 * every month a student has tips for - the same property {@code DashboardViewDao} has to correct for
 * the dashboard. Every query here names a month, so a tip can never be shown under a heading it does
 * not belong to.
 *
 * <p><b>Projected by alias rather than mapped as an entity.</b> {@code createNativeQuery(..., Tuple)}
 * lets each column be read by the name the query gave it, so a column added to or reordered in the
 * view cannot break this class - the same reason {@code DashboardViewDao}, {@code BudgetConsumptionDao}
 * and {@code ReportViewDao} are written this way. The one column this class writes is not here:
 * {@code state} is changed through {@code UserTipRepository}, because a view cannot be written to.
 *
 * <p>A DAO rather than a Spring Data repository because every query is native and every result is a
 * projection rather than a managed entity.
 */
@Repository
public class TipViewDao {

    /**
     * UC-18: the visible tips for one student and one month, in display order.
     *
     * <p>{@code state <> 'DISMISSED'} is already applied by the view, so this query cannot return a
     * dismissed tip even if one exists for the month.
     *
     * <p><b>The ordering restates the view's rule, and that is not a second ranking.</b> SQL makes no
     * promise about the order of an unordered result, so {@code v_dashboard_tips} returning rows in
     * its window order is not something to rely on. This query names the same two keys the view ranks
     * by - pinned first, then {@code display_order} - and adds {@code tip_id} so two equally-ranked
     * tips cannot swap places between two calls of the same endpoint. It asks for the order rather
     * than re-deriving it: {@code display_order} is read, not recomputed, so the ranking is still the
     * view's.
     */
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

    /**
     * The months a student has visible tips for, newest first.
     *
     * <p>Read from the same view as {@link #findTips}, so the months offered are exactly the months
     * that would return something. A month whose tips have all been dismissed drops out of both - it
     * is not a month with an empty list, it is a month with nothing to show, and offering it would be
     * an empty screen behind a menu entry.
     *
     * <p>{@code DISTINCT} because the view has one row per tip, not per month. Ordered newest first
     * because a picker lists the most recent month where it is read first.
     */
    private static final String SELECT_MONTHS = """
            SELECT DISTINCT v.period_month AS periodMonth
              FROM v_dashboard_tips v
             WHERE v.user_id = :userId
             ORDER BY periodMonth DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-18: the caller's visible tips for one month, already ranked.
     *
     * <p>An empty list is a real answer: a month with no tips, or whose tips were all dismissed,
     * matches no row. The service does not turn that into a {@code 404}, because "you have no tips
     * for September" is a fact about the student's own data rather than a missing resource.
     */
    @Transactional(readOnly = true)
    public List<TipRow> findTips(Long userId, LocalDate periodMonth) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // the result is immediately mapped through toTipRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TIPS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(TipViewDao::toTipRow).toList();
    }

    /**
     * UC-18: the months the caller has tips to show for, newest first.
     *
     * <p>Backs the month picker with the months that would actually return something, rather than an
     * invented range. The current month is not guaranteed to be in the list: a student who has not
     * had tips generated yet has no months at all, and the response says so with an empty array.
     */
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

    /** One row of the tips view as the projection carries it. */
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
