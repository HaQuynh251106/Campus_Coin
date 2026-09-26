package com.campuscoin.categorisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;

/**
 * The UC-08 rule matching, tested directly.
 *
 * <p>A plain unit test, deliberately: {@link CategoryRuleMatcher} is a pure function of the rows it is
 * given, so nothing about it needs a database and no assertion here is weakened by the absence of one.
 * The rows are constructed as {@code CategoryRuleDao} would return them - a keyword already normalised,
 * a mode, an id - so what is pinned is the comparison the service actually runs.
 *
 * <p><b>What these tests are for.</b> This is the half of UC-08 that works with no AI provider at all,
 * so it is the half a deployment without a key depends on completely. Three things could make it
 * quietly wrong: the precedence between the two modes, the choice between two substring matches, and
 * the normalisation. Each is pinned below, because each fails silently - a wrong precedence still
 * returns a category, just not the one the student taught, and a normalisation that disagreed with the
 * stored form would simply stop matching anything without raising anything.
 */
class CategoryRuleMatcherTest {

    private static final Long FOOD = 4L;
    private static final Long TRANSPORT = 5L;
    private static final Long ENTERTAINMENT = 6L;

    private final CategoryRuleMatcher matcher = new CategoryRuleMatcher();

    private static CategoryRuleRow exact(long id, String keyword, Long categoryId) {
        return new CategoryRuleRow(id, keyword, RuleMatchMode.EXACT, categoryId,
                new BigDecimal("1.0000"));
    }

    private static CategoryRuleRow contains(long id, String keyword, Long categoryId) {
        return new CategoryRuleRow(id, keyword, RuleMatchMode.CONTAINS, categoryId,
                new BigDecimal("1.0000"));
    }

    // ------------------------------------------------------------------
    //  Normalisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A description is trimmed and lower cased, and nothing else")
    void normalisationIsTrimAndLowerCase() {
        // Exactly the two steps `category_rules.keyword`'s comment names. Collapsing inner whitespace
        // would be a guess at the stored form that nothing documents, so it deliberately does not.
        assertThat(CategoryRuleMatcher.normalise("  Campus Cafe  ")).isEqualTo("campus cafe");
        assertThat(CategoryRuleMatcher.normalise("CAMPUS CAFE")).isEqualTo("campus cafe");
        assertThat(CategoryRuleMatcher.normalise("Campus  Cafe")).isEqualTo("campus  cafe");
    }

