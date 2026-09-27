import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AnomalyScanResult,
  FlaggedTransaction,
  FlaggedTransactionListResponse
} from '../models/anomaly.model';

/**
 * Module 12 (UC-24) — the anomaly check.
 *
 * Read and scan, and nothing else. The flag is the detector's own verdict, so there is no method
 * here that sets one: UC-24 does not allow a client to arbitrarily flag a record. Correcting a
 * flagged record is done on the transactions screen, which is why every entry carries the
 * transaction's own id.
 */
@Injectable({
  providedIn: 'root'
})
export class AnomalyService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/anomalies`;

  /** The records the detector has marked. Empty is the ordinary case, not an error. */
  listFlagged(limit = 20): Observable<FlaggedTransaction[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http
      .get<FlaggedTransactionListResponse>(this.baseUrl, { params })
      .pipe(map(res => res.entries || []));
  }

  /**
   * Re-examines the student's live records and updates the marks. The response says what the scan
   * did — how many records it read, newly marked, un-marked — which is more useful than a bare list
   * on a screen whose whole point is "nothing changed".
   */
  scan(): Observable<AnomalyScanResult> {
    return this.http.post<AnomalyScanResult>(`${this.baseUrl}/scan`, {});
  }
}
