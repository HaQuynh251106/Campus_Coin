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

/**
 * Reads the four UC-12 views (UC-12).
 *
 * <p><b>Why the database answers this, not Java.</b> Each of the four views is the definition of one
 * figure on the dashboard, and each definition is shared with something else that must agree with
 * it. {@code v_dashboard_summary} computes the month's totals the same way
 * {@code v_monthly_income_expense_6m} computes six months of them; {@code v_top_category_current_month}
 * wraps {@code v_category_month_totals}, which is also the reports module's pie-chart source;
 * {@code v_dashboard_tips} applies BR-14's pinned-first ordering that UC-18 will be judged against.
 * Re-deriving any of them here would create a second answer to a question the schema has already
 * answered once - and the two would eventually disagree about a month boundary or a deleted
 * transaction (BR-09), which is precisely the kind of drift a dashboard must not have.

 * <p><b>Four queries, one per view, rather than a single statement joining them.</b> The views are
 * not all keyed the same way: the summary is one row per student, the top category at most one, the
 * tips many, and the announcements not per-student at all. A join would multiply the summary's row by
 * the number of tips and require the totals to be {@code DISTINCT}-ed or re-aggregated - undoing the
 * guarantee that the figure comes from the view unaltered. Four reads of four indexed views is the
 * cheaper and clearer arrangement, and it is why this class is a DAO with several methods rather than
 * one query.

 * <p><b>Projected by alias rather than mapped as entities.</b> {@code createNativeQuery(..., Tuple)}
 * lets each column be read by the name the query gave it, so a column added to or reordered in a view
 * cannot break this class, and the records only carry what this module publishes - the same reason
 * {@code BudgetConsumptionDao} is written this way.
 *
 * <p>A DAO rather than a Spring Data repository because every query is native and every result is a
 * projection rather than a managed entity.
 */
@Repository
public class DashboardViewDao {

    /**
     * UC-12 B1: the caller's current-month totals, their goal progress and their currency.
     *
     * <p>The view carries every student in one row each, so the caller's id is what narrows it to one.
     * {@code period_month} is read back out rather than assumed: it is {@code CURDATE()} as the
     * <em>database session</em> sees it, and it is the value the other three queries are scoped to, so
     * reading it is what keeps the four parts of the response describing one month.
     */
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

    /**
     * UC-12 B2: the caller's highest-spending expense category this month, or nothing.
     *
     * <p>The view already restricts itself to the current month and to {@code rn = 1}, so this only
     * narrows to the caller. The join to {@code categories} adds the two presentation columns the view
     * does not publish; it is on the row's own {@code category_id}, so one row in, one row out.
     */
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

    /**
     * UC-12 B3: the caller's tips for one month, in the order the view ranked them.
     *
     * <p><b>The month is filtered here, and it must be.</b> {@code v_dashboard_tips} has no time
     * filter at all - it partitions by {@code period_month} for its {@code ROW_NUMBER} but returns
     * every month a student has tips for. That is harmless for the view, which is also UC-18's source
     * for the tips screen, but it would be wrong here: a dashboard shows one month's tips, and
     * without this predicate a student would see January's tip under September's heading. The
     * predicate is what makes the view's per-month partition mean what the dashboard reads it to mean.
     *
     * <p>The order is restated rather than relied upon. SQL makes no promise that a view's
     * {@code ROW_NUMBER} ordering survives into the selecting statement's result order, so the same
     * two keys the view ranks by are named here - pinned first, then the score - with the tip id as a
     * final tie-break so two equally-scored tips cannot swap places between two calls. This is not a
     * second definition of the ranking: it asks for the order the view already computes.
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
     * UC-12 B3: the announcements currently live and meant for a student.
     *
     * <p><b>The audience filter is applied here because the view does not apply it.</b>
     * {@code v_active_announcements} answers "is this notice within its window", not "is this notice
     * for this reader", and {@code announcements.audience} has three values -
     * {@code ALL}, {@code STUDENTS} and {@code ADMINS}. A student's dashboard admits the first two and
     * refuses the third. Leaving the filter out would show a notice written for administrators on
     * every student's dashboard, which is a disclosure rather than a cosmetic slip. An administrator
     * is refused this whole endpoint by {@code SecurityConfig}, so there is no mirror-image case to
     * handle here.
     *
     * <p>Ordered newest first, because the view has no {@code ORDER BY} of its own and a dashboard
     * shows the most recent notice where it is looked at; the id breaks ties between two notices
     * posted in the same second. {@code created_by} is not selected - the API does not publish an
     * author's identity.
     */
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

