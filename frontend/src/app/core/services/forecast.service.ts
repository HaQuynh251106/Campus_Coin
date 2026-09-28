import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ForecastResponse } from '../models/forecast.model';

@Injectable({
  providedIn: 'root'
})
export class ForecastService {
  private http = inject(HttpClient);

  getForecast(): Observable<ForecastResponse> {
    return this.http.get<ForecastResponse>(`${environment.apiUrl}/v1/forecast`);
  }
}
