package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

/**
 * One learned mapping, as UC-08 reads it.
 *
 * <p><b>Five columns, and each one is read.</b> The id breaks a tie deterministically when two
 * substring rules are equally long; the keyword is what the description is compared against; the mode
 * says which of the two comparisons applies; the category is the answer; the confidence is what a
 * rule-based suggestion reports. Columns this module writes but never reads - {@code hit_count},
 * {@code last_used_at}, {@code type}, {@code source} - are deliberately not projected, so the record
 * cannot come to imply a reader that does not exist.
 *
 * <p><b>{@code userId} is not carried.</b> Every query narrows on it, so it is the same value on every
 * row of an answer and a caller has no use for it - the reasoning {@code FlaggedTransactionRow},
 * {@code TipRow} and {@code RecentActivityRow} all record.
 *
 * <p><b>{@code keyword} is carried normalised and is never re-normalised here.</b> The column's own
 * comment says the stored form is "lower case, trimmed", and {@code CategoryRuleMatcher} applies the
 * same normalisation to the incoming description before comparing, so both sides of the comparison are
 * produced by one piece of code. A reader that normalised again would be a second definition of the
 * stored form, and the two could disagree about, say, a run of two spaces.
 *
 * <p>A projection rather than a managed entity: nothing is written back through it. The write path is
 * {@code CategoryRuleDao#upsert}, whose {@code INSERT ... ON DUPLICATE KEY UPDATE} is the only
 * statement in this build that touches the table, and it is native rather than a mapped entity so that
 * no JPA flush can compete with it over {@code uk_rule_user_keyword}.
 */
public record CategoryRuleRow(
        Long ruleId,
        String keyword,
        RuleMatchMode matchMode,
        Long categoryId,
        BigDecimal confidence) {
}
