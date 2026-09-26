import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-auth-layout',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <div class="min-h-screen bg-[#F9FAFB] dark:bg-[#09090B] flex flex-col justify-between p-4 sm:p-6 transition-colors relative overflow-hidden">
      <!-- Background Subtle Ambient Glow -->
      <div class="absolute -top-32 left-1/2 -translate-x-1/2 w-96 h-96 bg-amber-500/5 rounded-full blur-3xl pointer-events-none"></div>

      <!-- Header with Brand & Theme Toggle -->
      <header class="max-w-md w-full mx-auto flex items-center justify-between pt-2 pb-6 z-10">
        <a routerLink="/" class="flex items-center gap-2.5 group cursor-pointer" title="Return to Landing Page">
          <div class="w-9 h-9 rounded-lg bg-amber-500/10 border border-amber-500/25 flex items-center justify-center text-amber-600 dark:text-amber-400 shadow-xs group-hover:scale-105 transition-transform">
            <app-icon name="squirrel-logo" [size]="20" strokeWidth="1.75"></app-icon>
          </div>
          <div>
            <span class="font-semibold text-xl tracking-tight text-neutral-900 dark:text-neutral-50 block leading-tight">
              Campus<span class="text-amber-500">Coin</span>
            </span>
            <span class="text-[10px] font-medium text-neutral-400 uppercase tracking-wider block">
              Smart Student Spending
            </span>
          </div>
        </a>

        <button
          type="button"
          (click)="theme.toggleDarkMode()"
          class="p-2 border border-neutral-200 dark:border-neutral-800 rounded-lg bg-white dark:bg-neutral-900 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800 shadow-xs transition-colors cursor-pointer"
          [attr.aria-label]="theme.isDarkMode() ? 'Switch to light mode' : 'Switch to dark mode'"
        >
          @if (theme.isDarkMode()) {
            <app-icon name="sun" [size]="16" className="text-amber-400"></app-icon>
          } @else {
            <app-icon name="moon" [size]="16" className="text-neutral-700"></app-icon>
          }
        </button>
      </header>

      <!-- Center Auth Content -->
      <main class="max-w-md w-full mx-auto z-10 my-auto">
        <router-outlet></router-outlet>
      </main>

      <!-- Footer -->
      <footer class="max-w-md w-full mx-auto text-center py-4 text-xs font-medium text-neutral-400 dark:text-neutral-500 z-10 flex items-center justify-center gap-4">
        <span>© 2026 Campus Coin</span>
      </footer>
    </div>
  `
})
export class AuthLayoutComponent {
  theme = inject(ThemeService);
}
