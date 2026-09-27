import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InsightMonthsResponse, MonthlyInsight } from '../models/insight.model';

/**
 * Module 12 (UC-17) — the monthly insight.
 *
 * Reading and generating are separate calls, and deliberately so: an empty month is a real answer,
 * not a cue to call the generator. The bill for a provider-written summary belongs to the student
 * who asked for it, so `generate` only ever runs from a click.
 */
@Injectable({
  providedIn: 'root'
})
export class InsightService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/insights`;

  /** One month's insight. Omit `month` for the current one. */
  getInsight(month?: string): Observable<MonthlyInsight> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    return this.http.get<MonthlyInsight>(this.baseUrl, { params });
  }

  /** Only the months that already have an insight, so the picker cannot offer an empty one. */
  getInsightMonths(): Observable<InsightMonthsResponse> {
    return this.http.get<InsightMonthsResponse>(`${this.baseUrl}/months`);
  }

  /**
   * Computes the month and asks for the prose. Repeating the call for a month that already has an
   * insight does not produce a second one — the response is the stored insight.
   */
  generateInsight(month?: string): Observable<MonthlyInsight> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    return this.http.post<MonthlyInsight>(`${this.baseUrl}/generate`, {}, { params });
  }
}
