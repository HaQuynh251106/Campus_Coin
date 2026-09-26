package com.campuscoin.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.ai.AiSuggestionPort;
import com.campuscoin.common.ai.CategorySuggestion;
import com.campuscoin.common.ai.CategorySuggestionRequest;
import com.campuscoin.common.ai.MonthlyNarrative;
import com.campuscoin.common.ai.MonthlyNarrativeRequest;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.common.setting.entity.SystemSetting;
import com.campuscoin.common.setting.repository.SystemSettingRepository;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.entity.InsightGeneratedBy;
import com.campuscoin.insight.entity.InsightRow;
import com.campuscoin.insight.entity.MonthlyCategoryTotal;
import com.campuscoin.insight.mapper.InsightMapper;
import com.campuscoin.insight.repository.InsightViewDao;
import com.campuscoin.insight.repository.InsightWriteDao;

/**
 * UC-17's AI overlay and its month handling, tested directly rather than through the API.
 *
 * <p><b>Why this is a unit test while the rest of the module is not.</b> No AI credential is configured
 * for the integration suite, so {@code AiSuggestionPort} there is the no-op implementation and the
 * provider path is unreachable through HTTP. Its behaviour is nevertheless the part of UC-17 that most
 * needs pinning - what leaves the server, what happens when nothing comes back, and what happens when
 * something unusable comes back - and every one of those is one line here with a stub port and a
 * fixture of dated transactions otherwise. {@code InsightApiIT} proves the wiring and the rule-based
 * path end to end; this file proves the overlay's decisions.
 *
 * <p><b>The port is stubbed, the currency reader is real.</b> {@link RecordingPort} records what it was
 * asked and answers whatever the test told it to, which is what makes the two assertions that matter
 * possible: that a provider is <em>not</em> consulted when no narrative is to be stored, and that the
 * request it receives carries no identifier. {@link SettingReader} runs over a stubbed repository, so
 * the currency the request names is read the way the service reads it rather than passed in.
 */
class InsightNarrativeTest {

    private static final Long USER_ID = 7L;

    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    private static final AuthenticatedUser PRINCIPAL =
            new AuthenticatedUser(USER_ID, "a.student@student.campuscoin.edu", "A Student",
                    UserRole.STUDENT, "session-hash");

    private final InsightViewDao viewDao = mock(InsightViewDao.class);
    private final InsightWriteDao writeDao = mock(InsightWriteDao.class);
    private final RecordingPort port = new RecordingPort();

    private final InsightService service = new InsightService(
            viewDao, writeDao, new InsightMapper(), port, currencyReader());

    // ==================================================================
    //  The provider's answer is stored, and only the two sentences are
    // ==================================================================

