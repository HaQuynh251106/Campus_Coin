package com.campuscoin.dashboard.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.dashboard.dto.DashboardResponse;
import com.campuscoin.dashboard.entity.DashboardSummary;
import com.campuscoin.dashboard.mapper.DashboardMapper;
import com.campuscoin.dashboard.repository.DashboardViewDao;

/**
 * Assembling the student's dashboard from the four UC-12 views (UC-12).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>The month's totals</b> - {@code v_dashboard_summary}, which splits income from expense by
 *       the category's type (BR-05) and counts only records that are not in the trash (BR-09). This
 *       class never sums a transaction.</li>
 *   <li><b>Which category is the top one</b> - {@code v_top_category_current_month}, which ranks
 *       {@code v_category_month_totals} within the student's current month. This class does not
 *       compare two totals.</li>
 *   <li><b>Which tips to show, in what order</b> - {@code v_dashboard_tips}, which excludes dismissed
 *       tips and sorts pinned ones first (BR-14). This class does not rank a tip and has no way to
 *       change a tip's state.</li>
 *   <li><b>Which announcements are live</b> - {@code v_active_announcements}, which applies the
 *       {@code is_active} flag and the start/end window. This class does not compare a clock to a
 *       window.</li>
 *   <li><b>The saving-goal percentage</b> - the view's own {@code CASE}, which reports nothing at all
 *       when the goal is zero rather than reporting zero progress.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Deciding that the whole screen is one read of one student's
 * own rows, choosing which month the tips are selected by (the one the summary view reports), and
 * applying the one filter the schema leaves to the application: the announcement audience. Nothing
 * else - and nothing here writes, so there is no lock and no state to keep consistent.
 *
 * <p><b>One threshold is read here, and BR-14 is why.</b> {@code tips.max_dashboard} bounds how many
 * tips a dashboard may show, and the stored rows do not carry that bound: {@code sp_generate_tips}
 * applies it to one generation run, while the rows for a month accumulate across runs, because the
 * dedupe key is per rule and per category rather than per month. The bound therefore has to be applied
 * where the display is assembled, which is here. The value comes through {@link SettingReader}, so it
 * is the administrator's configured number rather than a constant, and the view is unchanged - this
 * class narrows a read and writes nothing, so no historical tip is hidden from the tips screen and
 * none is ever deleted to make a count come out right.
 *
 * <p><b>There is no user id parameter on any method.</b> The account comes from the verified token, so
 * there is no way to ask for somebody else's dashboard, and the views are read with the caller's id
 * bound. That is the whole of UC-12's ownership requirement, and it is enforced by the query rather
 * than by a check afterwards (BR-02).
 */
@Service
public class DashboardService {

    private final DashboardViewDao dashboardViewDao;
    private final DashboardMapper dashboardMapper;
    private final SettingReader settingReader;

    public DashboardService(DashboardViewDao dashboardViewDao, DashboardMapper dashboardMapper,
                            SettingReader settingReader) {
        this.dashboardViewDao = dashboardViewDao;
        this.dashboardMapper = dashboardMapper;
        this.settingReader = settingReader;
    }

    /**
     * UC-12: the signed-in student's dashboard for the current month.
     *
     * <p>{@code readOnly = true} documents that nothing is written. That is worth stating for this
     * endpoint in particular, because a dashboard is the kind of screen where a careless
     * implementation would mark things seen, refresh a cache or record a view - and every one of those
     * would be a write behind a GET.
     *
     * <p><b>The month comes from the summary row, not from the application clock, and that is the one
     * ordering decision in this method.</b> {@code v_dashboard_summary} reports
     * {@code CURDATE()} as the database session sees it; {@code v_top_category_current_month} scopes
     * itself to that same value. The tips view has no such filter - it returns every month a student
     * has tips for - so this method passes it the month the summary reported. Reading the three
     * blocks with one month named once is what stops the response being internally inconsistent: the
     * totals of one month beside the tips of another would be a bug that no field of the response
     * would reveal, because every field would be individually correct.
     *
     * <p><b>The four reads are deliberately unconditional.</b> None is skipped when an earlier one is
     * empty: a student with no spending still has announcements, and would still have tips if a
     * previous month's tip were pinned. Coupling them would make the response's shape depend on the
     * student's data, which a client cannot predict.
     *
     * <p><b>The tips are bounded by {@code tips.max_dashboard}, read at request time.</b> BR-14 caps
     * how many tips a dashboard shows, and the stored rows are not themselves capped - the generator
     * bounds one run, and a month's rows can accumulate across runs. Reading the setting here rather
     * than at generation is what makes an administrator's change take effect on the next request
     * instead of on the next nightly run, and it is what makes the displayed count honour the setting
     * the administrator actually set.
     *
     * @throws NotFoundException if the caller has no summary row, which the view produces only for an
     *                           account whose role is {@code STUDENT}
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(AuthenticatedUser principal) {
        Long userId = principal.userId();

        DashboardSummary summary = dashboardViewDao.findSummary(userId)
                .orElseThrow(() -> new NotFoundException("Dashboard not found."));

        int maxTips = settingReader.getInt(SettingReader.TIPS_MAX_DASHBOARD,
                SettingReader.DEFAULT_TIPS_MAX_DASHBOARD);

        return dashboardMapper.toResponse(
                summary.periodMonth(),
                summary,
                dashboardViewDao.findTopCategory(userId).orElse(null),
                dashboardViewDao.findTipsLimited(userId, summary.periodMonth(), maxTips),
                dashboardViewDao.findAnnouncementsForStudent());
    }
}
