# Module 12 — Optional / Advanced (UC-08, UC-11, UC-17, UC-24, UC-25, UC-26)

| | |
|---|---|
| **Endpoints** | `POST /api/v1/imports` (62), `GET /api/v1/imports` (63), `GET /api/v1/imports/{batchId}` (64), `PATCH /api/v1/imports/{batchId}/rows/{rowId}` (65), `POST /api/v1/imports/{batchId}/commit` (66), `POST /api/v1/imports/{batchId}/cancel` (67), `POST /api/v1/ai/suggest-category` (68), `GET /api/v1/insights` (69), `GET /api/v1/insights/months` (70), `POST /api/v1/insights/generate` (71), `GET /api/v1/anomalies` (72), `POST /api/v1/anomalies/scan` (73), `GET /api/v1/forecast` (74), `GET /api/v1/recent-activity` (75), `POST /api/v1/recent-activity` (76) |
| **Requirements** | UC-08 (B1–B6), UC-11 (A1–A2, B1–B9), UC-17, UC-24, UC-25, UC-26; BR-02, BR-05, BR-12, BR-13, BR-15; SRS §7.2, §7.5, §26; the six module-12 use cases |
| **Schema objects read** | `transactions`, `categories`, `category_rules`, `import_batches`, `import_rows`, `insights`, `recent_activity`, `system_settings`, `dim_month`; `v_user_recent_activity` |
| **Schema objects written** | `import_batches`, `import_rows` and `insights` (direct statements); `transactions` flags, `recent_activity` and `insights` (through `sp_flag_transaction`, `sp_touch_recent_activity`, `sp_generate_monthly_insight`); `category_rules` (upsert); `transactions` (through `sp_apply_csv_batch`, at the commit) |
| **Tests** | 361 new M12 + `OpenApiContractIT` 14 → 19; suite 736 → **1090**, all green |
| **Status** | Implemented and tested. **LOCKED pending the project owner's approval** — the code, the contract and the database changes are complete; authorisation to ship is not this module's to give |

---

## 1. Scope

Module 12 is the six use cases the project brief placed in the optional/advanced module: learning a
category from the student's own filing (UC-08), importing a CSV (UC-11), writing a monthly narrative
(UC-17), marking records that look wrong (UC-24), projecting next month (UC-25), and remembering what
the student recently opened (UC-26).

**The module is built and locked at the same time, and that is the tension every decision below has to
live with.** The prompt asked for the features to be implemented and for the module to remain pending
the project owner's approval; the brief's own wording was "implementation complete, locked pending
approval". So each feature is real — tested against MySQL 8, served over HTTP, documented — and the
frontend is told, repeatedly and in the contract itself, not to wire to it yet. Nothing was stubbed to
make the lock easier: a stubbed feature would be a lie in the release report, and the final status
wording the project requires is about M1–M11, not about pretence.

**Three facts shape the whole module.**

1. **The database was already complete for it.** All five M12 procedures existed from the start —
   `sp_generate_monthly_insight`, `sp_apply_csv_batch`, `sp_touch_recent_activity`,
   `sp_upsert_category_rule`'s table and its two settings keys. So did the tables
   (`import_batches`, `import_rows`, `insights`, `recent_activity`, `category_rules`) and the view
   `v_user_recent_activity`. The module therefore **adds no table, no view and no column**; it adds
   exactly two `db/` changes, both of which are corrections or an omission being filled rather than a
   redesign (§3.3).
2. **An external AI provider is optional, and its absence is a supported deployment.** The provider is
   reached through a port (`AiSuggestionPort`) that has no repository, and with no credential a
   `NoopAiSuggestionPort` is installed. UC-08 answers from the student's own learned rules; UC-17 keeps
   the `RULE_BASED` narrative a procedure already wrote and records `generated_by = 'RULE_BASED'`
   rather than `AI`. **Nothing is faked and nothing is hidden** — the response names which author wrote
   the prose.
3. **Two of the six features are advisory and the API says so at every level.** A category suggestion
   never files a record (BR-13); an insight is a suggestion rather than financial advice. That is
   visible in the response types (`source`, `generatedBy`), not only in prose.

### What is deliberately not built

