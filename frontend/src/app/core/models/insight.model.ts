export interface CategoryFlag {
  categoryName: string;
  percentChange: number;
  direction: 'UP' | 'DOWN';
}

export interface MonthlyInsight {
  id: string;
  month: string; // e.g. "September 2026"
  periodCode: string; // "2026-09"
  title: string;
  narrativeSummary: string;
  savingTip: string;
  categoryFlag?: CategoryFlag;
  isBookmarked: boolean;
  avatarUrl: string;
  createdAt: string;
}

export interface SpendingSummary {
  totalIncome: number;
  totalExpense: number;
  netSavings: number;
  savingsRate: number; // percentage (e.g. 24%)
  avgDailySpend: number;
}
