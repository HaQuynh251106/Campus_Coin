# Module 08 — Reports & Export (UC-15, UC-16) — Manual Test Procedure

**Scope:** endpoints 36 and 37 — `GET /api/v1/reports` and `GET /api/v1/reports/spending`, both
role `STUDENT` only.

**Sources of truth for this procedure:** [`docs/api/reports.md`](../../api/reports.md) (the UC-15/UC-16
contract — §4 the selected month and the one figure that ignores it, §5 the spending series, §6 absent
versus zero, §7 the share rounding rule, §12 why there is no export endpoint),
[`docs/modules/MODULE_08_REPORTS.md`](../../modules/MODULE_08_REPORTS.md) (§4.1 the month-selectability
split, §4.3 the weekly overlap predicate, §5 the six defects found and fixed),
[`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 36–37),
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md) (seeded accounts), `db/02_views.sql` and `db/06_demo.sql`
(what the seeded figures are), and the implementation under
`backend/src/main/java/com/campuscoin/reports/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 36 | `GET` | `/api/v1/reports` | `200` report object |
| 37 | `GET` | `/api/v1/reports/spending` | `200` spending-series object |

**Two endpoints, and the split between them is the module's central design decision — not an accident
of packaging.** Most of a report can be asked about **any** month; the breakdown by day or by week can
only be asked about **the current** month. §3.4 explains why, and it is the single most important thing
to understand before running §4.

**There is no third endpoint.** There is **no** `/api/v1/reports/export`, **no** `/reports/csv`,
**no** `/reports/download`, **no** `/reports/{id}`, **no** `/reports/summary`, `/reports/monthly` or
`/reports/categories`, and **no** `/profile/me/reports`. UC-16 (export) deliberately has no route of
its own — §3.5 and M8-20. An endpoint that is not in the table above does not exist; if a step here
asks you to call one, the document is wrong, not the server.

**No request body, no `POST`/`PUT`/`PATCH`/`DELETE` on either path.** A report is read; there is
nothing for a client to write (M8-20, M8-21).

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 36 | `month` | `yyyy-MM` — **any real month** | the current month |
| 37 | `month` | `yyyy-MM` — **the current month only** | the current month |
| 37 | `from`, `to` | `yyyy-MM-dd` **within** the current month | the 1st and last day of the month |
| 37 | `granularity` | `DAILY` \| `WEEKLY` | `DAILY` |

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema, seed data and demo data applied (`db/01_schema.sql` …
  `db/06_demo.sql`).
- **For M8-02 the database must be in its freshly seeded state.** That case asserts exact figures from
  `db/06_demo.sql`. Both reports are reads, so this procedure changes nothing on the demo account — but
  if you have already run an earlier module's manual tests against the same database, the demo student
  has extra transactions and the seeded figures no longer hold. Re-seed before M8-02, or read it as
  "the shape is right and the numbers are whatever the seed plus your earlier testing produced".

Every step below can be done in Swagger UI or with `curl`.

### 2.2 Getting the four tokens — the only placeholders this document uses

A token is obtained by calling the sign-in endpoint and **copying the `accessToken` value out of the
response**. Passwords and tokens are never written into this document, never pasted into a source
file, a committed config file, or a bug report. The two supported places to put one are the
**Authorize** dialog in Swagger UI and a shell environment variable local to your session.

Sign in with the seeded accounts from `docs/CREDENTIALS.md`:

```
POST /api/v1/auth/login          (students — body: {"email": "...", "password": "..."})
POST /api/v1/admin/auth/login    (administrator)
```

The response contains `accessToken` (copy that value), `tokenType` (`"Bearer"`), `expiresIn`
(seconds — 7200 by default, so a long session may need a fresh sign-in) and a `user` object.

| Placeholder | What it is | Seeded account used here |
|---|---|---|
| `${JWT}` | A `STUDENT` access token. | Alex Nguyen — `an.nguyen@student.campuscoin.edu` |
| `${USER_A_JWT}` | The `STUDENT` token of **owner A**, the seeded demo student whose figures M8-02 asserts. Same account as `${JWT}`; the ownership cases name it explicitly so the two sides of the case are unambiguous. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of **owner B**, a different student with no records. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` access token, obtained from the administrator sign-in endpoint. | System Administrator |

There are no other placeholders in this document. Where a numeric id appears (a `categoryId`, a
transaction `id`), it is an **example**; always use the id you recorded in your own run.

```bash
# Example: obtain a student token, then use it without ever writing it down.
# Read the password for the seeded student from docs/CREDENTIALS.md and type it at the
# prompt; it is not repeated in this document.
read -rs CC_PW
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e 'an.nguyen@student.campuscoin.edu' --arg p "$CC_PW" \
        '{email:$e,password:$p}')" \
  | jq -r .accessToken
