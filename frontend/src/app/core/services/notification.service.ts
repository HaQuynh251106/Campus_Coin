import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, map, of } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface NotificationItem {
  id: string | number;
  title: string;
  message: string;
  time: string;
  read: boolean;
  type: 'urgent' | 'info' | 'success';
  icon: string;
  linkUrl?: string;
}

@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/notifications`;

  readonly notifications = signal<NotificationItem[]>([]);

  readonly unreadCount = computed(() => {
    return this.notifications().filter(n => !n.read).length;
  });

  readonly hasUrgentUnread = computed(() => {
    return this.notifications().some(n => !n.read && n.type === 'urgent');
  });

  constructor() {
    this.loadNotifications();
  }

  loadNotifications(): void {
    this.http.get<any[]>(this.baseUrl).subscribe({
      next: (list) => {
        const mapped = list.map(item => this.mapBackendNotif(item));
        this.notifications.set(mapped);
      },
      error: () => {
        // Quiet fallback if not yet authenticated
      }
    });
  }

  private mapBackendNotif(raw: any): NotificationItem {
    let type: 'urgent' | 'info' | 'success' = 'info';
    let icon = 'bell';

    if (raw.type === 'BUDGET_EXCEEDED' || raw.type === 'SAVINGS_GOAL_RISK') {
      type = 'urgent';
      icon = 'alert-triangle';
    } else if (raw.type === 'BUDGET_WARNING') {
      type = 'urgent';
      icon = 'bell';
    } else if (raw.type === 'ANNOUNCEMENT') {
      type = 'info';
      icon = 'zap';
    } else if (raw.type === 'TIP') {
      type = 'info';
      icon = 'sparkles';
    }

    const timeStr = this.formatRelativeTime(raw.createdAt);

    return {
      id: raw.id,
      title: raw.title,
      message: raw.body || '',
      time: timeStr,
      read: raw.isRead || false,
      type,
      icon,
      linkUrl: raw.linkUrl
    };
  }

  private formatRelativeTime(isoStr: string): string {
    if (!isoStr) return '';
    try {
      const created = new Date(isoStr).getTime();
      const diffSec = Math.floor((Date.now() - created) / 1000);
      if (diffSec < 60) return 'Just now';
      if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
      if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
      return `${Math.floor(diffSec / 86400)}d ago`;
    } catch {
      return '';
    }
  }

  markAllAsRead(): void {
    const unread = this.notifications().filter(n => !n.read);
    for (const item of unread) {
      this.markAsRead(item.id);
    }
  }

  markAsRead(id: string | number): void {
    this.http.post<any>(`${this.baseUrl}/${id}/read`, {}).subscribe({
      next: (res) => {
        this.notifications.update(items =>
          items.map(item => String(item.id) === String(id) ? { ...item, read: true } : item)
        );
      },
      error: () => {
        // Fallback optimistic update
        this.notifications.update(items =>
          items.map(item => String(item.id) === String(id) ? { ...item, read: true } : item)
        );
      }
    });
  }

  clearAll(): void {
    this.notifications.set([]);
  }
}
