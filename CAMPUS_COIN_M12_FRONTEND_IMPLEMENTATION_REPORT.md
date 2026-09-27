# Campus Coin — M12 Frontend Implementation Report

**Scope:** build the Module 12 frontend on top of the existing Angular application, wired to the
running backend. This is an implementation report, not an audit: the 15 M12 operations are reachable
from the UI and were each exercised in a real browser against the real API.

**Stack in use (unchanged):** Angular 21 (zoneless, standalone, signals) · Spring Boot 3.4.3 · MySQL
8.0.46 · JWT. No dependency was added, no CSS framework was introduced, no existing page was
redesigned.

---

## 1. What was created

Six typed model files, six services, and five feature components. Nothing else was added to the
project.

### Models (`frontend/src/app/core/models/`)

| File | Contents |
|---|---|
| `import.model.ts` | `ImportBatchStatus`, `ImportRowStatus`, `ImportRawRow`, `ImportRow`, `ImportBatch`, `ImportSummary`, `ImportBatchListResponse`, `UploadCsvRequest` |
| `insight.model.ts` | `InsightGeneratedBy` (`AI` \| `RULE_BASED`), `FlaggedCategory`, `MonthlyInsight`, `InsightMonthsResponse` |
| `anomaly.model.ts` | `AnomalyFlagType` (`NONE` \| `DUPLICATE` \| `UNUSUAL_AMOUNT`), `FlaggedTransaction`, `FlaggedTransactionListResponse`, `AnomalyScanResult` |
| `forecast.model.ts` | `ForecastMonth`, `CurrentMonthTotals`, `ProjectedMonth`, `ForecastResponse` |
| `recent-activity.model.ts` | `RecentAction` (`VIEWED` \| `EDITED`), `RecentActivity`, `RecentActivityListResponse`, `RecordRecentActivityRequest` |
| `categorisation.model.ts` | `SuggestionSource` (`AI` \| `RULE` \| `NONE`), `LearnedCategoryRule`, `CategorySuggestion`, `SuggestCategoryRequest` |

Every field name matches the runtime response contract. No field was invented: there is no
`userId`, no `type` on a transaction DTO, no fabricated AI score, no fabricated forecast confidence
and no fabricated anomaly severity, because none of those exist in the responses the backend
returns.

### Services (`frontend/src/app/core/services/`)

| Service | Methods |
|---|---|
| `csv-import.service.ts` | `listImports`, `uploadCsv`, `getImport`, `setRowCategory`, `commitImport`, `cancelImport`; exports `CSV_TEMPLATE_COLUMNS` / `CSV_TEMPLATE_CONTENT` |
| `insight.service.ts` | `getInsight`, `getInsightMonths`, `generateInsight` |
| `anomaly.service.ts` | `listFlagged`, `scan` — and deliberately no method that sets a flag |
| `forecast.service.ts` | `getForecast` |
| `recent-activity.service.ts` | `list`, `record` |
| `categorisation.service.ts` | `suggestCategory` — one method, no write |

### Feature components

| Screen | Route | Component | Lines |
|---|---|---|---|
| Import CSV | `/app/imports` | `features/imports/csv-import.component.ts` | 697 |
| Monthly Insights | `/app/insights` | `features/insights/insights.component.ts` | 309 |
| Anomaly Check | `/app/anomalies` | `features/anomalies/anomalies.component.ts` | 249 |
| Forecast | `/app/forecast` | `features/forecast/forecast.component.ts` | 242 |
| Recent Activity | `/app/recent-activity` | `features/recent-activity/recent-activity.component.ts` | 241 |

---

## 2. What was extended

Three existing files, each a minimal, additive change.

| File | Change |
|---|---|
| `app.routes.ts` | Five lazy `loadComponent` routes appended inside the existing `app` children, after `profile`. They inherit the existing `authGuard` and `StudentLayoutComponent` — no new guard, no new layout, no redirect-to-home route. |
| `shared/navigation.config.ts` | Five items appended to `STUDENT_SIDEBAR_EXTRA_ITEMS`, so they land in the sidebar's existing "Explore" group. Because that array is the student-side list, an admin session never receives them. |
| `features/quick-add/quick-add.component.ts` | The AI category suggestion, folded into the existing row table (see §4, Journey B). |

The visual language is the existing one throughout: the same page header and breadcrumb, the same
orange action button, the same white rounded cards and shadows, the same spacing, icons, typography,
tables, dialogs, loading indicators and toast calls. No gradient, glassmorphism, neon or hero
section was introduced, and no existing page was restyled.

