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

@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

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
