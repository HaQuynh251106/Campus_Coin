package com.campuscoin.insight.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.ai.AiSuggestionPort;
import com.campuscoin.common.ai.MonthlyNarrative;
import com.campuscoin.common.ai.MonthlyNarrativeRequest;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.insight.dto.InsightMonthsResponse;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.entity.InsightRow;
import com.campuscoin.insight.entity.MonthlyCategoryTotal;
import com.campuscoin.insight.mapper.InsightMapper;
import com.campuscoin.insight.repository.InsightViewDao;
import com.campuscoin.insight.repository.InsightWriteDao;

/**
 * Showing a student their monthly insight and generating one (UC-17).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>The month's figures</b> - {@code sp_generate_monthly_insight} sums income and expense by the
 *       category's own type (BR-05) over records not in the trash (BR-09), for the month it was asked
 *       for. This class never adds up a transaction.</li>
 *   <li><b>Which categories count as unusual</b> - the procedure reads
 *       {@code v_category_spend_trend}, whose spike test joins the {@code insight.spike_threshold_pct}
 *       and {@code insight.spike_baseline_months} settings (BR-15, VĐ-05). So "respect the documented
 *       thresholds and settings" is achieved by calling the procedure: no threshold is read in Java,
 *       and an administrator retuning one changes the next generation without a redeploy.</li>
 *   <li><b>The rule-based text</b> - the procedure composes the summary and the advice it writes when
 *       no provider is involved. This class does not write prose of its own for that case.</li>
 *   <li><b>That an existing AI narrative is not overwritten</b> - the procedure's
 *       {@code ON DUPLICATE KEY UPDATE} guards on {@code generated_by = 'AI'}. This class relies on
 *       that rule rather than repeating it; see {@code InsightWriteDao}.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Which month a request means, that an insight is reachable
 * only by its owner, what context a provider is given, and what the answer is checked against before
 * it is stored.
 *
 * <p><b>The provider is given a month's aggregates and nothing else.</b> The request is built here
 * from the row the procedure just wrote - one student's totals - plus that student's per-category
 * expense totals read from {@code v_category_month_totals}. It carries no identifier of any kind: no
 * user id, no category id, no transaction, no description, no e-mail. The flow section 7 requires is
 * the shape of the code:
 *
 * <pre>
 *   Angular -&gt; this class (reads and filters the caller's own rows)
 *            -&gt; MonthlyNarrativeRequest -&gt; AiSuggestionPort
 *           &lt;- Optional&lt;MonthlyNarrative&gt; checked here before it is stored
 * </pre>
 *
 * <p><b>An unavailable provider is not a failure.</b> {@link AiSuggestionPort} returns empty when no
 * credential is configured, when {@code ai.enabled} is off, or when the call errored. In every one of
 * those cases the rule-based summary the procedure wrote stands, and the student still gets an
 * insight - so generating works in a deployment with no AI provider at all and gets better when one
 * is configured. Nothing is marked failed; the row's {@code status} column is not written by this
 * build.
 *
 * <p><b>Nothing the provider writes is trusted as prose about the student's money.</b> It is stored
 * as the AI narrative and published with {@code generatedBy = AI}, which is what BR-13 requires a
 * client to label as a suggestion rather than as advice. The figures it was shown are not rewritten by
 * it - only the two sentences are - so the numbers a student checks the narrative against are the
 * database's own.
 *
 * <p><b>There is no user id parameter on any method.</b> The account comes from the verified token,
 * so there is no way to ask for somebody else's insight, and every query is bound with the caller's id
 * (BR-02). An insight states a student's income, their expense and their net balance, so a missing
 * filter here would disclose a financial summary of one student to another.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    /**
     * The zone the application judges "this month" in.
     *
     * <p>The same {@code +07:00} the database session is pinned to by
     * {@code connection-init-sql: SET time_zone = '+07:00'} and that {@code TipService} and
     * {@code TransactionService} use. A month boundary is the one place a JVM in another zone would
     * disagree with the database for part of every day, and the effective month is what a request with
     * no {@code month} resolves to - the same value the procedure would default to, so a named month
     * and a defaulted one land on the same row.
     */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * VĐ-08: the currency a narrative describes amounts in when no setting names one.
     *
     * <p>Mirrors the default {@code AuthService} uses for a new account, and the seeded
     * {@code app.currency} row. It is a fallback for an absent row rather than a second definition of
     * the setting - the value is read from {@code system_settings} first, the same way every other
     * consumer reads it (VĐ-05).
     */
    private static final String DEFAULT_CURRENCY = "USD";

    private final InsightViewDao insightViewDao;
    private final InsightWriteDao insightWriteDao;
    private final InsightMapper insightMapper;
    private final AiSuggestionPort aiSuggestionPort;
    private final SettingReader settingReader;

    public InsightService(InsightViewDao insightViewDao,
                          InsightWriteDao insightWriteDao,
                          InsightMapper insightMapper,
                          AiSuggestionPort aiSuggestionPort,
                          SettingReader settingReader) {
        this.insightViewDao = insightViewDao;
        this.insightWriteDao = insightWriteDao;
        this.insightMapper = insightMapper;
        this.aiSuggestionPort = aiSuggestionPort;
        this.settingReader = settingReader;
    }

    /**
     * UC-17: the caller's insight for a month, or for the current month when none is named.
     *
     * <p>{@code readOnly = true} documents that reading never generates - opening the insights screen
     * cannot change what is on it, and cannot spend a provider call. Generating is the separate
     * request below, the distinction {@code TipService} draws for the same reason.
     *
     * <p><b>A month with no insight is a {@code 404}, and this differs from the tips list
     * deliberately.</b> A list of tips is a collection, and an empty collection is an answer; an
     * insight is one object, and "there is no insight for this month" is the absence of the resource
     * rather than a resource that happens to be empty. An insight for a month the student recorded
     * nothing in is a different thing and does exist - the procedure writes it, with zero totals - so
     * the two cases stay distinguishable to a caller.
     *
     * @param month {@code yyyy-MM}, or null/blank for the current month
     * @throws RequestValidationException if {@code month} is present but is not a valid month
     * @throws NotFoundException          if no insight exists for that month
     */
    @Transactional(readOnly = true)
    public MonthlyInsightResponse getInsight(AuthenticatedUser principal, String month) {
        LocalDate periodMonth = resolveMonth(month);

        InsightRow row = insightViewDao.find(principal.userId(), periodMonth)
                .orElseThrow(() -> new NotFoundException(
                        "No insight has been generated for that month."));

        return insightMapper.toInsightResponse(row);
    }

    /**
     * UC-17: the months the caller has insights for, newest first.
     *
     * <p>Read from {@code insights}, so the months offered are exactly the months that would answer
     * with content. Backs a month picker without inventing a range: a student who has never had an
     * insight generated gets an empty array, and the current month appears only if one was actually
     * produced for it.
     */
    @Transactional(readOnly = true)
    public InsightMonthsResponse listMonths(AuthenticatedUser principal) {
        return insightMapper.toMonthsResponse(insightViewDao.findMonths(principal.userId()));
    }

    /**
     * UC-17: generates the insight for a month - or for the current month - and returns it.
     *
     * <p><b>Two steps, in this order.</b> The procedure computes and stores the month's figures, its
     * flagged categories and a rule-based summary and advice. Only then is a provider asked for a
     * narrative, and only its two sentences are stored over that text. So the transaction always
     * leaves a complete insight behind: if the provider is not configured, declines or fails, the
     * request still succeeds and the student still sees their month.
     *
     * <p><b>A named month is allowed, and that is what makes the history usable.</b> An insight is
     * stored per month, so a student who wants to look back at August after recording something they
     * had forgotten re-generates August rather than being told the generator only runs for today. The
     * procedure accepts any month - it only defaults to the current one when none is given - and the
     * data it reads is the caller's own, so there is nothing a past month could disclose that the
     * current one does not. An insight already generated for a past month is refreshed rather than
     * duplicated: {@code uk_insight_user_month} makes it an upsert, and a provider's text already
     * stored for that month is preserved by the procedure's own rule. See {@code TipController} for
     * the opposite decision on tips, where the generator is deliberately current-month-only.
     *
     * <p><b>Safe to call repeatedly.</b> Repeating it recomputes the same figures and preserves an
     * existing AI narrative, so a client pressing refresh cannot rewrite the advice it is looking at.
     * The one thing that does change it is a provider answering again on a month whose stored text is
     * rule-based - which is the intended effect of asking for a fresh generation.
     *
     * <p><b>The provider's context is read after the procedure, not before.</b> The totals handed to
     * it are the row the call just produced and the per-category figures for that same month, so the
     * narrative describes the figures the stored insight reports. Reading them first would compute the
     * month twice and let the two disagree.
     *
     * @param month {@code yyyy-MM}, or null/blank for the current month
     * @throws RequestValidationException if {@code month} is present but is not a valid month
     */
    @Transactional
    public MonthlyInsightResponse generate(AuthenticatedUser principal, String month) {
        Long userId = principal.userId();
        LocalDate periodMonth = resolveMonth(month);

        insightWriteDao.generate(userId, periodMonth);

        InsightRow row = insightViewDao.find(userId, periodMonth)
                .orElseThrow(() -> new IllegalStateException(
                        "Generating an insight for " + periodMonth + " stored no row."));

        // Overlay the provider's narrative, if there is one to overlay. An empty answer here is the
        // ordinary case rather than an error: no credential, the switch off, a rate limit or a
        // timeout all leave the rule-based summary the procedure wrote standing.
        boolean narrated = narrate(userId, periodMonth, row);

        InsightRow stored = narrated
                ? insightViewDao.find(userId, periodMonth).orElse(row)
                : row;

        log.info("Insight generated userId={} periodMonth={} generatedBy={} provider={}",
                userId, periodMonth, stored.generatedBy(), aiSuggestionPort.isExternalProvider());

        return insightMapper.toInsightResponse(stored);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * UC-17: asks the configured provider for a narrative, and stores it if it gave one.
     *
     * <p><b>The request is built from the row and the month's category totals, and contains no
     * identifier.</b> A month label, a currency code, three totals and a list of category names with
     * their totals - nothing that names the student, and nothing that names the categories to a
     * database key. That is the whole of what leaves the server for this feature, and it is why the
     * request type is built here rather than handed in: the caller cannot widen it.
     *
     * <p><b>The answer is checked before it is stored.</b> A narrative with no summary and no advice is
     * discarded rather than written - storing it would replace readable rule-based prose with an empty
     * string and mark the row as a provider's work. A partial answer is accepted on the half that
     * arrived, because a summary alone is still the thing the endpoint exists to show.
     *
     * <p><b>A write that matched no row is a fault, not a no-op.</b> The row was read in this same
     * transaction a moment ago, and nothing deletes an insight except the student's own account
     * (BR-02's cascade), so an update affecting zero rows means the row moved underneath the request.
     * Reporting success would leave the client with a response describing text the table does not
     * hold.
     *
     * @return true when a provider's narrative was stored
     */
    private boolean narrate(Long userId, LocalDate periodMonth, InsightRow row) {
        List<MonthlyCategoryTotal> totals =
                insightViewDao.findExpenseTotalsByCategory(userId, periodMonth);

        MonthlyNarrativeRequest request = new MonthlyNarrativeRequest(
                insightMapper.toMonthString(periodMonth),
                settingReader.getString(SettingReader.APP_CURRENCY, DEFAULT_CURRENCY),
                row.totalIncome(),
                row.totalExpense(),
                row.netAmount(),
                totals.stream()
                        .map(total -> new MonthlyNarrativeRequest.CategoryTotal(
                                total.categoryName(), total.totalAmount()))
                        .toList());

        Optional<MonthlyNarrative> narrative = aiSuggestionPort.narrateMonth(request);
        if (narrative.isEmpty()) {
            return false;
        }

        MonthlyNarrative answer = narrative.get();
        if (isBlank(answer.summary()) && isBlank(answer.advice())) {
            log.warn("Insight narrative discarded as empty userId={} periodMonth={}",
                    userId, periodMonth);
            return false;
        }

        int updated = insightWriteDao.writeAiNarrative(
                userId, periodMonth, answer.summary(), answer.advice(), answer.model());

        if (updated != 1) {
            throw new IllegalStateException("Insight narrative wrote " + updated
                    + " rows for " + periodMonth + ", which should be exactly one.");
        }

        return true;
    }

    /** The current month in the application's zone, as the first-of-month {@code DATE} the column holds. */
    private LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    /**
     * The month an insights request means: the named one, or the current month when none was given.
     *
     * <p>Parsing is strict - {@code YearMonth.parse} accepts {@code 2026-09} and refuses
     * {@code 2026-9} and {@code 2026-13} - so a malformed month cannot be silently read as a
     * different, valid one. The refusal names the parameter so a client can correct the request. It is
     * the same helper {@code TipService#resolveMonth} is, deliberately: two endpoints that both take
     * a {@code month} must not disagree about what one is.
     */
    private LocalDate resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return currentMonth();
        }
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError("month",
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
