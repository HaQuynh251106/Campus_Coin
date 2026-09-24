import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators, FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TransactionService } from '../../core/services/transaction.service';
import { CategoryService } from '../../core/services/category.service';
import { AiCategorizationService, AiParseResult } from '../../core/services/ai-categorization.service';
import { Category } from '../../core/models/category.model';
import { Transaction, TransactionType } from '../../core/models/transaction.model';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-quick-add',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    BreadcrumbsComponent,
    IconComponent
  ],
  template: `
    <div class="space-y-6">

      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'Quick Add' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Log Transaction
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            Type naturally for instant AI auto-tagging or enter details manually below.
          </p>
        </div>
      </div>

      <!-- 1. Smart Conversational Input Card (AI-Powered) -->
      <div class="card-brutal p-5 sm:p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex items-center gap-2 mb-3">
          <span class="inline-flex items-center gap-1.5 text-[11px] font-medium px-2.5 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
            <app-icon name="sparkles" [size]="12" strokeWidth="1.5"></app-icon>
            AI Smart Assist
          </span>
          <span class="text-xs text-neutral-500 dark:text-neutral-400">
            Natural language parser
          </span>
        </div>

        <!-- Conversational Input Field -->
        <div class="relative">
          <input
            type="text"
            [(ngModel)]="conversationalInput"
            (ngModelChange)="onConversationalInput($event)"
            placeholder="Type what happened… e.g. 'Campus dining lunch $14.50' or 'Uber ride $8.20'"
            class="input-brutal text-sm sm:text-base font-normal pr-10 rounded-lg py-2.5 px-3.5"
          />
          @if (conversationalInput) {
            <button
              type="button"
              (click)="clearConversationalInput()"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 cursor-pointer"
            >
              ✕
            </button>
          }
        </div>

        <!-- Real-Time AI Suggestion Chip -->
        @if (aiResult && conversationalInput.trim().length > 2) {
          <div class="mt-4 p-3.5 bg-neutral-50 dark:bg-neutral-800/60 border border-neutral-200 dark:border-neutral-700 rounded-lg flex flex-wrap items-center justify-between gap-3 animate-fade-in">
            <div class="flex items-center gap-2.5">
              <span class="text-xs font-medium text-neutral-500">Suggested:</span>
              @if (aiResult.suggestedCategory) {
                <div
                  class="text-xs py-1 px-2.5 rounded-full flex items-center gap-1.5 border border-neutral-200 dark:border-neutral-700 font-medium"
                  [style.background-color]="aiResult.suggestedCategory.color"
                >
                  <app-icon [name]="aiResult.suggestedCategory.icon" [size]="13" strokeWidth="1.5"></app-icon>
                  <span>{{ aiResult.suggestedCategory.name }}</span>
                </div>
              }

              @if (aiResult.extractedAmount) {
                <span class="text-xs font-mono font-semibold bg-white dark:bg-neutral-700 px-2 py-0.5 rounded border border-neutral-200 dark:border-neutral-600">
                  \${{ aiResult.extractedAmount }}
                </span>
              }
            </div>

            <!-- Affordances: Accept or Change -->
            <div class="flex items-center gap-2">
              <button
                type="button"
                (click)="applyAiSuggestion()"
                class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs py-1 px-3 rounded-md shadow-xs transition-colors cursor-pointer"
              >
                Apply to Form ✓
              </button>
              <button
                type="button"
                (click)="showCategoryPicker = !showCategoryPicker"
                class="text-xs text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100 underline cursor-pointer"
              >
                Change category
              </button>
            </div>
          </div>

          <!-- Quick Category Switcher Panel if user says 'not right' -->
          @if (showCategoryPicker) {
            <div class="mt-3 p-3 bg-white dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 rounded-lg flex flex-wrap gap-1.5">
              @for (cat of categories; track cat.id) {
                <button
                  type="button"
                  (click)="selectCategoryOverride(cat)"
                  class="text-[11px] py-1 px-2.5 rounded-full border border-neutral-200 dark:border-neutral-700 cursor-pointer hover:opacity-85 transition-opacity flex items-center gap-1 font-medium"
                  [style.background-color]="cat.color"
                >
                  <app-icon [name]="cat.icon" [size]="12" strokeWidth="1.5"></app-icon>
                  <span>{{ cat.name }}</span>
                </button>
              }
            </div>
          }
        }
      </div>

      <!-- 2. Full Manual Entry Form (Fallback & Explicit Control) -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800 tracking-tight">
          Transaction Details
        </h3>

        @if (successMessage) {
          <div class="mb-4 p-3 bg-emerald-50 dark:bg-emerald-950/50 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200 text-xs font-medium rounded-lg flex items-center gap-2">
            <span>✓ {{ successMessage }}</span>
          </div>
        }

        <form [formGroup]="txForm" (ngSubmit)="onSubmitTransaction()" class="space-y-4">
          <!-- Type Switcher (Income vs Expense) -->
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1.5">
              Transaction Type
            </label>
            <div class="grid grid-cols-2 gap-2 max-w-xs">
              <button
                type="button"
                (click)="setType('EXPENSE')"
                class="py-2 px-3 font-medium text-xs rounded-lg border transition-all cursor-pointer flex items-center justify-center gap-1.5"
                [class.bg-rose-500]="txForm.get('type')?.value === 'EXPENSE'"
                [class.text-white]="txForm.get('type')?.value === 'EXPENSE'"
                [class.border-rose-500]="txForm.get('type')?.value === 'EXPENSE'"
                [class.bg-neutral-50]="txForm.get('type')?.value !== 'EXPENSE'"
                [class.dark:bg-neutral-800]="txForm.get('type')?.value !== 'EXPENSE'"
                [class.border-neutral-200]="txForm.get('type')?.value !== 'EXPENSE'"
                [class.dark:border-neutral-700]="txForm.get('type')?.value !== 'EXPENSE'"
                [class.text-neutral-600]="txForm.get('type')?.value !== 'EXPENSE'"
                [class.dark:text-neutral-400]="txForm.get('type')?.value !== 'EXPENSE'"
              >
                <span>↓ Expense</span>
              </button>

              <button
                type="button"
                (click)="setType('INCOME')"
                class="py-2 px-3 font-medium text-xs rounded-lg border transition-all cursor-pointer flex items-center justify-center gap-1.5"
                [class.bg-emerald-500]="txForm.get('type')?.value === 'INCOME'"
                [class.text-white]="txForm.get('type')?.value === 'INCOME'"
                [class.border-emerald-500]="txForm.get('type')?.value === 'INCOME'"
                [class.bg-neutral-50]="txForm.get('type')?.value !== 'INCOME'"
                [class.dark:bg-neutral-800]="txForm.get('type')?.value !== 'INCOME'"
                [class.border-neutral-200]="txForm.get('type')?.value !== 'INCOME'"
                [class.dark:border-neutral-700]="txForm.get('type')?.value !== 'INCOME'"
                [class.text-neutral-600]="txForm.get('type')?.value !== 'INCOME'"
                [class.dark:text-neutral-400]="txForm.get('type')?.value !== 'INCOME'"
              >
                <span>↑ Income</span>
              </button>
            </div>
          </div>

          <!-- Amount & Date Grid -->
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <!-- Amount -->
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Amount ($ USD) *
              </label>
              <div class="relative">
                <span class="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 font-medium">
                  $
                </span>
                <input
                  type="number"
                  step="0.01"
                  min="0.01"
                  formControlName="amount"
                  placeholder="0.00"
                  class="input-brutal pl-7 text-lg font-semibold"
                />
              </div>
            </div>

            <!-- Date -->
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Date *
              </label>
              <input
                type="date"
                formControlName="date"
                class="input-brutal"
              />
            </div>
          </div>

          <!-- Category & Recurring Grid -->
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <!-- Category Dropdown -->
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Category *
              </label>
              <select formControlName="categoryId" class="input-brutal">
                @for (cat of filteredCategories; track cat.id) {
                  <option [value]="cat.id">
                    {{ cat.name }} ({{ cat.type }})
                  </option>
                }
              </select>
            </div>

            <!-- Make Recurring Toggle -->
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Recurring Frequency
              </label>
              <select formControlName="recurringFrequency" class="input-brutal">
                <option value="NONE">One-time only (No repeat)</option>
                <option value="DAILY">Repeat Daily</option>
                <option value="WEEKLY">Repeat Weekly</option>
                <option value="MONTHLY">Repeat Monthly</option>
              </select>
            </div>
          </div>

          <!-- Description -->
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Description *
            </label>
            <input
              type="text"
              formControlName="description"
              placeholder="e.g. Campus Cafeteria Burrito Lunch"
              class="input-brutal"
            />
          </div>

          <!-- Optional Notes -->
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Optional Note
            </label>
            <input
              type="text"
              formControlName="note"
              placeholder="Split with study group / saved receipt"
              class="input-brutal text-xs"
            />
          </div>

          <!-- Submit Buttons -->
          <div class="flex items-center gap-3 pt-2">
            <button
              type="submit"
              [disabled]="txForm.invalid || isSubmitting"
              class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-5 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
            >
              @if (isSubmitting) {
                <span class="inline-block animate-spin">⏳</span>
              }
              <span>{{ editingTxId ? 'Update Transaction' : 'Save Transaction' }}</span>
            </button>

            @if (editingTxId) {
              <button
                type="button"
                (click)="cancelEdit()"
                class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-sm transition-colors cursor-pointer"
              >
                Cancel Edit
              </button>
            }
          </div>
        </form>
      </div>

      <!-- 3. Existing Transactions List for Quick Edit / Soft Delete -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
          <div>
            <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
              Recent Entries
            </h3>
            <p class="text-xs text-neutral-500">Edit entries or remove with soft-delete preservation</p>
          </div>

          <div class="text-xs font-medium text-neutral-400">
            {{ recentList.length }} entries
          </div>
        </div>

        <div class="overflow-x-auto">
          <table class="w-full text-left text-xs font-normal border-collapse">
            <thead>
              <tr class="border-b border-neutral-200 dark:border-neutral-800 text-neutral-400 font-medium uppercase text-[11px]">
                <th class="py-2.5 px-3">Date</th>
                <th class="py-2.5 px-3">Category</th>
                <th class="py-2.5 px-3">Description</th>
                <th class="py-2.5 px-3 text-right">Amount</th>
                <th class="py-2.5 px-3 text-center">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-neutral-100 dark:divide-neutral-800">
              @for (tx of recentList; track tx.id) {
                <tr class="hover:bg-neutral-50 dark:hover:bg-neutral-800/40 transition-colors">
                  <td class="py-2.5 px-3 font-mono text-neutral-500 whitespace-nowrap">
                    {{ tx.date }}
                  </td>
                  <td class="py-2.5 px-3">
                    <span
                      class="inline-flex items-center gap-1 text-[11px] py-0.5 px-2 rounded-full border border-neutral-200 dark:border-neutral-700 font-medium"
                      [style.background-color]="tx.categoryColor || '#EAB308'"
                    >
                      <app-icon [name]="tx.categoryIcon" [size]="12" strokeWidth="1.5"></app-icon>
                      <span>{{ tx.categoryName }}</span>
                    </span>
                  </td>
                  <td class="py-2.5 px-3 text-neutral-900 dark:text-neutral-100 font-medium max-w-[200px] truncate">
                    {{ tx.description }}
                  </td>
                  <td
                    class="py-2.5 px-3 text-right font-semibold whitespace-nowrap font-mono"
                    [class.text-emerald-600]="tx.type === 'INCOME'"
                    [class.text-rose-600]="tx.type === 'EXPENSE'"
                  >
                    {{ tx.type === 'INCOME' ? '+' : '-' }}\${{ tx.amount.toFixed(2) }}
                  </td>
                  <td class="py-2.5 px-3 text-center whitespace-nowrap">
                    <div class="flex items-center justify-center gap-1">
                      <button
                        type="button"
                        (click)="startEdit(tx)"
                        title="Edit Transaction"
                        class="p-1 text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 rounded cursor-pointer"
                      >
                        <app-icon name="edit-2" [size]="14" strokeWidth="1.5"></app-icon>
                      </button>
                      <button
                        type="button"
                        (click)="onDelete(tx.id)"
                        title="Delete"
                        class="p-1 text-neutral-400 hover:text-rose-600 rounded cursor-pointer"
                      >
                        <app-icon name="trash-2" [size]="14" strokeWidth="1.5"></app-icon>
                      </button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

    </div>
  `
})
export class QuickAddComponent implements OnInit {
  private fb = inject(FormBuilder);
  private txService = inject(TransactionService);
  private categoryService = inject(CategoryService);
  private aiService = inject(AiCategorizationService);
  private router = inject(Router);

