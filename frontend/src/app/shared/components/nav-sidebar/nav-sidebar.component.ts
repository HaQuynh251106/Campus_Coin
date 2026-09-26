import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { NavItem, STUDENT_NAV_ITEMS, STUDENT_SIDEBAR_EXTRA_ITEMS } from '../../navigation.config';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-nav-sidebar',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <aside
      class="w-64 shrink-0 bg-white dark:bg-neutral-900 border-r border-neutral-200 dark:border-neutral-800 h-screen sticky top-0 flex flex-col justify-between p-4 z-20 overflow-y-auto"
      [class.admin-sidebar]="isAdmin"
    >
      <!-- Top Section: Brand & Nav Links -->
      <div>
        <!-- Brand Header -->
        <div class="px-2 py-2.5 mb-5 border-b border-neutral-200 dark:border-neutral-800 pb-4">
          <a [routerLink]="isAdmin ? '/admin/dashboard' : '/app/home'" class="flex items-center gap-2.5 group">
            <div
              class="w-9 h-9 rounded-lg flex items-center justify-center transition-all border bg-amber-500/10 border-amber-500/25 text-amber-600 dark:text-amber-400 shadow-xs"
            >
              <app-icon [name]="isAdmin ? 'shield-check' : 'squirrel-logo'" [size]="20" strokeWidth="1.75"></app-icon>
            </div>
            <div>
              <span class="font-semibold text-lg tracking-tight text-neutral-900 dark:text-neutral-100 block leading-tight">
                Campus<span class="text-amber-500">{{ isAdmin ? 'Admin' : 'Coin' }}</span>
              </span>
              <span class="text-[10px] font-medium text-neutral-400 dark:text-neutral-500 uppercase tracking-wider block">
                {{ isAdmin ? 'Institutional Portal' : 'Student Finance' }}
              </span>
            </div>
          </a>
        </div>

        <!-- Navigation Group -->
        <nav class="space-y-1" aria-label="Sidebar Navigation">
          <div class="text-[11px] font-semibold uppercase tracking-wider text-neutral-400 dark:text-neutral-500 px-3 mb-2">
            {{ isAdmin ? 'Management Console' : 'Menu' }}
          </div>

          @for (item of mainItems; track item.route) {
            <a
              [routerLink]="item.route"
              routerLinkActive="bg-amber-500/10 text-amber-700 dark:text-amber-400 font-medium"
              [routerLinkActiveOptions]="{ exact: false }"
              class="flex items-center justify-between px-3 py-2 rounded-lg transition-all text-sm font-medium text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 hover:bg-neutral-100 dark:hover:bg-neutral-800/60"
            >
              <div class="flex items-center gap-2.5">
                <app-icon [name]="item.icon" [size]="17" strokeWidth="1.5"></app-icon>
                <span>{{ item.label }}</span>
              </div>
              @if (item.badge) {
                <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-400 border border-amber-500/20">
                  {{ item.badge }}
                </span>
              }
            </a>
          }

          @if (!isAdmin && extraItems.length > 0) {
            <div class="pt-4 mt-4 border-t border-neutral-200 dark:border-neutral-800">
              <div class="text-[11px] font-semibold uppercase tracking-wider text-neutral-400 dark:text-neutral-500 px-3 mb-2">
                Explore
              </div>
              @for (item of extraItems; track item.route) {
                <a
                  [routerLink]="item.route"
                  routerLinkActive="bg-amber-500/10 text-amber-700 dark:text-amber-400 font-medium"
                  class="flex items-center justify-between px-3 py-2 rounded-lg transition-all text-sm font-medium text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 hover:bg-neutral-100 dark:hover:bg-neutral-800/60 mb-0.5"
                >
                  <div class="flex items-center gap-2.5">
                    <app-icon [name]="item.icon" [size]="16" strokeWidth="1.5"></app-icon>
                    <span>{{ item.label }}</span>
                  </div>
                  @if (item.badge) {
                    <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 border border-neutral-200 dark:border-neutral-700">
                      {{ item.badge }}
                    </span>
                  }
                </a>
              }
            </div>
          }
        </nav>
      </div>

      <!-- Bottom Section: Quick Profile & Logout -->
      <div class="pt-4 border-t border-neutral-200 dark:border-neutral-800 space-y-3">
        <!-- Logged in user info -->
        <div class="flex items-center gap-3 px-2">
          <div class="w-9 h-9 rounded-full border border-neutral-200 dark:border-neutral-700 overflow-hidden bg-neutral-100 dark:bg-neutral-800 shrink-0">
            <img
              [src]="user()?.avatar || 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80'"
              [alt]="user()?.name || 'User Avatar'"
              class="w-full h-full object-cover"
            />
          </div>
          <div class="overflow-hidden">
            <div class="font-medium text-sm text-neutral-900 dark:text-neutral-100 truncate">
              {{ user()?.name || 'Current User' }}
            </div>
            <div class="text-[11px] text-neutral-500 truncate">
              {{ user()?.studentId }}
            </div>
          </div>
        </div>

        <!-- Logout Action Button -->
        <button
          type="button"
          (click)="onLogout()"
          class="w-full flex items-center justify-center gap-2 text-xs font-medium py-2 px-3 rounded-lg border border-neutral-200 dark:border-neutral-800 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800/80 hover:text-rose-600 dark:hover:text-rose-400 transition-colors cursor-pointer"
        >
          <app-icon name="log-out" [size]="14" strokeWidth="1.5"></app-icon>
          <span>Sign Out</span>
        </button>
      </div>
    </aside>
  `
})
export class NavSidebarComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  @Input() isAdmin = false;
  @Input() mainItems: NavItem[] = STUDENT_NAV_ITEMS;
  @Input() extraItems: NavItem[] = STUDENT_SIDEBAR_EXTRA_ITEMS;

  user = this.auth.currentUser;

  onLogout(): void {
    this.auth.logout();
    this.router.navigate([this.isAdmin ? '/auth/admin-login' : '/auth/login']);
  }
}