| Excluded | Why |
|---|---|
| A multipart upload for 62 | The file is sent as JSON text in `content`. There is no `MultipartFile`, no multipart configuration and no over-size handler anywhere in this build — adding all three for one endpoint would leave the refusal a multipart route is most likely to hit (an over-large body) answered by Spring's default rather than by this API's error contract |
| `PATCH`/`PUT` on `/imports/{batchId}` | A committed batch has no writable field left; the student changes a *row* (65) or commits the *batch* (66), never the batch itself |
| `PUT` on any resource | Every writable thing is a `PATCH` or an action sub-resource |
| `DELETE /imports/{batchId}` | 67 is the abandon path, and it is a status transition with a defined set of states rather than a row disappearing |
| `{batchId}/rows/{rowId}` as a `POST` sub-resource | It writes exactly one field of the row it names. The contrast is `/tips/{id}/state`, where the state change writes two columns together and *which* pair is written depends on the state |
| `POST /anomalies/{id}/flag` or any client-supplied flag | UC-24's "do not allow the client to arbitrarily set anomaly flags". The mark is computed by the server; nothing in any request body can name one |
| `GET /forecast?month=` | UC-25 projects the month *after* the one in progress, which is what the schema's stored projection is keyed on — a documented judgement rather than a client choice. The evidence months are published as `recentMonths` inside the response rather than as a second route |
| `GET /transactions?sort=recent` in place of 75 | 75 is the student's own log of having *opened or changed* a record — a different table, a different fact, a different owner |
| `POST /ai/suggest-category` writing the category | BR-13. The proposal is stored *beside* the record as advice |
| An administrator route over insights, flags or suggestions | No UC asks an administrator to read another student's insight, and OB-015 records why the bookmark branch that would consume one is still refused |
| Making `anomaly.*` administrator-tunable | Widening `sp_admin_set_threshold`'s six-key allow-list is a `db/` change outside this module's scope and would invalidate module 11's "six adjustable" documentation. Recorded as OB-017 |

---

## 2. Endpoints

Fifteen operations on eleven paths; inventory numbers 62–76. All are `STUDENT`-only and all scope by
the caller's own identity — no operation accepts a user id, and another student's batch, insight or
transaction answers exactly as one that does not exist does.

| # | Method | Path | UC | Success |
|---|---|---|---|---|
| 62 | `POST` | `/api/v1/imports` | UC-11 A1 | `201` the stored preview |
| 63 | `GET` | `/api/v1/imports` | UC-11 | `200` the import history |
| 64 | `GET` | `/api/v1/imports/{batchId}` | UC-11 B5 | `200` the batch and its rows |
| 65 | `PATCH` | `/api/v1/imports/{batchId}/rows/{rowId}` | UC-11 B6 | `200` the row in its new state |
| 66 | `POST` | `/api/v1/imports/{batchId}/commit` | UC-11 B9 | `200` the batch after importing |
| 67 | `POST` | `/api/v1/imports/{batchId}/cancel` | UC-11 A2 | `200` the cancelled batch |
| 68 | `POST` | `/api/v1/ai/suggest-category` | UC-08 | `200` a proposal, or `NONE` |
| 69 | `GET` | `/api/v1/insights` | UC-17 | `200` the month's insight |
| 70 | `GET` | `/api/v1/insights/months` | UC-17 | `200` the months that have one |
| 71 | `POST` | `/api/v1/insights/generate` | UC-17 | `200` the insight after generating |
| 72 | `GET` | `/api/v1/anomalies` | UC-24 | `200` the flagged records |
| 73 | `POST` | `/api/v1/anomalies/scan` | UC-24 | `200` the scan's tally |
| 74 | `GET` | `/api/v1/forecast` | UC-25 | `200` the projection |
| 75 | `GET` | `/api/v1/recent-activity` | UC-26 | `200` the caller's activity |
| 76 | `POST` | `/api/v1/recent-activity` | UC-26 | `201` the recorded entry |

`SecurityConfig` gained **six rules, one per new path prefix** — `/api/v1/imports/**`,
`/api/v1/ai/**`, `/api/v1/insights/**`, `/api/v1/anomalies/**`, `/api/v1/forecast/**` and
`/api/v1/recent-activity/**` — each `hasRole("STUDENT")`, each with a comment arguing why an
administrator has no use case for it.

**Naming them is the correct choice here, and the reason is the catch-all below them.**
`SecurityConfig`'s last rule is `.requestMatchers("/api/**").authenticated()`, and there is no
`/api/v1/**` rule. A student-prefix rule that is *missing* therefore falls through to a rule that
admits **any authenticated account** — so an unlisted M12 route would be reachable by an
administrator's token, silently. (Module 11's admin paths are the mirror case: `/api/v1/admin/**`
already had `hasRole("ADMIN")`, so module 11 genuinely needed no new rule. Its plan said so, and that
claim was verified then; it does not transfer to module 12, whose prefixes were new.)

The cost of the explicit list is that a future M12 route added under one of these prefixes is covered
automatically, while one added under a *new* prefix must be added here. `AdminSecurityIT`'s sibling
sweep for this module — parameterised over all fifteen operations, asserting a student token is
admitted and an administrator's is `403 ACCESS_DENIED` — is what makes the omission fail loudly rather
than at runtime.

### 2.1 The non-obvious choices, argued

- **62 takes the file as text, not as a multipart upload (§4.1).**
- **65 is a `PATCH` on the row, and it re-runs the duplicate check.** Whether a row is a duplicate
  depends on the category it would be filed under, so correcting the category re-decides that row and
  can bring it back as importable or flag a row that has just become a duplicate of it. The commit
  treats the chosen category as **authoritative** and never re-derives it from the file's own name.
