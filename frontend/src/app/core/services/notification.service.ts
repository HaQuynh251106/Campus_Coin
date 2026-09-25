import { Injectable, signal, computed } from '@angular/core';

export interface NotificationItem {
  id: string;
  title: string;
  message: string;
  time: string;
  read: boolean;
  type: 'urgent' | 'info' | 'success';
  icon: string;
}

/**
 * NotificationService
 * NOTE: This is an in-memory mock service standing in for a future
 * real-time notification engine (e.g. WebSocket or Push API).
 */
@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  private readonly initialNotifications: NotificationItem[] = [
    {
      id: 'notif-1',
      title: 'Budget Alert',
      message: 'Your Transport & Commute budget limit was reached.',
      time: '20m ago',
      read: false,
      type: 'urgent',
      icon: 'alert-triangle'
    },
    {
      id: 'notif-2',
      title: 'Approaching Limit',
      message: "You're at 85% of your Food & Dining budget for September.",
      time: '2h ago',
      read: false,
      type: 'urgent',
      icon: 'bell'
    },
    {
      id: 'notif-3',
      title: 'Monthly Insight',
      message: 'Your new monthly insight is ready to view.',
      time: '5h ago',
      read: false,
      type: 'info',
      icon: 'sparkles'
    },
    {
      id: 'notif-4',
      title: 'Import Complete',
      message: 'CSV import completed — 42 transactions added.',
      time: '1d ago',
      read: true,
      type: 'success',
      icon: 'check-circle'
    },
    {
      id: 'notif-5',
      title: 'Campus Tip',
      message: 'Off-peak dining bonus points active this week.',
      time: '2d ago',
      read: true,
      type: 'info',
      icon: 'zap'
    }
  ];

  readonly notifications = signal<NotificationItem[]>(this.initialNotifications);

  readonly unreadCount = computed(() => {
    return this.notifications().filter(n => !n.read).length;
  });

  readonly hasUrgentUnread = computed(() => {
    return this.notifications().some(n => !n.read && n.type === 'urgent');
  });

  markAllAsRead(): void {
    this.notifications.update(items =>
      items.map(item => ({ ...item, read: true }))
    );
  }

  markAsRead(id: string): void {
    this.notifications.update(items =>
      items.map(item => (item.id === id ? { ...item, read: true } : item))
    );
  }

  clearAll(): void {
    this.notifications.set([]);
  }
}
