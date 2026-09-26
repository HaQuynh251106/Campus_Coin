import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, of, catchError } from 'rxjs';
import { CategoryService } from '../../../core/services/category.service';
import { AdminService, TipTemplate } from '../../../core/services/admin.service';
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
            <p class="text-xs text-[var(--color-text-muted)]">Immutable baseline categories inherited by all student cohorts</p>
          </div>
        </div>

        <div class="bg-white dark:bg-neutral-900 rounded-lg border border-slate-200 dark:border-neutral-800 overflow-hidden shadow-xs">
          <table class="w-full text-left text-xs border-collapse">
            <thead>
              <tr class="table-head-row bg-slate-50 dark:bg-neutral-800 border-b border-slate-200 dark:border-neutral-800 text-[var(--color-text-muted)] uppercase font-semibold text-[10px]">
                <th class="p-3 w-1/4">Category Name</th>
                <th class="p-3 w-28">Type</th>
                <th class="p-3">Description</th>
                <th class="p-3 w-40 text-center">Governance State</th>
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
                      <span class="font-medium text-xs text-slate-900 dark:text-white">{{ cat.name }}</span>
                    </div>
                  </td>
                  <td class="p-3">
                    <span class="px-2 py-0.5 rounded text-[10px] font-semibold" [class.bg-emerald-50]="cat.type === 'INCOME'" [class.text-emerald-700]="cat.type === 'INCOME'" [class.dark:bg-emerald-950/40]="cat.type === 'INCOME'" [class.dark:text-emerald-300]="cat.type === 'INCOME'" [class.bg-amber-50]="cat.type === 'EXPENSE'" [class.text-amber-700]="cat.type === 'EXPENSE'" [class.dark:bg-amber-950/40]="cat.type === 'EXPENSE'" [class.dark:text-amber-300]="cat.type === 'EXPENSE'">
                      {{ cat.type }}
                    </span>
                  </td>
                  <td class="p-3 text-slate-600 dark:text-neutral-400">
                    {{ cat.description }}
                  </td>
                  <td class="p-3 text-center">
                    <span class="px-2 py-0.5 rounded text-[10px] font-medium bg-slate-100 dark:bg-neutral-800 text-slate-600 dark:text-neutral-300 border border-slate-200 dark:border-neutral-700">
                      System Default
                    </span>
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
                <th class="p-3">Category Tag</th>
                <th class="p-3">Target Cohort</th>
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
                  <td class="p-3">
                    <div class="flex items-center gap-2">
                      <app-category-icon
                        [name]="tip.categoryTag"
                        size="sm"
                      ></app-category-icon>
                      <span class="text-xs text-slate-700 dark:text-neutral-300 font-medium">{{ tip.categoryTag }}</span>
                    </div>
                  </td>
                  <td class="p-3 text-slate-600 dark:text-neutral-300 font-mono text-[11px]">
                    {{ tip.audience }}
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

              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Category Tag
                  </label>
                  <input
                    type="text"
                    [(ngModel)]="newTipTag"
                    placeholder="e.g. Dining, Transit"
                    class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md"
                  />
                </div>
                <div>
                  <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
                    Audience Cohort
                  </label>
                  <select [(ngModel)]="newTipAudience" class="w-full px-3 py-2 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md">
                    <option value="ALL_STUDENTS">All Students</option>
                    <option value="FRESHMEN">Freshmen Cohort</option>
                    <option value="OFF_CAMPUS">Off-Campus Commuters</option>
                  </select>
                </div>
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
                  [disabled]="!newTipTitle.trim() || !newTipContent.trim()"
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
  newTipTitle = '';
  newTipTag = 'Dining';
  newTipAudience: 'ALL_STUDENTS' | 'FRESHMEN' | 'OFF_CAMPUS' = 'ALL_STUDENTS';
  newTipContent = '';

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
    this.newTipTitle = '';
    this.newTipTag = 'General';
    this.newTipAudience = 'ALL_STUDENTS';
    this.newTipContent = '';
    this.showTipModal = true;
  }

  saveTip(): void {
    if (!this.newTipTitle.trim()) return;

    this.adminService.addTipTemplate({
      title: this.newTipTitle.trim(),
      categoryTag: this.newTipTag.trim(),
      audience: this.newTipAudience,
      content: this.newTipContent.trim(),
      isActive: true
    }).subscribe(() => {
      this.showTipModal = false;
      this.adminService.getTipTemplates().subscribe(t => (this.tipTemplates = t));
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