- **The category also decides the record's type** (BR-05), which is how a wrong `type` in the file is
  corrected: choose a category of the type the record should have. There is no separate `type` field to
  get wrong.
- **73 is explicit and 72 is not.** Reading flagged records never writes; asking for a scan is a
  separate, deliberate call that examines the student's own history and rewrites the marks. A `GET`
  that mutated would be a `GET` a browser prefetch could trigger.
- **75 and 72 have different limits** (10/50 and 20/100). A flagged list is a review queue; a recent
  list is a glance. Each response echoes the limit it applied, which is why an over-large value is
  **refused rather than clamped** — a reduced answer would make the echoed field untrue.

---

## 3. Database alignment

### 3.1 What the database owns, so Java must not restate it

| Concern | Owner |
|---|---|
| Ownership of every read and write | the query's `user_id` predicate, and for the two write procedures a `SELECT ... INTO` ownership check in the procedure body |
| A transaction's anomaly flag and its history row | `sp_flag_transaction`, whose `UPDATE` fires `trg_transactions_after_update` |
| The recent-activity row and its `occurred_at` | `sp_touch_recent_activity` |
| The month's `RULE_BASED` figures | `sp_generate_monthly_insight` |
| Turning importable rows into transactions, and the batch's five counters | `sp_apply_csv_batch` |
| The two `anomaly.*` thresholds and every other tunable | `system_settings`, read through `SettingReader` at the point of use (VĐ-05) |
| The import row's own shape (`enum` values, `ck_` constraints) | the schema; the Java enums mirror the columns exactly |

### 3.2 The two write paths that go through a procedure, and why

Three writes in this module are `CALL`s rather than statements, and the reason is the same each time:
**the ownership check belongs next to `fk_txn_user`, not restated in Java.** `fk_txn_user` proves a
transaction row *exists*, not whose it is — so without a check in the procedure one student could flag
another student's record or attach a recent-activity entry to it.

- `sp_flag_transaction` — UC-24's only write path. `p_flag_type = 'NONE'` is the single clearing form:
  it sets `is_flagged = 0` and the note to `NULL`, so "not flagged" has exactly one representation and
  a stale note cannot survive an unflag.
- `sp_touch_recent_activity` — UC-26's write path, and the reason `POST /recent-activity` records an
  entry rather than changing a transaction.
- `sp_generate_monthly_insight` — UC-17's figures. The AI narrative is layered on top by
  `InsightWriteDao` and never replaces them, so the numbers are always the database's own.

**The import's own tables are written directly**, and that is a decision rather than an oversight:
`recent_activity`/`transactions` hold rows a *student's* identity protects, while `import_batches` and
`import_rows` belong to a batch the caller owns and which no other student can name. The one write that
does move money — the commit — goes through `sp_apply_csv_batch`, so the transaction rows and the
counters are written by one statement in one transaction.

### 3.3 The two `db/` changes this module carries (OB-016)

- **`sp_flag_transaction` (new, 71 lines).** UC-24's write path. The three flag columns
  (`is_flagged`, `flag_type`, `flag_note`), the index `ix_txn_flagged` and the two `anomaly.*` settings
  rows existed in the schema from the start, but **no procedure read or wrote them** — UC-24 was
  module 12 and had never been built. Nothing was added to the schema; a reserved capability was given
  its writer.
- **`sp_apply_csv_batch` counter correction.** The commit derived the duplicate count as
  `v_total - v_imported - v_errors`. That is wrong: the cursor only ever visits rows the preview had
  already left `VALID`, so a row refused in the preview (an unreadable date, a typo in the amount) was
  never seen by the walk at all — and the subtraction reported **every such row as "you already
  recorded this"**, which is a false statement about the student's own history. The three counters are
  now each read from the rows, which also makes the preview's counter and the commit's agree by
  construction: one definition per counter, used by both paths.

Both changes are mirrored into `db/merged/campuscoin_full.sql`, which is the file
`AbstractMySqlIntegrationTest` mounts — so a `db/*.sql` change that is **not** mirrored is a change the
tests do not see, and that rule is stated in OB-016 rather than left implicit.

### 3.4 The plaintext exposure this module widens (OB-012, OB-013)

Two findings were recorded rather than fixed, because fixing either is outside a locked module's scope:

- **`import_rows.parsed_description` and `import_rows.raw_data` are plaintext copies of text that
  `transactions.description` holds as AES-256-GCM ciphertext.** An import therefore produces an
  encrypted transaction *and* a plaintext preview copy of the same text, plus the whole CSV line in
  `raw_data`. The column-type mismatch that makes this concrete — `transactions.description` is
  `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin` while `parsed_description` is a table-default
  `utf8mb4` `VARCHAR(255)` — is recorded in OB-012, which module 12 re-scoped rather than closed.
