package com.campuscoin.insight.entity;

/**
 * The three values of {@code insights.generated_by} (UC-17).
 *
 * <p>Mirrors the column's ENUM exactly. The constant names are the members the database stores, and
 * nothing else is accepted: a JSON number in their place is rejected, because a number would be an
 * ordinal whose meaning changes if these constants were ever reordered - the reasoning
 * {@code CategoryType}, {@code AnomalyFlagType} and {@code RecentAction} all record.
 *
 * <p><b>This is the field that makes BR-13 visible.</b> BR-13 requires every AI result to be
 * presented as a suggestion the student can review rather than as fact, and a student cannot judge
 * how much to trust a sentence they are reading unless somebody tells them where it came from. The
 * column separates the two authors: {@code RULE_BASED} is what {@code sp_generate_monthly_insight}
 * composed from the student's own figures by a fixed rule, and {@code AI} is what a provider wrote
 * from the same figures. Publishing it is what lets a screen say "written by the suggestion service"
 * instead of implying a person or a model said it.
 *
 * <p><b>{@code MANUAL} is a member and this build never writes it.</b> The column has it so an
 * administrator or a hand-run statement can mark text neither the rules nor a provider produced.
 * It is published whole rather than narrowed to what one module happens to set - the precedent
 * {@code RuleSource} sets for {@code IMPORT} - because a client switching on this enum must be able
 * to render every value the column can hold, including one this build does not create.
 */
public enum InsightGeneratedBy {

    /**
     * A provider wrote the text from the student's own monthly aggregates (UC-17). The model that
     * wrote it is recorded in {@code insights.model_name} and published beside this value.
     */
    AI,

    /**
     * {@code sp_generate_monthly_insight} composed the text from the student's own figures by a fixed
     * rule. This is the value a deployment with no AI provider always has, and the value a run
     * overwrites - the procedure preserves {@code AI} text but rewrites its own.
     */
    RULE_BASED,

    /** Text written by neither path - reserved by the column, and never produced by this build. */
    MANUAL
}
