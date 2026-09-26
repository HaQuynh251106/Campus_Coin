package com.campuscoin.common.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * What UC-17 sends to a provider: one student's month, already reduced to totals.
 *
 * <p><b>Only aggregates, and only the caller's own.</b> The month's income, expense and net balance,
 * plus each expense category's name and total. There are no transaction rows, no descriptions, no
 * dates within the month and no identifiers - not the student's id, not the student's name, not even
 * the categories' ids. The provider learns "a student spent 1,240,000 on Food this month"; it cannot
 * learn which student, and it cannot see a single purchase.
 *
 * <p>This is what the {@code ai.send_aggregates_only} setting means, and it is why the setting is
 * documented as on by default. When it is off the same object is sent - the shape does not change,
 * because sending individual transactions is not something this build implements at all. The setting
 * is respected by {@link com.campuscoin.common.ai.GeminiAiSuggestionPort} refusing to call a
 * provider when it is off, so turning it off turns the AI narrative off rather than loosening what
 * is disclosed.
 *
 * <p>The figures are the ones {@code sp_generate_monthly_insight} already computed for the same
 * month, so the narrative and the stored rule-based summary describe the same numbers and cannot
 * disagree.
 *
 * @param monthLabel  the month as {@code yyyy-MM}, for the narrative to name
 * @param currency    the student's currency code, so amounts are described in the right unit
 * @param totalIncome the month's income
 * @param totalExpense the month's expense
 * @param netAmount   income minus expense
 * @param topCategories expense categories, largest first, name and total only
 */
public record MonthlyNarrativeRequest(String monthLabel,
                                      String currency,
                                      BigDecimal totalIncome,
                                      BigDecimal totalExpense,
                                      BigDecimal netAmount,
                                      List<CategoryTotal> topCategories) {

    /**
     * One expense category's total for the month.
     *
     * @param name  the category's name
     * @param total what was spent in it
     */
    public record CategoryTotal(String name, BigDecimal total) {
    }
}
