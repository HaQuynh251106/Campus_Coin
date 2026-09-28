import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of, catchError } from 'rxjs';
import { AdminService, AdminKpis } from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { AdminTopCategory, AdminUsageStats } from '../../../core/models/admin.model';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div class="space-y-6">

      <!-- Top Header -->
      <div class="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-slate-200 dark:border-neutral-800">
        <div>
          <h2 class="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
            Institutional Overview
          </h2>
          <p class="text-xs text-slate-500 dark:text-neutral-400">
            System health, active student demographics, and campus financial aggregate indicators.
          </p>
        </div>

        <div class="flex items-center gap-2">
          <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-50 dark:bg-emerald-950/50 text-emerald-700 dark:text-emerald-300 text-xs font-medium border border-emerald-200 dark:border-emerald-800">
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            System status: Healthy
          </span>
        </div>
      </div>

      <!-- 4 KPI Cards with Gold Accent on Key Metrics -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div class="bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-[var(--color-text-muted)] uppercase tracking-wider block">Registered Students</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ kpis ? kpis.totalUsers : (isLoading ? '...' : 0) }}
          </div>
          <div class="text-xs text-emerald-600 dark:text-emerald-400 font-medium mt-1">
            Registered accounts
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-[var(--color-text-muted)] uppercase tracking-wider block">Active Users (30d)</span>
          <div class="text-2xl font-bold text-amber-600 dark:text-amber-400 mt-1">
            {{ kpis ? kpis.activeUsers : (isLoading ? '...' : 0) }}
          </div>
          <div class="text-xs text-[var(--color-text-muted)] font-medium mt-1">
            30-day active sessions
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-[var(--color-text-muted)] uppercase tracking-wider block">Total Volume Logged</span>
          <div class="text-2xl font-bold text-amber-600 dark:text-amber-400 mt-1">
            {{ kpis ? money(kpis.totalVolumeTracked) : (isLoading ? '...' : '$0.00') }}
          </div>
          <div class="text-xs text-[var(--color-text-muted)] font-medium mt-1">
            Total expenses recorded
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-[var(--color-text-muted)] uppercase tracking-wider block">Avg. Monthly Student Outflow</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ kpis ? money(kpis.avgStudentMonthlySpend) : (isLoading ? '...' : '$0.00') }}
          </div>
          <div class="text-xs text-[var(--color-text-muted)] font-medium mt-1">
            Average per active student
          </div>
        </div>
      </div>

      <!-- Charts & Campus Volume Breakdown -->
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">

        <!-- Left: System-Wide Volume Breakdown (8 cols) -->
        <div class="lg:col-span-8 bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <div class="flex items-center justify-between mb-4 pb-2 border-b border-slate-100 dark:border-neutral-800">
            <div>
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                System-Wide Volume Breakdown
              </h3>
              <p class="text-xs text-[var(--color-text-muted)]">
                Aggregates published by GET /admin/stats (UC-23). Each bar is one figure as it stands today.
              </p>
            </div>
          </div>

          <div class="h-56 flex items-end justify-between gap-2 pt-6 px-2 border-b border-slate-200 dark:border-neutral-700">
            @for (bar of volumeBars; track bar.label) {
              <div class="flex-1 flex flex-col items-center justify-end h-full group relative">
                <!-- Tooltip -->
                <div class="opacity-0 group-hover:opacity-100 transition-opacity absolute -top-8 bg-slate-900 text-white text-[10px] py-1 px-1.5 rounded whitespace-nowrap z-20">
                  {{ bar.hint }}
                </div>

                <div
                  class="w-full max-w-[28px] bg-slate-800 dark:bg-slate-200 hover:bg-amber-500 dark:hover:bg-amber-400 rounded-t transition-colors cursor-pointer"
                  [style.height.%]="bar.heightPercent"
                ></div>
                <span class="text-[10px] text-slate-400 mt-2 font-mono">{{ bar.label }}</span>
              </div>
            }
          </div>

          <!-- Money figures are sums over the whole student base, so they sit beside the counts
               rather than on the same axis. -->
          <div class="grid grid-cols-3 gap-3 mt-4 text-xs">
            <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
              <span class="text-slate-400 block text-[10px] uppercase font-bold">Income Logged</span>
              <span class="font-bold text-emerald-600 dark:text-emerald-400">
                {{ money(totalIncomeLogged) }}
              </span>
            </div>
            <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
              <span class="text-slate-400 block text-[10px] uppercase font-bold">Expense Logged</span>
              <span class="font-bold text-rose-600 dark:text-rose-400">
                {{ money(totalExpenseLogged) }}
              </span>
            </div>
            <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
              <span class="text-slate-400 block text-[10px] uppercase font-bold">Disabled Accounts</span>
              <span class="font-bold text-slate-700 dark:text-neutral-200">{{ disabledStudents }}</span>
            </div>
          </div>
        </div>

        <!-- Right: Most Used Categories Campus-wide (4 cols) -->
        <div class="lg:col-span-4 bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <div class="mb-4 pb-2 border-b border-slate-100 dark:border-neutral-800">
            <h3 class="font-bold text-base text-slate-900 dark:text-white">
              Campus-Wide Category Shares
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">Distribution by student expenditure category</p>
          </div>

          <div class="space-y-3.5">
            @if (topCategories.length > 0) {
              @for (cat of topCategories; track cat.id) {
                <div>
                  <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                    <span class="truncate max-w-[180px]">{{ cat.name }}</span>
                    <span class="font-bold font-mono">
                      {{ money(cat.totalAmount) }} <span class="text-slate-400 font-normal">({{ cat.percentage }}%)</span>
                    </span>
                  </div>
                  <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                    <div
                      class="h-full rounded-full transition-all duration-500"
                      [style.width.%]="cat.percentage"
                      [style.background-color]="cat.color"
                    ></div>
                  </div>
                </div>
              }
            } @else {
              <div class="py-8 text-center text-xs text-slate-400 dark:text-neutral-500">
                No category expenditure recorded yet
              </div>
            }
          </div>
        </div>

      </div>

    </div>
  `
})
export class AdminDashboardComponent implements OnInit {
  private adminService = inject(AdminService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  kpis: AdminKpis | null = null;
  stats: AdminUsageStats | null = null;
  topCategories: Array<{ id: number; name: string; percentage: number; color: string; totalAmount: number }> = [];

  get volumeBars(): Array<{ label: string; value: number; hint: string; heightPercent: number }> {
    const s = this.stats;
    const items = [
      { label: 'TXN', value: Number(s?.totalTransactions ?? 0), hint: 'Transactions logged' },
      { label: 'BGT', value: Number(s?.totalBudgets ?? 0), hint: 'Budgets set' },
      { label: 'TIP', value: Number(s?.totalTipsGenerated ?? 0), hint: 'Saving tips generated' },
      { label: 'INS', value: Number(s?.totalInsightsGenerated ?? 0), hint: 'Monthly insights generated' }
    ];
    const max = Math.max(1, ...items.map(i => i.value));
    return items.map(i => ({ ...i, heightPercent: Math.round((i.value / max) * 100) }));
  }

  get totalIncomeLogged(): number {
    return Number(this.stats?.totalIncomeLogged ?? 0);
  }

  get totalExpenseLogged(): number {
    return Number(this.stats?.totalExpenseLogged ?? 0);
  }

  get disabledStudents(): number {
    return Number(this.stats?.disabledStudents ?? 0);
  }

  money(value: number): string {
    return `$${Number(value || 0).toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    })}`;
  }

  isLoading = false;

  ngOnInit(): void {
    this.isLoading = true;
    forkJoin({
      stats: this.adminService.getStats().pipe(catchError(() => of(null))),
      topCategories: this.adminService.getTopCategories().pipe(catchError(() => of([])))
    }).subscribe({
      next: ({ stats, topCategories }) => {
        this.isLoading = false;
        this.stats = stats;
        this.kpis = stats ? this.toKpis(stats) : null;
        this.processTopCategories(topCategories || []);
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.toast.error('Failed to load institutional overview data');
        this.cdr.markForCheck();
      }
    });
  }

  private toKpis(stats: AdminUsageStats): AdminKpis {
    const avg = stats.activeStudents > 0
      ? Math.round(stats.totalExpenseLogged / stats.activeStudents)
      : 0;
    return {
      totalUsers: stats.totalStudents,
      activeUsers: stats.activeUsers30d,
      totalTransactionsLogged: stats.totalTransactions,
      totalVolumeTracked: stats.totalExpenseLogged,
      avgStudentMonthlySpend: avg
    };
  }

  private processTopCategories(raw: AdminTopCategory[]): void {
    const active = raw.filter(c => c.txnCount > 0 || c.totalAmount > 0);
    const sorted = [...active].sort((a, b) => Number(b.totalAmount) - Number(a.totalAmount)).slice(0, 5);
    const totalSum = sorted.reduce((sum, c) => sum + Number(c.totalAmount || 0), 0);

    const colors = ['#F59E0B', '#8B5CF6', '#3B82F6', '#10B981', '#EC4899', '#6366F1'];

    this.topCategories = sorted.map((c, i) => ({
      id: c.categoryId,
      name: c.categoryName,
      totalAmount: Number(c.totalAmount || 0),
      percentage: totalSum > 0 ? Math.round((Number(c.totalAmount || 0) / totalSum) * 100) : 0,
      color: colors[i % colors.length]
    }));
  }
}