One deliberate correction was made during verification — see §7, Finding 1.

---

## 3. Journey A — CSV import

`UPLOAD → PREVIEW → FIX → CONFIRM → IMPORTED`, with `DISCARD` as the exit.

The screen reads the chosen file with `File.text()` and posts `{ filename, content }` to
`POST /imports`. There is no multipart endpoint, so no upload control other than the file picker was
invented. The preview shows four counters (rows in file, ready, already recorded, could not read),
the categories resolved per row, and per-row verdict badges. `PATCH /imports/{id}/rows/{rowId}`
fires when the student changes a row's category; `POST /imports/{id}/commit` creates the
transactions; `POST /imports/{id}/cancel` discards the batch.

**No transaction is ever written directly from the frontend**, and the import-batch workflow is not
bypassed: the only path to a `CSV` transaction in this UI is the commit call, which the backend
procedure owns.

### Verified, in a browser, against the live API

| Step | Evidence |
|---|---|
| Upload a 2-row file | `POST /api/v1/imports -> 201`, batch 5, status `PREVIEWED`, `validRows 2`, both rows `VALID` |
| Commit | `POST /api/v1/imports/5/commit -> 200`, `status COMMITTED`, `importedRows 2`; rows 14/15 became transactions **72** and **73** |
| UI renders the result | "2 of 2 rows became transactions", 3 `IMPORTED` badges, commit button gone |
| Re-upload the same file | `POST -> 201`, batch 6, `duplicateRows 1`, row 16 verdict `DUPLICATE` with the server's own note: "You already have a record of 77.11 in this category on 2026-09-24. This row was not imported." |
| Fix a row | `PATCH /api/v1/imports/6/rows/16 -> 200`, row re-read with `resolvedCategoryId 6` |
| Discard | `POST /api/v1/imports/6/cancel -> 200`, `status CANCELLED`, UI shows "No transactions were created" |
| Read a past batch | `GET /api/v1/imports/5 -> 200`, read-only view: "2 of 2 rows became transactions", no commit control |
| Malformed file | 3 rows offered, `COULD NOT READ 3`, `READY TO IMPORT 0`, per-row messages from the server (date/amount/type each named), commit button correctly disabled, toast "Read 3 rows — 3 need attention before importing." |

The commit path was independently confirmed in the database (read-only `SELECT`): `import_batches`
id 5 is `COMMITTED` with `imported_rows = 2`, and `transactions` gained exactly two `CSV` rows,
id 72 and 73, one per valid row.

The mobile strategy for the preview table is a compact stacked row list (`md:hidden` block) rather
than a horizontally scrolling table. At a 390 px viewport the table is not rendered, the stacked
rows are, and `document.scrollWidth == clientWidth` (390 = 390) — there is no horizontal overflow.

---

## 4. Journey B — AI category suggestion, inside the existing Quick Add

The suggestion lives in the Quick Add row table, not on a page of its own, because that is where a
student files a record and therefore where a suggestion is useful. Each row carries a sparkle button
that calls `POST /ai/suggest-category` for that row's transaction and renders the answer in an
inline expansion beneath the row.

**The AI never silently forces the category.** The suggestion is displayed as a proposal with its
provenance stated; the student either presses "Use this category" — which routes through the
existing `updateTransaction` call, so the record is written by the same path as any manual edit — or
dismisses it and the row is untouched.

Verified: `POST /api/v1/ai/suggest-category -> 200` returning
`{"source":"RULE","categoryId":6,"categoryName":"Food",...,"learned":{"keyword":"m12 verify alpha","categoryId":6,"source":"ACCEPTED"}}`.
The UI rendered "SUGGESTED CATEGORY / Food **from a rule you taught**" and offered both "Use this
category" and "Keep mine". Accepting it applied the category and closed the block.

The wording is driven by the `source` field the server returns, and the `NONE` branch says plainly
that no AI provider is configured rather than implying a suggestion was made.

---

## 5. Journeys C, D, E, F

**C — Monthly Insight.** `GET /insights/months` populates the month select, `GET /insights` loads
the month, `POST /insights/generate` rebuilds it. The figures, the prose and the flagged categories
all come from the response; the screen computes no metric of its own. The source badge is driven by
`generatedBy`, and the footnote states the truth for each case — this deployment returned
`RULE_BASED`, so the screen says a fixed rule composed it and that no AI model was involved. It
does **not** claim the text was AI-written. The copy is also labelled as not financial advice. 404
on a month with no insight yet is treated as the empty state, not as an error.

