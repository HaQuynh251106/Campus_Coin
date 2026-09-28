import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { InsightService } from '../../core/services/insight.service';
import { ToastService } from '../../core/services/toast.service';
import { MonthlyInsight } from '../../core/models/insight.model';

@Component({
  selector: 'app-insights',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  template: `
    <div class="max-w-4xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Monthly Insights
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Your month's figures, and what stood out in them.
          </p>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <select
            [ngModel]="selectedMonth"
            (ngModelChange)="onMonthChange($event)"
            [ngModelOptions]="{ standalone: true }"
            class="input-brutal !w-auto text-xs"
            aria-label="Choose a month"
          >
            @if (selectedMonth === '') {
              <option value="">This month</option>
            }
            @for (m of months; track m) {
              <option [value]="m">{{ monthLabel(m) }}</option>
            }
          </select>

          <button
            type="button"
            (click)="generate()"
            [disabled]="isGenerating"
            class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
          >
            <app-icon name="sparkles" [size]="15" strokeWidth="1.75"></app-icon>
            <span>{{ isGenerating ? 'Working…' : 'Generate' }}</span>
          </button>
        </div>
      </div>

      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Loading your insight…
        </div>
      } @else if (!insight) {
        <!--
          No insight for the month yet. That is an ordinary answer, not a failure, and the generator
          is the student's to run — so nothing is called automatically from this branch.
        -->
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-amber-500/10 text-amber-600 dark:text-amber-400 border border-amber-500/25 flex items-center justify-center mx-auto mb-3">
            <app-icon name="insights" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">
            No insight for {{ periodLabel() }} yet
          </h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
            An insight is a snapshot of a month's totals. Press Generate to build one from the entries
            already recorded for {{ periodLabel() }}.
          </p>
        </div>
      } @else {
        <!-- The month's figures -->
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Received</span>
            <span class="font-semibold text-xl text-emerald-600 dark:text-emerald-400 tabular-nums">
              \${{ insight.totalIncome.toFixed(2) }}
            </span>
          </div>
          <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Spent</span>
            <span class="font-semibold text-xl text-rose-600 dark:text-rose-400 tabular-nums">
              \${{ insight.totalExpense.toFixed(2) }}
            </span>
          </div>
          <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Net</span>
            <span
              class="font-semibold text-xl tabular-nums"
              [class.text-emerald-600]="insight.netAmount >= 0"
              [class.dark:text-emerald-400]="insight.netAmount >= 0"
              [class.text-rose-600]="insight.netAmount < 0"
              [class.dark:text-rose-400]="insight.netAmount < 0"
            >
              {{ insight.netAmount < 0 ? '-' : '' }}\${{ abs(insight.netAmount).toFixed(2) }}
            </span>
            <span class="block text-[10px] text-neutral-500 mt-0.5">
              {{ insight.netAmount >= 0 ? 'kept this month' : 'spent beyond what came in' }}
            </span>
          </div>
        </div>

        <!-- The prose, labelled with its source -->
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex flex-wrap items-center justify-between gap-2 mb-3">
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50">
              {{ monthLabel(insight.periodMonth) }}
            </h3>
            <span
              class="px-2 py-0.5 rounded-full text-[10px] font-semibold border flex items-center gap-1"
              [ngClass]="sourceClasses()"
            >
              <app-icon [name]="insight.generatedBy === 'AI' ? 'sparkles' : 'code'" [size]="11" strokeWidth="2"></app-icon>
              <span>{{ sourceLabel() }}</span>
            </span>
          </div>

          <p class="text-xs sm:text-sm text-neutral-700 dark:text-neutral-300 leading-relaxed">
            {{ insight.summary }}
          </p>

          <div class="mt-4 pt-3 border-t border-neutral-100 dark:border-neutral-800">
            <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500 mb-1">
              Worth considering
            </span>
            <p class="text-xs sm:text-sm text-neutral-700 dark:text-neutral-300 leading-relaxed">
              {{ insight.advice }}
            </p>
          </div>

          <!--
            The label above is the honest one, and this line explains it. A rule-based summary is
            composed from the student's own figures, and calling that "AI" would be a claim the
            response does not make.
          -->
          <p class="text-[10px] text-neutral-400 dark:text-neutral-500 mt-3 leading-snug">
            @if (insight.generatedBy === 'AI') {
              Written by an AI model{{ insight.model ? ' (' + insight.model + ')' : '' }} from your own
              monthly totals. A suggestion to review, not financial advice.
            } @else {
              Composed from your own monthly totals by a fixed rule — no AI model was involved. A
              suggestion to review, not financial advice.
            }
            Generated {{ insight.generatedAt | date: 'd MMM y, HH:mm' }}.
          </p>
        </div>

        <!-- Categories that ran above the student's own usual level -->
        @if (insight.flaggedCategories.length > 0) {
          <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">
              Above your usual level
            </h3>
            <p class="text-xs text-neutral-500 mb-4">
              Compared with your own average in each category over the preceding months.
            </p>
            <ul class="space-y-2">
              @for (cat of insight.flaggedCategories; track cat.categoryId) {
                <li class="flex flex-wrap items-center justify-between gap-2 p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
                  <div class="min-w-0">
                    <span class="font-medium text-xs text-neutral-900 dark:text-neutral-100 block">
                      {{ cat.categoryName }}
                    </span>
                    <span class="text-[11px] text-neutral-500">
                      \${{ cat.currentTotal.toFixed(2) }} this month · you usually spend
                      \${{ cat.baselineAvg.toFixed(2) }}
                    </span>
                  </div>
                  @if (cat.pctChange !== null) {
                    <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tabular-nums shrink-0">
                      +{{ cat.pctChange.toFixed(0) }}%
                    </span>
                  }
                </li>
              }
            </ul>
          </div>
        } @else {
          <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl text-center">
            <p class="text-xs text-neutral-500">
              No category ran noticeably above your usual level this month.
            </p>
          </div>
        }
      }
    </div>
  `
})
export class InsightsComponent implements OnInit {
  private insightService = inject(InsightService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  insight: MonthlyInsight | null = null;
  months: string[] = [];
  selectedMonth = '';
  isLoading = true;
  isGenerating = false;

  ngOnInit(): void {
    this.loadMonths();
    this.load();
  }

  private loadMonths(): void {
    this.insightService.getInsightMonths().subscribe({
      next: res => {
        this.months = res.months || [];
        this.cdr.markForCheck();
      },
      error: () => {}
    });
  }

  load(): void {
    this.isLoading = true;
    this.insightService.getInsight(this.selectedMonth || undefined).subscribe({
      next: res => {
        this.insight = res;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: err => {
        this.isLoading = false;

        if (err.status === 404) {
          this.insight = null;
        } else if (err.status === 400) {
          this.insight = null;
          this.toast.warning(err.error?.message || 'That month is not valid. Showing the current month.');
          this.selectedMonth = '';
        } else {
          this.toast.error('Could not load your insight.');
        }
        this.cdr.markForCheck();
      }
    });
  }

  onMonthChange(month: string): void {
    this.selectedMonth = month;
    this.load();
  }

  generate(): void {
    this.isGenerating = true;
    this.insightService.generateInsight(this.selectedMonth || undefined).subscribe({
      next: res => {
        this.isGenerating = false;

        this.insight = res;
        this.selectedMonth = res.periodMonth;
        this.toast.success(`Insight ready for ${this.monthLabel(res.periodMonth)}.`);
        this.loadMonths();
        this.cdr.markForCheck();
      },
      error: err => {
        this.isGenerating = false;
        this.toast.error(err.error?.message || 'Could not generate an insight for that month.');
        this.cdr.markForCheck();
      }
    });
  }

  abs(value: number): number {
    return Math.abs(value);
  }

  sourceLabel(): string {
    return this.insight?.generatedBy === 'AI' ? 'AI-written' : 'Rule-based';
  }

  sourceClasses(): string {
    return this.insight?.generatedBy === 'AI'
      ? 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-900'
      : 'bg-neutral-100 text-neutral-600 border-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:border-neutral-700';
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

  periodLabel(): string {
    return this.selectedMonth ? this.monthLabel(this.selectedMonth) : 'this month';
  }
}
