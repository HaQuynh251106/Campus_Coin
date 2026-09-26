# CSV import API — endpoints 62–67

UC-11: a student uploads a file of records, reviews what the importer made of each row, corrects any
row it read wrong, and imports what is left.

Base path `/api/v1`. Every endpoint needs a bearer token with the `STUDENT` role and operates only on
the caller's own data. Another student's batch answers exactly as a batch that does not exist does.

## Table of contents

1. [Scope](#1-scope)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [The workflow — 62–67](#4-the-workflow--6267)
5. [Status codes](#5-status-codes)
6. [Security properties](#6-security-properties)
7. [Angular integration notes](#7-angular-integration-notes)
8. [Traceability](#8-traceability)

## 1. Scope

Six operations on four paths, describing one workflow: **upload to preview, look at the preview,
correct a row's category, commit what is left, or abandon it.**

The division of labour is the point of the design:

- **The server parses and decides.** It reads the file, types each row, refuses the rows it cannot
  read, and marks the rows the student appears to have already. The client does not parse CSV and does
  not decide what is a duplicate.
- **The preview is stored, not recomputed.** An upload writes an `import_batches` row and its
  `import_rows`, so the preview a student sees is the same one the commit acts on. A client that
  reloaded the screen sees the stored preview, not a fresh parse of a file the server no longer has.
- **Nothing is imported until the commit.** The upload ends with the batch `PREVIEWED` and no
  transaction in existence. Committing twice is refused.

## 2. Endpoints at a glance

| # | Method | Path | UC-11 step | Success |
|---|--------|------|-----------|---------|
| 62 | POST | `/api/v1/imports` | A1 — upload and preview | `201` the stored preview |
| 63 | GET | `/api/v1/imports` | the import history | `200` the caller's imports |
| 64 | GET | `/api/v1/imports/{batchId}` | B5 — one preview, with its rows | `200` the batch and its rows |
| 65 | PATCH | `/api/v1/imports/{batchId}/rows/{rowId}` | B6 — choose a row's category | `200` the row |
| 66 | POST | `/api/v1/imports/{batchId}/commit` | B9 — import it | `200` the batch after importing |
| 67 | POST | `/api/v1/imports/{batchId}/cancel` | A2 — abandon it | `200` the cancelled batch |

### Parameters

| Endpoint | Parameter | In | Default | Notes |
|---|---|---|---|---|
| 63 | `limit` | query | `20` | At most `100`; a larger value is `400`, not a clamp |

No endpoint takes a user id. Identity comes from the bearer token.

### Authentication

Bearer token, role `STUDENT`. No token or an invalid one → `401 UNAUTHENTICATED`. A token held by a
disabled account → `401` as well, because the session is revoked and `token_version` no longer matches.

## 3. Field reference

### `UploadCsvRequest` (62)

| Field | Type | Required | Notes |
|---|---|---|---|
| `filename` | string | yes | At most 255 characters. Stored and echoed; **never opened or resolved as a path** |
| `content` | string | yes | The file's text, decoded UTF-8. Bounded in the service, not by `@Size` — see below |

`content`'s size is checked by the parser rather than by a bean-validation annotation, because the bound
is *"how many rows this server will store"* and the parser is what counts rows. A `@Size` would measure
characters, which is a different question. The limits are **2000 rows** and about **2,000,000
characters**; a larger file is refused rather than trimmed, because a preview of part of a file would
be showing the student something other than their file.

### `ImportBatchResponse` (62, 64, 66, 67)

| Field | Type | Notes |
|---|---|---|
| `id` | number | The batch identifier |
| `originalFilename` | string | As the student supplied it |
| `status` | `ImportBatchStatus` | `UPLOADED` \| `PREVIEWED` \| `COMMITTED` \| `CANCELLED` \| `FAILED` |
| `modifiable` | boolean | True while the rows can still change and the batch can be committed |
| `totalRows` | number | Every row of the file |
| `validRows` | number | Before a commit, the rows that will be imported; after one, the rows that were |
| `errorRows` | number | Rows that could not be imported |
| `duplicateRows` | number | Rows the student appears to have recorded already |
| `importedRows` | number | Rows that became transactions. Zero until committed |
| `createdAt` | datetime | |
| `committedAt` | datetime | Null until committed |
| `rows` | array | Every row, in file order (64, 66, 67). Present on 62 as well |

### `ImportSummaryResponse` (63)

The same shape as `ImportBatchResponse` **without `rows`**. The list is summary lines; a student with
ten imports should not receive ten files' worth of rows to render ten lines.

### `ImportRowResponse` (64, 65, 66, 67)

| Field | Type | Notes |
|---|---|---|
| `id` | number | The row identifier, used to override its category |
| `csvRowNo` | number | Line number in the file, **counting the header as line 1** — the number the student sees in their spreadsheet |
| `rawData` | object | The line as it arrived, keyed by column name |
| `parsedDate` | date | Null when the date could not be read |
| `parsedAmount` | number | Null when the amount could not be read |
| `parsedType` | `CategoryType` | Null when the type could not be read |
| `parsedDescription` | string | Null when the file had none |
| `parsedCategoryName` | string | The name from the file, unresolved. Null when the file had none |
| `resolvedCategoryId` | number | Null until the student chooses a category (65) |
| `aiSuggestedCategoryId` | number | Where the system would file it, from the student's own learned rules (UC-08). Advice only |
| `rowStatus` | `ImportRowStatus` | `VALID` \| `ERROR` \| `DUPLICATE` \| `IMPORTED` \| `SKIPPED` |
| `errorMessage` | string | Why a row is `ERROR`, in the student's terms |
| `transactionId` | number | The transaction an `IMPORTED` row produced |

`rawData` is published so the preview can show the file's own text beside the values the importer read —
which is how a student sees that the importer read `1.234,50` as `1.23`.

### `SetImportRowCategoryRequest` (65)

| Field | Type | Required | Notes |
|---|---|---|---|
| `categoryId` | number | yes | One of the caller's own or a shared default. It decides the record's type too (BR-05) |

## 4. The workflow — 62–67

### 62 — `POST /api/v1/imports`

Parses the file, decides what will happen to each row, stores the batch, and returns the preview.

**The file is sent as JSON text, not as a multipart upload.** Send the file's contents, decoded as
UTF-8, in `content`. A byte-order mark is handled, because Excel writes one even on a "CSV UTF-8"
export. There is no `MultipartFile`, no multipart configuration and no over-size handler anywhere in
this build; adding all three for one endpoint would leave the refusal a multipart route is most likely
to hit answered by Spring's default rather than by this API's error contract.

**The file needs a header row naming its columns**, including `date`, `amount` and `type`.
`description` and `category` are optional. A file missing a required column is refused with `400` and
**no batch is stored**.

**A row the importer cannot read does not stop the file.** It is stored as an `ERROR` row carrying a
sentence in the student's terms, and the rows around it are previewed normally. A file that will import
nothing is still a preview; the student sees which rows to fix.

**Some rows are previewed as `DUPLICATE`.** A row is a duplicate when one of the student's own records
already matches it on **category, amount and a date within a few days**. Those rows will not be imported
if the batch is committed. This is decided from the student's own data and **cannot be set from a
request**.

**Nothing is imported by this call.** The batch is stored as `PREVIEWED`.

```json
POST /api/v1/imports
{
  "filename": "september-expenses.csv",
  "content": "date,amount,type,description,category\n2026-09-24,12.50,EXPENSE,Lunch,Food"
}
```

### 63 — `GET /api/v1/imports?limit=20`

The caller's uploads, most recent first, each with its counters. `modifiable` says whether its rows can
still be changed, which is how a student finds an import they were part way through. An empty `entries`
array is a real answer.

### 64 — `GET /api/v1/imports/{batchId}`

One of the caller's batches with every row of its file, in file order.

**Each row says what will happen to it** (`rowStatus`), and both the parsed values and the original line
are returned. `resolvedCategoryId` is null until the student chooses — a category *name* in the file is
not resolved here; the commit does that, so resolving it now would be a second answer to a question the
commit owns.

A batch belonging to another student is answered exactly like one that does not exist (`404`).

### 65 — `PATCH /api/v1/imports/{batchId}/rows/{rowId}`

Records which category one row should be filed under. **This is what the commit treats as
authoritative** — once a row has a chosen category, the commit uses it and never re-derives the category
from the file's own name.

**The category also decides the record's type** (BR-05), which is how a wrong `type` in the file is
corrected: choose a category of the type the record should have.

**Changing the category re-runs the duplicate check.** Whether a row is a duplicate depends on the
category it would be filed under, so correcting the category re-decides that row and can bring it back
as importable, or flag another row that has just become a duplicate of it.

**There is no way to undo a choice.** The field is required and is never cleared; a student who wants
the file's own category back leaves the row alone.

Only an open batch can be changed. A batch that has been committed or cancelled answers `409`.

### 66 — `POST /api/v1/imports/{batchId}/commit`

Generates a transaction for every row the preview left importable.

**Which rows are imported is decided by the preview, not by this call.** A row stored as `VALID`
becomes a transaction; a row stored as `ERROR` or `DUPLICATE` does not.

**One row the database refuses does not end the import.** A row from the future (BR-08) or filed under
a category since retired (BR-07) becomes an `ERROR` row with a reason while the rest of the file imports
normally. So a commit can import some rows and report others as errors, and the counters describe
exactly that. All-or-nothing is **per row, not per file**.

**The counters are rewritten from what the import actually did.** Each of `importedRows`, `errorRows`
and `duplicateRows` is counted from the rows themselves, so the preview's counters and the commit's
agree by construction — one definition per counter, both paths using it. (This is the
`sp_apply_csv_batch` correction recorded in the module report: deriving the duplicate count by
subtraction counted rows the preview had already refused as "you already recorded this".)

**The import teaches the system, in the same step.** Each imported row maps its description to the
category it was filed under, in `category_rules`, so the next record from the same merchant is suggested
correctly (UC-08). If the import fails, the mappings fail with it.

Committing twice is refused with `409`. This is also how another student's batch is answered —
identically to one that does not exist.

### 67 — `POST /api/v1/imports/{batchId}/cancel`

Marks the batch `CANCELLED`. **The rows are kept**, so the record of what the file contained survives
the decision not to import it. Nothing reads a cancelled batch's rows as importable, so keeping them
cannot cause a later import.

## 5. Status codes

| Code | Error code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | An empty file, a file with no header, a missing required column, `limit` out of range, or a `categoryId` that is missing or names a retired category |
| `401` | `UNAUTHENTICATED` | No token, or an invalid, expired or revoked one |
| `404` | `NOT_FOUND` | No such import of the caller's, no such row in it, or no such category of the caller's |
| `409` | `DATA_CONFLICT` | The import has been committed or cancelled and can no longer be changed |

A `404` covers both "does not exist" and "belongs to someone else" — the two are made
indistinguishable on purpose (section 7.5).

## 6. Security properties

- **Ownership is enforced in the database.** Every read is ownership-narrowed; the commit calls
  `sp_apply_csv_batch`, which checks the batch belongs to the caller before writing anything. Another
  student's batch is a `404`, never a `403` — a `403` would confirm the batch exists.
- **A duplicate is never client-supplied.** The detector reads the student's own records; no field in
  any request names a flag or a status. The client cannot mark a row duplicate to skip it, nor unmark
  one to force it in.
- **The filename is never a path.** It is stored as text and echoed back, never opened or resolved.
- **A row's description is encrypted at rest.** An imported row's description reaches
  `transactions.description`, which is covered by Application-Level Field Encryption (AES-256-GCM) —
  see OB-012 for the one column that stays plaintext (`import_rows.parsed_description`, which is a
  preview artefact, not the record).
- **The file is bounded before it is parsed.** A larger file is refused, so a request cannot make the
  server store an unbounded number of rows.

## 7. Angular integration notes

- **Send the file's text, not a `FormData`.** Read the file with `FileReader.readAsText(file)` (which
  handles the UTF-8 decode) and post `{ filename: file.name, content: text }`. There is no multipart
  endpoint to send to.
- **The preview is server state.** After the upload, drive the screen from `GET /imports/{batchId}`,
  not from the text you sent — the stored preview is what the commit acts on.
- **Show `csvRowNo` beside every row.** It is the spreadsheet line number, and it is what makes an
  `errorMessage` actionable.
- **Re-read after a category change.** `PATCH` returns only the changed row, but the duplicate check
  may have re-decided *other* rows. Re-fetch the batch after a correction rather than patching one row
  into local state.
- **Disable the commit when `modifiable` is false**, and treat a `409` from commit or cancel as
  "reload the batch", which is what it means.
- **Show the counters as the commit reported them.** `validRows` before a commit is a prediction;
  after one it is a fact. The same field name with a different tense is deliberate.

## 8. Traceability

| UC-11 step | Endpoint | Database |
|---|---|---|
| A1 upload and preview | 62 | `import_batches`, `import_rows`; `CsvParser`, `ImportRowReader`, `ImportPreviewer`, `ImportDuplicateDetector` |
| A2 abandon | 67 | `import_batches.status = 'CANCELLED'` |
| B5 preview one batch | 64 | `import_rows` read by batch, ownership-narrowed |
| B6 choose a category | 65 | `import_rows.resolved_category_id` |
| B9 import | 66 | `sp_apply_csv_batch` — creates the transactions, rewrites the counters, and teaches `category_rules` |

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list.
- [ai-and-insights.md](ai-and-insights.md) — where `aiSuggestedCategoryId` comes from (UC-08).
- [../modules/MODULE_12_ADVANCED.md](../modules/MODULE_12_ADVANCED.md) — the module report.
