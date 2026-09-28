import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CsvImportService, CSV_TEMPLATE_CONTENT } from '../../core/services/csv-import.service';
import { CategoryService } from '../../core/services/category.service';
import { ToastService } from '../../core/services/toast.service';
import { Category } from '../../core/models/category.model';
import { ImportBatch, ImportRow, ImportSummary } from '../../core/models/import.model';

@Component({
  selector: 'app-csv-import',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  template: `
    <div class="max-w-5xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Import Spending File
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Bring a term's worth of entries in at once. Nothing is saved until you confirm the preview.
          </p>
        </div>

        @if (batch) {
          <button
            type="button"
            (click)="startAnother()"
            class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-sm transition-colors cursor-pointer flex items-center gap-2"
          >
            <app-icon name="upload" [size]="15" strokeWidth="1.5"></app-icon>
            <span>Choose Another File</span>
          </button>
        }
      </div>

      <!-- STEP 1 — choose a file -->
      @if (!batch) {
        <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">Choose a file</h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mb-4">
            The file needs a header row. <strong>date</strong>, <strong>amount</strong> and
            <strong>type</strong> are required; <strong>description</strong> and <strong>category</strong>
            are optional but give the importer more to work with.
          </p>

          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <label
              class="flex flex-col items-center justify-center gap-2 p-6 border border-dashed border-neutral-300 dark:border-neutral-700 rounded-xl cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800/50 transition-colors text-center"
              [class.opacity-60]="isUploading"
            >
              <app-icon name="file-text" [size]="26" strokeWidth="1.25" className="text-amber-500"></app-icon>
              <span class="text-sm font-medium text-neutral-900 dark:text-neutral-100">Select a .csv file</span>
              <span class="text-[11px] text-neutral-500">
                {{ selectedFilename || 'No file chosen yet' }}
              </span>
              <input
                type="file"
                accept=".csv,text/csv"
                class="hidden"
                [disabled]="isUploading"
                (change)="onFileChosen($event)"
              />
            </label>

            <div class="flex flex-col justify-between gap-3 p-4 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700 rounded-xl">
              <div>
                <span class="text-xs font-semibold text-neutral-900 dark:text-neutral-100 block mb-1">
                  Not sure of the format?
                </span>
                <p class="text-[11px] text-neutral-500 leading-relaxed">
                  Download an example with the five columns and three sample lines, then replace the
                  lines with your own.
                </p>
              </div>
              <button
                type="button"
                (click)="downloadTemplate()"
                class="text-xs font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 px-3 py-1.5 rounded-lg transition-colors cursor-pointer flex items-center justify-center gap-1.5"
              >
                <app-icon name="download" [size]="13" strokeWidth="1.75"></app-icon>
                <span>Download example CSV</span>
              </button>
            </div>
          </div>

          @if (errorMessage) {
            <div class="mt-4 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-semibold flex items-start gap-2">
              <app-icon name="alert-triangle" [size]="14" strokeWidth="1.5" className="mt-0.5 shrink-0"></app-icon>
              <span>{{ errorMessage }}</span>
            </div>
          }

          @if (isUploading) {
            <div class="mt-4 flex items-center gap-2 text-xs text-neutral-500">
              <svg class="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              <span>Reading the file and checking each row…</span>
            </div>
          }
        </div>
      }

      <!-- STEPS 2 and 3 — the preview, and where it ended -->
      @if (batch) {
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div class="min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 truncate">
                  {{ batch.originalFilename }}
                </h3>
                <span
                  class="px-2 py-0.5 rounded-full text-[10px] font-semibold border"
                  [ngClass]="statusClasses(batch.status)"
                >
                  {{ batch.status }}
                </span>
              </div>
              <p class="text-[11px] text-neutral-500 mt-1">
                Uploaded {{ batch.createdAt | date: 'd MMM y, HH:mm' }}
                @if (batch.committedAt) {
                  · imported {{ batch.committedAt | date: 'd MMM y, HH:mm' }}
                }
              </p>
            </div>
          </div>

          <!-- Counters -->
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-4">
            <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
              <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Rows in file</span>
              <span class="font-semibold text-lg text-neutral-900 dark:text-neutral-100 tabular-nums">{{ batch.totalRows }}</span>
            </div>
            <div class="p-3 rounded-lg bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-900">
              <span class="block text-[10px] uppercase font-medium tracking-wider text-emerald-700 dark:text-emerald-400">
                {{ batch.status === 'COMMITTED' ? 'Imported' : 'Ready to import' }}
              </span>
              <span class="font-semibold text-lg text-emerald-700 dark:text-emerald-400 tabular-nums">{{ batch.validRows }}</span>
            </div>
            <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
              <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Already recorded</span>
              <span class="font-semibold text-lg text-neutral-900 dark:text-neutral-100 tabular-nums">{{ batch.duplicateRows }}</span>
            </div>
            <div class="p-3 rounded-lg bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700">
              <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Could not read</span>
              <span
                class="font-semibold text-lg tabular-nums"
                [class.text-rose-600]="batch.errorRows > 0"
                [class.dark:text-rose-400]="batch.errorRows > 0"
                [class.text-neutral-900]="batch.errorRows === 0"
                [class.dark:text-neutral-100]="batch.errorRows === 0"
              >{{ batch.errorRows }}</span>
            </div>
          </div>

          @if (batch.status === 'COMMITTED') {
            <div class="mt-4 p-3 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200 rounded-lg text-xs font-medium flex items-start gap-2">
              <app-icon name="check-circle" [size]="14" strokeWidth="1.5" className="mt-0.5 shrink-0"></app-icon>
              <span>
                {{ batch.importedRows }} of {{ batch.totalRows }} rows became transactions. They can be
                edited like any other entry from the Quick Add page.
              </span>
            </div>
          }
          @if (batch.status === 'CANCELLED') {
            <div class="mt-4 p-3 bg-neutral-100 dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 rounded-lg text-xs font-medium">
              This import was abandoned. No transactions were created from it.
            </div>
          }
          @if (batch.status === 'FAILED') {
            <div class="mt-4 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-medium">
              This import failed and created no transactions. Choose the file again to retry.
            </div>
          }

          <!-- Actions -->
          @if (batch.modifiable) {
            <div class="flex flex-wrap items-center gap-3 mt-5 pt-4 border-t border-neutral-100 dark:border-neutral-800">
              <button
                type="button"
                (click)="commit()"
                [disabled]="isCommitting || batch.validRows === 0"
                class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-5 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
              >
                <app-icon name="check" [size]="15" strokeWidth="2"></app-icon>
                <span>{{ isCommitting ? 'Importing…' : 'Confirm and Import ' + batch.validRows + ' Rows' }}</span>
              </button>

              <button
                type="button"
                (click)="cancel()"
                [disabled]="isCommitting"
                class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:opacity-50 rounded-lg text-sm transition-colors cursor-pointer"
              >
                Discard This Import
              </button>

              @if (batch.validRows === 0) {
                <span class="text-[11px] text-neutral-500">
                  Nothing in this file can be imported yet — correct a row below first.
                </span>
              }
            </div>
          }
        </div>

        <!-- The rows -->
        <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
          <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
            <div>
              <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50">Preview</h3>
              <p class="text-xs text-neutral-500">
                Line numbers match your spreadsheet, counting the header as line 1.
              </p>
            </div>
            @if (batch.modifiable) {
              <span class="text-[11px] text-neutral-500">
                Choose a category to fix a row the importer could not file.
              </span>
            }
          </div>

          <!-- Desktop: table. Phone: the same rows as stacked cards, further down. -->
          <div class="hidden md:block overflow-x-auto">
            <table class="w-full text-left text-xs border-collapse">
              <thead>
                <tr class="border-b border-neutral-200 dark:border-neutral-800 text-neutral-500 uppercase text-[10px] font-medium">
                  <th class="py-2.5 px-3 w-12 text-center">Line</th>
                  <th class="py-2.5 px-3 w-28">Date</th>
                  <th class="py-2.5 px-3 w-32">Category</th>
                  <th class="py-2.5 px-3">Description</th>
                  <th class="py-2.5 px-3 w-24 text-right">Amount</th>
                  <th class="py-2.5 px-3 w-32">Status</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-neutral-100 dark:divide-neutral-800">
                @for (row of batch.rows; track row.id) {
                  <tr [ngClass]="rowTint(row)">
                    <td class="py-2.5 px-3 text-center font-mono text-[11px] text-neutral-500">{{ row.csvRowNo }}</td>
                    <td class="py-2.5 px-3 font-mono text-[11px] text-neutral-600 dark:text-neutral-400 whitespace-nowrap">
                      {{ row.parsedDate || '—' }}
                    </td>
                    <td class="py-2.5 px-3">
                      @if (batch.modifiable) {
                        <select
                          class="input-brutal !py-1 !px-2 text-[11px]"
                          [ngModel]="row.resolvedCategoryId"
                          [ngModelOptions]="{ standalone: true }"
                          [disabled]="patchingRowId === row.id"
                          (ngModelChange)="onRowCategoryChange(row, $event)"
                        >
                          <option [ngValue]="null">
                            {{ row.parsedCategoryName ? row.parsedCategoryName + ' (from file)' : 'Choose a category' }}
                          </option>
                          @for (cat of categoriesFor(row); track cat.id) {
                            <option [ngValue]="+cat.id">{{ cat.name }}</option>
                          }
                        </select>
                      } @else {
                        <span class="text-neutral-700 dark:text-neutral-300">
                          {{ row.parsedCategoryName || 'Not filed' }}
                        </span>
                      }
                      @if (rowCategoryName(row) && row.aiSuggestedCategoryId && row.resolvedCategoryId === null) {
                        <button
                          type="button"
                          (click)="applySuggestion(row)"
                          [disabled]="patchingRowId === row.id"
                          class="mt-1 text-[10px] font-medium text-sky-600 dark:text-sky-400 hover:underline cursor-pointer disabled:opacity-50"
                        >
                          Use suggestion: {{ rowCategoryName(row) }}
                        </button>
                      }
                    </td>
                    <td class="py-2.5 px-3 text-neutral-900 dark:text-neutral-100 max-w-[260px] truncate">
                      {{ row.parsedDescription || '—' }}
                    </td>
                    <td class="py-2.5 px-3 text-right font-mono whitespace-nowrap">
                      {{ row.parsedAmount !== null ? row.parsedAmount.toFixed(2) : '—' }}
                    </td>
                    <td class="py-2.5 px-3">
                      <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border" [ngClass]="rowStatusClasses(row)">
                        {{ row.rowStatus }}
                      </span>
                      @if (row.errorMessage) {
                        <span class="block text-[10px] text-neutral-500 mt-1 leading-snug">{{ row.errorMessage }}</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <!-- Phone layout: one card per row. -->
          <div class="md:hidden space-y-2">
            @for (row of batch.rows; track row.id) {
              <div class="p-3 rounded-lg border" [ngClass]="rowTint(row)">
                <div class="flex items-start justify-between gap-2">
                  <div class="min-w-0">
                    <span class="font-mono text-[10px] text-neutral-500 block">Line {{ row.csvRowNo }}</span>
                    <span class="font-medium text-xs text-neutral-900 dark:text-neutral-100 block truncate">
                      {{ row.parsedDescription || 'No description' }}
                    </span>
                  </div>
                  <div class="text-right shrink-0">
                    <span class="font-mono text-xs font-semibold text-neutral-900 dark:text-neutral-100 block">
                      {{ row.parsedAmount !== null ? row.parsedAmount.toFixed(2) : '—' }}
                    </span>
                    <span class="font-mono text-[10px] text-neutral-500">{{ row.parsedDate || '—' }}</span>
                  </div>
                </div>

                <div class="flex items-center gap-2 mt-2 flex-wrap">
                  <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border" [ngClass]="rowStatusClasses(row)">
                    {{ row.rowStatus }}
                  </span>
                  @if (batch.modifiable) {
                    <select
                      class="input-brutal !py-1 !px-2 text-[11px] flex-1 min-w-[140px]"
                      [ngModel]="row.resolvedCategoryId"
                      [ngModelOptions]="{ standalone: true }"
                      [disabled]="patchingRowId === row.id"
                      (ngModelChange)="onRowCategoryChange(row, $event)"
                    >
                      <option [ngValue]="null">
                        {{ row.parsedCategoryName ? row.parsedCategoryName + ' (from file)' : 'Choose a category' }}
                      </option>
                      @for (cat of categoriesFor(row); track cat.id) {
                        <option [ngValue]="+cat.id">{{ cat.name }}</option>
                      }
                    </select>
                  } @else {
                    <span class="text-[11px] text-neutral-600 dark:text-neutral-400">
                      {{ row.parsedCategoryName || 'Not filed' }}
                    </span>
                  }
                </div>

                @if (row.errorMessage) {
                  <p class="text-[10px] text-neutral-500 mt-1.5 leading-snug">{{ row.errorMessage }}</p>
                }
              </div>
            }
          </div>
        </div>
      }

      <!-- Import history -->
      <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">Past Imports</h3>
        <p class="text-xs text-neutral-500 mb-4">Files you have uploaded, most recent first.</p>

        @if (isLoadingHistory) {
          <div class="py-6 text-center text-xs text-neutral-500">Loading your imports…</div>
        } @else if (historyFailed) {
          <div class="py-6 text-center">
            <p class="text-xs text-neutral-500">
              Your past imports could not be loaded just now.
            </p>
            <button
              type="button"
              (click)="loadHistory()"
              class="mt-2 text-xs font-medium text-amber-700 dark:text-amber-400 hover:underline cursor-pointer"
            >
              Try again
            </button>
          </div>
        } @else if (history.length === 0) {
          <div class="py-6 text-center">
            <p class="text-xs text-neutral-500">
              You have not imported a file yet. Past imports will be listed here.
            </p>
          </div>
        } @else {
          <ul class="divide-y divide-neutral-100 dark:divide-neutral-800">
            @for (item of history; track item.id) {
              <li class="py-2.5 flex flex-wrap items-center justify-between gap-2">
                <div class="min-w-0 flex items-center gap-2">
                  <app-icon name="file-text" [size]="14" strokeWidth="1.5" className="text-neutral-400 shrink-0"></app-icon>
                  <span class="text-xs font-medium text-neutral-900 dark:text-neutral-100 truncate">
                    {{ item.originalFilename }}
                  </span>
                  <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border" [ngClass]="statusClasses(item.status)">
                    {{ item.status }}
                  </span>
                </div>
                <div class="flex items-center gap-3 text-[11px] text-neutral-500">
                  <span>{{ item.importedRows }}/{{ item.totalRows }} rows</span>
                  <span class="font-mono">{{ item.createdAt | date: 'd MMM y' }}</span>
                  <button
                    type="button"
                    (click)="openBatch(item.id)"
                    class="font-medium text-amber-700 dark:text-amber-400 hover:underline cursor-pointer"
                  >
                    View
                  </button>
                </div>
              </li>
            }
          </ul>
        }
      </div>
    </div>
  `
})
export class CsvImportComponent implements OnInit {
  private importService = inject(CsvImportService);
  private categoryService = inject(CategoryService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  batch: ImportBatch | null = null;
  history: ImportSummary[] = [];
  categories: Category[] = [];

  selectedFilename = '';
  errorMessage = '';

  isUploading = false;
  isCommitting = false;
  isLoadingHistory = true;
  historyFailed = false;
  patchingRowId: number | null = null;

  ngOnInit(): void {
    this.loadHistory();
    this.loadCategories();
  }

  loadHistory(): void {
    this.isLoadingHistory = true;
    this.historyFailed = false;
    this.importService.listImports().subscribe({
      next: list => {
        this.history = list;
        this.isLoadingHistory = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoadingHistory = false;

        this.historyFailed = true;
        this.cdr.markForCheck();
      }
    });
  }

  private loadCategories(): void {
    this.categoryService.getCategories().subscribe({
      next: cats => {
        this.categories = cats.filter(c => c.isActive !== false);
        this.cdr.markForCheck();
      },
      error: () => {

        this.cdr.markForCheck();
      }
    });
  }

  onFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.selectedFilename = file.name;
    this.errorMessage = '';
    this.isUploading = true;

    file
      .text()
      .then(content => {
        if (!content.trim()) {
          this.isUploading = false;
          this.errorMessage = 'That file is empty. Choose a file with a header row and at least one entry.';
          this.cdr.markForCheck();
          return;
        }
        this.importService.uploadCsv({ filename: file.name, content }).subscribe({
          next: batch => {
            this.isUploading = false;
            this.batch = batch;
            this.toast.success(
              batch.errorRows > 0
                ? `Read ${batch.totalRows} rows — ${batch.errorRows} need attention before importing.`
                : `Read ${batch.totalRows} rows — all ready to import.`
            );
            this.loadHistory();
            this.cdr.markForCheck();
          },
          error: err => {
            this.isUploading = false;

            this.errorMessage =
              err.error?.message || 'That file could not be read. Check the columns and try again.';
            this.cdr.markForCheck();
          }
        });
      })
      .catch(() => {
        this.isUploading = false;
        this.errorMessage = 'That file could not be opened. Try saving it as CSV and choosing it again.';
        this.cdr.markForCheck();
      });

    input.value = '';
  }

