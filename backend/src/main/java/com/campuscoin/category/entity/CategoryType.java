package com.campuscoin.category.entity;

/**
 * The two values of {@code categories.type}.
 *
 * <p>This enum lives in the {@code category} package because the categories table is where the
 * column is, and it is deliberately shared rather than re-declared: {@code transactions} and
 * {@code recurring_rules} have no type column of their own, so a category's type is the single
 * source of truth for whether a record is income or expense (BR-05). The transactions module
 * imports this type for that reason, which is a one-way dependency - a category knows nothing
 * about transactions.
 *
 * <p>The constant names are the ENUM members the database stores. Nothing else is accepted: the
 * API rejects a JSON number in their place, because a number would be an ordinal whose meaning
 * changes if these constants were ever reordered.
 */
public enum CategoryType {
    INCOME,
    EXPENSE
}
