package com.campuscoin.categorisation.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;

/**
 * Chooses which of the student's own learned mappings applies to a description (UC-08).
 *
 * <p><b>This is the deterministic half of UC-08, and it is the half that always works.</b> It needs no
 * provider, no key and no network: it compares the student's description against the student's own
 * table of corrections. That is what {@code AiSuggestionPort}'s contract leans on when it says an empty
 * result from a provider is an ordinary outcome - the feature is complete without one, and a deployment
 * that configures one gets better proposals, not a working feature.
 *
 * <p><b>The precedence is the point, not an implementation detail.</b> An exact match wins over a
 * substring match, and between two substring matches the longer keyword wins. Both are statements about
 * evidence: a rule learned from the whole description the student is typing again is the same
 * description, while a rule learned from a phrase it merely contains is a weaker claim - so
 * "Campus Cafe - lunch with Linh" is matched by a rule for the whole of it in preference to a rule for
 * "cafe", and a rule for "campus cafe" beats one for "cafe". The tie-break on the rule id exists only
 * so that two equally long keywords cannot make the answer depend on row order, which no test could
 * pin.
 *
 * <p><b>Normalisation lives here and nowhere else.</b> {@code category_rules}' own comment says the
 * stored keyword is "lower case, trimmed", and this class produces both sides of the comparison: the
 * stored keyword when a rule is learned, and the description when one is matched. A second
 * normalisation somewhere else could disagree with this one about, say, a trailing space, and the two
 * would then never match each other.
 *
 * <p>Stateless and free of any repository, so it is unit-tested directly with plain lists - the shape
 * {@code AnomalyDetector} has, and for the same reason: the comparison is the part that has to be
 * right, and a test that had to go through a database to exercise it would be testing something else.
 */
@Component
public class CategoryRuleMatcher {

    /**
     * The longest keyword that can be learned, matching {@code category_rules.keyword VARCHAR(80)}.
     *
     * <p>A description longer than this is not an error and does not stop the feature: the suggestion
     * is still computed and still returned. It simply does not teach the system a rule, because the
     * column could not hold the keyword and truncating one would produce a string that matches nothing
     * the student would ever type again.
     *
     * <p>Recorded as a limitation rather than worked around. Handling it properly means deriving a
     * shorter keyword from a long description - which word, chosen how? - and no document says. The
     * substring mode the column already supports is what a later build would use to do it.
     */
    static final int MAX_KEYWORD_LENGTH = 80;

    /**
     * The normalised form of a description: trimmed, then lower case.
     *
     * <p>Exactly the two steps {@code category_rules.keyword}'s comment names, and deliberately no
     * more. Collapsing runs of whitespace or stripping punctuation would be a guess at the stored form
     * that nothing documents, and a generated keyword that does not look like the comment says it
     * should is harder to defend than a missed match on a double space.
     *
     * <p>{@link Locale#ROOT} rather than the default locale: the Turkish locale lower-cases "I" to a
     * dotless "ı", which would make a keyword stored on one server fail to match on another. The
     * keyword is an opaque comparison key, so it must not depend on where the JVM happens to run.
     */
    public static String normalise(String description) {
        return description == null ? "" : description.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The keyword a description teaches, or {@code null} when it teaches none.
     *
     * <p>Returning {@code null} rather than an empty string makes "there is nothing to learn" a value
     * the caller must handle, which is what it is: a record with no description, or one whose reference
     * text is longer than the column can hold, still gets a suggestion - it just does not change what
     * the system remembers.
     *
     * <p><b>Public because UC-11 is a second caller.</b> {@code ImportRuleLearner} teaches a mapping for
     * each imported row, writing {@code RuleSource.IMPORT} - the member {@link RuleSource} records as
     * this build's own - and it must derive the keyword by exactly this rule. The alternative was for
     * that class to call {@link #normalise} and apply the length bound itself, which would put the
     * bound in two places: the column's width is a fact this class states once, and a second copy that
     * drifted would let an over-long keyword reach {@code uk_rule_user_keyword} and fail the insert.
     * Widening the method is the smaller change, and it keeps the direction of the dependency as it
     * already is - the import module already depends on this package for {@code CategorySuggester}.
     */
    public static String learnableKeyword(String description) {
        String normalised = normalise(description);

        if (normalised.isEmpty() || normalised.length() > MAX_KEYWORD_LENGTH) {
            return null;
        }
        return normalised;
    }

    /**
     * UC-08: the student's own rule for this description, if they have one.
     *
     * <p>An empty answer is the ordinary case for a description the student has not filed before, and
     * the caller's response is to consult the provider - which is why this returns
     * {@link Optional} rather than a nullable row. A blank description matches nothing: there is no
     * text to compare, and treating it as a substring of everything would suggest a category for a
     * record that says nothing at all.
     */
    public Optional<CategoryRuleRow> match(String description, List<CategoryRuleRow> rules) {
        String normalised = normalise(description);

        if (normalised.isEmpty()) {
            return Optional.empty();
        }

        for (CategoryRuleRow rule : rules) {
            if (rule.matchMode() == RuleMatchMode.EXACT && normalised.equals(rule.keyword())) {
                return Optional.of(rule);
            }
        }

        CategoryRuleRow longest = null;
        for (CategoryRuleRow rule : rules) {
            if (rule.matchMode() != RuleMatchMode.CONTAINS || !contains(normalised, rule.keyword())) {
                continue;
            }
            if (longest == null
                    || rule.keyword().length() > longest.keyword().length()
                    || (rule.keyword().length() == longest.keyword().length()
                            && Long.compare(rule.ruleId(), longest.ruleId()) < 0)) {
                longest = rule;
            }
        }

        return Optional.ofNullable(longest);
    }

    /**
     * Whether a keyword matches inside a description.
     *
     * <p>A blank keyword is refused rather than accepted: an empty string is contained in every
     * description, so honouring it would make one malformed rule decide every suggestion the student
     * is ever shown. The column is {@code NOT NULL} but not non-empty, so a hand-run insert can create
     * one, and this is where that is contained.
     */
    private static boolean contains(String normalised, String keyword) {
        return keyword != null && !keyword.isEmpty() && normalised.contains(keyword);
    }
}
