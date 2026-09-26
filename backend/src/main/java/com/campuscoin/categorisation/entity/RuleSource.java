package com.campuscoin.categorisation.entity;

/**
 * The three values of {@code category_rules.source} (UC-08, UC-11).
 *
 * <p>Mirrors the column's ENUM exactly, for the reason {@link RuleMatchMode} records.
 *
 * <p><b>What each member means in this build, and why {@code ACCEPTED} covers "no suggestion was
 * made".</b> UC-08 B6 asks the system to learn from the student's corrections, and
 * {@code category_rules} records both halves of that:
 *
 * <ul>
 *   <li>{@code OVERRIDE} - the student was shown a suggestion and filed the record under a different
 *       category. This is the correction the use case names, and it is the strongest signal the table
 *       holds: it is the record of the system having been wrong.</li>
 *   <li>{@code ACCEPTED} - the filing did not contradict a suggestion, either because the student kept
 *       the one they were shown or because no suggestion was made at all and they chose the category
 *       themselves.</li>
 *   <li>{@code IMPORT} - the mapping came from a CSV import rather than from a filing in the
 *       interface (UC-11). {@code ImportRuleLearner} writes it, once per imported row at the commit,
 *       from the category the row was actually filed under.</li>
 * </ul>
 *
 * <p><b>The "no suggestion" case is why {@code ACCEPTED} is read this way.</b> A rule is learned from
 * every filing, including the first one for a merchant - and the first filing is exactly the case
 * where no rule existed yet to suggest anything. If that case learned nothing, the feature could never
 * learn at all: the first "Campus Cafe" would produce no rule, so the second would produce no
 * suggestion, so neither would ever produce one. Reading {@code ACCEPTED} as "the student's own filing
 * confirmed this mapping" is what lets the loop start, and it is the only one of the three members that
 * fits a filing the system did not contradict.
 */
public enum RuleSource {

    /** The student's filing did not contradict a suggestion - they kept it, or there was none. */
    ACCEPTED,

    /** The student changed the category that was suggested. UC-08 B6's correction. */
    OVERRIDE,

    /** The mapping was learned from an imported row rather than from a filing (UC-11). */
    IMPORT
}
