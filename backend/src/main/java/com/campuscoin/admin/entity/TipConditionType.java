package com.campuscoin.admin.entity;

/**
 * Which rule a saving-tip template belongs to - {@code tip_templates.condition_type} exactly
 * (UC-21 B3/B4).
 *
 * <p>The seven members are the column's own values. Each names the condition
 * {@code sp_generate_tips} evaluates when it decides whether the template's advice applies, so this
 * is the field that ties a template to a rule rather than merely labelling it. {@link #GENERIC} is
 * the value the procedure falls back to when it is not told one, and it is also the condition of the
 * seeded catch-all template.
 *
 * <p>{@link #LOW_SAVINGS_RATE} is declared although this build never generates a tip from it: the
 * column accepts the value and the seeded template exists, so omitting it would make the enum an
 * incomplete description of the column and fail at the first read of that row. Whether the rule
 * fires is the database's business; that the value can be named is this type's.
 */
public enum TipConditionType {

    /** BR-10: spending in a category has passed the limit set for it. */
    OVER_BUDGET,

    /** VĐ-05 / BR-10: spending has reached the near-threshold percentage of the limit. */
    NEAR_BUDGET,

    /** BR-15: spending in a category has risen sharply against its recent baseline. */
    CATEGORY_SPIKE,

    /** No limit has been set for a category the student is spending in. */
    NO_BUDGET_SET,

    /** The month's savings goal is at risk of being missed. */
    SAVINGS_GOAL_AT_RISK,

    /** The share of income saved this month is low. Declared for completeness; see the class note. */
    LOW_SAVINGS_RATE,

    /** Advice that applies regardless of condition. The procedure's fallback and the seeded catch-all. */
    GENERIC
}