  onRowCategoryChange(row: ImportRow, categoryId: number | null): void {
    if (!this.batch || categoryId === null || !this.batch.modifiable) return;

    this.patchingRowId = row.id;
    this.importService.setRowCategory(this.batch.id, row.id, Number(categoryId)).subscribe({
      next: updated => {
        this.patchingRowId = null;

        this.batch = {
          ...this.batch!,
          rows: this.batch!.rows.map(r => (r.id === updated.id ? updated : r)),
          validRows: this.batch!.rows.filter(r => (r.id === updated.id ? updated : r).rowStatus === 'VALID').length,
          duplicateRows: this.batch!.rows.filter(r => (r.id === updated.id ? updated : r).rowStatus === 'DUPLICATE').length,
          errorRows: this.batch!.rows.filter(r => (r.id === updated.id ? updated : r).rowStatus === 'ERROR').length
        };
        this.cdr.markForCheck();
      },
      error: err => {
        this.patchingRowId = null;
        this.toast.error(err.error?.message || 'That category could not be set for the row.');
        this.cdr.markForCheck();
      }
    });
  }

  applySuggestion(row: ImportRow): void {
    const name = this.rowCategoryName(row);
    const match = name ? this.categories.find(c => c.name === name) : undefined;
    if (!match) return;
    this.onRowCategoryChange(row, Number(match.id));
  }

