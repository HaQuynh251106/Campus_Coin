export type BudgetAlertStatus = 'SAFE' | 'WARNING' | 'DANGER';

export interface Budget {
  id: string;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  monthlyLimit: number;
  spent: number;
  period: string; // e.g., '2026-09'
  alertStatus?: BudgetAlertStatus;
}
