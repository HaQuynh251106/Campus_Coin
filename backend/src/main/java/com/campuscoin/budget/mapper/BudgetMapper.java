package com.campuscoin.budget.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.campuscoin.budget.dto.BudgetResponse;
import com.campuscoin.budget.entity.BudgetConsumption;

/**
 * Maps a {@code v_budget_consumption} row to the API model (UC-13).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. The view carries
 * {@code user_id}; centralising the projection here means a column can be added to it without
 * silently appearing in a response.
 *
 * <p><b>This mapper also owns the two conversions between a month and a date.</b> The database stores
 * a month as the {@code DATE} of its first day ({@code ck_budget_month}), while a client names a
 * month as {@code yyyy-MM}. Both directions live here so that no other class has to remember the
 * convention: {@link #toMonthString} for reading, {@link #toPeriodMonth} for writing. Keeping them
 * together is what stops one path deciding the first of the month and another deciding the fifteenth.
 */
@Component
public class BudgetMapper {

    /**
     * UC-13: one budget with its consumption, as the client sees it.
     *
     * <p>Deliberately omitted: the owner, and the row's creation and modification timestamps.
     *
     * <p>{@code consumedPct} and the other derived figures are passed through exactly as the database
     * computed them - they are not rounded or recomputed here. The view already rounds the
     * percentage to two decimal places, and re-rounding a value the alert log also uses is how the
     * two would start to differ in the last digit.
     */
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

    /**
     * A first-of-month {@code DATE} as the {@code yyyy-MM} a client uses.
     *
     * @param periodMonth a date the database guarantees is the first of its month, or null
     * @return the month as {@code yyyy-MM}, or null when the value was null
     */
    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }

    /**
     * A client's {@code yyyy-MM} as the first-of-month {@code DATE} the column stores.
     *
     * <p>{@link YearMonth#atDay(int)} with day 1 is what guarantees the value passes
     * {@code ck_budget_month}. A bare {@code LocalDate.parse(month + "-01")} would look equivalent
     * but parses through the date formatter, which is happy to accept an out-of-range day; asking
     * {@link YearMonth} to be the first of its own month cannot produce a date outside that month at
     * all.
     *
     * @param month a value already matched against {@code \d{4}-\d{2}} by the request DTO
     * @return the first day of that month
     * @throws IllegalArgumentException if the value is well-formed but names no real month, such as
     *                                  {@code 2026-13}; the caller turns this into a field error
     */
    public LocalDate toPeriodMonth(String month) {
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Not a real month: " + month, ex);
        }
    }
}
