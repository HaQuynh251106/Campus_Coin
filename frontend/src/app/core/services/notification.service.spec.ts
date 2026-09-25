import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  let service: NotificationService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [NotificationService]
    });
    service = TestBed.inject(NotificationService);
  });

  it('should initialize with mock notifications', () => {
    expect(service.notifications().length).toBeGreaterThanOrEqual(4);
  });

  it('should calculate unreadCount correctly', () => {
    const unread = service.notifications().filter(n => !n.read).length;
    expect(service.unreadCount()).toBe(unread);
  });

  it('should flag urgent unread notifications', () => {
    expect(service.hasUrgentUnread()).toBe(true);
  });

  it('should mark all notifications as read', () => {
    service.markAllAsRead();
    expect(service.unreadCount()).toBe(0);
    expect(service.hasUrgentUnread()).toBe(false);
  });

  it('should mark a single notification as read', () => {
    const first = service.notifications()[0];
    service.markAsRead(first.id);
    const updated = service.notifications().find(n => n.id === first.id);
    expect(updated?.read).toBe(true);
  });
});
