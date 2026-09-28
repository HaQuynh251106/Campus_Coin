import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  RecentAction,
  RecentActivity,
  RecentActivityListResponse
} from '../models/recent-activity.model';

@Injectable({
  providedIn: 'root'
})
export class RecentActivityService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/recent-activity`;

  list(limit = 10): Observable<RecentActivity[]> {
    const params = new HttpParams().set('limit', String(limit));

    return this.http
      .get<RecentActivityListResponse>(this.baseUrl, { params })
      .pipe(map(res => res.entries || []));
  }

  record(transactionId: number, action: RecentAction): Observable<RecentActivity> {
    return this.http.post<RecentActivity>(this.baseUrl, { transactionId, action });
  }
}
