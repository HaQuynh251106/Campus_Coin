import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SavingTip, TipMonthsResponse, Bookmark, TipState } from '../models/tip.model';

/** `GET /tips` and `POST /tips/generate` both answer with the month named once on a wrapper. */
interface TipListEnvelope {
  periodMonth: string;
  tips: SavingTip[];
}

/**
 * Module 9 (UC-18) saving tips and module 10 (UC-19) saved items.
 *
 * The one property worth holding onto: a tip's text, figures and **order** are all decided by the
 * database. The only column UC-18 lets a student change is `state`, so the list is rendered exactly
 * as it arrives and never re-sorted — sorting by `potentialSaving` would disagree with the ranking
 * that puts a pinned tip first.
 */
@Injectable({
  providedIn: 'root'
})
export class TipService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1`;

  readonly tips = signal<SavingTip[]>([]);
  readonly bookmarks = signal<Bookmark[]>([]);

  /** The month the last tips response was about. Echoed back by the API rather than assumed. */
  readonly periodMonth = signal<string | null>(null);

  /**
   * One month's tips, already ranked. Reading never generates: an empty list is the real answer for
   * a month with nothing to say, not a cue to call the generator.
   */
  getTips(month?: string): Observable<SavingTip[]> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);

    return this.http.get<TipListEnvelope>(`${this.baseUrl}/tips`, { params }).pipe(
      tap(res => {
        this.tips.set(res.tips || []);
        this.periodMonth.set(res.periodMonth ?? null);
      }),
      map(res => res.tips || [])
    );
  }

  /** Only the months that have tips, so the picker cannot offer one that returns nothing. */
  getTipMonths(): Observable<TipMonthsResponse> {
    return this.http.get<TipMonthsResponse>(`${this.baseUrl}/tips/months`);
  }

  /**
   * Runs the generator for the current month. No body — generating is for the current month only,
   * and the response is the new list, so there is no follow-up read.
   */
  generateTips(): Observable<SavingTip[]> {
    return this.http.post<TipListEnvelope>(`${this.baseUrl}/tips/generate`, {}).pipe(
      map(res => res.tips || []),
      tap(list => this.tips.set(list))
    );
  }

  /**
   * Pin, unpin or dismiss. Un-pinning is `NEW` — there is no separate unpin route, because one
   * column with three values is one write.
   *
   * DISMISSED is terminal: the API answers `400` to any attempt to move a dismissed tip back, and
   * that is a refusal rather than a retry, so the caller must treat it as final.
   */
  setTipState(id: number, state: TipState): Observable<SavingTip> {
    return this.http.post<SavingTip>(`${this.baseUrl}/tips/${id}/state`, { state }).pipe(
      tap(updated => {
        this.tips.update(curr => curr.map(t => (t.id === id ? updated : t)));
      })
    );
  }

  /** True when the API refused a state change because the tip was already dismissed. */
  static isDismissedTerminal(err: unknown): boolean {
    return (
      err instanceof HttpErrorResponse &&
      err.status === 400 &&
      err.error?.fieldErrors?.some((f: { field: string }) => f.field === 'state')
    );
  }

  // --- Bookmarks (M10) ---

  /** Saved items, newest first. The tip's own text comes with the row — never resolve it per row. */
  getBookmarks(): Observable<Bookmark[]> {
    return this.http.get<Bookmark[]>(`${this.baseUrl}/bookmarks`).pipe(
      tap(list => this.bookmarks.set(list))
    );
  }

  createBookmark(tipId: number, note?: string): Observable<Bookmark> {
    const payload: { itemType: 'TIP'; itemId: number; note?: string } = {
      itemType: 'TIP',
      itemId: tipId
    };
    if (note) payload.note = note;

    return this.http.post<Bookmark>(`${this.baseUrl}/bookmarks`, payload).pipe(
      tap(bm => this.bookmarks.update(curr => [bm, ...curr]))
    );
  }

  /** `''` clears the note, which is the one field on a bookmark that has an empty state. */
  updateBookmarkNote(id: number, note: string): Observable<Bookmark> {
    return this.http.patch<Bookmark>(`${this.baseUrl}/bookmarks/${id}`, { note }).pipe(
      tap(updated => {
        this.bookmarks.update(curr => curr.map(b => (b.id === id ? updated : b)));
      })
    );
  }

  deleteBookmark(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bookmarks/${id}`).pipe(
      tap(() => {
        this.bookmarks.update(curr => curr.filter(b => b.id !== id));
      })
    );
  }

  /** The tip is already saved, so the remedy is to find the existing row rather than to retry. */
  static isAlreadySavedError(err: unknown): boolean {
    return err instanceof HttpErrorResponse && err.status === 409;
  }
}
