import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BudgetService, BudgetAlertNotification } from '../../core/services/budget.service';
import { CategoryService } from '../../core/services/category.service';
import { Budget } from '../../core/models/budget.model';
import { Category } from '../../core/models/category.model';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { ProgressRingComponent } from '../../shared/components/progress-ring/progress-ring.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-budgets',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    BreadcrumbsComponent,
    ProgressRingComponent,
    IconComponent
  ],
  template: `
    <div class="space-y-6">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'Budgets & Goals' }]"
      ></app-breadcrumbs>

      <!-- Header & Add Button -->
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Monthly Budgets & Category Limits
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            Keep your student allowance on track with real-time overspend alerts.
          </p>
        </div>

        <button
          type="button"
          (click)="openAddModal()"
          class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs sm:text-sm py-2 px-3.5 rounded-lg shadow-xs transition-colors flex items-center gap-1.5 cursor-pointer"
        >
          <app-icon name="plus" [size]="15" strokeWidth="1.5"></app-icon>
          <span>Add Category Budget</span>
        </button>
      </div>

      <!-- 1. Real-Time Budget Alerts Banner (80%+ or Over-budget) -->
      @if (alerts.length > 0) {
        <div class="space-y-2">
          @for (alert of alerts; track alert.budgetId) {
            <div
              class="p-4 rounded-xl border flex items-start justify-between gap-3 animate-fade-in shadow-xs"
              [class.bg-rose-50]="alert.status === 'DANGER'"
              [class.border-rose-200]="alert.status === 'DANGER'"
              [class.dark:bg-rose-950/40]="alert.status === 'DANGER'"
              [class.dark:border-rose-900/60]="alert.status === 'DANGER'"
              [class.bg-amber-50]="alert.status === 'WARNING'"
              [class.border-amber-200]="alert.status === 'WARNING'"
              [class.dark:bg-amber-950/40]="alert.status === 'WARNING'"
              [class.dark:border-amber-900/60]="alert.status === 'WARNING'"
            >
              <div class="flex items-start gap-3">
                <span class="text-lg">
                  {{ alert.status === 'DANGER' ? '🚨' : '⚠️' }}
                </span>
                <div>
                  <h4 class="font-semibold text-sm" [class.text-rose-900]="alert.status === 'DANGER'" [class.dark:text-rose-200]="alert.status === 'DANGER'" [class.text-amber-900]="alert.status === 'WARNING'" [class.dark:text-amber-200]="alert.status === 'WARNING'">
                    {{ alert.status === 'DANGER' ? 'Over Budget Alert' : 'Approaching Budget Limit' }} — {{ alert.categoryName }}
                  </h4>
                  <p class="text-xs text-neutral-600 dark:text-neutral-300 mt-0.5">
                    {{ alert.message }}
                  </p>
                </div>
              </div>

              <button
                type="button"
                (click)="dismissAlert(alert.budgetId)"
                class="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 p-1 cursor-pointer"
                title="Dismiss Alert"
              >
                ✕
              </button>
            </div>
          }
        </div>
      }

      <!-- 2. Overall Budget Health Meter Card -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex flex-wrap items-center justify-between gap-4 mb-4 pb-3 border-b border-neutral-200 dark:border-neutral-800">
          <div>
            <span class="inline-flex items-center text-[11px] font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded-full">
              Macro Envelope
            </span>
            <h3 class="text-lg font-semibold text-neutral-900 dark:text-neutral-50 mt-1 tracking-tight">
              Overall September Budget Progress
            </h3>
          </div>

          <div class="flex items-baseline gap-2">
            <span class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-white">
              \${{ totalSpent.toFixed(2) }}
            </span>
            <span class="text-xs font-medium text-neutral-500">
              allocated of \${{ totalLimit.toFixed(2) }}
            </span>
          </div>
        </div>

        <!-- Global Progress Bar -->
        <app-progress-ring
          [percentage]="overallPercentage"
          mode="bar"
          label="Total Monthly Envelope Consumed"
        ></app-progress-ring>
      </div>

      <!-- 3. Category Budgets Grid -->
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        @for (bgt of budgets; track bgt.id) {
          <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs flex flex-col justify-between hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
            <div>
              <!-- Header with Icon & Edit Button -->
              <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-2.5">
                  <div
                    class="w-9 h-9 rounded-lg border border-neutral-200 dark:border-neutral-700 flex items-center justify-center text-sm shadow-xs"
                    [style.background-color]="bgt.categoryColor || '#EAB308'"
                  >
                    <app-icon [name]="bgt.categoryIcon" [size]="16" strokeWidth="1.5" className="text-neutral-900"></app-icon>
                  </div>
                  <div>
                    <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-50">
                      {{ bgt.categoryName }}
                    </h4>
                    <span class="text-[11px] text-neutral-400">Monthly Target</span>
                  </div>
                </div>

                <button
                  type="button"
                  (click)="openEditModal(bgt)"
                  class="p-1.5 border border-neutral-200 dark:border-neutral-700 rounded-md text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100 hover:bg-neutral-50 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
                  title="Edit Category Budget"
                >
                  <app-icon name="edit-2" [size]="13" strokeWidth="1.5"></app-icon>
                </button>
              </div>

              <!-- Numerical Progress -->
              <div class="flex items-baseline justify-between text-xs mb-2">
                <span class="text-sm font-semibold text-neutral-900 dark:text-white">
                  \${{ bgt.spent.toFixed(2) }} spent
                </span>
                <span class="text-neutral-500">
                  Limit: \${{ bgt.monthlyLimit.toFixed(2) }}
                </span>
              </div>

              <!-- Progress bar -->
              <div class="mb-3">
                <app-progress-ring
                  [percentage]="calcPercentage(bgt.spent, bgt.monthlyLimit)"
                  mode="bar"
                  [showLabel]="false"
                ></app-progress-ring>
              </div>
            </div>

            <!-- Footer Status -->
            <div class="pt-3 border-t border-neutral-100 dark:border-neutral-800 flex items-center justify-between text-xs font-medium">
              @if (bgt.alertStatus === 'DANGER') {
                <span class="text-rose-600 dark:text-rose-400 flex items-center gap-1">
                  <span>Over Budget by \${{ (bgt.spent - bgt.monthlyLimit).toFixed(2) }}</span>
                </span>
              } @else if (bgt.alertStatus === 'WARNING') {
                <span class="text-amber-600 dark:text-amber-400 flex items-center gap-1">
                  <span>\${{ (bgt.monthlyLimit - bgt.spent).toFixed(2) }} remaining</span>
                </span>
              } @else {
                <span class="text-emerald-600 dark:text-emerald-400 flex items-center gap-1">
                  <span>\${{ (bgt.monthlyLimit - bgt.spent).toFixed(2) }} safe</span>
                </span>
              }

              <span class="font-mono text-neutral-400 text-[11px]">
                {{ calcPercentage(bgt.spent, bgt.monthlyLimit) }}%
              </span>
            </div>
          </div>
        }
      </div>

      <!-- 4. Budget Edit / Create Modal -->
      @if (showModal) {
        <div class="fixed inset-0 z-50 bg-black/40 backdrop-blur-xs flex items-center justify-center p-4">
          <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 max-w-md w-full rounded-xl shadow-lg animate-scale-up">
            <div class="flex items-center justify-between mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800">
              <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
                {{ editingBudget ? 'Adjust Budget Limit' : 'Set Category Budget' }}
              </h3>
              <button
                type="button"
                (click)="closeModal()"
                class="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 text-lg cursor-pointer"
              >
                ✕
              </button>
            </div>

            <div class="space-y-4">
              @if (!editingBudget) {
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Select Category
                  </label>
                  <select [(ngModel)]="selectedCategoryId" class="input-brutal">
                    @for (cat of availableCategories; track cat.id) {
                      <option [value]="cat.id">{{ cat.name }}</option>
                    }
                  </select>
                </div>
              } @else {
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Category
                  </label>
                  <div class="font-medium text-base text-neutral-900 dark:text-neutral-100">
                    {{ editingBudget.categoryName }}
                  </div>
                </div>
              }

              <div>
                <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                  Monthly Limit ($ USD)
                </label>
                <input
                  type="number"
                  min="5"
                  step="5"
                  [(ngModel)]="modalLimit"
                  class="input-brutal text-lg font-semibold"
                />
              </div>

              <div class="flex items-center justify-end gap-2 pt-3">
                <button
                  type="button"
                  (click)="closeModal()"
                  class="px-3.5 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-xs transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  (click)="saveModal()"
                  class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs py-2 px-4 rounded-lg shadow-xs transition-colors cursor-pointer"
                >
                  Save Limit ✓
                </button>
              </div>
            </div>
          </div>
        </div>
      }

    </div>
  `
})
export class BudgetsComponent implements OnInit {
  private budgetService = inject(BudgetService);
  private categoryService = inject(CategoryService);

