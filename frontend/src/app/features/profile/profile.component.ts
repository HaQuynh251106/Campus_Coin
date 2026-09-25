import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { TransactionService } from '../../core/services/transaction.service';
import { CategoryService } from '../../core/services/category.service';
import { User, FontSizePreference } from '../../core/models/user.model';
import { Transaction, TransactionType } from '../../core/models/transaction.model';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryTagComponent } from '../../shared/components/category-tag/category-tag.component';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    BreadcrumbsComponent,
    CategoryTagComponent
  ],
  template: `
    <div class="space-y-6">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'Profile & Settings' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Profile & App Settings
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            Manage your student baselines, theme appearance and CSV data imports.
          </p>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">

        <!-- Left Column: Student Identity & Profile Form (7 cols) -->
        <div class="lg:col-span-7 space-y-6">
          <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">

            <!-- User Avatar & Badge -->
            <div class="flex items-center gap-4 mb-6 pb-4 border-b border-neutral-200 dark:border-neutral-800">
              <div class="w-14 h-14 rounded-full border border-neutral-200 dark:border-neutral-700 overflow-hidden bg-neutral-100 dark:bg-neutral-800 shrink-0">
                <img
                  [src]="user()?.avatar || 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80'"
                  [alt]="user()?.name"
                  class="w-full h-full object-cover"
                />
              </div>
              <div>
                <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50">
                  {{ user()?.name }}
                </h3>
                <span class="text-xs font-mono text-neutral-500">
                  {{ user()?.studentId }} &bull; {{ user()?.email }}
                </span>
                <div class="mt-1">
                  <span class="text-[10px] font-medium px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800">
                    Verified Campus Account
                  </span>
                </div>
              </div>
            </div>

            @if (profileSuccess) {
              <div class="mb-4 p-3 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200 text-xs font-medium rounded-lg">
                ✓ Student profile updated successfully!
              </div>
            }

            <form [formGroup]="profileForm" (ngSubmit)="onSaveProfile()" class="space-y-4">
              <!-- Name -->
              <div>
                <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                  Display Name
                </label>
                <input
                  type="text"
                  formControlName="name"
                  class="input-brutal"
                />
              </div>

              <!-- Academic Year & Major -->
              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Academic Year
                  </label>
                  <select formControlName="academicYear" class="input-brutal">
                    <option value="Freshman (1st Year)">Freshman (1st Year)</option>
                    <option value="Sophomore (2nd Year)">Sophomore (2nd Year)</option>
                    <option value="Junior (3rd Year)">Junior (3rd Year)</option>
                    <option value="Senior (4th Year)">Senior (4th Year)</option>
                    <option value="Graduate / Master">Graduate / Master</option>
                  </select>
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Major / Department
                  </label>
                  <input
                    type="text"
                    formControlName="major"
                    class="input-brutal"
                  />
                </div>
              </div>

              <!-- Monthly Allowance Baseline & Savings Goal -->
              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Monthly Allowance ($)
                  </label>
                  <input
                    type="number"
                    formControlName="monthlyAllowance"
                    class="input-brutal font-medium"
                  />
                </div>
                <div>
                  <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                    Savings Goal Target ($)
                  </label>
                  <input
                    type="number"
                    formControlName="savingsGoal"
                    class="input-brutal font-medium text-amber-600 dark:text-amber-400"
                  />
                </div>
              </div>

              <div class="pt-2">
                <button
                  type="submit"
                  [disabled]="profileForm.invalid || isSaving"
                  class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-5 rounded-lg text-sm shadow-xs transition-colors cursor-pointer"
                >
                  Save Profile Changes ✓
                </button>
              </div>
            </form>

          </div>
        </div>

        <!-- Right Column: Theme & Accessibility Controls (5 cols) -->
        <div class="lg:col-span-5 space-y-6">
          <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-4 pb-2 border-b border-neutral-200 dark:border-neutral-800 tracking-tight">
              Appearance & Accessibility
            </h3>

            <!-- Dark Mode Toggle -->
            <div class="flex items-center justify-between p-3.5 bg-neutral-50 dark:bg-neutral-800/50 rounded-xl border border-neutral-200 dark:border-neutral-800 mb-4">
              <div class="flex items-center gap-3">
                <div class="w-8 h-8 rounded-lg border border-neutral-200 dark:border-neutral-700 flex items-center justify-center bg-white dark:bg-neutral-800 text-sm shadow-xs">
                  {{ theme.isDarkMode() ? '🌙' : '☀️' }}
                </div>
                <div>
                  <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-100">
                    Dark Theme Mode
                  </h4>
                  <p class="text-[11px] text-neutral-500">
                    {{ theme.isDarkMode() ? 'Enabled (Dark SaaS palette)' : 'Disabled (Light palette)' }}
                  </p>
                </div>
              </div>

              <button
                type="button"
                (click)="theme.toggleDarkMode()"
                class="px-3 py-1.5 rounded-lg border border-neutral-200 dark:border-neutral-700 text-xs font-medium cursor-pointer transition-colors"
                [class.bg-amber-500]="!theme.isDarkMode()"
                [class.text-neutral-950]="!theme.isDarkMode()"
                [class.border-amber-500]="!theme.isDarkMode()"
                [class.bg-neutral-800]="theme.isDarkMode()"
                [class.text-neutral-200]="theme.isDarkMode()"
              >
                {{ theme.isDarkMode() ? 'Light Mode' : 'Dark Mode' }}
              </button>
            </div>

            <!-- Font Size Adjuster Control -->
            <div class="p-3.5 bg-neutral-50 dark:bg-neutral-800/50 rounded-xl border border-neutral-200 dark:border-neutral-800">
              <div class="mb-2">
                <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-100">
                  Font Size Scaling
                </h4>
                <p class="text-[11px] text-neutral-500">
                  Adjust typography scale across all screens
                </p>
              </div>

              <div class="grid grid-cols-3 gap-2 mt-3">
                <button
                  type="button"
                  (click)="setFontSize('small')"
                  class="py-1.5 px-2 text-xs font-medium rounded-lg border transition-all cursor-pointer text-center"
                  [class.bg-amber-500]="theme.fontSize() === 'small'"
                  [class.text-neutral-950]="theme.fontSize() === 'small'"
                  [class.border-amber-500]="theme.fontSize() === 'small'"
                  [class.bg-white]="theme.fontSize() !== 'small'"
                  [class.dark:bg-neutral-700]="theme.fontSize() !== 'small'"
                  [class.border-neutral-200]="theme.fontSize() !== 'small'"
                  [class.dark:border-neutral-600]="theme.fontSize() !== 'small'"
                  [class.text-neutral-600]="theme.fontSize() !== 'small'"
                  [class.dark:text-neutral-300]="theme.fontSize() !== 'small'"
                >
                  Small (14px)
                </button>

                <button
                  type="button"
                  (click)="setFontSize('medium')"
                  class="py-1.5 px-2 text-xs font-medium rounded-lg border transition-all cursor-pointer text-center"
                  [class.bg-amber-500]="theme.fontSize() === 'medium'"
                  [class.text-neutral-950]="theme.fontSize() === 'medium'"
                  [class.border-amber-500]="theme.fontSize() === 'medium'"
                  [class.bg-white]="theme.fontSize() !== 'medium'"
                  [class.dark:bg-neutral-700]="theme.fontSize() !== 'medium'"
                  [class.border-neutral-200]="theme.fontSize() !== 'medium'"
                  [class.dark:border-neutral-600]="theme.fontSize() !== 'medium'"
                  [class.text-neutral-600]="theme.fontSize() !== 'medium'"
                  [class.dark:text-neutral-300]="theme.fontSize() !== 'medium'"
                >
                  Medium (16px)
                </button>

                <button
                  type="button"
                  (click)="setFontSize('large')"
                  class="py-1.5 px-2 text-xs font-medium rounded-lg border transition-all cursor-pointer text-center"
                  [class.bg-amber-500]="theme.fontSize() === 'large'"
                  [class.text-neutral-950]="theme.fontSize() === 'large'"
                  [class.border-amber-500]="theme.fontSize() === 'large'"
                  [class.bg-white]="theme.fontSize() !== 'large'"
                  [class.dark:bg-neutral-700]="theme.fontSize() !== 'large'"
                  [class.border-neutral-200]="theme.fontSize() !== 'large'"
                  [class.dark:border-neutral-600]="theme.fontSize() !== 'large'"
                  [class.text-neutral-600]="theme.fontSize() !== 'large'"
                  [class.dark:text-neutral-300]="theme.fontSize() !== 'large'"
                >
                  Large (18px)
                </button>
              </div>
            </div>

          </div>
        </div>

      </div>

      <!-- 3. CSV Import UI Feature -->
      <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <div class="flex flex-wrap items-center justify-between gap-4 mb-4 pb-3 border-b border-neutral-200 dark:border-neutral-800">
          <div>
            <span class="inline-flex items-center text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
              CSV Upload
            </span>
            <h3 class="text-lg font-semibold text-neutral-900 dark:text-neutral-50 mt-1 tracking-tight">
              Import Transactions
            </h3>
            <p class="text-xs text-[var(--color-text-muted)]">
              Import statement rows from your bank or student account.
            </p>
          </div>

          <button
            type="button"
            (click)="loadSampleCsv()"
            class="px-3 py-1.5 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-xs transition-colors cursor-pointer"
          >
            Load sample CSV
          </button>
        </div>

        <!-- File Upload Area -->
        <div class="p-6 border border-dashed border-neutral-300 dark:border-neutral-700 rounded-xl bg-neutral-50/60 dark:bg-neutral-800/30 text-center mb-4">
          <input
            #csvFileInput
            type="file"
            accept=".csv,text/csv"
            (change)="onFileSelected($event)"
            class="hidden"
          />

          <div class="w-10 h-10 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center mx-auto mb-2 text-lg">
            📄
          </div>
          <h4 class="font-medium text-sm text-neutral-900 dark:text-neutral-100">
            Choose a CSV file or load sample data
          </h4>
          <p class="text-xs text-neutral-500 mt-0.5 mb-3">
            Expected headers: <code class="font-mono bg-neutral-200 dark:bg-neutral-700 px-1 py-0.5 rounded text-[11px]">date,description,amount,type,category</code>
          </p>

          <button
            type="button"
            (click)="csvFileInput.click()"
            class="bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium text-xs py-2 px-4 rounded-lg shadow-xs transition-colors cursor-pointer"
          >
            Select .CSV File
          </button>
        </div>

        <!-- Parsed Rows Preview Table -->
        @if (parsedRows.length > 0) {
          <div class="space-y-4">
            <div class="flex items-center justify-between">
              <span class="text-xs font-semibold text-neutral-900 dark:text-neutral-100">
                {{ parsedRows.length }} rows parsed:
              </span>

              <button
                type="button"
                (click)="confirmImport()"
                [disabled]="isImporting"
                class="bg-emerald-600 hover:bg-emerald-700 text-white font-medium text-xs py-2 px-4 rounded-lg shadow-xs transition-colors cursor-pointer"
              >
                Import {{ parsedRows.length }} transactions
              </button>
            </div>

            <div class="overflow-x-auto border border-neutral-200 dark:border-neutral-800 rounded-lg">
              <table class="w-full text-left text-xs border-collapse">
                <thead>
                  <tr class="table-head-row bg-neutral-50 dark:bg-neutral-800 text-[var(--color-text-muted)] font-medium text-[11px] uppercase border-b border-[var(--color-border)]">
                    <th class="p-2.5">Date</th>
                    <th class="p-2.5">Description</th>
                    <th class="p-2.5">Type</th>
                    <th class="p-2.5">Category</th>
                    <th class="p-2.5 text-right">Amount</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-[var(--color-border)]">
                  @for (row of parsedRows; track $index) {
                    <tr class="hover:bg-neutral-50 dark:hover:bg-neutral-800/30">
                      <td class="p-2.5 font-mono text-[var(--color-text-muted)]">{{ row.date }}</td>
                      <td class="p-2.5 font-medium text-neutral-900 dark:text-neutral-100">{{ row.description }}</td>
                      <td class="p-2.5">
                        <span class="text-[10px] font-medium px-1.5 py-0.2 rounded-full" [class.bg-emerald-50]="row.type === 'INCOME'" [class.text-emerald-700]="row.type === 'INCOME'" [class.dark:bg-emerald-950/40]="row.type === 'INCOME'" [class.dark:text-emerald-300]="row.type === 'INCOME'" [class.bg-rose-50]="row.type === 'EXPENSE'" [class.text-rose-700]="row.type === 'EXPENSE'" [class.dark:bg-rose-950/40]="row.type === 'EXPENSE'" [class.dark:text-rose-300]="row.type === 'EXPENSE'">
                          {{ row.type }}
                        </span>
                      </td>
                      <td class="p-2.5 whitespace-nowrap">
                        <app-category-tag [name]="row.categoryName" size="xs"></app-category-tag>
                      </td>
                      <td class="p-2.5 text-right font-mono font-semibold" [class.text-emerald-600]="row.type === 'INCOME'" [class.dark:text-emerald-400]="row.type === 'INCOME'" [class.text-rose-600]="row.type === 'EXPENSE'" [class.dark:text-rose-400]="row.type === 'EXPENSE'">
                        \${{ row.amount.toFixed(2) }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      </div>

    </div>
  `
})
export class ProfileComponent implements OnInit {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  theme = inject(ThemeService);
  private txService = inject(TransactionService);
  private categoryService = inject(CategoryService);

