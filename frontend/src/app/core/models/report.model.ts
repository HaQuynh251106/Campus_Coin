export interface ReportTotals {
  income?: number;
  expense?: number;
  net?: number;
  transactionCount?: number;
}

export interface ReportCategoryItem {
  categoryId: number | string;
  categoryName: string;
  categoryIcon?: string;
  categoryColor?: string;
  type: 'INCOME' | 'EXPENSE';
  total: number;
  percentage: number;
  transactionCount: number;
}

export interface ReportTrendPoint {
  periodMonth: string;
  income: number;
  expense: number;
  net: number;
}

export interface ReportResponse {
  periodMonth: string;
  currency: string;
  totals?: ReportTotals;
  expenseByCategory: ReportCategoryItem[];
  incomeByCategory: ReportCategoryItem[];
  sixMonthTrend: ReportTrendPoint[];
}

export interface SpendingPoint {
  intervalStart: string;
  intervalEnd: string;
  totalExpense: number;
  transactionCount: number;
}

export interface SpendingSeriesResponse {
  granularity: 'DAILY' | 'WEEKLY';
  from: string;
  to: string;
  currency: string;
  totalExpense: number;
  points: SpendingPoint[];
}

// UI view models used by charts & components
export interface MonthlyTrendItem {
  periodCode: string;
  monthLabel: string;
  income: number;
  expense: number;
  net: number;
}

export interface CategoryBreakdownItem {
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  total: number;
  percentage: number;
  transactionCount: number;
}

export interface DailySpendingItem {
  day: string;
  amount: number;
  count: number;
}
