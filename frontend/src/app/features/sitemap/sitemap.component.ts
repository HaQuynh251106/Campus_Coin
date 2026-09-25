import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-sitemap',
  standalone: true,
  imports: [CommonModule, RouterModule, BreadcrumbsComponent],
  template: `
    <div class="space-y-6 max-w-4xl mx-auto">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'System Sitemap' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-xl shadow-xs">
            🗺️
          </div>
          <div>
            <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
              Campus Coin — Information Architecture & Sitemap
            </h2>
            <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
              Direct clickable index to all application routes, roles and lazy-loaded modules.
            </p>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">

        <!-- 0. Public Guest Module -->
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs md:col-span-2">
          <div class="flex items-center gap-2 mb-3 pb-2 border-b border-neutral-200 dark:border-neutral-800">
            <span class="text-base">🌐</span>
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 tracking-tight">
              Public Guest Entry Route
            </h3>
          </div>

          <div class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
            <a routerLink="/" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-0.5 text-xs">
              / (Root Landing Page)
            </a>
            <span class="text-xs text-[var(--color-text-muted)]">Public marketing landing page for guest visitors explaining Campus Coin with hero, interactive component preview, feature highlights, and student testimonials.</span>
          </div>
        </div>

        <!-- 1. Authentication Module -->
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex items-center gap-2 mb-3 pb-2 border-b border-neutral-200 dark:border-neutral-800">
            <span class="text-base">🔐</span>
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 tracking-tight">
              Auth Routes (/auth)
            </h3>
          </div>

          <ul class="space-y-2 text-xs">
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/auth/login" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-0.5">
                /auth/login
              </a>
              <span class="text-neutral-500">Student login with validation and 1-click demo filler.</span>
            </li>
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/auth/register" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-0.5">
                /auth/register
              </a>
              <span class="text-neutral-500">Student registration with academic year and major selections.</span>
            </li>
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/auth/forgot-password" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-0.5">
                /auth/forgot-password
              </a>
              <span class="text-neutral-500">3-step password recovery flow with simulated email token link.</span>
            </li>
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/auth/admin-login" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-0.5">
                /auth/admin-login
              </a>
              <span class="text-neutral-500">Distinct enterprise/academic dashboard administrative login.</span>
            </li>
          </ul>
        </div>

        <!-- 2. Admin Portal Module -->
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex items-center gap-2 mb-3 pb-2 border-b border-neutral-200 dark:border-neutral-800">
            <span class="text-base">🛡️</span>
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 tracking-tight">
              Admin Portal (/admin)
            </h3>
          </div>

          <ul class="space-y-2 text-xs">
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/admin/dashboard" class="font-mono font-medium text-emerald-600 dark:text-emerald-400 hover:underline block mb-0.5">
                /admin/dashboard
              </a>
              <span class="text-neutral-500">System KPIs, campus transaction volume, and macro category meters.</span>
            </li>
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/admin/users" class="font-mono font-medium text-emerald-600 dark:text-emerald-400 hover:underline block mb-0.5">
                /admin/users
              </a>
              <span class="text-neutral-500">Directory table with search, account disable toggle, and password resets.</span>
            </li>
            <li class="p-2.5 rounded-lg border border-neutral-200 dark:border-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
              <a routerLink="/admin/categories" class="font-mono font-medium text-emerald-600 dark:text-emerald-400 hover:underline block mb-0.5">
                /admin/categories
              </a>
              <span class="text-neutral-500">System-wide default categories and student tip template broadcaster.</span>
            </li>
          </ul>
        </div>

      </div>

      <!-- 3. Student Portal Module (Full Width) -->
      <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex items-center gap-2 mb-3 pb-2 border-b border-neutral-200 dark:border-neutral-800">
          <span class="text-base">🎓</span>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 tracking-tight">
            Student Portal (/app) — Navigation Experience
          </h3>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3 text-xs">
          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/home" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/home
            </a>
            <span class="text-neutral-500">Feed view: balance hero, AI story card, budget meters, grouped transaction log.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/quick-add" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/quick-add
            </a>
            <span class="text-neutral-500">Conversational AI input, smart category chips, manual form & soft-delete table.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/reports" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/reports
            </a>
            <span class="text-neutral-500">Analytics dashboard: 6-month trend, donut breakdown, daily rhythm, PDF export.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/budgets" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/budgets
            </a>
            <span class="text-neutral-500">Category limits, real-time transaction spend computation, and near-limit alerts.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/insights" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/insights
            </a>
            <span class="text-neutral-500">Historical AI-generated monthly reviews, actionable student tips, and pin toggle.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40">
            <a routerLink="/app/categories" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/categories
            </a>
            <span class="text-neutral-500">System default lock vs user-created custom categories with full CRUD.</span>
          </div>

          <div class="p-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50/60 dark:bg-neutral-800/40 sm:col-span-2 md:col-span-3">
            <a routerLink="/app/profile" class="font-mono font-medium text-amber-600 dark:text-amber-400 hover:underline block mb-1 text-sm">
              /app/profile
            </a>
            <span class="text-neutral-500">Student allowances, savings goals, dark mode switch, font scaling (sm/md/lg), and CSV bulk import preview table.</span>
          </div>
        </div>
      </div>

    </div>
  `
})
export class SitemapComponent {}
