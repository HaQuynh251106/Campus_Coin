import { CategoryType } from './category.model';

export type RecentAction = 'VIEWED' | 'EDITED';

export interface RecentActivity {

  transactionId: number;
  action: RecentAction;
  occurredAt: string;
  categoryId: number;
  categoryType: CategoryType;
  amount: number;
  description?: string;

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
