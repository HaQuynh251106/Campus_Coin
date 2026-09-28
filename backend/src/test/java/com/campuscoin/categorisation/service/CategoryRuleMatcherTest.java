package com.campuscoin.categorisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;

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

    @Test
    @DisplayName("A description is trimmed and lower cased, and nothing else")
    void normalisationIsTrimAndLowerCase() {

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

        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(CategoryRuleMatcher.normalise("INTERNET")).isEqualTo("internet");
            assertThat(CategoryRuleMatcher.learnableKeyword("INTERNET")).isEqualTo("internet");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("An ordinary description is learnable as itself")
    void ordinaryDescriptionIsLearnable() {
        assertThat(CategoryRuleMatcher.learnableKeyword("Campus Cafe")).isEqualTo("campus cafe");
    }

    @Test
    @DisplayName("A blank description teaches nothing, rather than teaching an empty keyword")
    void blankDescriptionTeachesNothing() {

        assertThat(CategoryRuleMatcher.learnableKeyword(null)).isNull();
        assertThat(CategoryRuleMatcher.learnableKeyword("")).isNull();
        assertThat(CategoryRuleMatcher.learnableKeyword("   ")).isNull();
    }

    @Test
    @DisplayName("A description longer than the column teaches nothing rather than being truncated")
    void overlongDescriptionTeachesNothing() {

        String exactly80 = "c".repeat(CategoryRuleMatcher.MAX_KEYWORD_LENGTH);
        String eightyOne = "c".repeat(CategoryRuleMatcher.MAX_KEYWORD_LENGTH + 1);

        assertThat(CategoryRuleMatcher.learnableKeyword(exactly80)).hasSize(80);
        assertThat(CategoryRuleMatcher.learnableKeyword(eightyOne)).isNull();
    }

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
