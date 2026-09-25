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

/**
 * The student's financial reports: UC-15, and the reads UC-16 exports.
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>The month's totals</b> - {@code v_monthly_income_expense}, which splits income from expense
 *       by the category's type (BR-05) and counts only records that are not in the trash (BR-09). This
 *       class never sums a transaction.</li>
 *   <li><b>Each category's total for the month</b> - {@code v_category_month_totals}, the same view
 *       the dashboard's top-category widget ranks, so the pie chart and the home screen cannot
 *       disagree.</li>
 *   <li><b>The six-month window and its zero-fill</b> - {@code v_monthly_income_expense_6m}, which
 *       joins {@code dim_month} so that BR-17 and UAT-09 hold: six rows always, empty months as zero.
 *       This class does not decide which six months, and does not fill a gap.</li>
 *   <li><b>Which day or week a record falls in, and what an ISO week's boundaries are</b> -
 *       {@code v_daily_spending_current_month} and {@code v_weekly_spending_current_month}, the latter
 *       grouping by {@code YEARWEEK(date, 3)} so a week is never split across a year boundary
 *       (VĐ-10).</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Turning a client's {@code yyyy-MM} into the first-of-month
 * date the columns store; deciding which month a report may be asked about, which is the module's
 * central constraint; splitting one view's rows into the income and expense blocks that are the same
 * value read once; and refusing a request that would name a month the underlying views cannot answer
 * about.
 *
 * <p><b>There is no user id parameter on any method.</b> The account comes from the verified token, so
 * there is no way to ask for somebody else's report, and the views are read with the caller's id
 * bound. That is the whole of UC-15's ownership requirement, and it is enforced by the query rather
 * than by a check afterwards (BR-02).
 */
@Service
public class ReportService {

    /**
     * The zone the application judges "the current month" in.
     *
     * <p>Deliberately the same {@code Asia/Ho_Chi_Minh} offset the database session is pinned to by
     * Hikari's {@code connection-init-sql} (VĐ-10) and that {@code BudgetService} uses for a budget's
     * default month. This module needs it for a sharper reason than a default: whether a requested
     * month <em>is</em> the current one decides whether the request is answered or refused, because
     * the daily and weekly views derive their range from {@code CURDATE()}. Judging that in the JVM's
     * default zone would refuse a legitimate late-evening request - or, worse, accept one at 06:00 on
     * the first of a month and answer it from the previous month's view - for seven hours in every
     * twenty-four.
     */
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

    /**
     * UC-15: one month's report for the signed-in student.
     *
     * <p>{@code readOnly = true} documents that nothing is written. That is worth stating for a report
     * in particular, because a report is the kind of screen where a careless implementation would
     * record that it was viewed or cache its own figures - and every one of those would be a write
     * behind a GET.
     *
     * <p><b>{@code month} may be any month the student has records for, and needs no restriction.</b>
     * The two views behind the totals and the breakdowns are keyed by {@code period_month} and
     * therefore answer about whatever month is asked. The six-month trend is the exception - it is
     * BR-17's fixed window, the last six months ending at the current one - and it is returned beside
     * a selected month's totals rather than filtered by them, with each point carrying its own month
     * so nothing is mislabelled. There is no request field that could move it.
     *
     * <p>The four reads are unconditional: a month with no records still has a trend, and a month with
     * no spending may still have income. Making one read depend on another would make the response's
     * shape depend on the student's data, which a client cannot predict.
     *
     * @throws RequestValidationException if {@code month} is not a real month in {@code yyyy-MM} form
     * @throws NotFoundException          if the caller's account cannot be read, which no valid token
     *                                    should produce
     */
    @Transactional(readOnly = true)
    public ReportResponse getReport(AuthenticatedUser principal, String month) {
        Long userId = principal.userId();
        LocalDate periodMonth = resolveAnyMonth(month, "month");

        // The view emits one row per month that holds data, so a quiet month yields nothing here.
        // That is turned into ReportTotals.empty with the requested month on it, not into a zero:
        // "you recorded nothing in July" and "July's activity netted to nothing" are different
        // statements, and only the first is true.
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

    /**
     * UC-15: the signed-in student's spending over a window, by day or by ISO week.
     *
     * <p><b>This is the one report that cannot be asked about another month, and the request is refused
     * rather than quietly narrowed.</b> {@code v_daily_spending_current_month} and
     * {@code v_weekly_spending_current_month} both derive their range from {@code CURDATE()} inside the
     * database session; neither takes a month parameter. A request naming an earlier month - or a
     * window that reaches outside the current one - is therefore answered with
     * {@code 400 VALIDATION_ERROR} and a field error pointing at the offending parameter. The
     * alternative was to accept the value and silently answer about the current month, which would put
     * one month's bars under another month's heading; that is the failure mode the dashboard's absent
     * {@code ?month=} exists to avoid, and here the parameter is genuinely useful, so the honest
     * answer is to refuse what cannot be honoured rather than to remove the field.
     *
     * <p>The window defaults to the whole of the current month, which is what the views cover. It is
     * published on the response even though the view returns only the intervals that have spending, so
     * a chart knows where its axis starts and ends.
     *
     * @throws RequestValidationException if the month or window is malformed, names a month other than
     *                                    the current one, or is the wrong way round
     */
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

    /**
     * The account's currency.
     *
     * <p>Read from {@code users.currency} rather than from the {@code app.currency} setting, because
     * they answer different questions: the setting is what a <em>new</em> account starts with (VĐ-08),
     * while the column is what this particular account's figures are denominated in. A student whose
     * currency was changed after registration must see their own, which is the same value
     * {@code ProfileResponse} publishes.
     */
    private String currencyOf(Long userId) {
        return userRepository.findById(userId)
                .map(User::getCurrency)
                .orElseThrow(() -> new NotFoundException("Your account could not be found."));
    }

    /**
     * The month a report's totals and breakdowns describe.
     *
     * <p>Any month is permitted here, including a future one: the two views behind these figures are
     * keyed by {@code period_month} and will simply return nothing for a month the student has no
     * records in, which is an honest answer. Absent or blank means the current month, which is the one
     * a reports screen opens on.
     */
    private LocalDate resolveAnyMonth(String month, String fieldName) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
        }
        return parseMonth(month, fieldName);
    }

    /**
     * The month and window a spending series covers, refusing anything the views cannot answer.
     *
     * <p>Every rejection names the parameter that caused it, so a client can correct the request
     * rather than re-read the documentation. The three cases are: a well-formed month string that is
     * not a real month; a month - or a window edge - outside the current month, which the
     * {@code CURDATE()}-scoped views cannot honour; and an inverted window. A window edge that is
     * not a date at all is refused by {@link #parseDate} the same way.
     *
     * <p>The two edges are completed rather than required. A caller who gives {@code from} but no
     * {@code to} means "from then to the end of the month", and one who gives neither means "the whole
     * month" - both are the obvious readings, and requiring both would be a formality that adds a
     * failure mode without adding information.
     */
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

    /**
     * Refuses a date that falls outside the current month.
     *
     * <p>Compared by month rather than by range, so the message can say what is actually true - "only
     * the current month is available" - rather than describing the boundary as an arbitrary pair of
     * dates the caller would try to work around.
     */
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
