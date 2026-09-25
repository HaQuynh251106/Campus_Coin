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
  consumptionStatus?: 'SAFE' | 'WARNING' | 'EXCEEDED';
  createdAt?: string;
}
