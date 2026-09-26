package com.campuscoin.categorisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;
import com.campuscoin.categorisation.entity.SuggestionCandidate;
import com.campuscoin.categorisation.entity.SuggestionSource;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.ai.AiSuggestionPort;
import com.campuscoin.common.ai.CategorySuggestion;
import com.campuscoin.common.ai.CategorySuggestionRequest;
import com.campuscoin.common.ai.MonthlyNarrative;
import com.campuscoin.common.ai.MonthlyNarrativeRequest;

/**
 * The UC-08 suggestion order, tested directly.
 *
 * <p>A plain unit test, deliberately: {@link CategorySuggester} takes rows, candidates and a port, so
 * nothing about it needs a database. The port is replaced by {@link RecordingPort}, which records what
 * it was asked and answers whatever the test told it to - which is what makes two of the assertions
 * below possible at all: that the provider is <em>not</em> consulted when a rule already answers, and
 * that nothing it says is used until it has been resolved against the student's own categories.
 *
 * <p><b>What these tests are for.</b> The order of the three sources is the whole design: a correction
 * outranks a guess, and both outrank silence. Two of those steps fail silently if they are wrong - a
 * provider consulted first would still return a plausible category, just the wrong one, and a provider
 * whose answer was trusted without being resolved would return a name that could not become anything
 * but which no test would notice unless it asserted on the resolved id rather than the name.
 */
class CategorySuggesterTest {

    private static final Long FOOD = 4L;
    private static final Long TRANSPORT = 5L;
    private static final Long PERSONAL_FOOD = 91L;

    private static final List<SuggestionCandidate> CANDIDATES = List.of(
            new SuggestionCandidate(FOOD, "Food", CategoryType.EXPENSE, true),
            new SuggestionCandidate(TRANSPORT, "Transport", CategoryType.EXPENSE, true),
            new SuggestionCandidate(PERSONAL_FOOD, "Food", CategoryType.EXPENSE, false));

    private final RecordingPort port = new RecordingPort();
    private final CategorySuggester suggester =
            new CategorySuggester(port, new CategoryRuleMatcher());

    /** A port that answers whatever it is told and remembers whether it was asked anything. */
    private static final class RecordingPort implements AiSuggestionPort {

        private Optional<CategorySuggestion> answer = Optional.empty();
        private final List<CategorySuggestionRequest> requests = new ArrayList<>();

        private void answers(CategorySuggestion suggestion) {
            this.answer = Optional.of(suggestion);
        }

        @Override
        public Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request) {
            requests.add(request);
            return answer;
        }

