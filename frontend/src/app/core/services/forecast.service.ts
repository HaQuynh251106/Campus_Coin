import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ForecastResponse } from '../models/forecast.model';

/**
 * Module 12 (UC-25) — the next-month projection.
 *
 * One read, no parameters: the endpoint is always about the month in progress and the month after
 * it, and it returns the months it averaged over so the projection can be shown as what it is —
 * an average of the student's own recent months.
 */
@Injectable({
  providedIn: 'root'
})
export class ForecastService {
  private http = inject(HttpClient);

  getForecast(): Observable<ForecastResponse> {
    return this.http.get<ForecastResponse>(`${environment.apiUrl}/v1/forecast`);
  }
}
