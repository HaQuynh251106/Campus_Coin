# Module 12 — Optional / Advanced (UC-08, UC-11, UC-17, UC-24, UC-25, UC-26) — Manual Test Procedure

**Scope:** endpoints 62–76 — the fifteen module-12 operations: CSV import
(`POST`/`GET /api/v1/imports`, `GET /api/v1/imports/{batchId}`,
`PATCH /api/v1/imports/{batchId}/rows/{rowId}`, `POST /api/v1/imports/{batchId}/commit`,
`POST /api/v1/imports/{batchId}/cancel`), category suggestion (`POST /api/v1/ai/suggest-category`),
monthly insights (`GET /api/v1/insights`, `GET /api/v1/insights/months`,
`POST /api/v1/insights/generate`), anomaly flagging (`GET /api/v1/anomalies`,
`POST /api/v1/anomalies/scan`), forecast (`GET /api/v1/forecast`) and recent activity
(`GET`/`POST /api/v1/recent-activity`). All role `STUDENT` only.

> **Module 12 is implemented but LOCKED pending the project owner's approval.** This procedure is what
> the approval decision can be made against: it exercises the features as built. It does **not** mean
> the module is released, and the frontend must not be wired to any of it until the project owner says
> so. The required status wording is unchanged —
> **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL.**