  commit(): void {
    if (!this.batch) return;
    this.isCommitting = true;
    this.importService.commitImport(this.batch.id).subscribe({
      next: batch => {
        this.isCommitting = false;
        this.batch = batch;
        this.toast.success(`Imported ${batch.importedRows} rows.`);
        this.loadHistory();
        this.cdr.markForCheck();
      },
      error: err => {
        this.isCommitting = false;
        this.toast.error(err.error?.message || 'That import could not be completed.');
        this.cdr.markForCheck();
      }
    });
  }

  async cancel(): Promise<void> {
    if (!this.batch) return;
    const ok = await this.toast.confirm(
      'Discard This Import',
      'Nothing from this file will be recorded, and the preview will be closed. You can upload the file again later.',
      'Discard',
      'Keep Preview'
    );
    if (!ok) return;

    this.importService.cancelImport(this.batch.id).subscribe({
      next: batch => {
        this.batch = batch;
        this.toast.info('Import discarded. No transactions were created.');
        this.loadHistory();
        this.cdr.markForCheck();
      },
      error: err => {
        this.toast.error(err.error?.message || 'That import could not be discarded.');
        this.cdr.markForCheck();
      }
    });
  }

  openBatch(id: number): void {
    this.importService.getImport(id).subscribe({
      next: batch => {
        this.batch = batch;
        this.errorMessage = '';
        this.cdr.markForCheck();
      },
      error: err => {
        this.toast.error(err.error?.message || 'That import could not be opened.');
        this.cdr.markForCheck();
      }
    });
  }

