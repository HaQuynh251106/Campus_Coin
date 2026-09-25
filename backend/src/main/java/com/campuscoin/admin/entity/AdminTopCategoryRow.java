package com.campuscoin.admin.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

/**
 * One row of {@code v_admin_top_categories} (UC-23).
 *
 * <p>{@code scope} is published because it is the column that makes the list readable: the seeded
 * default categories and students' own categories share this table, and "Entertainment, 412
 * transactions" means something different depending on whether it is the shared row or one
 * student's. The view computes it as {@code 'DEFAULT'} or {@code 'PERSONAL'}; it is carried as a
 * string rather than an enum because it does not correspond to a column of {@code categories} - it
 * is derived from whether {@code user_id} is null, and inventing an enum for two computed values
 * would be a second vocabulary beside the schema's own.
 *
 * <p>{@code totalAmount} carries the same recorded exposure {@link AdminUsageStats} documents: it is
 * a {@code SUM} over plaintext amounts, aggregated across students, and it is the OB-013 position
 * rather than something this module chose.
 *
 * <p>{@code txnCount} and {@code distinctUsers} are what make the ordering meaningful, and both are
 * counted over non-deleted transactions only - the view's own {@code is_deleted = 0}, which is BR-09
 * applied to a statistic. A category with no transactions appears with zeroes rather than being
 * omitted: the view is a {@code LEFT JOIN} from {@code categories} for exactly that reason.
 */
public record AdminTopCategoryRow(
        Long categoryId,
        String categoryName,
        CategoryType type,
        String scope,
        Long txnCount,
        BigDecimal totalAmount,
        Long distinctUsers) {
}