unset CC_PW
# copy that output into a local variable, e.g.:
# export USER_A_JWT='<the value you copied>'      # not committed, not written to a file
# export JWT="$USER_A_JWT"
```

In Swagger UI: click **Authorize**, paste the token, and it is sent as
`Authorization: Bearer <accessToken>` on every request until you sign out.

**Unlike modules 6 and 7, this procedure needs no direct database writes.** A report has no writer —
no table, no procedure, no migration — so every case below is driven entirely through HTTP. The
`mysql` command appears once, in M8-02, and only to *read* what the views hold. Every write case
registers or uses a student of its own, so no case disturbs the seeded demo account.

### 2.3 Dates and the month

- "This month" means the current month in **`Asia/Ho_Chi_Minh` (`+07:00`)** — for example `2026-09`.
  Check it with `TZ=Asia/Ho_Chi_Minh date +%Y-%m`. The database session is pinned to the same offset.
- The month is rendered `"YYYY-MM"`, with no day component, on both endpoints' `periodMonth`/`from`.
- A **transaction date** used by the fixture steps is a plain calendar date `"YYYY-MM-DD"` with no time
  and no zone. **BR-08 refuses a future-dated transaction**, so use `date +%F` for today or an earlier
  date.
- To produce a **previous month's** data you must be able to date a transaction in the past. Any past
  date is accepted (BR-08 only forbids the future), so a case wanting an earlier month records a
  transaction dated in that month.
- **The current timezone is a deployment setting, not a guarantee.** What this module guarantees, and
  what M8-14 checks, is narrower: one request is judged against one value.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with three months of demo data (`db/06_demo.sql`). For the **current**
  month it holds: income `200.00` + `60.00` (`260.00`), and expenses Hostel/Rent `120.00`,
  Entertainment `25.00`, Food `24.00`, Transport `12.00`, Subscriptions `8.00` (total `189.00`), so net
  `71.00` across `7` records. The **previous** month holds income `290.00`, expenses `207.00`, net
  `83.00` across `8` records.
- **Bella Tran** is the seeded **empty** account: no transactions, no budgets, no tips. She is the
  clean subject for the empty-state and ownership cases.
- Shared default categories (`Allowance`, `Food`, `Transport`, `Hostel/Rent`, `Academics`,
  `Subscriptions`, `Entertainment`, `Miscellaneous`, `Part-time Job`, `Scholarship`, `Gift`,
  `Other Income`) are visible to every student. `Allowance`, `Part-time Job`, `Scholarship`, `Gift` and
  `Other Income` are `INCOME`; the rest are `EXPENSE`. Use `GET /api/v1/categories` to read the real
  ids and types.

### 3.2 Authentication

Both endpoints require `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403` (M8-18). A missing, malformed, expired or
revoked token answers `401`. There is no anonymous or shared view of a report.

### 3.3 What the two responses look like

Endpoint 36, for the demo account's current month:

```json
{
  "periodMonth": "2026-09",
  "currency": "USD",
  "totals": { "income": 260.00, "expense": 189.00, "net": 71.00, "transactionCount": 7 },
  "expenseByCategory": [
    { "categoryId": 8, "categoryName": "Hostel/Rent", "categoryIcon": "home", "categoryColor": "#EF4444",
      "type": "EXPENSE", "total": 120.00, "percentage": 63.49, "transactionCount": 1 },
    { "categoryId": 11, "categoryName": "Entertainment", "categoryIcon": "film", "categoryColor": "#A855F7",
      "type": "EXPENSE", "total": 25.00, "percentage": 13.23, "transactionCount": 1 },
    { "categoryId": 6, "categoryName": "Food", "categoryIcon": "utensils", "categoryColor": "#F97316",
      "type": "EXPENSE", "total": 24.00, "percentage": 12.70, "transactionCount": 1 },
    { "categoryId": 7, "categoryName": "Transport", "categoryIcon": "bus", "categoryColor": "#3B82F6",
      "type": "EXPENSE", "total": 12.00, "percentage": 6.35, "transactionCount": 1 },
    { "categoryId": 10, "categoryName": "Subscriptions", "categoryIcon": "repeat", "categoryColor": "#EC4899",
      "type": "EXPENSE", "total": 8.00, "percentage": 4.23, "transactionCount": 1 }
  ],
  "incomeByCategory": [
    { "categoryId": 1, "categoryName": "Allowance", "categoryIcon": "wallet", "categoryColor": "#22C55E",
      "type": "INCOME", "total": 200.00, "percentage": 76.92, "transactionCount": 1 },
    { "categoryId": 2, "categoryName": "Part-time Job", "categoryIcon": "briefcase", "categoryColor": "#16A34A",
      "type": "INCOME", "total": 60.00, "percentage": 23.08, "transactionCount": 1 }
  ],
  "sixMonthTrend": [
    { "periodMonth": "2026-04", "income": 0.00, "expense": 0.00, "net": 0.00 },
    { "periodMonth": "2026-05", "income": 0.00, "expense": 0.00, "net": 0.00 },
    { "periodMonth": "2026-06", "income": 280.00, "expense": 211.00, "net": 69.00 },
    { "periodMonth": "2026-07", "income": 350.00, "expense": 199.00, "net": 151.00 },
    { "periodMonth": "2026-08", "income": 290.00, "expense": 207.00, "net": 83.00 },
    { "periodMonth": "2026-09", "income": 260.00, "expense": 189.00, "net": 71.00 }
  ]
}
```

Endpoint 37, the same month by day and by week:

```json
{ "granularity": "DAILY", "from": "2026-09-01", "to": "2026-09-30", "currency": "USD",
  "totalExpense": 189.00,
  "points": [
    { "intervalStart": "2026-09-03", "intervalEnd": "2026-09-03", "totalExpense": 120.00, "transactionCount": 1 },
    { "intervalStart": "2026-09-05", "intervalEnd": "2026-09-05", "totalExpense": 8.00,   "transactionCount": 1 },
    { "intervalStart": "2026-09-07", "intervalEnd": "2026-09-07", "totalExpense": 24.00,  "transactionCount": 1 },
    { "intervalStart": "2026-09-08", "intervalEnd": "2026-09-08", "totalExpense": 12.00,  "transactionCount": 1 },
    { "intervalStart": "2026-09-10", "intervalEnd": "2026-09-10", "totalExpense": 25.00,  "transactionCount": 1 }
  ] }
```

```json
{ "granularity": "WEEKLY", "from": "2026-09-01", "to": "2026-09-30", "currency": "USD",
  "totalExpense": 189.00,
  "points": [
    { "intervalStart": "2026-08-31", "intervalEnd": "2026-09-06", "totalExpense": 128.00, "transactionCount": 2 },
    { "intervalStart": "2026-09-07", "intervalEnd": "2026-09-13", "totalExpense": 61.00,  "transactionCount": 3 }
  ] }
