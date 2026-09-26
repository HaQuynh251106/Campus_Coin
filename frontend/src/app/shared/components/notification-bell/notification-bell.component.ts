import { Component, inject, signal, HostListener, ElementRef, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { NotificationService, NotificationItem } from '../../../core/services/notification.service';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-notification-bell',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="relative">
      <!-- Bell Button Trigger -->
      <button
        type="button"
        (click)="toggleOpen()"
        aria-label="View notifications"
        class="relative p-2 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors shadow-xs cursor-pointer"
      >
        <app-icon name="bell" [size]="16" strokeWidth="1.5"></app-icon>

        <!-- Subtle Unread Indicator Dot -->
        @if (unreadCount() > 0) {
          <span
            class="absolute top-1.5 right-1.5 w-2 h-2 rounded-full border border-white dark:border-neutral-900"
            [class.bg-rose-500]="hasUrgentUnread()"
            [class.bg-amber-500]="!hasUrgentUnread()"
          ></span>
        }
      </button>

      <!-- Dropdown Panel -->
      @if (isOpen()) {
        <div
          class="absolute right-0 mt-2 w-80 sm:w-96 rounded-xl bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 shadow-subtle-lg z-50 overflow-hidden animate-fade-in"
        >
          <!-- Panel Header -->
          <div class="px-4 py-3 bg-neutral-50 dark:bg-neutral-800/80 border-b border-neutral-200 dark:border-neutral-700 flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="font-semibold text-sm text-neutral-900 dark:text-neutral-100">
                Notifications
              </span>
              @if (unreadCount() > 0) {
                <span class="text-[11px] font-semibold text-amber-600 dark:text-amber-400">
                  ({{ unreadCount() }} new)
                </span>
              }
            </div>

            @if (unreadCount() > 0) {
              <button
                type="button"
                (click)="markAllAsRead()"
                class="text-xs text-amber-600 dark:text-amber-400 hover:underline cursor-pointer font-medium"
              >
                Mark all as read
              </button>
            }
          </div>

          <!-- Notification Items List -->
          <div class="max-h-[380px] overflow-y-auto divide-y divide-neutral-100 dark:divide-neutral-800/80">
            @for (item of notifications(); track item.id) {
              <div
                (click)="onItemClick(item)"
                class="p-3.5 flex items-start gap-3 transition-colors cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800/50"
                [class.bg-amber-500/5]="!item.read && item.type !== 'urgent'"
                [class.dark:bg-amber-500/10]="!item.read && item.type !== 'urgent'"
                [class.bg-rose-500/5]="!item.read && item.type === 'urgent'"
                [class.dark:bg-rose-500/10]="!item.read && item.type === 'urgent'"
              >
                <!-- Semantic Icon Container -->
                <div
                  class="w-7 h-7 rounded-lg shrink-0 flex items-center justify-center border text-xs"
                  [class.bg-rose-50]="item.type === 'urgent'"
                  [class.border-rose-200]="item.type === 'urgent'"
                  [class.text-rose-600]="item.type === 'urgent'"
                  [class.dark:bg-rose-950/40]="item.type === 'urgent'"
                  [class.dark:border-rose-900]="item.type === 'urgent'"
                  [class.dark:text-rose-400]="item.type === 'urgent'"
                  [class.bg-amber-50]="item.type === 'info'"
                  [class.border-amber-200]="item.type === 'info'"
                  [class.text-amber-700]="item.type === 'info'"
                  [class.dark:bg-amber-950/40]="item.type === 'info'"
                  [class.dark:border-amber-900]="item.type === 'info'"
                  [class.dark:text-amber-400]="item.type === 'info'"
                  [class.bg-emerald-50]="item.type === 'success'"
                  [class.border-emerald-200]="item.type === 'success'"
                  [class.text-emerald-700]="item.type === 'success'"
                  [class.dark:bg-emerald-950/40]="item.type === 'success'"
                  [class.dark:border-emerald-900]="item.type === 'success'"
                  [class.dark:text-emerald-400]="item.type === 'success'"
                >
                  <app-icon [name]="item.icon" [size]="14" strokeWidth="1.5"></app-icon>
                </div>

                <!-- Content -->
                <div class="flex-1 min-w-0">
                  <div class="flex items-baseline justify-between gap-1 mb-0.5">
                    <h4 class="text-xs font-semibold text-neutral-900 dark:text-neutral-100 truncate">
                      {{ item.title }}
                    </h4>
                    <span class="text-[10px] text-[var(--color-text-muted)] font-mono shrink-0">
                      {{ item.time }}
                    </span>
                  </div>
                  <p class="text-xs text-neutral-600 dark:text-neutral-300 leading-snug">
                    {{ item.message }}
                  </p>
                </div>

                <!-- Unread Indicator Dot -->
                @if (!item.read) {
                  <span class="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0 mt-1.5"></span>
                }
              </div>
            } @empty {
              <!-- Empty State -->
              <div class="py-10 px-4 text-center space-y-2">
                <div class="w-10 h-10 rounded-full bg-neutral-100 dark:bg-neutral-800 flex items-center justify-center mx-auto text-neutral-400">
                  <app-icon name="check-circle" [size]="20" strokeWidth="1.5"></app-icon>
                </div>
                <div class="font-medium text-xs text-neutral-900 dark:text-neutral-100">
                  You're all caught up
                </div>
                <p class="text-[11px] text-[var(--color-text-muted)]">
                  No new budget warnings or notifications.
                </p>
              </div>
            }
          </div>
        </div>
      }
    </div>
  `
})
export class NotificationBellComponent {
  private notifService = inject(NotificationService);
  private elementRef = inject(ElementRef);

  isOpen = signal(false);

  notifications = this.notifService.notifications;
  unreadCount = this.notifService.unreadCount;
  hasUrgentUnread = this.notifService.hasUrgentUnread;

  toggleOpen(): void {
    this.isOpen.set(!this.isOpen());
  }

  markAllAsRead(): void {
    this.notifService.markAllAsRead();
  }

  onItemClick(item: NotificationItem): void {
    this.notifService.markAsRead(item.id);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isOpen.set(false);
    }
  }
}
