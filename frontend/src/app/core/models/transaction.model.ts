export type TransactionType = 'INCOME' | 'EXPENSE';
export type RecurringFrequency = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type TransactionSource = 'MANUAL' | 'CSV' | 'RECURRING';

export interface Transaction {
  id: string;
  userId?: string;
  type: TransactionType;
  categoryType?: TransactionType;
  amount: number;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  date: string; // ISO date string YYYY-MM-DD
  txnDate?: string;
  description: string;
  recurringFrequency?: RecurringFrequency;
  source?: TransactionSource;
  recurringRuleId?: number | null;
  isDeleted?: boolean;
  deletedAt?: string | null;
  createdAt?: string;
  note?: string;
}
