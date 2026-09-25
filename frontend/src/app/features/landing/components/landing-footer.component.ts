import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-landing-footer',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <footer class="border-t border-neutral-200 dark:border-neutral-800 bg-neutral-50/80 dark:bg-neutral-900/70 backdrop-blur-xs transition-colors">
      <div class="max-w-6xl mx-auto px-4 sm:px-6 py-12">
        <div class="grid grid-cols-1 md:grid-cols-4 gap-8 pb-10 border-b border-neutral-200 dark:border-neutral-800">

          <!-- Brand Column -->
          <div class="md:col-span-2 space-y-3">
            <div class="flex items-center gap-2.5">
              <div class="w-7 h-7 rounded-lg bg-amber-500 flex items-center justify-center text-neutral-950 font-bold text-xs shadow-xs">
                ⚡
              </div>
              <span class="font-bold text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
                Campus Coin
              </span>
            </div>
            <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 max-w-sm leading-relaxed">
              Smart spending, student style. A modern financial tracking and AI categorization tool built specifically for college cohorts.
            </p>
          </div>

          <!-- Product Links -->
          <div class="space-y-2.5">
            <h4 class="text-xs font-semibold uppercase tracking-wider text-neutral-900 dark:text-neutral-100">
              Product
            </h4>
            <ul class="space-y-2 text-xs text-neutral-600 dark:text-neutral-300">
              <li>
                <a href="#features" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Features
                </a>
              </li>
              <li>
                <a href="#how-it-works" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  How It Works
                </a>
              </li>
              <li>
                <a href="#stories" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Student Stories
                </a>
              </li>
              <li>
                <a routerLink="/sitemap" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Sitemap & Routes
                </a>
              </li>
            </ul>
          </div>

          <!-- Account Links -->
          <div class="space-y-2.5">
            <h4 class="text-xs font-semibold uppercase tracking-wider text-neutral-900 dark:text-neutral-100">
              Account & Portals
            </h4>
            <ul class="space-y-2 text-xs text-neutral-600 dark:text-neutral-300">
              <li>
                <a routerLink="/auth/login" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Student Log In
                </a>
              </li>
              <li>
                <a routerLink="/auth/register" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Create Account
                </a>
              </li>
              <li>
                <a routerLink="/auth/forgot-password" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Recover Password
                </a>
              </li>
              <li>
                <a routerLink="/auth/admin-login" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Institutional Admin
                </a>
              </li>
            </ul>
          </div>

        </div>

        <!-- Small print -->
        <div class="pt-8 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-[var(--color-text-muted)]">
          <p>
            &copy; 2026 Campus Coin. A student-centered financial wellness initiative.
          </p>
          <p class="font-mono text-[11px]">
            Built with Angular & Tailwind CSS &bull; No Bank Credentials Required
          </p>
        </div>
      </div>
    </footer>
  `
})
export class LandingFooterComponent {}
