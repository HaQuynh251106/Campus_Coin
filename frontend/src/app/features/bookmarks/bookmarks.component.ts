import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { TipService } from '../../core/services/tip.service';
import { ToastService } from '../../core/services/toast.service';
import { Bookmark } from '../../core/models/tip.model';

@Component({
  selector: 'app-bookmarks',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, IconComponent, BreadcrumbsComponent],
  template: `
    <div class="max-w-4xl mx-auto px-4 md:px-8 py-6 space-y-6">
      <app-breadcrumbs [items]="[{ label: 'Saving Tips', url: '/app/tips' }, { label: 'Saved Tips' }]"></app-breadcrumbs>

      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Saved Tips
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400 mt-1">
            Advice you kept, with your own notes. Saved here, not on the dashboard — pin a tip there
            if you want it on the home screen.
          </p>
        </div>
        <a
          routerLink="/app/tips"
          class="px-4 py-2 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-sm transition-colors flex items-center gap-2"
        >
          <app-icon name="insights" [size]="15" strokeWidth="1.5"></app-icon>
          <span>Browse tips</span>
        </a>
      </div>

      @if (isLoading) {
        <div class="card-brutal p-10 text-center text-sm text-neutral-500 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          Loading your saved tips…
        </div>
      } @else if (bookmarks.length === 0) {
        <div class="card-brutal p-10 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl">
          <div class="w-12 h-12 rounded-xl bg-amber-500/10 border border-amber-500/25 flex items-center justify-center text-amber-600 dark:text-amber-400 mx-auto mb-3 shadow-xs">
            <app-icon name="bookmark" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100">Nothing saved yet</h3>
          <p class="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
            Save a tip you want to come back to, and add a note about how you plan to use it.
          </p>
          <a
            routerLink="/app/tips"
            class="inline-block mt-4 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium py-2 px-4 rounded-lg text-sm shadow-xs transition-colors"
          >
            See this month's tips
          </a>
        </div>
      } @else {
        <div class="space-y-3">
          @for (bm of bookmarks; track bm.id) {
            <div class="card-brutal p-5 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
              <div class="flex flex-wrap items-start justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400 border border-neutral-200 dark:border-neutral-700">
                      {{ monthLabel(bm.tipMonth) }}
                    </span>
                    <!--
                      The tip's own state, shown because it is what the row carries. A dismissed
                      tip is still kept here on purpose — that is the difference between keeping
                      an item and displaying it on the dashboard.
                    -->
                    @if (bm.tipState === 'DISMISSED') {
                      <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-neutral-100 dark:bg-neutral-800 text-neutral-500 border border-neutral-200 dark:border-neutral-700">
                        no longer on dashboard
                      </span>
                    } @else if (bm.tipState === 'PINNED') {
                      <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                        pinned
                      </span>
                    }
                  </div>
                  <h3 class="font-semibold text-sm sm:text-base text-neutral-900 dark:text-neutral-100 mt-2">
                    {{ bm.tipTitle }}
                  </h3>
                  <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-400 mt-1.5 leading-relaxed">
                    {{ bm.tipBody }}
                  </p>
                </div>

                @if (bm.tipPotentialSaving > 0) {
                  <div class="text-right shrink-0">
                    <span class="block text-[10px] uppercase font-medium tracking-wider text-neutral-500">Could save</span>
                    <span class="font-semibold text-base text-emerald-600 dark:text-emerald-400 tabular-nums">
                      \${{ bm.tipPotentialSaving.toFixed(2) }}
                    </span>
                  </div>
                }
              </div>

              <!-- Note: the one thing a bookmark adds. -->
              <div class="mt-4 pt-3 border-t border-neutral-100 dark:border-neutral-800">
                @if (editingNoteId === bm.id) {
                  <div class="space-y-2">
                    <textarea
                      [(ngModel)]="noteDraft"
                      rows="2"
                      maxlength="255"
                      placeholder="Why did you keep this? What will you try?"
                      class="input-brutal text-xs resize-none"
                    ></textarea>
                    <div class="flex items-center gap-2">
                      <button type="button" (click)="saveNote(bm)"
                        [disabled]="isSavingNote"
                        class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-neutral-950 font-medium py-1.5 px-3 rounded-lg text-xs shadow-xs transition-colors cursor-pointer">
                        {{ isSavingNote ? 'Saving…' : 'Save Note' }}
                      </button>
                      <button type="button" (click)="cancelNote()"
                        class="px-3 py-1.5 border border-neutral-200 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-lg text-xs transition-colors cursor-pointer">
                        Cancel
                      </button>
                      <span class="text-[10px] text-neutral-400 ml-auto">{{ noteDraft.length }}/255</span>
                    </div>
                  </div>
                } @else {
                  <div class="flex flex-wrap items-center gap-3">
                    @if (bm.note) {
                      <p class="text-xs text-neutral-700 dark:text-neutral-300 italic flex-1 min-w-0">
                        “{{ bm.note }}”
                      </p>
                    } @else {
                      <p class="text-xs text-neutral-400 italic flex-1 min-w-0">No note yet</p>
                    }
                    <div class="flex items-center gap-3 shrink-0">
                      <button type="button" (click)="startNote(bm)"
                        class="text-xs font-semibold text-sky-600 hover:text-sky-800 dark:text-sky-400 cursor-pointer">
                        {{ bm.note ? 'Edit note' : 'Add note' }}
                      </button>
                      <button type="button" (click)="remove(bm)"
                        class="text-xs font-semibold text-rose-600 hover:text-rose-800 cursor-pointer">
                        Remove
                      </button>
                    </div>
                  </div>
                }
              </div>
            </div>
          }
        </div>

        <p class="text-[11px] text-neutral-400">
          {{ bookmarks.length }} saved tip{{ bookmarks.length === 1 ? '' : 's' }}
        </p>
      }
    </div>
  `
})
export class BookmarksComponent implements OnInit {
  private tipService = inject(TipService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  bookmarks: Bookmark[] = [];
  isLoading = true;

  editingNoteId: number | null = null;
  noteDraft = '';
  isSavingNote = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.tipService.getBookmarks().subscribe({
      next: list => {

        this.bookmarks = list || [];
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.isLoading = false;
        this.toast.error('Could not load your saved tips.');
        this.cdr.markForCheck();
      }
    });
  }

