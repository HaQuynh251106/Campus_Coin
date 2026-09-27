import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ImportBatch,
  ImportBatchListResponse,
  ImportRow,
  ImportSummary,
  UploadCsvRequest
} from '../models/import.model';

/** The header the importer requires, in the order the example file uses. */
export const CSV_TEMPLATE_COLUMNS = ['date', 'amount', 'type', 'description', 'category'] as const;

/** An example file, so the student can see the expected shape before choosing their own. */
export const CSV_TEMPLATE_CONTENT =
  'date,amount,type,description,category\n' +
  '2026-09-24,12.50,EXPENSE,Campus cafe lunch,Food\n' +
  '2026-09-20,42.00,EXPENSE,Textbook,Education\n' +
  '2026-09-15,850.00,INCOME,Part-time job,Part-Time Job\n';

/**
 * Module 12 (UC-11) — the CSV import workflow.
 *
 * A file is uploaded, previewed, corrected and then committed, and this service keeps those steps
 * separate because the backend does. Three properties are load-bearing:
 *
 * 1. **The file is sent as JSON text in `content`.** There is no multipart endpoint, so the caller
 *    reads the chosen file as a string rather than appending it to a `FormData`.
 * 2. **The frontend never writes transactions.** Committing is what turns rows into transactions,
 *    inside `sp_apply_csv_batch`; there is no path here that posts to `/transactions`.
 * 3. **`PATCH` changes one row, never the batch.** A corrected row is re-verdicts by the server, so
 *    the response is the stored row and the preview reloads around it.
 */
@Injectable({
  providedIn: 'root'
})
export class CsvImportService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/imports`;

  readonly batches = signal<ImportSummary[]>([]);
  /** The batch currently open in the preview. Null before an upload and after leaving the screen. */
  readonly currentBatch = signal<ImportBatch | null>(null);

  /** The student's imports, most recent first. */
  listImports(limit = 20): Observable<ImportSummary[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<ImportBatchListResponse>(this.baseUrl, { params }).pipe(
      tap(res => this.batches.set(res.entries || [])),
      map(res => res.entries || [])
    );
  }

  /**
   * Uploads a file and gets the preview back in the same response. The upload does not import
   * anything — it parses, verdicts each row and stores the batch.
   */
  uploadCsv(request: UploadCsvRequest): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(this.baseUrl, request).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  getImport(batchId: number): Observable<ImportBatch> {
    return this.http.get<ImportBatch>(`${this.baseUrl}/${batchId}`).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  /**
   * Files one previewed row under the category the student chose. The server re-verdicts the row —
   * a duplicate verdict rests on the category, so changing it can change the verdict.
   */
  setRowCategory(batchId: number, rowId: number, categoryId: number): Observable<ImportRow> {
    return this.http
      .patch<ImportRow>(`${this.baseUrl}/${batchId}/rows/${rowId}`, { categoryId })
      .pipe(tap(row => this.replaceRow(row)));
  }

  /** Turns the batch's valid rows into transactions. Irreversible — the batch settles. */
  commitImport(batchId: number): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`${this.baseUrl}/${batchId}/commit`, {}).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  /** Abandons the batch. This is the only way out of a preview — there is no delete route. */
  cancelImport(batchId: number): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`${this.baseUrl}/${batchId}/cancel`, {}).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  /** Keeps the open preview consistent with one row's new state without re-reading the batch. */
  private replaceRow(row: ImportRow): void {
    this.currentBatch.update(batch => {
      if (!batch) return batch;
      return { ...batch, rows: batch.rows.map(r => (r.id === row.id ? row : r)) };
    });
  }
}