**D — Anomaly Detection.** The screen is advisory. "Check My Records" calls `POST /anomalies/scan`
and renders the returned counters (records read / newly marked / no longer marked / unchanged) plus
each flagged entry with the server's `flagNote` verbatim. There is no control anywhere that sets a
flag, and the service exposes no write method — the client cannot create or fake a flag. The only
action offered on a flagged entry is a link to Quick Add, so the student corrects the record and a
later scan re-examines it. This is the remedy the backend's own detector documents.

Verified: `POST /api/v1/anomalies/scan -> 200`, `{"examined":35,"flagged":1,"cleared":0,"unchanged":34}`.
The UI rendered 35 read / 1 newly marked / 0 no longer marked, and listed the entry with its note.

**E — Forecast.** One read, `GET /forecast`; no parameter is accepted and none was invented. The
screen shows the projection, the current month so far, and — importantly — a "What it is based on"
table rendering `recentMonths`, so every projected figure is traceable to the months the backend
averaged. When `projected` is absent the screen says "No projection yet" rather than showing a
number. No forecast value is computed client-side.

Verified: `GET /api/v1/forecast -> 200`, `nextMonth 2026-10`, `basedOnMonths 3`, the three recent
months listed, `projected {income 306.67, expense 205.67, savings 101.00}`.

**F — Recent Activity.** The list is `GET /recent-activity`, and the log form calls
`POST /recent-activity` with a transaction id and an action. Nothing is fabricated locally: the
entries and their timestamps are the server's.

Verified: `POST /api/v1/recent-activity -> 201` for transaction 29, and the list re-read grew from 2
entries to 3 with the new row first, timestamped by the server (`27 Sep, 00:40`). An unknown id
returns `404 {"errorCode":"NOT_FOUND","message":"Transaction not found."}` and the screen shows
"Transaction not found." A full page reload still shows the entry — it is persisted, not
client state.

One honest limitation is visible rather than papered over: the response publishes `categoryId` and
`categoryType` but not the category's name, so the entry labels the row "Expense"/"Income" from the
type it was given instead of inventing a name.

---

## 6. Section 16 — required tables

### 6.1 M12 API → UI traceability (15 operations)

| M12 API | Feature | UI Screen | UI Action | Connected | Browser Verified |
|---|---|---|---|---|---|
| `GET /api/v1/imports` | CSV import | `/app/imports` | Page load, and after every upload/commit/cancel | Yes | Yes — 200, 9 batches listed |
| `POST /api/v1/imports` | CSV import | `/app/imports` | Choose a file | Yes | Yes — 201, preview with `validRows 2` |
| `GET /api/v1/imports/{batchId}` | CSV import | `/app/imports` | "View" on a past import | Yes | Yes — 200, read-only committed batch |
| `PATCH /api/v1/imports/{batchId}/rows/{rowId}` | CSV import | `/app/imports` | Change a row's category in the preview | Yes | Yes — 200, row re-read |
| `POST /api/v1/imports/{batchId}/commit` | CSV import | `/app/imports` | "Confirm and Import N Rows" | Yes | Yes — 200, `importedRows 2`, txns 72/73 |
| `POST /api/v1/imports/{batchId}/cancel` | CSV import | `/app/imports` | "Discard This Import" → confirm | Yes | Yes — 200, `CANCELLED` |
| `POST /api/v1/ai/suggest-category` | AI suggestion | `/app/quick-add` | Sparkle button on a row | Yes | Yes — 200, `source RULE`, category 6 |
| `GET /api/v1/insights` | Monthly insight | `/app/insights` | Page load, month change | Yes | Yes — 200, real figures |
| `GET /api/v1/insights/months` | Monthly insight | `/app/insights` | Page load (fills the month select) | Yes | Yes — 200, 3 months |
| `POST /api/v1/insights/generate` | Monthly insight | `/app/insights` | "Generate" | Yes | Yes — 200, `generatedBy RULE_BASED` |
| `GET /api/v1/anomalies` | Anomalies | `/app/anomalies` | Page load, and after a scan | Yes | Yes — 200 |
| `POST /api/v1/anomalies/scan` | Anomalies | `/app/anomalies` | "Check My Records" | Yes | Yes — 200, `examined 35, flagged 1` |
| `GET /api/v1/forecast` | Forecast | `/app/forecast` | Page load | Yes | Yes — 200, 3 months, projection |
| `GET /api/v1/recent-activity` | Recent activity | `/app/recent-activity` | Page load, and after recording | Yes | Yes — 200 |
| `POST /api/v1/recent-activity` | Recent activity | `/app/recent-activity` | "Log an action" → Record | Yes | Yes — 201; 404 on an unknown id |

