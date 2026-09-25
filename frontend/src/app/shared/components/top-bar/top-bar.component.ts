import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { TransactionService } from '../../../core/services/transaction.service';
import { IconComponent } from '../icon/icon.component';
import { NotificationBellComponent } from '../notification-bell/notification-bell.component';

@Component({
  selector: 'app-top-bar',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, NotificationBellComponent],
  template: `
    <header class="sticky top-0 z-30 bg-white/90 dark:bg-neutral-900/90 backdrop-blur-md border-b border-neutral-200 dark:border-neutral-800 px-4 md:px-8 py-2.5 transition-colors">
      <div class="max-w-7xl mx-auto flex items-center justify-between gap-4">

        <!-- Left: Brand / User Greeting -->
        <div class="flex items-center gap-3">
          <!-- Mobile Brand Logo -->
          <a routerLink="/app/home" class="md:hidden flex items-center gap-2 font-semibold text-lg tracking-tight">
            <span class="w-8 h-8 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 flex items-center justify-center text-sm font-bold">
              🪙
            </span>
            <span class="text-neutral-900 dark:text-white font-semibold">Campus<span class="text-amber-500">Coin</span></span>
          </a>

          <!-- Greeting (Desktop / Tablet) -->
          <div class="hidden md:block">
            <h1 class="text-base sm:text-lg font-semibold tracking-tight text-neutral-900 dark:text-neutral-100">
              Welcome back, {{ user()?.name?.split(' ')?.[0] || 'Alex' }}
            </h1>
            <p class="text-[11px] text-[var(--color-text-muted)] font-normal">
              {{ user()?.major }} &bull; {{ user()?.academicYear }}
            </p>
          </div>
        </div>

        <!-- Center: Current Month Balance Badge -->
        <div class="hidden sm:flex items-center gap-2.5 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-800 rounded-lg px-3 py-1.5 shadow-xs">
          <div class="text-left pr-2.5 border-r border-neutral-200 dark:border-neutral-700">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500 dark:text-neutral-400">Sep Net</span>
            <span class="font-semibold text-sm" [class.text-emerald-600]="balance().net >= 0" [class.text-red-500]="balance().net < 0">
              {{ balance().net >= 0 ? '+' : '' }}\${{ balance().net.toFixed(2) }}
            </span>
          </div>
          <div class="text-left text-[11px] font-medium leading-tight">
            <span class="block text-emerald-600 dark:text-emerald-400 font-sans">↑ \${{ balance().income.toFixed(2) }}</span>
            <span class="block text-rose-500 dark:text-rose-400 font-sans">↓ \${{ balance().expense.toFixed(2) }}</span>
          </div>
        </div>

        <!-- Right: Actions (Quick Add + Notification Bell + Theme Toggle + Avatar) -->
        <div class="flex items-center gap-2.5">
          <!-- + Quick Add CTA Button -->
          <a
            routerLink="/app/quick-add"
            class="bg-amber-500 hover:bg-amber-600 active:scale-[0.98] text-neutral-950 font-medium text-xs md:text-sm py-1.5 px-3 md:px-3.5 rounded-lg shadow-xs transition-all flex items-center gap-1.5"
          >
            <app-icon name="plus" [size]="15" strokeWidth="2"></app-icon>
            <span class="hidden sm:inline">Quick Add</span>
          </a>

          <!-- Notification Bell -->
          <app-notification-bell></app-notification-bell>

          <!-- Dark Mode Toggle Button -->
          <button
            type="button"
            (click)="theme.toggleDarkMode()"
            [attr.aria-label]="theme.isDarkMode() ? 'Switch to light mode' : 'Switch to dark mode'"
            class="p-2 border border-neutral-200 dark:border-neutral-800 rounded-lg bg-white dark:bg-neutral-900 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors shadow-xs cursor-pointer"
          >
            @if (theme.isDarkMode()) {
              <app-icon name="sun" [size]="16" className="text-amber-400"></app-icon>
            } @else {
              <app-icon name="moon" [size]="16" className="text-neutral-700"></app-icon>
            }
          </button>

          <!-- User Avatar & Profile Link -->
          <a
            routerLink="/app/profile"
            class="flex items-center pl-1 group"
            title="View Profile & Settings"
          >
            <div class="w-8 h-8 rounded-full border border-neutral-200 dark:border-neutral-700 overflow-hidden bg-neutral-100 dark:bg-neutral-800 group-hover:ring-2 group-hover:ring-amber-500/30 transition-all">
              <img
                [src]="user()?.avatar || 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80'"
                [alt]="user()?.name || 'Student Avatar'"
                class="w-full h-full object-cover"
              />
            </div>
          </a>
        </div>

      </div>
    </header>
  `
})
export class TopBarComponent {
  private auth = inject(AuthService);
  theme = inject(ThemeService);
  private txService = inject(TransactionService);

  user = this.auth.currentUser;

  get balance() {
    return () => this.txService.getMonthlyBalance('2026-09');
  }
}
