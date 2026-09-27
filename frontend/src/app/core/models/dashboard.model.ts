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
  /**
   * `NEW` and `PINNED` only — the dashboard view excludes dismissed tips entirely, so `DISMISSED`
   * never arrives here. This is deliberately not the same union as `TipResponse.state` on the tips
   * screen: widening it invites a `case 'DISMISSED'` branch that can never run.
   */
  state: 'NEW' | 'PINNED';
}

export interface DashboardAnnouncement {
  id: number;
  title: string;
  body: string;
  /**
   * The three members the backend enum publishes. `CRITICAL` is not one of them and never arrives;
   * `AnnouncementSeverity` is `INFO | WARNING | SUCCESS` and `announcements.severity` is the matching
   * three-value `ENUM` in the schema, so a fourth member here could only ever be a value this client
   * invented.
   */
  severity: 'INFO' | 'WARNING' | 'SUCCESS';
  startsAt: string;
  /** Absent for an open-ended announcement. Absence means "still running", not "expired". */
  endsAt?: string;
}

export interface DashboardResponse {
  periodMonth: string;
  summary: DashboardSummary;
  topCategory?: DashboardTopCategory;
  tips: DashboardTip[];
  announcements: DashboardAnnouncement[];
}
