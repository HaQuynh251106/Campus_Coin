import { Component, OnInit, inject, signal, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of, catchError } from 'rxjs';
import { TransactionService, MonthlyBalance } from '../../core/services/transaction.service';
import { BudgetService } from '../../core/services/budget.service';
import { DashboardService } from '../../core/services/dashboard.service';
import { AuthService } from '../../core/services/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { Transaction } from '../../core/models/transaction.model';
import { Budget } from '../../core/models/budget.model';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { ProgressRingComponent } from '../../shared/components/progress-ring/progress-ring.component';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { LoadingSkeletonComponent } from '../../shared/components/loading-skeleton/loading-skeleton.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryIconComponent } from '../../shared/components/category-icon/category-icon.component';

interface GroupedDayTransactions {
  label: string;
  date: string;
  transactions: Transaction[];
}

@Component({
  selector: 'app-home-feed',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    ProgressRingComponent,
    EmptyStateComponent,
    LoadingSkeletonComponent,
    IconComponent,
    CategoryIconComponent
  ],
  template: `
    <div class="space-y-6">

      <!-- 1. Top Balance & Allowance Hero Banner (Refined Modern SaaS with Subtle Gold Accent) -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-amber-500/25 dark:border-amber-400/20 rounded-xl shadow-xs relative overflow-hidden border-t-2 border-t-amber-500">
        <!-- Subtle Gold Ambient Glow -->
        <div class="absolute -right-16 -top-16 w-64 h-64 bg-amber-500/5 rounded-full blur-3xl pointer-events-none"></div>

        <div class="relative z-10">
          <div class="flex flex-wrap items-center justify-between gap-4 mb-5">
            <div>
              <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tracking-wider uppercase block">
                September 2026
              </span>
              <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight text-neutral-950 dark:text-white mt-1.5">
                \${{ balance.net.toFixed(2) }}
              </h2>
              <p class="text-xs font-medium text-neutral-500 dark:text-neutral-400 mt-0.5">
                Net balance this month
              </p>
            </div>

            <!-- Quick Action Buttons -->
            <div class="flex items-center gap-2">
              <a
                routerLink="/app/quick-add"
                class="bg-amber-500 hover:bg-amber-600 active:scale-[0.98] text-neutral-950 font-medium py-1.5 px-3.5 rounded-lg text-xs sm:text-sm shadow-xs transition-all flex items-center gap-1.5"
              >
                <app-icon name="plus" [size]="15" strokeWidth="2"></app-icon>
                <span>Log Spending</span>
              </a>
              <a
                routerLink="/app/reports"
                class="bg-neutral-100 hover:bg-neutral-200/70 dark:bg-neutral-800 dark:hover:bg-neutral-750 text-neutral-700 dark:text-neutral-300 font-medium py-1.5 px-3.5 rounded-lg text-xs sm:text-sm transition-colors border border-neutral-200/80 dark:border-neutral-700 flex items-center gap-1.5"
              >
                <app-icon name="bar-chart-2" [size]="15" strokeWidth="1.5"></app-icon>
                <span>Analytics</span>
              </a>
            </div>
          </div>

          <!-- 3-Metric Mini Strip -->
          <div class="grid grid-cols-3 gap-3 pt-4 border-t border-amber-500/15 dark:border-amber-400/10">
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-amber-500/20 dark:border-amber-400/15">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Total Income</span>
              <span class="font-semibold text-sm sm:text-base text-emerald-600 dark:text-emerald-400">
                +\${{ balance.income.toFixed(2) }}
              </span>
            </div>
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-amber-500/20 dark:border-amber-400/15">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Total Expenses</span>
              <span class="font-semibold text-sm sm:text-base text-rose-600 dark:text-rose-400">
                -\${{ balance.expense.toFixed(2) }}
              </span>
            </div>
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-amber-500/20 dark:border-amber-400/15">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Savings Rate</span>
              <span class="font-semibold text-sm sm:text-base text-amber-600 dark:text-amber-400">
                {{ balance.savingsRate }}%
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. Category Budget Progress Cards Strip -->
      <div>
        <div class="flex items-center justify-between mb-3 px-0.5">
          <div>
            <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-100 tracking-tight">
              Monthly Budget Status
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">Tracked against monthly limits</p>
          </div>
          <a routerLink="/app/budgets" class="text-xs font-medium text-amber-600 dark:text-amber-400 hover:underline">
            Manage Budgets →
          </a>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
          @for (bgt of topBudgets; track bgt.id) {
            <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-amber-500/25 dark:border-amber-400/20 hover:border-amber-500/40 dark:hover:border-amber-400/35 rounded-xl shadow-xs transition-colors">
              <div class="flex items-center justify-between mb-2.5">
                <div class="flex items-center gap-2 min-w-0">
                  <app-category-icon
                    [name]="bgt.categoryName"
                    [icon]="bgt.categoryIcon"
                    [color]="bgt.categoryColor"
                    size="sm"
                  ></app-category-icon>
                  <span class="font-medium text-xs text-neutral-900 dark:text-neutral-100 truncate max-w-[120px]">{{ bgt.categoryName }}</span>
                </div>
                <!-- Status Badge (Restrained text label) -->
                @if (bgt.alertStatus === 'DANGER') {
                  <span class="text-[11px] font-semibold text-rose-600 dark:text-rose-400">Over</span>
                } @else if (bgt.alertStatus === 'WARNING') {
                  <span class="text-[11px] font-semibold text-amber-600 dark:text-amber-400">80%+</span>
                } @else {
                  <span class="text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">Safe</span>
                }
              </div>

              <!-- Spending vs Limit -->
              <div class="flex items-baseline justify-between text-xs mb-2">
                <span class="font-semibold text-sm text-neutral-900 dark:text-white">\${{ bgt.spent }}</span>
                <span class="text-[var(--color-text-muted)]">of \${{ bgt.monthlyLimit }}</span>
              </div>

              <app-progress-ring
                [percentage]="getPercentage(bgt.spent, bgt.monthlyLimit)"
                mode="bar"
                [showLabel]="false"
              ></app-progress-ring>
            </div>
          }
        </div>
      </div>

      <!-- 4. Recent Transactions Stream -->
      <div>
        <div class="flex items-center justify-between mb-3 px-0.5">
          <div>
            <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-100 tracking-tight">
              Recent Transactions
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">Recent spending activity</p>
          </div>
          <a routerLink="/app/quick-add" class="text-xs font-medium text-amber-600 dark:text-amber-400 hover:underline">
            + Add New
          </a>
        </div>

        @if (isLoadingTxs) {
          <div class="space-y-2">
            <app-loading-skeleton type="row"></app-loading-skeleton>
            <app-loading-skeleton type="row"></app-loading-skeleton>
            <app-loading-skeleton type="row"></app-loading-skeleton>
          </div>
        } @else if (groupedTransactions.length === 0) {
          <!-- Empty State with Real Freely-Licensed Sourced Image -->
          <app-empty-state
            (actionClicked)="goToQuickAdd()"
          ></app-empty-state>
        } @else {
          <div class="space-y-4">
            @for (group of groupedTransactions; track group.date) {
              <div>
                <!-- Day Header -->
                <div class="flex items-center gap-2 mb-2">
                  <span class="text-[11px] font-medium text-[var(--color-text-muted)] px-2 py-0.5 bg-neutral-100 dark:bg-neutral-800 rounded-md">
                    {{ group.label }}
                  </span>
                  <div class="h-px flex-1 bg-neutral-200 dark:border-neutral-800"></div>
                </div>

                <!-- Transaction Rows -->
                <div class="space-y-1.5">
                  @for (tx of group.transactions; track tx.id) {
                    <div class="card-brutal p-3 sm:p-3.5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs flex items-center justify-between gap-3 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
                      <div class="flex items-center gap-3 min-w-0">
                        <!-- Category Icon Avatar (Left) -->
                        <app-category-icon
                          [name]="tx.categoryName"
                          [icon]="tx.categoryIcon"
                          [color]="tx.categoryColor"
                          size="md"
                        ></app-category-icon>

                        <!-- Title + Plain Category Name Underneath -->
                        <div class="min-w-0">
                          <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-100 truncate">
                            {{ tx.description }}
                          </h4>
                          <div class="flex items-center gap-2 text-xs text-[var(--color-text-muted)] mt-0.5">
                            <span>{{ tx.categoryName }}</span>
                            @if (tx.recurringFrequency !== 'NONE') {
                              <span>&bull;</span>
                              <span class="text-[11px]">
                                🔁 {{ tx.recurringFrequency | lowercase }}
                              </span>
                            }
                          </div>
                        </div>
                      </div>

                      <!-- Amount & Quick Delete -->
                      <div class="flex items-center gap-3 shrink-0">
                        <span
                          class="font-semibold text-sm sm:text-base font-mono"
                          [class.text-emerald-600]="tx.type === 'INCOME'"
                          [class.dark:text-emerald-400]="tx.type === 'INCOME'"
                          [class.text-rose-600]="tx.type === 'EXPENSE'"
                          [class.dark:text-rose-400]="tx.type === 'EXPENSE'"
                        >
                          {{ tx.type === 'INCOME' ? '+' : '-' }}\${{ tx.amount.toFixed(2) }}
                        </span>

                        <button
                          type="button"
                          (click)="deleteTx(tx.id)"
                          title="Delete transaction"
                          class="p-1.5 text-neutral-400 hover:text-rose-600 hover:bg-rose-50 dark:hover:bg-neutral-800 rounded transition-colors cursor-pointer"
                        >
                          <app-icon name="trash-2" [size]="14" strokeWidth="1.5"></app-icon>
                        </button>
                      </div>
                    </div>
                  }
                </div>
              </div>
            }
          </div>
        }
      </div>

    </div>
  `
})
export class HomeFeedComponent implements OnInit {
  private txService = inject(TransactionService);
  private budgetService = inject(BudgetService);
  private dashboardService = inject(DashboardService);
  private auth = inject(AuthService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  balance: MonthlyBalance = { income: 0, expense: 0, net: 0, savingsRate: 0 };
  periodLabel = 'September 2026';
  topBudgets: Budget[] = [];
  groupedTransactions: GroupedDayTransactions[] = [];

  isLoadingTxs = true;

  ngOnInit(): void {
    this.refreshAll();
  }

  refreshAll(): void {
    this.isLoadingTxs = true;

    // Parallel fetch via forkJoin with individual error boundaries
    forkJoin({
      dashboard: this.dashboardService.getDashboard().pipe(catchError(() => of(null))),
      budgets: this.budgetService.getBudgets('2026-09').pipe(catchError(() => of([]))),
      transactions: this.txService.getTransactions({ from: '2026-08-01', to: '2026-09-30' }).pipe(catchError(() => of([])))
    }).subscribe({
      next: ({ dashboard, budgets, transactions }) => {
        // 1. Dashboard summary
        if (dashboard && dashboard.summary) {
          this.balance = {
            income: dashboard.summary.totalIncome,
            expense: dashboard.summary.totalExpense,
            net: dashboard.summary.netAmount,
            savingsRate: Math.round(dashboard.summary.savingsGoalPct ?? 0)
          };
          this.periodLabel = dashboard.periodMonth || '2026-09';
        } else {
          this.balance = this.txService.getMonthlyBalance('2026-09');
        }

        // 2. Budgets
        this.topBudgets = (budgets || []).slice(0, 4);

        // 3. Transactions
        this.groupTransactionsByDay((transactions || []).slice(0, 15));
        this.isLoadingTxs = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoadingTxs = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadTransactions(): void {
    this.isLoadingTxs = true;
    this.txService.getTransactions({ from: '2026-08-01', to: '2026-09-30' }).subscribe({
      next: (txs) => {
        this.groupTransactionsByDay(txs.slice(0, 15));
        this.isLoadingTxs = false;
      },
      error: () => {
        this.isLoadingTxs = false;
      }
    });
  }

  private groupTransactionsByDay(txs: Transaction[]): void {
    const todayStr = '2026-09-24';
    const yesterdayStr = '2026-09-23';

    const map = new Map<string, Transaction[]>();

    for (const t of txs) {
      if (!map.has(t.date)) {
        map.set(t.date, []);
      }
      map.get(t.date)!.push(t);
    }

    const groups: GroupedDayTransactions[] = [];

    map.forEach((items, dateStr) => {
      let label = dateStr;
      if (dateStr === todayStr) {
        label = 'Today, Sep 24';
      } else if (dateStr === yesterdayStr) {
        label = 'Yesterday, Sep 23';
      } else {
        label = dateStr;
      }

      groups.push({
        label,
        date: dateStr,
        transactions: items
      });
    });

    // Sort descending by date
    this.groupedTransactions = groups.sort((a, b) => b.date.localeCompare(a.date));
  }

  getPercentage(spent: number, limit: number): number {
    return limit > 0 ? Math.round((spent / limit) * 100) : 0;
  }

  async deleteTx(id: string | number): Promise<void> {
    const confirmed = await this.toast.confirm(
      'Remove Transaction',
      'Are you sure you want to remove this transaction?',
      'Remove',
      'Cancel',
      true
    );

    if (confirmed) {
      this.txService.deleteTransaction(id).subscribe({
        next: () => {
          this.toast.success('Transaction removed successfully');
          this.refreshAll();
        },
        error: (err) => {
          this.toast.error(err.error?.message || 'Failed to remove transaction');
        }
      });
    }
  }

  goToQuickAdd(): void {}
}
