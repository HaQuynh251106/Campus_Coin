package com.campuscoin.reports.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

/**
 * One category's total for one month: one row of {@code v_category_month_totals} (UC-15).
 *
 * <p>This is the pie chart's source and, through {@code v_top_category_current_month}, the
 * dashboard's "highest-spending category" widget - deliberately one row shape read by two modules,
 * so the slice a student sees on the reports screen and the category named on their home screen are
 * the same figure computed once.
 *
 * <p><b>{@code type} is carried rather than assumed.</b> The view publishes it, because
 * {@code v_category_month_totals} groups income and expense categories alike; a report that showed
 * both under one heading would be adding an income category's total to a spending chart. The
 * service splits the two and publishes them as separate blocks, which is why this record has to
 * carry the value that was split on.
 *
 * <p><b>{@code icon} and {@code color} come from {@code categories}, not from the view.</b> The view
 * publishes the id, the name and the total; the DAO joins the category for the two presentation
 * columns, exactly as {@code BudgetConsumptionDao} and {@code DashboardViewDao} do, so there is one
 * way these two columns are fetched rather than three. Both are nullable, because
 * {@code categories.icon} and {@code categories.color} are.
 *
 * <p>{@code transactionCount} is the view's {@code txn_count}: how many live records make up the
 * total. It is published because a student reading "24.00 on Food" reasonably wants to know whether
 * that was one meal or eight, and because recomputing it would be a second count of rows the view
 * has already counted.
 */
public record CategoryBreakdownRow(
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        CategoryType type,
        BigDecimal totalAmount,
        Long transactionCount) {
}
