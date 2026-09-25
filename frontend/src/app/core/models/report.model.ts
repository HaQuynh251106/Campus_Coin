export interface MonthlyIncomeExpense {
  periodMonth: string;
  totalIncome: number;
  totalExpense: number;
  netAmount: number;
  savingsRate?: number;
}

export interface CategoryMonthTotal {
  categoryId: number;
  categoryName: string;
  categoryIcon?: string;
  categoryColor?: string;
  categoryType: 'INCOME' | 'EXPENSE';
  totalAmount: number;
  txnCount: number;
  percentage?: number;
}

export interface ReportResponse {
  periodMonth: string;
  totals?: MonthlyIncomeExpense;
  categoryBreakdown: CategoryMonthTotal[];
  sixMonthTrend: MonthlyIncomeExpense[];
}

export interface SpendingDataPoint {
  intervalStart: string;
  intervalEnd: string;
  totalExpense: number;
  txnCount: number;
  label: string;
}

export interface SpendingReportResponse {
  periodMonth: string;
  granularity: 'DAILY' | 'WEEKLY';
  points: SpendingDataPoint[];
}
