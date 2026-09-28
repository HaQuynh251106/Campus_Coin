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
  period: string;
  periodMonth?: string;
  alertStatus?: BudgetAlertStatus;

  consumptionStatus?: 'ON_TRACK' | 'NEAR' | 'EXCEEDED';
  createdAt?: string;
}

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
