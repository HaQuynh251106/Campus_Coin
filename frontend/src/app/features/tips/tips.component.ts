import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { TipService } from '../../core/services/tip.service';
import { ToastService } from '../../core/services/toast.service';
import { SavingTip } from '../../core/models/tip.model';

/**
 * Module 9 (UC-18) — the saving tips screen.
 *
 * Three rules shape this component, and all three come from the module rather than from taste:
 *
 * 1. **The array is rendered in the order it arrives.** Pinned tips lead and the rest follow the
 *    database's ranking; sorting by `potentialSaving` would put a different tip on top than the
 *    ranking chose, and a pinned one would drift down the page. There is no `sort()` here, and no
 *    `sort` pipe.
 * 2. **Reading never generates.** An empty month is a real answer. The generator runs when the
 *    student asks for it, not because the list came back short.
 * 3. **Dismissal is terminal.** The API refuses to move a dismissed tip back, so the screen says so
 *    before the click rather than after the `400`.
 */
@Component({
  selector: 'app-tips',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  template: `
    <div class="max-w-5xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Saving Tips
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Advice built from your own spending, ranked by what following it could save you.
          </p>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <select
            [ngModel]="selectedMonth"
            (ngModelChange)="onMonthChange($event)"
            [ngModelOptions]="{ standalone: true }"
            class="input-brutal !w-auto text-xs"
            aria-label="Choose a month"
          >
            @if (selectedMonth === '') {
              <option value="">This month</option>
            }
            @for (m of months; track m) {
              <option [value]="m">{{ monthLabel(m) }}</option>
            }
          </select>

          <button
            type="button"
            (click)="generate()"
            [disabled]="isGenerating"
            class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center gap-2"
          >
            <app-icon name="sparkles" [size]="15" strokeWidth="1.75"></app-icon>
            <span>{{ isGenerating ? 'Generating…' : 'Generate Now' }}</span>
          </button>

          <a
            routerLink="/app/bookmarks"
            class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-sm transition-colors flex items-center gap-2"
          >
            <app-icon name="bookmark" [size]="15" strokeWidth="1.5"></app-icon>
            <span>Saved ({{ savedCount }})</span>
          </a>
        </div>
      </div>

      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Loading your tips…
        </div>
      } @else if (tips.length === 0) {
        <!--
          A real answer, not an error. Advice appears only once the generator has run, so nothing
          is called automatically here — generating is the student's decision.
        -->
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-amber-500/10 border border-amber-500/25 flex items-center justify-center text-amber-600 dark:text-amber-400 mx-auto mb-3 shadow-xs">
            <app-icon name="lightbulb" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">
            Nothing to suggest for {{ periodLabel() }}
          </h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
            Tips are drawn from the spending already recorded for the month. Record a few entries,
            then press Generate Now to ask for advice.
          </p>
        </div>
      } @else {
        <!-- No sort: the API's order is the contract. -->
        <div class="space-y-3">
          @for (tip of tips; track tip.id) {
            <div
              class="card-brutal p-5 bg-white dark:bg-neutral-900 border rounded-xl shadow-xs transition-colors"
              [class.border-amber-400]="tip.state === 'PINNED'"
              [class.dark:border-amber-500]="tip.state === 'PINNED'"
              [class.border-neutral-200]="tip.state !== 'PINNED'"
              [class.dark:border-neutral-800]="tip.state !== 'PINNED'"
            >
              <div class="flex flex-wrap items-start justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2 flex-wrap">
                    @if (tip.state === 'PINNED') {
                      <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                        PINNED
                      </span>
                    }
                    <h3 class="font-semibold text-sm sm:text-base text-neutral-900 dark:text-neutral-100">
                      {{ tip.title }}
                    </h3>
                  </div>
                  <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-400 mt-2 leading-relaxed">
                    {{ tip.body }}
                  </p>
                </div>

                @if (tip.potentialSaving > 0) {
                  <div class="text-right shrink-0">
                    <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Could save</span>
                    <span class="font-semibold text-base text-emerald-600 dark:text-emerald-400 tabular-nums">
                      \${{ tip.potentialSaving.toFixed(2) }}
                    </span>
                  </div>
                }
              </div>

              <!--
                Every action here writes the same one column through the same route. Dismissing is
                final, so it asks first and says so in the question.
              -->
              <div class="flex items-center gap-2 mt-4 pt-3 border-t border-neutral-100 dark:border-neutral-800">
                @if (tip.state === 'PINNED') {
                  <button type="button" (click)="setState(tip, 'NEW')"
                    class="text-xs font-semibold text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 cursor-pointer">
                    Unpin
                  </button>
                } @else {
                  <button type="button" (click)="setState(tip, 'PINNED')"
                    class="text-xs font-semibold text-amber-600 hover:text-amber-800 dark:text-amber-400 cursor-pointer">
                    Pin to top
                  </button>
                }

                <span class="text-neutral-300 dark:text-neutral-700">|</span>

                <button type="button" (click)="saveTip(tip)"
                  [disabled]="savedTipIds.has(tip.id)"
                  class="text-xs font-semibold text-sky-600 hover:text-sky-800 dark:text-sky-400 disabled:opacity-50 disabled:cursor-default cursor-pointer flex items-center gap-1">
                  <app-icon name="bookmark" [size]="12" strokeWidth="1.5"></app-icon>
                  <span>{{ savedTipIds.has(tip.id) ? 'Saved' : 'Save for later' }}</span>
                </button>

                <span class="text-neutral-300 dark:text-neutral-700">|</span>

                <button type="button" (click)="dismiss(tip)"
                  class="text-xs font-semibold text-rose-600 hover:text-rose-800 cursor-pointer">
                  Dismiss
                </button>
              </div>
            </div>
          }
        </div>
      }
    </div>
  `
})
export class TipsComponent implements OnInit {
  private tipService = inject(TipService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  tips: SavingTip[] = [];
  months: string[] = [];
  savedTipIds = new Set<number>();

  selectedMonth = '';
  isLoading = true;
  isGenerating = false;

  ngOnInit(): void {
    this.loadMonths();
    this.loadTips();
    this.loadSavedIds();
  }

  private loadMonths(): void {
    this.tipService.getTipMonths().subscribe({
      next: res => {
        this.months = res.months || [];
        this.cdr.markForCheck();
      },
      error: () => {}
    });
  }

  private loadSavedIds(): void {
    this.tipService.getBookmarks().subscribe({
      next: list => {
        this.savedTipIds = new Set((list || []).map(b => b.tipId));
        this.cdr.markForCheck();
      },
      error: () => {}
    });
  }

  loadTips(): void {
    this.isLoading = true;
    this.tipService.getTips(this.selectedMonth || undefined).subscribe({
      next: tips => {
        this.tips = tips;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: err => {
        this.isLoading = false;
        // A bad month is refused rather than read as another one, so the picker goes back to what
        // was showing instead of quietly displaying a different month's advice.
        if (err.status === 400) {
          this.toast.warning(err.error?.message || 'That month is not valid. Showing the current month.');
          this.selectedMonth = '';
        } else {
          this.toast.error('Could not load your tips.');
        }
        this.cdr.markForCheck();
      }
    });
  }

  onMonthChange(month: string): void {
    this.selectedMonth = month;
    this.loadTips();
  }

  generate(): void {
    this.isGenerating = true;
    this.tipService.generateTips().subscribe({
      next: tips => {
        // The generator's own response is the new list, so there is no follow-up read.
        this.tips = tips;
        this.selectedMonth = '';
        this.isGenerating = false;
        this.toast.success(
          tips.length > 0 ? `Generated ${tips.length} tip${tips.length === 1 ? '' : 's'}.` : 'Nothing to suggest yet — record a few more entries first.'
        );
        this.loadMonths();
        this.cdr.markForCheck();
      },
      error: err => {
        this.isGenerating = false;
        this.toast.error(err.error?.message || 'Could not generate tips.');
        this.cdr.markForCheck();
      }
    });
  }

  setState(tip: SavingTip, state: 'NEW' | 'PINNED'): void {
    this.tipService.setTipState(tip.id, state).subscribe({
      next: updated => {
        // A pinned tip must lead the list, and the API returns it that way on the next read. The
        // local array is re-read rather than re-sorted, so the ranking stays the database's.
        this.loadTips();
        this.toast.success(state === 'PINNED' ? 'Tip pinned.' : 'Tip unpinned.');
      },
      error: err => {
        this.toast.error(err.error?.message || 'Could not change the tip.');
      }
    });
  }

  async dismiss(tip: SavingTip): Promise<void> {
    const ok = await this.toast.confirm(
      'Dismiss This Tip',
      'A dismissed tip is gone for good — it will not be shown again, and this cannot be undone. Save it first if you want to keep the advice.',
      'Dismiss',
      'Keep',
      true
    );
    if (!ok) return;

    this.tipService.setTipState(tip.id, 'DISMISSED').subscribe({
      next: () => {
        this.tips = this.tips.filter(t => t.id !== tip.id);
        this.loadMonths();
        this.toast.success('Tip dismissed.');
        this.cdr.markForCheck();
      },
      error: err => {
        // `400` on `state` means the tip was already dismissed elsewhere, so the list is stale.
        if (TipService.isDismissedTerminal(err)) {
          this.toast.warning('That tip was already dismissed.');
          this.loadTips();
          return;
        }
        this.toast.error(err.error?.message || 'Could not dismiss the tip.');
      }
    });
  }

  saveTip(tip: SavingTip): void {
    this.tipService.createBookmark(tip.id).subscribe({
      next: () => {
        this.savedTipIds = new Set([...this.savedTipIds, tip.id]);
        this.toast.success('Saved. Add a note from the Saved Tips page.');
        this.cdr.markForCheck();
      },
      error: err => {
        // Already saved — that is not a failure, and the user's intent is satisfied.
        if (TipService.isAlreadySavedError(err)) {
          this.savedTipIds = new Set([...this.savedTipIds, tip.id]);
          this.toast.info('That tip is already saved.');
          this.cdr.markForCheck();
          return;
        }
        this.toast.error(err.error?.message || 'Could not save the tip.');
      }
    });
  }

  get savedCount(): number {
    return this.savedTipIds.size;
  }

  /** `2026-09` → `September 2026`. The API sends `yyyy-MM` and no human label. */
  monthLabel(month: string): string {
    const [y, m] = (month || '').split('-');
    const names = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    const name = names[parseInt(m, 10) - 1];
    return name ? `${name} ${y}` : month;
  }

  periodLabel(): string {
    const month = this.selectedMonth || this.tipService.periodMonth();
    return month ? this.monthLabel(month) : 'this month';
  }
}
