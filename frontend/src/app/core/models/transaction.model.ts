export type TransactionType = 'INCOME' | 'EXPENSE';
export type RecurringFrequency = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY';

export interface Transaction {
  id: string;
  userId: string;
  type: TransactionType;
  amount: number;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  date: string; // ISO date string YYYY-MM-DD
  description: string;
  recurringFrequency: RecurringFrequency;
  isDeleted?: boolean;
  createdAt?: string;
  note?: string;
}
