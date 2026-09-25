package com.campuscoin.budget.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code v_budget_consumption}: a budget together with the month's spending against it.
 *
 * <p><b>Why a view rather than a {@code @Entity}.</b> Everything on this record except the limit
 * itself is computed - {@code spent_amount} is a {@code SUM} over the month's live transactions, and
 * {@code consumed_pct}, {@code remaining_amount} and {@code consumption_status} are derived from it.
 * The database already computes all of them in {@code v_budget_consumption}, and the same
 * percentages decide when {@code sp_check_budget_alerts} raises an alert. Reading the view is what
 * keeps the number a student sees and the number that triggers a notification identical; deleting a
 * transaction, for instance, drops the spend and moves the status back, and neither has to be
 * recomputed here to know that.
 *
 * <p><b>A plain record, not a managed entity.</b> The view is read-only and is never written, so
 * there is nothing for Hibernate to track. It is loaded through {@code BudgetConsumptionDao} with an
 * explicit projection, which also means the API's response shape is decided by
 * {@code BudgetMapper} rather than by a mapping annotation here - the same separation the other
 * modules keep between an entity and its response.
 *
 * <p>{@code periodMonth} is the {@code DATE} column as the database stores it, always the first of a
 * month. Its {@code yyyy-MM} presentation is the mapper's job.
 */
public record BudgetConsumption(
        Long budgetId,
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        LocalDate periodMonth,
        BigDecimal limitAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        BigDecimal consumedPct,
        ConsumptionStatus consumptionStatus) {
}
