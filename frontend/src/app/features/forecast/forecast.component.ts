import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { LoadingSkeletonComponent } from '../../shared/components/loading-skeleton/loading-skeleton.component';
import { ForecastService } from '../../core/services/forecast.service';
import { ToastService } from '../../core/services/toast.service';
import { ForecastResponse } from '../../core/models/forecast.model';

@Component({
  selector: 'app-forecast',
  standalone: true,
  imports: [CommonModule, IconComponent, LoadingSkeletonComponent],
  template: `
    <div class="max-w-4xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div>
        <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
          Next Month So Far
        </h2>
        <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
          What {{ nextMonthLabel }} could look like, averaged from your recent months.
        </p>
      </div>

      @if (isLoading) {
        <app-loading-skeleton type="card"></app-loading-skeleton>
        <app-loading-skeleton type="card"></app-loading-skeleton>
      } @else if (!data) {
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <p class="text-xs text-neutral-500">Your forecast is not available right now.</p>
        </div>
      } @else {
        <!-- The projection, or the reason there is none -->
        @if (data.projected) {
          <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
            <div class="flex flex-wrap items-center justify-between gap-2 mb-4">
              <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50">
                Projected for {{ monthLabel(data.nextMonth) }}
              </h3>
              <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 border border-neutral-200 dark:border-neutral-700">
                Average of {{ data.basedOnMonths }} month{{ data.basedOnMonths === 1 ? '' : 's' }}
              </span>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
                <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Expected in</span>
                <span class="font-semibold text-lg text-emerald-600 dark:text-emerald-400 tabular-nums">
                  \${{ data.projected.income.toFixed(2) }}
                </span>
              </div>
              <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
                <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Expected out</span>
                <span class="font-semibold text-lg text-rose-600 dark:text-rose-400 tabular-nums">
                  \${{ data.projected.expense.toFixed(2) }}
                </span>
              </div>
              <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
                <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Expected to keep</span>
                <span
                  class="font-semibold text-lg tabular-nums"
                  [class.text-emerald-600]="data.projected.savings >= 0"
                  [class.dark:text-emerald-400]="data.projected.savings >= 0"
                  [class.text-rose-600]="data.projected.savings < 0"
                  [class.dark:text-rose-400]="data.projected.savings < 0"
                >
                  {{ data.projected.savings < 0 ? '-' : '' }}\${{ abs(data.projected.savings).toFixed(2) }}
                </span>
              </div>
            </div>

            @if (data.projected.savings < 0) {
              <p class="text-[11px] text-rose-600 dark:text-rose-400 mt-3 leading-snug">
                Averaged over these months, your spending is on course to outrun what comes in.
              </p>
            }

            <p class="text-[10px] text-neutral-400 dark:text-neutral-500 mt-3 leading-snug">
              A straight average of your own complete months — not a promise, and not advice about
              what to do.
            </p>
          </div>

          <!-- The month in progress -->
          @if (data.currentMonthTotals) {
            <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
              <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">
                {{ monthLabel(data.currentMonth) }} so far
              </h3>
              <p class="text-xs text-neutral-500 mb-4">
                Recorded in the month in progress, which the projection does not include.
              </p>
              <div class="grid grid-cols-3 gap-3">
                <div>
                  <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">In</span>
                  <span class="font-semibold text-base text-emerald-600 dark:text-emerald-400 tabular-nums">
                    \${{ data.currentMonthTotals.income.toFixed(2) }}
                  </span>
                </div>
                <div>
                  <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Out</span>
                  <span class="font-semibold text-base text-rose-600 dark:text-rose-400 tabular-nums">
                    \${{ data.currentMonthTotals.expense.toFixed(2) }}
                  </span>
                </div>
                <div>
                  <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Net</span>
                  <span
                    class="font-semibold text-base tabular-nums"
                    [class.text-emerald-600]="data.currentMonthTotals.net >= 0"
                    [class.dark:text-emerald-400]="data.currentMonthTotals.net >= 0"
                    [class.text-rose-600]="data.currentMonthTotals.net < 0"
                    [class.dark:text-rose-400]="data.currentMonthTotals.net < 0"
                  >
                    {{ data.currentMonthTotals.net < 0 ? '-' : '' }}\${{ abs(data.currentMonthTotals.net).toFixed(2) }}
                  </span>
                </div>
              </div>
            </div>
          }
        } @else {
          <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
            <div class="w-12 h-12 rounded-xl bg-neutral-100 dark:bg-neutral-800 text-neutral-500 border border-neutral-200 dark:border-neutral-700 flex items-center justify-center mx-auto mb-3">
              <app-icon name="trending-up" [size]="22" strokeWidth="1.75"></app-icon>
            </div>
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">
              No projection yet
            </h3>
            <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
              A projection averages your complete months, and there is not one behind you yet. Once
              a month is finished and recorded, it will appear here.
            </p>
          </div>
        }

        <!-- The evidence the projection rests on -->
        @if (data.recentMonths.length > 0) {
          <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">
              What it is based on
            </h3>
            <p class="text-xs text-neutral-500 mb-4">
              Your complete months, oldest first. The projection is the average of these.
            </p>

            <div class="overflow-x-auto">
              <table class="w-full text-left text-xs border-collapse">
                <thead>
                  <tr class="border-b border-neutral-200 dark:border-neutral-800 text-neutral-500 uppercase text-[10px] font-medium">
                    <th class="py-2 pl-3 pr-2">Month</th>
                    <th class="py-2 px-3 text-right">In</th>
                    <th class="py-2 px-3 text-right">Out</th>
                    <th class="py-2 pl-3 pr-2 text-right">Net</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-neutral-100 dark:divide-neutral-800">
                  @for (m of data.recentMonths; track m.periodMonth) {
                    <tr>
                      <td class="py-2 pl-3 pr-2 font-medium text-neutral-900 dark:text-neutral-100 whitespace-nowrap">
                        {{ monthLabel(m.periodMonth) }}
                      </td>
                      <td class="py-2 px-3 text-right font-mono text-emerald-600 dark:text-emerald-400">
                        {{ m.income.toFixed(2) }}
                      </td>
                      <td class="py-2 px-3 text-right font-mono text-rose-600 dark:text-rose-400">
                        {{ m.expense.toFixed(2) }}
                      </td>
                      <td
                        class="py-2 pl-3 pr-2 text-right font-mono font-semibold"
                        [class.text-emerald-600]="m.net >= 0"
                        [class.dark:text-emerald-400]="m.net >= 0"
                        [class.text-rose-600]="m.net < 0"
                        [class.dark:text-rose-400]="m.net < 0"
                      >
                        {{ m.net < 0 ? '-' : '' }}{{ abs(m.net).toFixed(2) }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      }
    </div>
  `
})
export class ForecastComponent implements OnInit {
  private forecastService = inject(ForecastService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  data: ForecastResponse | null = null;
  isLoading = true;

  ngOnInit(): void {
    this.forecastService.getForecast().subscribe({
      next: res => {
        this.data = res;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: err => {
        this.isLoading = false;
        this.toast.error(err.error?.message || 'Could not load your forecast.');
        this.cdr.markForCheck();
      }
    });
  }

  abs(value: number): number {
    return Math.abs(value);
  }

  monthLabel(month: string): string {
    const [y, m] = (month || '').split('-');
    const names = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    const name = names[parseInt(m, 10) - 1];
    return name ? `${name} ${y}` : month;
  }

  get nextMonthLabel(): string {
    return this.data ? this.monthLabel(this.data.nextMonth) : 'next month';
  }
}