  user = this.auth.currentUser;
  profileSuccess = false;
  isSaving = false;

  profileForm = this.fb.group({
    name: ['', [Validators.required]],
    academicYear: ['Junior (3rd Year)', [Validators.required]],
    major: ['Computer Science', [Validators.required]],
    monthlyAllowance: [500, [Validators.required, Validators.min(0)]],
    savingsGoal: [1000, [Validators.required, Validators.min(0)]]
  });

  parsedRows: Array<Omit<Transaction, 'id' | 'createdAt'>> = [];
  isImporting = false;

  ngOnInit(): void {
    const u = this.user();
    if (u) {
      this.profileForm.patchValue({
        name: u.name,
        academicYear: u.academicYear,
        major: u.major,
        monthlyAllowance: u.monthlyAllowance,
        savingsGoal: u.savingsGoal
      });
    }
  }

  onSaveProfile(): void {
    if (this.profileForm.invalid) return;

    this.isSaving = true;
    const updates = this.profileForm.value;

    this.auth.updateProfile({
      name: updates.name || undefined,
      academicYear: updates.academicYear || undefined,
      major: updates.major || undefined,
      monthlyAllowance: Number(updates.monthlyAllowance) || undefined,
      savingsGoal: Number(updates.savingsGoal) || undefined
    }).subscribe({
      next: () => {
        this.isSaving = false;
        this.profileSuccess = true;
        setTimeout(() => this.profileSuccess = false, 3500);
      },
      error: () => {
        this.isSaving = false;
      }
    });
  }

