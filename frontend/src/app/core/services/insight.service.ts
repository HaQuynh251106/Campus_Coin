import { Injectable, signal, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { MonthlyInsight } from '../models/insight.model';
import { MOCK_INSIGHTS } from '../../mock-data/insights.mock';

@Injectable({
  providedIn: 'root'
})
export class InsightService {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  private insightsList = signal<MonthlyInsight[]>([...MOCK_INSIGHTS]);

  constructor() {
    this.restoreBookmarks();
  }

  private restoreBookmarks(): void {
    if (!this.isBrowser) return;
    const stored = localStorage.getItem('campus_coin_bookmarked_insights');
    if (stored) {
      try {
        const bookmarkedIds: string[] = JSON.parse(stored);
        this.insightsList.update(list =>
          list.map(ins => ({
            ...ins,
            isBookmarked: bookmarkedIds.includes(ins.id)
          }))
        );
      } catch (e) {
        console.error('Failed to parse bookmarked insights:', e);
      }
    }
  }

  getInsights(): Observable<MonthlyInsight[]> {
    return of(this.insightsList());
  }

  getCurrentInsight(): Observable<MonthlyInsight> {
    const current = this.insightsList()[0];
    return of(current);
  }

  toggleBookmark(insightId: string): Observable<boolean> {
    let nextState = false;
    this.insightsList.update(list =>
      list.map(ins => {
        if (ins.id === insightId) {
          nextState = !ins.isBookmarked;
          return { ...ins, isBookmarked: nextState };
        }
        return ins;
      })
    );

    if (this.isBrowser) {
      const bookmarkedIds = this.insightsList()
        .filter(ins => ins.isBookmarked)
        .map(ins => ins.id);
      localStorage.setItem('campus_coin_bookmarked_insights', JSON.stringify(bookmarkedIds));
    }

    return of(nextState);
  }
}