- **The AI surfaces send only aggregates and the minimum.** UC-08 sends a description and the
  student's own category names — no amount, no date, no identifier. UC-17 sends the month's totals and
  category names, never a transaction. `ai.send_aggregates_only` is honoured, and when it is off the
  narrative is **skipped** rather than sent as per-row data: this build has no per-row disclosure path,
  and refusing to call is the strict reading.

---

## 4. Implementation

### 4.1 The AI boundary, enforced by the type

```
Angular ─► Spring Boot ─► this backend reads and filters the student's own rows
                        ─► a prepared context object ─► the AI provider
          ◄─ this backend validates the answer ◄─
```

- **`AiSuggestionPort` has no repository.** An implementation can send only what a service deliberately
  handed it, so "the provider never sees the database" is a property of the type rather than a promise
  in a comment. `GeminiAiSuggestionPort` is the only implementation that talks to a network; it
  receives `CategorySuggestionRequest` / `MonthlyNarrativeRequest` and nothing else.
- **The answer is validated before it means anything.** Whatever the provider returns is matched back
  against the student's own categories; a name the student does not have is discarded rather than
  trusted, and **a category id is never taken from the provider's response**.
- **The key never leaves the server.** `GEMINI_API_KEY` is read from the environment. It is never
  written to `application.yml`, never stored in MySQL, never put in a JWT, never sent to Angular and
  never logged. The model and endpoint (`AI_MODEL`, `AI_BASE_URL`) are deployment configuration
  rather than administrator-tunable settings, because changing them changes where student data is sent.
- **A timeout is "no suggestion".** One call is bounded by `AI_TIMEOUT_SECONDS`; past it the request
  still succeeds with `source: NONE`.

### 4.2 Package layout

Feature-first, matching every other module — `controller/`, `service/`, `repository/`, `entity/`,
`dto/`, `mapper/` — in six new packages, plus one shared one:

| Package | Contents |
|---|---|
| `com.campuscoin.common.ai` | `AiSuggestionPort`, `AiProperties` + `AiConfig`, `GeminiAiSuggestionPort`, `NoopAiSuggestionPort`, the two request types and two reply types. **The port is the whole security boundary** |
| `com.campuscoin.categorisation` | UC-08: `CategoryRuleMatcher` (the learned-mapping lookup), `CategorySuggester` (rule first, then the provider), `CategorisationWriteFailure`, `TransactionCategorisationDao`, `CategoryRuleDao` |
| `com.campuscoin.imports` | UC-11: `CsvParser`, `ImportRowReader`, `ImportPreviewer`, `ImportDuplicateDetector`, `ImportCategoryResolver`, `ImportRuleLearner`, `ImportService`, `ImportWriteFailure` |
| `com.campuscoin.insight` | UC-17: `InsightService`, `InsightViewDao`, `InsightWriteDao` |
| `com.campuscoin.anomaly` | UC-24: `AnomalyDetector` (the only thing that decides a flag), `AnomalyService`, `AnomalyViewDao`, `AnomalyFlagProcedureDao` |
| `com.campuscoin.forecast` | UC-25: `Forecaster` (the average of the last three complete months), `ForecastService`, `ForecastViewDao` |
| `com.campuscoin.recent` | UC-26: `RecentActivityService`, `RecentActivityViewDao`, `RecentActivityProcedureDao` |
| `com.campuscoin.common.jdbc` | `JdbcValues` — the one helper the module added to `common`, for reading nullable `BigDecimal`/date columns from a native `Tuple` without restating the null dance in six DAOs |

The four `*WriteFailure` classifiers follow module 10's and module 11's precedent exactly: recognised by
**SQLSTATE** (`45000`, `23000`) and by **constraint name**, never by driver message text.

### 4.3 The CSV parser is hand-written, and that is the safe choice

`CsvParser` handles quoting, escaped quotes, embedded newlines, CRLF, a UTF-8 BOM and a trailing blank
line — pinned by 21 tests. A library would have been acceptable, but it would also have been a **new
dependency resolved over the network** in a build that is otherwise offline-reproducible, for a format
whose rules fit in one file. The parser is where the row count and the size bound are enforced, which
is why `content`'s limit is checked there rather than by a `@Size` annotation: the bound is *"how many
rows this server will store"* and the parser is what counts rows, while `@Size` would measure
characters, which is a different question.

### 4.4 The password-reset notifier (Phase 3, OB-002)

Delivered in this module's phase order but belonging to module 1's UC-03.

`PasswordResetConfig` already returned a `PasswordResetNotifier` and `PasswordResetNotifier` was
already the port; the work was the third implementation and the configuration that selects it.

