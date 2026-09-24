import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { TransactionService, MonthlyTrendItem, CategoryBreakdownItem } from '../../core/services/transaction.service';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    BreadcrumbsComponent,
    IconComponent
  ],
  template: `
    <div class="space-y-6">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'Reports & Analytics' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Financial Analytics & Reports
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            Comprehensive audit of spending velocity, category distributions and multi-month trends.
          </p>
        </div>

        <!-- Export Report Button (Frontend client-side export via window.print) -->
        <div class="flex items-center gap-2 no-print">
          <button
            type="button"
            (click)="exportReport()"
            class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs sm:text-sm py-2 px-4 rounded-lg shadow-xs transition-colors flex items-center gap-2 cursor-pointer"
          >
            <app-icon name="download" [size]="15" strokeWidth="1.5"></app-icon>
            <span>Export Report (PDF / Print)</span>
          </button>
        </div>
      </div>

      <!-- Filter Bar -->
      <div class="card-brutal p-3.5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs no-print">
        <div class="flex flex-wrap items-center justify-between gap-4">
          <!-- Flow Type Switcher -->
          <div class="flex items-center gap-1 bg-neutral-100 dark:bg-neutral-800 p-1 rounded-lg border border-neutral-200 dark:border-neutral-700">
            <button
              type="button"
              (click)="selectedFlow = 'ALL'; applyFilters()"
              class="px-3 py-1 text-xs font-medium rounded-md transition-colors cursor-pointer"
              [class.bg-white]="selectedFlow === 'ALL'"
              [class.dark:bg-neutral-700]="selectedFlow === 'ALL'"
              [class.text-neutral-950]="selectedFlow === 'ALL'"
              [class.dark:text-white]="selectedFlow === 'ALL'"
              [class.shadow-xs]="selectedFlow === 'ALL'"
              [class.text-neutral-500]="selectedFlow !== 'ALL'"
            >
              All Cash Flows
            </button>
            <button
              type="button"
              (click)="selectedFlow = 'EXPENSE'; applyFilters()"
              class="px-3 py-1 text-xs font-medium rounded-md transition-colors cursor-pointer"
              [class.bg-rose-500]="selectedFlow === 'EXPENSE'"
              [class.text-white]="selectedFlow === 'EXPENSE'"
              [class.text-neutral-500]="selectedFlow !== 'EXPENSE'"
            >
              Expenses Only
            </button>
            <button
              type="button"
              (click)="selectedFlow = 'INCOME'; applyFilters()"
              class="px-3 py-1 text-xs font-medium rounded-md transition-colors cursor-pointer"
              [class.bg-emerald-500]="selectedFlow === 'INCOME'"
              [class.text-white]="selectedFlow === 'INCOME'"
              [class.text-neutral-500]="selectedFlow !== 'INCOME'"
            >
              Income Only
            </button>
          </div>

          <!-- Time Horizon -->
          <div class="flex items-center gap-2">
            <label class="text-xs font-medium text-neutral-500">Period:</label>
            <select [(ngModel)]="selectedPeriod" (ngModelChange)="applyFilters()" class="input-brutal text-xs py-1.5 px-3 w-44 rounded-lg">
              <option value="2026-09">Current Month (Sep 2026)</option>
              <option value="2026-08">August 2026</option>
              <option value="2026-07">July 2026</option>
              <option value="ALL_6M">Full 6-Month Horizon</option>
            </select>
          </div>
        </div>
      </div>

      <!-- 4 KPI Stat Tiles Row -->
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <span class="text-[11px] font-medium uppercase text-neutral-500 block">Total Expenses</span>
          <div class="text-xl sm:text-2xl font-semibold text-rose-600 dark:text-rose-400 mt-1">
            -\${{ kpiTotalExpense.toFixed(2) }}
          </div>
          <span class="text-[11px] text-neutral-400">Spent in selected frame</span>
        </div>

        <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <span class="text-[11px] font-medium uppercase text-neutral-500 block">Total Income</span>
          <div class="text-xl sm:text-2xl font-semibold text-emerald-600 dark:text-emerald-400 mt-1">
            +\${{ kpiTotalIncome.toFixed(2) }}
          </div>
          <span class="text-[11px] text-neutral-400">Allowance + earnings</span>
        </div>

        <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <span class="text-[11px] font-medium uppercase text-neutral-500 block">Net Balance</span>
          <div
            class="text-xl sm:text-2xl font-semibold mt-1"
            [class.text-emerald-600]="kpiNetBalance >= 0"
            [class.text-rose-600]="kpiNetBalance < 0"
          >
            {{ kpiNetBalance >= 0 ? '+' : '' }}\${{ kpiNetBalance.toFixed(2) }}
          </div>
          <span class="text-[11px] text-neutral-400">Net retained capital</span>
        </div>

        <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <span class="text-[11px] font-medium uppercase text-neutral-500 block">Savings Rate</span>
          <div class="text-xl sm:text-2xl font-semibold text-amber-600 dark:text-amber-400 mt-1">
            {{ kpiSavingsRate }}%
          </div>
          <span class="text-[11px] text-neutral-400">Target baseline: 20%</span>
        </div>
      </div>

      <!-- Charts Grid 1: 6-Month Trend & Category Donut Breakdown -->
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">

        <!-- Left: 6-Month Income vs Expense Trend Chart (8 cols) -->
        <div class="lg:col-span-7 card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex items-center justify-between mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800">
            <div>
              <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
                6-Month Trend (Income vs. Expense)
              </h3>
              <p class="text-xs text-neutral-500">Comparative multi-month student cash flow</p>
            </div>
            <div class="flex items-center gap-3 text-xs font-medium">
              <span class="flex items-center gap-1.5">
                <span class="w-2.5 h-2.5 rounded-full bg-emerald-500"></span> Income
              </span>
              <span class="flex items-center gap-1.5">
                <span class="w-2.5 h-2.5 rounded-full bg-rose-500"></span> Expense
              </span>
            </div>
          </div>

          <!-- Custom SVG / HTML Bar Chart -->
          <div class="h-64 flex flex-col justify-end pt-4">
            <div class="h-full flex items-end justify-between gap-2 sm:gap-4 px-2 border-b border-neutral-200 dark:border-neutral-800 pb-1">
              @for (item of sixMonthTrend; track item.periodCode) {
                <div class="flex-1 flex flex-col items-center h-full justify-end group relative">
                  <!-- Tooltip Hover Popover -->
                  <div class="opacity-0 group-hover:opacity-100 transition-opacity absolute -top-10 z-20 bg-neutral-900 dark:bg-neutral-100 text-white dark:text-neutral-900 text-[10px] font-mono py-1 px-2 rounded-md pointer-events-none whitespace-nowrap shadow-md">
                    In: \${{ item.income }} | Out: \${{ item.expense }}
                  </div>

                  <!-- Side-by-side Bars -->
                  <div class="w-full flex items-end justify-center gap-1 sm:gap-1.5 h-full">
                    <!-- Income Bar -->
                    <div
                      class="w-1/2 max-w-[20px] bg-emerald-500/90 hover:bg-emerald-500 rounded-t-sm transition-all duration-300"
                      [style.height.%]="(item.income / 700) * 100"
                    ></div>
                    <!-- Expense Bar -->
                    <div
                      class="w-1/2 max-w-[20px] bg-rose-500/90 hover:bg-rose-500 rounded-t-sm transition-all duration-300"
                      [style.height.%]="(item.expense / 700) * 100"
                    ></div>
                  </div>

                  <!-- Month Label -->
                  <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 mt-2">
                    {{ item.monthLabel }}
                  </span>
                </div>
              }
            </div>
          </div>
        </div>

        <!-- Right: Category Donut / Breakdown Chart (5 cols) -->
        <div class="lg:col-span-5 card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800">
            <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
              Spending by Category
            </h3>
            <p class="text-xs text-neutral-500">Distribution breakdown for current month</p>
          </div>

          <div class="space-y-3">
            @for (cat of categoryBreakdown; track cat.categoryId) {
              <div>
                <div class="flex items-center justify-between text-xs font-medium mb-1.5">
                  <div class="flex items-center gap-2">
                    <span class="w-2.5 h-2.5 rounded-full" [style.background-color]="cat.categoryColor"></span>
                    <span class="text-neutral-800 dark:text-neutral-200">{{ cat.categoryName }}</span>
                  </div>
                  <div class="font-mono">
                    <span class="font-medium">\${{ cat.total.toFixed(2) }}</span>
                    <span class="text-neutral-400 ml-1">({{ cat.percentage }}%)</span>
                  </div>
                </div>
                <!-- Progress bar representation -->
                <div class="w-full h-1.5 bg-neutral-100 dark:bg-neutral-800 rounded-full overflow-hidden">
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    [style.width.%]="cat.percentage"
                    [style.background-color]="cat.categoryColor"
                  ></div>
                </div>
              </div>
            }
          </div>
        </div>

      </div>

      <!-- Charts Grid 2: Daily Spending Rhythm Scatter/Bar -->
      <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800">
          <div>
            <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
              Daily Spending Rhythm (September 1 - 24, 2026)
            </h3>
            <p class="text-xs text-neutral-500">Day-by-day expenditure spike visualization</p>
          </div>

          <div class="text-xs font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2.5 py-1 rounded-full">
            Peak: Sep 05 ($280 Dorm Share)
          </div>
        </div>

        <!-- 24-day Rhythm Micro Bar Grid -->
        <div class="h-36 flex items-end gap-1 sm:gap-2 px-1 pt-6 border-b border-neutral-200 dark:border-neutral-800">
          @for (d of dailySpend; track d.day) {
            <div class="flex-1 flex flex-col items-center h-full justify-end group relative cursor-pointer">
              <!-- Tooltip on hover -->
              <div class="opacity-0 group-hover:opacity-100 transition-opacity absolute -top-8 bg-neutral-900 text-white text-[9px] font-mono py-0.5 px-1.5 rounded pointer-events-none whitespace-nowrap z-20 shadow-xs">
                {{ d.day }}: \${{ d.amount }}
              </div>

              <!-- Bar -->
              <div
                class="w-full rounded-t-sm transition-all duration-300"
                [style.height.%]="d.amount > 0 ? Math.min(100, Math.max(12, (d.amount / 300) * 100)) : 4"
                [class.bg-amber-500]="d.amount > 100"
                [class.bg-amber-400/70]="d.amount > 0 && d.amount <= 100"
                [class.bg-neutral-200]="d.amount === 0"
                [class.dark:bg-neutral-800]="d.amount === 0"
              ></div>
            </div>
          }
        </div>
        <div class="flex justify-between text-[10px] font-mono text-neutral-400 mt-2 px-1">
          <span>Sep 01</span>
          <span>Sep 08</span>
          <span>Sep 15</span>
          <span>Sep 24 (Today)</span>
        </div>
      </div>

    </div>
  `
})
export class ReportsComponent implements OnInit {
  private txService = inject(TransactionService);

