import { CategoryType } from './category.model';

export type ImportBatchStatus = 'UPLOADED' | 'PREVIEWED' | 'COMMITTED' | 'CANCELLED' | 'FAILED';

export type ImportRowStatus = 'VALID' | 'ERROR' | 'DUPLICATE' | 'IMPORTED' | 'SKIPPED';

export type ImportRawRow = Record<string, string>;

export interface ImportRow {
  id: number;

  csvRowNo: number;
  rawData: ImportRawRow;
  parsedDate: string | null;
  parsedAmount: number | null;
  parsedType: CategoryType | null;
  parsedDescription: string | null;
  parsedCategoryName: string | null;

  resolvedCategoryId: number | null;

  aiSuggestedCategoryId: number | null;
  rowStatus: ImportRowStatus;
  errorMessage: string | null;

  transactionId: number | null;
}

export interface ImportBatch {
  id: number;
  originalFilename: string;
  status: ImportBatchStatus;

  modifiable: boolean;
  totalRows: number;

  validRows: number;
  errorRows: number;
  duplicateRows: number;
  importedRows: number;
  createdAt: string;
  committedAt: string | null;
  rows: ImportRow[];
}

export type ImportSummary = Omit<ImportBatch, 'rows'>;

export interface ImportBatchListResponse {

  limit: number;
  entries: ImportSummary[];
}

export interface UploadCsvRequest {
  filename: string;
  content: string;
}
