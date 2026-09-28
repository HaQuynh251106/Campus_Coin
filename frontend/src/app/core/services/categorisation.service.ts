import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CategorySuggestion } from '../models/categorisation.model';

@Injectable({
  providedIn: 'root'
})
export class CategorisationService {
  private http = inject(HttpClient);

  suggestCategory(transactionId: number): Observable<CategorySuggestion> {
    return this.http.post<CategorySuggestion>(`${environment.apiUrl}/v1/ai/suggest-category`, {
      transactionId
    });
  }
}
