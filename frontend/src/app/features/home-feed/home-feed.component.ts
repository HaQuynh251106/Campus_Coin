import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TransactionService, MonthlyBalance } from '../../core/services/transaction.service';
import { BudgetService } from '../../core/services/budget.service';
import { InsightService } from '../../core/services/insight.service';
import { AuthService } from '../../core/services/auth.service';
import { Transaction } from '../../core/models/transaction.model';
import { Budget } from '../../core/models/budget.model';
import { MonthlyInsight } from '../../core/models/insight.model';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { ProgressRingComponent } from '../../shared/components/progress-ring/progress-ring.component';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { LoadingSkeletonComponent } from '../../shared/components/loading-skeleton/loading-skeleton.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

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
    IconComponent
  ],
  template: `
    <div class="space-y-6">

      <!-- 1. Top Balance & Allowance Hero Banner (Refined Modern SaaS) -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs relative overflow-hidden border-t-2 border-t-amber-500">
        <!-- Subtle Gold Ambient Glow -->
        <div class="absolute -right-16 -top-16 w-64 h-64 bg-amber-500/5 rounded-full blur-3xl pointer-events-none"></div>

        <div class="relative z-10">
          <div class="flex flex-wrap items-center justify-between gap-4 mb-5">
            <div>
              <span class="inline-flex items-center text-[11px] font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2.5 py-0.5 rounded-full">
                September 2026
              </span>
              <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight text-neutral-950 dark:text-white mt-1.5">
                \${{ balance.net.toFixed(2) }}
              </h2>
              <p class="text-xs font-medium text-neutral-500 dark:text-neutral-400 mt-0.5">
                Net Campus Balance (Income vs Expenses)
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
          <div class="grid grid-cols-3 gap-3 pt-4 border-t border-neutral-100 dark:border-neutral-800">
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-neutral-200/80 dark:border-neutral-800">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Total Income</span>
              <span class="font-semibold text-sm sm:text-base text-emerald-600 dark:text-emerald-400">
                +\${{ balance.income.toFixed(2) }}
              </span>
            </div>
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-neutral-200/80 dark:border-neutral-800">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Total Expenses</span>
              <span class="font-semibold text-sm sm:text-base text-rose-600 dark:text-rose-400">
                -\${{ balance.expense.toFixed(2) }}
              </span>
            </div>
            <div class="bg-neutral-50/80 dark:bg-neutral-800/40 p-3 rounded-lg border border-neutral-200/80 dark:border-neutral-800">
              <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 block mb-0.5">Savings Rate</span>
              <span class="font-semibold text-sm sm:text-base text-amber-600 dark:text-amber-400">
                {{ balance.savingsRate }}%
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. AI Insight Card (Clean SaaS style) -->
      @if (isLoadingInsight) {
        <app-loading-skeleton type="card"></app-loading-skeleton>
      } @else if (currentInsight) {
        <div class="card-brutal p-5 sm:p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs relative">
          <div class="flex items-start gap-4">
            <!-- Sourced Free-Licensed Real Photo for AI Student Advisor Avatar -->
            <div class="relative shrink-0">
              <div class="w-11 h-11 rounded-lg border border-neutral-200 dark:border-neutral-700 overflow-hidden bg-neutral-100 dark:bg-neutral-800">
                <img
                  [src]="currentInsight.avatarUrl"
                  alt="Campus AI Financial Coach"
                  class="w-full h-full object-cover"
                />
              </div>
              <span class="absolute -bottom-1 -right-1 w-3.5 h-3.5 rounded-full bg-amber-500 border border-white dark:border-neutral-900 flex items-center justify-center text-[8px] text-white">
                ⚡
              </span>
            </div>

            <!-- Content Bubble -->
            <div class="flex-1 min-w-0">
              <div class="flex flex-wrap items-center justify-between gap-2 mb-1.5">
                <div class="flex items-center gap-2">
                  <span class="font-semibold text-sm text-neutral-900 dark:text-neutral-100">
                    Campus AI Coach
                  </span>
                  <span class="inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
                    <app-icon name="sparkles" [size]="11" strokeWidth="1.5"></app-icon>
                    Monthly Story
                  </span>
                </div>
                <a routerLink="/app/insights" class="text-xs font-medium text-amber-600 dark:text-amber-400 hover:underline">
                  All Insights →
                </a>
              </div>

              <h4 class="font-semibold text-base text-neutral-900 dark:text-neutral-100 mb-1.5">
                "{{ currentInsight.title }}"
              </h4>

              <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 leading-relaxed mb-3">
                {{ currentInsight.narrativeSummary }}
              </p>

              <!-- Actionable Saving Tip Box -->
              <div class="p-3 bg-amber-500/5 dark:bg-amber-500/10 border border-amber-500/20 rounded-lg flex items-start gap-2.5">
                <span class="text-sm">💡</span>
                <p class="text-xs font-medium text-amber-900 dark:text-amber-200 leading-snug">
                  {{ currentInsight.savingTip }}
                </p>
              </div>
            </div>
          </div>
        </div>
      }

      <!-- 3. Category Budget Progress Cards Strip -->
      <div>
        <div class="flex items-center justify-between mb-3 px-0.5">
          <div>
            <h3 class="font-semibold text-base sm:text-lg text-neutral-900 dark:text-neutral-100 tracking-tight">
              Monthly Budget Status
            </h3>
            <p class="text-xs text-neutral-500">Live tracker computed from recent receipts</p>
          </div>
          <a routerLink="/app/budgets" class="text-xs font-medium text-amber-600 dark:text-amber-400 hover:underline">
            Manage Budgets →
          </a>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
          @for (bgt of topBudgets; track bgt.id) {
            <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
              <div class="flex items-center justify-between mb-2.5">
                <div class="flex items-center gap-2">
                  <span class="w-7 h-7 rounded-lg border border-neutral-200 dark:border-neutral-700 flex items-center justify-center text-xs" [style.background-color]="bgt.categoryColor">
                    <app-icon [name]="bgt.categoryIcon" [size]="14" strokeWidth="1.5"></app-icon>
                  </span>
                  <span class="font-medium text-xs text-neutral-900 dark:text-neutral-100 truncate max-w-[120px]">{{ bgt.categoryName }}</span>
                </div>
                <!-- Status Badge -->
                @if (bgt.alertStatus === 'DANGER') {
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-rose-50 text-rose-700 border border-rose-200 dark:bg-rose-950/40 dark:text-rose-300 dark:border-rose-800">Over</span>
                } @else if (bgt.alertStatus === 'WARNING') {
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-200 dark:bg-amber-950/40 dark:text-amber-300 dark:border-amber-800">80%+</span>
                } @else {
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800">Safe</span>
                }
              </div>

              <!-- Spending vs Limit -->
              <div class="flex items-baseline justify-between text-xs mb-2">
                <span class="font-semibold text-sm text-neutral-900 dark:text-white">\${{ bgt.spent }}</span>
                <span class="text-neutral-500">of \${{ bgt.monthlyLimit }}</span>
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
            <p class="text-xs text-neutral-500">Grouped timeline of campus spending</p>
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
                  <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400 px-2 py-0.5 bg-neutral-100 dark:bg-neutral-800 rounded-md">
                    {{ group.label }}
                  </span>
                  <div class="h-px flex-1 bg-neutral-200 dark:bg-neutral-800"></div>
                </div>

                <!-- Transaction Rows -->
                <div class="space-y-1.5">
                  @for (tx of group.transactions; track tx.id) {
                    <div class="card-brutal p-3 sm:p-3.5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs flex items-center justify-between gap-3 hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
                      <div class="flex items-center gap-3 min-w-0">
                        <!-- Category Icon -->
                        <div
                          class="w-9 h-9 rounded-lg border border-neutral-200 dark:border-neutral-700 shrink-0 flex items-center justify-center text-sm shadow-xs"
                          [style.background-color]="tx.categoryColor || '#EAB308'"
                        >
                          <app-icon [name]="tx.categoryIcon" [size]="16" strokeWidth="1.5" className="text-neutral-900"></app-icon>
                        </div>

                        <!-- Description & Category Name -->
                        <div class="min-w-0">
                          <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-100 truncate">
                            {{ tx.description }}
                          </h4>
                          <div class="flex items-center gap-2 text-[11px] text-neutral-500">
                            <span>{{ tx.categoryName }}</span>
                            @if (tx.recurringFrequency !== 'NONE') {
                              <span class="text-[10px] bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.2 rounded text-neutral-600 dark:text-neutral-400 border border-neutral-200 dark:border-neutral-700">
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
  private insightService = inject(InsightService);
  private auth = inject(AuthService);

  balance: MonthlyBalance = { income: 0, expense: 0, net: 0, savingsRate: 0 };
  currentInsight: MonthlyInsight | null = null;
  topBudgets: Budget[] = [];
  groupedTransactions: GroupedDayTransactions[] = [];

  isLoadingTxs = true;
  isLoadingInsight = true;

  ngOnInit(): void {
    this.refreshAll();
  }

  refreshAll(): void {
    // 1. Balance
    this.balance = this.txService.getMonthlyBalance('2026-09');

    // 2. Insight
    this.isLoadingInsight = true;
    this.insightService.getCurrentInsight().subscribe(insight => {
      this.currentInsight = insight;
      this.isLoadingInsight = false;
    });

    // 3. Budgets
    this.budgetService.getBudgets('2026-09').subscribe(budgets => {
      this.topBudgets = budgets.slice(0, 4);
    });

    // 4. Transactions stream
    this.loadTransactions();
  }

  loadTransactions(): void {
    this.isLoadingTxs = true;
    this.txService.getTransactions().subscribe(txs => {
      this.groupTransactionsByDay(txs.slice(0, 15));
      this.balance = this.txService.getMonthlyBalance('2026-09');
      this.isLoadingTxs = false;
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

  deleteTx(id: string): void {
    this.txService.deleteTransaction(id).subscribe(() => {
      this.refreshAll();
    });
  }

  goToQuickAdd(): void {}
}
