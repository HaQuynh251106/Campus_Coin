package com.campuscoin.reports.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.reports.dto.ReportResponse;
import com.campuscoin.reports.dto.SpendingSeriesResponse;
import com.campuscoin.reports.entity.CategoryBreakdownRow;
import com.campuscoin.reports.entity.ReportGranularity;
import com.campuscoin.reports.entity.ReportScope;
import com.campuscoin.reports.entity.ReportTotals;
import com.campuscoin.reports.mapper.ReportMapper;
import com.campuscoin.reports.repository.ReportViewDao;

@Service
public class ReportService {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReportViewDao reportViewDao;
    private final ReportMapper reportMapper;
    private final UserRepository userRepository;

    public ReportService(ReportViewDao reportViewDao,
                         ReportMapper reportMapper,
                         UserRepository userRepository) {
        this.reportViewDao = reportViewDao;
        this.reportMapper = reportMapper;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(AuthenticatedUser principal, String month) {
        Long userId = principal.userId();
        LocalDate periodMonth = resolveAnyMonth(month, "month");

        ReportTotals totals = reportViewDao.findTotals(userId, periodMonth)
                .orElseGet(() -> ReportTotals.empty(periodMonth));

        List<CategoryBreakdownRow> rows = reportViewDao.findCategoryTotals(userId, periodMonth);

        return reportMapper.toResponse(
                new ReportScope(periodMonth, periodMonth, periodMonth.withDayOfMonth(
                        periodMonth.lengthOfMonth()), ReportGranularity.DAILY),
                currencyOf(userId),
                totals,
                rows.stream().filter(row -> row.type() == CategoryType.EXPENSE).toList(),
                rows.stream().filter(row -> row.type() == CategoryType.INCOME).toList(),
                reportViewDao.findSixMonthTrend(userId));
    }

    @Transactional(readOnly = true)
    public SpendingSeriesResponse getSpendingSeries(AuthenticatedUser principal,
                                                    String month,
                                                    String from,
                                                    String to,
                                                    ReportGranularity granularity) {
        Long userId = principal.userId();
        ReportScope scope = resolveScope(month, from, to, granularity);

        List<com.campuscoin.reports.entity.SpendingPoint> points =
                scope.granularity() == ReportGranularity.DAILY
                        ? reportViewDao.findDailySpending(userId, scope.from(), scope.to())
                        : reportViewDao.findWeeklySpending(userId, scope.from(), scope.to());

        return reportMapper.toSeriesResponse(scope, currencyOf(userId), points);
    }

    private String currencyOf(Long userId) {
        return userRepository.findById(userId)
                .map(User::getCurrency)
                .orElseThrow(() -> new NotFoundException("Your account could not be found."));
    }

    private LocalDate resolveAnyMonth(String month, String fieldName) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
        }
        return parseMonth(month, fieldName);
    }

    private ReportScope resolveScope(String month, String from, String to,
                                     ReportGranularity granularity) {
        LocalDate currentMonth = YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
        LocalDate monthEnd = currentMonth.withDayOfMonth(currentMonth.lengthOfMonth());

        if (month != null && !month.isBlank()) {
            LocalDate requested = parseMonth(month, "month");
            if (!requested.equals(currentMonth)) {
                throw new RequestValidationException(
                        "A breakdown by day or week is only available for the current month.",
                        List.of(new ApiError.FieldError("month",
                                "The daily and weekly breakdowns are computed for the current "
                                        + "month only. Omit the month to use the current one, or "
                                        + "ask for a whole month's totals instead.")));
            }
        }

        LocalDate fromDate = from == null || from.isBlank() ? null : parseDate(from, "from");
        LocalDate toDate = to == null || to.isBlank() ? null : parseDate(to, "to");

        if (fromDate != null) {
            requireCurrentMonth(fromDate, currentMonth, "from");
        }
        if (toDate != null) {
            requireCurrentMonth(toDate, currentMonth, "to");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new RequestValidationException(
                    "The start of the window is after its end.",
                    List.of(new ApiError.FieldError("from",
                            "The start of the window must not be later than its end.")));
        }

        LocalDate windowStart = fromDate == null ? currentMonth : fromDate;
        LocalDate windowEnd = toDate == null ? monthEnd : toDate;

        return new ReportScope(currentMonth, windowStart, windowEnd, granularity);
    }

    private void requireCurrentMonth(LocalDate date, LocalDate currentMonth, String fieldName) {
        if (!YearMonth.from(date).equals(YearMonth.from(currentMonth))) {
            throw new RequestValidationException(
                    "A breakdown by day or week is only available for the current month.",
                    List.of(new ApiError.FieldError(fieldName,
                            "The daily and weekly breakdowns are computed for the current month "
                                    + "only, so this date is outside the range that can be reported.")));
        }
    }

    private LocalDate parseMonth(String month, String fieldName) {
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError(fieldName,
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }

    private LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The window edge is not a valid date.",
                    List.of(new ApiError.FieldError(fieldName,
                            "Enter a date in yyyy-MM-dd form, for example 2026-09-01.")));
        }
    }
}
