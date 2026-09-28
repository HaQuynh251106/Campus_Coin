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

@Component
public class DashboardMapper {

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

    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