  budgets: Budget[] = [];
  alerts: BudgetAlertNotification[] = [];
  dismissedAlertIds: string[] = [];

  availableCategories: Category[] = [];

  totalLimit = 0;
  totalSpent = 0;
  overallPercentage = 0;

  showModal = false;
  editingBudget: Budget | null = null;
  selectedCategoryId = '';
  modalLimit = 100;

  ngOnInit(): void {
    this.loadBudgets();
    this.loadCategories();
  }

  loadBudgets(): void {
    this.budgetService.getBudgets('2026-09').subscribe(data => {
      this.budgets = data;
      this.totalLimit = data.reduce((acc, b) => acc + b.monthlyLimit, 0);
      this.totalSpent = data.reduce((acc, b) => acc + b.spent, 0);
      this.overallPercentage = this.totalLimit > 0
        ? Math.round((this.totalSpent / this.totalLimit) * 100)
        : 0;
    });

    this.budgetService.getBudgetAlerts('2026-09').subscribe(alerts => {
      this.alerts = alerts.filter(a => !this.dismissedAlertIds.includes(a.budgetId));
    });
  }

  loadCategories(): void {
    this.categoryService.getExpenseCategories().subscribe(cats => {
      this.availableCategories = cats;
      if (cats.length > 0) {
        this.selectedCategoryId = cats[0].id;
      }
    });
  }

  calcPercentage(spent: number, limit: number): number {
    return limit > 0 ? Math.round((spent / limit) * 100) : 0;
  }

  dismissAlert(budgetId: string): void {
    this.dismissedAlertIds.push(budgetId);
    this.alerts = this.alerts.filter(a => a.budgetId !== budgetId);
  }

  openAddModal(): void {
    this.editingBudget = null;
    this.modalLimit = 100;
    this.showModal = true;
  }

  openEditModal(budget: Budget): void {
    this.editingBudget = budget;
    this.modalLimit = budget.monthlyLimit;
    this.showModal = true;
  }

  closeModal(): void {
    this.showModal = false;
    this.editingBudget = null;
  }

  saveModal(): void {
    if (this.modalLimit <= 0) return;

    if (this.editingBudget) {
      this.budgetService.updateBudgetLimit(this.editingBudget.id, this.modalLimit).subscribe(() => {
        this.loadBudgets();
        this.closeModal();
      });
    } else {
      if (!this.selectedCategoryId) return;
      this.budgetService.addBudget(this.selectedCategoryId, this.modalLimit).subscribe(() => {
        this.loadBudgets();
        this.closeModal();
      });
    }
  }
}
