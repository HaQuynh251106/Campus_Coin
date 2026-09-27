import { CategoryType } from './category.model';

/**
 * Module 12 (UC-24) — the anomaly check.
 *
 * The mark is decided by the detector and is read-only: there is no route that lets a client set a
 * flag, and this model has no field for one. The screen presents the marks and the detector's own
 * note; correcting the record is done on the transactions screen.
 */
export type AnomalyFlagType = 'NONE' | 'DUPLICATE' | 'UNUSUAL_AMOUNT';

export interface FlaggedTransaction {
  /** The transaction's own id, so the record can be opened or corrected from here. */
  transactionId: number;
  categoryId: number;
  categoryName: string;
  categoryType: CategoryType;
  amount: number;
  txnDate: string;
  description?: string;
  /** Always true on this endpoint — every entry was selected because it is flagged. */
  isFlagged: boolean;
  flagType: AnomalyFlagType;
  /** The detector's plain-language explanation, with the figures it compared. */
  flagNote?: string;
}

export interface FlaggedTransactionListResponse {
  limit: number;
  entries: FlaggedTransaction[];
}

/** What one scan did. A repeated scan over unchanged data reports every record as `unchanged`. */
export interface AnomalyScanResult {
  /** How many of the student's live records were examined. */
  examined: number;
  /** Newly marked by this scan. */
  flagged: number;
  /** Un-marked, because the student corrected them or the counterpart went to the trash. */
  cleared: number;
  unchanged: number;
  entries: FlaggedTransaction[];
}
