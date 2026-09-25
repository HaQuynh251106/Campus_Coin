import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DashboardResponse } from '../models/dashboard.model';

@Injectable({
  providedIn: 'root'
})
export class DashboardService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/dashboard`;

  readonly dashboard = signal<DashboardResponse | null>(null);

  getDashboard(): Observable<DashboardResponse> {
    return this.http.get<DashboardResponse>(this.baseUrl).pipe(
      tap(res => this.dashboard.set(res))
    );
  }
}
