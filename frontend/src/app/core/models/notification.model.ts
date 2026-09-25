export type NotificationType =
  | 'BUDGET_WARNING'
  | 'BUDGET_EXCEEDED'
  | 'ANNOUNCEMENT'
  | 'TIP'
  | 'SAVINGS_GOAL_RISK'
  | 'WEEKLY_REPORT'
  | 'ACCOUNT_STATUS';

export interface NotificationItem {
  id: number;
  type: NotificationType;
  title: string;
  body?: string;
  linkUrl?: string;
  refEntityType?: string;
  refEntityId?: number;
  isRead: boolean;
  readAt?: string;
  createdAt: string;
}
