package com.campuscoin.categorisation.entity;

/**
 * The two values of {@code category_rules.match_mode} (UC-08).
 *
 * <p>Mirrors the column's ENUM exactly. The constant names are the members the database stores, and
 * nothing else is accepted: a JSON number in their place is rejected, because a number would be an
 * ordinal whose meaning changes if these constants were ever reordered - the reasoning
 * {@code CategoryType}, {@code TransactionSource}, {@code AnomalyFlagType} and {@code RecentAction} all
 * record.
 *
 * <p><b>The two members are a precedence, not an alternative.</b> {@code CategoryRuleMatcher} resolves
 * an {@code EXACT} match first and considers {@code CONTAINS} only if nothing matched exactly - in
 * Java, rather than leaving it to the order a query happened to return. The reason is that they are
 * different strengths of evidence: an exact match is the same description the student filed before,
 * while a substring match is a phrase that description merely contained, so a rule learned from
 * "Campus Cafe" must not outrank a rule learned from the whole, longer description of the very record
 * being categorised.
 *
 * <p>{@code uk_rule_user_keyword} is {@code (user_id, keyword, match_mode)}, so the same keyword can
 * legitimately exist twice for one student - once in each mode - and the pair is what a filing records.
 *
 * <p><b>This build writes {@code EXACT} rules only.</b> Every rule UC-08 learns is the whole normalised
 * description of the record the student filed, which is a keyword the student demonstrably types again.
 * {@code CONTAINS} is honoured by the matcher - a rule already stored in that mode is applied, and
 * {@code CategoryRuleDao} resolves the member by name rather than assuming - but nothing here produces
 * one, because deriving a substring from a longer description means choosing which words to keep, and
 * no document says how. Teaching a substring rule is the extension this member exists for; inventing
 * the choice now would be undocumented behaviour, so the mode is left unexercised rather than guessed
 * at.
 */
public enum RuleMatchMode {

    /** The description equals the keyword, both normalised - lower case, trimmed. */
    EXACT,

    /**
     * The description contains the keyword.
     *
     * <p>Stored in the schema and honoured by the matcher, and not yet produced by this build - see the
     * class note. It is the member that would let a rule learned from "Campus Cafe" match a later
     * "Campus Cafe - lunch with Linh", and it is also the member that can be wrong: a keyword short
     * enough to be a substring of unrelated descriptions would suggest a category for records that have
     * nothing to do with the phrase it was learned from.
     */
    CONTAINS
}
