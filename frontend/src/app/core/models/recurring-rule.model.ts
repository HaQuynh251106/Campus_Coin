

export type RecurringFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY';

export type RecurringStatus = 'ACTIVE' | 'PAUSED' | 'ENDED';

export interface RecurringRule {
  id: number;
  categoryId: number;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  amount: number;
  description: string;
  frequency: RecurringFrequency;
  intervalCount: number;

  startDate: string;
  endDate: string | null;
  nextRunDate: string;

  lastRunDate: string | null;
  status: RecurringStatus;
}

export interface CreateRecurringRuleRequest {
  categoryId: number;
  amount: number;
  description?: string;
  frequency: RecurringFrequency;
  intervalCount?: number;
  startDate: string;

  endDate?: string;
  nextRunDate?: string;
}

export interface UpdateRecurringRuleRequest {
  categoryId?: number;
  amount?: number;
  description?: string;
  frequency?: RecurringFrequency;
  intervalCount?: number;
  endDate?: string;
  nextRunDate?: string;
  status?: RecurringStatus;
}
