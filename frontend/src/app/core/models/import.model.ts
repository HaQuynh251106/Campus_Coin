import { CategoryType } from './category.model';

/**
 * Module 12 (UC-11) — the CSV import workflow.
 *
 * These mirror `imports/dto` in the backend one field for field. Nothing here is guessed: the
 * importer publishes `rawData` so the preview can show the file's own text beside the values the
 * importer read from it, and it publishes `errorMessage` per row so the student is told which cell
 * was wrong rather than being handed a bare row number.
 */

/** Where a batch has got to. A batch previews within the upload request, so `UPLOADED` is transient. */
export type ImportBatchStatus = 'UPLOADED' | 'PREVIEWED' | 'COMMITTED' | 'CANCELLED' | 'FAILED';

/** What will happen to a row. `IMPORTED` only appears once the batch is committed. */
export type ImportRowStatus = 'VALID' | 'ERROR' | 'DUPLICATE' | 'IMPORTED' | 'SKIPPED';

/** One file line, as it arrived, keyed by column name. Values are the file's own cells. */
export type ImportRawRow = Record<string, string>;

export interface ImportRow {
  id: number;
  /** The line number in the file, counting the header as line 1 — the number the student sees. */
  csvRowNo: number;
  rawData: ImportRawRow;
  parsedDate: string | null;
  parsedAmount: number | null;
  parsedType: CategoryType | null;
  parsedDescription: string | null;
  parsedCategoryName: string | null;
  /** Set once the student has chosen a category for this row; the commit then uses it as-is. */
  resolvedCategoryId: number | null;
  /** Advisory only (UC-08). Never filed automatically — choosing is the student's decision. */
  aiSuggestedCategoryId: number | null;
  rowStatus: ImportRowStatus;
  errorMessage: string | null;
  /** The transaction this row became. Null until the batch is committed. */
  transactionId: number | null;
}

export interface ImportBatch {
  id: number;
  originalFilename: string;
  status: ImportBatchStatus;
  /** True while the rows can still be changed and the batch committed. */
  modifiable: boolean;
  totalRows: number;
  /** Before a commit, the rows that will be imported; after one, the rows that were. */
  validRows: number;
  errorRows: number;
  duplicateRows: number;
  importedRows: number;
  createdAt: string;
  committedAt: string | null;
  rows: ImportRow[];
}

/** One entry of the import history — the batch without its rows. */
export type ImportSummary = Omit<ImportBatch, 'rows'>;

export interface ImportBatchListResponse {
  /** Echoed so a capped list can be told apart from a student who has imported little. */
  limit: number;
  entries: ImportSummary[];
}

/** The upload body. The file travels as JSON text — there is no multipart endpoint. */
export interface UploadCsvRequest {
  filename: string;
  content: string;
}
