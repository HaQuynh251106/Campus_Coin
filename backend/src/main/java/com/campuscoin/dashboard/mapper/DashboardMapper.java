package com.campuscoin.dashboard.mapper;

import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.stereotype.Component;

import com.campuscoin.dashboard.dto.DashboardAnnouncementResponse;
import com.campuscoin.dashboard.dto.DashboardResponse;
import com.campuscoin.dashboard.dto.DashboardSummaryResponse;
import com.campuscoin.dashboard.dto.DashboardTipResponse;
import com.campuscoin.dashboard.dto.DashboardTopCategoryResponse;
import com.campuscoin.dashboard.entity.DashboardAnnouncement;
import com.campuscoin.dashboard.entity.DashboardSummary;
import com.campuscoin.dashboard.entity.DashboardTip;
import com.campuscoin.dashboard.entity.DashboardTopCategory;

/**
 * Turns the four UC-12 view projections into the response (UC-12).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. Three of the four
 * views carry {@code user_id} and {@code v_active_announcements} carries {@code created_by};
 * centralising the projection means a column can be added to a view without silently appearing in a
 * response.
 *
 * <p><b>Nothing is recalculated here.</b> Every figure, every percentage and the order of the tip list
 * arrive from the database already decided, and are passed through unaltered. Rounding a number again
 * or re-sorting a list is how the dashboard would start to disagree with the reports module reading
 * the same views - the reasoning {@code BudgetMapper} gives for the consumption figures it publishes.
 *
 * <p>The one conversion this class owns is the month: the database stores it as the {@code DATE} of
 * the first day, and a client names it as {@code yyyy-MM}. It is done once, here, so that no caller
 * has to remember the convention, and it is the same conversion {@code BudgetMapper} makes - a
 * dashboard month and a budget month must be the same string for the same month.
 */
@Component
public class DashboardMapper {

    /**
     * UC-12: the whole dashboard.
     *
     * <p>{@code topCategory} is null when the student has spent nothing, and {@code DashboardResponse}
     * omits it - the null is the honest value and the omission is the wire form of it.
     *
     * <p>The lists are never null: the DAO returns empty lists, and passing an empty list through
     * keeps "there are none" and "the field is missing" distinguishable on the wire.
     */
    public DashboardResponse toResponse(LocalDate periodMonth,
                                        DashboardSummary summary,
                                        DashboardTopCategory topCategory,
                                        java.util.List<DashboardTip> tips,
                                        java.util.List<DashboardAnnouncement> announcements) {
        return new DashboardResponse(
                toMonthString(periodMonth),
                toSummaryResponse(summary),
                topCategory == null ? null : toTopCategoryResponse(topCategory),
                tips.stream().map(this::toTipResponse).toList(),
                announcements.stream().map(this::toAnnouncementResponse).toList());
    }

    /** UC-12 B1: the month's totals, with the goal percentage omitted when there is no goal. */
    public DashboardSummaryResponse toSummaryResponse(DashboardSummary summary) {
        return new DashboardSummaryResponse(
                summary.currency(),
                summary.totalIncome(),
                summary.totalExpense(),
                summary.netAmount(),
                summary.monthlyAllowanceBaseline(),
                summary.monthlySavingsGoal(),
                summary.savingsGoalPct());
    }

    public DashboardTopCategoryResponse toTopCategoryResponse(DashboardTopCategory topCategory) {
        return new DashboardTopCategoryResponse(
                topCategory.categoryId(),
                topCategory.categoryName(),
                topCategory.categoryIcon(),
                topCategory.categoryColor(),
                topCategory.totalAmount());
    }

    public DashboardTipResponse toTipResponse(DashboardTip tip) {
        return new DashboardTipResponse(
                tip.tipId(),
                tip.categoryId(),
                tip.title(),
                tip.body(),
                tip.potentialSaving(),
                tip.state());
    }

    public DashboardAnnouncementResponse toAnnouncementResponse(DashboardAnnouncement announcement) {
        return new DashboardAnnouncementResponse(
                announcement.id(),
                announcement.title(),
                announcement.body(),
                announcement.severity(),
                announcement.startsAt(),
                announcement.endsAt());
    }

    /**
     * A first-of-month {@code DATE} as the {@code yyyy-MM} a client uses.
     *
     * <p>{@link YearMonth#from} rather than slicing the string: the column is guaranteed to be the
     * first of its month, so the year and the month are the whole of what it carries, and taking them
     * through the date type cannot produce a value the calendar disagrees with.
     *
     * @param periodMonth a date the database guarantees is the first of its month, or null
     * @return the month as {@code yyyy-MM}, or null when the value was null
     */
    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