```

**Four things about the shape, each pinned by a case below:**

- **All amounts carry exactly two decimals** — `120.00`, `63.49` — never `120` or `120.0`. Every amount
  is `DECIMAL(15,2)` in the schema and serialises with that scale.
- A field with no value is **absent from the JSON, not `null`**. `categoryIcon`/`categoryColor` are
  absent when a category has none; the four `totals` figures are absent when the month is empty
  (M8-01, M8-08).
- **The two category blocks are separate**, and every slice carries `type`. `expenseByCategory` holds
  `EXPENSE` slices only; `incomeByCategory` holds `INCOME` slices only (M8-03).
- **`total` is exact; `percentage` is rounded independently and need not sum to 100** (M8-06). This is
  the module's subtlest property and the one most likely to be misread.

### 3.4 The decision this module is built around — read this before §4

**Some of UC-15's sources can be asked about any month, and some cannot.** This is not a packaging
choice; it is a property of the schema, and it is why there are two endpoints.

| Source | Month it can answer about |
|---|---|
| `v_monthly_income_expense` (the totals) | **any** — keyed by `period_month` |
| `v_category_month_totals` (the pie) | **any** — keyed by `period_month` |
| `v_monthly_income_expense_6m` (the trend) | the last six months ending at **the current one** — no parameter |
| `v_daily_spending_current_month` (the daily bars) | **the current month only** — `CURDATE()` inside the database |
| `v_weekly_spending_current_month` (the weekly bars) | **the current month only** — `CURDATE()` inside the database |

So endpoint 36 can answer about August in December; endpoint 37 cannot be pointed at August at all.

**Endpoint 37 therefore refuses a month it cannot honour rather than quietly answering from the current
one.** A `GET /api/v1/reports/spending?month=<last month>` is `400 VALIDATION_ERROR` with a field error
naming `month` — not a silent substitution. The alternative would put one month's bars under another
month's heading with every number individually correct and no field revealing it, which is exactly the
failure a tester could not see. M8-15 and M8-16 test the refusals.

**Naming the current month explicitly is accepted** — `?month=<this month>` is the same request as
omitting it (M8-15 step 1), because refusing it would be a trap.

### 3.5 Why UC-16 has no endpoint — a decision, not an omission

BR-18 requires that an export file be produced **only when the user asks** and that **nothing be
buffered** — "the file is generated at call time". That is exactly what endpoints 36 and 37 already do:
they are computed on each call from the views, they buffer nothing, and they may be called as often as
a client likes with no stored file accumulating.

An `/api/v1/reports/export` route returning these same figures as CSV or PDF would therefore be **a
second way to ask one question** (§13). Its payload would be one of these two responses, formatted
differently, from the same views. M8-20 checks it does not exist.

**What the frontend already does is correct.** `ReportsComponent.exportReport()` calls
`window.print()`, which is BR-18 satisfied on the client: nothing buffered, generated when the button
is pressed, from the figures the screen already fetched. No backend call is involved and none is
needed. Do not report its absence as a missing feature.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M8-01 — A fresh student's report is valid, for the current month, and reports nothing

**Covers:** UC-15; the empty state; the absent-figures rule; the default month.

**Preconditions:** Signed in as owner B (`${USER_B_JWT}`), the seeded account with no transactions. (If
you have already used owner B for another test, register a fresh student with
`POST /api/v1/auth/register` and sign in as that one.)

**Steps:**

1. `GET /api/v1/reports` with `Authorization: Bearer ${USER_B_JWT}`.

**Expected result:**

- `200 OK` — **not `404`**, and not an error. A student with nothing to show has a valid, empty report.
- `periodMonth` equals the current month in `+07:00` (e.g. `"2026-09"`).
- `currency` is the account's own currency (a freshly registered student's is `"USD"` by default, or
  whatever `PATCH /api/v1/profile/me` set — it is **not** a request parameter).
- **`totals` is present but empty: `"totals": {}`, with no `income`, `expense`, `net` or
  `transactionCount` keys.** This is the module's most-misread field. It means "this month has no
  records", which is **not** the same as `"income": 0.00` — that would claim the records were examined
  and summed to nothing. Do not report the empty object as a missing field (M8-08, §5.1).
- `expenseByCategory: []` and `incomeByCategory: []` — present and empty, not absent.
- `sixMonthTrend` has **exactly six** entries, all `0.00`, all present — the *opposite* of `totals`
  (M8-11).

**Result:** [ ] Pass   [ ] Fail

---

### M8-02 — The demo account's report matches the seeded month

**Covers:** UC-15; the figures a reviewer will check by hand (UAT). **Read-only.**

**Preconditions:** A **freshly seeded** database (§2.1). Signed in as owner A (`${USER_A_JWT}`). Do not
run any write case before this one.

**Step 1: ask the database what the view holds.**

```bash
mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
  "SELECT period_month, total_income, total_expense, net_amount, txn_count
     FROM v_monthly_income_expense
    WHERE user_id = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu')
    ORDER BY period_month DESC LIMIT 3;"
```

**Step 2:** `GET /api/v1/reports` with `Authorization: Bearer ${USER_A_JWT}`.

**Expected result:**

- Step 1's newest row matches step 2's `totals` **exactly**: `income: 260.00`, `expense: 189.00`,
  `net: 71.00`, `transactionCount: 7`. The endpoint publishes the view's figures unaltered; if they
  differ, the endpoint is recomputing something it should not.
- `expenseByCategory` holds five slices, led by `Hostel/Rent` `120.00` at `63.49`, then
  `Entertainment` `25.00` `13.23`, `Food` `24.00` `12.70`, `Transport` `12.00` `6.35`,
  `Subscriptions` `8.00` `4.23`. The largest is first.
- `incomeByCategory` holds two slices: `Allowance` `200.00` `76.92`, `Part-time Job` `60.00` `23.08`.
- Each slice carries `categoryIcon` and `categoryColor` (e.g. `"home"` / `"#EF4444"` for Hostel/Rent).
  The view publishes neither, so their presence is what proves the join onto `categories` worked.
- `sixMonthTrend` has six entries ending at the current month, with `2026-04` and `2026-05` at `0.00`
  and June/July/August/September populated as §3.3 shows.
- **No `userId` key anywhere in the body.**

**Result:** [ ] Pass   [ ] Fail

---

### M8-03 — Income and expense are split by the category's type, not by a sign

**Covers:** UC-15; BR-05 (a category's type decides which block a transaction falls in); BR-09 (a
deleted record stops counting).

**Preconditions:** A student of your own — use owner B (`${USER_B_JWT}`) or register a fresh one — so
the demo account is not disturbed. You have an `EXPENSE` `categoryId` and an `INCOME` `categoryId`
from `GET /api/v1/categories`.

**Steps:**

1. `GET /api/v1/reports`; record `totals`, and note both blocks are empty.
2. `POST /api/v1/transactions` dated **today**, `amount: 100.00`, in an **`EXPENSE`** category, e.g.
   `Miscellaneous`:

   ```json
   { "categoryId": 12, "amount": 100.00, "description": "Books", "txnDate": "<today in +07:00>" }
   ```

3. `GET /api/v1/reports` again.
4. `POST /api/v1/transactions` dated **today**, `amount: 50.00`, in an **`INCOME`** category, e.g.
   `Gift`. Read the report again.
5. Soft-delete the step 2 transaction (`DELETE /api/v1/transactions/{id}`) and read again.

**Expected result:**

- Step 3: `totals.expense: 100.00`, `totals.income` is `0.00` (**present**, because the month now has
  records), `totals.net: -100.00` — **negative, and reported as such**. `expenseByCategory` has the
  `Miscellaneous` slice at `100.00` and `percentage: 100.00`; `incomeByCategory` is still `[]`.
- Step 4: `totals.income: 50.00`, `totals.expense` unchanged at `100.00`, `totals.net: -50.00`. The
  `Gift` slice appears in **`incomeByCategory`**, not in `expenseByCategory`. The two blocks never mix.
- Step 5: `totals.expense` is back to `0.00` but `totals` is **still present** (the income record
  remains), and the `Miscellaneous` slice is gone from `expenseByCategory`. A soft delete hides a
  record from every figure; it does not erase it.
- **`total` and `expense` are always positive.** A record's direction is its category's type, never a
  sign on the number — `ck_txn_amount` requires `amount > 0`. There is no negative amount anywhere in
  either response.

**Result:** [ ] Pass   [ ] Fail

---

### M8-04 — An earlier month can be reported on, and it is that month's figures

**Covers:** UC-15; the month is selectable on endpoint 36.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded, so the previous month's seeded figures
hold. The current month must be known (§2.3) — call it `CUR`; the previous month is `PREV`.

**Steps:**

1. `GET /api/v1/reports?month=${CUR}` and record `totals` and `periodMonth`.
2. `GET /api/v1/reports?month=${PREV}`.
3. Compare the two.

**Expected result:**

- Step 1: `periodMonth` is `CUR` and `totals` are the current month's (M8-02).
- Step 2: `periodMonth` is `PREV` and `totals` are the **previous month's** — the demo account's are
  `income: 290.00`, `expense: 207.00`, `net: 83.00`, `transactionCount: 8`. **They are not `CUR`'s
  figures under a `PREV` heading** — the whole point of a selectable month.
- Step 2's `expenseByCategory` leads with `Hostel/Rent` `120.00` at `57.97`, and has six slices
  including `Academics` `30.00` `14.49`, which the current month has no slice for.
- **Both responses carry the same `sixMonthTrend`** — the six months ending at the *current* one,
  whatever `month` says. That is BR-17 and is deliberate; §5.2 and M8-11.
- There is no way to make endpoint 36 return the *current* month's figures under an earlier heading,
  and no way to make it mix the two months within one response.

**Result:** [ ] Pass   [ ] Fail

---

### M8-05 — An empty month reports absence, not zeroes

**Covers:** UC-15; the absent-versus-zero asymmetry; `v_monthly_income_expense` has no row for an empty
month.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. Find a month the demo account has **no**
records in — the seeded history is three months, so a month a few months back with no data, e.g.
`2026-05`, will do. Confirm with step 1's query.

**Steps:**

1. Confirm the month is empty in the view:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT COUNT(*) AS rows_for_may FROM v_monthly_income_expense
       WHERE user_id = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu')
         AND period_month = '2026-05-01';"
   ```

