package com.campuscoin.forecast.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.forecast.dto.ForecastResponse;
import com.campuscoin.forecast.entity.MonthTotals;
import com.campuscoin.forecast.mapper.ForecastMapper;
import com.campuscoin.forecast.repository.ForecastViewDao;

/**
 * Projects a student's next month from their own recorded months (UC-25).
 *
 * <p><b>The whole feature is one read and one calculation, and neither leaves the caller's data.</b>
 * The read is narrowed to the caller's id by the query, the calculation is a trailing average over
 * that student's own complete months, and the response carries figures and month labels only - no
 * identifier, no category, no other student's data reaches it. So unlike UC-08 and UC-17 there is no
 * external service here and no {@code ai.enabled} switch to consult: a forecast is the student's own
 * history read back to them, and turning it off would be turning off arithmetic. That is why this
 * module has no port and no configuration beyond the application's clock.
 *
 * <p><b>Identity comes from the verified token, never from the request.</b> The endpoint takes no
 * parameters at all, and the only id this class passes to the database is {@code principal.userId()} -
 * the same guarantee {@code ReportService} and the dashboard rely on (BR-02). A client cannot ask for
 * a month, either: the month in progress is derived from the application's clock in the configured
 * zone (VĐ-10), so a caller cannot reach a month of their own choosing and there is no window to
 * widen.
 *
 * <p><b>Two reads, because "the last three complete months" and "this month so far" are different
 * questions.</b> The baseline is drawn from complete months only and the current month is read on its
 * own, so the estimate is not dragged down by a partial month and the current-month figures are
 * reported as the fact they are. The DAO owns the queries and {@code Forecaster} owns the arithmetic;
 * this class decides which months are complete, which is the one judgement that needs the clock.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    /**
     * The zone the application and the database session both run in (VĐ-10), so "this month" means the
     * same month here as it does to every other module and to the database's own {@code CURDATE()}.
     *
     * <p>A {@code private static final} here rather than a shared constant, matching
     * {@code ReportService}, {@code BudgetService} and {@code TransactionService} - the project keeps
     * one copy of the zone per class that needs it.
     */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ForecastViewDao forecastViewDao;
    private final Forecaster forecaster;
    private final ForecastMapper forecastMapper;

    public ForecastService(ForecastViewDao forecastViewDao,
                           Forecaster forecaster,
                           ForecastMapper forecastMapper) {
        this.forecastViewDao = forecastViewDao;
        this.forecaster = forecaster;
        this.forecastMapper = forecastMapper;
    }

    /**
     * UC-25: the caller's current month and a projection for the next one.
     *
     * <p>Read-only: nothing here writes, and the whole method is one {@code @Transactional(readOnly =
     * true)} unit so the two reads see the same database state - a month boundary crossed between them
     * could otherwise have the baseline end a month before the current month the response names.
     */
    @Transactional(readOnly = true)
    public ForecastResponse forecast(AuthenticatedUser principal) {
        LocalDate currentMonth = YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);

        List<MonthTotals> completeMonths = forecastViewDao.findCompletedMonths(
                principal.userId(), currentMonth, Forecaster.WINDOW_MONTHS);
        var currentTotals = forecastViewDao.findCurrentMonth(principal.userId(), currentMonth);
        var projection = forecaster.project(completeMonths);

        log.debug("Forecast computed userId={} currentMonth={} basedOnMonths={}",
                principal.userId(), currentMonth, projection.map(Forecaster.Projection::basedOnMonths)
                        .orElse(0));

        return forecastMapper.toResponse(currentMonth, completeMonths, currentTotals, projection);
    }
}
