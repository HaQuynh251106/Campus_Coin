package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;

/**
 * One row of {@code v_top_category_current_month}: the expense category this student has spent the
 * most on this month (UC-12 B2).
 *
 * <p><b>Absent, not zero, when there is nothing to report.</b> The view selects a row only from months
 * that have expense transactions, so a student who has recorded none gets no row at all and the
 * response carries no {@code topCategory}. That is deliberately different from the summary, whose
 * figures are {@code IFNULL(..., 0)}: "spent 0.00 in every category" and "has not spent anything yet"
 * are the same statement, and there is no category to name, so naming one with a zero would be
 * inventing an answer.
 *
 * <p><b>Which category won a tie is the view's decision, not this module's.</b>
 * {@code ROW_NUMBER() ... ORDER BY total_amount DESC} has no tie-break, so two categories with the
 * same total are separated by whatever order the plan produced. This module reads the single row it
 * is given and does not re-rank: choosing between two equally-spent categories is a preference, and
 * inventing one here would make the dashboard disagree with the same view read by a later module.
 * The row is stable within a request, which is all a dashboard needs.
 *
 * <p>{@code categoryIcon} and {@code categoryColor} are the one thing carried from outside the view.
 * The view publishes the id, the name and the total, so the DAO joins {@code categories} on the row's
 * own {@code category_id} for the two presentation columns - the same join, for the same reason,
 * {@code BudgetConsumptionDao} makes. It cannot widen the result: one row in, one row out.
 */
public record DashboardTopCategory(
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        BigDecimal totalAmount) {
}
