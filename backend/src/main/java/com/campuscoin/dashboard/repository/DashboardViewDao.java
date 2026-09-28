package com.campuscoin.dashboard.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.dashboard.entity.AnnouncementSeverity;
import com.campuscoin.dashboard.entity.DashboardAnnouncement;
import com.campuscoin.dashboard.entity.DashboardSummary;
import com.campuscoin.dashboard.entity.DashboardTip;
import com.campuscoin.dashboard.entity.DashboardTopCategory;
import com.campuscoin.dashboard.entity.TipState;

@Repository
public class DashboardViewDao {

    private static final String SELECT_SUMMARY = """
            SELECT v.period_month              AS periodMonth,
                   v.currency                  AS currency,
                   v.total_income              AS totalIncome,
                   v.total_expense             AS totalExpense,
                   v.net_amount                AS netAmount,
                   v.monthly_allowance_baseline AS monthlyAllowanceBaseline,
                   v.monthly_savings_goal      AS monthlySavingsGoal,
                   v.savings_goal_pct          AS savingsGoalPct
              FROM v_dashboard_summary v
             WHERE v.user_id = :userId
            """;

    private static final String SELECT_TOP_CATEGORY = """
            SELECT v.category_id   AS categoryId,
                   v.category_name AS categoryName,
                   c.icon          AS categoryIcon,
                   c.color         AS categoryColor,
                   v.total_amount  AS totalAmount
              FROM v_top_category_current_month v
              JOIN categories c ON c.id = v.category_id
             WHERE v.user_id = :userId
            """;

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

    private static final String SELECT_TIPS_LIMITED = """
            SELECT t.tipId           AS tipId,
                   t.categoryId      AS categoryId,
                   t.title           AS title,
                   t.body            AS body,
                   t.potentialSaving AS potentialSaving,
                   t.state           AS state
              FROM (
                    SELECT v.tip_id           AS tipId,
                           v.category_id      AS categoryId,
                           v.title            AS title,
                           v.body             AS body,
                           v.potential_saving AS potentialSaving,
                           v.state            AS state,
                           v.display_order    AS displayOrder
                      FROM v_dashboard_tips v
                     WHERE v.user_id = :userId
                       AND v.period_month = :periodMonth
                     ORDER BY (v.state = 'PINNED') DESC, v.display_order ASC, v.tip_id ASC
                     LIMIT :maxTips
                   ) t
             ORDER BY (t.state = 'PINNED') DESC, t.displayOrder ASC, t.tipId ASC
            """;

    private static final String SELECT_ANNOUNCEMENTS = """
            SELECT a.id         AS id,
                   a.title      AS title,
                   a.body       AS body,
                   a.severity   AS severity,
                   a.starts_at  AS startsAt,
                   a.ends_at    AS endsAt
              FROM v_active_announcements a
             WHERE a.audience IN ('ALL', 'STUDENTS')
             ORDER BY a.starts_at DESC, a.id DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<DashboardSummary> findSummary(Long userId) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_SUMMARY, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toSummary).findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<DashboardTopCategory> findTopCategory(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOP_CATEGORY, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toTopCategory).findFirst();
    }

    @Transactional(readOnly = true)
    public List<DashboardTip> findTips(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TIPS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toTip).toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardTip> findTipsLimited(Long userId, LocalDate periodMonth, int maxTips) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TIPS_LIMITED, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .setParameter("maxTips", maxTips)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toTip).toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardAnnouncement> findAnnouncementsForStudent() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ANNOUNCEMENTS, Tuple.class)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toAnnouncement).toList();
    }

    private static DashboardSummary toSummary(Tuple row) {
        return new DashboardSummary(
                row.get("periodMonth", java.sql.Date.class) == null
                        ? null
                        : row.get("periodMonth", java.sql.Date.class).toLocalDate(),
                row.get("currency", String.class),
                row.get("totalIncome", java.math.BigDecimal.class),
                row.get("totalExpense", java.math.BigDecimal.class),
                row.get("netAmount", java.math.BigDecimal.class),
                row.get("monthlyAllowanceBaseline", java.math.BigDecimal.class),
                row.get("monthlySavingsGoal", java.math.BigDecimal.class),
                row.get("savingsGoalPct", java.math.BigDecimal.class));
    }

    private static DashboardTopCategory toTopCategory(Tuple row) {
        return new DashboardTopCategory(
                row.get("categoryId", Long.class),
                row.get("categoryName", String.class),
                row.get("categoryIcon", String.class),
                row.get("categoryColor", String.class),
                row.get("totalAmount", java.math.BigDecimal.class));
    }

    private static DashboardTip toTip(Tuple row) {
        return new DashboardTip(
                row.get("tipId", Long.class),
                row.get("categoryId", Long.class),
                row.get("title", String.class),
                row.get("body", String.class),
                row.get("potentialSaving", java.math.BigDecimal.class),
                TipState.valueOf(row.get("state", String.class)));
    }

    private static DashboardAnnouncement toAnnouncement(Tuple row) {
        return new DashboardAnnouncement(
                row.get("id", Long.class),
                row.get("title", String.class),
                row.get("body", String.class),
                AnnouncementSeverity.valueOf(row.get("severity", String.class)),
                toLocalDateTime(row.get("startsAt", java.sql.Timestamp.class)),
                toLocalDateTime(row.get("endsAt", java.sql.Timestamp.class)));
    }

    private static java.time.LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