    @Test
    @DisplayName("A null description normalises to nothing rather than failing")
    void nullNormalisesToEmpty() {
        assertThat(CategoryRuleMatcher.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("The same description normalises to the same keyword wherever the JVM runs")
    void normalisationDoesNotDependOnTheDefaultLocale() {
        // The Turkish locale lower-cases "I" to a dotless "ı", so a keyword stored on a server with one
        // default locale would stop matching one stored with another. The keyword is an opaque
        // comparison key, so it must not depend on where the process happens to run - which is why the
        // matcher pins Locale.ROOT.
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(CategoryRuleMatcher.normalise("INTERNET")).isEqualTo("internet");
            assertThat(CategoryRuleMatcher.learnableKeyword("INTERNET")).isEqualTo("internet");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    // ------------------------------------------------------------------
    //  What is worth learning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An ordinary description is learnable as itself")
    void ordinaryDescriptionIsLearnable() {
        assertThat(CategoryRuleMatcher.learnableKeyword("Campus Cafe")).isEqualTo("campus cafe");
    }

    @Test
    @DisplayName("A blank description teaches nothing, rather than teaching an empty keyword")
    void blankDescriptionTeachesNothing() {
        // An empty keyword is a substring of every description, so honouring one would let a single
        // blank record decide every suggestion the student is ever shown.
        assertThat(CategoryRuleMatcher.learnableKeyword(null)).isNull();
        assertThat(CategoryRuleMatcher.learnableKeyword("")).isNull();
        assertThat(CategoryRuleMatcher.learnableKeyword("   ")).isNull();
    }

    @Test
    @DisplayName("A description longer than the column teaches nothing rather than being truncated")
    void overlongDescriptionTeachesNothing() {
        // `category_rules.keyword` is VARCHAR(80), so a longer description has no home in the column.
        // Truncating one would store a keyword the student would never type again, and it would match
        // descriptions that had nothing to do with it. The suggestion is still computed and returned;
        // only the learning is skipped.
        String exactly80 = "c".repeat(CategoryRuleMatcher.MAX_KEYWORD_LENGTH);
        String eightyOne = "c".repeat(CategoryRuleMatcher.MAX_KEYWORD_LENGTH + 1);

        assertThat(CategoryRuleMatcher.learnableKeyword(exactly80)).hasSize(80);
        assertThat(CategoryRuleMatcher.learnableKeyword(eightyOne)).isNull();
    }

    // ------------------------------------------------------------------
    //  Matching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An exact match returns the rule's category")
    void exactMatchWins() {
        var match = matcher.match("Campus Cafe", List.of(exact(1, "campus cafe", FOOD)));

        assertThat(match).isPresent();
        assertThat(match.get().categoryId()).isEqualTo(FOOD);
    }

    @Test
    @DisplayName("A blank description matches nothing, and no rule is applied")
    void blankDescriptionMatchesNothing() {
        // No text to compare means nothing to propose. Treating a blank description as a substring of
        // everything would suggest a category for a record that says nothing at all.
        assertThat(matcher.match("", List.of(contains(1, "", ENTERTAINMENT)))).isEmpty();
        assertThat(matcher.match("   ", List.of(exact(1, "campus cafe", FOOD), contains(2, "", FOOD))))
                .isEmpty();
    }

    @Test
    @DisplayName("An empty rule set matches nothing")
    void noRulesMatchNothing() {
        assertThat(matcher.match("Campus Cafe", List.of())).isEmpty();
    }

    @Test
    @DisplayName("An exact match outranks a longer substring match")
    void exactOutranksContainsEvenWhenShorter() {
        // The load-bearing precedence. "Campus Cafe" filed under Transport is what the student said;
        // a substring rule learned from a longer phrase that happens to contain "campus cafe" is a
        // weaker claim and must not displace it.
        List<CategoryRuleRow> rules = List.of(
                contains(1, "campus cafe lunch with linh every tuesday", TRANSPORT),
                exact(2, "campus cafe", FOOD));

        var match = matcher.match("Campus Cafe", rules);

        assertThat(match).isPresent();
        assertThat(match.get().categoryId()).isEqualTo(FOOD);
    }

    @Test
    @DisplayName("Between two substring matches the longer keyword wins")
    void longerSubstringWins() {
        // More of the description accounted for is more evidence. A rule for "cafe" would match this
        // description too, but "campus cafe" explains more of it.
        List<CategoryRuleRow> rules = List.of(
                contains(1, "cafe", ENTERTAINMENT),
                contains(2, "campus cafe", FOOD));

        var match = matcher.match("Campus Cafe - lunch with Linh", rules);

        assertThat(match).isPresent();
        assertThat(match.get().categoryId()).isEqualTo(FOOD);
    }

    @Test
    @DisplayName("Two equally long keywords are broken by the rule id, so the answer is stable")
    void equalLengthSubstringsAreBrokenById() {
        // Otherwise the answer would depend on the order the query happened to return, which no test
        // could pin and no reviewer could rely on. The lower id wins, and it wins in both orders.
        List<CategoryRuleRow> forward = List.of(
                contains(7, "campus", FOOD),
                contains(3, "burger", TRANSPORT));
        List<CategoryRuleRow> reversed = List.of(
                contains(3, "burger", TRANSPORT),
                contains(7, "campus", FOOD));

        assertThat(matcher.match("campus burger", forward).orElseThrow().categoryId())
                .isEqualTo(TRANSPORT);
        assertThat(matcher.match("campus burger", reversed).orElseThrow().categoryId())
                .isEqualTo(TRANSPORT);
    }

    @Test
    @DisplayName("A substring rule with a blank keyword matches nothing")
    void blankKeywordIsRefused() {
        // The column is NOT NULL but not non-empty, so a hand-run insert can create one - and an empty
        // keyword is contained in every description, so honouring it would let one malformed rule
        // decide every suggestion the student is shown.
        assertThat(matcher.match("Campus Cafe", List.of(contains(1, "", FOOD)))).isEmpty();
    }

    @Test
    @DisplayName("A description that neither equals nor contains any keyword matches nothing")
    void unrelatedDescriptionMatchesNothing() {
        List<CategoryRuleRow> rules = List.of(
                exact(1, "campus cafe", FOOD),
                contains(2, "hostel", TRANSPORT));

        assertThat(matcher.match("Bus ticket to Hanoi", rules)).isEmpty();
    }

    @Test
    @DisplayName("Case and padding do not stop a match")
    void matchingNormalisesBothSides() {
        var match = matcher.match("   CAMPUS CAFE   ", List.of(exact(1, "campus cafe", FOOD)));

        assertThat(match).isPresent();
        assertThat(match.get().categoryId()).isEqualTo(FOOD);
    }
}
