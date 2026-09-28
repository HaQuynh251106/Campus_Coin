import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { CategoryTagComponent } from '../../shared/components/category-tag/category-tag.component';
import { RecentActivityService } from '../../core/services/recent-activity.service';
import { ToastService } from '../../core/services/toast.service';
import { RecentActivity } from '../../core/models/recent-activity.model';

@Component({
  selector: 'app-recent-activity',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, IconComponent, CategoryTagComponent],
  template: `
    <div class="max-w-3xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Recent Activity
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Entries you opened or changed lately, most recent first.
          </p>
        </div>

        <button
          type="button"
          (click)="reload()"
          [disabled]="isLoading"
          class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:opacity-50 rounded-lg text-sm transition-colors cursor-pointer flex items-center gap-2"
        >
          <app-icon name="repeat" [size]="15" strokeWidth="1.5"></app-icon>
          <span>Refresh</span>
        </button>
      </div>

      <!--
        Recording an action lives here rather than being fired on every page view, so the list only
        ever holds actions the student actually took. The id is typed in because the backend names a
        record by id and there is no "recently added" list on this contract.
      -->
      <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
        <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mb-1">Log an action</h3>
        <p class="text-xs text-neutral-500 mb-4">
          Record that you opened or changed one of your entries. The time is set by the server.
        </p>

        <div class="flex flex-wrap items-end gap-3">
          <div>
            <label for="activity-tx-id" class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Entry number
            </label>
            <input
              id="activity-tx-id"
              type="number"
              min="1"
              [(ngModel)]="newTransactionId"
              placeholder="e.g. 31"
              class="input-brutal !w-36"
            />
          </div>

          <div>
            <label for="activity-action" class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Action
            </label>
            <select id="activity-action" [(ngModel)]="newAction" class="input-brutal !w-40">
              <option value="VIEWED">Opened to read</option>
              <option value="EDITED">Changed</option>
            </select>
          </div>

          <button
            type="button"
            (click)="record()"
            [disabled]="isRecording || !newTransactionId"
            class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors cursor-pointer"
          >
            {{ isRecording ? 'Recording…' : 'Record' }}
          </button>
        </div>

        @if (recordError) {
          <div class="mt-3 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-semibold flex items-start gap-2">
            <app-icon name="alert-triangle" [size]="14" strokeWidth="1.5" className="mt-0.5 shrink-0"></app-icon>
            <span>{{ recordError }}</span>
          </div>
        }
      </div>

      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Loading your activity…
        </div>
      } @else if (entries.length === 0) {
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-neutral-100 dark:bg-neutral-800 text-neutral-500 border border-neutral-200 dark:border-neutral-700 flex items-center justify-center mx-auto mb-3">
            <app-icon name="home-sub" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">
            Nothing here yet
          </h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
            This page shows the entries you have opened or changed. Nothing has been recorded for
            your account so far — use Log an action above to record one.
          </p>
        </div>
      } @else {
        <div class="card-brutal divide-y divide-neutral-100 dark:divide-neutral-800 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl overflow-hidden">
          @for (entry of entries; track entry.transactionId + '-' + entry.action + '-' + entry.occurredAt) {
            <div class="p-4 flex flex-wrap items-start justify-between gap-3">
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2 flex-wrap mb-1.5">
                  <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold border" [ngClass]="actionClasses(entry.action)">
                    {{ entry.action === 'VIEWED' ? 'Opened' : 'Changed' }}
                  </span>
                  <app-category-tag
                    [name]="entryCategoryLabel(entry)"
                    icon="tag"
                    color="#EAB308"
                    size="xs"
                  ></app-category-tag>
                </div>

                <span class="font-medium text-sm text-neutral-900 dark:text-neutral-100 block truncate">
                  {{ entry.description || 'No description' }}
                </span>
                <span class="text-[11px] text-neutral-500 font-mono">
                  entry for {{ entry.txnDate }} · #{{ entry.transactionId }}
                </span>
              </div>

              <div class="text-right shrink-0">
                <span
                  class="font-semibold text-sm tabular-nums block"
                  [class.text-emerald-600]="entry.categoryType === 'INCOME'"
                  [class.dark:text-emerald-400]="entry.categoryType === 'INCOME'"
                  [class.text-rose-600]="entry.categoryType === 'EXPENSE'"
                  [class.dark:text-rose-400]="entry.categoryType === 'EXPENSE'"
                >
                  {{ entry.categoryType === 'INCOME' ? '+' : '-' }}\${{ entry.amount.toFixed(2) }}
                </span>
                <span class="text-[10px] text-neutral-500">
                  {{ entry.occurredAt | date: 'd MMM, HH:mm' }}
                </span>
              </div>
            </div>
          }
        </div>
      }
    </div>
  `
})
export class RecentActivityComponent implements OnInit {
  private recentService = inject(RecentActivityService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  entries: RecentActivity[] = [];
  isLoading = true;

  newTransactionId: number | null = null;
  newAction: 'VIEWED' | 'EDITED' = 'VIEWED';
  isRecording = false;
  recordError = '';

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.isLoading = true;
    this.recentService.list().subscribe({
      next: list => {
        this.entries = list;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.toast.error('Could not load your recent activity.');
        this.cdr.markForCheck();
      }
    });
  }

  record(): void {
    if (!this.newTransactionId) return;
    this.isRecording = true;
    this.recordError = '';

    this.recentService.record(Number(this.newTransactionId), this.newAction).subscribe({
      next: () => {
        this.isRecording = false;

        this.reload();
        this.toast.success('Action recorded.');
        this.cdr.markForCheck();
      },
      error: err => {
        this.isRecording = false;

        this.recordError =
          err.error?.message || 'That entry could not be recorded. Check the entry number and try again.';
        this.cdr.markForCheck();
      }
    });
  }

  entryCategoryLabel(entry: RecentActivity): string {
    return entry.categoryType === 'INCOME' ? 'Income' : 'Expense';
  }

  actionClasses(action: string): string {
    return action === 'EDITED'
      ? 'bg-sky-50 text-sky-700 border-sky-200 dark:bg-sky-950/40 dark:text-sky-400 dark:border-sky-900'
      : 'bg-neutral-100 text-neutral-600 border-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:border-neutral-700';
  }
}
