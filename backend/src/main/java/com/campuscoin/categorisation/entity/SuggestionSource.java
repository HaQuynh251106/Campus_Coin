package com.campuscoin.categorisation.entity;

/**
 * Where a category suggestion came from (UC-08).
 *
 * <p><b>Not a column.</b> Unlike {@link RuleMatchMode}, {@link RuleSource} and
 * {@code AnomalyFlagType}, this enum mirrors nothing in the schema: it is published so a client can
 * say <em>why</em> it is showing a proposal, and it is the difference between "we remember how you
 * filed this before" and "the AI service thinks this looks like Food". BR-13 requires every proposal
 * to be presented as a suggestion the student may override, and a proposal whose origin is not stated
 * cannot be presented honestly.
 *
 * <p>It lives in {@code entity} with the other two because it is the same kind of thing - this
 * module's vocabulary, written once and used by both the service and the response - and because
 * placing it in {@code dto} would suggest it is only ever serialised, which is not the case: the
 * recorder branches on it to decide whether a learned rule carries the suggestion's confidence or the
 * certain value a confirmed mapping gets.
 */
public enum SuggestionSource {

    /**
     * No suggestion. The student is choosing freely, and {@code CategorySuggester} has nothing to
     * propose - either they have no rule for this description and no provider is configured, or the
     * provider's answer named a category they may not file under.
     *
     * <p>A member rather than an absent field, for the reason {@code AnomalyFlagType.NONE} is one:
     * "we looked and have nothing to propose" has to be sayable, and a client that received an empty
     * body instead could not tell it from a malformed response.
     */
    NONE,

    /** The student's own learned {@code category_rules} mapping for this description. */
    RULE,

    /** The configured {@code AiSuggestionPort}, validated against the categories the student may use. */
    AI
}
