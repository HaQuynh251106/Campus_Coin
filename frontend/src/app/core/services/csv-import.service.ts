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

export const CSV_TEMPLATE_COLUMNS = ['date', 'amount', 'type', 'description', 'category'] as const;

export const CSV_TEMPLATE_CONTENT =
  'date,amount,type,description,category\n' +
  '2026-09-24,12.50,EXPENSE,Campus cafe lunch,Food\n' +
  '2026-09-20,42.00,EXPENSE,Textbook,Education\n' +
  '2026-09-15,850.00,INCOME,Part-time job,Part-Time Job\n';

@Injectable({
  providedIn: 'root'
})
export class CsvImportService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/imports`;

  readonly batches = signal<ImportSummary[]>([]);

  readonly currentBatch = signal<ImportBatch | null>(null);

  listImports(limit = 20): Observable<ImportSummary[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<ImportBatchListResponse>(this.baseUrl, { params }).pipe(
      tap(res => this.batches.set(res.entries || [])),
      map(res => res.entries || [])
    );
  }

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

  setRowCategory(batchId: number, rowId: number, categoryId: number): Observable<ImportRow> {
    return this.http
      .patch<ImportRow>(`${this.baseUrl}/${batchId}/rows/${rowId}`, { categoryId })
      .pipe(tap(row => this.replaceRow(row)));
  }

  commitImport(batchId: number): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`${this.baseUrl}/${batchId}/commit`, {}).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  cancelImport(batchId: number): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`${this.baseUrl}/${batchId}/cancel`, {}).pipe(
      tap(batch => this.currentBatch.set(batch))
    );
  }

  private replaceRow(row: ImportRow): void {
    this.currentBatch.update(batch => {
      if (!batch) return batch;
      return { ...batch, rows: batch.rows.map(r => (r.id === row.id ? row : r)) };
    });
  }
}
