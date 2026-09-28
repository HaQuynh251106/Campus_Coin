package com.campuscoin.forecast.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.forecast.entity.MonthTotals;

@Component
public class Forecaster {

    static final int WINDOW_MONTHS = 3;

    private static final int SCALE = 2;

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public record Projection(BigDecimal projectedIncome,
                             BigDecimal projectedExpense,
                             BigDecimal projectedSavings,
                             int basedOnMonths) {
    }

    public Optional<Projection> project(List<MonthTotals> completeMonths) {
        if (completeMonths.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal projectedIncome = average(completeMonths, MonthTotals::totalIncome);
        BigDecimal projectedExpense = average(completeMonths, MonthTotals::totalExpense);

        return Optional.of(new Projection(
                projectedIncome,
                projectedExpense,
                projectedIncome.subtract(projectedExpense),
                completeMonths.size()));
    }

    private static BigDecimal average(List<MonthTotals> months,
                                      java.util.function.Function<MonthTotals, BigDecimal> figure) {
        BigDecimal total = months.stream()
                .map(figure)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return total.divide(BigDecimal.valueOf(months.size()), SCALE, ROUNDING);
    }
}
