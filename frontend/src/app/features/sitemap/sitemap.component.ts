import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/components/icon/icon.component';

export interface RouteItem {
  path: string;
  name: string;
  description: string;
  isPublic: boolean;
  requiresRole?: 'student' | 'admin';
  isModule12?: boolean;
}

export interface RouteSection {
  title: string;
  subtitle: string;
  icon: string;
  badge: string;
  badgeClass: string;
  routes: RouteItem[];
}

@Component({
  selector: 'app-sitemap',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <div class="min-h-screen bg-[#F9FAFB] dark:bg-[#09090B] text-neutral-900 dark:text-neutral-50 transition-colors">
      <!-- Top Navigation Bar -->
      <header class="border-b border-neutral-200/80 dark:border-neutral-800/80 bg-white/80 dark:bg-neutral-900/80 backdrop-blur-md sticky top-0 z-30">
        <div class="max-w-5xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
          <!-- Logo & Brand Link -->
          <a routerLink="/" class="flex items-center gap-2.5 group cursor-pointer" title="Return to Landing Page">
            <div class="w-9 h-9 rounded-lg bg-amber-500/10 border border-amber-500/25 flex items-center justify-center text-amber-600 dark:text-amber-400 shadow-xs group-hover:scale-105 transition-transform">
              <app-icon name="squirrel-logo" [size]="20" strokeWidth="1.75"></app-icon>
            </div>
            <div>
              <span class="font-semibold text-lg tracking-tight text-neutral-900 dark:text-neutral-50 block leading-tight">
                Campus<span class="text-amber-500">Coin</span>
              </span>
              <span class="text-[10px] font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider block">
                Information Architecture
              </span>
            </div>
          </a>

          <!-- Right Actions -->
          <div class="flex items-center gap-2.5">
            <button
              type="button"
              (click)="theme.toggleDarkMode()"
              class="p-2 border border-neutral-200 dark:border-neutral-800 rounded-lg bg-white dark:bg-neutral-900 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800 shadow-xs transition-colors cursor-pointer"
              [attr.aria-label]="theme.isDarkMode() ? 'Switch to light mode' : 'Switch to dark mode'"
              title="Toggle color theme"
            >
              @if (theme.isDarkMode()) {
                <app-icon name="sun" [size]="16" className="text-amber-400"></app-icon>
              } @else {
                <app-icon name="moon" [size]="16" className="text-neutral-700"></app-icon>
              }
            </button>

            <a
              routerLink="/"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 transition-colors shadow-xs"
            >
              <app-icon name="arrow-left" [size]="14"></app-icon>
              <span>Back to Home</span>
            </a>

            <a
              routerLink="/auth/login"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-amber-500 hover:bg-amber-400 text-neutral-950 transition-colors shadow-xs"
            >
              <span>Sign In</span>
              <app-icon name="arrow-right" [size]="14"></app-icon>
            </a>
          </div>
        </div>
      </header>

      <!-- Main Content Container -->
      <main class="max-w-5xl mx-auto px-4 sm:px-6 py-10 sm:py-14 space-y-8">

        <!-- Page Hero / Introduction -->
        <div class="border border-neutral-200 dark:border-neutral-800 rounded-2xl p-6 sm:p-8 bg-white dark:bg-neutral-900 shadow-subtle relative overflow-hidden">
          <div class="absolute -right-12 -top-12 w-48 h-48 bg-amber-500/5 rounded-full blur-2xl pointer-events-none"></div>

          <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 relative z-10">
            <div class="space-y-2">
              <div class="inline-flex items-center gap-2 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-500/10 border border-amber-500/20 text-amber-700 dark:text-amber-400">
                <app-icon name="map" [size]="13"></app-icon>
                <span>SRS Deliverable Requirement</span>
              </div>
              <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
                Campus Coin — Sitemap
              </h1>
              <p class="text-sm text-neutral-600 dark:text-neutral-400 max-w-2xl leading-relaxed">
                Direct clickable index and architectural wayfinding map of all application routes, roles, and access boundaries currently implemented in the Campus Coin platform.
              </p>
            </div>

            <!-- Quick Stats Chips -->
            <div class="flex flex-wrap sm:flex-col gap-2 shrink-0 text-xs">
              <div class="px-3 py-1.5 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800/60 flex items-center gap-2">
                <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
                <span class="font-medium text-neutral-700 dark:text-neutral-300">4 Architecture Sections</span>
              </div>
              <div class="px-3 py-1.5 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800/60 flex items-center gap-2">
                <span class="w-2 h-2 rounded-full bg-amber-500"></span>
                <span class="font-medium text-neutral-700 dark:text-neutral-300">22 Active Routes</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Sitemap Sections -->
        <div class="space-y-6">

          @for (section of sections; track section.title) {
            <section class="border border-neutral-200 dark:border-neutral-800 rounded-2xl bg-white dark:bg-neutral-900 overflow-hidden shadow-subtle">
              <!-- Section Header -->
              <div class="p-5 sm:px-6 border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50/70 dark:bg-neutral-800/40 flex flex-wrap items-center justify-between gap-3">
                <div class="flex items-center gap-3">
                  <div class="w-9 h-9 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 flex items-center justify-center shadow-xs">
                    <app-icon [name]="section.icon" [size]="18" strokeWidth="1.75"></app-icon>
                  </div>
                  <div>
                    <h2 class="text-base sm:text-lg font-bold text-neutral-900 dark:text-neutral-50 tracking-tight">
                      {{ section.title }}
                    </h2>
                    <p class="text-xs text-neutral-500 dark:text-neutral-400">
                      {{ section.subtitle }}
                    </p>
                  </div>
                </div>

                <div class="flex items-center gap-2">
                  <span [class]="section.badgeClass">
                    {{ section.badge }}
                  </span>
                  <span class="text-xs font-mono font-medium text-neutral-400 dark:text-neutral-500 px-2 py-0.5 rounded border border-neutral-200 dark:border-neutral-800">
                    {{ section.routes.length }} routes
                  </span>
                </div>
              </div>

              <!-- Nested Route List -->
              <div class="p-4 sm:p-6 divide-y divide-neutral-100 dark:divide-neutral-800/80">
                @for (route of section.routes; track route.path) {
                  <div class="py-3.5 first:pt-1 last:pb-1 flex flex-col sm:flex-row sm:items-center justify-between gap-3 group">
                    <!-- Route Left Info -->
                    <div class="flex items-start gap-3 min-w-0">
                      <div class="mt-0.5 text-neutral-400 dark:text-neutral-500 select-none">
                        <span class="font-mono text-xs">├──</span>
                      </div>

                      <div class="space-y-1 min-w-0">
                        <div class="flex flex-wrap items-center gap-2">
                          @if (route.isPublic) {
                            <a
                              [routerLink]="route.path"
                              class="font-mono text-xs sm:text-sm font-bold text-amber-600 dark:text-amber-400 hover:text-amber-700 dark:hover:text-amber-300 hover:underline flex items-center gap-1 transition-colors"
                            >
                              <span>{{ route.path }}</span>
                              <app-icon name="arrow-up-right" [size]="13" className="opacity-75"></app-icon>
                            </a>
                          } @else {
                            <span class="font-mono text-xs sm:text-sm font-bold text-neutral-800 dark:text-neutral-200 flex items-center gap-1.5">
                              <app-icon name="lock" [size]="13" className="text-neutral-400 dark:text-neutral-500"></app-icon>
                              <span>{{ route.path }}</span>
                            </span>
                          }

                          <span class="font-semibold text-xs sm:text-sm text-neutral-900 dark:text-neutral-100">
                            — {{ route.name }}
                          </span>

                          @if (route.isModule12) {
                            <span class="px-1.5 py-0.5 text-[10px] font-bold rounded bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
                              Module 12
                            </span>
                          }
                        </div>

                        <p class="text-xs text-neutral-600 dark:text-neutral-400 leading-relaxed pl-5 sm:pl-0">
                          {{ route.description }}
                        </p>
                      </div>
                    </div>

                    <!-- Route Right Status/Action -->
                    <div class="sm:shrink-0 flex items-center gap-2 pl-5 sm:pl-0">
                      @if (route.isPublic) {
                        <a
                          [routerLink]="route.path"
                          class="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-semibold rounded-lg bg-neutral-100 dark:bg-neutral-800 text-neutral-700 dark:text-neutral-300 hover:bg-amber-500 hover:text-neutral-950 transition-colors shadow-2xs"
                        >
                          <span>Open</span>
                          <app-icon name="arrow-right" [size]="11"></app-icon>
                        </a>
                      } @else {
                        <span class="inline-flex items-center gap-1 px-2 py-0.5 text-[11px] font-medium rounded-full bg-neutral-100 dark:bg-neutral-800/80 text-neutral-500 dark:text-neutral-400 border border-neutral-200/80 dark:border-neutral-700/60">
                          <app-icon name="lock" [size]="11"></app-icon>
                          <span>{{ route.requiresRole === 'admin' ? 'Requires admin login' : 'Requires student login' }}</span>
                        </span>
                      }
                    </div>
                  </div>
                }
              </div>
            </section>
          }

        </div>

        <!-- ASCII Spec Format Verification Card (Directly from SRS Requirements) -->
        <div class="border border-neutral-200 dark:border-neutral-800 rounded-2xl p-6 bg-white dark:bg-neutral-900 shadow-subtle space-y-3">
          <div class="flex items-center justify-between gap-4">
            <div class="flex items-center gap-2 text-xs font-semibold text-neutral-700 dark:text-neutral-300 uppercase tracking-wider">
              <app-icon name="file-text" [size]="14"></app-icon>
              <span>Plain Hierarchy Reference</span>
            </div>
            <span class="text-[11px] text-neutral-400">app.routes.ts mapping</span>
          </div>

          <pre class="p-4 rounded-xl bg-neutral-950 text-neutral-200 font-mono text-xs overflow-x-auto leading-relaxed border border-neutral-800 selection:bg-amber-500 selection:text-neutral-950">Campus Coin — Sitemap

Public
├── / — Guest Landing Page
└── /sitemap — This page

Authentication (public, unified login)
├── /auth/login — Log in (single form for both Student and Admin; role-based redirect after login)
├── /auth/register — Create a student account
└── /auth/forgot-password — Password recovery

Student Portal (requires student login)
├── /app/home — Feed / Dashboard
├── /app/quick-add — Add income or expense
├── /app/reports — Reports & analytics
├── /app/budgets — Budgets & goals
├── /app/categories — Manage categories
├── /app/recurring — Recurring rules (subscriptions & bills)
├── /app/tips — Personalized saving tips
├── /app/bookmarks — Bookmarked tips & notes (accessed via Saving Tips)
├── /app/profile — Profile & preferences
├── /app/imports — CSV import with smart auto-categorization
├── /app/insights — Monthly automated spending insights
├── /app/anomalies — Anomaly detection & outlier check
├── /app/forecast — Next month forecast & cash flow projection
└── /app/recent-activity — Recent activity log & audit trail

Admin Portal (requires admin login)
├── /admin/dashboard — Usage statistics overview
├── /admin/users — User account management
└── /admin/categories — Default categories & tip templates</pre>
        </div>

      </main>

      <!-- Footer -->
      <footer class="border-t border-neutral-200 dark:border-neutral-800 py-8 mt-12 bg-white/50 dark:bg-neutral-900/50 backdrop-blur-xs text-center text-xs text-neutral-500 dark:text-neutral-400">
        <div class="max-w-5xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-4">
          <p>&copy; 2026 Campus Coin. Student Expense Management System.</p>
          <div class="flex items-center gap-4">
            <a routerLink="/" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">Home</a>
            <span class="text-neutral-300 dark:text-neutral-700">•</span>
            <a routerLink="/auth/login" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">Sign In</a>
            <span class="text-neutral-300 dark:text-neutral-700">•</span>
            <a routerLink="/auth/register" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">Register</a>
          </div>
        </div>
      </footer>
    </div>
  `
})
export class SitemapComponent {
  theme = inject(ThemeService);

  readonly sections: RouteSection[] = [
    {
      title: 'Public',
      subtitle: 'Open access routes for unauthenticated visitors and wayfinding',
      icon: 'globe',
      badge: 'Public Access',
      badgeClass: 'px-2.5 py-0.5 text-xs font-semibold rounded-full bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border border-emerald-500/20',
      routes: [
        {
          path: '/',
          name: 'Guest Landing Page',
          description: 'Marketing entry point explaining Campus Coin with interactive 3D physics coins, feature highlights, and student stories.',
          isPublic: true
        },
        {
          path: '/sitemap',
          name: 'Sitemap Page',
          description: 'Complete wayfinding index and navigation hierarchy of all public and protected routes across the application.',
          isPublic: true
        }
      ]
    },
    {
      title: 'Authentication',
      subtitle: 'Public unified credentials gateway with automated role routing',
      icon: 'key',
      badge: 'Public Auth Gateway',
      badgeClass: 'px-2.5 py-0.5 text-xs font-semibold rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20',
      routes: [
        {
          path: '/auth/login',
          name: 'Unified Sign In',
          description: 'Single login form for both Students and Institutional Admins with automated role-based redirect after verification.',
          isPublic: true
        },
        {
          path: '/auth/register',
          name: 'Create Account',
          description: 'Student registration flow with academic year, major selections, and initial allowance settings.',
          isPublic: true
        },
        {
          path: '/auth/forgot-password',
          name: 'Password Recovery',
          description: 'Secure tokenized password reset request flow with development link delivery.',
          isPublic: true
        }
      ]
    },
    {
      title: 'Student Portal',
      subtitle: 'Personal financial workspace for students (requires authenticated session)',
      icon: 'graduation-cap',
      badge: 'Requires Student Login',
      badgeClass: 'px-2.5 py-0.5 text-xs font-semibold rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20',
      routes: [
        {
          path: '/app/home',
          name: 'Feed / Dashboard',
          description: 'Main dashboard displaying total account balance, monthly budget consumption gauge, and recent spending transactions.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/quick-add',
          name: 'Add Income or Expense',
          description: 'Fast transaction logger with category selection, date picker, receipt notes, and transaction type toggle.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/reports',
          name: 'Reports & Analytics',
          description: 'Visual financial analytics with 6-month trends, category breakdowns, daily spending rhythm, and export.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/budgets',
          name: 'Budgets & Goals',
          description: 'Category-specific spending limits with dynamic progress bars and automated near-limit threshold warnings.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/categories',
          name: 'Manage Categories',
          description: 'Personal spending categories with color badges, icon pickers, and complete CRUD operations.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/recurring',
          name: 'Recurring Rules',
          description: 'Scheduled recurring expenses, subscriptions, bills, and automatic monthly allowances.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/tips',
          name: 'Saving Tips',
          description: 'Personalized smart financial tips and saving habits generated from historical spending patterns.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/bookmarks',
          name: 'Saved Tips & Notes',
          description: 'Repository for bookmarked financial tips and custom personal spending notes, accessed via the Saving Tips page.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/profile',
          name: 'Profile & Settings',
          description: 'Student profile configuration, monthly allowance, dark mode toggle, and font scaling options.',
          isPublic: false,
          requiresRole: 'student'
        },
        {
          path: '/app/imports',
          name: 'CSV Import',
          description: 'Bulk CSV transaction upload with column mapping, preview validation, duplicate detection, and AI suggestions.',
          isPublic: false,
          requiresRole: 'student',
          isModule12: true
        },
        {
          path: '/app/insights',
          name: 'Monthly Insights',
          description: 'Automated narrative spending summaries, category variances, and actionable saving suggestions.',
          isPublic: false,
          requiresRole: 'student',
          isModule12: true
        },
        {
          path: '/app/anomalies',
          name: 'Anomaly Detection',
          description: 'Automated outlier detection for suspicious transactions, duplicate charges, and unusual amount spikes.',
          isPublic: false,
          requiresRole: 'student',
          isModule12: true
        },
        {
          path: '/app/forecast',
          name: 'Expense Forecasting',
          description: 'Predictive forecasting model projecting end-of-month and next-month spending totals.',
          isPublic: false,
          requiresRole: 'student',
          isModule12: true
        },
        {
          path: '/app/recent-activity',
          name: 'Recent Activity',
          description: 'Comprehensive chronological audit trail of all transactions, rule triggers, and category modifications.',
          isPublic: false,
          requiresRole: 'student',
          isModule12: true
        }
      ]
    },
    {
      title: 'Admin Portal',
      subtitle: 'Governance and institutional management (requires administrative credentials)',
      icon: 'shield-check',
      badge: 'Requires Admin Login',
      badgeClass: 'px-2.5 py-0.5 text-xs font-semibold rounded-full bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border border-emerald-500/20',
      routes: [
        {
          path: '/admin/dashboard',
          name: 'Institutional Dashboard',
          description: 'System KPIs, campus-wide transaction metrics, active user statistics, and macro category breakdown.',
          isPublic: false,
          requiresRole: 'admin'
        },
        {
          path: '/admin/users',
          name: 'User Management Directory',
          description: 'Searchable student directory with role management, account status toggles, and password reset actions.',
          isPublic: false,
          requiresRole: 'admin'
        },
        {
          path: '/admin/categories',
          name: 'Default Categories & Tip Templates',
          description: 'System default category configuration and campus-wide financial tip template broadcaster.',
          isPublic: false,
          requiresRole: 'admin'
        }
      ]
    }
  ];
}
