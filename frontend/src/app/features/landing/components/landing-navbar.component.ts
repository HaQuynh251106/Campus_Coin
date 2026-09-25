import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ThemeService } from '../../../core/services/theme.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-landing-navbar',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <nav class="sticky top-0 z-50 w-full bg-white/85 dark:bg-neutral-900/85 backdrop-blur-md border-b border-neutral-200/80 dark:border-neutral-800 transition-colors">
      <div class="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">

        <!-- Logo & Wordmark with Squirrel Brand Mark -->
        <a routerLink="/" class="flex items-center gap-2.5 group cursor-pointer">
          <div class="w-8 h-8 rounded-lg bg-amber-500 flex items-center justify-center text-neutral-950 shadow-xs group-hover:scale-105 transition-transform">
            <app-icon name="squirrel-logo" [size]="18" strokeWidth="1.75"></app-icon>
          </div>
          <div class="flex items-center gap-2">
            <span class="font-bold text-base sm:text-lg tracking-tight text-neutral-900 dark:text-neutral-50">
              Campus Coin
            </span>
            <span class="hidden sm:inline-block text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
              Student Edition
            </span>
          </div>
        </a>

        <!-- Desktop Navigation Links -->
        <div class="hidden md:flex items-center gap-6 text-xs sm:text-sm font-medium text-neutral-600 dark:text-neutral-300">
          <a
            href="#features"
            (click)="scrollTo($event, 'features')"
            class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors"
          >
            Features
          </a>
          <a
            href="#how-it-works"
            (click)="scrollTo($event, 'how-it-works')"
            class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors"
          >
            How It Works
          </a>
          <a
            href="#stories"
            (click)="scrollTo($event, 'stories')"
            class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors"
          >
            Student Stories
          </a>
        </div>

        <!-- Right Action Items -->
        <div class="hidden md:flex items-center gap-3">
          <!-- Theme Toggle -->
          <button
            type="button"
            (click)="theme.toggleDarkMode()"
            [title]="theme.isDarkMode() ? 'Switch to light mode' : 'Switch to dark mode'"
            class="p-2 rounded-lg text-neutral-500 dark:text-neutral-400 hover:bg-neutral-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
          >
            <app-icon [name]="theme.isDarkMode() ? 'sun' : 'moon'" [size]="16" strokeWidth="1.5"></app-icon>
          </button>

          <!-- Log In (Ghost / Outline) -->
          <a
            routerLink="/auth/login"
            class="px-3.5 py-1.5 text-xs sm:text-sm font-medium text-neutral-700 dark:text-neutral-200 hover:text-neutral-950 dark:hover:text-white border border-neutral-300 dark:border-neutral-700 hover:border-neutral-400 dark:hover:border-neutral-600 rounded-lg transition-colors"
          >
            Log in
          </a>

          <!-- Sign Up (Primary Gold) -->
          <a
            routerLink="/auth/register"
            class="px-3.5 py-1.5 text-xs sm:text-sm font-medium text-neutral-950 bg-amber-500 hover:bg-amber-600 rounded-lg shadow-xs transition-colors cursor-pointer"
          >
            Sign up free
          </a>
        </div>

        <!-- Mobile Controls (Theme Toggle + Hamburger) -->
        <div class="flex items-center gap-2 md:hidden">
          <button
            type="button"
            (click)="theme.toggleDarkMode()"
            class="p-2 rounded-lg text-neutral-500 dark:text-neutral-400 hover:bg-neutral-100 dark:hover:bg-neutral-800"
          >
            <app-icon [name]="theme.isDarkMode() ? 'sun' : 'moon'" [size]="16" strokeWidth="1.5"></app-icon>
          </button>

          <button
            type="button"
            (click)="mobileMenuOpen.set(!mobileMenuOpen())"
            aria-label="Toggle navigation menu"
            class="p-2 text-neutral-700 dark:text-neutral-200 hover:bg-neutral-100 dark:hover:bg-neutral-800 rounded-lg transition-colors cursor-pointer"
          >
            <app-icon [name]="mobileMenuOpen() ? 'x' : 'menu'" [size]="18" strokeWidth="1.5"></app-icon>
          </button>
        </div>

      </div>

      <!-- Mobile Dropdown Drawer -->
      @if (mobileMenuOpen()) {
        <div class="md:hidden border-t border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 px-4 py-4 space-y-3 animate-fade-in shadow-lg">
          <div class="flex flex-col space-y-2 text-sm font-medium text-neutral-700 dark:text-neutral-300">
            <a
              href="#features"
              (click)="scrollTo($event, 'features'); mobileMenuOpen.set(false)"
              class="py-2 px-3 rounded-lg hover:bg-neutral-100 dark:hover:bg-neutral-800"
            >
              Features
            </a>
            <a
              href="#how-it-works"
              (click)="scrollTo($event, 'how-it-works'); mobileMenuOpen.set(false)"
              class="py-2 px-3 rounded-lg hover:bg-neutral-100 dark:hover:bg-neutral-800"
            >
              How It Works
            </a>
            <a
              href="#stories"
              (click)="scrollTo($event, 'stories'); mobileMenuOpen.set(false)"
              class="py-2 px-3 rounded-lg hover:bg-neutral-100 dark:hover:bg-neutral-800"
            >
              Student Stories
            </a>
          </div>

          <div class="pt-3 border-t border-neutral-200 dark:border-neutral-800 flex flex-col gap-2">
            <a
              routerLink="/auth/login"
              class="w-full text-center py-2 px-4 text-xs font-medium text-neutral-700 dark:text-neutral-200 border border-neutral-300 dark:border-neutral-700 rounded-lg"
            >
              Log in
            </a>
            <a
              routerLink="/auth/register"
              class="w-full text-center py-2 px-4 text-xs font-medium text-neutral-950 bg-amber-500 hover:bg-amber-600 rounded-lg shadow-xs"
            >
              Sign up free
            </a>
          </div>
        </div>
      }
    </nav>
  `
})
export class LandingNavbarComponent {
  theme = inject(ThemeService);
  mobileMenuOpen = signal(false);

  scrollTo(event: Event, elementId: string): void {
    event.preventDefault();
    const el = document.getElementById(elementId);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
}