2. `GET /api/v1/reports?month=2026-05`.

**Expected result:**

- Step 1 returns **`0`** — the view emits **no row** for a month with no records. That is the cause of
  the shape below, not a styling choice.
- Step 2: `200 OK`, `periodMonth: "2026-05"`, and **`"totals": {}`** — an empty object with no
  `income`, `expense`, `net` or `transactionCount` keys.
- `expenseByCategory: []` and `incomeByCategory: []`.
- `sixMonthTrend` still has six entries — but if `2026-05` is inside the trend window, **its point is
  `0.00`, present**, not absent. The same month is absent in `totals` and zero in the trend, because
  two different views answer two different questions (§5.1). This contrast in one response is the
  clearest way to see the asymmetry.
- A month the student has no records in is **not** a `404` and **not** an error — it is an honest,
  valid, empty report.

**Result:** [ ] Pass   [ ] Fail

---

### M8-06 — A future month is answered honestly, and a non-month is refused

**Covers:** UC-15; validation; the difference between "no data" and "not a month".

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:** Send each request and record the status and body.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/reports?month=<next month, yyyy-MM>` | `200`, `"totals": {}` — a future month is answered honestly, not refused |
| b | `GET /api/v1/reports?month=2026-13` | `400` `VALIDATION_ERROR`, field error on `month` |
| c | `GET /api/v1/reports?month=2026-9` | `400` — not zero-padded |
| d | `GET /api/v1/reports?month=September` | `400` |
| e | `GET /api/v1/reports?month=yesterday` | `400` |

**Expected result:**

- a: a future month is **valid input** — the view is keyed by `period_month` and simply has no row for
  it, so the response is an empty report with `"totals": {}`. Refusing it would be wrong; a student may
  reasonably look ahead.
- b–e: each returns `400` with `errorCode: "VALIDATION_ERROR"` and a `fieldErrors` array whose single
  entry has `"field": "month"` and a message beginning "Enter a real month in yyyy-MM form…". The
  refusal names the parameter that was wrong.
- **`2026-13` is not clamped to `2026-12`, and `2026-9` is not accepted as `2026-09`.** The parse is
  strict, so a malformed month can never be silently read as a different, valid one.

**Result:** [ ] Pass   [ ] Fail

---

### M8-07 — The six-month trend is always six, oldest first, and empty months are zero

**Covers:** BR-17, UAT-09; the trend's zero-fill.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. **Read-only.**

**Steps:**

1. `GET /api/v1/reports` and count `sixMonthTrend`.
2. List each point's `periodMonth` in order.
3. `GET /api/v1/reports` as owner B (`${USER_B_JWT}`) — a student with no records at all — and count
   its `sixMonthTrend`.

**Expected result:**

- Step 1: **exactly six** entries, never five and never seven.
- Step 2: the months ascend, oldest first, ending at the **current** month — e.g.
  `2026-04, 2026-05, 2026-06, 2026-07, 2026-08, 2026-09` when September is current.
- Every point has all four keys (`periodMonth`, `income`, `expense`, `net`). A month with no records —
  `2026-04` and `2026-05` in the seed — reads **`0.00`, present**, **not** absent. This is the
  **opposite** of `totals` on an empty month (M8-05) and is the schema's decision, not the module's:
  `v_monthly_income_expense_6m` joins `dim_month` so all six rows always exist.
- Step 3: owner B's trend is **six points, all `0.00`** — the six months exist for a student with no
  history at all, because BR-17 requires the window rather than the data.
- `net` equals `income − expense` on every point, including the `0.00` ones.

**Result:** [ ] Pass   [ ] Fail

---

### M8-08 — The two category blocks and each slice's share

**Covers:** UC-15; the share that is computed in Java; the presentation columns.

**Preconditions:** A student of your own, so the demo account is not disturbed. Record several
transactions in known amounts across a few expense categories and one income category, all dated
within the current month.

**Steps:**

1. Record, in the **current** month: `Food` `60.00`, `Transport` `30.00`, `Entertainment` `10.00` (all
   `EXPENSE`), and `Allowance` `100.00` (`INCOME`).
2. `GET /api/v1/reports`.
3. Add a **personal** category (`POST /api/v1/categories`) with an icon and colour, record `20.00` in
   it this month, and read the report again.

**Expected result:**

- Step 2: `expenseByCategory` has three slices, **largest first**: `Food` `60.00`, `Transport` `30.00`,
  `Entertainment` `10.00`. Each `percentage` is that slice's share of the expense block's own total
  (`100.00`): `60.00`, `30.00`, `10.00`. `incomeByCategory` has the `Allowance` slice at `100.00`
  `100.00` — the income block's total is its own, not the month's combined total.
- The two blocks' percentages are computed against **different** denominators — the expense block sums
  to 100 across expenses, the income block across income. This is what makes each share a proportion of
  the block it appears in (M8-09).
- Step 3: the personal category appears as a fourth expense slice with its own icon and colour, treated
  exactly like a shared default — a report does not distinguish the two.
- Each slice carries `categoryId`, `categoryName`, `type`, `total`, `percentage` and
  `transactionCount`.

**Result:** [ ] Pass   [ ] Fail

---

### M8-09 — The shares are each their own category's, and do NOT sum to 100

**Covers:** UC-15; **the module's most important subtlety** — independent rounding. This is the case
M8-08 cannot catch.

**Preconditions:** A student of your own. Record **three equal expenses** in the current month, of the
same amount, in three different expense categories — e.g. `Food` `10.00`, `Transport` `10.00`,
`Entertainment` `10.00`.

**Steps:**

1. `GET /api/v1/reports`.
2. Read each of the three slices' `percentage`, and add them.
3. Compare against the demo account's seeded month (`${USER_A_JWT}`, freshly seeded), whose six August
   expense slices have unequal totals.

**Expected result:**

- Step 2: **each share is `33.33`**, and the three sum to **`99.99`** — **not `100.00`**.
- **This is correct and must not be reported as a bug.** Each share is rounded on its own, to two
  decimals, with `HALF_UP`. `10.00/30.00 × 100 = 33.333…` rounds to `33.33`; `33.33 × 3 = 99.99`. The
  shortfall is bounded at under one unit of the last decimal place per slice.
- **No slice is adjusted to absorb the remainder.** An implementation that gave the difference to the
  largest slice so a pie chart closes would publish one category's share as a number that is not that
  category's share — the wrong figure in the one place it cannot be afforded. A report is where a
  student reconciles their records; a deliberately-wrong number here is worse than a total of `99.99`.
- Step 3: the demo account's August block shows the same effect with unequal totals —
  `57.97 + 14.49 + 10.63 + 7.73 + 5.31 + 3.86 = 99.99`.
- **`total` is exact** (`10.00`, `10.00`, `10.00`), and it is what a client drawing a whole pie should
  size its arcs from. A client that needs the block to read exactly 100 should derive it from `total`,
  not from `percentage`.
- Do not report "the percentages don't add up" as a defect. It is documented as a property in
  `docs/api/reports.md` §7 and on the DTO.

**Result:** [ ] Pass   [ ] Fail

---

### M8-10 — The trend is unaffected by the selected month

**Covers:** UC-15; BR-17's fixed window; the module's second most-misread behaviour.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. `CUR` and `PREV` as in M8-04.

**Steps:**

1. `GET /api/v1/reports?month=${CUR}` and record `sixMonthTrend` in full.
2. `GET /api/v1/reports?month=${PREV}` and record `sixMonthTrend`.
3. Compare the two arrays.

**Expected result:**

- The two `sixMonthTrend` arrays are **identical**, field for field. Selecting an earlier month changes
  `what totals and which category blocks are returned, and **nothing else**.