| Condition | Implementation |
|---|---|
| `sink-enabled: true` | `FilePasswordResetNotifier` — writes the link under `backend/target/`. The dev default, so nothing is mailed by accident |
| sink off, `MAIL_HOST` **and** `MAIL_FROM_ADDRESS` both set | `SmtpPasswordResetNotifier` — real delivery |
| sink off, either missing | `NoopPasswordResetNotifier` — the request still succeeds, no message is delivered |

**SMTP configuration deliberately does not use Spring Boot's `spring.mail` block.** That block is
switched on by the mere *presence* of `spring.mail.host`, and an empty string is present — so
`host: ${MAIL_HOST:}` would construct a `JavaMailSender` aimed at a blank host in every deployment
that has no mail server. The values therefore live under `campuscoin.security.password-reset.smtp` and
`PasswordResetConfig` reads them directly, treating a blank host as "no mail server". The trap is
pinned by `PasswordResetConfigTest#aBlankHostYieldsTheNoop`.

STARTTLS defaults **on**: the message carries a one-time token in its query string, so a connection a
network observer can read is a connection that leaks account access. `MAIL_SSL=true` is the separate
switch for implicit TLS on port 465 — the two are not inferred from the port number. Timeouts are 10
seconds per phase, so an unreachable mail server degrades one request instead of holding an HTTP worker
until the OS gives up.

**Nothing about the security behaviour changed**: the three endpoints keep their paths, the token
stays one-time and expiring, the response stays generic so the endpoint cannot be used to enumerate
accounts, the raw token is never logged, and it reaches the world through exactly one exit — the
notifier port. `SmtpPasswordResetDeliveryTest` proves delivery against a loopback SMTP server.

---

## 5. Defects found and fixed

### 5.1 `sp_apply_csv_batch` reported preview-refused rows as duplicates

Found while wiring the commit: the procedure derived `v_skipped` by subtraction from a walk that only
visits `VALID` rows, so every row the preview had refused was counted as "already recorded" — a false
statement about the student's own history, and one the student would see in the batch summary. Fixed
by reading each of the three counters from `import_rows` directly (§3.3).

### 5.2 `CampusCoinApplicationTests` pinned a stale procedure count

The full-suite run caught this: the test asserted **24** procedures and the module's
`sp_flag_transaction` makes **25** (`expected: 24 but was: 25`). The count is asserted exactly so that
a procedure dropped from the script, or added without its call site, is caught here rather than by
whichever integration test happens to use it — and it did exactly that job. Fixed to `isEqualTo(25)`
with a comment naming the procedure and OB-016. The table count (23) is unchanged, which is the
evidence for "no table was added".

### 5.3 A stale javadoc on `RuleSource.IMPORT`

`IMPORT` was documented as "this build does not write it". Module 12's `ImportRuleLearner` writes it,
once per imported row at the commit, from the category the row was actually filed under. Corrected in
place rather than left to contradict the code.

### 5.4 Stale endpoint counts across the documentation set

The M1–M11 docs said "43 paths / 61 operations"; the new ground truth is **56 paths / 76 operations**
(pinned by `OpenApiContractIT`, which asserts `hasSize(56)` and enumerates all 76 operations). The
figure was corrected wherever it appeared, across `README.md`, `docs/api/*.md`, `docs/modules/`,
`docs/testing/manual/` and `docs/SECURITY.md`.

**Two documents were deliberately left carrying the old figure**, because they are records of a past
point rather than living documents, and editing their numbers would falsify what they attest:

- **`docs/HANDOFF_M1_M11.md`** — the M1–M11 release gate. Its 61 / 43 / 736 figures are what the gate
  passed against, so they stay; a new **§7** states the delta (76 / 56 / 1090, 24 → 25 procedures)
  and its scope line now says module 12 was subsequently built and remains locked. Its PASS verdict
  still applies to M1–M11 as delivered.
- **`CAMPUS_COIN_MANUAL_QA_REPORT.md`** — a point-in-time QA run that exercised "61 operations… by
  real HTTP requests". Its claim is about that run, not about the current build; revising the count
  would make it claim a run that never happened. It is untracked and is not maintained.

Where a *living* document quoted the old figure as current — module 11's own module report and manual
procedure, and `docs/api/authentication.md`'s "Not built — module 12, locked" table row — the text was
corrected rather than the number replaced, so the reader is told which figure belongs to which
checkpoint.

A handful of stale *statements* rather than numbers was found alongside them and corrected with them —
the guide's "there are no insight, chatbot or AI endpoints, and none should be built against this
contract", module 11's "the `insight.*` keys are not adjustable" premise (they are, and module 9
already depends on them), and module 10's "no read path exists" for the `INSIGHT` bookmark branch,
which is now refused on **scope** grounds rather than for want of a read path (OB-015).

---

## 6. Tests

### 6.1 Per class

