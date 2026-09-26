package com.campuscoin.insight.entity;

import java.math.BigDecimal;

/**
 * One expense category's total for one month: what UC-17 shows a provider about the month's shape
 * (UC-17).
 *
 * <p><b>Name and total only, and no identifier.</b> This is the shape
 * {@code MonthlyNarrativeRequest.CategoryTotal} takes, and it is deliberately the smallest one that
 * answers the question the narrative asks - which category took the largest share. A category id
 * would be an identifier the provider has no use for: it names the category back in prose, and the
 * client resolves nothing from the answer. Not sending it is what
 * {@code ai.send_aggregates_only} requires of every call this module makes.
 *
 * <p><b>Read from {@code v_category_month_totals}, not summed here.</b> That view is the schema's one
 * answer to "what did this student spend per category in this month" - the dashboard, the reports
 * screen and {@code sp_generate_monthly_insight} itself all read it - so a total computed in Java
 * would be a second answer that could disagree about a month boundary or a trashed record, and the
 * narrative would then describe figures the stored insight does not show.
 *
 * <p>Ordered by total descending when read, because "largest first" is what the provider is told the
 * list means; the ordering is the query's and is not re-derived.
 */
public record MonthlyCategoryTotal(String categoryName, BigDecimal totalAmount) {
}
