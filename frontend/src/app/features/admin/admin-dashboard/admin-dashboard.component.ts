import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AdminService, AdminKpis } from '../../../core/services/admin.service';

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
          <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-emerald-50 dark:bg-emerald-950/50 text-emerald-700 dark:text-emerald-300 text-xs font-medium border border-emerald-200 dark:border-emerald-800">
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            Mock API Engine: Connected (Latency: 250ms)
          </span>
        </div>
      </div>

      <!-- 4 Calm Classic KPI Cards -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div class="bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-slate-500 uppercase tracking-wider block">Registered Students</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ kpis?.totalUsers || 582 }}
          </div>
          <div class="text-xs text-emerald-600 dark:text-emerald-400 font-medium mt-1">
            ↑ +14% from last semester
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-slate-500 uppercase tracking-wider block">Active Users (30d)</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ kpis?.activeUsers || 541 }}
          </div>
          <div class="text-xs text-slate-500 font-medium mt-1">
            93% engagement rate
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-slate-500 uppercase tracking-wider block">Total Volume Logged</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            \${{ (kpis?.totalVolumeTracked || 184500).toLocaleString() }}
          </div>
          <div class="text-xs text-slate-500 font-medium mt-1">
            Across 14,280 student transactions
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <span class="text-xs font-semibold text-slate-500 uppercase tracking-wider block">Avg. Monthly Student Outflow</span>
          <div class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            \${{ kpis?.avgStudentMonthlySpend || 425 }}
          </div>
          <div class="text-xs text-slate-500 font-medium mt-1">
            Within healthy student baseline
          </div>
        </div>
      </div>

      <!-- Charts & Campus Volume Breakdown -->
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">

        <!-- Left: Usage Activity Chart (8 cols) -->
        <div class="lg:col-span-8 bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <div class="flex items-center justify-between mb-4 pb-2 border-b border-slate-100 dark:border-neutral-800">
            <div>
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                Daily Campus Activity & Volume (Past 14 Days)
              </h3>
              <p class="text-xs text-slate-500">Transaction log velocity across university cohorts</p>
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
                  class="w-full max-w-[28px] bg-slate-800 dark:bg-slate-200 hover:bg-emerald-600 dark:hover:bg-emerald-400 rounded-t transition-colors"
                  [style.height.%]="bar.heightPercent"
                ></div>
                <span class="text-[10px] text-slate-400 mt-2 font-mono">{{ bar.label }}</span>
              </div>
            }
          </div>
        </div>

        <!-- Right: Most Used Categories Campus-wide (4 cols) -->
        <div class="lg:col-span-4 bg-white dark:bg-neutral-900 p-5 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs">
          <div class="mb-4 pb-2 border-b border-slate-100 dark:border-neutral-800">
            <h3 class="font-bold text-base text-slate-900 dark:text-white">
              Campus-Wide Category Shares
            </h3>
            <p class="text-xs text-slate-500">Aggregate student expenditure clusters</p>
          </div>

          <div class="space-y-3.5">
            <div>
              <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                <span>Food & Dining</span>
                <span class="font-bold font-mono">42%</span>
              </div>
              <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                <div class="h-full bg-amber-400 rounded-full" style="width: 42%"></div>
              </div>
            </div>

            <div>
              <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                <span>Housing & Dorms</span>
                <span class="font-bold font-mono">28%</span>
              </div>
              <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                <div class="h-full bg-purple-500 rounded-full" style="width: 28%"></div>
              </div>
            </div>

            <div>
              <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                <span>Books & Academics</span>
                <span class="font-bold font-mono">14%</span>
              </div>
              <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                <div class="h-full bg-blue-500 rounded-full" style="width: 14%"></div>
              </div>
            </div>

            <div>
              <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                <span>Transport & Mobility</span>
                <span class="font-bold font-mono">9%</span>
              </div>
              <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                <div class="h-full bg-emerald-500 rounded-full" style="width: 9%"></div>
              </div>
            </div>

            <div>
              <div class="flex justify-between text-xs font-medium mb-1 text-slate-700 dark:text-slate-300">
                <span>Other (Tech & Social)</span>
                <span class="font-bold font-mono">7%</span>
              </div>
              <div class="w-full h-2 bg-slate-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                <div class="h-full bg-rose-500 rounded-full" style="width: 7%"></div>
              </div>
            </div>
          </div>
        </div>

      </div>

    </div>
  `
})
export class AdminDashboardComponent implements OnInit {
  private adminService = inject(AdminService);

  kpis: AdminKpis | null = null;

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

  ngOnInit(): void {
    this.adminService.getKpiMetrics().subscribe(data => {
      this.kpis = data;
    });
  }
}