All 15 are connected and all 15 were verified in a browser. No M12 endpoint is left unexplained.

### 6.2 User journeys

| User Journey | Start Screen | Main Actions | APIs Used | Result |
|---|---|---|---|---|
| A — CSV import | `/app/imports` | Choose file → read preview → fix a row's category → confirm → (or discard) | `GET /imports`, `POST /imports`, `GET /imports/{id}`, `PATCH .../rows/{rowId}`, `POST .../commit`, `POST .../cancel` | Pass — preview, per-row verdicts, fix, commit creating one transaction per valid row, discard, and duplicate/file-error refusal all confirmed |
| B — AI category suggestion | `/app/quick-add` | Press sparkle on a row → read the proposal and its source → accept or dismiss | `POST /ai/suggest-category`, `PUT /transactions/{id}` | Pass — proposal rendered inside Quick Add with provenance; accepting applies it, dismissing leaves the record untouched; nothing forced |
| C — Monthly insight | `/app/insights` | Pick a month → Generate | `GET /insights/months`, `GET /insights`, `POST /insights/generate` | Pass — figures and prose from the server; source badge honest (`RULE_BASED`, "no AI model was involved"); not-advice labelling present |
| D — Anomaly detection | `/app/anomalies` | "Check My Records" | `GET /anomalies`, `POST /anomalies/scan` | Pass — 35 read / 1 flagged / 0 cleared; entry shown with the server's own note; no write control exists in the client |
| E — Forecast | `/app/forecast` | Visit the screen | `GET /forecast` | Pass — projection plus the evidence table of the three months it averages; no client-side arithmetic |
| F — Recent activity | `/app/recent-activity` | Enter a transaction id and an action → Record | `GET /recent-activity`, `POST /recent-activity` | Pass — 201, list grew 2 → 3 with a server timestamp, survives reload; 404 path shows the server's message |

---

## 7. Verification results

### Build, type-check and tests

| Check | Command | Result |
|---|---|---|
| Production build | `npx ng build` | `EXIT=0` — "Application bundle generation complete." Only the 2 pre-existing `angular-sass` `@import` deprecation warnings (they come from `styles/styles.scss` and predate this work). |
| Type-check | `npx tsc --noEmit -p tsconfig.app.json` | `EXIT=0`, no output |
| Unit tests | `npx ng test --watch=false` | `EXIT=0` — **13 test files, 62 tests passed** |

There is no lint step in this project to run: `frontend/` carries a `.prettierrc` but no ESLint
configuration, so `tsc --noEmit` was used as the static gate. Nothing was added to create one.

### Browser verification

Every M12 screen was driven in a real Chromium against the running app on
`http://localhost:4200`, with the response interceptor recording method, URL, status and body for
each M12 call. All five screens render live backend data with **zero console errors** in the normal
path.

- **Loading state** — with the forecast request held open, the screen shows skeleton placeholders
  (2 `.animate-pulse` blocks) and no stale numbers; once released, the projection renders.
- **Error state** — a forced 500 on `/anomalies`, `/forecast` and `/insights` each produced the
  app's existing toast pattern ("ACTION FAILED — Could not load your flagged entries." / "Something
  went wrong on the server." / "Could not load your insight.").
- **Persistence** — a hard reload of `/app/recent-activity` still shows the recorded entry.
- **Authorization** — an admin session's sidebar contains none of the five M12 items (it has 3 nav
  links, none M12). All five screens use the authenticated student implicitly; no screen accepts or
  displays a user id, and no route carries one.
- **Mobile** — at 390 px the import preview uses the stacked layout with no horizontal overflow, and
  the insights screen still renders its figures.

### Finding 1 — a failed history load rendered the empty state (fixed)

Controlled test: `GET /imports` was forced to fail. The "Past Imports" card then showed **"You have
not imported a file yet"** — an empty state asserting a fact the client could not know, and
indistinguishable from a genuinely empty history. That is a §10 defect: loading, empty and error
must be distinct.