- Both still end at `CUR`, not at `PREV`. **This is deliberate:** BR-17 defines the trend as the last
  six months ending at the **current** one — a fixed window, not a parameter — so it is the same six
  points whatever `month` says.
- What keeps this honest is that **each point carries its own `periodMonth`**. A client draws the
  trend's x-axis from the points, never from the response's top-level `periodMonth`, which names the
  *selected* month. If a chart labelled the six bars with `periodMonth`, it would be mislabelling five
  of them — a **client bug, not a server bug**, and `docs/api/reports.md` §4 says so.
- **Do not report "the trend ignores my month selection" as a defect.** It is the requirement. A trend
  filtered to the selected month would be a single point, which is not a trend.

**Result:** [ ] Pass   [ ] Fail

---

### M8-11 — The spending series: the default window is the whole month, daily bars, quiet days absent

**Covers:** UC-15; the daily view; the published window.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded, so the seeded days are known. **Read-only.**

**Steps:**

1. `GET /api/v1/reports/spending` — no parameters.
2. Read `granularity`, `from`, `to` and the `points`.
3. Check that `points`' `totalExpense` sums to `totalExpense`.

**Expected result:**

- `granularity: "DAILY"` — the default when the parameter is omitted.
- `from` is the **first** day of the current month and `to` the **last** — `2026-09-01` … `2026-09-30`
  when September is current. Omitting both covers the whole month.
- `points` holds **only the days that have spending**: for the seed, `09-03`, `09-05`, `09-07`,
  `09-08`, `09-10`. **A day with no spending is a missing entry, not a `0.00` one.**
- **The window is published precisely because the days are absent.** A chart must draw its axis from
  `from` to `to`, not from `points.length` — a month whose early days were quiet returns no point for
  them, and the axis must still begin at the 1st. A client inferring the range from the array's length
  would mislabel the axis; that is a client bug the published window exists to prevent.
- Each point has `intervalStart` and `intervalEnd` **equal** for DAILY — a day is one day.
- Summing the points' `totalExpense` gives the response's `totalExpense` (`189.00` for the seed).
- **Income is never in the series.** It is a *spending* breakdown; an `INCOME` category's records are
  ignored by the view (M8-12 step 3).

**Result:** [ ] Pass   [ ] Fail

---

### M8-12 — Weekly bars are real ISO weeks, and a week may reach outside the month

**Covers:** UC-15; VĐ-10 (a month boundary never splits an ISO week); the weekly view.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. **Read-only.**

**Steps:**

1. `GET /api/v1/reports/spending?granularity=WEEKLY`.
2. Read each point's `intervalStart` / `intervalEnd`.
3. Record an `INCOME` of `500.00` dated today, and read the series again.
4. Delete it again.

**Expected result:**

- Step 2: each `intervalStart` is a **Monday** and each `intervalEnd` the following **Sunday** — a real
  ISO week, exactly seven days apart. For the seed's September: `2026-08-31`→`2026-09-06` and
  `2026-09-07`→`2026-09-13`.
- **The first bar's `intervalStart` is `2026-08-31` — a day of the *previous* month — and that is
  correct.** The view groups by ISO week, so a month boundary never splits a week; the bar's *dates*
  legitimately reach past the month's first day.
- **What the bar *counts*, however, is only the current month's records.** Both current-month views
  filter `txn_date` to the month *before* grouping and group the identical record set — only the
  grouping differs. So a bar's dates can be wider than the month while its amount cannot include a day
  of spending from outside it. **Do not clip the bars to `from`/`to`** — they are not a pair of dates
  the bars fit inside.
- Step 3: `totalExpense` is **unchanged** — the `500.00` was income. The series is spending only.
- The two granularities returned the **same** `totalExpense` (`189.00`). They must, over a whole month
  (M8-13).

**Result:** [ ] Pass   [ ] Fail

---

### M8-13 — Over the whole month the two granularities agree; over a narrowed window they need not

**Covers:** UC-15; the overlap predicate; **the module's third subtlety**.

**Preconditions:** A student of your own, so the change is unambiguous. Record three expenses in the
current month in **the same ISO week**, on non-adjacent days — e.g. `Food` `10.00` on the **Monday**
of this week, `Transport` `20.00` on the **Wednesday**, `Entertainment` `30.00` on the **Friday**.
(Both the Monday and the Friday must still be within the current month; if the week straddles the
month boundary, use a week wholly inside it.)

**Steps:**

1. `GET /api/v1/reports/spending?granularity=DAILY` — no window. Record `totalExpense`.
2. `GET /api/v1/reports/spending?granularity=WEEKLY` — no window. Record `totalExpense`. Compare.
3. `GET /api/v1/reports/spending?granularity=DAILY&from=<the Wednesday>&to=<the Wednesday>`. Record
   `totalExpense`.
