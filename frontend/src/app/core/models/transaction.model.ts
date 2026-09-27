export type TransactionType = 'INCOME' | 'EXPENSE';
export type TransactionSource = 'MANUAL' | 'CSV' | 'RECURRING';

/**
 * One recorded income or expense (UC-07, UC-10).
 *
 * There is no frequency on a transaction. The record is a single event on a single date; whether
 * it repeats is a separate `RecurringRule` (UC-09, module 5) with its own id and its own lifecycle.
 * `source` is the only provenance the API publishes — `'RECURRING'` says a rule generated this row,
 * and the rule's frequency is read from the rule list, never from here.
 *
 * `userId` is likewise absent: the token decides whose transactions these are, and the API neither
 * publishes the field nor accepts one back.
 */
export interface Transaction {
  id: string;
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
  source?: TransactionSource;
  isDeleted?: boolean;
  deletedAt?: string | null;
}
