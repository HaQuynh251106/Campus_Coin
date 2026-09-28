export type TransactionType = 'INCOME' | 'EXPENSE';
export type TransactionSource = 'MANUAL' | 'CSV' | 'RECURRING';

export interface Transaction {
  id: string;
  type: TransactionType;
  categoryType?: TransactionType;
  amount: number;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  date: string;
  txnDate?: string;
  description: string;
  source?: TransactionSource;
  isDeleted?: boolean;
  deletedAt?: string | null;
}
