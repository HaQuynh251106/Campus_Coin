/**
 * Module 12 (UC-17) — the monthly insight.
 *
 * `generatedBy` is the field a client must switch on. BR-13 requires the summary and advice to be
 * labelled honestly: `AI` means a provider wrote the prose, `RULE_BASED` means the database composed
 * it from the student's own figures by a fixed rule. Calling a rule-based line "AI-generated" would
 * be a claim the response does not make.
 */
export type InsightGeneratedBy = 'AI' | 'RULE_BASED';

/** An expense category that ran above the student's own usual level (BR-15). */
export interface FlaggedCategory {
  categoryId: number;
  categoryName: string;
  /** What was spent in it that month. */
  currentTotal: number;
  /** The student's own average over the preceding months — a baseline, not a limit. */
  baselineAvg: number;
  /** How far above that baseline the month ran, as a percentage. Null when there is nothing to compare. */
  pctChange: number | null;
}

export interface MonthlyInsight {
  /** The month described, as `yyyy-MM`. Echoed back rather than assumed. */
  periodMonth: string;
  totalIncome: number;
  totalExpense: number;
  /** Income minus spending. Negative means the student spent more than they received. */
  netAmount: number;
  summary: string;
  advice: string;
  generatedBy: InsightGeneratedBy;
  /** The provider's model identifier. Absent when no provider was involved. */
  model?: string;
  flaggedCategories: FlaggedCategory[];
  generatedAt: string;
}

export interface InsightMonthsResponse {
  /** `yyyy-MM`, newest first. Empty when no insight has been generated for the caller. */
  months: string[];
}
