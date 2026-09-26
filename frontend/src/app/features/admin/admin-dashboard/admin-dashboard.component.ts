import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of, catchError } from 'rxjs';
import { AdminService, AdminKpis } from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { AdminTopCategory } from '../../../core/models/admin.model';

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
            \${{ kpis ? kpis.totalVolumeTracked.toLocaleString() : (isLoading ? '...' : '0.00') }}
          </div>
          <div class="text-xs text-[var(--color-text-muted)] font-medium mt-1">
            Total expenses recorded
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-[var(--color-text-muted)] uppercase tracking-wider block">Avg. Monthly Student Outflow</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            \${{ kpis ? kpis.avgStudentMonthlySpend.toLocaleString() : (isLoading ? '...' : '0.00') }}
          </div>
          <div class="text-xs text-[var(--color-text-muted)] font-medium mt-1">
            Average per active student
          </div>
        </div>
      </div>

      <!-- Charts & Campus Volume Breakdown -->
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">

        <!-- Left: Usage Activity Chart (8 cols) -->
        <div class="lg:col-span-8 bg-white dark:bg-neutral-900 p-5 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xs">
          <div class="flex items-center justify-between mb-4 pb-2 border-b border-slate-100 dark:border-neutral-800">
            <div>
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                Daily Campus Activity & Volume (Past 14 Days)
              </h3>
              <p class="text-xs text-[var(--color-text-muted)]">Daily transaction volume across student cohorts</p>
            </div>
          </div>

          <div class="h-56 flex items-end justify-between gap-2 pt-6 px-2 border-b border-slate-200 dark:border-neutral-700">
            @for (bar of activityBars; track bar.day) {
              <div class="flex-1 flex flex-col items-center justify-end h-full group relative">
                <!-- Tooltip -->
                <div class="opacity-0 group-hover:opacity-100 transition-opacity absolute -top-8 bg-slate-900 text-white text-[10px] py-1 px-1.5 rounded whitespace-nowrap z-20">
                  {{ bar.day }}: {{ bar.count }} txs
                </div>

                <div
                  class="w-full max-w-[28px] bg-slate-800 dark:bg-slate-200 hover:bg-amber-500 dark:hover:bg-amber-400 rounded-t transition-colors cursor-pointer"
                  [style.height.%]="bar.heightPercent"
                ></div>
                <span class="text-[10px] text-slate-400 mt-2 font-mono">{{ bar.label }}</span>
              </div>
            }
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
                      \${{ cat.totalAmount.toLocaleString() }} <span class="text-slate-400 font-normal">({{ cat.percentage }}%)</span>
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
  topCategories: Array<{ id: number; name: string; percentage: number; color: string; totalAmount: number }> = [];

  activityBars = [
    { day: 'Sep 11', label: '11', count: 180, heightPercent: 45 },
    { day: 'Sep 12', label: '12', count: 210, heightPercent: 52 },
    { day: 'Sep 13', label: '13', count: 160, heightPercent: 40 },
    { day: 'Sep 14', label: '14', count: 240, heightPercent: 60 },
    { day: 'Sep 15', label: '15', count: 320, heightPercent: 80 },
    { day: 'Sep 16', label: '16', count: 290, heightPercent: 72 },
    { day: 'Sep 17', label: '17', count: 270, heightPercent: 68 },
    { day: 'Sep 18', label: '18', count: 350, heightPercent: 88 },
    { day: 'Sep 19', label: '19', count: 310, heightPercent: 78 },
    { day: 'Sep 20', label: '20', count: 220, heightPercent: 55 },
    { day: 'Sep 21', label: '21', count: 280, heightPercent: 70 },
    { day: 'Sep 22', label: '22', count: 340, heightPercent: 85 },
    { day: 'Sep 23', label: '23', count: 390, heightPercent: 95 },
    { day: 'Sep 24', label: '24', count: 400, heightPercent: 100 }
  ];

  isLoading = false;

  ngOnInit(): void {
    this.isLoading = true;
    forkJoin({
      kpis: this.adminService.getKpiMetrics().pipe(catchError(() => of(null))),
      topCategories: this.adminService.getTopCategories().pipe(catchError(() => of([])))
    }).subscribe({
      next: ({ kpis, topCategories }) => {
        this.isLoading = false;
        this.kpis = kpis;
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