4. `GET /api/v1/reports/spending?granularity=WEEKLY&from=<the Wednesday>&to=<the Wednesday>`. Record
   `totalExpense`. Compare.

**Expected result:**

- Steps 1–2: the two are **equal** (`60.00`). Over the whole month the two views group the same
  month-filtered records, so they can only agree. **A weekly total that exceeds the month's expense
  would be a bug** — the views filter `txn_date` to the month before grouping, so no bar can carry
  spending from outside it.
- Step 3: DAILY returns only the Wednesday's `20.00` — a point is a day, so days outside the window are
  dropped.
- Step 4: WEEKLY returns the **whole ISO week's `60.00`**, because the predicate is an **overlap**: a
  bar that merely **touches** the window is returned whole, with all of its own week's spending. So the
  weekly total is **larger than the daily one over the same narrow window**.
- **This is correct and deliberate.** The predicate is `week_start <= to AND week_end >= from`, not a
  containment test, because a containment test would drop a bar whose Monday falls in the previous
  month even though the bar carries spending from the requested window. Both figures are right for
  their unit — a day or a week.
- **Do not report "the weekly total exceeds the daily one" as a bug** over a narrowed window. A client
  that needs the two to agree must ask for the whole month, which is their default. Documented in
  `docs/api/reports.md` §5.3.

**Result:** [ ] Pass   [ ] Fail

---

### M8-14 — The series refuses what it cannot honour, rather than answering from the wrong month

**Covers:** UC-15; **the module's central decision** (§3.4); the one report that cannot be asked about
another month.

**Preconditions:** Owner A (`${USER_A_JWT}`). `CUR` and `PREV` as in M8-04.

**Steps:** Send each request and record the status and body.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/reports/spending?month=${CUR}` | `200` — naming the current month explicitly is accepted |
| b | `GET /api/v1/reports/spending?month=${PREV}` | `400` `VALIDATION_ERROR`, field error on `month` |
| c | `GET /api/v1/reports/spending?month=<next month>` | `400`, field error on `month` |
| d | `GET /api/v1/reports/spending?from=<a day of PREV>` | `400`, field error on `from` |
| e | `GET /api/v1/reports/spending?to=<a day of the next month>` | `400`, field error on `to` |

**Expected result:**

- a: `200`, and the response is **identical** to omitting `month` — the current month named is the same
  request as the current month implied. Refusing it would be a trap.
- b–e: each is `400` with `errorCode: "VALIDATION_ERROR"` and a `fieldErrors` entry naming the
  offending parameter (`month`, `from` or `to`).
- **The response is a refusal, not a substitution.** b does **not** return the current month's bars
  under `PREV`'s name: that would be a response whose every number was individually correct and whose
  heading was a lie, with no field revealing it. The `400` is the honest answer, and the message says
  why and what to do instead ("Omit the month to use the current one, or ask for a whole month's totals
  instead").
- The refusal **names the parameter**, so a client can correct the request rather than re-read the
  documentation.
- There is no way to obtain a previous month's daily or weekly breakdown through this endpoint. That is
  the schema's constraint (`CURDATE()` in the view), accommodated rather than worked around.

**Result:** [ ] Pass   [ ] Fail

---

### M8-15 — The series refuses a bad granularity, an inverted window and a malformed edge

**Covers:** UC-15; input validation on endpoint 37.

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:** Send each request and record the status, `errorCode` and the `field` named.

| # | Request | Expected field |
|---|---|---|
| a | `GET /api/v1/reports/spending?granularity=MONTHLY` | `granularity` |
| b | `GET /api/v1/reports/spending?granularity=hourly` | `granularity` |
| c | `GET /api/v1/reports/spending?from=<20th>&to=<8th>` (both this month) | `from` |
| d | `GET /api/v1/reports/spending?from=2026-9-1` | `from` |
| e | `GET /api/v1/reports/spending?to=01/09/2026` | `to` |

**Expected result:**

- All five are `400` `VALIDATION_ERROR`, each with a `fieldErrors` entry naming the parameter in the
  last column.
- a–b: `MONTHLY` is refused because the schema computes only two breakdowns; a third would be a
  grouping no view produces. The message is "Choose DAILY or WEEKLY."
- c: the inverted window — `from` later than `to` — names `from`, with "The start of the window must
  not be later than its end."
- d–e: a malformed edge is **refused, not coerced**. `2026-9-1` is not accepted as `2026-09-01`, and
  `01/09/2026` is not read as a date at all. Each names the edge that was wrong.
- **A window edge is completed rather than required**: `?from=<a day>` alone means "from then to the
  end of the month", and `?to=<a day>` alone means "the 1st to then". Omitting both means the whole
  month.

**Result:** [ ] Pass   [ ] Fail

---

### M8-16 — Each report belongs to the caller, and no parameter can name another student

**Covers:** §15; BR-02 (ownership).

**Preconditions:** Owner A (`${USER_A_JWT}`) and owner B (`${USER_B_JWT}`), both freshly seeded.

**Steps:**

1. `GET /api/v1/reports` with `${USER_A_JWT}`; record `totals`.
2. `GET /api/v1/reports` with `${USER_B_JWT}`; record `totals`.
3. `GET /api/v1/reports?userId=<owner A's id>` with `${USER_B_JWT}`.
4. `GET /api/v1/reports/spending?userId=<owner A's id>` with `${USER_B_JWT}`.

**Expected result:**

- Step 1: owner A's figures (M8-02). Step 2: owner B's — empty, or whatever owner B's own testing
  produced. **Never owner A's `260.00` / `189.00`.**
- Steps 3–4: `200`, with **owner B's own** figures. `userId` is **ignored** — it is not a parameter
  either endpoint reads, so it cannot be wrong. A `200` returning owner A's data here would be a
  **critical** finding; report it above every other result in this procedure.
- **Ownership is structural, not checked.** No method at any layer takes a user identifier, so there is
  no way to *ask* for another student's report — the caller's id comes from the verified token and is
  bound into every query. There is therefore nothing to tamper with, which is stronger than a
  post-read check.
- Neither response contains a row belonging to another student: no category slice, no trend point, no
  series bar from a different account.

**Result:** [ ] Pass   [ ] Fail

---

### M8-17 — Both endpoints require a student's token

**Covers:** §7.5; the role rule; the token requirement.

**Preconditions:** All four tokens available.

