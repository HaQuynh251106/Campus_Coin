import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CategorySuggestion } from '../models/categorisation.model';

/**
 * Module 12 (UC-08) — the category proposal for one of the student's own records.
 *
 * The proposal is advice stored *beside* the record (BR-13). This service therefore has exactly one
 * method and no write: accepting a proposal is a normal `PATCH` on the transactions endpoint, made
 * by the screen the student is already on. A `NONE` source is a real answer — the student has no
 * learned rule for the description and no provider is configured — and the screen says so rather
 * than filling the category in anyway.
 */
@Injectable({
  providedIn: 'root'
})
export class CategorisationService {
  private http = inject(HttpClient);

  /**
   * Asks for a proposal for one already-recorded transaction. The answer may teach a keyword rule
   * (`learned`), which is what makes the second suggestion for a similar description a `RULE`.
   */
  suggestCategory(transactionId: number): Observable<CategorySuggestion> {
    return this.http.post<CategorySuggestion>(`${environment.apiUrl}/v1/ai/suggest-category`, {
      transactionId
    });
  }
}
