import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CategoryService } from '../../core/services/category.service';
import { ToastService } from '../../core/services/toast.service';
import { Category, CategoryType } from '../../core/models/category.model';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryIconComponent } from '../../shared/components/category-icon/category-icon.component';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [CommonModule, FormsModule, BreadcrumbsComponent, IconComponent, CategoryIconComponent],
  template: `
    <div class="space-y-6">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'Categories' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Category Directory
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            System defaults and custom student spending categories.
          </p>
        </div>

        <button
          type="button"
          (click)="openAddModal()"
          class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs sm:text-sm py-2 px-3.5 rounded-lg shadow-xs transition-colors flex items-center gap-1.5 cursor-pointer"
        >
          <app-icon name="plus" [size]="15" strokeWidth="1.5"></app-icon>
          <span>Add Custom Category</span>
        </button>
      </div>

      <!-- Type Switcher Tabs (Expense vs Income) -->
      <div class="flex items-center gap-4 border-b border-neutral-200 dark:border-neutral-800">
        <button
          type="button"
          (click)="activeTab = 'EXPENSE'"
          class="pb-2.5 text-sm font-medium transition-colors cursor-pointer relative"
          [class.text-amber-600]="activeTab === 'EXPENSE'"
          [class.dark:text-amber-400]="activeTab === 'EXPENSE'"
          [class.border-b-2]="activeTab === 'EXPENSE'"
          [class.border-amber-500]="activeTab === 'EXPENSE'"
          [class.text-neutral-500]="activeTab !== 'EXPENSE'"
        >
          Expense Categories ({{ expenseCategories.length }})
        </button>

        <button
          type="button"
          (click)="activeTab = 'INCOME'"
          class="pb-2.5 text-sm font-medium transition-colors cursor-pointer relative"
          [class.text-amber-600]="activeTab === 'INCOME'"
          [class.dark:text-amber-400]="activeTab === 'INCOME'"
          [class.border-b-2]="activeTab === 'INCOME'"
          [class.border-amber-500]="activeTab === 'INCOME'"
          [class.text-neutral-500]="activeTab !== 'INCOME'"
        >
          Income Categories ({{ incomeCategories.length }})
        </button>
      </div>

      <!-- Categories Grid -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        @for (cat of currentList; track cat.id) {
          <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs flex flex-col justify-between hover:border-neutral-300 dark:hover:border-neutral-700 transition-colors">
            <div>
              <div class="flex items-center justify-between mb-2">
                <div class="flex items-center gap-3">
                  <app-category-icon
                    [name]="cat.name"
                    [icon]="cat.icon"
                    [color]="cat.color"
                    size="md"
                  ></app-category-icon>
                  <div>
                    <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-50">
                      {{ cat.name }}
                    </h4>
                    <span class="text-[10px] uppercase font-medium text-[var(--color-text-muted)]">
                      {{ cat.type }}
                    </span>
                  </div>
                </div>

                @if (cat.isDefault) {
                  <span class="text-[11px] font-medium text-neutral-500 dark:text-neutral-400">
                    System Default
                  </span>
                } @else {
                  <span class="text-[11px] font-semibold text-amber-600 dark:text-amber-400">
                    Custom
                  </span>
                }
              </div>

              <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-2 mb-4 leading-relaxed">
                {{ cat.description || 'No description provided.' }}
              </p>
            </div>

            <!-- Action footer -->
            <div class="pt-3 border-t border-neutral-100 dark:border-neutral-800 flex items-center justify-between text-xs">
              @if (cat.isDefault) {
                <span class="text-[11px] text-[var(--color-text-muted)] font-normal">
                  Standard category
                </span>
                <span class="text-[11px] text-[var(--color-text-muted)]">
                  System locked
                </span>
              } @else {
                <span class="text-[11px] text-[var(--color-text-muted)] font-normal">
                  Custom category
                </span>
                <div class="flex items-center gap-3">
                  <button
                    type="button"
                    (click)="openEditModal(cat)"
                    class="text-neutral-500 hover:text-neutral-900 dark:hover:text-white font-medium cursor-pointer transition-colors"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    (click)="deleteCategory(cat.id)"
                    class="text-rose-600 hover:text-rose-700 dark:text-rose-400 font-medium cursor-pointer transition-colors"
                  >
                    Delete
                  </button>
                </div>
              }
            </div>
          </div>
        }
      </div>

      <!-- Add/Edit Modal -->
      @if (showModal) {
        <div class="fixed inset-0 z-50 bg-black/40 backdrop-blur-xs flex items-center justify-center p-4">
          <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 max-w-md w-full rounded-xl shadow-lg animate-scale-up">
            <div class="flex items-center justify-between mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800">
              <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
                {{ editingCategory ? 'Edit Category' : 'Create Custom Category' }}
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
              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-400 mb-1">
                  Category Name *
                </label>
                <input
                  type="text"
                  [(ngModel)]="modalName"
                  (ngModelChange)="modalNameError = null"
                  placeholder="e.g. Lab Materials, Subscriptions"
                  class="input-brutal"
                  [class.border-rose-500]="modalNameError"
                />
                @if (modalNameError) {
                  <p class="text-xs text-rose-600 dark:text-rose-400 font-semibold mt-1 flex items-center gap-1 animate-fade-in">
                    <app-icon name="alert-triangle" size="14"></app-icon>
                    <span>{{ modalNameError }}</span>
                  </p>
                }
              </div>

              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-400 mb-1">
                  Type
                </label>
                <select [(ngModel)]="modalType" (ngModelChange)="modalNameError = null" class="input-brutal">
                  <option value="EXPENSE">Expense Category</option>
                  <option value="INCOME">Income Category</option>
                </select>
              </div>

              <!-- Curated Icon Picker -->
              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-400 mb-1.5 flex items-center justify-between">
                  <span>Category Icon</span>
                  <span class="text-[11px] font-normal text-neutral-400 capitalize">{{ modalIcon }}</span>
                </label>
                <div class="grid grid-cols-5 sm:grid-cols-10 gap-1.5 p-2 bg-neutral-50 dark:bg-neutral-800/60 rounded-xl border border-neutral-200 dark:border-neutral-700/80 max-h-36 overflow-y-auto">
                  @for (ic of availableCategoryIcons; track ic.name) {
                    <button
                      type="button"
                      (click)="modalIcon = ic.name"
                      [title]="ic.label"
                      class="h-8 w-8 rounded-lg flex items-center justify-center transition-all cursor-pointer border"
                      [class.bg-white]="modalIcon !== ic.name"
                      [class.dark:bg-neutral-800]="modalIcon !== ic.name"
                      [class.border-transparent]="modalIcon !== ic.name"
                      [class.hover:bg-neutral-100]="modalIcon !== ic.name"
                      [class.dark:hover:bg-neutral-700]="modalIcon !== ic.name"
                      [class.bg-amber-100]="modalIcon === ic.name"
                      [class.dark:bg-amber-950/80]="modalIcon === ic.name"
                      [class.border-amber-500]="modalIcon === ic.name"
                      [class.text-amber-700]="modalIcon === ic.name"
                      [class.dark:text-amber-300]="modalIcon === ic.name"
                      [class.ring-2]="modalIcon === ic.name"
                      [class.ring-amber-500/40]="modalIcon === ic.name"
                    >
                      <app-icon [name]="ic.name" size="16"></app-icon>
                    </button>
                  }
                </div>
              </div>

              <!-- Color Palette -->
              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-400 mb-1.5">
                  Color Accent
                </label>
                <div class="flex flex-wrap gap-2">
                  @for (col of paletteColors; track col) {
                    <button
                      type="button"
                      (click)="modalColor = col"
                      class="w-7 h-7 rounded-lg border border-neutral-200 dark:border-neutral-700 cursor-pointer transition-transform hover:scale-105"
                      [style.background-color]="col"
                      [class.ring-2]="modalColor === col"
                      [class.ring-offset-2]="modalColor === col"
                      [class.ring-amber-500]="modalColor === col"
                    ></button>
                  }
                </div>
              </div>

              <div>
                <label class="block text-xs font-semibold uppercase tracking-wider text-neutral-600 dark:text-neutral-400 mb-1">
                  Description
                </label>
                <input
                  type="text"
                  [(ngModel)]="modalDesc"
                  placeholder="Short note about what belongs in this category"
                  class="input-brutal text-xs"
                />
              </div>

              <div class="flex items-center justify-end gap-2 pt-3">
                <button
                  type="button"
                  (click)="closeModal()"
                  class="px-3.5 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-xs font-semibold transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  (click)="saveModal()"
                  class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-bold text-xs py-2 px-4 rounded-lg shadow-xs transition-colors cursor-pointer"
                >
                  Save Category ✓
                </button>
              </div>
            </div>
          </div>
        </div>
      }

    </div>
  `
})
export class CategoriesComponent implements OnInit {
  private categoryService = inject(CategoryService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  activeTab: CategoryType = 'EXPENSE';
  categories: Category[] = [];

  showModal = false;
  editingCategory: Category | null = null;
  modalName = '';
  modalNameError: string | null = null;
  modalIcon = 'tag';
  modalType: CategoryType = 'EXPENSE';
  modalColor = '#0EA5E9';
  modalDesc = '';

  readonly availableCategoryIcons = [
    { name: 'tag', label: 'Tag' },
    { name: 'utensils', label: 'Food & Dining' },
    { name: 'coffee', label: 'Coffee & Snacks' },
    { name: 'bus', label: 'Transport' },
    { name: 'home', label: 'Housing / Rent' },
    { name: 'book-open', label: 'Books & Academics' },
    { name: 'graduation-cap', label: 'Scholarship' },
    { name: 'briefcase', label: 'Job / Work' },
    { name: 'wallet', label: 'Allowance / Money' },
    { name: 'gift', label: 'Gift' },
    { name: 'repeat', label: 'Subscriptions' },
    { name: 'film', label: 'Entertainment' },
    { name: 'gamepad-2', label: 'Gaming' },
    { name: 'laptop', label: 'Tech & Gadgets' },
    { name: 'shopping-bag', label: 'Shopping' },
    { name: 'shirt', label: 'Clothing' },
    { name: 'heart-pulse', label: 'Health' },
    { name: 'dumbbell', label: 'Fitness' },
    { name: 'music', label: 'Music' },
    { name: 'more-horizontal', label: 'Miscellaneous' }
  ];

  paletteColors = [
    '#EA580C', '#0D9488', '#0EA5E9', '#8B5CF6',
    '#06B6D4', '#F43F5E', '#6366F1', '#10B981'
  ];

  get expenseCategories(): Category[] {
    return this.categories.filter(c => c.type === 'EXPENSE');
  }

  get incomeCategories(): Category[] {
    return this.categories.filter(c => c.type === 'INCOME');
  }

  get currentList(): Category[] {
    return this.activeTab === 'EXPENSE' ? this.expenseCategories : this.incomeCategories;
  }

  ngOnInit(): void {
    this.loadCategories();
  }

  loadCategories(): void {
    this.categoryService.getCategories().subscribe({
      next: (list) => {
        this.categories = list;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.toast.error(err.error?.message || 'Failed to load categories');
      }
    });
  }

  openAddModal(): void {
    this.editingCategory = null;
    this.modalName = '';
    this.modalNameError = null;
    this.modalIcon = 'tag';
    this.modalType = this.activeTab;
    this.modalColor = '#EAB308';
    this.modalDesc = '';
    this.showModal = true;
  }

  openEditModal(cat: Category): void {
    this.editingCategory = cat;
    this.modalName = cat.name;
    this.modalNameError = null;
    this.modalIcon = cat.icon || 'tag';
    this.modalType = cat.type;
    this.modalColor = cat.color;
    this.modalDesc = cat.description || '';
    this.showModal = true;
  }

  closeModal(): void {
    this.showModal = false;
    this.editingCategory = null;
    this.modalNameError = null;
  }

  saveModal(): void {
    const trimmedName = this.modalName.trim();
    if (!trimmedName) {
      this.modalNameError = 'Category name cannot be blank.';
      return;
    }

    // Client-side duplicate check before submit
    const duplicate = this.categories.some(c =>
      c.type === this.modalType &&
      c.name.trim().toLowerCase() === trimmedName.toLowerCase() &&
      (!this.editingCategory || String(c.id) !== String(this.editingCategory.id))
    );

    if (duplicate) {
      this.modalNameError = `You already have a category named "${trimmedName}" of this type.`;
      return;
    }

    this.modalNameError = null;

    if (this.editingCategory) {
      this.categoryService.updateCategory(this.editingCategory.id, {
        name: trimmedName,
        icon: this.modalIcon,
        color: this.modalColor,
        description: this.modalDesc.trim()
      }).subscribe({
        next: () => {
          this.toast.success(`Category "${trimmedName}" updated!`);
          this.loadCategories();
          this.closeModal();
        },
        error: (err) => {
          if (err.status === 409 || err.error?.errorCode === 'CATEGORY_NAME_TAKEN') {
            this.modalNameError = err.error?.message || `You already have a category named "${trimmedName}" of this type.`;
          } else {
            this.toast.error(err.error?.message || 'Failed to update category');
          }
        }
      });
    } else {
      this.categoryService.addCategory({
        name: trimmedName,
        type: this.modalType,
        icon: this.modalIcon,
        color: this.modalColor,
        description: this.modalDesc.trim()
      }).subscribe({
        next: () => {
          this.toast.success(`Category "${trimmedName}" created!`);
          this.loadCategories();
          this.closeModal();
        },
        error: (err) => {
          if (err.status === 409 || err.error?.errorCode === 'CATEGORY_NAME_TAKEN') {
            this.modalNameError = err.error?.message || `You already have a category named "${trimmedName}" of this type.`;
          } else {
            this.toast.error(err.error?.message || 'Failed to create category');
          }
        }
      });
    }
  }

  async deleteCategory(id: string | number): Promise<void> {
    const cat = this.categories.find(c => String(c.id) === String(id));
    const catName = cat ? ` "${cat.name}"` : '';

    const confirmed = await this.toast.confirm(
      'Remove Category',
      `Are you sure you want to remove the category${catName}? This cannot be undone if it has transactions.`,
      'Remove Category',
      'Cancel',
      true
    );

    if (confirmed) {
      this.categoryService.deleteCategory(id).subscribe({
        next: () => {
          this.toast.success(`Category${catName} removed.`);
          this.loadCategories();
        },
        error: (err: any) => {
          this.toast.error(err.error?.message || err.message || 'Failed to delete category');
        }
      });
    }
  }
}