  Math = Math;

  selectedFlow: 'ALL' | 'EXPENSE' | 'INCOME' = 'ALL';
  selectedPeriod = '2026-09';

  kpiTotalExpense = 0;
  kpiTotalIncome = 0;
  kpiNetBalance = 0;
  kpiSavingsRate = 0;

  categoryBreakdown: CategoryBreakdownItem[] = [];
  sixMonthTrend: MonthlyTrendItem[] = [];
  dailySpend: Array<{ day: string; amount: number }> = [];

  ngOnInit(): void {
    this.applyFilters();
  }

  applyFilters(): void {
    // 1. Balances & KPIs
    const bal = this.txService.getMonthlyBalance(this.selectedPeriod === 'ALL_6M' ? '2026' : this.selectedPeriod);
    this.kpiTotalExpense = bal.expense;
    this.kpiTotalIncome = bal.income;
    this.kpiNetBalance = bal.net;
    this.kpiSavingsRate = bal.savingsRate;

    // 2. Category breakdown
    this.txService.getCategoryBreakdown(this.selectedPeriod === 'ALL_6M' ? undefined : this.selectedPeriod)
      .subscribe(res => {
        this.categoryBreakdown = res;
      });

    // 3. 6-Month Trend
    this.txService.get6MonthTrend().subscribe(res => {
      this.sixMonthTrend = res;
    });

    // 4. Daily Spend
    this.txService.getDailySpending('2026-09').subscribe(res => {
      this.dailySpend = res;
    });
  }

  exportReport(): void {
    window.print();
  }
}