    /**
     * UC-12 B1: the caller's dashboard summary, or empty for an account the view does not cover.
     *
     * <p>The view restricts itself to {@code role = 'STUDENT'}, so an administrator id simply matches
     * no row. That is not reachable through the API - the endpoint requires the student role - but the
     * DAO does not assume it, and returning empty lets the caller answer honestly rather than
     * fabricate a month.
     */
    @Transactional(readOnly = true)
    public Optional<DashboardSummary> findSummary(Long userId) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // the result is immediately mapped through toSummary, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_SUMMARY, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toSummary).findFirst();
    }

    /**
     * UC-12 B2: the caller's top expense category this month, absent when they have spent nothing.
     *
     * <p>Empty is the ordinary answer for a new student, not a failure: the view selects a row only
     * from months with expense transactions, and a dashboard with no spending has no top category to
     * name.
     */
    @Transactional(readOnly = true)
    public Optional<DashboardTopCategory> findTopCategory(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TOP_CATEGORY, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toTopCategory).findFirst();
    }

    /** UC-12 B3: the caller's tips for one month, already filtered of dismissed ones. */
    @Transactional(readOnly = true)
    public List<DashboardTip> findTips(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_TIPS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toTip).toList();
    }

    /** UC-12 B3: the live announcements a student may see, newest first. */
    @Transactional(readOnly = true)
    public List<DashboardAnnouncement> findAnnouncementsForStudent() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ANNOUNCEMENTS, Tuple.class)
                .getResultList();

        return rows.stream().map(DashboardViewDao::toAnnouncement).toList();
    }

    // ------------------------------------------------------------------
    //  Row mapping
    // ------------------------------------------------------------------

    /**
     * Reads one summary row.
     *
     * <p>{@code savingsGoalPct} is read as a nullable column because it is one: the view emits NULL
     * when the student's goal is zero, and a substituted zero would report "made no progress" for a
     * student who never set a goal. {@code periodMonth} is a {@code DATE}, which the driver reports as
     * {@link java.sql.Date}, so it is converted here rather than left as a driver type.
     */
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

    /**
     * Reads one tip row.
     *
     * <p>{@code state} is parsed rather than cast, so a value outside the enum fails loudly here
     * instead of reaching a client that would render nothing. The view already excludes
     * {@code DISMISSED}, so the two members of {@link TipState} are the only values that can arrive -
     * and if the schema ever grew a third, this is where it would be noticed.
     *
     * <p>{@code categoryId} is nullable on this table: a tip about the savings goal or about too
     * little data belongs to no category, which is why the record's field is the only one here read
     * through the nullable path.
     */
    private static DashboardTip toTip(Tuple row) {
        return new DashboardTip(
                row.get("tipId", Long.class),
                row.get("categoryId", Long.class),
                row.get("title", String.class),
                row.get("body", String.class),
                row.get("potentialSaving", java.math.BigDecimal.class),
                TipState.valueOf(row.get("state", String.class)));
    }

    /**
     * Reads one announcement row.
     *
     * <p>{@code severity} is parsed for the same reason {@code state} is, and {@code endsAt} is
     * nullable because an announcement may be open-ended - {@code ck_ann_window} permits
     * {@code ends_at IS NULL}, and the view treats it as "still running".
     */
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
