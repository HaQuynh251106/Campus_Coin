import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  RecentAction,
  RecentActivity,
  RecentActivityListResponse
} from '../models/recent-activity.model';

/**
 * Module 12 (UC-26) — what the student recently opened or changed.
 *
 * Both calls are here rather than split across the feature, because the feature never keeps its own
 * copy: the list is always the server's, and recording an action is a write that the server may
 * refuse. A local-only history would show entries the API knows nothing about.
 */
@Injectable({
  providedIn: 'root'
})
export class RecentActivityService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/recent-activity`;

  /** The entries, most recent first. Empty until the student opens or changes something. */
  list(limit = 10): Observable<RecentActivity[]> {
    const params = new HttpParams().set('limit', String(limit));
    // The wrapper echoes its own limit; the list is what a screen renders.
    return this.http
      .get<RecentActivityListResponse>(this.baseUrl, { params })
      .pipe(map(res => res.entries || []));
  }

  /**
   * Records that the student viewed or edited a transaction. The response is the entry as stored,
   * with the timestamp the server assigned — the client does not time this itself.
   */
  record(transactionId: number, action: RecentAction): Observable<RecentActivity> {
    return this.http.post<RecentActivity>(this.baseUrl, { transactionId, action });
  }
}
