import { CategoryType } from './category.model';

/**
 * Module 12 (UC-26) — what the student recently opened or changed.
 *
 * A transaction can appear twice, once per action, and the list is ordered by `occurredAt` rather
 * than by the transaction's own date. Nothing is stored locally: the list is whatever the API
 * returns, so a browser that has never opened a record shows the honest empty state.
 */
export type RecentAction = 'VIEWED' | 'EDITED';

export interface RecentActivity {
  /** The transaction's own id, so the entry can be opened directly. */
  transactionId: number;
  action: RecentAction;
  occurredAt: string;
  categoryId: number;
  categoryType: CategoryType;
  amount: number;
  description?: string;
  /** The date the transaction was recorded against — not the date of this action. */
  txnDate: string;
}

export interface RecentActivityListResponse {
  limit: number;
  entries: RecentActivity[];
}

export interface RecordRecentActivityRequest {
  transactionId: number;
  action: RecentAction;
}