  startNote(bm: Bookmark): void {
    this.editingNoteId = bm.id;
    this.noteDraft = bm.note || '';
    this.cdr.markForCheck();
  }

  cancelNote(): void {
    this.editingNoteId = null;
    this.noteDraft = '';
    this.cdr.markForCheck();
  }

  saveNote(bm: Bookmark): void {
    this.isSavingNote = true;

    const note = this.noteDraft.trim();
    this.tipService.updateBookmarkNote(bm.id, note).subscribe({
      next: updated => {
        this.bookmarks = this.bookmarks.map(b => (b.id === updated.id ? updated : b));
        this.isSavingNote = false;
        this.editingNoteId = null;
        this.noteDraft = '';
        this.toast.success(note ? 'Note saved.' : 'Note cleared.');
        this.cdr.markForCheck();
      },
      error: err => {
        this.isSavingNote = false;
        this.toast.error(err.error?.message || 'Could not save the note.');
        this.cdr.markForCheck();
      }
    });
  }

  async remove(bm: Bookmark): Promise<void> {
    const ok = await this.toast.confirm(
      'Remove Saved Tip',
      'Remove this tip from your saved list? The tip itself is not dismissed and stays on this month\'s tips page.',
      'Remove',
      'Cancel',
      true
    );
    if (!ok) return;

    this.tipService.deleteBookmark(bm.id).subscribe({
      next: () => {
        this.bookmarks = this.bookmarks.filter(b => b.id !== bm.id);
        this.toast.success('Removed from saved tips.');
        this.cdr.markForCheck();
      },
      error: err => {
        this.toast.error(err.error?.message || 'Could not remove the saved tip.');
      }
    });
  }

  monthLabel(month: string): string {
    const [y, m] = (month || '').split('-');
    const names = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    const name = names[parseInt(m, 10) - 1];
    return name ? `${name} ${y}` : month;
  }
}