    @Test
    @DisplayName("UC-17: a provider's narrative is stored and the response says a provider wrote it")
    void aProvidersNarrativeIsStoredAndReported() {
        givenStoredRows(ruleBasedRow(), aiRow("Written by a model.", "Advised by a model."));
        givenCategoryTotals(new MonthlyCategoryTotal("Food", new BigDecimal("95.00")));
        port.answers(new MonthlyNarrative("Written by a model.", "Advised by a model.", "gemini-3.5-flash"));
        when(writeDao.writeAiNarrative(any(), any(), any(), any(), any())).thenReturn(1);

        MonthlyInsightResponse response = service.generate(PRINCIPAL, "2026-08");

        // The overlay writes the two sentences, the model that produced them, and the marker that makes
        // the text survive a later run - all in one statement, because the procedure preserves
        // summary_text only for a row it can see is a provider's.
        verify(writeDao).writeAiNarrative(USER_ID, MONTH, "Written by a model.", "Advised by a model.",
                "gemini-3.5-flash");

        assertThat(response.generatedBy()).isEqualTo(InsightGeneratedBy.AI);
        assertThat(response.model()).isEqualTo("gemini-3.5-flash");
        assertThat(response.summary()).isEqualTo("Written by a model.");
        assertThat(response.advice()).isEqualTo("Advised by a model.");

        // The figures are the procedure's and are not rewritten from the provider's prose.
        assertThat(response.totalExpense()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("UC-17: the request carries aggregates only - no user id, no category id, no record")
    void theRequestCarriesAggregatesOnly() {
        givenStoredRows(ruleBasedRow(), aiRow("s", "a"));
        givenCategoryTotals(
                new MonthlyCategoryTotal("Food", new BigDecimal("95.00")),
                new MonthlyCategoryTotal("Transport", new BigDecimal("30.00")));
        port.answers(new MonthlyNarrative("s", "a", "gemini-3.5-flash"));
        when(writeDao.writeAiNarrative(any(), any(), any(), any(), any())).thenReturn(1);

        service.generate(PRINCIPAL, "2026-08");

        // Compared field for field against the value it must be, rather than inspected for absent keys:
        // the type has six components and none of them can name a student, a category or a record.
        assertThat(port.requests).containsExactly(new MonthlyNarrativeRequest(
                "2026-08",
                "USD",
                new BigDecimal("260.00"),
                new BigDecimal("95.00"),
                new BigDecimal("165.00"),
                List.of(new MonthlyNarrativeRequest.CategoryTotal("Food", new BigDecimal("95.00")),
                        new MonthlyNarrativeRequest.CategoryTotal("Transport", new BigDecimal("30.00")))));

        // And the request's own shape cannot carry an identifier: six components, asserted literally so
        // a seventh added to the record fails a test instead of quietly widening what is disclosed.
        assertThat(MonthlyNarrativeRequest.class.getRecordComponents()).hasSize(6);
    }

    // ==================================================================
    //  Nothing useful came back: the rule-based text stands
    // ==================================================================

    @Test
    @DisplayName("UC-17: no provider, no overlay - the rule-based text is what the student gets")
    void anAbsentProviderLeavesTheRuleBasedText() {
        givenStoredRows(ruleBasedRow());
        givenCategoryTotals(new MonthlyCategoryTotal("Food", new BigDecimal("95.00")));
        // The ordinary case in a deployment with no credential: the port answers empty rather than
        // throwing, and the request still succeeds.
        port.answersNothing();

        MonthlyInsightResponse response = service.generate(PRINCIPAL, "2026-08");

        verify(writeDao, never()).writeAiNarrative(any(), any(), any(), any(), any());
        assertThat(response.generatedBy()).isEqualTo(InsightGeneratedBy.RULE_BASED);
        assertThat(response.model()).isNull();
        assertThat(response.summary()).isEqualTo("Rule-based summary.");
    }

    @Test
    @DisplayName("UC-17: a narrative with nothing in it is discarded rather than stored")
    void anEmptyNarrativeIsDiscarded() {
        givenStoredRows(ruleBasedRow());
        givenCategoryTotals();
        // Two blank strings are what a provider returns when it declined to answer in words. Storing
        // them would replace readable rule-based prose with an empty string and mark the row as a
        // provider's work, which is worse than keeping what the procedure wrote.
        port.answers(new MonthlyNarrative("  ", "", "gemini-3.5-flash"));

        MonthlyInsightResponse response = service.generate(PRINCIPAL, "2026-08");

        verify(writeDao, never()).writeAiNarrative(any(), any(), any(), any(), any());
        assertThat(response.generatedBy()).isEqualTo(InsightGeneratedBy.RULE_BASED);
        assertThat(response.summary()).isEqualTo("Rule-based summary.");
    }

    @Test
    @DisplayName("UC-17: half an answer is stored, because a summary alone is what the endpoint shows")
    void aPartialNarrativeIsAccepted() {
        givenStoredRows(ruleBasedRow(), aiRow("Only a summary.", null, null));
        givenCategoryTotals();
        port.answers(new MonthlyNarrative("Only a summary.", null, null));
        when(writeDao.writeAiNarrative(any(), any(), any(), any(), any())).thenReturn(1);

        MonthlyInsightResponse response = service.generate(PRINCIPAL, "2026-08");

        verify(writeDao).writeAiNarrative(USER_ID, MONTH, "Only a summary.", null, null);
        assertThat(response.summary()).isEqualTo("Only a summary.");
        assertThat(response.advice()).isNull();
        // No model was reported, and none is invented: the column is what lets an operator tell which
        // model produced a stored insight.
        assertThat(response.model()).isNull();
    }

    @Test
    @DisplayName("UC-17: a write that matched no row is a fault, because the row was read a moment ago")
    void aWriteThatMatchedNothingIsAFault() {
        givenStoredRows(ruleBasedRow());
        givenCategoryTotals();
        port.answers(new MonthlyNarrative("s", "a", "gemini-3.5-flash"));
        when(writeDao.writeAiNarrative(any(), any(), any(), any(), any())).thenReturn(0);

        // Nothing deletes an insight except the student's own account, so an update affecting zero rows
        // means the row moved underneath the request. Reporting success would answer with text the table
        // does not hold.
        assertThatThrownBy(() -> service.generate(PRINCIPAL, "2026-08"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which should be exactly one");
    }

    // ==================================================================
    //  Reading and the month
    // ==================================================================

    @Test
    @DisplayName("UC-17: a month with no insight is a 404, not a month of zeroes")
    void readingAMonthWithNoInsightIsANotFound() {
        when(viewDao.find(USER_ID, MONTH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInsight(PRINCIPAL, "2026-08"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UC-17: no month named means the current month in the application's zone")
    void anAbsentMonthMeansTheCurrentMonth() {
        LocalDate currentMonth = YearMonth.from(
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))).atDay(1);
        givenStoredRowsFor(currentMonth, ruleBasedRowAt(currentMonth));
        givenCategoryTotals();
        port.answersNothing();

        service.generate(PRINCIPAL, null);
        service.generate(PRINCIPAL, "   ");

        verify(writeDao, times(2)).generate(USER_ID, currentMonth);
    }

    @Test
    @DisplayName("UC-17: a named month is parsed strictly and never read as a different one")
    void aNamedMonthIsParsedStrictly() {
        givenStoredRows(ruleBasedRow());
        givenCategoryTotals();
        port.answersNothing();

        service.generate(PRINCIPAL, "2026-08");

        verify(writeDao).generate(USER_ID, MONTH);
    }

    @Test
    @DisplayName("UC-17 A2: a month that is not a month is refused with a field error naming `month`")
    void aMalformedMonthIsRefused() {
        for (String bad : new String[] {"2026-13", "2026-9", "not-a-month", "2026-09-01", "2026"}) {
            assertThatThrownBy(() -> service.getInsight(PRINCIPAL, bad))
                    .as("month=%s", bad)
                    .isInstanceOfSatisfying(RequestValidationException.class, ex ->
                            assertThat(ex.getFieldErrors()).singleElement()
                                    .satisfies(error -> assertThat(error.field()).isEqualTo("month")));
        }

        // Nothing reached the database: the refusal happens before any query, so a malformed month
        // cannot even be attempted against a table.
        verify(viewDao, never()).find(any(), any());
    }

    @Test
    @DisplayName("UC-17: the months list is read for the caller and nobody else")
    void theMonthsListIsReadForTheCaller() {
        when(viewDao.findMonths(USER_ID)).thenReturn(List.of(MONTH));

        assertThat(service.listMonths(PRINCIPAL).months()).containsExactly("2026-08");

        verify(viewDao).findMonths(USER_ID);
    }

    // ==================================================================
    //  Fixtures
    // ==================================================================

    /** The row the procedure would have just written: rule-based, with the month's figures. */
    private static InsightRow ruleBasedRow() {
        return ruleBasedRowAt(MONTH);
    }

    private static InsightRow ruleBasedRowAt(LocalDate month) {
        return new InsightRow(month, "Rule-based summary.",
                "You are keeping a positive balance. Consider moving the surplus toward your savings "
                        + "goal at the start of the month.",
                List.of(), new BigDecimal("260.00"), new BigDecimal("95.00"), new BigDecimal("165.00"),
                InsightGeneratedBy.RULE_BASED, null, month.atStartOfDay().plusHours(20));
    }

    /** The same month as the row reads after the overlay wrote over it. */
    private static InsightRow aiRow(String summary, String advice) {
        return aiRow(summary, advice, "gemini-3.5-flash");
    }

    /**
     * The row as it reads when the provider reported no model.
     *
     * <p>{@code model_name} is what the provider said, not a constant this build supplies, so a row
     * written from an answer that named no model stores null - which is the case the assertion using
     * this overload pins.
     */
    private static InsightRow aiRow(String summary, String advice, String model) {
        return new InsightRow(MONTH, summary, advice, List.of(), new BigDecimal("260.00"),
                new BigDecimal("95.00"), new BigDecimal("165.00"), InsightGeneratedBy.AI,
                model, MONTH.atStartOfDay().plusHours(20));
    }

    private void givenStoredRows(InsightRow... rows) {
        givenStoredRowsFor(MONTH, rows);
    }

    /** The row the read returns, in order: the one after the procedure, then the one after the overlay. */
    private void givenStoredRowsFor(LocalDate month, InsightRow... rows) {
        when(viewDao.find(USER_ID, month))
                .thenReturn(Optional.of(rows[0]))
                .thenReturn(rows.length > 1 ? Optional.of(rows[1]) : Optional.of(rows[0]));
    }

    private void givenCategoryTotals(MonthlyCategoryTotal... totals) {
        when(viewDao.findExpenseTotalsByCategory(USER_ID, MONTH)).thenReturn(List.of(totals));
    }

    /** A reader over the seeded currency row, so the request's currency is read as the service reads it. */
    private static SettingReader currencyReader() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        SystemSetting currency = mock(SystemSetting.class);
        when(currency.getSettingValue()).thenReturn("USD");
        when(repository.findBySettingKey(SettingReader.APP_CURRENCY)).thenReturn(Optional.of(currency));
        return new SettingReader(repository);
    }

    /** A port that answers whatever it is told and remembers every request it was given. */
    private static final class RecordingPort implements AiSuggestionPort {

        private Optional<MonthlyNarrative> answer = Optional.empty();
        private final List<MonthlyNarrativeRequest> requests = new ArrayList<>();

        private void answers(MonthlyNarrative narrative) {
            this.answer = Optional.of(narrative);
        }

        private void answersNothing() {
            this.answer = Optional.empty();
        }

        @Override
        public Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request) {
            return Optional.empty();
        }

        @Override
        public Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request) {
            requests.add(request);
            return answer;
        }

        @Override
        public boolean isExternalProvider() {
            return true;
        }
    }
}
