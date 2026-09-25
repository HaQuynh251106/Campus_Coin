import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CategoryService } from '../../core/services/category.service';
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
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400 border border-neutral-200 dark:border-neutral-700">
                    System Default
                  </span>
                } @else {
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
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
                <label class="block text-xs font-medium uppercase text-neutral-500 mb-1">
                  Category Name *
                </label>
                <input
                  type="text"
                  [(ngModel)]="modalName"
                  placeholder="e.g. Lab Materials, Subscriptions"
                  class="input-brutal"
                />
              </div>

              <div>
                <label class="block text-xs font-medium uppercase text-neutral-500 mb-1">
                  Type
                </label>
                <select [(ngModel)]="modalType" class="input-brutal">
                  <option value="EXPENSE">Expense Category</option>
                  <option value="INCOME">Income Category</option>
                </select>
              </div>

              <!-- Color Palette -->
              <div>
                <label class="block text-xs font-medium uppercase text-neutral-500 mb-1.5">
                  Color Accent
                </label>
                <div class="flex flex-wrap gap-2">
                  @for (col of paletteColors; track col) {
                    <button
                      type="button"
                      (click)="modalColor = col"
                      class="w-7 h-7 rounded-lg border border-neutral-200 dark:border-neutral-700 cursor-pointer transition-transform"
                      [style.background-color]="col"
                      [class.ring-2]="modalColor === col"
                      [class.ring-amber-500]="modalColor === col"
                    ></button>
                  }
                </div>
              </div>

              <div>
                <label class="block text-xs font-medium uppercase text-neutral-500 mb-1">
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
                  class="px-3.5 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-xs transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  (click)="saveModal()"
                  class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs py-2 px-4 rounded-lg shadow-xs transition-colors cursor-pointer"
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

  activeTab: CategoryType = 'EXPENSE';
  categories: Category[] = [];

  showModal = false;
  editingCategory: Category | null = null;
  modalName = '';
  modalType: CategoryType = 'EXPENSE';
  modalColor = '#0EA5E9';
  modalDesc = '';

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
    this.categoryService.getCategories().subscribe(list => {
      this.categories = list;
    });
  }

  openAddModal(): void {
    this.editingCategory = null;
    this.modalName = '';
    this.modalType = this.activeTab;
    this.modalColor = '#EAB308';
    this.modalDesc = '';
    this.showModal = true;
  }

  openEditModal(cat: Category): void {
    this.editingCategory = cat;
    this.modalName = cat.name;
    this.modalType = cat.type;
    this.modalColor = cat.color;
    this.modalDesc = cat.description || '';
    this.showModal = true;
  }

  closeModal(): void {
    this.showModal = false;
    this.editingCategory = null;
  }

  saveModal(): void {
    if (!this.modalName.trim()) return;

    if (this.editingCategory) {
      this.categoryService.updateCategory(this.editingCategory.id, {
        name: this.modalName.trim(),
        type: this.modalType,
        color: this.modalColor,
        description: this.modalDesc.trim()
      }).subscribe(() => {
        this.loadCategories();
        this.closeModal();
      });
    } else {
      this.categoryService.addCategory({
        name: this.modalName.trim(),
        type: this.modalType,
        icon: 'tag',
        color: this.modalColor,
        description: this.modalDesc.trim(),
        isDefault: false
      }).subscribe(() => {
        this.loadCategories();
        this.closeModal();
      });
    }
  }

  deleteCategory(id: string): void {
    if (confirm('Are you sure you want to remove this category?')) {
      this.categoryService.deleteCategory(id).subscribe({
        next: () => this.loadCategories(),
        error: (err) => alert(err.message)
      });
    }
  }
}
