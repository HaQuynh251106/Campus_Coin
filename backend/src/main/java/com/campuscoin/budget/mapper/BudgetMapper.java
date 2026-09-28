package com.campuscoin.budget.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.campuscoin.budget.dto.BudgetResponse;
import com.campuscoin.budget.entity.BudgetConsumption;

@Component
public class BudgetMapper {

    public BudgetResponse toResponse(BudgetConsumption consumption) {
        return new BudgetResponse(
                consumption.budgetId(),
                consumption.categoryId(),
                consumption.categoryName(),
                consumption.categoryIcon(),
                consumption.categoryColor(),
                toMonthString(consumption.periodMonth()),
                consumption.limitAmount(),
                consumption.spentAmount(),
                consumption.remainingAmount(),
                consumption.consumedPct(),
                consumption.consumptionStatus());
    }

    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }

    public LocalDate toPeriodMonth(String month) {
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Not a real month: " + month, ex);
        }
    }
}