  setFontSize(size: FontSizePreference): void {
    this.theme.setFontSize(size);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;

    const file = input.files[0];
    const reader = new FileReader();

    reader.onload = (e) => {
      const text = e.target?.result as string;
      this.parseCsvText(text);
    };

    reader.readAsText(file);
  }

  loadSampleCsv(): void {
    const sample = `date,description,amount,type,category
2026-09-22,Library Printing Deposit,5.00,EXPENSE,Books & Supplies
2026-09-21,Study Group Boba Tea,6.50,EXPENSE,Coffee & Snacks
2026-09-20,Tutoring Stipend,75.00,INCOME,Freelance & Work
2026-09-19,Campus Bookstore USB Cable,12.00,EXPENSE,Tech & Subscriptions`;

    this.parseCsvText(sample);
  }

  private parseCsvText(csv: string): void {
    const lines = csv.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    if (lines.length < 2) return;

    const rows: Array<Omit<Transaction, 'id' | 'createdAt'>> = [];

    // Skip header line
    for (let i = 1; i < lines.length; i++) {
      const cols = lines[i].split(',').map(c => c.trim());
      if (cols.length >= 5) {
        const [date, description, amountStr, typeStr, catName] = cols;
        const amount = parseFloat(amountStr);
        if (!isNaN(amount)) {
          rows.push({
            userId: this.user()?.id || 'user-001',
            date: date || '2026-09-24',
            description: description || 'Imported Entry',
            amount: Math.abs(amount),
            type: (typeStr?.toUpperCase() === 'INCOME' ? 'INCOME' : 'EXPENSE') as TransactionType,
            categoryId: 'cat-custom',
            categoryName: catName || 'General',
            categoryIcon: 'tag',
            categoryColor: '#EAB308',
            recurringFrequency: 'NONE',
            isDeleted: false
          });
        }
      }
    }

    this.parsedRows = rows;
  }

  confirmImport(): void {
    if (this.parsedRows.length === 0) return;

    this.isImporting = true;
    this.txService.importBatch(this.parsedRows).subscribe({
      next: (count) => {
        this.isImporting = false;
        alert(`Successfully imported ${count} campus transactions into your budget feed!`);
        this.parsedRows = [];
      },
      error: () => {
        this.isImporting = false;
      }
    });
  }
}
