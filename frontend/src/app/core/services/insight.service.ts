import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InsightMonthsResponse, MonthlyInsight } from '../models/insight.model';

@Injectable({
  providedIn: 'root'
})
export class InsightService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/insights`;

  getInsight(month?: string): Observable<MonthlyInsight> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    return this.http.get<MonthlyInsight>(this.baseUrl, { params });
  }

  getInsightMonths(): Observable<InsightMonthsResponse> {
    return this.http.get<InsightMonthsResponse>(`${this.baseUrl}/months`);
  }

  generateInsight(month?: string): Observable<MonthlyInsight> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    return this.http.post<MonthlyInsight>(`${this.baseUrl}/generate`, {}, { params });
  }
}