  conversationalInput = '';
  aiResult: AiParseResult | null = null;
  showCategoryPicker = false;

  categories: Category[] = [];
  filteredCategories: Category[] = [];
  recentList: Transaction[] = [];

  isSubmitting = false;
  successMessage = '';
  editingTxId: string | null = null;

  txForm = this.fb.group({
    type: ['EXPENSE' as TransactionType, [Validators.required]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    date: ['2026-09-24', [Validators.required]],
    categoryId: ['', [Validators.required]],
    description: ['', [Validators.required, Validators.minLength(2)]],
    recurringFrequency: ['NONE'],
    note: ['']
  });

  ngOnInit(): void {
    this.categoryService.getCategories().subscribe(cats => {
      this.categories = cats;
      this.filterCategoriesByType('EXPENSE');
    });

    this.loadRecent();
  }

  loadRecent(): void {
    this.txService.getRecentTransactions(10).subscribe(list => {
      this.recentList = list;
    });
  }

  setType(type: TransactionType): void {
    this.txForm.patchValue({ type });
    this.filterCategoriesByType(type);
  }

  private filterCategoriesByType(type: TransactionType): void {
    this.filteredCategories = this.categories.filter(c => c.type === type);
    if (this.filteredCategories.length > 0 && !this.txForm.get('categoryId')?.value) {
      this.txForm.patchValue({ categoryId: this.filteredCategories[0].id });
    }
  }

  onConversationalInput(text: string): void {
    if (!text || text.trim().length < 3) {
      this.aiResult = null;
      return;
    }

    this.aiResult = this.aiService.parseNaturalLanguage(text);
  }

  clearConversationalInput(): void {
    this.conversationalInput = '';
    this.aiResult = null;
  }

  applyAiSuggestion(): void {
    if (!this.aiResult) return;

    this.txForm.patchValue({
      description: this.aiResult.cleanedDescription,
      amount: this.aiResult.extractedAmount || this.txForm.get('amount')?.value,
      categoryId: this.aiResult.suggestedCategory?.id || this.txForm.get('categoryId')?.value,
      type: this.aiResult.suggestedCategory?.type || 'EXPENSE'
    });

    this.filterCategoriesByType(this.aiResult.suggestedCategory?.type || 'EXPENSE');
    this.conversationalInput = '';
    this.aiResult = null;
  }

  selectCategoryOverride(cat: Category): void {
    if (this.aiResult) {
      this.aiResult.suggestedCategory = cat;
      this.showCategoryPicker = false;
    }
  }

  onSubmitTransaction(): void {
    if (this.txForm.invalid) {
      this.txForm.markAllAsTouched();
      return;
    }

    this.isSubmitting = true;
    const val = this.txForm.value;
    const cat = this.categories.find(c => c.id === val.categoryId);

    const payload = {
      userId: 'user-001',
      type: val.type as TransactionType,
      amount: Number(val.amount),
      date: val.date as string,
      categoryId: val.categoryId as string,
      categoryName: cat?.name || 'Uncategorized',
      categoryIcon: cat?.icon || 'tag',
      categoryColor: cat?.color || '#EAB308',
      description: val.description as string,
      recurringFrequency: (val.recurringFrequency || 'NONE') as any,
      note: val.note || ''
    };

    if (this.editingTxId) {
      this.txService.updateTransaction(this.editingTxId, payload).subscribe({
        next: () => {
          this.isSubmitting = false;
          this.successMessage = 'Transaction updated!';
          this.editingTxId = null;
          this.resetForm();
          this.loadRecent();
          setTimeout(() => this.successMessage = '', 3500);
        },
        error: () => {
          this.isSubmitting = false;
        }
      });
    } else {
      this.txService.addTransaction(payload).subscribe({
        next: () => {
          this.isSubmitting = false;
          this.successMessage = 'Transaction recorded!';
          this.resetForm();
          this.loadRecent();
          setTimeout(() => this.successMessage = '', 3500);
        },
        error: () => {
          this.isSubmitting = false;
        }
      });
    }
  }

  startEdit(tx: Transaction): void {
    this.editingTxId = tx.id;
    this.txForm.patchValue({
      type: tx.type,
      amount: tx.amount,
      date: tx.date,
      categoryId: tx.categoryId,
      description: tx.description,
      recurringFrequency: tx.recurringFrequency || 'NONE',
      note: tx.note || ''
    });
    this.filterCategoriesByType(tx.type);
    window.scrollTo({ top: 300, behavior: 'smooth' });
  }

  cancelEdit(): void {
    this.editingTxId = null;
    this.resetForm();
  }

  onDelete(id: string): void {
    this.txService.deleteTransaction(id).subscribe(() => {
      this.loadRecent();
    });
  }

  private resetForm(): void {
    this.txForm.reset({
      type: 'EXPENSE',
      amount: null,
      date: '2026-09-24',
      categoryId: this.filteredCategories[0]?.id || '',
      recurringFrequency: 'NONE',
      note: ''
    });
  }
}
