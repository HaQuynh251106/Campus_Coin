package com.campuscoin.recurring.entity;

/**
 * The five values of {@code recurring_rules.frequency}.
 *
 * <p>These are the ENUM members the database stores, and nothing else is accepted: the API rejects
 * a JSON number in their place, because a number would be an ordinal whose meaning changes if these
 * constants were ever reordered - the same project-wide Jackson setting that governs every other
 * enum.
 *
 * <p><b>The frequency is consumed by {@code sp_post_recurring_transactions} and by nothing in
 * Java.</b> Which calendar periods a rule has already been posted for is decided entirely by that
 * procedure, which derives a period key per frequency - {@code '2026-09-24'} for {@code DAILY},
 * {@code '2026-W38'} for {@code WEEKLY}, {@code '2026-09'} for {@code MONTHLY}, {@code '2026-Q3'}
 * for {@code QUARTERLY} and {@code '2026'} for {@code YEARLY'} - and relies on
 * {@code uk_occurrence_rule_period} to post each one exactly once (BR-16).
 *
 * <p>This module therefore never computes a period key or a due date itself. A second
 * implementation would be a second answer to "has this period been posted?", and the unique key
 * exists precisely to make a second answer impossible.
 */
public enum RecurringFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY
}
