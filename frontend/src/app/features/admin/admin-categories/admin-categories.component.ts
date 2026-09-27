import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, of, catchError } from 'rxjs';
import { CategoryService } from '../../../core/services/category.service';
import {
  AdminService,
  TipTemplate,
  TipConditionType,
  TIP_CONDITION_TYPES
} from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { Category } from '../../../core/models/category.model';
import { CategoryIconComponent } from '../../../shared/components/category-icon/category-icon.component';

@Component({
  selector: 'app-admin-categories',
  standalone: true,
  imports: [CommonModule, FormsModule, CategoryIconComponent],
  template: `
    <div class="space-y-8">

      <!-- Page Header -->
      <div class="pb-4 border-b border-slate-200 dark:border-neutral-800">
        <h2 class="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
          System Taxonomies & Advisor Templates
        </h2>
        <p class="text-xs text-[var(--color-text-muted)]">
          Govern campus default financial categories and institutional advice broadcast templates.
        </p>
      </div>

      <!-- Section 1: Default System Categories Table -->
      <div class="space-y-4">
        <div class="flex items-center justify-between">
          <div>
            <h3 class="font-bold text-base text-slate-900 dark:text-white">
              Default Campus Expense & Income Categories
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">Shared baseline categories offered to every student, alongside their own</p>
          </div>

          <button
            type="button"
            (click)="openAddCategoryModal()"
            class="px-3.5 py-1.5 bg-slate-900 hover:bg-slate-800 dark:bg-slate-100 dark:hover:bg-white text-white dark:text-slate-900 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition-all shadow-xs cursor-pointer"
          >
            <span>+ Add Default Category</span>
          </button>
        </div>

        <div class="bg-white dark:bg-neutral-900 rounded-lg border border-slate-200 dark:border-neutral-800 overflow-hidden shadow-xs">
          <table class="w-full text-left text-xs border-collapse">
            <thead>
              <tr class="table-head-row bg-slate-50 dark:bg-neutral-800 border-b border-slate-200 dark:border-neutral-800 text-[var(--color-text-muted)] uppercase font-semibold text-[10px]">
                <th class="p-3 w-1/4">Category Name</th>
                <th class="p-3 w-28">Type</th>
                <th class="p-3">Description</th>
                <th class="p-3 w-32 text-center">Governance State</th>
                <th class="p-3 w-36 text-right">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 dark:divide-neutral-800">
              @for (cat of defaultCategories; track cat.id) {
                <tr class="hover:bg-slate-50/60 dark:hover:bg-neutral-800/40">
                  <td class="p-3">
                    <div class="flex items-center gap-2.5">
                      <app-category-icon
                        [name]="cat.name"
                        [icon]="cat.icon"
                        [color]="cat.color"
                        size="sm"
                      ></app-category-icon>
                      <span class="font-medium text-xs" [class.text-slate-900]="cat.isActive" [class.dark:text-white]="cat.isActive" [class.text-slate-400]="!cat.isActive">{{ cat.name }}</span>
                    </div>
                  </td>
                  <td class="p-3">
                    <span class="px-2 py-0.5 rounded text-[10px] font-semibold" [class.bg-emerald-50]="cat.type === 'INCOME'" [class.text-emerald-700]="cat.type === 'INCOME'" [class.dark:bg-emerald-950/40]="cat.type === 'INCOME'" [class.dark:text-emerald-300]="cat.type === 'INCOME'" [class.bg-amber-50]="cat.type === 'EXPENSE'" [class.text-amber-700]="cat.type === 'EXPENSE'" [class.dark:bg-amber-950/40]="cat.type === 'EXPENSE'" [class.dark:text-amber-300]="cat.type === 'EXPENSE'">
                      {{ cat.type }}
                    </span>
                  </td>
                  <td class="p-3 text-slate-600 dark:text-neutral-400">
                    {{ cat.description || '—' }}
                  </td>
                  <td class="p-3 text-center">
                    <button
                      type="button"
                      (click)="toggleCategoryStatus(cat)"
                      [attr.aria-label]="(cat.isActive ? 'Retire ' : 'Restore ') + cat.name"
                      class="px-2 py-0.5 rounded text-[10px] font-semibold border transition-colors cursor-pointer"
                      [class.bg-emerald-50]="cat.isActive"
                      [class.text-emerald-700]="cat.isActive"
                      [class.border-emerald-200]="cat.isActive"
                      [class.bg-slate-100]="!cat.isActive"
                      [class.dark:bg-neutral-800]="!cat.isActive"
                      [class.text-slate-500]="!cat.isActive"
                      [class.border-slate-200]="!cat.isActive"
                    >
                      {{ cat.isActive ? 'Active' : 'Retired' }}
                    </button>
                  </td>
                  <td class="p-3 text-right whitespace-nowrap">
                    <button
                      type="button"
                      (click)="openEditCategoryModal(cat)"
                      class="text-slate-700 dark:text-neutral-300 hover:text-slate-900 dark:hover:text-white text-xs font-semibold underline mr-3 cursor-pointer"
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      (click)="toggleCategoryStatus(cat)"
                      class="text-rose-600 hover:text-rose-800 text-xs font-semibold underline cursor-pointer"
                    >
                      {{ cat.isActive ? 'Retire' : 'Restore' }}
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <!-- Section 2: Financial Tip & Broadcast Templates -->
      <div class="space-y-4 pt-4 border-t border-slate-200 dark:border-neutral-800">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 class="font-bold text-base text-slate-900 dark:text-white">
              AI Advisor Announcement & Tip Templates
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">Structured tips surfaced inside student feeds and monthly insights</p>
          </div>

          <button
            type="button"
            (click)="openAddTipModal()"
            class="px-3.5 py-1.5 bg-amber-500 hover:bg-amber-600 active:scale-[0.98] text-neutral-950 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition-all shadow-xs cursor-pointer"
          >
            <span>+ Create Tip Template</span>
          </button>
        </div>

        <div class="bg-white dark:bg-neutral-900 rounded-lg border border-slate-200 dark:border-neutral-800 overflow-hidden shadow-xs">
          <table class="w-full text-left text-xs border-collapse">
            <thead>
              <tr class="table-head-row bg-slate-50 dark:bg-neutral-800 border-b border-slate-200 dark:border-neutral-800 text-[var(--color-text-muted)] uppercase font-semibold text-[10px]">
                <th class="p-3">Tip Title</th>
                <th class="p-3">Code</th>
                <th class="p-3">Condition</th>
                <th class="p-3">Narrative Body</th>
                <th class="p-3">Status</th>
                <th class="p-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 dark:divide-neutral-800">
              @for (tip of tipTemplates; track tip.id) {
                <tr class="hover:bg-slate-50/60 dark:hover:bg-neutral-800/40">
                  <td class="p-3 font-bold text-slate-900 dark:text-white max-w-[200px] truncate">
                    {{ tip.title }}
                  </td>
                  <td class="p-3 text-[11px] font-mono text-slate-500 dark:text-neutral-400">
                    {{ tip.code || '—' }}
                  </td>
                  <td class="p-3">
                    <!-- conditionType is the column that ties a template to the rule that fires
                         it, so it is shown as itself rather than as a category badge. -->
                    <span class="px-2 py-0.5 rounded text-[10px] font-semibold bg-slate-100 dark:bg-neutral-800 text-slate-600 dark:text-neutral-300 border border-slate-200 dark:border-neutral-700">
                      {{ tip.categoryTag }}
                    </span>
                  </td>
                  <td class="p-3 text-[var(--color-text-muted)] max-w-[280px] truncate">
                    {{ tip.content }}
                  </td>
                  <td class="p-3">
                    <button
                      type="button"
                      (click)="toggleTipStatus(tip)"
                      class="px-2 py-0.5 rounded text-[10px] font-semibold transition-colors"
                      [class.bg-emerald-50]="tip.isActive"
                      [class.text-emerald-700]="tip.isActive"
                      [class.border]="true"
                      [class.border-emerald-200]="tip.isActive"
                      [class.bg-slate-100]="!tip.isActive"
                      [class.text-slate-500]="!tip.isActive"
                    >
                      {{ tip.isActive ? 'Active' : 'Draft' }}
                    </button>
                  </td>
                  <td class="p-3 text-right">
                    <button
                      type="button"
                      (click)="deleteTip(tip.id)"
                      class="text-rose-600 hover:text-rose-800 text-xs font-semibold underline"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <!-- Add / Edit Default Category Modal -->
      @if (showCategoryModal) {
        <div class="fixed inset-0 z-50 bg-black/40 backdrop-blur-xs flex items-center justify-center p-4">
          <div class="bg-white dark:bg-neutral-900 rounded-xl border border-slate-200 dark:border-neutral-800 max-w-lg w-full p-6 shadow-xl space-y-4">
            <div class="flex items-center justify-between pb-3 border-b border-slate-100 dark:border-neutral-800">
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                {{ editingCategoryId !== null ? 'Edit Default Category' : 'New Default Category' }}
              </h3>
              <button (click)="showCategoryModal = false" class="text-slate-400 hover:text-slate-700">✕</button>
            </div>

            <div class="space-y-3">
              <div>
                <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                  Category Name
                </label>
                <input
                  type="text"
                  [(ngModel)]="catName"
                  placeholder="e.g. Printing & Course Materials"
                  class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                />
              </div>

              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Type
                  </label>
                  <select [(ngModel)]="catType" class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md">
                    <option value="EXPENSE">EXPENSE</option>
                    <option value="INCOME">INCOME</option>
                  </select>
                </div>
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Icon Name
                  </label>
                  <input
                    type="text"
                    [(ngModel)]="catIcon"
                    placeholder="e.g. printer"
                    class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                  />
                </div>
              </div>

              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Colour
                  </label>
                  <input
                    type="text"
                    [(ngModel)]="catColor"
                    placeholder="#F59E0B"
                    class="w-full px-3 py-2 text-xs font-mono bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                  />
                  <p class="text-[10px] text-[var(--color-text-muted)] mt-1">
                    Six-digit hex, or leave blank.
                  </p>
                </div>
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Description
                  </label>
                  <input
                    type="text"
                    [(ngModel)]="catDescription"
                    placeholder="Optional note"
                    class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                  />
                </div>
              </div>

              <div class="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  (click)="showCategoryModal = false"
                  class="px-3.5 py-2 rounded-lg text-xs font-medium text-slate-700 dark:text-neutral-300 border border-slate-300 dark:border-neutral-700 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  (click)="saveCategory()"
                  [disabled]="!isCategoryFormValid"
                  class="px-4 py-2 bg-amber-500 text-neutral-950 rounded-lg text-xs font-semibold hover:bg-amber-600 disabled:opacity-50 transition-colors shadow-xs cursor-pointer"
                >
                  {{ editingCategoryId !== null ? 'Save Changes' : 'Create Category' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      }

      <!-- Add Tip Template Modal -->
      @if (showTipModal) {
        <div class="fixed inset-0 z-50 bg-black/40 backdrop-blur-xs flex items-center justify-center p-4">
          <div class="bg-white dark:bg-neutral-900 rounded-xl border border-slate-200 dark:border-neutral-800 max-w-lg w-full p-6 shadow-xl space-y-4">
            <div class="flex items-center justify-between pb-3 border-b border-slate-100 dark:border-neutral-800">
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                New Broadcast Tip Template
              </h3>
              <button (click)="showTipModal = false" class="text-slate-400 hover:text-slate-700">✕</button>
            </div>

            <div class="space-y-3">
              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Template Code
                  </label>
                  <input
                    type="text"
                    [(ngModel)]="newTipCode"
                    placeholder="e.g. OFF_PEAK_DINING"
                    class="w-full px-3 py-2 text-xs font-mono bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                  />
                  <!-- code is required by the contract and immutable once created, so the form
                       collects it rather than deriving one. Letters, digits and underscores. -->
                  <p class="text-[10px] text-[var(--color-text-muted)] mt-1">
                    Letters, digits and underscores. Cannot be changed afterwards.
                  </p>
                </div>
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Condition
                  </label>
                  <select [(ngModel)]="newTipCondition" class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md">
                    @for (c of conditionTypes; track c) {
                      <option [value]="c">{{ c }}</option>
                    }
                  </select>
                  <!-- Which rule the template fires on. GENERIC always applies; every other
                       value is a condition sp_generate_tips evaluates against the student's figures. -->
                  <p class="text-[10px] text-[var(--color-text-muted)] mt-1">
                    Decides which rule renders this advice.
                  </p>
                </div>
              </div>

              <div>
                <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                  Tip Headline
                </label>
                <input
                  type="text"
                  [(ngModel)]="newTipTitle"
                  placeholder="e.g. Off-Peak Dining Hall Bonus Points"
                  class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                />
              </div>

              <div>
                <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                  Tip Narrative & Advice
                </label>
                <textarea
                  [(ngModel)]="newTipContent"
                  rows="3"
                  placeholder="Clear, student-friendly actionable saving tip..."
                  class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                ></textarea>
              </div>

              <div class="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  (click)="showTipModal = false"
                  class="px-3.5 py-2 rounded-lg text-xs font-medium text-slate-700 dark:text-neutral-300 border border-slate-300 dark:border-neutral-700 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  (click)="saveTip()"
                  [disabled]="!newTipCode.trim() || !newTipTitle.trim() || !newTipContent.trim()"
                  class="px-4 py-2 bg-amber-500 text-neutral-950 rounded-lg text-xs font-semibold hover:bg-amber-600 disabled:opacity-50 transition-colors shadow-xs cursor-pointer"
                >
                  Publish Template
                </button>
              </div>
            </div>
          </div>
        </div>
      }

    </div>
  `
})
export class AdminCategoriesComponent implements OnInit {
  private adminService = inject(AdminService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  defaultCategories: Category[] = [];
  tipTemplates: TipTemplate[] = [];
  isLoading = false;

  showTipModal = false;
  newTipCode = '';
  newTipTitle = '';
  newTipCondition: TipConditionType = 'GENERIC';
  newTipContent = '';

  /** The seven values `tip_templates.condition_type` accepts, straight from the backend enum. */
  readonly conditionTypes = TIP_CONDITION_TYPES;

  /** Editing an existing default category reuses one modal; null means "create a new one". */
  showCategoryModal = false;
  editingCategoryId: number | string | null = null;
  catName = '';
  catType: 'INCOME' | 'EXPENSE' = 'EXPENSE';
  catIcon = '';
  catColor = '';
  catDescription = '';
  catSortOrder = 0;

  /** The contract refuses a colour that is not `#RRGGBB`, so the button stays off until it is. */
  get isCategoryFormValid(): boolean {
    const colour = this.catColor.trim();
    return this.catName.trim().length > 0 && (colour === '' || /^#[0-9A-Fa-f]{6}$/.test(colour));
  }

  openAddCategoryModal(): void {
    this.editingCategoryId = null;
    this.catName = '';
    this.catType = 'EXPENSE';
    this.catIcon = '';
    this.catColor = '';
    this.catDescription = '';
    this.catSortOrder = 0;
    this.showCategoryModal = true;
  }

  openEditCategoryModal(cat: Category): void {
    this.editingCategoryId = cat.id;
    this.catName = cat.name;
    this.catType = cat.type;
    this.catIcon = cat.icon === 'tag' ? '' : cat.icon;
    this.catColor = cat.color === '#EAB308' ? '' : cat.color;
    this.catDescription = cat.description ?? '';
    this.catSortOrder = 0;
    this.showCategoryModal = true;
  }

  saveCategory(): void {
    const name = this.catName.trim();
    if (!name) return;

    // Only non-empty optional fields are sent for icon/colour, and only when they differ from what
    // the row already holds: `null`/absent means "unchanged" and `""` means "clear", so sending a
    // defaulted value on every edit would silently overwrite the row's own icon and colour.
    const optional: { icon?: string; color?: string } = {};
    if (this.catIcon.trim()) optional.icon = this.catIcon.trim();
    if (this.catColor.trim()) optional.color = this.catColor.trim();

    const editing = this.editingCategoryId !== null;
    const request$ = editing
      ? this.adminService.updateDefaultCategory(this.editingCategoryId!, {
          name,
          type: this.catType,
          ...optional,
          // The editor shows an empty description as an empty box; `""` is how the contract clears
          // a nullable column, so an emptied field is sent rather than dropped.
          description: this.catDescription.trim()
        })
      : this.adminService.createDefaultCategory({
          name,
          type: this.catType,
          ...optional,
          description: this.catDescription.trim() || undefined,
          sortOrder: this.catSortOrder
        });

    request$.subscribe({
      next: () => {
        this.showCategoryModal = false;
        this.toast.success(editing ? 'Default category updated.' : 'Default category created.');
        this.reloadCategories();
      },
      error: (err) => {
        // 409 carries a specific errorCode; the server's message names the field that clashed.
        this.toast.error(err.error?.message || (editing
          ? 'Failed to update default category'
          : 'Failed to create default category'));
      }
    });
  }

  toggleCategoryStatus(cat: Category): void {
    const next = !cat.isActive;
    this.adminService.setDefaultCategoryStatus(cat.id, next).subscribe({
      next: (updated) => {
        cat.isActive = updated.isActive;
        this.toast.success(`${cat.name} ${updated.isActive ? 'restored' : 'retired'}.`);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.toast.error(err.error?.message || 'Failed to change the category status');
      }
    });
  }

  private reloadCategories(): void {
    this.adminService.getDefaultCategories().subscribe(list => {
      this.defaultCategories = list;
      this.cdr.markForCheck();
    });
  }

  ngOnInit(): void {
    this.isLoading = true;
    forkJoin({
      categories: this.adminService.getDefaultCategories().pipe(
        catchError((err) => {
          this.toast.error(err.error?.message || 'Failed to load default categories');
          return of([]);
        })
      ),
      tips: this.adminService.getTipTemplates().pipe(
        catchError((err) => {
          this.toast.error(err.error?.message || 'Failed to load tip templates');
          return of([]);
        })
      )
    }).subscribe({
      next: ({ categories, tips }) => {
        this.isLoading = false;
        this.defaultCategories = categories;
        this.tipTemplates = tips;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  openAddTipModal(): void {
    this.newTipCode = '';
    this.newTipTitle = '';
    this.newTipCondition = 'GENERIC';
    this.newTipContent = '';
    this.showTipModal = true;
  }

  saveTip(): void {
    if (!this.newTipCode.trim() || !this.newTipTitle.trim()) return;

    this.adminService.addTipTemplate({
      code: this.newTipCode.trim(),
      title: this.newTipTitle.trim(),
      conditionType: this.newTipCondition,
      content: this.newTipContent.trim(),
      isActive: true
    }).subscribe({
      next: () => {
        this.showTipModal = false;
        this.toast.success('Tip template published.');
        this.adminService.getTipTemplates().subscribe(t => {
          this.tipTemplates = t;
          this.cdr.markForCheck();
        });
      },
      // `TIP_TEMPLATE_CODE_TAKEN` (409) is the interesting one: the client let the user pick a code
      // that is already in use. The server's message names that, so it is shown rather than a
      // generic failure.
      error: (err) => {
        this.toast.error(err.error?.message || 'Failed to create tip template');
      }
    });
  }

  toggleTipStatus(tip: TipTemplate): void {
    this.adminService.updateTipTemplate(tip.id, { isActive: !tip.isActive }).subscribe({
      next: (u) => {
        tip.isActive = u.isActive;
        this.toast.success(`Tip template ${u.isActive ? 'activated' : 'deactivated'}.`);
      },
      error: (err) => {
        this.toast.error(err.error?.message || 'Failed to update tip template');
      }
    });
  }

  async deleteTip(id: string | number): Promise<void> {
    const confirmed = await this.toast.confirm(
      'Deactivate Tip Template',
      'Are you sure you want to deactivate this tip template?',
      'Deactivate',
      'Cancel',
      true
    );

    if (confirmed) {
      this.adminService.updateTipTemplate(id, { isActive: false }).subscribe({
        next: () => {
          this.toast.success('Tip template deactivated.');
          this.adminService.getTipTemplates().subscribe(t => (this.tipTemplates = t));
        },
        error: (err) => {
          this.toast.error(err.error?.message || 'Failed to deactivate tip template');
        }
      });
    }
  }
}
