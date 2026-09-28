import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AnomalyScanResult,
  FlaggedTransaction,
  FlaggedTransactionListResponse
} from '../models/anomaly.model';

@Injectable({
  providedIn: 'root'
})
export class AnomalyService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/anomalies`;

  listFlagged(limit = 20): Observable<FlaggedTransaction[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http
      .get<FlaggedTransactionListResponse>(this.baseUrl, { params })
      .pipe(map(res => res.entries || []));
  }

  scan(): Observable<AnomalyScanResult> {
    return this.http.post<AnomalyScanResult>(`${this.baseUrl}/scan`, {});
  }
}
