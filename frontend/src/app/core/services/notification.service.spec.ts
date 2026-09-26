import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { NotificationService, NotificationItem } from './notification.service';
import { AuthService } from './auth.service';

describe('NotificationService', () => {
  let service: NotificationService;
  let httpTesting: HttpTestingController;

  const mockNotifs: NotificationItem[] = [
    {
      id: 1,
      title: 'Over Budget',
      message: 'You exceeded your budget',
      time: '1h ago',
      read: false,
      type: 'urgent',
      icon: 'alert-triangle'
    },
    {
      id: 2,
      title: 'Welcome',
      message: 'Welcome to Campus Coin',
      time: '2d ago',
      read: true,
      type: 'info',
      icon: 'bell'
    }
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        NotificationService,
        AuthService
      ]
    });
    service = TestBed.inject(NotificationService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  it('should initialize with empty notifications list when unauthenticated', () => {
    expect(service.notifications()).toEqual([]);
  });

  it('should calculate unreadCount correctly', () => {
    service.notifications.set(mockNotifs);
    expect(service.unreadCount()).toBe(1);
  });

  it('should flag urgent unread notifications', () => {
    service.notifications.set(mockNotifs);
    expect(service.hasUrgentUnread()).toBe(true);
  });

  it('should clear all notifications', () => {
    service.notifications.set(mockNotifs);
    service.clearAll();
    expect(service.notifications().length).toBe(0);
    expect(service.unreadCount()).toBe(0);
  });
});
