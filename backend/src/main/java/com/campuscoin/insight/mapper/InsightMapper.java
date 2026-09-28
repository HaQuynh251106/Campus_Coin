package com.campuscoin.insight.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.insight.dto.InsightMonthsResponse;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.entity.InsightRow;

@Component
public class InsightMapper {

    public MonthlyInsightResponse toInsightResponse(InsightRow row) {
        return new MonthlyInsightResponse(
                toMonthString(row.periodMonth()),
                row.totalIncome(),
                row.totalExpense(),
                row.netAmount(),
                row.summaryText(),
                row.adviceText(),
                row.generatedBy(),
                row.modelName(),
                row.flaggedCategories().stream().map(InsightMapper::toFlaggedCategory).toList(),
                row.generatedAt());
    }

    public InsightMonthsResponse toMonthsResponse(List<LocalDate> months) {
        return new InsightMonthsResponse(months.stream().map(this::toMonthString).toList());
    }

    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }

    private static MonthlyInsightResponse.FlaggedCategoryResponse toFlaggedCategory(
            InsightRow.FlaggedCategory flagged) {
        return new MonthlyInsightResponse.FlaggedCategoryResponse(
                flagged.categoryId(),
                flagged.categoryName(),
                flagged.currentTotal(),
                flagged.baselineAvg(),
                flagged.pctChange());
    }
}