| Class | Tests | Pins |
|---|---|---|
| `ImportApiIT` | 21 | 62–67 end to end: an invalid row beside valid ones, an override, a re-run duplicate check, the commit, the cancel |
| `CsvParserTest` | 21 | Quoting, escaped quotes, embedded newlines, CRLF, BOM, blank lines, the row and size bounds |
| `ImportRowReaderTest` | 24 | Reading a date, an amount and a type out of a line; every way each can fail |
| `ImportPreviewerTest` | 18 | Which rows are `VALID` / `ERROR` / `DUPLICATE`, and the counters |
| `ImportDuplicateDetectorTest` | 17 | The duplicate verdict against the student's own records |
| `ImportWriteFailureTest` | 13 | The classifier, with the real driver exception shapes |
| `CategorisationApiIT` | 23 | 68 end to end, including the preserved "Campus Cafe → Food" example and the override |
| `CategoryRuleMatcherTest` | 15 | The learned-mapping lookup, and that `RULE` beats `AI` |
| `CategorySuggesterTest` | 12 | Rule first, then the provider; `NONE` when neither answers |
| `CategorisationWriteFailureTest` | 12 | The classifier |
| `InsightApiIT` | 26 | 69–71, including the `RULE_BASED` path with no provider configured |
| `InsightNarrativeTest` | 11 | The narrative is layered on the procedure's figures and never replaces them |
| `AnomalyApiIT` | 27 | 72–73, including that no request can set a flag |
| `AnomalyDetectorTest` | 29 | The duplicate window, the unusual-amount multiplier, and the clearing form |
| `AnomalyWriteFailureTest` | 12 | The classifier |
| `ForecastApiIT` | 18 | 74, including the absent `projected` for a student with no complete month |
| `ForecasterTest` | 11 | The three-month average, and the empty case |
| `RecentActivityApiIT` | 16 | 75–76, and that another student's transaction is `404` |
| `RecentActivityWriteFailureTest` | 13 | The classifier |
| `SmtpPasswordResetNotifierTest` | 3 | What the message carries, and that the raw token is not logged |
| `SmtpPasswordResetDeliveryTest` | 1 | Real delivery against a loopback SMTP server |
| `PasswordResetConfigTest` | 6 | Which notifier each configuration selects, including the blank-host trap |
| `OpenApiContractIT` | +5 (14 → 19) | The document lists all **76** operations on **56** paths; the five new tests pin the M12 schemas and the no-duplicate alias list |
| **Total new** | **366** | Suite 736 → **1090** |

All integration classes extend `AbstractMySqlIntegrationTest`, so the real procedures, triggers and
views run against MySQL 8 in Testcontainers. The `*WriteFailure` classes are plain unit tests of
classifiers whose refusal branches the HTTP path cannot deterministically reach.

### 6.2 The acceptance scenarios the prompt named

| Scenario | Test |
|---|---|
| UC-08 "Campus Cafe → Food", then an override, then the corrected mapping is used | `CategorisationApiIT` — the preserved example, asserted through the response's `source` (`RULE` → `OVERRIDE`) and `CategoryRuleMatcher` |
| UC-11 a file with an invalid row and a valid row: the invalid one is identifiable, the valid one is importable | `ImportApiIT` |
| UC-17 insight generation, with and without a provider | `InsightApiIT`, `InsightNarrativeTest` |
| UC-24 a duplicate and an unusual amount are both flagged, and neither can be set from a request | `AnomalyApiIT`, `AnomalyDetectorTest` |
| UC-25 a forecast is produced, and the empty case is an answer rather than an error | `ForecastApiIT`, `ForecasterTest` |
| UC-26 recent activity is recorded and read back, per user | `RecentActivityApiIT` |
| UC-03 / UAT-03 a reset link cannot be reused | `PasswordResetApiIT` (14 tests, unchanged contract) |

### 6.3 Test-design notes

**Ownership is asserted per route, not once.** Every M12 integration class sweeps its own routes for
"no token → `401`, an administrator's token → `403`", and the write classes assert that a second
student's id is a `404` — the same shape module 10's policy uses. A single global sweep would stop
covering a route the moment somebody added a sixteenth operation to the module.

**Delta-based assertions, because the container is shared.** `AbstractMySqlIntegrationTest` starts one
static `MySQLContainer` for the whole JVM run and seeds it from the project's own merged script. Rows
are only ever added, so scan tallies, flag counts and activity counts are asserted as deltas or scoped
to a specific row id.

**The non-triviality control is carried forward.** `OpenApiContractIT`'s recursive sensitive-key scan
is paired with a control that interleaves `"token_version"` and `"password_hash"` into a response and
proves the scan fails, so the assertion cannot pass vacuously over the ten new schemas.

---

