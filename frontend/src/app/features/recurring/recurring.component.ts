import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryTagComponent } from '../../shared/components/category-tag/category-tag.component';
import { RecurringRuleService } from '../../core/services/recurring-rule.service';
import { CategoryService } from '../../core/services/category.service';
import { ToastService } from '../../core/services/toast.service';
import { Category, CategoryType } from '../../core/models/category.model';
import {
  RecurringRule,
  RecurringStatus,
  CreateRecurringRuleRequest
} from '../../core/models/recurring-rule.model';

@Component({
  selector: 'app-recurring',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    IconComponent,
    CategoryTagComponent
  ],
  template: `
    <div class="max-w-6xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <!-- Header -->
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Recurring Rules
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Repeating income and expenses — rent, allowance, subscriptions. The scheduler posts
            them for you once a day.
          </p>
        </div>
        <button
          type="button"
          (click)="openCreate()"
          class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
        >
          <app-icon name="plus" [size]="15" strokeWidth="2"></app-icon>
          <span>New Rule</span>
        </button>
      </div>

      <!-- Notice about how posting works (the module has no "run now" endpoint by design) -->
      <div class="p-3.5 bg-sky-50 dark:bg-sky-950/40 border border-sky-200 dark:border-sky-900/60 text-sky-900 dark:text-sky-200 text-xs font-medium rounded-lg flex items-start gap-2">
        <app-icon name="info" [size]="15" strokeWidth="1.5"></app-icon>
        <span>
          A rule posts nothing the moment you create it. The scheduler runs once a day and posts
          every period whose date has passed, so new transactions appear on their own.
        </span>
      </div>

      <!-- Rules table -->
      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Loading your rules…
        </div>
      } @else if (rules.length === 0) {
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-amber-500/10 border border-amber-500/25 flex items-center justify-center text-amber-600 dark:text-amber-400 mx-auto mb-3 shadow-xs">
            <app-icon name="repeat" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">No recurring rules yet</h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
            Create one for anything that repeats — rent, your allowance, a subscription.
          </p>
        </div>
      } @else {
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="overflow-x-auto">
            <table class="w-full text-left text-xs border-collapse">
              <thead>
                <tr class="border-b border-neutral-200 dark:border-neutral-800 text-neutral-500 font-medium uppercase text-[11px]">
                  <th class="py-2 pr-3">Category</th>
                  <th class="py-2 pr-3">Schedule</th>
                  <th class="py-2 pr-3">Amount</th>
                  <th class="py-2 pr-3">Next Run</th>
                  <th class="py-2 pr-3">Ends</th>
                  <th class="py-2 pr-3">Status</th>
                  <th class="py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-neutral-100 dark:divide-neutral-800/70">
                @for (rule of rules; track rule.id) {
                  <tr class="hover:bg-neutral-50 dark:hover:bg-neutral-800/40 transition-colors">
                    <td class="py-2.5 pr-3">
                      <app-category-tag
                        [name]="rule.categoryName"
                        [icon]="rule.categoryIcon"
                        [color]="rule.categoryColor"
                      ></app-category-tag>
                    </td>
                    <td class="py-2.5 pr-3 font-medium text-neutral-700 dark:text-neutral-300">
                      {{ describe(rule) }}
                    </td>
                    <td class="py-2.5 pr-3 font-semibold tabular-nums"
                        [class.text-emerald-600]="isIncome(rule)"
                        [class.text-rose-600]="!isIncome(rule)">
                      {{ isIncome(rule) ? '+' : '−' }}\${{ rule.amount.toFixed(2) }}
                    </td>
                    <td class="py-2.5 pr-3 text-neutral-600 dark:text-neutral-400 tabular-nums">
                      {{ rule.nextRunDate }}
                    </td>
                    <td class="py-2.5 pr-3 text-neutral-600 dark:text-neutral-400 tabular-nums">
                      {{ rule.endDate || '—' }}
                    </td>
                    <td class="py-2.5 pr-3">
                      <span
                        class="px-2 py-0.5 rounded-full text-[10px] font-semibold border"
                        [class.bg-emerald-50]="rule.status === 'ACTIVE'"
                        [class.text-emerald-700]="rule.status === 'ACTIVE'"
                        [class.border-emerald-200]="rule.status === 'ACTIVE'"
                        [class.bg-amber-50]="rule.status === 'PAUSED'"
                        [class.text-amber-700]="rule.status === 'PAUSED'"
                        [class.border-amber-200]="rule.status === 'PAUSED'"
                        [class.bg-neutral-100]="rule.status === 'ENDED'"
                        [class.text-neutral-600]="rule.status === 'ENDED'"
                        [class.border-neutral-200]="rule.status === 'ENDED'"
                      >{{ rule.status }}</span>
                    </td>
                    <td class="py-2.5 text-right whitespace-nowrap">
                      <button
                        type="button"
                        (click)="openEdit(rule)"
                        [disabled]="rule.status === 'ENDED'"
                        class="text-amber-600 hover:text-amber-800 dark:text-amber-400 font-semibold disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer mr-3"
                        [title]="rule.status === 'ENDED' ? 'An ended rule cannot be edited' : 'Edit this rule'"
                      >Edit</button>

                      @if (rule.status === 'ACTIVE') {
                        <button type="button" (click)="setStatus(rule, 'PAUSED')"
                          class="text-neutral-600 hover:text-neutral-800 dark:text-neutral-400 font-semibold cursor-pointer mr-3">Pause</button>
                      }
                      @if (rule.status === 'PAUSED') {
                        <button type="button" (click)="setStatus(rule, 'ACTIVE')"
                          class="text-emerald-600 hover:text-emerald-800 font-semibold cursor-pointer mr-3">Resume</button>
                      }
                      @if (rule.status !== 'ENDED' && rule.lastRunDate) {
                        <button type="button" (click)="endRule(rule)"
                          class="text-rose-600 hover:text-rose-800 font-semibold cursor-pointer mr-3">End</button>
                      }
                      @if (!rule.lastRunDate) {
                        <button type="button" (click)="deleteRule(rule)"
                          class="text-rose-600 hover:text-rose-800 font-semibold cursor-pointer">Delete</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- Create / Edit modal -->
      @if (showModal) {
        <div class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-neutral-900/50 backdrop-blur-sm">
          <div class="w-full max-w-lg bg-white dark:bg-neutral-900 rounded-xl border border-neutral-200 dark:border-neutral-800 shadow-subtle-lg p-6">
            <div class="flex items-center justify-between mb-4 pb-3 border-b border-neutral-200 dark:border-neutral-800">
              <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50">
                {{ editingId ? 'Edit Recurring Rule' : 'New Recurring Rule' }}
              </h3>
              <button type="button" (click)="closeModal()" class="text-neutral-400 hover:text-neutral-700 cursor-pointer">✕</button>
            </div>

            @if (modalError) {
              <div class="mb-3 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-900/60 text-rose-800 dark:text-rose-200 text-xs font-medium rounded-lg">
                {{ modalError }}
              </div>
            }

            <!-- A retired category freezes the rule: only the category may be changed. -->
            @if (categoryRetired) {
              <div class="mb-3 p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-900/60 text-amber-900 dark:text-amber-200 text-xs font-medium rounded-lg">
                This rule's category has been retired, so no field can be saved except the category.
                Move it to another category to make it editable again.
              </div>
            }

            <form [formGroup]="ruleForm" (ngSubmit)="onSubmit()" class="space-y-3.5">
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Flow Type *
                  </label>
                  <select [ngModel]="flowType" (ngModelChange)="setFlowType($event)"
                          [ngModelOptions]="{standalone: true}"
                          class="input-brutal">
                    <option value="EXPENSE">Expense</option>
                    <option value="INCOME">Income</option>
                  </select>
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Category *
                  </label>
                  <select formControlName="categoryId" class="input-brutal">
                    @for (cat of filteredCategories; track cat.id) {
                      <option [value]="cat.id">{{ cat.name }} ({{ cat.type }})</option>
                    }
                  </select>
                </div>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Amount *
                  </label>
                  <input type="number" step="0.01" min="0.01" formControlName="amount"
                         placeholder="200.00" class="input-brutal font-medium" />
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Frequency *
                  </label>
                  <select formControlName="frequency" class="input-brutal">
                    <option value="DAILY">Daily</option>
                    <option value="WEEKLY">Weekly</option>
                    <option value="MONTHLY">Monthly</option>
                    <option value="QUARTERLY">Quarterly</option>
                    <option value="YEARLY">Yearly</option>
                  </select>
                </div>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Every N periods
                  </label>
                  <input type="number" min="1" max="999" formControlName="intervalCount" class="input-brutal" />
                  <p class="text-[10px] text-neutral-400 mt-0.5">1 = every period. 2 with Monthly = every other month.</p>
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Starts On *
                  </label>
                  <input type="date" formControlName="startDate" class="input-brutal"
                         [readonly]="!!editingId"
                         [class.opacity-60]="!!editingId" />
                  @if (editingId) {
                    <p class="text-[10px] text-neutral-400 mt-0.5">The start date is the rule's origin and cannot change.</p>
                  }
                </div>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    End Date (optional)
                  </label>
                  <input type="date" formControlName="endDate" class="input-brutal" />
                  <p class="text-[10px] text-neutral-400 mt-0.5">Leave empty to run until you end it.</p>
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Next Run (optional)
                  </label>
                  <input type="date" formControlName="nextRunDate" class="input-brutal" />
                  <p class="text-[10px] text-neutral-400 mt-0.5">Moves the rule onto a different day.</p>
                </div>
              </div>

              <div>
                <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                  Description
                </label>
                <input type="text" formControlName="description"
                       placeholder="Monthly allowance" class="input-brutal" />
              </div>

              <div class="flex items-center gap-3 pt-2">
                <button type="submit" [disabled]="ruleForm.invalid || isSaving || categoryRetiredOnlyCategoryInvalid()"
                        class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-5 rounded-lg text-sm shadow-xs transition-colors cursor-pointer">
                  {{ isSaving ? 'Saving…' : (editingId ? 'Save Changes' : 'Create Rule') }}
                </button>
                <button type="button" (click)="closeModal()"
                        class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-sm transition-colors cursor-pointer">
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      }
    </div>
  `
})
export class RecurringComponent implements OnInit {
  private fb = inject(FormBuilder);
  private ruleService = inject(RecurringRuleService);
  private categoryService = inject(CategoryService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  rules: RecurringRule[] = [];
  categories: Category[] = [];
  filteredCategories: Category[] = [];

  isLoading = true;
  isSaving = false;
  showModal = false;
  editingId: number | null = null;
  modalError = '';
  categoryRetired = false;
  flowType: CategoryType = 'EXPENSE';

  ruleForm = this.fb.group({
    categoryId: ['', [Validators.required]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    frequency: ['MONTHLY', [Validators.required]],
    intervalCount: [1, [Validators.min(1), Validators.max(999)]],
    startDate: ['', [Validators.required]],
    endDate: [''],
    nextRunDate: [''],
    description: ['']
  });

  ngOnInit(): void {
    this.categoryService.getCategories().subscribe(cats => {
      this.categories = cats;
      this.filterCategories('EXPENSE');
      this.cdr.markForCheck();
    });
    this.loadRules();
  }

  loadRules(): void {
    this.ruleService.listRules().subscribe({
      next: rules => {
        this.rules = rules;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.toast.error('Could not load your recurring rules.');
        this.cdr.markForCheck();
      }
    });
  }

  describe(rule: RecurringRule): string {
    return RecurringRuleService.describeSchedule(rule);
  }

  isIncome(rule: RecurringRule): boolean {
    return this.categories.find(c => String(c.id) === String(rule.categoryId))?.type === 'INCOME';
  }

  setFlowType(type: CategoryType): void {
    this.flowType = type;
    this.filterCategories(type);
  }

  private filterCategories(type: CategoryType): void {

    this.filteredCategories = this.categories.filter(c => c.type === type && c.isActive !== false);
    const current = this.ruleForm.get('categoryId')?.value;
    const stillValid = this.filteredCategories.some(c => String(c.id) === String(current));
    if (!stillValid && this.filteredCategories.length > 0) {
      this.ruleForm.patchValue({ categoryId: String(this.filteredCategories[0].id) });
    }
  }

  openCreate(): void {
    this.editingId = null;
    this.modalError = '';
    this.categoryRetired = false;
    this.flowType = 'EXPENSE';
    this.filterCategories('EXPENSE');
    this.ruleForm.reset({
      categoryId: this.filteredCategories[0]?.id ? String(this.filteredCategories[0].id) : '',
      amount: null,
      frequency: 'MONTHLY',
      intervalCount: 1,
      startDate: this.today(),
      endDate: '',
      nextRunDate: '',
      description: ''
    });
    this.ruleForm.get('startDate')?.enable();
    this.showModal = true;
  }

  openEdit(rule: RecurringRule): void {
    this.editingId = rule.id;
    this.modalError = '';
    const cat = this.categories.find(c => String(c.id) === String(rule.categoryId));
    this.flowType = cat?.type ?? 'EXPENSE';
    this.filterCategories(this.flowType);

    this.categoryRetired = cat?.isActive === false;

    this.ruleForm.reset({
      categoryId: String(rule.categoryId),
      amount: rule.amount,
      frequency: rule.frequency,
      intervalCount: rule.intervalCount,
      startDate: rule.startDate,
      endDate: rule.endDate ?? '',
      nextRunDate: rule.nextRunDate ?? '',
      description: rule.description ?? ''
    });

    this.ruleForm.get('startDate')?.disable();
    this.showModal = true;
  }

  categoryRetiredOnlyCategoryInvalid(): boolean {
    if (!this.editingId || !this.categoryRetired) return false;
    const original = this.rules.find(r => r.id === this.editingId);
    return !original || String(this.ruleForm.get('categoryId')?.value) === String(original.categoryId);
  }

  closeModal(): void {
    this.showModal = false;
    this.editingId = null;
    this.modalError = '';
    this.categoryRetired = false;
  }

  onSubmit(): void {
    if (this.ruleForm.invalid) {
      this.ruleForm.markAllAsTouched();
      return;
    }

    this.isSaving = true;
    this.modalError = '';

    if (this.editingId) {
      this.saveEdit(this.editingId);
    } else {
      this.saveCreate();
    }
  }

  private saveCreate(): void {

    const val = this.ruleForm.getRawValue();
    const endDate = (val.endDate || '').trim();
    const nextRunDate = (val.nextRunDate || '').trim();

    const body: CreateRecurringRuleRequest = {
      categoryId: Number(val.categoryId),
      amount: Number(val.amount),
      frequency: val.frequency as any,
      intervalCount: Number(val.intervalCount ?? 1),
      startDate: val.startDate as string,
      description: (val.description || '').trim()
    };

    if (endDate) body.endDate = endDate;
    if (nextRunDate) body.nextRunDate = nextRunDate;

    this.ruleService.createRule(body).subscribe({
      next: created => {
        this.isSaving = false;
        this.closeModal();
        this.toast.success(`Rule created. First run ${created.nextRunDate}.`);
        this.loadRules();
      },
      error: err => {
        this.isSaving = false;
        this.modalError = err.error?.message || 'Could not create the rule.';
        this.cdr.markForCheck();
      }
    });
  }

  private saveEdit(id: number): void {
    const val = this.ruleForm.getRawValue();
    const body: Record<string, unknown> = {};

    if (this.ruleForm.get('categoryId')?.dirty) body['categoryId'] = Number(val.categoryId);
    if (this.ruleForm.get('amount')?.dirty) body['amount'] = Number(val.amount);
    if (this.ruleForm.get('frequency')?.dirty) body['frequency'] = val.frequency;
    if (this.ruleForm.get('intervalCount')?.dirty) body['intervalCount'] = Number(val.intervalCount);
    if (this.ruleForm.get('description')?.dirty) body['description'] = (val.description || '').trim();
    if (this.ruleForm.get('endDate')?.dirty) body['endDate'] = (val.endDate || '').trim();
    if (this.ruleForm.get('nextRunDate')?.dirty) body['nextRunDate'] = (val.nextRunDate || '').trim();

    if (Object.keys(body).length === 0) {
      this.isSaving = false;
      this.closeModal();
      return;
    }

    this.ruleService.updateRule(id, body as any).subscribe({
      next: () => {
        this.isSaving = false;
        this.closeModal();
        this.toast.success('Rule updated.');
        this.loadRules();
      },
      error: err => {
        this.isSaving = false;

        if (RecurringRuleService.isEndedError(err)) {
          this.modalError = 'This rule has ended and cannot be changed. Create a new one instead.';
        } else {
          this.modalError = err.error?.message || 'Could not update the rule.';
        }
        this.cdr.markForCheck();
      }
    });
  }

  setStatus(rule: RecurringRule, status: RecurringStatus): void {
    this.ruleService.setStatus(rule.id, status).subscribe({
      next: updated => {
        this.rules = this.rules.map(r => (r.id === updated.id ? updated : r));
        this.toast.success(status === 'PAUSED' ? 'Rule paused.' : 'Rule resumed.');
        this.cdr.markForCheck();
      },
      error: err => {
        if (RecurringRuleService.isEndedError(err)) {
          this.toast.error('This rule has ended and cannot be restarted.');
        } else {
          this.toast.error(err.error?.message || 'Could not change the rule status.');
        }
      }
    });
  }

  async endRule(rule: RecurringRule): Promise<void> {
    const ok = await this.toast.confirm(
      'End Recurring Rule',
      'Ending stops this rule for good — an ended rule cannot be restarted. The transactions it already posted are kept.',
      'End Rule',
      'Cancel',
      true
    );
    if (!ok) return;
    this.setStatus(rule, 'ENDED');
  }

  async deleteRule(rule: RecurringRule): Promise<void> {
    const ok = await this.toast.confirm(
      'Delete Recurring Rule',
      `Remove the "${rule.categoryName}" rule? This rule has never posted, so nothing will be lost.`,
      'Delete',
      'Cancel',
      true
    );
    if (!ok) return;

    this.ruleService.deleteRule(rule.id).subscribe({
      next: () => {
        this.rules = this.rules.filter(r => r.id !== rule.id);
        this.toast.success('Rule deleted.');
        this.cdr.markForCheck();
      },
      error: err => {

        if (RecurringRuleService.isInUseError(err)) {
          this.toast.warning('This rule has already posted transactions, so it cannot be deleted. End it instead.');
        } else {
          this.toast.error(err.error?.message || 'Could not delete the rule.');
        }
      }
    });
  }

  private today(): string {
    return new Date().toLocaleDateString('sv-SE');
  }
}
