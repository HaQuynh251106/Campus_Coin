import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NotificationBellComponent } from './notification-bell.component';
import { NotificationService } from '../../../core/services/notification.service';

describe('NotificationBellComponent', () => {
  let component: NotificationBellComponent;
  let fixture: ComponentFixture<NotificationBellComponent>;
  let notifService: NotificationService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NotificationBellComponent],
      providers: [NotificationService]
    }).compileComponents();

    fixture = TestBed.createComponent(NotificationBellComponent);
    component = fixture.componentInstance;
    notifService = TestBed.inject(NotificationService);
    fixture.detectChanges();
  });

  it('should create the notification bell component', () => {
    expect(component).toBeTruthy();
  });

  it('should toggle dropdown panel open and closed', () => {
    expect(component.isOpen()).toBe(false);
    component.toggleOpen();
    expect(component.isOpen()).toBe(true);
    component.toggleOpen();
    expect(component.isOpen()).toBe(false);
  });

  it('should mark all as read through service', () => {
    component.markAllAsRead();
    expect(component.unreadCount()).toBe(0);
  });
});