## 7. Phase 9 — first review

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every route and field traces to a UC, BR or SRS clause (§9). The exclusions are named in §1 rather than merely absent |
| B. Duplicate / missing endpoints | None of the fifteen restates another. The non-obvious choices are argued in §2.1 and the refusals are enumerated in §1; `OpenApiContractIT#noEndpointIsDuplicated` carries the rejected-alias list |
| C. Ownership | No operation accepts a user id. Every write procedure checks ownership in the database; every read query filters by the caller |
| D. Authorisation | `STUDENT`-only, via the existing rule. The sweep tests assert `403` for an administrator token on every route, and no route was added to the security list |
| E. Error contract | Only the 26 existing `ErrorCode` values are returned — the module added **no error code**. A refusal with no natural code is a `VALIDATION_ERROR` field error naming the field |
| F. Encryption | The module reads and writes encrypted `transactions.description` through the existing boundary; it added no new encrypted column. The plaintext preview copies are recorded in OB-012 |
| G. AI disclosure | Only aggregates and the minimum leave the server; the port has no repository; the response names the author (`source`, `generatedBy`) |
| H. Backward compatibility | No M1–M11 route, DTO, error code or security rule was changed. The two `db/` changes are additive/corrective and mirrored |
| I. `ddl-auto=validate` | Still `validate`; the context starts against the real schema, which is the evidence that no entity/column mismatch was introduced |
| J. Status honesty | The module is reported as **implemented and locked**, never as approved or released |

---

## 8. Phase 10 — adversarial review

Findings that survived and their dispositions:

| # | Attack | Result |
|---|---|---|
| 1 | Read another student's import batch by guessing an id | `404`, identical to a batch that does not exist. Pinned by `ImportApiIT` |
| 2 | Set an anomaly flag from a request body | Impossible — no request type has a flag field, and the only writer is `AnomalyDetector` via `sp_flag_transaction`. Pinned by `AnomalyApiIT` |
| 3 | Use 68 to move a record into a category | It cannot: the endpoint stores a suggestion and writes no `transactions` column but the suggestion pair. Pinned by `CategorisationApiIT` |
| 4 | Read another student's insight | `404`. The `insights` query is keyed on `(user_id, period_month)` |
| 5 | Make the provider see a transaction's amount or id | The request types have no such field. A provider returning a category name the student does not have has it discarded, and a category id from the provider is never trusted. Pinned by `CategorySuggesterTest` |
| 6 | Reuse a password-reset token | Refused by `sp_verify_password_reset_token` / `sp_complete_password_reset`; pinned by `PasswordResetApiIT` (UAT-03) |
| 7 | Read a reset token out of a log | Never logged. `SmtpPasswordResetNotifierTest` asserts the message body is the only carrier, and `SmtpPasswordResetDeliveryTest` asserts it against a real conversation |
| 8 | Trigger a mail send with a blank host configured | The blank host yields the no-op notifier. Pinned by `PasswordResetConfigTest#aBlankHostYieldsTheNoop` |
| 9 | Put an unreadable row through a commit and read a false "duplicate" | Fixed in §5.1; the counters are now read from the rows |
| 10 | Change an `anomaly.*` threshold through the admin API | `409 THRESHOLD_NOT_ADJUSTABLE`. The keys are readable, and deliberately not in the allow-list (OB-017) |

---

## 9. Traceability

| Use case | Steps / rules | Endpoints | Tests |
|---|---|---|---|
| UC-08 | B1 suggest, B2 preserve the student's choice, B6 learn from the correction | 68 | `CategorisationApiIT`, `CategoryRuleMatcherTest`, `CategorySuggesterTest` |
| UC-11 | A1 upload, A2 cancel, B1–B9 preview, correct, import | 62–67 | `ImportApiIT`, `CsvParserTest`, `ImportRowReaderTest`, `ImportPreviewerTest`, `ImportDuplicateDetectorTest` |
| UC-17 | the month's figures, the flagged categories, the narrative | 69–71 | `InsightApiIT`, `InsightNarrativeTest` |
| UC-24 | detect a duplicate and an unusual amount; do not let the client set a flag | 72–73 | `AnomalyApiIT`, `AnomalyDetectorTest`, `AnomalyWriteFailureTest` |
| UC-25 | project the next month from the student's own history | 74 | `ForecastApiIT`, `ForecasterTest` |
| UC-26 | record and list what the student opened or changed | 75–76 | `RecentActivityApiIT`, `RecentActivityWriteFailureTest` |
| UC-03 (Phase 3) | request / verify / complete, one-time token, generic response, real delivery | 4–6 | `PasswordResetApiIT`, `SmtpPasswordResetDeliveryTest`, `PasswordResetConfigTest`, `SmtpPasswordResetNotifierTest` |
| BR-02 | ownership on every read and write | all | the per-route ownership sweeps |
| BR-05 | the category decides the record's type | 65, 66 | `ImportApiIT` |
| BR-12 | a suggestion is stored as advice | 68 | `CategorisationApiIT` |
| BR-13 | a suggestion never files a record; an insight is not financial advice | 68, 69, 71 | `CategorisationApiIT`, `InsightApiIT` |
| BR-15 | the spike threshold the insights and tips share | 69–71 | `InsightApiIT`; the module 9 suite already pins the tips half |
| VĐ-05 | the two `anomaly.*` thresholds are read at the point of use | 72–73 | `AnomalyDetectorTest` |
| SRS §7.5 | role and ownership policy | all | the sweep tests |

