export interface DashboardSummary {
  currency: string;
  totalIncome: number;
  totalExpense: number;
  netAmount: number;
  monthlyAllowanceBaseline: number;
  monthlySavingsGoal: number;
  savingsGoalPct?: number;
}

export interface DashboardTopCategory {
  categoryId: number;
  categoryName: string;
  categoryIcon?: string;
  categoryColor?: string;
  totalAmount: number;
}

export interface DashboardTip {
  id: number;
  categoryId?: number;
  title: string;
  body: string;
  potentialSaving: number;
  state: 'NEW' | 'PINNED' | 'DISMISSED';
}

export interface DashboardAnnouncement {
  id: number;
  title: string;
  body: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'SUCCESS';
  startsAt: string;
  endsAt?: string;
}

export interface DashboardResponse {
  periodMonth: string;
  summary: DashboardSummary;
  topCategory?: DashboardTopCategory;
  tips: DashboardTip[];
  announcements: DashboardAnnouncement[];
}
