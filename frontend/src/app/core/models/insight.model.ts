
export type InsightGeneratedBy = 'AI' | 'RULE_BASED';

export interface FlaggedCategory {
  categoryId: number;
  categoryName: string;

  currentTotal: number;

  baselineAvg: number;

  pctChange: number | null;
}

export interface MonthlyInsight {

  periodMonth: string;
  totalIncome: number;
  totalExpense: number;

  netAmount: number;
  summary: string;
  advice: string;
  generatedBy: InsightGeneratedBy;

  model?: string;
  flaggedCategories: FlaggedCategory[];
  generatedAt: string;
}

export interface InsightMonthsResponse {

  months: string[];
}