---

## 10. Definition of done

| Requirement | Status |
|---|---|
| The six module-12 use cases implemented | **Done** — 15 operations |
| Ownership enforced on every read and write | **Done** — in the query, and in the two procedures |
| Anomaly flags decided by the server only | **Done** — no request type has a flag field |
| A category suggestion never files a record | **Done** — BR-13, pinned by test |
| The AI provider never sees the database; the key never leaves the server | **Done** — `AiSuggestionPort` has no repository, and the key is environment-only |
| A deployment with no AI credential works fully | **Done** — `NoopAiSuggestionPort`; `generated_by` records `RULE_BASED` |
| CSV import: invalid rows identifiable, valid rows importable, ownership preserved | **Done** — pinned by `ImportApiIT` |
| Real password-reset email delivery, preserving the existing contract | **Done** — three notifiers, selected by configuration; contract unchanged |
| `ddl-auto=validate` still starts cleanly | **Done** |
| The full regression suite green | **Done** — 1090 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS |
| Documentation set updated | **Done** — this file, `docs/api/{imports,ai-and-insights,advanced}.md`, the guide, the inventory, OB-002/006/012/015/016/017, `.env.example` |
| External credentials supplied | **Not done — blocked.** The AI key and the SMTP credentials are the project owner's to provide (§11) |
| Frontend wired | **Not done by design** — module 12 is locked |

---

## 11. Deferred / blocked

### External dependencies

| # | What is missing | What it blocks | What happens meanwhile |
|---|---|---|---|
| OB-002 | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS` (and `MAIL_FROM_NAME`) — or a transactional provider's equivalent | Real email delivery of the reset link | The flow is complete and tested. The dev profile writes the link to a file; production installs the no-op notifier and the request still succeeds. **No code change is needed once the values arrive** — `PasswordResetConfig` selects the SMTP notifier automatically when `MAIL_HOST` and `MAIL_FROM_ADDRESS` are both set |
| — | `GEMINI_API_KEY` (and the model, if not the default) | The provider-written narrative and a provider category suggestion | Both features work without it: UC-08 answers from the student's own learned rules, UC-17 keeps the `RULE_BASED` narrative. Delivery of a suggestion from the provider is untested against the real service; the port and its validation are pinned by stub-based tests |

### Recorded, not fixed

| # | Item | Why it is deferred |
|---|---|---|
| OB-012 | `import_rows.parsed_description` and `import_rows.raw_data` hold plaintext copies of text `transactions.description` encrypts | Re-scoped by this module rather than closed; resolving it is a schema decision for the project owner |
| OB-013 | Amounts remain unencrypted | MySQL cannot sum ciphertext; the module adds no new amounts to the exposure but reads the existing ones |
| OB-015 | The `INSIGHT` bookmark branch is still refused | Now refused on **scope** grounds — module 12 is locked — rather than for want of a read path |
| OB-016 | Two `db/` changes and the merged-file mirroring rule | Informational; both changes are in place and mirrored |
| OB-017 | `anomaly.*` thresholds are not administration-tunable | Widening the allow-list is a `db/` change outside a locked module, and would invalidate module 11's documentation |
| — | `tip_templates.condition_params` is not exposed | The schema does not document its meaning and nothing reads it. Recorded rather than guessed |
| — | A provider suggestion end to end | Needs the credential above |
| — | The frontend | Module 12 is locked; the Angular client is still mock-only |

### The lock

**Module 12 is implemented, tested and documented, and it remains locked pending the project owner's
approval.** The status wording the project requires is unchanged and applies as before:

> **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL**

---

## Related documentation

- [../api/imports.md](../api/imports.md) — endpoints 62–67, request and response contract
- [../api/ai-and-insights.md](../api/ai-and-insights.md) — endpoints 68–71, and the AI boundary
- [../api/advanced.md](../api/advanced.md) — endpoints 72–76
- [../api/API_INVENTORY.md](../api/API_INVENTORY.md) — the authoritative endpoint list
- [../api/FRONTEND_API_GUIDE.md](../api/FRONTEND_API_GUIDE.md) — the frontend entry point
- [../OVERNIGHT_BLOCKERS.md](../OVERNIGHT_BLOCKERS.md) — OB-002, OB-006, OB-012, OB-015, OB-016, OB-017
- [../SECURITY.md](../SECURITY.md) — the encryption and disclosure rules this module obeys
- [../testing/manual/MODULE_12_MANUAL_TEST.md](../testing/manual/MODULE_12_MANUAL_TEST.md) — the hand-run procedure
- [MODULE_11_ADMINISTRATION.md](MODULE_11_ADMINISTRATION.md) — the module before this one