**Steps:** Send each request and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/reports` — no `Authorization` header | `401` `UNAUTHENTICATED` |
| b | `GET /api/v1/reports` — `Authorization: Bearer not.a.token` | `401` |
| c | `GET /api/v1/reports` — `${ADMIN_JWT}` | `403` `ACCESS_DENIED` |
| d | `GET /api/v1/reports/spending` — `${ADMIN_JWT}` | `403` |
| e | `GET /api/v1/reports/spending` — no header | `401` |
| f | `GET /api/v1/reports` — `${JWT}` | `200` |

**Expected result:**

- a, b, e: `401` with `errorCode: "UNAUTHENTICATED"`. A malformed token is not a `400`, and **no stack
  trace or token text** is returned or logged.
- c, d: `403` with `errorCode: "ACCESS_DENIED"`. An administrator is **not** a student; the rule is on
  `/api/v1/reports/**` and Spring Security enforces it *before* the controller. **An administrator has
  no report of their own** — which is the point: a report names one student's income and
  category-by-category habits, and UC-21's administrative reports under `/api/v1/admin/**` are sums
  across many students, built not to name an individual.
- f: `200`, the caller's own report.

**Result:** [ ] Pass   [ ] Fail

---

### M8-18 — No response names the owner, and carries nothing else it should not

**Covers:** UC-15; BR-02; §15 (no sensitive or internal field in a response).

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:**

1. Capture the raw body of both endpoints:

   ```bash
   curl -s http://localhost:8080/api/v1/reports \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_report.json
   curl -s "http://localhost:8080/api/v1/reports/spending" \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_spending.json
   ```

2. List the keys each body actually contains, at every level:

   ```bash
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_report.json | sort -u
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_spending.json | sort -u
   ```

**Expected result:**

- None of these appears in either body, at any level:

  | Must not appear | Why |
  |---|---|
  | `userId`, `user_id`, `user` | The response is the caller's by construction. A `userId` is one more place an identity could leak into a log or a proxy cache |
  | `email`, `fullName`, `full_name` | The account's name is not part of a report. The screen already knows who is signed in |
  | `passwordHash`, `password_hash`, `tokenVersion`, `refreshToken` | Never present in any response; checked globally by `OpenApiContractIT` |

- The report body's keys are exactly: `periodMonth`, `currency`, `income`, `expense`, `net`,
  `transactionCount`, `categoryId`, `categoryName`, `categoryIcon`, `categoryColor`, `type`, `total`,
  `percentage`. Anything else is a finding.
- The spending body's keys are exactly: `granularity`, `from`, `to`, `currency`, `totalExpense`,
  `intervalStart`, `intervalEnd`, `totalExpense` (inside points), `transactionCount`.
- **`categoryName` is the category's, not the owner's** — the two are different things and only the
  category's is published.
- No nested user object anywhere in either body.

**Result:** [ ] Pass   [ ] Fail

---

### M8-19 — There is no export route and no other reports route

**Covers:** §13 (no duplicate capabilities); BR-18; the endpoint inventory.

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:** Send each request below as owner A and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/reports/export` | `404` |
| b | `GET /api/v1/reports/csv` | `404` |
| c | `GET /api/v1/reports/download` | `404` |
| d | `GET /api/v1/reports/summary` | `404` |
| e | `GET /api/v1/reports/monthly` | `404` |
| f | `GET /api/v1/reports/categories` | `404` |
| g | `GET /api/v1/reports/1` | `404` |
| h | `GET /api/v1/profile/me/reports` | `404` |
| i | `POST`, `PUT`, `PATCH`, `DELETE /api/v1/reports` | `400` `INVALID_REQUEST` — the method is not supported |
| j | `GET /api/v1/reports?userId=2&bogus=1` | `200`, the **caller's** own report (M8-16) |

**Expected result:**

- a–h: `404`. **There is no export route, and that is deliberate** (§3.5): BR-18's "generated at call
  time, nothing buffered" is what endpoints 36 and 37 already do, so a CSV route would be a second way
  to ask one question. The absence is asserted by the contract test, so a future developer adding one
  fails the build with the reason attached.
- i: `400` with `errorCode: "INVALID_REQUEST"` and message "The HTTP method is not supported by this
  endpoint." This is the application-wide behaviour for an unsupported method (every module answers
  the same way), not a `405`. **Nothing is created, changed or deleted.** It carries no data.
- j: `200` with the caller's report — both unknown parameters are ignored, and the body is **byte-for-byte
  identical** to the same request without them. An ignored parameter is the correct outcome; a `200`
  returning another student's data would be a critical finding.
- The OpenAPI document at `/api-docs` lists **exactly two** reports operations. This is machine-checked
  by `OpenApiContractIT` (24 distinct paths, 37 operations overall as of module 8; the count grows as
  later modules add routes), and the path count is asserted deliberately — adding a route without
  adding it to `docs/api/API_INVENTORY.md` fails that test on purpose.

**Result:** [ ] Pass   [ ] Fail

---

### M8-20 — Reading a report changes nothing

**Covers:** UC-15; both endpoints are reads and are idempotent. The module's answer to "no concurrency
test".

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded.

**Steps:**

1. `GET /api/v1/transactions` as owner A; record the count.
2. `GET /api/v1/notifications` as owner A; record the count.
3. `GET /api/v1/reports` three times in a row, then `GET /api/v1/reports/spending` three times.
4. `GET /api/v1/transactions` and `GET /api/v1/notifications` again; compare the counts.

**Expected result:**

- Step 3: the three report responses are **byte-for-byte identical**, and so are the three series
  responses. The same month, the same figures, the same slice order, the same day order.
- Step 4: **both counts are unchanged.** A report render does not record that it was viewed, does not
  create a notification, does not cache its figures and does not touch a tip. There is no view-tracking
  table, no view counter and no cache to warm — every figure is computed per read from the views.
- Both methods are `@Transactional(readOnly = true)`, and there is no `POST`, `PUT`, `PATCH` or
  `DELETE` on either path.
- **This case is the module's substitute for a concurrency test.** Both endpoints are reads with no
  lock, no write and no pagination, so there is nothing to race; idempotency is the property that does
  apply, and it is what this case checks.
- If two responses differ, the cause is the data changing underneath (another tester writing to the
  same account between calls), not the endpoint being non-deterministic. Confirm by checking whether a
  write happened between the calls.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Four behaviours a tester is most likely to misread

All four are deliberate and documented. Read this section before marking anything in §4 as a failure.
None is a defect in the module.

> ### 5.1 `"totals": {}` is not a missing field — and the trend's `0.00` is not a contradiction
>
> A month with no records returns **`"totals": {}`** — an object with no `income`, `expense`, `net` or
> `transactionCount` — while the *same* empty month, if it falls inside the trend window, appears in
> `sixMonthTrend` as a point with **`0.00` present**.
>
> **Both are correct, and they are two different statements.** `v_monthly_income_expense` emits one row
> per month that has data, so an empty month has **no row** — and a substituted `0.00` would claim the
> student's records were examined and summed to nothing, where the truth is that there were no records.
> `v_monthly_income_expense_6m` instead joins `dim_month` and left-joins the totals, so all six rows
> always exist and an empty month **is** zero: BR-17 requires all six months.
>
> **The schema decides which report fills its gaps, and this module passes through what each view
> returned.** It does not zero-fill one and not the other by accident. **Do not report the empty
> `totals` as a bug, and do not report the disagreement between the two as one** — M8-05 shows both in
> a single response. A student reconciling records against memory is exactly the reader for whom
> "recorded nothing" and "recorded activity that netted to nothing" must not be conflated.

> ### 5.2 The trend ignores the month you selected
>
> `GET /api/v1/reports?month=<earlier month>` returns that month's totals and category blocks, and
> **the six months ending at the *current* month** — the same array as the current month's report.
>
> **This is BR-17, not an oversight.** The trend is defined as the last six months ending at the current
> one: a fixed window, not a parameter. Filtering it to the selected month would leave one point, which
> is not a trend. Each point carries **its own `periodMonth`**, which is what keeps it honest — a
> client labels the trend from the points, never from the response's top-level `periodMonth` (M8-10).
>
> **Do not report "the trend ignores my selection" as a defect.** If a chart mislabels the six bars,
> that is the client reading `periodMonth` where it should read the points.

> ### 5.3 The percentages do not add up to 100
>
> Three equal expenses each return `percentage: 33.33`, and the three sum to **`99.99`** (M8-09).
>
> **This is correct.** Each share is rounded **on its own** to two decimals, because a slice's
> percentage is a property of that slice. `33.333…` rounds to `33.33`, and `33.33 × 3 = 99.99`. The
> alternative — give the difference to the largest slice so a pie closes — would publish one category's
> share as a number that is **not** that category's share, which is the one thing a report cannot
> afford. The shortfall is bounded at under one unit of the last decimal place per slice.
>
> **`total` is exact and is what a whole pie should be drawn from.** A client that needs the block to
> read exactly 100 derives it from `total`, not from `percentage`. **Do not report the shortfall as a
> bug** (documented in `docs/api/reports.md` §7).

> ### 5.4 Over a narrowed window the weekly total can exceed the daily one — but not over a whole month
>
> **Over the whole month the two granularities always agree** (`189.00` for the seed). Both views filter
> `txn_date` to the month *before* grouping and group the identical record set — only the grouping
> differs, so no weekly bar can carry spending from outside the month. **A weekly total exceeding the
> month's expense would be a bug.**
>
> **Over a narrowed window they can differ.** A weekly bar whose dates merely *touch* the window is
> returned **whole**, with all of its own week's spending (M8-13: DAILY `20.00` against WEEKLY `60.00`
> for the same single-day window). The predicate is `week_start <= to AND week_end >= from` — an
> overlap, not a containment — because a containment test would drop a bar whose Monday is in the
> previous month even though it carries spending from the requested window.
>
> **Both figures are right for their unit.** A client that needs the two to agree must ask for the whole
> month, which is their default when `from`/`to` are omitted. **Do not report the difference as a bug**
> over a narrowed window (documented in `docs/api/reports.md` §5.3).

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M8-01 | UC-15 empty state; the empty `totals` object; the default month; the empty arrays; six trend points |
| M8-02 | UC-15; the seeded month's totals, category blocks, icons/colours and trend |
| M8-03 | UC-15; BR-05 (the category's type splits the two blocks); BR-09 (a deleted record stops counting); `net` may be negative; amounts are positive |
| M8-04 | UC-15; any month can be reported on, including an earlier one; the trend is not filtered by it |
| M8-05 | UC-15; absent versus zero — `totals: {}` versus a `0.00` trend point, caused by the two views |
| M8-06 | UC-15; a future month is answered honestly; a malformed or non-month is refused with a field error |
| M8-07 | BR-17, UAT-09; the trend is always six, oldest first, empty months as `0.00` |
| M8-08 | UC-15; the two blocks and their separate denominators; the share computed in Java; the icon/colour join; a personal category |
| M8-09 | §7; the shares are each their own and do not sum to 100 (independent rounding) |
| M8-10 | BR-17; the trend is unaffected by the selected month (§5.2) |
| M8-11 | UC-15; the default window is the whole month; quiet days absent; the window published for the axis |
| M8-12 | UC-15, VĐ-10; weekly bars are real ISO weeks; a week's dates may reach outside the month; income ignored |
| M8-13 | §5.3; the two granularities agree over a month and a narrowed window returns a bar whole |
| M8-14 | §4.1; the series refuses a month or window it cannot honour rather than answering from the wrong month |
| M8-15 | UC-15; the granularity, inverted-window and malformed-edge refusals; the window is completed, not required |
| M8-16 | §15; BR-02 (ownership, structurally); an unknown `userId` is ignored |
| M8-17 | §7.5 (a token, role `STUDENT`; admin `403`) |
| M8-18 | UC-15; BR-02 (no response names the owner); §15 (no internal or sensitive field published) |
| M8-19 | §13 (no duplicate capability); BR-18; the endpoint inventory; unsupported methods |
| M8-20 | UC-15; both reads write nothing and are idempotent — the module's answer to concurrency |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass.

| Not covered | Why |
|---|---|
| A server-generated export file (CSV/PDF) | BR-18 needs no route and there is none (§3.5). The frontend's `window.print()` satisfies it on the client, and no backend call is involved |
| Producing a real previous month's data from the seed | The demo account's seeded months are fixed. M8-04 uses them; a case wanting an arbitrary earlier month must date a transaction in the past, which is permitted (BR-08) |
| A daily or weekly breakdown of a month other than the current one | The endpoint refuses it (§3.4). It is the schema's constraint, not a missing feature |
| The `v_monthly_income_expense_6m` view's tie-break or ordering internals | The DAO restates `ORDER BY period_month ASC`; the view's own ordering is not relied upon and is not what this procedure tests |
| Concurrency | Both endpoints are reads with no lock, no write and no pagination, so there is nothing to race. M8-20 pins idempotency instead, which is the property that does apply |
| A disabled account's token, a revoked session, an expired token | Disabling is module 11's action and revocation needs module 1's sign-out. M8-17 covers the missing, malformed and wrong-role cases |
| Budget progress, notifications, transaction lists | Deliberately not on this screen: `GET /api/v1/budgets`, `/notifications` and `/transactions` own them. Covered by their own modules' procedures |
| A `500`-level failure path | Not reachable through the API. There is no input to make fail and no fault injection |
| Sharing or downloading a report | Not a requirement; UC-16's export is satisfied by the two reads and the client's print (§3.5) |
| A client-side rendering of a nullable or rounded field | The Angular notes are documented (`docs/api/reports.md` §10, including the ten mock-versus-contract divergences); no Angular source is changed by this module, and all its services are still mock-only |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/reports.md`,
`docs/modules/MODULE_08_REPORTS.md`, `docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md`,
`db/02_views.sql`, `db/06_demo.sql` or the implementation under
`backend/src/main/java/com/campuscoin/reports/`.