        @Override
        public Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request) {
            return Optional.empty();
        }

        @Override
        public boolean isExternalProvider() {
            return false;
        }
    }

    private static CategoryRuleRow rule(String keyword, Long categoryId) {
        return new CategoryRuleRow(1L, keyword, RuleMatchMode.EXACT, categoryId,
                new BigDecimal("1.0000"));
    }

    // ------------------------------------------------------------------
    //  The student's own rules come first
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A learned mapping answers, and the provider is never asked")
    void ruleAnswersWithoutConsultingTheProvider() {
        // The second half is the assertion that matters: section 7 restricts what may leave the
        // server, and a description the student has already filed is text that need not be sent
        // anywhere. The cost of the feature also scales with their new descriptions rather than with
        // every filing.
        var advice = suggester.advise("Campus Cafe", List.of(rule("campus cafe", FOOD)), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.RULE);
        assertThat(advice.categoryId()).isEqualTo(FOOD);
        assertThat(advice.categoryName()).isEqualTo("Food");
        assertThat(advice.type()).isEqualTo(CategoryType.EXPENSE);
        assertThat(advice.reason()).isNull();
        assertThat(port.requests).isEmpty();
    }

    @Test
    @DisplayName("A rule naming a category the student no longer has answers nothing")
    void ruleForARetiredCategoryIsNotApplied() {
        // Retiring a category is BR-07, and "you may not file new records here" has to mean the system
        // does not propose it either. The rule row survives - nothing deletes it - so the check is
        // against the active candidate list, and the answer falls through to the provider.
        var advice = suggester.advise("Campus Cafe", List.of(rule("campus cafe", 404L)), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
        assertThat(advice.categoryId()).isNull();
    }

    // ------------------------------------------------------------------
    //  The provider, when there is no rule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A provider's proposal is resolved to a category the student may actually use")
    void providerProposalIsResolvedToAnId() {
        port.answers(new CategorySuggestion("Transport", "EXPENSE", 0.8, "looks like a bus fare"));

        var advice = suggester.advise("Bus ticket", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.AI);
        assertThat(advice.categoryId()).isEqualTo(TRANSPORT);
        assertThat(advice.categoryName()).isEqualTo("Transport");
        assertThat(advice.type()).isEqualTo(CategoryType.EXPENSE);
        assertThat(advice.confidence()).isEqualByComparingTo("0.8");
        assertThat(advice.reason()).isEqualTo("looks like a bus fare");
    }

    @Test
    @DisplayName("A provider is sent the description and the category names, and nothing else")
    void theProviderReceivesTheMinimum() {
        port.answers(new CategorySuggestion("Food", "EXPENSE", 0.5, null));

        suggester.advise("Campus Cafe", List.of(), CANDIDATES);

        assertThat(port.requests).hasSize(1);
        CategorySuggestionRequest sent = port.requests.get(0);
        assertThat(sent.description()).isEqualTo("Campus Cafe");
        assertThat(sent.categoryNames()).containsExactly(
                new CategorySuggestionRequest.Candidate("Food", "EXPENSE"),
                new CategorySuggestionRequest.Candidate("Transport", "EXPENSE"),
                new CategorySuggestionRequest.Candidate("Food", "EXPENSE"));
    }

    @Test
    @DisplayName("A name the student does not have is discarded, however plausible it sounds")
    void hallucinatedCategoryIsDiscarded() {
        // A hallucinated category, a name belonging to another student, and a name that does not exist
        // all produce the same outcome: no suggestion. None of them can become a foreign key.
        port.answers(new CategorySuggestion("Groceries", "EXPENSE", 0.9, "looks like a grocery run"));

        var advice = suggester.advise("Campus Cafe", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
        assertThat(advice.categoryId()).isNull();
        assertThat(advice.categoryName()).isNull();
        assertThat(advice.reason()).isNull();
    }

    @Test
    @DisplayName("A proposal of the wrong type is discarded, even when the name is right")
    void wrongTypeIsDiscarded() {
        // BR-05 makes the category's type the record's type, so "Food as income" is wrong in a way a
        // name comparison alone cannot catch.
        port.answers(new CategorySuggestion("Food", "INCOME", 0.9, "looks like a refund"));

        var advice = suggester.advise("Campus Cafe", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
    }

    @Test
    @DisplayName("A proposal is matched case-insensitively and around padding")
    void proposalMatchingIsForgivingAboutCaseAndSpace() {
        // A model asked for "Transport" may answer " transport ". The difference is cosmetic, and
        // rejecting it would be refusing a correct answer over whitespace.
        port.answers(new CategorySuggestion("  transport ", "expense", 0.7, null));

        var advice = suggester.advise("Bus ticket", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.AI);
        assertThat(advice.categoryId()).isEqualTo(TRANSPORT);
    }

    @Test
    @DisplayName("A name held by both a shared and a personal category resolves to the personal one")
    void ambiguousNamePrefersTheStudentsOwnCategory() {
        // The rule `sp_apply_csv_batch` applies to the same question, so the two cannot disagree:
        // ordering `(user_id IS NULL)` first prefers the student's own row.
        port.answers(new CategorySuggestion("Food", "EXPENSE", 0.6, null));

        var advice = suggester.advise("Campus Cafe", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.AI);
        assertThat(advice.categoryId()).isEqualTo(PERSONAL_FOOD);
    }

    @Test
    @DisplayName("A proposal with no name or no type is discarded rather than half-used")
    void incompleteProposalIsDiscarded() {
        port.answers(new CategorySuggestion(null, "EXPENSE", 0.9, null));
        assertThat(suggester.advise("Campus Cafe", List.of(), CANDIDATES).source())
                .isEqualTo(SuggestionSource.NONE);

        port.answers(new CategorySuggestion("Food", null, 0.9, null));
        assertThat(suggester.advise("Campus Cafe", List.of(), CANDIDATES).source())
                .isEqualTo(SuggestionSource.NONE);
    }

    // ------------------------------------------------------------------
    //  Silence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No rule and no provider proposal is NONE, not an error")
    void nothingToProposeIsAnAnswer() {
        // "We looked and have nothing to propose" has to be sayable, which is why NONE is a member of
        // the enum rather than an absent field.
        var advice = suggester.advise("Campus Cafe", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
        assertThat(advice.categoryId()).isNull();
        assertThat(advice.confidence()).isNull();
    }

    @Test
    @DisplayName("A blank description proposes nothing and does not reach the provider")
    void blankDescriptionDoesNotReachTheProvider() {
        // There is no text to classify, so sending one would spend a call to learn nothing - and the
        // provider would be asked to choose from a list on the strength of whitespace.
        port.answers(new CategorySuggestion("Food", "EXPENSE", 0.9, null));

        var advice = suggester.advise("   ", List.of(), CANDIDATES);

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
        assertThat(port.requests).isEmpty();
    }

    @Test
    @DisplayName("A student with no categories at all is proposed nothing")
    void noCandidatesProposesNothing() {
        port.answers(new CategorySuggestion("Food", "EXPENSE", 0.9, null));

        var advice = suggester.advise("Campus Cafe", List.of(), List.of());

        assertThat(advice.source()).isEqualTo(SuggestionSource.NONE);
    }
}
