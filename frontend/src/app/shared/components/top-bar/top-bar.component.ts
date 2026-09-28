import { Component, OnInit, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { DashboardService } from '../../../core/services/dashboard.service';
import { IconComponent } from '../icon/icon.component';
import { NotificationBellComponent } from '../notification-bell/notification-bell.component';
import { AvatarComponent } from '../avatar/avatar.component';

@Component({
  selector: 'app-top-bar',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, NotificationBellComponent, AvatarComponent],
  template: `
    <header class="sticky top-0 z-30 bg-white/90 dark:bg-neutral-900/90 backdrop-blur-md border-b border-neutral-200 dark:border-neutral-800 px-4 md:px-8 py-2.5 transition-colors">
      <div class="max-w-7xl mx-auto flex items-center justify-between gap-4">

        <!-- Left: Brand / User Greeting -->
        <div class="flex items-center gap-3">
          <!-- Mobile Brand Logo -->
          <a routerLink="/app/home" class="md:hidden flex items-center gap-2 font-semibold text-lg tracking-tight">
            <span class="w-8 h-8 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 flex items-center justify-center shadow-xs">
              <app-icon name="squirrel-logo" [size]="16" strokeWidth="1.75"></app-icon>
            </span>
            <span class="text-neutral-900 dark:text-white font-semibold">Campus<span class="text-amber-500">Coin</span></span>
          </a>

          <!-- Greeting (Desktop / Tablet) -->
          <div class="hidden md:block">
            <h1 class="text-base sm:text-lg font-semibold tracking-tight text-neutral-900 dark:text-neutral-100">
              Welcome back, {{ user()?.name?.split(' ')?.[0] || 'Alex' }}
            </h1>
            <p class="text-[11px] text-[var(--color-text-muted)] font-normal">
              {{ user()?.academicYear }}
            </p>
          </div>
        </div>

        <!--
          Center: current month balance badge.
          The figures come from GET /dashboard, which the header fetches once itself if no other
          screen has already done so. Reading them from the transaction list instead made the badge
          show $0.00 on every route except the Feed, because only the Feed loaded that list.
        -->
        <div class="hidden sm:flex items-center gap-2.5 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-800 rounded-lg px-3 py-1.5 shadow-xs">
          <div class="text-left pr-2.5 border-r border-neutral-200 dark:border-neutral-700">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500 dark:text-neutral-400">{{ periodLabel() }} Net</span>
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
            <app-avatar
              [avatarUrl]="user()?.avatar"
              [name]="user()?.name"
              size="sm"
              className="group-hover:ring-2 group-hover:ring-amber-500/40 transition-all shadow-xs"
            ></app-avatar>
          </a>
        </div>

      </div>
    </header>
  `
})
export class TopBarComponent implements OnInit {
  private auth = inject(AuthService);
  private dashboardService = inject(DashboardService);
  theme = inject(ThemeService);

  user = this.auth.currentUser;

  /**
   * The badge is a read of the dashboard, which is the one endpoint that publishes a month's
   * income, expense and net together. It is fetched here rather than pushed in from a page so the
   * figure is correct on every route, not only the Feed.
   */
  private readonly summary = computed(() => this.dashboardService.dashboard()?.summary ?? null);

  balance = computed(() => {
    const s = this.summary();
    return {
      income: Number(s?.totalIncome ?? 0),
      expense: Number(s?.totalExpense ?? 0),
      net: Number(s?.netAmount ?? 0)
    };
  });

  /** The month the dashboard reported, so the label cannot disagree with the figures. */
  periodLabel = computed(() => {
    const month = this.dashboardService.dashboard()?.periodMonth;
    if (!month) return 'This month';
    const [y, m] = month.split('-');
    const names = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return names[parseInt(m, 10)] || month;
  });

  ngOnInit(): void {
    if (this.dashboardService.dashboard()) return;
    this.dashboardService.getDashboard().subscribe({ error: () => {} });
  }
}
