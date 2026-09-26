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

/**
 * Turns a projection and the months behind it into the response (UC-25).
 *
 * <p><b>This is the one place that decides what leaves the server</b>, which is why the field set is
 * assembled here rather than in the service: a reader can see the whole published contract on one
 * screen and check it against UC-25. Nothing here reads a user id, an email or a category - the
 * response is figures about one student's own months, and every figure came from the request's own
 * bearer token.
 *
 * <p><b>The month labels are strings, and formatted here rather than in the DATO.</b> The write
 * format {@code yyyy-MM} is the same one the reports module uses, so the two screens read alike, and
 * keeping the {@link DateTimeFormatter} here means a DTO record stays a plain carrier of what it is
 * given - the treatment {@code ReportMapper} chose for {@code periodMonth}.
 *
 * <p><b>The optional blocks are decided here, not by the client.</b> A missing current month and a
 * missing projection are different facts, and this class turns each into the absence of the block
 * that would otherwise state something untrue. The service hands it "no current month" and "no
 * projection" separately and this class keeps them separate in the response.
 */
@Component
public class ForecastMapper {

    /** BR-17 uses `yyyy-MM` for a month; the forecast matches it so the two screens read alike. */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * UC-25: the response for one caller.
     *
     * @param currentMonth   the month in progress, from the application's own clock in the configured
     *                       zone - not from the client, so a caller cannot ask about a month of their
     *                       own choosing and reach data the endpoint was not meant to expose
     * @param recentMonths   the complete months the projection averaged, oldest first
     * @param currentTotals  the month in progress as recorded, or empty when nothing is recorded yet
     * @param projection     the estimate for the next month, or empty when there is no complete month
     */
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

    /** The net of a single month, for the evidence list and the current-month block. */
    private static BigDecimal net(BigDecimal income, BigDecimal expense) {
        return income.subtract(expense);
    }
}
