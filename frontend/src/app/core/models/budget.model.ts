export type BudgetAlertStatus = 'SAFE' | 'WARNING' | 'DANGER' | 'EXCEEDED';

export interface Budget {
  id: string;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  categoryIsActive?: boolean;
  monthlyLimit: number;
  limitAmount?: number;
  spent: number;
  spentAmount?: number;
  remainingAmount?: number;
  consumedPct?: number;
  period: string; // e.g., '2026-09'
  periodMonth?: string;
  alertStatus?: BudgetAlertStatus;
  /**
   * The three members `ConsumptionStatus` publishes, taken straight from the response. This is not
   * the same vocabulary as `alertStatus` below it — `SAFE`/`WARNING`/`DANGER` is a local display
   * classification, while `ON_TRACK`/`NEAR`/`EXCEEDED` is what `v_budget_consumption` computed and
   * what `sp_check_budget_alerts` compared against. Reading the server's own value rather than
   * re-deriving one from `consumedPct` is what keeps a screen from disagreeing with an alert that
   * was already stored.
   */
  consumptionStatus?: 'ON_TRACK' | 'NEAR' | 'EXCEEDED';
  createdAt?: string;
}

/**
 * How the home screen orders the budget strip, worst first.
 *
 * **The list endpoint's order is not usable for this.** `GET /api/v1/budgets` is ordered by category
 * name so the budgets screen is stable between calls, which means taking its first four rows shows
 * whichever categories happen to sort first alphabetically — a category that is over its limit can
 * easily sit at position five and never appear. On a strip whose entire purpose is "how am I doing
 * this month", an exceeded budget that the student cannot see is the one failure that matters.
 *
 * **The ranking is the server's own classification, not a new one.** `consumptionStatus` is
 * `v_budget_consumption`'s `CASE` over `spent_amount / limit_amount`, computed from the thresholds
 * in `system_settings` — the same comparison `sp_check_budget_alerts` makes. Ordering by it therefore
 * ranks by a rule the database already owns, and a student who changes `budget.near_threshold_pct`
 * moves both the label and this order together, with no second definition to drift.
 *
 * `consumedPct` breaks ties inside a band so the closest to the limit comes first, and `categoryName`
 * breaks the remainder so the strip does not reshuffle between two renders of the same data. Sorting
 * is by a copy — the service's own list is left in the order the API returned it.
 */
const CONSUMPTION_SEVERITY: Record<Budget['consumptionStatus'] & string, number> = {
  EXCEEDED: 0,
  NEAR: 1,
  ON_TRACK: 2
};

export function rankBudgetsBySeverity(budgets: Budget[]): Budget[] {
  return [...budgets].sort((a, b) => {
    const rankA = CONSUMPTION_SEVERITY[a.consumptionStatus ?? 'ON_TRACK'] ?? 2;
    const rankB = CONSUMPTION_SEVERITY[b.consumptionStatus ?? 'ON_TRACK'] ?? 2;
    if (rankA !== rankB) return rankA - rankB;
    const pctDiff = (b.consumedPct ?? 0) - (a.consumedPct ?? 0);
    if (pctDiff !== 0) return pctDiff;
    return a.categoryName.localeCompare(b.categoryName);
  });
}
