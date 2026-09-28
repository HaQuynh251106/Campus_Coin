import { CategoryType } from './category.model';

export type AnomalyFlagType = 'NONE' | 'DUPLICATE' | 'UNUSUAL_AMOUNT';

export interface FlaggedTransaction {

  transactionId: number;
  categoryId: number;
  categoryName: string;
  categoryType: CategoryType;
  amount: number;
  txnDate: string;
  description?: string;

  isFlagged: boolean;
  flagType: AnomalyFlagType;

  flagNote?: string;
}

export interface FlaggedTransactionListResponse {
  limit: number;
  entries: FlaggedTransaction[];
}

export interface AnomalyScanResult {

  examined: number;

  flagged: number;

  cleared: number;
  unchanged: number;
  entries: FlaggedTransaction[];
}
