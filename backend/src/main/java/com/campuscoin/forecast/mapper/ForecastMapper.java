package com.campuscoin.forecast.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.forecast.dto.ForecastResponse;
import com.campuscoin.forecast.entity.MonthTotals;
import com.campuscoin.forecast.service.Forecaster;

@Component
public class ForecastMapper {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    public ForecastResponse toResponse(LocalDate currentMonth,
                                       List<MonthTotals> recentMonths,
                                       Optional<MonthTotals> currentTotals,
                                       Optional<Forecaster.Projection> projection) {
        return new ForecastResponse(
                MONTH.format(currentMonth.plusMonths(1)),
                MONTH.format(currentMonth),
                projection.map(Forecaster.Projection::basedOnMonths).orElse(0),
                recentMonths.stream().map(this::toMonth).toList(),
                currentTotals.map(this::toCurrentTotals).orElse(null),
                projection.map(this::toProjected).orElse(null));
    }

    private ForecastResponse.ProjectedMonthResponse toProjected(Forecaster.Projection projection) {
        return new ForecastResponse.ProjectedMonthResponse(
                projection.projectedIncome(),
                projection.projectedExpense(),
                projection.projectedSavings());
    }

    private ForecastResponse.ForecastMonthResponse toMonth(MonthTotals month) {
        return new ForecastResponse.ForecastMonthResponse(
                MONTH.format(month.periodMonth()),
                month.totalIncome(),
                month.totalExpense(),
                net(month.totalIncome(), month.totalExpense()));
    }

    private ForecastResponse.CurrentMonthTotalsResponse toCurrentTotals(MonthTotals month) {
        return new ForecastResponse.CurrentMonthTotalsResponse(
                month.totalIncome(),
                month.totalExpense(),
                net(month.totalIncome(), month.totalExpense()));
    }

    private static BigDecimal net(BigDecimal income, BigDecimal expense) {
        return income.subtract(expense);
    }
}