Fixed by adding a `historyFailed` flag, a distinct message ("Your past imports could not be loaded
just now.") and a "Try again" control. Re-verified: with the request failing, the false empty claim
is **gone**, the error message and the retry are both present; releasing the request and pressing
"Try again" recovers to the real list (8 rows) with the error cleared. Build, type-check and the
62 unit tests still pass afterwards.

### Finding 2 — an imported row's description is sometimes encrypted, sometimes not (recorded, not fixed)

This is a backend observation, and it **contradicts a documented expectation**, so it is recorded
rather than fixed.

The code says one thing. `imports/entity/ImportRow.java` states that
`sp_apply_csv_batch` inserts the description "straight into `transactions.description`, which *is*
an encrypted column, **without encrypting it** — a procedure cannot, because that would need the key
inside MySQL. So a transaction created by an import carries a plaintext description." OB-012 point 1
says the same.

The runtime says something else. Four transactions were created by import during verification, and
their storage is not uniform:

| Batch | Row | Transaction | `parsed_description` (preview) | stored `description` | length |
|---|---|---|---|---|---|
| 3 | 8 | 70 | `M12 verify snack` | **ciphertext** `AQFuoHiZ…` | 64 |
| 3 | 9 | 71 | `M12 verify tea` | plaintext | 14 |
| 5 | 14 | 72 | `M12 verify alpha` | **ciphertext** `AQGYaFQu…` | 64 |
| 5 | 15 | 73 | `M12 verify beta` | plaintext | 15 |

The pattern is consistent — the first imported row of each batch is encrypted and the second is not
— and the mechanism is visible in the audit trail. `transaction_history` shows a second `UPDATE`
with `changed_fields = "description"` for transactions **70** and **72** only:

```
70  CREATE  created
70  UPDATE  aiSuggestedCategoryId,aiConfidence
70  UPDATE  description            <-- 71 and 73 have no such row
72  CREATE  created
72  UPDATE  aiSuggestedCategoryId,aiConfidence
72  UPDATE  description
72  UPDATE  isFlagged,flagType,flagNote
```

`TransactionService` encrypts the description on update (`encryptionService.encrypt(...)`, lines 214
and 271) and the import procedure inserts it plaintext, so any path that subsequently updates one of
these rows re-writes the description as ciphertext. Half the imported rows were reached by such a
path and half were not.

Two consequences, stated plainly:

1. **The documentation is inaccurate.** `ImportRow.java`'s javadoc and OB-012 point 1 both claim the
   import path always yields a plaintext description. That is not what the running system does.
2. **The residual at-rest exposure is real but not uniform**, and it is the encrypted half that
   diverges from the documented position.

No code was changed for this. It is a backend contract question about which write path owns
`transactions.description`, and choosing an answer changes behaviour the M12 frontend does not own.
It is recorded as **OB-018** in `docs/OVERNIGHT_BLOCKERS.md` with the evidence above.

### Finding 3 — ~~the M12 `db/` signature change is not deployed~~ — **RETRACTED**

> **RETRACTED on 2026-09-27 after measurement.** The claim below is contradicted by the live database.
> It is kept visible rather than deleted so that anyone who read it earlier sees the correction.

**The original claim.** All four `sp_*` routines and `sp_flag_transaction` in the live database still
carry their original signatures with no `p_actor_id`; for example the deployed
`sp_soft_delete_transaction(IN p_txn_id BIGINT UNSIGNED)` takes a single parameter while
`db/03_procedures.sql` declares the actor as well.

**What the live database actually contains.** Every routine was read from
`information_schema.PARAMETERS` and compared with the repository files, parameter by parameter (name,
ordinal position and type):

| Routine | Live signature | Verdict |
|---|---|---|
| `sp_soft_delete_transaction` | `p_txn_id`, `p_user_id` | **Two parameters** — identical to `db/03_procedures.sql`. The specific example cited above is false: the deployed routine does **not** take a single parameter. |
| `sp_admin_create_announcement` | `p_actor_id`, `p_title`, `p_body`, `p_severity`, `p_audience`, `p_starts_at`, `p_ends_at`, `p_ip` | `p_actor_id` **is present**, as the repo declares |
| `sp_admin_set_announcement_active` | `p_actor_id`, `p_announce_id`, `p_is_active`, `p_ip` | `p_actor_id` **is present** |
| `sp_admin_upsert_default_category` | `p_actor_id` + 9 further parameters | `p_actor_id` **is present** |
| `sp_admin_upsert_tip_template` | `p_actor_id` + 8 further parameters | `p_actor_id` **is present** |
| `sp_admin_set_threshold` | `p_actor_id`, `p_key`, `p_value`, `p_ip` | `p_actor_id` **is present** |
| `sp_admin_send_password_reset` | `p_target_user_id`, `p_actor_id`, `p_token_hash`, `p_ip` | `p_actor_id` **is present** |
| `sp_flag_transaction` | `p_txn_id`, `p_user_id`, `p_flag_type` | Matches the repo exactly |

Every routine and trigger was also compared by body text. **No difference was found** other than
`fn_render_template`'s header, which is MySQL's own canonical re-rendering of the same declaration
(`CREATE FUNCTION ... RETURNS text CHARSET utf8mb4` versus the file's equivalent spelling) — same
name, same parameters, same return type, same body.

**Therefore: there is no procedure signature drift and no migration to apply.** `db/03_procedures.sql`
and `db/merged/campuscoin_full.sql` describe the deployed schema accurately, and the live database is
already running the M12 signatures. Nothing needs to be reconciled, and no operator action is
outstanding. This finding is recorded as **RETRACTED** in
`docs/testing/qa/DECISION_LOG.md` (D-09), which had earlier retracted a related drift claim for the
same reason.

**How the original claim arose.** The observation predates the corrected check; reading a procedure's
signature indirectly (for example from a `SHOW CREATE` captured before the M12 database was rebuilt)
yields the pre-M12 shape. The direct `information_schema` read is authoritative and is what the
retraction rests on.

---

## 8. Screens, services and files touched

**Created (17 files):** 6 models, 6 services, 5 feature components.

**Extended (3 files):** `app.routes.ts` (5 lazy routes), `shared/navigation.config.ts` (5 sidebar
items), `features/quick-add/quick-add.component.ts` (the suggestion block).

**Corrected (1 file):** `features/imports/csv-import.component.ts` — the `historyFailed` state from
Finding 1.

**Recorded (1 file):** `docs/OVERNIGHT_BLOCKERS.md` — OB-018, plus its Summary-table row.

No existing non-M12 feature was modified beyond the Quick Add integration that §14 Phase 4 asks for.
No unrelated file was renamed, no unrelated code was reformatted, and no page was restyled.

---

## 9. Demo-database side effects from this verification

Disclosed for completeness. Every write below was made **through the API**, as the application
intends. No direct SQL mutation was performed — the only database access was read-only `SELECT` —
and `docker compose down -v` was never run.

- **Import batches**, user 2 (Alex), 9 rows. Three were left mid-preview by probe runs and were then
  cleared through the app's own Discard action; `demo-history.csv`, `probe.csv`, `m12-verify.csv`,
  `m12-dupe-7711.csv`, `m12-bad.csv` and `m12-mobile.csv` are all now `CANCELLED`. Two are
  `COMMITTED`: `m12-verify.csv` (id 3, `importedRows 2`) and `m12-good-7711.csv` (id 5,
  `importedRows 2`).
- **Transactions**, user 2: four `CSV` rows created, ids **72, 73** (batch 5) and **70, 71** (batch
  3). The user-2 live-transaction count moved from 31 to **35**.
- **Anomaly flag**: transaction **72** is `is_flagged = 1`, `flag_type = UNUSUAL_AMOUNT`, because the
  probe imported a 77.11 Food entry against a 15.33 category average. It is the only flagged record
  for user 2, and it exists solely because of this verification.
- **Recent activity**, user 2: three rows — transaction 29 `VIEWED`, 30 `EDITED`, 31 `VIEWED`.
- **Insight**: `POST /insights/generate` rewrote the 2026-09 `insights` row for user 2, so its
  `total_expense` now reflects the probe transactions (285.33 rather than 197.00).

Anyone reviewing the seeded data should treat these as the footprint of the verification below, not
as seed content.

---

## 10. Status

All 15 M12 operations are implemented, connected, and browser-verified. Build, type-check and the
62-test suite pass. Three findings are recorded: one frontend defect found and fixed, and two backend
or operational observations written up in OB-018 and §7 rather than silently patched.

**M12 FRONTEND IMPLEMENTATION COMPLETE — ALL 15 OPERATIONS CONNECTED AND BROWSER-VERIFIED, WITH 2 RECORDED BACKEND/OPS FINDINGS (OB-018) AND 0 UNEXPLAINED ENDPOINTS.**
