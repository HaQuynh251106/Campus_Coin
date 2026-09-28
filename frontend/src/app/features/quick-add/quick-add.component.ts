import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators, FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TransactionService } from '../../core/services/transaction.service';
import { RecurringRuleService } from '../../core/services/recurring-rule.service';
import { RecurringFrequency } from '../../core/models/recurring-rule.model';
import { CategoryService } from '../../core/services/category.service';
import { MascotService } from '../../core/services/mascot.service';
import { ToastService } from '../../core/services/toast.service';
import { Category } from '../../core/models/category.model';
import { Transaction, TransactionType } from '../../core/models/transaction.model';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryTagComponent } from '../../shared/components/category-tag/category-tag.component';
import { CategorisationService } from '../../core/services/categorisation.service';
import { CategorySuggestion } from '../../core/models/categorisation.model';

@Component({
  selector: 'app-quick-add',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    IconComponent,
    CategoryTagComponent
  ],
  template: `
    <div class="space-y-6">

      <!-- Page Header -->
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Log Transaction
          </h2>
          <p class="text-xs sm:text-sm font-medium text-[var(--color-text-muted)]">
            Enter spending with manual transaction details.
          </p>
        </div>
      </div>

      <!-- Manual Entry Form -->
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
              <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-300 mb-1.5">
                Amount ($ USD) *
              </label>
              <div class="relative flex items-center">
                <span class="absolute left-3.5 text-neutral-700 dark:text-neutral-200 font-bold text-base pointer-events-none select-none z-10">
                  $
                </span>
                <input
                  type="number"
                  step="0.01"
                  min="0.01"
                  formControlName="amount"
                  placeholder="0.00"
                  class="input-brutal !pl-9 text-lg font-semibold placeholder:text-neutral-400 dark:placeholder:text-neutral-500"
                  style="padding-left: 2.25rem !important;"
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

          <!-- Category & Repeat Grid -->
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

            <!--
              Repeat. This saves the transaction and then creates a separate recurring rule for it,
              because the two are separate records: the transaction happened once on a date, and the
              rule is the schedule that repeats it. There is no frequency field on a transaction to
              send it in, so the rule is a second call (module 5).
            -->
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Repeat
              </label>
              @if (editingTxId) {
                <div class="input-brutal opacity-70 text-[var(--color-text-muted)] flex items-center">
                  Manage this on the Recurring page
                </div>
              } @else {
                <select formControlName="repeatFrequency" class="input-brutal">
                  <option value="NONE">One-time only (no repeat)</option>
                  <option value="DAILY">Every day</option>
                  <option value="WEEKLY">Every week</option>
                  <option value="MONTHLY">Every month</option>
                  <option value="QUARTERLY">Every quarter</option>
                  <option value="YEARLY">Every year</option>
                </select>
              }
              @if (!editingTxId && repeatFrequency !== 'NONE') {
                <p class="text-[10px] text-neutral-400 mt-0.5">
                  Saves this entry and starts a repeating rule from {{ txForm.get('date')?.value }}.
                </p>
              }
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
            <p class="text-xs text-[var(--color-text-muted)]">Edit or remove recent transactions</p>
          </div>

          <div class="text-xs font-medium text-[var(--color-text-muted)]">
            {{ recentList.length }} entries
          </div>
        </div>

        <div class="overflow-x-auto">
          <table class="w-full text-left text-xs font-normal border-collapse">
            <thead>
              <tr class="table-head-row border-b border-[var(--color-border)] text-[var(--color-text-muted)] font-medium uppercase text-[11px]">
                <th class="py-2.5 pl-4 pr-2 w-10 text-center text-[var(--color-text-muted)]">#</th>
                <th class="py-2.5 px-3 text-[var(--color-text-muted)]">Date</th>
                <th class="py-2.5 px-3 text-[var(--color-text-muted)]">Category</th>
                <th class="py-2.5 px-3 text-[var(--color-text-muted)]">Description</th>
                <th class="py-2.5 px-3 text-right text-[var(--color-text-muted)]">Amount</th>
                <th class="py-2.5 px-3 text-center text-[var(--color-text-muted)]">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-[var(--color-border)]">
              @for (tx of recentList; track tx.id; let idx = $index) {
                <tr class="hover:bg-neutral-50/80 dark:hover:bg-neutral-800/40 transition-colors">
                  <td class="py-2.5 pl-4 pr-2 text-center font-mono text-xs text-[var(--color-text-muted)] whitespace-nowrap">
                    {{ idx + 1 }}
                  </td>
                  <td class="py-2.5 px-3 font-mono text-xs text-[var(--color-text-muted)] whitespace-nowrap">
                    {{ tx.date }}
                  </td>
                  <td class="py-2.5 px-3 whitespace-nowrap">
                    <app-category-tag
                      [name]="tx.categoryName"
                      [icon]="tx.categoryIcon"
                      [color]="tx.categoryColor"
                      size="xs"
                    ></app-category-tag>
                  </td>
                  <td class="py-2.5 px-3 text-neutral-900 dark:text-neutral-100 font-medium max-w-[200px] truncate">
                    {{ tx.description }}
                  </td>
                  <td
                    class="py-2.5 px-3 text-right font-semibold whitespace-nowrap font-mono"
                    [class.text-emerald-600]="tx.type === 'INCOME'"
                    [class.dark:text-emerald-400]="tx.type === 'INCOME'"
                    [class.text-rose-600]="tx.type === 'EXPENSE'"
                    [class.dark:text-rose-400]="tx.type === 'EXPENSE'"
                  >
                    {{ tx.type === 'INCOME' ? '+' : '-' }}\${{ tx.amount.toFixed(2) }}
                  </td>
                  <td class="py-2.5 px-3 text-center whitespace-nowrap">
                    <div class="flex items-center justify-center gap-1">
                      <!--
                        Asks for a category proposal for this entry (M12, UC-08). It is a suggestion
                        and nothing more: the row's category is unchanged until the student accepts
                        it below.
                      -->
                      <button
                        type="button"
                        (click)="suggestCategory(tx)"
                        [disabled]="suggestingTxId === tx.id"
                        title="Suggest a category"
                        class="p-1 text-neutral-400 hover:text-amber-600 disabled:opacity-50 rounded cursor-pointer"
                      >
                        @if (suggestingTxId === tx.id) {
                          <span class="inline-block animate-spin text-[11px]">⏳</span>
                        } @else {
                          <app-icon name="sparkles" [size]="14" strokeWidth="1.5"></app-icon>
                        }
                      </button>
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

                <!--
                  The proposal sits under its own row, beside the record rather than in it (BR-13).
                  Accepting is a normal category update; dismissing leaves the entry exactly as it
                  was. Nothing is applied on its own.
                -->
                @if (suggestion && suggestion.transactionId === +tx.id) {
                  <tr class="bg-amber-50/60 dark:bg-amber-950/20">
                    <td colspan="6" class="px-4 py-3">
                      @if (suggestion.source === 'NONE') {
                        <div class="flex items-start justify-between gap-3">
                          <p class="text-xs text-neutral-600 dark:text-neutral-400">
                            No suggestion for this entry.
                            @if (suggestion.learned) {
                              Your choice was remembered: “{{ suggestion.learned.keyword }}” is now
                              filed under {{ suggestion.learned.categoryName }}.
                            } @else {
                              Nothing in your own rules matches this description, and no AI provider
                              is configured, so the category is yours to choose.
                            }
                          </p>
                          <button type="button" (click)="dismissSuggestion()"
                            class="text-xs font-semibold text-neutral-500 hover:text-neutral-800 dark:hover:text-neutral-200 shrink-0 cursor-pointer">
                            Close
                          </button>
                        </div>
                      } @else {
                        <div class="flex flex-wrap items-start justify-between gap-3">
                          <div class="min-w-0">
                            <span class="block text-[10px] uppercase font-medium tracking-wider text-amber-700 dark:text-amber-400 mb-1">
                              Suggested category
                            </span>
                            <span class="font-medium text-sm text-neutral-900 dark:text-neutral-100">
                              {{ suggestion.categoryName }}
                            </span>
                            <span class="text-[11px] text-neutral-500 ml-2">
                              <!--
                                The source is stated plainly: a learned rule and a provider-produced
                                proposal are different claims, and only one of them is AI.
                              -->
                              {{ suggestion.source === 'RULE' ? 'from a rule you taught' : 'from the AI provider' }}
                              @if (suggestion.reason) {
                                — {{ suggestion.reason }}
                              }
                            </span>
                            @if (suggestion.learned) {
                              <span class="block text-[11px] text-neutral-500 mt-1">
                                Your choice was remembered: “{{ suggestion.learned.keyword }}” will be
                                filed under {{ suggestion.learned.categoryName }} next time.
                              </span>
                            }
                          </div>

                          <div class="flex items-center gap-2 shrink-0">
                            <button
                              type="button"
                              (click)="acceptSuggestion(tx)"
                              [disabled]="applyingSuggestion"
                              class="text-xs font-semibold text-neutral-950 bg-amber-500 hover:bg-amber-600 disabled:opacity-50 px-3 py-1.5 rounded-lg transition-colors cursor-pointer"
                            >
                              {{ applyingSuggestion ? 'Applying…' : 'Use this category' }}
                            </button>
                            <button
                              type="button"
                              (click)="dismissSuggestion()"
                              class="text-xs font-semibold text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 px-2 py-1.5 cursor-pointer"
                            >
                              Keep mine
                            </button>
                          </div>
                        </div>
                      }
                    </td>
                  </tr>
                }
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
  private ruleService = inject(RecurringRuleService);
  private mascotService = inject(MascotService);
  private toast = inject(ToastService);
  private router = inject(Router);
  private cdr = inject(ChangeDetectorRef);
  private categorisationService = inject(CategorisationService);

  categories: Category[] = [];
  filteredCategories: Category[] = [];
  recentList: Transaction[] = [];

  suggestion: CategorySuggestion | null = null;
  suggestingTxId: string | null = null;
  applyingSuggestion = false;

  isSubmitting = false;
  successMessage = '';
  editingTxId: string | null = null;

  txForm = this.fb.group({
    type: ['EXPENSE' as TransactionType, [Validators.required]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    date: [this.today(), [Validators.required]],
    categoryId: ['', [Validators.required]],
    description: ['', [Validators.required, Validators.minLength(2)]],
    repeatFrequency: ['NONE']
  });

  get repeatFrequency(): string {
    return this.txForm.get('repeatFrequency')?.value || 'NONE';
  }

  ngOnInit(): void {
    this.categoryService.getCategories().subscribe(cats => {
      this.categories = cats;
      this.filterCategoriesByType('EXPENSE');
      this.cdr.markForCheck();
    });

    this.loadRecent();
  }

  loadRecent(): void {
    this.txService.getRecentTransactions(10, { from: '2026-08-01', to: '2026-09-30' }).subscribe(list => {
      this.recentList = list;
      this.cdr.markForCheck();
    });
  }

  setType(type: TransactionType): void {
    this.txForm.patchValue({ type });
    this.filterCategoriesByType(type);
  }

  private filterCategoriesByType(type: TransactionType): void {

    this.filteredCategories = this.categories.filter(c => c.type === type && c.isActive !== false);
    if (this.filteredCategories.length > 0 && !this.txForm.get('categoryId')?.value) {
      this.txForm.patchValue({ categoryId: String(this.filteredCategories[0].id) });
    }
  }

  onSubmitTransaction(): void {
    if (this.txForm.invalid) {
      this.txForm.markAllAsTouched();
      return;
    }

    this.isSubmitting = true;
    const val = this.txForm.value;
    const cat = this.categories.find(c => String(c.id) === String(val.categoryId));

    const payload = {
      categoryId: String(val.categoryId),
      amount: Number(val.amount),
      txnDate: val.date as string,
      date: val.date as string,
      description: val.description as string
    };

    if (this.editingTxId) {
      this.txService.updateTransaction(this.editingTxId, payload).subscribe({
        next: () => {
          this.isSubmitting = false;
          this.successMessage = 'Transaction updated!';
          this.editingTxId = null;
          this.resetForm();
          this.loadRecent();
          this.mascotService.onTransactionLogged();
          setTimeout(() => this.successMessage = '', 3500);
        },
        error: (err) => {
          this.isSubmitting = false;
          this.toast.error(err.error?.message || 'Failed to update transaction');
        }
      });
    } else {
      this.txService.addTransaction(payload).subscribe({
        next: () => {
          this.mascotService.onTransactionLogged();

          if (this.repeatFrequency !== 'NONE') {
            this.createRepeatRule();
          } else {
            this.isSubmitting = false;
            this.successMessage = 'Transaction recorded!';
            this.toast.success('Transaction recorded successfully!');
          }
          this.resetForm();
          this.loadRecent();
          setTimeout(() => this.successMessage = '', 3500);
        },
        error: (err) => {
          this.isSubmitting = false;
          this.toast.error(err.error?.message || 'Failed to record transaction');
        }
      });
    }
  }

  private createRepeatRule(): void {
    const val = this.txForm.getRawValue();
    this.ruleService.createRule({
      categoryId: Number(val.categoryId),
      amount: Number(val.amount),
      description: (val.description || '').trim(),
      frequency: this.repeatFrequency as RecurringFrequency,
      startDate: val.date as string
    }).subscribe({
      next: rule => {
        this.isSubmitting = false;
        this.successMessage = 'Transaction recorded and set to repeat!';
        this.toast.success(`Transaction recorded. Repeats ${RecurringRuleService.describeSchedule(rule).toLowerCase()}.`);
      },
      error: (err) => {
        this.isSubmitting = false;
        this.successMessage = 'Transaction recorded!';
        this.toast.warning(
          'The transaction was saved, but the repeat could not be set up' +
          (err.error?.message ? `: ${err.error.message}` : '.') +
          ' You can add it on the Recurring page.'
        );
      }
    });
  }

  suggestCategory(tx: Transaction): void {
    this.suggestingTxId = String(tx.id);
    this.categorisationService.suggestCategory(Number(tx.id)).subscribe({
      next: res => {
        this.suggestingTxId = null;
        this.suggestion = res;
        this.cdr.markForCheck();
      },
      error: err => {
        this.suggestingTxId = null;
        this.toast.error(err.error?.message || 'Could not ask for a category suggestion.');
        this.cdr.markForCheck();
      }
    });
  }

  acceptSuggestion(tx: Transaction): void {
    if (!this.suggestion?.categoryId) return;
    this.applyingSuggestion = true;

    this.txService
      .updateTransaction(tx.id, {
        categoryId: this.suggestion.categoryId,
        amount: tx.amount,
        txnDate: tx.date || tx.txnDate,
        description: tx.description
      })
      .subscribe({
        next: () => {
          this.applyingSuggestion = false;
          const name = this.suggestion?.categoryName;
          this.suggestion = null;
          this.toast.success(`Filed under ${name}.`);
          this.loadRecent();
          this.cdr.markForCheck();
        },
        error: err => {
          this.applyingSuggestion = false;
          this.toast.error(err.error?.message || 'Could not change the category.');
          this.cdr.markForCheck();
        }
      });
  }

  dismissSuggestion(): void {
    this.suggestion = null;
    this.cdr.markForCheck();
  }

  startEdit(tx: Transaction): void {
    this.editingTxId = String(tx.id);
    this.txForm.patchValue({
      type: tx.type,
      amount: tx.amount,
      date: tx.date || tx.txnDate,
      categoryId: String(tx.categoryId),
      description: tx.description,

      repeatFrequency: 'NONE'
    });
    this.filterCategoriesByType(tx.type);
    window.scrollTo({ top: 300, behavior: 'smooth' });
  }

  cancelEdit(): void {
    this.editingTxId = null;
    this.resetForm();
  }

  async onDelete(id: string | number): Promise<void> {
    const ok = await this.toast.confirm(
      'Delete Transaction',
      'Move this entry to the trash? It stops counting toward your balances and reports.',
      'Delete',
      'Cancel',
      true
    );
    if (!ok) return;

    this.txService.deleteTransaction(id).subscribe({
      next: () => {
        this.toast.success('Transaction deleted.');
        this.loadRecent();
      },
      error: (err) => {
        if (err.error?.errorCode === 'TRANSACTION_ALREADY_DELETED') {
          this.toast.warning('That entry was already deleted. Refreshing the list.');
          this.loadRecent();
          return;
        }
        this.toast.error(err.error?.message || 'Failed to delete transaction');
      }
    });
  }

  private resetForm(): void {
    this.txForm.reset({
      type: 'EXPENSE',
      amount: null,
      date: this.today(),
      categoryId: this.filteredCategories[0]?.id ? String(this.filteredCategories[0].id) : '',
      repeatFrequency: 'NONE'
    });
  }

  private today(): string {
    return new Date().toLocaleDateString('sv-SE');
  }
}
