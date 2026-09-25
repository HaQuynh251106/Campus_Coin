import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { NavSidebarComponent } from '../../shared/components/nav-sidebar/nav-sidebar.component';
import { ADMIN_NAV_ITEMS } from '../../shared/navigation.config';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { NotificationBellComponent } from '../../shared/components/notification-bell/notification-bell.component';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [CommonModule, RouterModule, NavSidebarComponent, IconComponent, NotificationBellComponent],
  template: `
    <div class="min-h-screen bg-slate-50 dark:bg-neutral-950 flex font-sans transition-colors">
      <!-- Admin Sidebar (Present at all breakpoints, classic dashboard layout) -->
      <app-nav-sidebar
        [isAdmin]="true"
        [mainItems]="adminNavItems"
        [extraItems]="[]"
      ></app-nav-sidebar>

      <!-- Main Admin Workspace -->
      <div class="flex-1 flex flex-col min-w-0">
        <!-- Calm, functional Admin Top Header -->
        <header class="bg-white dark:bg-neutral-900 border-b border-slate-200 dark:border-neutral-800 px-6 py-3.5 flex items-center justify-between sticky top-0 z-30 shadow-xs">
          <div class="flex items-center gap-3">
            <span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-500/10 border border-amber-500/20 text-xs font-medium text-amber-700 dark:text-amber-400">
              <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse"></span>
              Admin Control Center
            </span>
          </div>

          <div class="flex items-center gap-2.5">
            <!-- Notification Bell -->
            <app-notification-bell></app-notification-bell>

            <!-- Theme Toggle -->
            <button
              type="button"
              (click)="theme.toggleDarkMode()"
              class="p-2 text-slate-500 hover:text-slate-800 dark:text-neutral-400 dark:hover:text-white rounded-lg border border-slate-200 dark:border-neutral-800 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
              title="Toggle Theme"
            >
              @if (theme.isDarkMode()) {
                <app-icon name="sun" [size]="16"></app-icon>
              } @else {
                <app-icon name="moon" [size]="16"></app-icon>
              }
            </button>

            <!-- Admin Profile Link & Quick Exit -->
            <a
              routerLink="/app/home"
              class="text-xs font-medium text-slate-600 dark:text-neutral-400 hover:text-slate-900 dark:hover:text-neutral-100 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-neutral-800 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors"
            >
              Student App View ↗
            </a>
          </div>
        </header>

        <!-- Dynamic Admin Child Pages -->
        <main class="flex-1 p-6 md:p-8 max-w-7xl w-full mx-auto">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>
  `
})
export class AdminLayoutComponent {
  theme = inject(ThemeService);
  auth = inject(AuthService);
  adminNavItems = ADMIN_NAV_ITEMS;
}