  startAnother(): void {
    this.batch = null;
    this.selectedFilename = '';
    this.errorMessage = '';
    this.cdr.markForCheck();
  }

  downloadTemplate(): void {
    const blob = new Blob([CSV_TEMPLATE_CONTENT], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'campus-coin-example.csv';
    a.click();
    URL.revokeObjectURL(url);
  }

  categoriesFor(row: ImportRow): Category[] {
    if (!row.parsedType) return this.categories;
    return this.categories.filter(c => c.type === row.parsedType);
  }

  rowCategoryName(row: ImportRow): string {
    if (row.aiSuggestedCategoryId === null) return '';
    return this.categories.find(c => Number(c.id) === row.aiSuggestedCategoryId)?.name || '';
  }

  statusClasses(status: string): string {
    switch (status) {
      case 'COMMITTED':
        return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-900';
      case 'PREVIEWED':
      case 'UPLOADED':
        return 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-900';
      case 'CANCELLED':
        return 'bg-neutral-100 text-neutral-600 border-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:border-neutral-700';
      default:
        return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-400 dark:border-rose-900';
    }
  }

  rowStatusClasses(row: ImportRow): string {
    switch (row.rowStatus) {
      case 'VALID':
        return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-900';
      case 'IMPORTED':
        return 'bg-sky-50 text-sky-700 border-sky-200 dark:bg-sky-950/40 dark:text-sky-400 dark:border-sky-900';
      case 'ERROR':
        return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-400 dark:border-rose-900';
      default:
        return 'bg-neutral-100 text-neutral-600 border-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:border-neutral-700';
    }
  }

  rowTint(row: ImportRow): string {
    if (row.rowStatus === 'ERROR') {
      return 'bg-rose-50/40 dark:bg-rose-950/10';
    }
    if (row.rowStatus === 'DUPLICATE') {
      return 'bg-neutral-50 dark:bg-neutral-800/30';
    }
    return '';
  }
}
