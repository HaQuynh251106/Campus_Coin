import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryTagComponent } from '../../shared/components/category-tag/category-tag.component';
import { AnomalyService } from '../../core/services/anomaly.service';
import { ToastService } from '../../core/services/toast.service';
import { FlaggedTransaction } from '../../core/models/anomaly.model';

/**
 * Module 12 (UC-24) — records the anomaly check has marked.
 *
 * The screen is read-and-scan only, because that is all the module allows. A flag is the
 * detector's verdict: there is no route that lets this page set one, and the page does not pretend
 * otherwise. What it can do is explain each mark in the detector's own words and hand the student
 * the transaction's id, so correcting the record happens on the transactions screen where
 * correcting records belongs.
 *
 * The ordinary answer is an empty list, so the empty state is written as a real result rather than
 * as something that went wrong.
 */
@Component({
  selector: 'app-anomalies',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, CategoryTagComponent],
  template: `
    <div class="max-w-4xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Anomaly Check
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Entries that look like a repeat, or that ran well above your usual amount.
          </p>
        </div>

        <button
          type="button"
          (click)="scan()"
          [disabled]="isScanning"
          class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
        >
          <app-icon name="search" [size]="15" strokeWidth="1.75"></app-icon>
          <span>{{ isScanning ? 'Checking…' : 'Check My Records' }}</span>
        </button>
      </div>

      <!-- What the last scan did. Shown because "nothing changed" is a real, useful answer. -->
      @if (scanResult) {
        <div class="card-brutal p-4 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500 mb-2">
            Last check
          </span>
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div>
              <span class="font-semibold text-lg text-neutral-900 dark:text-neutral-100 tabular-nums block">{{ scanResult.examined }}</span>
              <span class="text-[10px] text-neutral-500">records read</span>
            </div>
            <div>
              <span
                class="font-semibold text-lg tabular-nums block"
                [class.text-amber-600]="scanResult.flagged > 0"
                [class.dark:text-amber-400]="scanResult.flagged > 0"
                [class.text-neutral-900]="scanResult.flagged === 0"
                [class.dark:text-neutral-100]="scanResult.flagged === 0"
              >{{ scanResult.flagged }}</span>
              <span class="text-[10px] text-neutral-500">newly marked</span>
            </div>
            <div>
              <span
                class="font-semibold text-lg tabular-nums block"
                [class.text-emerald-600]="scanResult.cleared > 0"
                [class.dark:text-emerald-400]="scanResult.cleared > 0"
                [class.text-neutral-900]="scanResult.cleared === 0"
                [class.dark:text-neutral-100]="scanResult.cleared === 0"
              >{{ scanResult.cleared }}</span>
              <span class="text-[10px] text-neutral-500">no longer marked</span>
            </div>
            <div>
              <span class="font-semibold text-lg text-neutral-900 dark:text-neutral-100 tabular-nums block">{{ scanResult.unchanged }}</span>
              <span class="text-[10px] text-neutral-500">unchanged</span>
            </div>
          </div>
        </div>
      }

      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Checking your records…
        </div>
      } @else if (entries.length === 0) {
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/25 flex items-center justify-center mx-auto mb-3">
            <app-icon name="check-circle" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">
            Nothing looks out of the ordinary
          </h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
            No entry of yours is currently marked as a likely repeat or as an unusual amount. This is
            the usual result — press Check My Records after adding entries to look again.
          </p>
        </div>
      } @else {
        <div class="space-y-3">
          @for (entry of entries; track entry.transactionId + '-' + entry.flagType) {
            <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
              <div class="flex flex-wrap items-start justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2 flex-wrap mb-2">
                    <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border" [ngClass]="flagClasses(entry)">
                      {{ flagLabel(entry.flagType) }}
                    </span>
                    <app-category-tag
                      [name]="entry.categoryName"
                      icon="tag"
                      color="#EAB308"
                      size="xs"
                    ></app-category-tag>
                  </div>

                  <span class="font-medium text-sm text-neutral-900 dark:text-neutral-100 block">
                    {{ entry.description || 'No description' }}
                  </span>
                  <span class="text-[11px] text-neutral-500 font-mono">
                    {{ entry.txnDate }} · entry #{{ entry.transactionId }}
                  </span>

                  <!-- The detector's own explanation, with the figures it compared. -->
                  @if (entry.flagNote) {
                    <p class="text-xs text-neutral-600 dark:text-neutral-400 mt-2 leading-relaxed">
                      {{ entry.flagNote }}
                    </p>
                  }
                </div>

                <div class="text-right shrink-0">
                  <span
                    class="font-semibold text-base tabular-nums block"
                    [class.text-emerald-600]="entry.categoryType === 'INCOME'"
                    [class.dark:text-emerald-400]="entry.categoryType === 'INCOME'"
                    [class.text-rose-600]="entry.categoryType === 'EXPENSE'"
                    [class.dark:text-rose-400]="entry.categoryType === 'EXPENSE'"
                  >
                    {{ entry.categoryType === 'INCOME' ? '+' : '-' }}\${{ entry.amount.toFixed(2) }}
                  </span>
                </div>
              </div>

              <!--
                Correcting the record is done where records are edited. The mark itself is not
                the student's to set, so there is no control here that would change it.
              -->
              <div class="mt-4 pt-3 border-t border-neutral-100 dark:border-neutral-800">
                <a
                  routerLink="/app/quick-add"
                  class="text-xs font-semibold text-amber-700 dark:text-amber-400 hover:underline inline-flex items-center gap-1"
                >
                  <app-icon name="edit-2" [size]="12" strokeWidth="1.5"></app-icon>
                  <span>Review or correct this entry</span>
                </a>
              </div>
            </div>
          }
        </div>
      }
    </div>
  `
})
export class AnomaliesComponent implements OnInit {
  private anomalyService = inject(AnomalyService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  entries: FlaggedTransaction[] = [];
  scanResult: { examined: number; flagged: number; cleared: number; unchanged: number } | null = null;

  isLoading = true;
  isScanning = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.anomalyService.listFlagged().subscribe({
      next: list => {
        this.entries = list;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.toast.error('Could not load your flagged entries.');
        this.cdr.markForCheck();
      }
    });
  }

  scan(): void {
    this.isScanning = true;
    this.anomalyService.scan().subscribe({
      next: res => {
        this.isScanning = false;
        // The scan's response carries the list as it stands afterwards, so there is no second read.
        this.scanResult = {
          examined: res.examined,
          flagged: res.flagged,
          cleared: res.cleared,
          unchanged: res.unchanged
        };
        this.entries = res.entries || [];
        this.toast.success(
          res.flagged > 0
            ? `${res.flagged} entr${res.flagged === 1 ? 'y' : 'ies'} marked.`
            : 'No new marks — nothing looks out of the ordinary.'
        );
        this.cdr.markForCheck();
      },
      error: err => {
        this.isScanning = false;
        this.toast.error(err.error?.message || 'The check could not be completed.');
        this.cdr.markForCheck();
      }
    });
  }

  flagLabel(type: string): string {
    switch (type) {
      case 'DUPLICATE':
        return 'Looks like a repeat';
      case 'UNUSUAL_AMOUNT':
        return 'Unusual amount';
      default:
        return type;
    }
  }

  flagClasses(entry: FlaggedTransaction): string {
    return entry.flagType === 'DUPLICATE'
      ? 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-900'
      : 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-400 dark:border-rose-900';
  }
}
