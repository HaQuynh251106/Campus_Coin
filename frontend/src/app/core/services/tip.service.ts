import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SavingTip, TipMonthsResponse, Bookmark, TipState } from '../models/tip.model';

@Injectable({
  providedIn: 'root'
})
export class TipService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1`;

  readonly tips = signal<SavingTip[]>([]);
  readonly bookmarks = signal<Bookmark[]>([]);

  getTips(month?: string): Observable<SavingTip[]> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);

    return this.http.get<{ periodMonth: string; tips: SavingTip[] }>(`${this.baseUrl}/tips`, { params }).pipe(
      map(res => res.tips || []),
      tap(list => this.tips.set(list))
    );
  }

  getTipMonths(): Observable<TipMonthsResponse> {
    return this.http.get<TipMonthsResponse>(`${this.baseUrl}/tips/months`);
  }

  generateTips(): Observable<SavingTip[]> {
    return this.http.post<{ periodMonth: string; tips: SavingTip[] }>(`${this.baseUrl}/tips/generate`, {}).pipe(
      map(res => res.tips || []),
      tap(list => this.tips.set(list))
    );
  }

  setTipState(id: number, state: TipState): Observable<SavingTip> {
    return this.http.post<SavingTip>(`${this.baseUrl}/tips/${id}/state`, { state }).pipe(
      tap(updated => {
        this.tips.update(curr => curr.map(t => t.id === id ? updated : t));
      })
    );
  }

  // --- Bookmarks (M10) ---

  getBookmarks(): Observable<Bookmark[]> {
    return this.http.get<Bookmark[]>(`${this.baseUrl}/bookmarks`).pipe(
      tap(list => this.bookmarks.set(list))
    );
  }

  createBookmark(tipId: number, note?: string): Observable<Bookmark> {
    const payload: any = {
      itemType: 'TIP',
      itemId: tipId
    };
    if (note) payload.note = note;

    return this.http.post<Bookmark>(`${this.baseUrl}/bookmarks`, payload).pipe(
      tap(bm => this.bookmarks.update(curr => [bm, ...curr]))
    );
  }

  updateBookmarkNote(id: number, note: string): Observable<Bookmark> {
    return this.http.patch<Bookmark>(`${this.baseUrl}/bookmarks/${id}`, { note }).pipe(
      tap(updated => {
        this.bookmarks.update(curr => curr.map(b => b.id === id ? updated : b));
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
}
