import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { NavSidebarComponent } from '../../shared/components/nav-sidebar/nav-sidebar.component';
import { ADMIN_NAV_ITEMS } from '../../shared/navigation.config';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [CommonModule, RouterModule, NavSidebarComponent, IconComponent],
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
        <header class="bg-white dark:bg-neutral-900 border-b border-slate-200 dark:border-neutral-800 px-6 py-3.5 flex items-center justify-between sticky top-0 z-30 shadow-sm">
          <div class="flex items-center gap-3">
            <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-slate-100 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 text-xs font-semibold text-slate-700 dark:text-slate-300 uppercase tracking-wider">
              <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              Admin Control Center
            </span>
          </div>

          <div class="flex items-center gap-3">
            <!-- Theme Toggle -->
            <button
              type="button"
              (click)="theme.toggleDarkMode()"
              class="p-2 text-slate-500 hover:text-slate-800 dark:text-neutral-400 dark:hover:text-white rounded-md hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
              title="Toggle Theme"
            >
              @if (theme.isDarkMode()) {
                <app-icon name="sun" [size]="18"></app-icon>
              } @else {
                <app-icon name="moon" [size]="18"></app-icon>
              }
            </button>

            <!-- Admin Profile Link & Quick Exit -->
            <a routerLink="/app/home" class="text-xs font-medium text-slate-600 dark:text-neutral-400 hover:text-black dark:hover:text-white px-2 py-1 rounded border border-slate-200 dark:border-neutral-700 hover:bg-slate-50">
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