**Sources of truth for this procedure:** [`docs/api/imports.md`](../../api/imports.md) (62–67),
[`docs/api/ai-and-insights.md`](../../api/ai-and-insights.md) (68–71, §2 the AI boundary),
[`docs/api/advanced.md`](../../api/advanced.md) (72–76),
[`docs/modules/MODULE_12_ADVANCED.md`](../../modules/MODULE_12_ADVANCED.md) (§3 what the database owns
and the two `db/` changes, §4 the implementation, §5 the defects found and fixed),
[`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 62–76),
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md) (seeded accounts),
[`docs/SECURITY.md`](../../SECURITY.md) (§4 the AI disclosure rule this module obeys),
[`docs/OVERNIGHT_BLOCKERS.md`](../../OVERNIGHT_BLOCKERS.md) (OB-002, OB-012, OB-015, OB-016, OB-017),
`db/01_schema.sql` (`import_batches`, `import_rows`, `insights`, `recent_activity`,
`category_rules`), `db/03_procedures.sql` (`sp_apply_csv_batch`, `sp_flag_transaction`,
`sp_touch_recent_activity`, `sp_generate_monthly_insight`), and the implementation under
`backend/src/main/java/com/campuscoin/{imports,categorisation,insight,anomaly,forecast,recent}/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | UC | Success |
|---|---|---|---|---|
| 62 | `POST` | `/api/v1/imports` | UC-11 A1 | `201` the stored preview |
| 63 | `GET` | `/api/v1/imports` | UC-11 | `200` my past imports |
| 64 | `GET` | `/api/v1/imports/{batchId}` | UC-11 B5 | `200` the batch and its rows |
| 65 | `PATCH` | `/api/v1/imports/{batchId}/rows/{rowId}` | UC-11 B6 | `200` the row in its new state |
| 66 | `POST` | `/api/v1/imports/{batchId}/commit` | UC-11 B9 | `200` the batch after importing |
| 67 | `POST` | `/api/v1/imports/{batchId}/cancel` | UC-11 A2 | `200` the cancelled batch |
| 68 | `POST` | `/api/v1/ai/suggest-category` | UC-08 | `200` a proposal, or `source: NONE` |
| 69 | `GET` | `/api/v1/insights` | UC-17 | `200` the month's insight |
| 70 | `GET` | `/api/v1/insights/months` | UC-17 | `200` `{months:[...]}` |
| 71 | `POST` | `/api/v1/insights/generate` | UC-17 | `200` the insight after generating |
| 72 | `GET` | `/api/v1/anomalies` | UC-24 | `200` my flagged records |
| 73 | `POST` | `/api/v1/anomalies/scan` | UC-24 | `200` the scan's tally and list |
| 74 | `GET` | `/api/v1/forecast` | UC-25 | `200` the current month and projection |
| 75 | `GET` | `/api/v1/recent-activity` | UC-26 | `200` my recent activity |
| 76 | `POST` | `/api/v1/recent-activity` | UC-26 | `201` the recorded entry |

There is **no** multipart upload, no `PUT`, no `DELETE`, no `{id}` read on any new collection, and no
request field anywhere that sets an anomaly flag or names a batch's status.

---

## 2. Before you start

### 2.1 Environment

- The stack from `README.md` §3: MySQL 8 and the backend, with the Angular app **not** needed — every
  case here is an HTTP call.
- `db/merged/campuscoin_full.sql` loaded, and `db/06_demo.sql` run if you want the seeded data the
  counts below assume.
- `CAMPUSCOIN_ENCRYPTION_KEY` and `JWT_SECRET` exported. The application will not start without them,
  and the import cases create transactions whose descriptions are encrypted.
- **Optional, for the two AI cases:** `GEMINI_API_KEY`. With it unset — the supported default —
  M12-05 and M12-07 mark the provider-specific steps as **N/A** and everything else still has to pass.

### 2.2 Getting the tokens — the only placeholders this document uses

A token is obtained by calling the sign-in endpoint and **copying the `accessToken` value out of the
response**. Passwords and tokens are never written into this document, never pasted into a source file,
a committed config file, or a bug report. The two supported places to put one are the **Authorize**
dialog in Swagger UI and a shell environment variable local to your session.

Sign in with the seeded accounts from `docs/CREDENTIALS.md`:

```
POST /api/v1/auth/login          (students — body: {"email": "...", "password": "..."})
```

The response contains `accessToken` (copy that value), `tokenType` (`"Bearer"`), `expiresIn`
(seconds — 7200 by default, so a long session may need a fresh sign-in) and a `user` object.

| Placeholder | What it is | Seeded account used here |
|---|---|---|
| `${JWT}` | A `STUDENT` token. Most cases use this one. | Alex Nguyen — `an.nguyen@student.campuscoin.edu` |
| `${USER_A_JWT}` | The `STUDENT` token of one account, the **owner** in the two cross-account cases. Same account as `${JWT}` unless you signed in differently. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of a **second** account, so a case can try to reach someone else's data. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` token, used only by M12-13's `403` sweep. | System Administrator — `admin@campuscoin.edu` |

There are no other placeholders in this document. Where a numeric id appears (a `batchId`, a `rowId`,
a `transactionId`, a `categoryId`), it is an **example**; always use the id you recorded in your own
run.

```bash
# Example: obtain a student token, then use it without ever writing it down.
# Read the password for the seeded student from docs/CREDENTIALS.md and type it at the prompt;
# it is not repeated in this document.
read -rs CC_PW
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e 'an.nguyen@student.campuscoin.edu' --arg p "$CC_PW" \
        '{email:$e,password:$p}')" \
  | jq -r .accessToken
unset CC_PW
# copy that output into a local variable, e.g.:
# export USER_A_JWT='<the value you copied>'      # not committed, not written to a file
```

In Swagger UI: click **Authorize**, paste the token, and it is sent as
`Authorization: Bearer <accessToken>` on every request until you sign out.

### 2.3 Dates, and the month the cases run in

- "Now" means the current moment in **`Asia/Ho_Chi_Minh` (`+07:00`)** — check it with
  `TZ=Asia/Ho_Chi_Minh date +%FT%T`. The database session is pinned to the same offset and the
  application's default zone is the same, so a month boundary is comparable to it.
- **`yyyy-MM` is the month format everywhere** — 69, 70, 71 and the CSV's `date` column. A malformed
  value is a `400`, not a guess.
- **Run M12-06…M12-09 in the first half of a month if you can.** Several cases compare "this month" with
  the months the forecast averages over, and a month with only one day elapsed makes the
  current-month totals small enough to be uninteresting to read. Nothing in the cases *depends* on
  that; it only makes the numbers easier to check by eye.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** and **Bella Tran** are the two seeded students, both `ACTIVE`. Alex is the account
  every case runs as; Bella exists so the cross-account cases have someone to try to reach.
- **A freshly seeded database holds exactly these rows in the module's tables**, and the counts below
  depend on them:

  | Table | Rows | Which |
  |---|---|---|
  | `transactions` | **31** | Alex's own, across four months; `26` live and `5` soft-deleted by the seed |
  | `categories` | **12** | all `user_id IS NULL`: 5 `INCOME` and 7 `EXPENSE` (shared defaults) |
  | `import_batches` | **0** | the seed uploads nothing |
  | `import_rows` | **0** | as above |
  | `recent_activity` | **0** | the seed records none; only 76 writes it |
  | `category_rules` | **0** | the seed learns none; 68 and an import commit write it |
  | `insights` | **3** | `db/06_demo.sql` calls `sp_generate_monthly_insight` for three months — these are `RULE_BASED` |
  | `system_settings` | **16** | of which `anomaly.duplicate_window_days` = `3` and `anomaly.unusual_multiplier` = `3` |

- **Nothing in this module writes `notifications`**, and no case should show a new notification.

### 3.2 Authentication, and the one thing to read before §4

All fifteen endpoints require `Authorization: Bearer <accessToken>` **and** an account whose current
role is `STUDENT`. Each of the six new path prefixes has its own `hasRole("STUDENT")` rule in
`SecurityConfig`, naming the prefix and arguing why an administrator has no use case for it. Those
entries matter because the configuration's catch-all is `.requestMatchers("/api/**").authenticated()`
— a prefix with **no** rule of its own would admit any authenticated account, administrator included,
so the explicit rules are what keep an administrator out. A missing, malformed, expired or revoked
token answers `401`; an administrator's token answers `403 ACCESS_DENIED` (M12-13), and a `200` there
means a rule is missing rather than that the design intends it.

> ### 3.2.1 An external AI provider is optional, and its absence is a supported deployment
>
> The provider is reached through a port that has no repository, and with no credential a no-op
> implementation is installed. Consequences a tester will see:
>
> - **68 answers from the student's own learned rules.** A description the system has never seen
>   answers `source: NONE`. **Do not report this as a failure.** It is a result, and it is the correct
>   result when no provider is configured and nothing has been learned yet.
> - **71's narrative is the `RULE_BASED` summary the database wrote**, and `generatedBy` says so.
>   **Do not report `RULE_BASED` as a defect** — it is the field working correctly.
>
> The provider is **never** given database access, and the key never reaches Angular, MySQL or a JWT.
> §5.3 is the case that checks the disclosure half of this.

> ### 3.2.2 Ownership is enforced by the query and by the procedure, not by a check in Java
>
> No operation takes a user id; identity comes from the token. Two writes go through a stored procedure
> whose body re-checks ownership next to `fk_txn_user`, because that foreign key proves a row *exists*,
> not whose it is. The visible consequence is that **another student's batch, insight or transaction
> answers exactly as one that does not exist does** — a `404`, never a `403`. That is deliberate and is
> pinned by tests. **Do not report the indistinguishable `404` as a defect**: telling a caller "that
> exists but is not yours" is itself a disclosure.

> ### 3.2.3 An anomaly flag can never be set by a client
>
> There is no request field for it anywhere in this module, and the only writer is the server's own
> detector. M12-08 is the case that makes this observable: it scans, reads the marks back, and confirms
> that no request in the procedure ever named one.

### 3.3 What a response looks like — the counters are the interesting part

`ImportBatchResponse` carries **five counters** and they are not interchangeable:

| Field | Before a commit | After a commit |
|---|---|---|
| `totalRows` | every row of the file | unchanged |
| `validRows` | the rows that **will** be imported | the rows that **were** |
| `errorRows` | rows the importer could not read | unchanged |
| `duplicateRows` | rows that look like ones already recorded | unchanged |
| `importedRows` | **`0`** | how many transactions were created |

The invariants to check by eye on every batch are
**`totalRows = validRows + errorRows + duplicateRows`** before a commit,
**`validRows = importedRows`** after one, and — on both — **each counter equal to the number of rows
whose `rowStatus` says so**. The third is what catches the defect §5.1 of the module report records:
the duplicate count used to be derived by subtraction from a walk that only visited `VALID` rows, so
rows the preview had *refused* were reported as "you already recorded this". See §5.5.

### 3.4 What no response may ever contain

- **A password hash, a token version, an encryption key, an AI key, or a raw reset token.** None of
  these is in any module-12 response, at any depth.
- **The plaintext of another student's data.** Every read is scoped to the token's owner.
- **A `parsedDescription` or `rawData` value that differs from the line the file actually contained.**
  `rawData` is the file's own text, published so the preview can show it beside what the importer read.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

> **Three cases leave rows behind, and that is fine.** M12-02 commits a batch (creating transactions),
> M12-08 writes flags, and M12-09 writes activity rows. Nothing here deletes them, and the seed is
> restored by reloading `db/merged/campuscoin_full.sql` plus `db/06_demo.sql`. The one case that
> deliberately cleans up after itself is M12-03, which cancels rather than commits.

---

### M12-01 — A CSV is previewed, and one bad row does not stop the file

**Covers:** UC-11 A1, B1–B5; the counters of §3.3; "invalid rows identifiable, valid rows importable".

**Preconditions:** `${JWT}`. A text editor, so you can build the file's content by hand.

**Steps:**

1. Build a five-line file — a header plus four rows — with **one unreadable row** in the middle. Use
   the student's own category names from `GET /api/v1/categories`; `Food` and `Transport` are seeded
   defaults.

   ```csv
   date,amount,type,description,category
   2026-09-20,12.50,EXPENSE,Lunch,Food
   2026-09-21,not-a-number,EXPENSE,Broken row,Food
   2026-09-22,3.20,EXPENSE,Bus fare,Transport
   2026-09-23,45.00,EXPENSE,Textbook,Food
   ```

2. `POST /api/v1/imports` with the file's text as `content`:
   `{"filename": "september-expenses.csv", "content": "<the file, as one JSON string with \n>"}`.
   Record the batch's `id` as `<BATCH_ID>` and each row's `id`.
3. `GET /api/v1/imports/{<BATCH_ID>}` and record every row's `csvRowNo`, `rowStatus`,
   `errorMessage`, `parsedAmount` and `rawData`.
4. `GET /api/v1/imports` and record the count of entries and the newest one's counters.
5. **Check nothing was imported yet:** `GET /api/v1/transactions` and compare its count with the count
   from before step 2.

**Expected result:**

- Step 2: `201`, and the response is a **preview**: `status` is `PREVIEWED`, `modifiable` is `true`,
  `importedRows` is **`0`**, and `rows` is present with all four.
- Step 2: `totalRows` is **4**, `errorRows` is **1**, and `totalRows = validRows + errorRows +
  duplicateRows`.
- Step 3: **`csvRowNo` counts the header as line 1**, so the broken row is `3` — the number the student
  sees in their spreadsheet, not its index in the array.
- Step 3: the broken row is `ERROR` with a sentence in the student's terms in `errorMessage` — **not a
  stack trace, not a column name, not `null`**. **Its sibling rows are `VALID`**: three good rows
  previewed normally around one bad one. That is the case's point.
- Step 3: `parsedAmount` is `12.50` for the first row, `null` for the broken one, and `rawData` holds
  the line's own text keyed by column name — so the broken row's `rawData.amount` still reads
  `"not-a-number"`.
- Step 4: the new batch is in the history, most recent first, with the same counters **and no `rows`
  key** — the list is a summary.
- Step 5: **the transaction count is unchanged.** Nothing is imported by an upload.

**Result:** [ ] Pass   [ ] Fail

---

### M12-02 — Correcting a row, the duplicate check re-running, and the commit

**Covers:** UC-11 B6, B9; the update path that re-decides a duplicate; BR-05.

**Preconditions:** M12-01's `<BATCH_ID>` with its four rows, still `PREVIEWED`, and `${JWT}`. Find a
category of the **other** type from `GET /api/v1/categories` — if the rows are `EXPENSE`, pick an
`INCOME` one — and call its id `<OTHER_TYPE_ID>`.

**Steps:**

1. `GET /api/v1/transactions` and record the count and the highest `id` — call it `<LAST_TXN_ID>`.
2. `PATCH /api/v1/imports/{<BATCH_ID>}/rows/{<BROKEN_ROW_ID>}` with `{"categoryId": <OTHER_TYPE_ID>}`
   — correcting the row the importer could not read.
3. Read that row's `rowStatus` and `errorMessage` in the response.
4. Pick one `VALID` row and record its `aiSuggestedCategoryId`.
5. Upload a **second** file whose single row exactly matches the row you picked in step 4 — same date,
   same amount, same type, and the **same category name**. Record its `id` as `<DUP_BATCH_ID>`, then
   `GET /api/v1/imports/{<DUP_BATCH_ID>}`.
6. `POST /api/v1/imports/{<BATCH_ID>}/commit` with no body.
7. `GET /api/v1/transactions` again. Record the new count and the new rows' `source`.
8. `PATCH` a row of `<DUP_BATCH_ID>` with a category of a **different type**, then read its `rowStatus`.

**Expected result:**

- Step 3: the corrected row is **no longer `ERROR`** — it is `VALID` (or `DUPLICATE`) and
  `errorMessage` is **absent**. A student fixes a row by choosing the category it belongs in, and
  **the category is what decides the record's type** (BR-05) — that is how a wrong `type` in the file
  is corrected, with no separate field to get wrong.
- Step 5: the second file's one row is **`DUPLICATE`**, with `errorMessage` naming the reason in the
  student's terms. Nothing in the request could have set that — the detector compared it against the
  student's own records on **category, amount and a date within a few days**.
- Step 6: `200`, `status` is `COMMITTED`, `modifiable` is **`false`**,
  **`validRows = importedRows`**, and `committedAt` is now set.
- Step 7: the transaction count rose by **exactly `importedRows`** — not by `totalRows`, and not by the
  error rows. Every new row has `source` of **`CSV`**, and its `description` reads back as the text the
  file carried (§3.4).
- Step 8: **`409 DATA_CONFLICT`.** A committed or cancelled batch cannot be changed — this is a
  *conflict with the batch's state*, not a malformed request, so it is `409` and not `400`. The row is
  unchanged in the database.
- The duplicate row was **not** imported: it is still there with its mark, and no transaction exists
  for it.

**Result:** [ ] Pass   [ ] Fail

---

### M12-03 — Cancelling is the abandon path, and it imports nothing

**Covers:** UC-11 A2; the state machine `UPLOADED → PREVIEWED → COMMITTED | CANCELLED`.

**Preconditions:** `${JWT}`, and a fresh two-row CSV uploaded as in M12-01 — call it `<BATCH_ID>`.

**Steps:**

1. `GET /api/v1/transactions` and record the count.
2. `POST /api/v1/imports/{<BATCH_ID>}/cancel` with no body.
3. `GET /api/v1/imports/{<BATCH_ID>}` and record `status` and `modifiable`.
4. `GET /api/v1/transactions` and compare with step 1.
5. `POST /api/v1/imports/{<BATCH_ID>}/commit` and record the response.
6. `GET /api/v1/imports/{<BATCH_ID>}/rows/{<A_ROW_ID>}` — actually `PATCH` it with any valid
   `categoryId`.

**Expected result:**

- Step 2: `200` with `status` of **`CANCELLED`**.
- Step 3: `modifiable` is **`false`**. The rows are still readable — cancelling abandons, it does not
  erase, so a student can still see what they had.
- Step 4: **the transaction count is unchanged.** Nothing was imported.
- Step 5: **`409`** — a cancelled batch cannot be committed. It is not re-openable, and there is no
  route to change its status.
- Step 6: **`409`** — its rows cannot be changed either.
- **There is no `DELETE /imports/{id}` anywhere in the API.** Cancel is the abandon path, and it is a
  status transition with a defined set of states rather than a row disappearing.

**Result:** [ ] Pass   [ ] Fail

---

### M12-04 — An oversized file is refused, not trimmed

**Covers:** the parser's two bounds — **2000 rows** and about **2,000,000 characters**.

**Preconditions:** `${JWT}`. A way to build a large string — `python3`, `awk` or a spreadsheet export.

**Steps:**

1. Build a file with **exactly 2000** data rows (a header plus 2000 lines) and `POST /api/v1/imports`
   with it — this is the largest file the importer accepts, so it must be **accepted**. Then build a
   file with **2001** data rows and `POST` that. Record each status and, for the second, its
   `message`.
2. Build a file whose content is **more than 2,000,000 characters** but has few rows — a single very
   long `description` on one row, repeated a few times, will do. `POST` it. Record the status.
3. Upload a file with **no header row** (just the data lines). Record the status.
4. Upload a file whose header names no `amount` column. Record the status.
5. `GET /api/v1/imports` and compare the count with before step 1.

**Expected result:**

- Step 1: the **2000-row file is accepted** (`201`, `PREVIEWED`, `totalRows` of 2000), and the
  **2001-row file is `400`**. The boundary is `> MAX_ROWS`, so 2000 is inside it. A `400` on the
  2000-row file would be an off-by-one worth reporting.
- Steps 1, 3, 4: **`400`** each, with a `message` in the student's terms — the row count says the file
  is too large and to split it; the size refusal says the content is too large; the missing-header and
  missing-column refusals name what is missing. **None is a `500`, and none is Spring's default
  multipart error** — there is no multipart route to reach.
- **The refusal is not truncation.** A preview of part of a file would be showing the student something
  other than their file, so nothing is accepted-and-shortened.
- Step 5: **no batch was stored for any of the three refused attempts.** A refused upload leaves no
  trace, which is what makes retrying safe. The 2000-row file did store one, and that is expected.

**Result:** [ ] Pass   [ ] Fail

---

### M12-05 — "Campus Cafe → Food", the override, and the corrected mapping

**Covers:** UC-08 (B1, B2, B6) — the preserved example from the SRS; BR-12; BR-13.

**Preconditions:** `${JWT}`. Record the id of the seeded **Food** category and one other `EXPENSE`
category (**Entertainment** if present) from `GET /api/v1/categories` — call them `<FOOD_ID>` and
`<OTHER_ID>`.

**Steps:**

1. `POST /api/v1/transactions` with
   `{"categoryId": <FOOD_ID>, "type": "EXPENSE", "amount": 12.50, "txnDate": "<today>", "description": "Campus Cafe"}`.
   Record the new transaction's `id` as `<TXN_1>`.
2. `POST /api/v1/ai/suggest-category` with `{"transactionId": <TXN_1>}`. Record `source`,
   `categoryId`, `categoryName` and `learnedRule.source`.
3. **Check the record did not move:** `GET /api/v1/transactions/{<TXN_1>}` and record its
   `categoryId`.
4. Create a **second** transaction with the same description `Campus Cafe` and **no** category of your
   choosing — use `<OTHER_ID>` this time, which is the *correction*. Record its `id` as `<TXN_2>`.
5. `POST /api/v1/ai/suggest-category` with `{"transactionId": <TXN_2>}` and again with `<TXN_1>`.
   Record both `source` values and `learnedRule.source`.
6. Create a **third** transaction described `Campus Cafe` under `<FOOD_ID>` and call 68 on it.
   Record `source` and `categoryName`.
7. Read `category_rules` for the student (read-only, `SELECT keyword, category_id, source FROM
   category_rules WHERE user_id = <the student's id>`).

**Expected result:**

- Step 2: `200`. `source` is **`RULE`** if a mapping already existed, or **`NONE`** on a pristine
  database where nothing has been learned yet (see §3.2.1) — **either is correct here**.
- Step 3: **`categoryId` is still `<FOOD_ID>`.** The suggestion is stored *beside* the record as
  advice; **no call to 68 ever moves a transaction from one category to another** (BR-13). That is the
  case's central assertion.
- Step 5: the second call's `learnedRule.source` is **`OVERRIDE`** — the filing contradicted a
  suggestion, which is the strongest signal the table holds and the correction UC-08 B6 names. On the
  third call the keyword resolves to **`<OTHER_ID>`**, not `<FOOD_ID>`: the correction took.
- Step 6: `source` is **`RULE`** and `categoryName` is **Entertainment** (whatever `<OTHER_ID>` is) —
  the mapping the student taught is what answers, and it is the corrected value.
- Step 7: **one row** for the keyword `campus cafe` — not three. The description is stored trimmed and
  lower-cased, which is the form the next match compares against. Repeating a call over an unchanged
  record writes nothing, so opening a screen twice cannot accumulate history.
- **No amount, date or transaction id was ever needed by the provider.** The request `SuggestCategoryRequest`
  carries only `transactionId`; what leaves the server is the description and the student's own
  category **names** (§5.3).

**Result:** [ ] Pass   [ ] Fail

---

### M12-06 — A month's insight, and the two ways to get one

**Covers:** UC-17; `sp_generate_monthly_insight`; BR-13; BR-15.

**Preconditions:** `${JWT}`.

**Steps:**

1. `GET /api/v1/insights/months` and record the list.
2. `GET /api/v1/insights?month=<the most recent month in the list>` and record every field, especially
   `generatedBy`, `model` and `flaggedCategories`.
3. `POST /api/v1/insights/generate` with `<that same month>` as the query parameter.
4. `GET /api/v1/insights?month=<a month with no data at all — e.g. 2019-01>`.
5. `GET /api/v1/insights?month=2026-13` and `?month=not-a-month`.
6. Re-read the month from step 2 and compare `totalExpense` with what `GET /api/v1/reports?month=<that
   month>` reports for the same month.
7. **If `GEMINI_API_KEY` is set:** repeat step 3 and record whether `generatedBy` changed. **If it is
   not set: N/A.**

**Expected result:**

- Step 1: `200` with `{"months": [...]}`, newest first. The seeded database has **three** (see §3.1).
- Step 2: `200` with `periodMonth`, `totalIncome`, `totalExpense`, `netAmount`, `summary`, `advice`,
  `generatedBy`, `flaggedCategories` and `generatedAt`. **`netAmount` is income minus spending** and is
  negative when the student spent more than they received.
- Step 2: `generatedBy` is **`RULE_BASED`** on a deployment with no AI credential, and **`model` is
  absent** (not `null` — omitted). **Do not report either as a defect** (§3.2.1). A `flaggedCategories`
  entry carries `categoryId`, `categoryName`, `currentTotal`, `baselineAvg` and `pctChange` — the
  categories that ran above their **own** usual level, judged by `insight.spike_threshold_pct` over
  `insight.spike_baseline_months`.
- Step 2: **`advice` reads as a suggestion, not as a certification.** An insight is advice, and the
  response is where that has to be visible.
- Step 3: `200` with the month's insight. **Generating is idempotent** — a second call in the same month
  adds nothing, so a refresh button is safe.
- Step 4: `404` for a month with nothing generated. **Not an empty insight and not a zero-filled one** —
  the month has no insight, and saying so is the honest answer.
- Step 5: **`400` each.** A malformed month is a validation error, never a guess or a default.
- Step 6: **the totals agree.** The figures are the database's own; the narrative is layered on top by
  the backend and never replaces them.
- Step 7: with a credential, `generatedBy` may read **`AI`** and `model` is then present. With one
  absent, it stays `RULE_BASED` — nothing is faked either way.

**Result:** [ ] Pass   [ ] Fail

---

### M12-07 — The forecast, and the empty case that is an answer

**Covers:** UC-25; the three-month average; the two optional response objects.

**Preconditions:** `${JWT}`. A student with at least three complete months of history — the seeded
Alex has four.

**Steps:**

1. `GET /api/v1/forecast`. Record `currentMonth`, `nextMonth`, `basedOnMonths`, `recentMonths`,
   `currentMonthTotals` and `projected`.
2. Check each figure in `projected` against the average of the last three entries in `recentMonths`.
3. Register a **brand-new** account with `POST /api/v1/auth/register`, sign in, and `GET /api/v1/forecast`
   with **its** token. Record what is present and what is absent.
4. Call `GET /api/v1/forecast` with `${USER_B_JWT}` — the **second** seeded student, **Bella Tran**,
   who has no transactions at all.
5. `GET /api/v1/forecast?month=2026-01` and `GET /api/v1/forecast?month=` — record both responses.

**Expected result:**

- Step 1: `200` with `currentMonth` the month in progress and `nextMonth` the month **after** it. The
  projection is **not** for the month in progress.
- Step 2: `projected.expense` is the **average of the last three complete months**, and `basedOnMonths`
  is **`3`** (or fewer if the student has less history). `savings` may be **negative** — a projection
  where the student spends more than they receive is a real answer, not an error.
- Step 3: `basedOnMonths` is **`0`**, and **`projected` is absent from the JSON** — not `null`, not
  zeros. `currentMonthTotals` is also absent if nothing has been recorded. **That is the honest answer
  for a student with no complete month to average, and the UI is expected to say so rather than render
  zeros.**
- Step 4: the same shape for an account with no records — `basedOnMonths` of `0` and no `projected`.
  Bella is the seeded "new student" and is what DB_DESIGN and the M9/M10 procedures use for the
  empty-state comparison, so the two cases should agree exactly.
- Step 5: **the `month` parameter is ignored, not honoured.** There is no `@RequestParam` for it on 74 —
  the month is a documented judgement rather than a client choice — so an unbound query parameter is
  discarded by Spring and both calls return **exactly the same body as the plain `GET` in step 1**.
  There is no `400`, because nothing validated the value: it never reached the controller. **What must
  not happen is a projection for an arbitrary past month**, which is what a bound parameter would
  produce.

**Result:** [ ] Pass   [ ] Fail

---

### M12-08 — A duplicate and an unusual amount are both flagged, and no client can set a flag

**Covers:** UC-24; "do not allow the client to arbitrarily set anomaly flags"; VĐ-05; OB-017.

**Preconditions:** `${JWT}`. The seeded `anomaly.duplicate_window_days` = `3` and
`anomaly.unusual_multiplier` = `3`.

**Steps:**

1. `GET /api/v1/transactions` and record how many rows carry any flag field.
2. `POST /api/v1/anomalies/scan` with no body. Record `examined`, `flagged`, `cleared`, `unchanged` and
   the `entries` array.
3. Check the invariant: **`examined = flagged + cleared + unchanged`**.
4. `POST /api/v1/anomalies/scan` a **second** time and compare the tally with step 2.
5. Create two transactions that are **the same** — same category, same amount, dates two days apart —
   and one transaction whose amount is roughly **ten times** the student's usual for its category.
6. Scan again. Record which of the three new rows are flagged and their `flagType` and `flagNote`.
7. `GET /api/v1/anomalies?limit=20` and record `limit` and the `entries`.
8. `GET /api/v1/anomalies?limit=101`, then `?limit=0`.
9. Search every response body so far for any string that looks like a request field for a flag —
   `flagType`, `isFlagged`, `flagNote` as an **input**.

**Expected result:**

- Step 2: `200` with the four counters and a list. `entries` may be **empty** on a clean seed — an empty
  review queue is the ordinary case, not a failure.
- Step 3: **the invariant holds.** If it does not, the scan is double-counting or losing rows and that
  is a defect worth reporting precisely.
- Step 4: **every examined record is under `unchanged` and nothing was written.** A rescan that reaches
  the same conclusion writes nothing at all — the flag `UPDATE` fires a history row only when one of
  the three columns actually changed (BR-09). **A refresh button is therefore safe.**
- Step 6: the near-identical pair is flagged **`DUPLICATE`**, and the outlier is flagged
  **`UNUSUAL_AMOUNT`**. `flagNote` explains it **in the student's terms** — it names the category,
  because the note is read on its own.
- Step 7: each entry carries `transactionId`, `categoryId`, `categoryName`, `categoryType`, `amount`,
  `txnDate`, `description` (omitted when there is none), `isFlagged` and `flagType` with `flagNote`.
  **`isFlagged` is always `true` here** — every entry was selected by `WHERE t.is_flagged = 1`, so the
  field carries no information the endpoint's existence does not already give. It is published only so
  the field set matches a transaction's own shape. **Do not read it as a per-entry distinction.**
  **The `amount` and `description` are the caller's own**, decrypted by the existing boundary.
- Step 8: **`400` each.** A limit above 100 is refused, **not clamped**, because the response echoes the
  limit it applied and a clamped answer would make the echoed field untrue.
- Step 9: **there is no input field for a flag anywhere.** The only writer is the server's own detector,
  through `sp_flag_transaction`. **Try to set one:** `POST /api/v1/transactions` with an extra
  `"flagType": "DUPLICATE"` in the body must either be `400` or ignore the field — it must **never**
  store a flag. Then `GET /api/v1/transactions/{id}` and confirm no flag was set.
- **`anomaly.*` is not administrator-adjustable** (OB-017): `PATCH /api/v1/admin/settings/anomaly.duplicate_window_days`
  with `${ADMIN_JWT}` answers **`409 THRESHOLD_NOT_ADJUSTABLE`**, even though the key is readable in
  `GET /admin/settings`.

**Result:** [ ] Pass   [ ] Fail

---

### M12-09 — Recent activity is the client's own record, and it is per user

**Covers:** UC-26; `sp_touch_recent_activity`; BR-02.

**Preconditions:** `${JWT}` and `${USER_B_JWT}`. A transaction id belonging to **Alex** — call it
`<ALEX_TXN_ID>` — and one belonging to **Bella**, if she has one.

**Steps:**

1. `GET /api/v1/recent-activity` with `${JWT}`. Record `limit` and the entries.
2. `POST /api/v1/recent-activity` with `{"transactionId": <ALEX_TXN_ID>, "action": "VIEWED"}`.
3. `POST /api/v1/recent-activity` with the same id and `{"action": "EDITED"}`.
4. `GET /api/v1/recent-activity` with `${JWT}` and record the order and the two new entries.
5. `GET /api/v1/recent-activity` with `${USER_B_JWT}`. Compare with step 4.
6. `POST /api/v1/recent-activity` with `${JWT}` and `{"transactionId": <A_BELLA_TXN_ID>, "action": "VIEWED"}`.
7. `POST /api/v1/recent-activity` with `{"transactionId": 999999999, "action": "VIEWED"}`.
8. `POST /api/v1/recent-activity` with `{"transactionId": <ALEX_TXN_ID>, "action": "DELETED"}`.
9. `GET /api/v1/recent-activity?limit=51` and then `?limit=10`.
10. Check that the transaction itself is unchanged: `GET /api/v1/transactions/{<ALEX_TXN_ID>}`.

**Expected result:**

- Step 1: `200` with `limit` of **`10`** and an `entries` array — **empty on a fresh seed**, which is a
  real answer.
- Step 2–3: `201` each with `transactionId`, `action`, `occurredAt`, `categoryId`, `categoryType`,
  `amount`, `txnDate` and `description` when there is one.
- Step 4: the two new entries are **first**, ordered by `occurredAt` descending, with actions `EDITED`
  then `VIEWED`.
- Step 5: **Bella's list does not contain Alex's entries.** The read is scoped by the token's owner —
  this is BR-02 made structural, and it is the case's point.
- Step 6: **`404`.** Another student's transaction answers exactly as one that does not exist does
  (§3.2.2). **Do not report the `404` as a defect** — a `403` would disclose that the row exists.
  Confirm **no** entry was written for Bella's transaction.
- Step 7: `404` for an id that does not exist.
- Step 8: **`400`.** `RecentAction` is exactly `VIEWED` and `EDITED` — this is the record of having
  *opened or changed* a record, not of what happened to it, so there is no `DELETED`.
- Step 9: `?limit=51` is **`400`** (the max is 50, and an over-large value is refused rather than
  clamped); `?limit=10` is the default and answers `200`.
- Step 10: **the transaction is unchanged.** 76 records an entry; it does not edit the transaction. That
  is the difference between this route and `GET /transactions?sort=recent`, and it is why the table is a
  separate one.

**Result:** [ ] Pass   [ ] Fail

---

### M12-10 — The ownership sweep: someone else's id looks like no id at all

**Covers:** BR-02 across every read in the module; §3.2.2.

**Preconditions:** `${USER_A_JWT}` (Alex) and `${USER_B_JWT}` (Bella). Alex has a committed import
batch, an insight and a flagged record, from M12-02, M12-06 and M12-08 — call the batch `<BATCH_ID>`.

**Steps:**

1. As Alex, `GET /api/v1/insights?month=<a month Alex has an insight for>` and record the body.
2. As **Bella**, request the **same** month, and also a month she has nothing for. Record both.
3. As **Bella**, `GET /api/v1/imports/{<BATCH_ID>}` — Alex's batch.
4. As **Bella**, `PATCH /api/v1/imports/{<BATCH_ID>}/rows/{<A_ROW_ID>}` with a valid `categoryId`.
5. As **Bella**, `POST /api/v1/imports/{<BATCH_ID>}/commit`.
6. As **Bella**, `POST /api/v1/imports/{<BATCH_ID>}/cancel`.
7. As **Bella**, `POST /api/v1/ai/suggest-category` with Alex's `<ALEX_TXN_ID>`.
8. As **Bella**, `POST /api/v1/anomalies/scan` and `GET /api/v1/anomalies`.
9. Compare `GET /api/v1/imports` under each token.

**Expected result:**

- Steps 3–7: **`404` in every case, with a body indistinguishable from the one for an id that does not
  exist.** Not `403`, and not a different message — the distinction itself would disclose that Alex's
  batch exists. **Do not report this as a defect** (§3.2.2).
- Step 5–6: **Alex's batch is untouched** — still `COMMITTED`, with its counters and, crucially, the
  same number of transactions. Bella's call changed nothing.
- Step 8: Bella's scan examines **Bella's** records and flags only hers. **Alex's flags are unchanged** —
  the scan reads by token owner, and the write procedure re-checks ownership beside `fk_txn_user`.
- Step 9: **the two lists are disjoint.** Bella sees her own batches, which may be none.
- **No response in the whole sweep contains a row belonging to the other account**, at any depth.

**Result:** [ ] Pass   [ ] Fail

---

### M12-11 — Secrets and keys never leave the server

**Covers:** the AI security rules; SECURITY.md §4; the standing constraint that the encryption key is
symmetric and environment-only.

**Preconditions:** The stack running with `CAMPUSCOIN_ENCRYPTION_KEY` and `JWT_SECRET` exported, and —
if you want the full case — `GEMINI_API_KEY` set as well.

**Steps:**

1. Capture the raw response body of every endpoint exercised in M12-01…M12-10 and search all of them
   for: the value of `CAMPUSCOIN_ENCRYPTION_KEY`, the value of `JWT_SECRET`, the value of
   `GEMINI_API_KEY`, the string `passwordHash`, and the string `tokenVersion`.
2. `GET /api-docs` and search the **whole document** for the same strings, and for any `apiKey`
   security scheme other than `bearerAuth`.
3. Search the running application's log for the value of `GEMINI_API_KEY` and for any reset token.
4. Read `backend/src/main/resources/application.yml` and `application-dev.yml` and confirm the AI and
   SMTP blocks carry **no literal credential** — every one is `${VAR:}` or absent.
5. Read `.env.example` and confirm every secret is a **placeholder**, and that `.env` itself is not
   committed (`git check-ignore -v .env`).

**Expected result:**

- Step 1: **no match, anywhere, at any depth.** The responses carry a student's own data and no secret.
- Step 2: **no match**, and the only security scheme is `bearerAuth`. **No API key is exposed to the
  frontend** — not in a schema, not in an example, not in a description.
- Step 3: **no match.** The AI key is never logged, and a reset token never is either.
- Step 4: **no literal credential in any profile.** The AI key and the SMTP password are read from the
  environment and nowhere else.
- Step 5: placeholders only. If `.env` were tracked, that is a finding worth reporting immediately.
- **The provider was never given database access.** Nothing in this case can prove that from the
  outside; the check is the type — `AiSuggestionPort` has no repository, and
  `docs/modules/MODULE_12_ADVANCED.md` §4.1 states it. What you *can* confirm from the outside is that
  **only aggregates and a description left the server**: 68 sends the description and the student's own
  category **names** — no amount, no date, no identifier — and 69/71 send the month's totals and
  category names, never a transaction.

**Result:** [ ] Pass   [ ] Fail

---

### M12-12 — The reset link, the AI surfaces and the module's own absence of duplicates

**Covers:** the Phase 3 reset work (OB-002); the endpoint inventory; the "no duplicate route" rule.

**Preconditions:** `${JWT}`, and a mail catcher if you want to exercise delivery.

**Steps:**

1. `POST /api/v1/auth/password-reset/request` with a seeded student's email. Record the status and body.
2. **Read `backend/target/password-reset-dev.log`** (the dev sink) and copy the link. Record where the
   raw token appeared — the sink file, and nowhere else.
3. `POST /api/v1/auth/password-reset/verify` with that token, then `POST /api/v1/auth/password-reset/complete`
   with the same token and a new password. Then `verify` the **same** token again.
4. `POST /api/v1/auth/password-reset/request` with an email that does not exist and compare the body and
   status with step 1.
5. Confirm the **contract is unchanged** for 4, 5 and 6: same paths, same bodies, same statuses. Then
   sign in with the password set in step 3.
6. `GET /api-docs` and count the operations and paths. Then look for any alias: `PUT` on any module-12
   resource, `DELETE /imports/{id}`, `POST /imports/{id}/rows` (rather than `PATCH` on a named row), a
   `{id}` read on `/insights`, `/anomalies` or `/recent-activity`, and any `/admin/insights`,
   `/admin/anomalies` or `/admin/ai` route.
7. **If `RESET_SINK_ENABLED=false` and `MAIL_HOST` plus `MAIL_FROM_ADDRESS` are set:** repeat step 1 and
   read the catcher. **Otherwise: N/A.**

**Expected result:**

- Step 1: `200` with a **generic** message, and the same message whether or not the address exists
  (step 4) — the anti-enumeration rule (BR-04) is intact.
- Step 2: the raw token is in the sink file **and nowhere else** — not the response, not the log, not
  the database (which stores a 64-hex `token_hash`). **This is OB-002's testable half.**
- Step 3: `verify` and `complete` succeed once; the **second `verify` is a `400 INVALID_RESET_TOKEN`.**
  A reset link cannot be reused — that is UAT-03.
- Step 5: **the contract is byte-for-byte what it was before module 12.** The reset work added a third
  notifier implementation and configuration; it did not change the endpoints.
- Step 6: **76 operations on 56 paths.** Every alias in the list is **absent**. Each absence is argued
  in `docs/api/API_INVENTORY.md`'s module-12 section rather than merely missing.
- Step 7: with the sink off and both variables set, the message **is actually delivered** and the link
  works. That is the whole of the delivery work — `SmtpPasswordResetNotifier` selected by configuration,
  no code change needed.
- **With the sink off and either variable missing, no email is sent and the request still succeeds.**
  That is the supported no-credential deployment, and **the link is never silently written to a file on
  a server**.

**Result:** [ ] Pass   [ ] Fail

---

### M12-13 — Every route refuses an administrator, and a missing token is not a refusal by role

**Covers:** §7.5; the `STUDENT` role rule that covers every new path without being named.

**Preconditions:** `${ADMIN_JWT}` and a **valid body** for each writing route, so a `400` cannot be
mistaken for the right answer.

**Steps:**

1. For each of the fifteen operations in §1, send the request **with no `Authorization` header**.
2. Repeat each with `${ADMIN_JWT}`.
3. Repeat each with a **tampered** token — flip one character in the middle.
4. Sign in as a student, then `POST /api/v1/auth/logout`, then reuse the token.

**Expected result:**

- Step 1: **`401 UNAUTHENTICATED`** on all fifteen. Not `403` — a missing credential is not a refusal by
  role, and the two must not be conflated.
- Step 2: **`403 ACCESS_DENIED`** on all fifteen with `errorCode` of `ACCESS_DENIED`. Each of the six
  new path prefixes has its own `hasRole("STUDENT")` rule in `SecurityConfig`, naming that prefix and
  arguing why an administrator has no use case for it. If any of the fifteen answered `200` instead,
  that prefix's rule is missing and the request fell through to the catch-all
  `/api/**` → `authenticated()`, which admits **any** authenticated account — so a `200` here is a
  finding worth reporting precisely, not a cosmetic difference.
- Step 3: **`401`** on all fifteen.
- Step 4: **`401`** — the session was revoked.
- **No route answers `500`, and no route leaks a stack trace or an internal class name.** Every error
  body has the documented shape: `timestamp`, `status`, `errorCode`, `message`, and `fieldErrors` only
  when it is a validation error.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Behaviours a tester is most likely to misread

### 5.1 `source: NONE` and `generatedBy: RULE_BASED` are results, not failures

On a deployment with no AI credential — which is supported and is the default — 68 answers from the
student's own learned mappings and says `NONE` when there is none, and 71's narrative is the summary
the database wrote with `generatedBy` of `RULE_BASED`. **Nothing is faked and nothing is missing.**
The response names its author precisely so a reader can tell the two apart; reporting `RULE_BASED` as a
defect would be asking the system to lie.

### 5.2 An empty anomalies list, an absent `projected`, and a `404` insight are all answers

- An empty `entries` array from 72 means the student has nothing to review. It is the ordinary case.
- An **absent** `projected` in 74 means there is no complete month to average. The UI is expected to
  say so; zeros would be a false projection. Zeros are the thing to report, not the omission.
- A `404` from 69 means nothing has been generated for that month. It is not an error to retry blindly —
  71 is what generates it.

### 5.3 Another student's row answers `404`, and that is deliberate

Steps 3–7 of M12-10 all answer `404`, never `403`. Answering "that exists but is not yours" discloses
that the row exists — which is exactly what module 10's policy forbids, and the same rule applies here.
The batch, the insight, the flagged record and the transaction all behave identically, which is what
makes the policy a rule rather than a per-endpoint decision.

### 5.4 A rescan writes nothing, and that is the design

M12-08 step 4: a second scan over unchanged data reports every record under `unchanged` and writes no
row. The flag `UPDATE` fires the history trigger **only when one of the three columns actually
changed** (BR-09). So a refresh button is safe, and "the second scan wrote nothing" is the expected
result rather than evidence that the scan failed.

### 5.5 The import's counters have one definition each, and they used to have two

The commit originally derived the duplicate count by subtraction from a walk that only visits `VALID`
rows, so **every row the preview had refused was counted as "already recorded"** — a false statement
about the student's own history. That is the defect the module report records as §5.1, and the fix is
that all three counters are now read from the rows.

**The check that catches a regression is to count the rows by `rowStatus` and compare.** On every batch,
`errorRows` must equal the number of rows whose `rowStatus` is `ERROR`, and `duplicateRows` must equal
the number whose `rowStatus` is `DUPLICATE` — including after a commit, when the `IMPORTED` rows join
in. If a batch's `duplicateRows` disagrees with its own `DUPLICATE` row count, the fix has regressed;
report it with the batch id.

**Do not use `errorMessage` as that signal.** `errorMessage` is deliberately populated for a
`DUPLICATE` row as well as for an `ERROR` one — a duplicate is not a mistake in the row, but it *is* a
row that will not be imported, and UC-11 requires the student to see why. The two are told apart by
`rowStatus`, which is exactly why `rowStatus` is the field to count.

### 5.6 `csvRowNo` counts the header as line 1

The importer's row number is the one the student sees in their spreadsheet, not the array index. A
4-row file has rows numbered 2–5, and the first data row is `2`. A UI that shows the array index will
send the student to the wrong line of their file.

### 5.7 An import produces two copies of the description, and one is plaintext

`transactions.description` is stored as ciphertext (AES-256-GCM), but the preview's
`import_rows.parsed_description` and the whole-line `import_rows.raw_data` are plaintext — so an
imported row leaves an encrypted transaction **and** a plaintext preview copy of the same text. That
exposure is recorded as **OB-012** and re-scoped by this module rather than closed; **do not report it
as a new defect**, and do not assume the preview's copy is encrypted.

### 5.8 `anomaly.*` is readable but not adjustable

`GET /admin/settings` lists `anomaly.duplicate_window_days` and `anomaly.unusual_multiplier`, so they
appear tunable. **`PATCH` on either answers `409 THRESHOLD_NOT_ADJUSTABLE`** — they are outside
`sp_admin_set_threshold`'s six-key allow-list (OB-017). A deployment that needs a different window
changes the seeded row directly; that is a database operation, not an API one.

### 5.9 The lock is real, and this document does not lift it

Every case above can pass in full, and module 12 is **still locked pending the project owner's
approval**. The Angular frontend must not be wired to endpoints 62–76 until that decision is made, and
the required status wording is unchanged.

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M12-01 | UC-11 A1, B1–B4; the preview's five counters; `csvRowNo` counting the header; an unreadable row not stopping the file; the file-is-JSON decision |
| M12-02 | UC-11 B6, B9; the override being authoritative at the commit; the duplicate check re-running; BR-05 (the category decides the type); `source` of `CSV` |
| M12-03 | UC-11 A2; the state machine; `409` on a closed batch; no `DELETE` |
| M12-04 | The parser's two bounds (2000 rows, ~2,000,000 characters); refusal rather than truncation; a refused upload leaves no batch |
| M12-05 | UC-08 B1, B2, B6; the preserved "Campus Cafe → Food" example; BR-12 (advice stored beside the record); BR-13 (never files); the one-row keyword |
| M12-06 | UC-17; `sp_generate_monthly_insight`; `generatedBy`; BR-13 (advisory); BR-15 (the spike keys); idempotent generation; a malformed month |
| M12-07 | UC-25; the three-month average; the two optional objects; the absent `projected` as an answer |
| M12-08 | UC-24; the flag is server-decided; the scan invariant; a rescan writing nothing; VĐ-05; OB-017 |
| M12-09 | UC-26; `sp_touch_recent_activity`; `RecentAction`'s two values; BR-02; 76 not editing the transaction |
| M12-10 | BR-02 across every read; the indistinguishable `404`; a procedure's ownership check |
| M12-11 | The AI security rules; SECURITY.md §4; the key never leaving the server; only aggregates and a description leaving it |
| M12-12 | OB-002; UC-03; UAT-03 (one-time token); BR-04 (anti-enumeration); the inventory and the refused aliases |
| M12-13 | SRS §7.5; the `STUDENT` role rule covering every new path; `401` versus `403`; the error contract |
| §5.1–§5.9 | The nine behaviours most likely to be misread |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass.

| Not covered | Why |
|---|---|
| A provider's suggestion against the real service | Needs a real `GEMINI_API_KEY` for the deployment under test. The port and its answer validation are pinned by stub-based tests; M12-05 and M12-06 mark the provider-specific steps N/A when no key is configured |
| Real SMTP delivery without a mail server | Needs the SMTP credentials (OB-002). The dev sink and the loopback-server test cover both halves; M12-12 step 7 is N/A without them |
| A timeout mid-provider-call | Not reliably producible by hand. A timeout is treated as "no suggestion" and pinned by a unit test |
| Two requests racing over the same import batch | A manual tester cannot produce it reliably, and a race that happens not to interleave proves nothing |
| A `500`-level failure path | Not reachable through the API: there is no input to make fail and no fault injection |
| The encryption of `transactions.description` | Module 1's and the Phase-0 suite's subject. This document reads a description back only to confirm the round trip survived an import |
| The plaintext preview copies | **OB-012**, recorded and re-scoped rather than fixed (§5.7). A tester confirms they exist; changing them is a schema decision for the project owner |
| Amount encryption | **OB-013**: out of scope by decision, because MySQL cannot sum ciphertext |
| The `INSIGHT` bookmark branch | **OB-015**: still refused, now on scope grounds. Module 10's procedure covers the refusal |
| A wider anomaly threshold allow-list | **OB-017**: a `db/` change outside a locked module |
| Modules 1–11's own behaviour | Their own procedures cover them. This document reads transactions, categories and reports only where an M12 case needs a counterpart to compare against |
| The Angular client | **There is none wired.** Module 12 is locked, and no Angular source is changed by it. `docs/api/FRONTEND_API_GUIDE.md` §7.12 and §8.14 are the integration notes a rewiring would start from |
| Releasing the module | **Not this document's to do.** The status wording is unchanged: **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL** |

---

## Related documentation

- [../../modules/MODULE_12_ADVANCED.md](../../modules/MODULE_12_ADVANCED.md) — the module report
- [../../api/imports.md](../../api/imports.md) — endpoints 62–67
- [../../api/ai-and-insights.md](../../api/ai-and-insights.md) — endpoints 68–71 and the AI boundary
- [../../api/advanced.md](../../api/advanced.md) — endpoints 72–76
- [../../api/API_INVENTORY.md](../../api/API_INVENTORY.md) — the authoritative endpoint list
- [../../OVERNIGHT_BLOCKERS.md](../../OVERNIGHT_BLOCKERS.md) — OB-002, OB-012, OB-015, OB-016, OB-017
- [MODULE_11_MANUAL_TEST.md](MODULE_11_MANUAL_TEST.md) — the module before this one
- [MODULE_10_MANUAL_TEST.md](MODULE_10_MANUAL_TEST.md) — the ownership policy this module reuses
