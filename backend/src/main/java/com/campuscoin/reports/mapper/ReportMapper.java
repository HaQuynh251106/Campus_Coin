package com.campuscoin.reports.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.reports.dto.ReportCategoryResponse;
import com.campuscoin.reports.dto.ReportResponse;
import com.campuscoin.reports.dto.ReportTotalsResponse;
import com.campuscoin.reports.dto.ReportTrendPointResponse;
import com.campuscoin.reports.dto.SpendingPointResponse;
import com.campuscoin.reports.dto.SpendingSeriesResponse;
import com.campuscoin.reports.entity.CategoryBreakdownRow;
import com.campuscoin.reports.entity.MonthlyTrendPoint;
import com.campuscoin.reports.entity.ReportScope;
import com.campuscoin.reports.entity.ReportTotals;
import com.campuscoin.reports.entity.SpendingPoint;

@Component
public class ReportMapper {

    public ReportResponse toResponse(ReportScope scope,
                                     String currency,
                                     ReportTotals totals,
                                     List<CategoryBreakdownRow> expenseByCategory,
                                     List<CategoryBreakdownRow> incomeByCategory,
                                     List<MonthlyTrendPoint> trend) {
        return new ReportResponse(
                toMonthString(scope.periodMonth()),
                currency,
                toTotalsResponse(totals),
                toCategoryResponses(expenseByCategory),
                toCategoryResponses(incomeByCategory),
                trend.stream().map(this::toTrendPointResponse).toList());
    }

    public ReportTotalsResponse toTotalsResponse(ReportTotals totals) {
        return new ReportTotalsResponse(
                totals.income(),
                totals.expense(),
                totals.net(),
                totals.transactionCount());
    }

    public List<ReportCategoryResponse> toCategoryResponses(List<CategoryBreakdownRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        BigDecimal blockTotal = rows.stream()
                .map(CategoryBreakdownRow::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return rows.stream()
                .map(row -> toCategoryResponse(row, blockTotal))
                .toList();
    }

    private ReportCategoryResponse toCategoryResponse(CategoryBreakdownRow row, BigDecimal blockTotal) {
        return new ReportCategoryResponse(
                row.categoryId(),
                row.categoryName(),
                row.categoryIcon(),
                row.categoryColor(),
                row.type(),
                row.totalAmount(),
                toPercentage(row.totalAmount(), blockTotal),
                row.transactionCount());
    }

    private static BigDecimal toPercentage(BigDecimal amount, BigDecimal blockTotal) {
        if (blockTotal.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(blockTotal, 2, RoundingMode.HALF_UP);
    }

    public ReportTrendPointResponse toTrendPointResponse(MonthlyTrendPoint point) {
        return new ReportTrendPointResponse(
                toMonthString(point.periodMonth()),
                point.income(),
                point.expense(),
                point.net());
    }

    public SpendingSeriesResponse toSeriesResponse(ReportScope scope, String currency,
                                                  List<SpendingPoint> points) {
        BigDecimal total = points.stream()
                .map(SpendingPoint::totalExpense)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SpendingSeriesResponse(
                scope.granularity(),
                scope.from().toString(),
                scope.to().toString(),
                currency,
                total,
                points.stream().map(this::toSpendingPointResponse).toList());
    }

    public SpendingPointResponse toSpendingPointResponse(SpendingPoint point) {
        return new SpendingPointResponse(
                point.intervalStart().toString(),
                point.intervalEnd().toString(),
                point.totalExpense(),
                point.transactionCount());
    }

    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
