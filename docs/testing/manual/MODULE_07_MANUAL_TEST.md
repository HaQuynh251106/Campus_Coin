# Module 07 — Dashboard (UC-12) — Manual Test Procedure

**Scope:** endpoint 35 — `GET /api/v1/dashboard`, role `STUDENT` only.

**Sources of truth for this procedure:** `docs/api/dashboard.md` (the UC-12 contract, §4 "the month
is the database's", §5 "the two filters the schema leaves to the application", §6 the verified
example response, §8 "what is not on the screen and where it lives"), `docs/modules/MODULE_07_DASHBOARD.md`
(§5.1 the tips view's missing time filter, §5.2 the announcement view's missing audience filter),
`docs/api/API_INVENTORY.md` (endpoint 35), `docs/CREDENTIALS.md` (seeded accounts), `db/02_views.sql`
and `db/06_demo.sql` (what the seeded figures are), and the implementation under
`backend/src/main/java/com/campuscoin/dashboard/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 35 | `GET` | `/api/v1/dashboard` | `200` dashboard object |

**One endpoint, no parameters.** There is **no** `?month=`, **no** `?userId=`, **no**
`/dashboard/{id}`, and **no** per-block route such as `/dashboard/summary`, `/dashboard/top-category`,
`/dashboard/tips` or `/dashboard/announcements`. There is no `POST`, `PUT`, `PATCH` or `DELETE` on this
path — a dashboard render writes nothing. An endpoint that is not in the table above does not exist;
if a step here asks you to call one, the document is wrong, not the server.

The response carries four blocks in one object: the month's totals with the saving-goal progress, the
top spending category, the saving tips to show, and the live announcements.

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema, seed data and demo data applied (`db/01_schema.sql` …
  `db/06_demo.sql`).
- **For M7-01 and M7-09 the database must be in its freshly seeded state.** Those two cases assert
  exact figures from `db/06_demo.sql`. The dashboard is a read, so this procedure changes nothing — but
  if you have already run module 4's or module 6's manual tests against the same database, the demo
  student has extra transactions and the seeded figures no longer hold. Re-seed before M7-01, or read
  those two cases as "the shape is right and the numbers are whatever the seed plus your earlier
  testing produced".

Every step below can be done either in Swagger UI (the endpoint takes no body and no parameters) or
with `curl`.

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
| `${USER_A_JWT}` | The `STUDENT` token of **owner A**, the seeded demo student whose figures M7-01 asserts. Same account as `${JWT}`; the ownership cases name it explicitly so the two sides of the case are unambiguous. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of **owner B**, a different student. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` access token, obtained from the administrator sign-in endpoint. | System Administrator |

There are no other placeholders in this document. Where a numeric id appears (a `categoryId`, a
`tipId`, an `announcementId`), it is an **example**; always use the id you recorded in your own run.

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

**Several cases below need a row that no endpoint can create yet.** Pinning or dismissing a tip is
UC-18 (module 9) and raising an announcement is UC-21 (module 11); neither module is built. Those
cases therefore say so explicitly and give the `mysql` statement to run against the running database,
because a hand tester has no HTTP route to the change. Every such step also gives the statement that
undoes it. This is a limitation of the test procedure, not of the module.

### 2.3 Dates and the month

The dashboard reports **one month** and gives you no way to choose it. That month is whatever the
**database session** thinks the current month is, and the database session is pinned to
`+07:00`. So:

- "This month" means the current month in **`Asia/Ho_Chi_Minh` (`+07:00`)** — for example
  `2026-09`. Check it with `TZ=Asia/Ho_Chi_Minh date +%Y-%m`.
- The response's `periodMonth` is a **month** rendered `"YYYY-MM"`, with no day component. It is the
  1st of that month's row in the database, shown without the day.
- The month cannot be requested, so a test cannot ask for another month's dashboard. To see a
  different month you would have to change the server's clock or the database's, which this procedure
  does not ask you to do.
- **This is not a timezone guarantee.** What is guaranteed is narrower and is what the cases check:
  one request never mixes two months.

A **transaction date** used by the fixture steps in this document is a plain calendar date
`"YYYY-MM-DD"` with no time and no zone. **BR-08 refuses a future-dated transaction**, so use `date +%F`
for today or an earlier date.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with three months of demo data (`db/06_demo.sql`). For the **current**
  month it holds: income `200.00` + `60.00`, expenses Hostel/Rent `120.00`, Entertainment `25.00`,
  Food `24.00`, Transport `12.00`, Subscriptions `8.00` (total `189.00`), so net `71.00`; an allowance
  baseline of `200.00` and a saving goal of `100.00`; five current-month budgets. Its tips were
  generated for three months by three `CALL sp_generate_tips` statements in the same script.
- **Bella Tran** is the seeded **empty** account: no transactions, no budgets, no tips. She is the
  clean subject for the empty-state and ownership cases.
- Shared default categories (`Allowance`, `Food`, `Transport`, `Hostel/Rent`, `Academics`,
  `Subscriptions`, `Entertainment`, `Miscellaneous`, `Part-time Job`, `Scholarship`, `Gift`,
  `Other Income`) are visible to every student. `Allowance`, `Part-time Job`, `Scholarship`, `Gift` and
  `Other Income` are `INCOME`; the rest are `EXPENSE`. Use `GET /api/v1/categories` to read the real
  ids and types.
- Two announcements are seeded, both `STUDENTS` and both currently live (`Welcome to Campus Coin`,
  `Import your past spending from a CSV file`). A third, `ADMINS`-targeted notice is **not** seeded —
  M7-12 has you create one.

### 3.2 Authentication

The endpoint requires `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403`. A missing, malformed, expired or revoked
token answers `401`. There is no anonymous or shared view of a dashboard.

### 3.3 What a dashboard response looks like

```json
{
  "periodMonth": "2026-09",
  "summary": {
    "totalIncome": 260.00,
    "totalExpense": 189.00,
    "netAmount": 71.00,
    "monthlyAllowanceBaseline": 200.00,
    "monthlySavingsGoal": 100.00,
    "savingsGoalPct": 71.00
  },
  "topCategory": {
    "categoryId": 8,
    "categoryName": "Hostel/Rent",
    "totalAmount": 120.00,
    "categoryIcon": "home",
    "categoryColor": "#EF4444"
  },
  "tips": [
    { "id": 7, "title": "Your savings goal is at risk", "body": "...", "potentialSaving": 29.00, "state": "NEW", "displayOrder": 1 },
    { "id": 8, "categoryId": 11, "title": "Entertainment spending is up 127.3%", "body": "...", "potentialSaving": 14.00, "state": "NEW", "displayOrder": 2 },
    { "id": 9, "categoryId": 6, "title": "Food has used 80.0% of its budget", "body": "...", "potentialSaving": 3.00, "state": "NEW", "displayOrder": 3 }
  ],
  "announcements": [
    { "id": 2, "title": "Import your past spending from a CSV file", "body": "...", "severity": "SUCCESS", "startsAt": "2026-09-25T00:00:00", "endsAt": "2026-12-24T00:00:00" }
  ]
}
```

Four things about the shape, each pinned by a case below:

- A field with no value is **absent from the JSON, not `null`**. `savingsGoalPct` is absent when the
  student has no saving goal; `topCategory` is absent when the month has no expense; `categoryId`,
  `categoryIcon`, `categoryColor` and `endsAt` are each absent when they have no value (M7-05, M7-07,
  M7-10, M7-13).
- **There is no `userId`, and no nested `user` object.** The response is the caller's by construction,
  never by a field (M7-16).
- Money is a number with exactly two decimals (`120.00`). `savingsGoalPct`, `totalAmount` and
  `potentialSaving` are numbers; `displayOrder` is an integer; `periodMonth` is a string.
- `state` is one of `NEW`, `PINNED`, `DISMISSED`. Only `NEW` and `PINNED` are ever returned — a
  dismissed tip is filtered out by the view, not moved to the end (M7-11).

**Nothing about the payload is computed in Java beyond two filters and one join.** The totals, the
net figure, the goal percentage, the top category, the tip ranking and the announcement window are all
the database's (see §5 of the module report). If a figure looks wrong, the thing to check is the view's
output, not the service.

### 3.4 What decides which month, and which tips, and which announcements

Read this before §4; three of its points are cases a tester most often reports as bugs.

1. **The month comes from the summary row, not from a clock in Java.** Every field is scoped to the
   month the database reported, so a single response cannot mix two months (§5 of the module report).
2. **The tips table holds every month's tips; the screen shows one month's.** `v_dashboard_tips` has
   **no time filter** — its `PARTITION BY` exists only to restart the ranking per month. The
   application adds `period_month = <this month>`. For the demo account the view returns **nine** tips
   (three months × three) and the endpoint returns **three**. **This is the single most likely thing to
   look like a bug and is not one** (§5.1).
3. **The announcement view filters by time, not by reader.** `v_active_announcements` answers "is this
   notice within its window", not "is it for this reader". The application adds the audience filter
   `IN ('ALL', 'STUDENTS')`. Without it an administrator-targeted notice would appear on every
   student's dashboard — a disclosure, not a styling bug (§5.2).

### 3.5 Settings that bound the dashboard

| Setting | Seeded value | Meaning |
|---|---|---|
| `tips.max_dashboard` | `3` | The most tips the dashboard may carry. Read by `sp_generate_tips` when tips are *generated*; the dashboard reports what exists |

It is a row in `system_settings`, not a constant in the code. Changing it is an administrator action
(module 11); this endpoint accepts no parameter and no endpoint here changes it. Note that it bounds
**generation**: reducing it does not delete tips that already exist, so M7-09 asserts the endpoint
returns no more than the setting - and any tips the generator already wrote.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M7-01 — The demo account's dashboard reports the seeded month

**Covers:** UC-12 B1, B2, B3 together; the figures a reviewer will check by hand (UAT). **Read-only.**

**Preconditions:** A **freshly seeded** database (§2.1). Signed in as owner A (`${USER_A_JWT}`), whose
seeded data §3.1 describes. Do not run any write case before this one.

**Steps:**

1. `GET /api/v1/dashboard` with `Authorization: Bearer ${USER_A_JWT}`.

**Expected result:**

- `200 OK`, and `periodMonth` equals the current month in `+07:00` (e.g. `"2026-09"`).
- `summary.totalIncome: 260.00`, `summary.totalExpense: 189.00`, `summary.netAmount: 71.00`.
  (`189.00 = 120.00 + 25.00 + 24.00 + 12.00 + 8.00`. If a later module's manual test has already run
  against this database, the figure is that seed **plus** what you recorded — §2.1.)
- `summary.monthlyAllowanceBaseline: 200.00`, `summary.monthlySavingsGoal: 100.00`,
  `summary.savingsGoalPct: 71.00`. The percentage is `net / goal × 100` — `71.00 / 100.00` — and it is
  the **net** figure, not the income, so `71.00` is right and `260` would be wrong.
- `topCategory.categoryName: "Hostel/Rent"`, `totalAmount: 120.00`, `categoryIcon: "home"`,
  `categoryColor: "#EF4444"`. `Hostel/Rent` at `120.00` beats `Entertainment` at `25.00`, so the block
  is not simply the first expense category.
- `tips` has **three** entries. `tips[0].title` is `"Your savings goal is at risk"` and
  `tips[0]` has **no `categoryId` key**; `tips[1].title` is `"Entertainment spending is up 127.3%"`
  with `categoryId: 11`; `tips[2].title` is `"Food has used 80.0% of its budget"` with
  `categoryId: 6`. All three read `state: "NEW"`, with `displayOrder` `1`, `2`, `3`.
- `tips[2].title` reads **`80.0%`**, with one decimal — the procedure renders the percentage through
  `CAST(ROUND(pct, 1) AS CHAR)`. Expect `80.0%`, **not** `80%`. This is not a formatting bug.
- `announcements` is non-empty and contains `"Import your past spending from a CSV file"`. **No entry
  has `severity: "ADMINS"` or any audience value** — `audience` is not a published field at all
  (M7-16).
- **No `userId` key anywhere in the body**, and no nested `user` object.

**Result:** [ ] Pass   [ ] Fail

---

### M7-02 — A new student's dashboard is empty but valid

**Covers:** UC-12; the empty state; every nullable field's absence rule at once.

**Preconditions:** Signed in as owner B (`${USER_B_JWT}`), the seeded account with no transactions,
no budgets and no tips. (If you have already used owner B for another module's manual test, register a
fresh student with `POST /api/v1/auth/register` instead and sign in as that one.)

**Steps:**

1. `GET /api/v1/dashboard` with `Authorization: Bearer ${USER_B_JWT}`.

**Expected result:**

- `200 OK` — **not `404`**, and not an error. A student with nothing to show has a valid, empty
  dashboard. This is what the frontend's first-run screen renders from.
- `periodMonth` is the current month in `+07:00`, the same as M7-01's.
- `summary.totalIncome: 0.00`, `summary.totalExpense: 0.00`, `summary.netAmount: 0.00`. Zeros, not
  omitted.
- `summary.monthlyAllowanceBaseline` and `summary.monthlySavingsGoal` are whatever the account's
  profile holds (a freshly registered student has `0.00` for both).
- **`summary` has no `savingsGoalPct` key.** With a goal of `0.00` the percentage is undefined and the
  view returns `NULL`; the field is absent, not `0`. (With a goal of `0.00`, `71` would be a fabricated
  value and `0` would suggest a hopeless month — neither is right.)
- **`topCategory` has no value** — the whole key is absent, or it is absent from the body. There is no
  expense category to rank, so there is no winner. It is **not** an object full of nulls.
- `tips: []` and `announcements` is the list of live `ALL`/`STUDENTS` notices — the two seeded ones
  are visible to **every** student, so this array is **not** empty even on a brand-new account. An
  empty `announcements` array means the seeded notices have expired or been switched off, not that the
  filter is wrong.

**Result:** [ ] Pass   [ ] Fail

---

### M7-03 — The month cannot be requested; `?month=` is ignored

**Covers:** UC-12; the month is the database's, and there is no month parameter (§3.4 point 1).

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`).

**Steps:**

1. `GET /api/v1/dashboard` — note `periodMonth` and the `summary` figures.
2. `GET /api/v1/dashboard?month=2020-01` — the same request with a month parameter.
3. `GET /api/v1/dashboard?month=2099-12`.

**Expected result:**

- Steps 2 and 3: `200 OK`, and the response is **identical** to step 1's, month included. The parameter
  is ignored — it is not read at all, so it cannot be wrong.
- **`periodMonth` is the current month in every response, never `"2020-01"` or `"2099-12"`.** Accepting
  the parameter would mean returning the *current* month's totals under a requested month's heading:
  every individual field would still look plausible, which is what would make it dangerous.
- **The parameter is ignored rather than refused.** A `400` here would be a bug: an unknown query
  parameter on a read is not a malformed request. Documented in `docs/api/dashboard.md` §4 so a tester
  does not read this as a missing validation.
- There is no way to see a previous month's dashboard through the API.

**Result:** [ ] Pass   [ ] Fail

---

### M7-04 — The totals count income and expense by the category's type

**Covers:** UC-12 B1; BR-05 (a category's type decides which side of the total a transaction falls on);
BR-09 (a deleted record stops counting).

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`), freshly seeded so M7-01's figures hold.
You have an `EXPENSE` `categoryId` and an `INCOME` `categoryId` from `GET /api/v1/categories`.

**Steps:**

1. `GET /api/v1/dashboard`; record `summary.totalIncome`, `summary.totalExpense`, `summary.netAmount`.
2. `POST /api/v1/transactions` as owner A, dated **today**, `amount: 100.00`, in an **`EXPENSE`**
   category, e.g. `Miscellaneous`.

   ```json
   { "categoryId": 12, "amount": 100.00, "description": "Books and stationery", "txnDate": "<today in +07:00>" }
   ```

3. `GET /api/v1/dashboard` again.
4. `POST /api/v1/transactions` as owner A, dated **today**, `amount: 50.00`, in an **`INCOME`**
   category, e.g. `Gift`.
5. `GET /api/v1/dashboard` again.
6. Soft-delete the transaction from step 2 (`DELETE /api/v1/transactions/{id}`), then read the
   dashboard once more.

**Expected result:**

- Step 3: `totalExpense` rose by exactly `100.00`; `totalIncome` is **unchanged**; `netAmount` fell by
  `100.00`. An expense category's transaction never touches the income figure.
- Step 5: `totalIncome` rose by exactly `50.00`; `totalExpense` is unchanged; `netAmount` rose by
  `50.00`.
- Step 6: `totalExpense` is back to step 1's value, and `netAmount` with it. The row still exists in
  `GET /api/v1/transactions`' data — a soft delete hides it from every total, it does not erase it.
- The dashboard reports the figures the **views** compute; it does not add up transactions itself. If a
  total looks wrong, query the view directly rather than suspecting the service.

**Result:** [ ] Pass   [ ] Fail

---

### M7-05 — The saving-goal percentage appears only when there is a goal

**Covers:** UC-12 B1; the nullable field, and the two directions of it.

**Preconditions:** Signed in as owner B (`${USER_B_JWT}`) — or a freshly registered student, who has
no goal. `PATCH /api/v1/profile/me` (module 2) sets the goal.

**Steps:**

1. `GET /api/v1/dashboard` as owner B. Confirm `summary` has **no** `savingsGoalPct` (M7-02).
2. `PATCH /api/v1/profile/me` with `{"monthlySavingsGoal": 100.00}`.
3. `GET /api/v1/dashboard` again.
4. Record one expense of `40.00` in any `EXPENSE` category, dated **today**.
5. `GET /api/v1/dashboard` again.
6. `PATCH /api/v1/profile/me` with `{"monthlySavingsGoal": 0.00}`.
7. `GET /api/v1/dashboard` again.

**Expected result:**

- Step 3: `savingsGoalPct` is now **present**, and `monthlySavingsGoal: 100.00`. With no transactions
  and a goal set, it reads `0.00` — a real zero here, distinct from step 1's absent field.
- Step 5: `savingsGoalPct: -40.00`. Net is `-40.00`, so `-40.00 / 100.00 × 100 = -40.00`. It is
  **reported as negative, not clamped to `0`**: the student is `40.00` behind the goal and hiding that
  would misstate the position (M7-06).
- Step 7: the key is **absent again**, exactly as in step 1. Clearing the goal removes the percentage
  rather than showing `0`.
- The **only** thing that makes `savingsGoalPct` absent is a goal of `0.00`; a goal of any other value
  always produces a number, whatever the totals are.

**Result:** [ ] Pass   [ ] Fail

---

### M7-06 — A net-negative month reports a negative percentage

**Covers:** UC-12 B1; the boundary a clamping implementation would get wrong.

**Preconditions:** Continue from M7-05 step 5: owner B has a goal of `100.00` and a net of `-40.00`.

**Steps:**

1. `GET /api/v1/dashboard` as owner B.
2. Record an `INCOME` of `100.00` dated today, in e.g. `Allowance`.
3. `GET /api/v1/dashboard` again.

**Expected result:**

- Step 1: `summary.netAmount: -40.00` and `summary.savingsGoalPct: -40.00`. Both are negative and both
  are reported as they are.
- Step 3: `netAmount: 60.00` and `savingsGoalPct: 60.00` — `60.00 / 100.00`. The percentage moves with
  the net, in both directions, and there is no floor at zero anywhere.
- `savingsGoalPct` can exceed `100.00` too: a net above the goal is a good month, not an error. There
  is no upper clamp either.

**Result:** [ ] Pass   [ ] Fail

---

### M7-07 — The top category is the largest expense category, and only that

**Covers:** UC-12 B2; BR-05 (income is never the top "spending" category); BR-09 (a trashed record does
not count).

**Preconditions:** Signed in as owner B (or a fresh student), so the change is unambiguous. Signed out
of owner A's token, or use owner B's throughout — the demo account's figure is asserted read-only in
M7-01 and must not be disturbed.

**Steps:**

1. `GET /api/v1/dashboard` as owner B; confirm `topCategory` is absent (M7-02).
2. Record an **`INCOME`** transaction of `500.00` dated today, in e.g. `Scholarship`. Read the
   dashboard.
3. Record an **`EXPENSE`** of `20.00` in `Food`. Read the dashboard.
4. Record an **`EXPENSE`** of `35.00` in `Transport`. Read the dashboard.
5. Record an **`EXPENSE`** of `60.00` in `Entertainment`, then soft-delete that transaction. Read the
   dashboard.
6. Soft-delete the `Transport` transaction. Read the dashboard.
7. Soft-delete the `Food` transaction. Read the dashboard.

**Expected result:**

- Step 2: `topCategory` is **still absent**. `500.00` of income is not spending, and the view ranks
  `EXPENSE` rows only. A dashboard that showed `Scholarship` here would be reporting the largest
  transaction, not the largest expense.
- Step 3: `topCategory.categoryName: "Food"`, `totalAmount: 20.00`, with `categoryIcon` and
  `categoryColor` present (the view publishes neither, so their presence is what proves the join onto
  `categories` found the row).
- Step 4: the block becomes `"Transport"` `35.00` — a bigger expense replaces a smaller one; the block
  is not sticky.
- Step 5: **still `"Transport"` `35.00`.** The `60.00` Entertainment expense was deleted, so it counts
  for nothing — `60.00` must not appear anywhere in the block.
- Step 6: the block becomes `"Food"` `20.00`.
- Step 7: `topCategory` is **absent again** — with no expense left there is no winner, and the response
  returns no block rather than a zeroed one.
- Throughout, `summary.totalExpense` moves in step with these edits; the top category can never exceed
  the month's total expense.

**Result:** [ ] Pass   [ ] Fail

---

### M7-08 — The tips are this month's, though the table holds three months'

**Covers:** UC-12 B3; **the module's central finding** — the tips view has no time filter, and the
application adds one (§5.1).

**Preconditions:** A freshly seeded database, so the demo account's tips are exactly the nine that
`db/06_demo.sql` generated. Signed in as owner A (`${USER_A_JWT}`). **Read-only** — this case must not
write.

**Steps:**

1. Ask the database what the tip view holds for the demo student:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT tip_id, period_month, state, display_order, title
        FROM v_dashboard_tips
       WHERE user_id = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu')
       ORDER BY period_month, display_order;"
   ```

2. `GET /api/v1/dashboard` as owner A and count the `tips` array.

**Expected result:**

- Step 1 returns **nine** rows across **three** months (three per month). The current month's three are
  the ones M7-01 lists.
- Step 2 returns **three**, and their ids are exactly the current month's three from step 1's output.
- **Nine in the table, three on the screen.** This is correct and is the whole point of the case: the
  tips view returns every month the student has tips for, and the endpoint scopes it to the month the
  summary row reported. A dashboard that passed the view through unchanged would show nine tips — one
  month's advice interleaved with another's — under a single month's heading.
- The response's `periodMonth` matches the `period_month` of the tips it returned. No tip from another
  month appears, and no tip carries its own month field.
- If step 2 returns nine, the DAO's month predicate has been lost. Report it; it is not a display
  question.

**Result:** [ ] Pass   [ ] Fail

---

### M7-09 — The tip block is bounded

**Covers:** UC-12 B3; BR-14 (a bounded number of tips); the `tips.max_dashboard` setting.

**Preconditions:** A freshly seeded database. Owner A (`${USER_A_JWT}`). **Read-only.**

**Steps:**

1. Read the setting:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT setting_key, setting_value FROM system_settings WHERE setting_key = 'tips.max_dashboard';"
   ```

2. `GET /api/v1/dashboard` as owner A and count the `tips` array.

**Expected result:**

- `tips.max_dashboard` reads `3`.
- The `tips` array holds **no more than three** entries, and its length equals the number of
  non-dismissed tips the current month actually has in `v_dashboard_tips` — not necessarily three. The
  bound is applied by `sp_generate_tips` **when tips are generated**, so the dashboard reports however
  many this month has; it does not take the first three of a longer list and does not pad a shorter one.
- The response's array length never exceeds the setting.
- The bound is **not** a dashboard parameter. There is no `?limit=` and this endpoint does not read the
  setting itself.

**Result:** [ ] Pass   [ ] Fail

---

### M7-10 — The tips arrive in the view's order, and a tip with no category omits the field

**Covers:** UC-12 B3; BR-14 (pinned tips lead); the nullable `categoryId`.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. **Read-only.**

**Steps:**

1. `GET /api/v1/dashboard` as owner A.
2. Read each tip's `displayOrder` and its position in the array.
3. Read `tips[0]`, the `"Your savings goal is at risk"` tip, and look for `categoryId`.

**Expected result:**

- The array is in `displayOrder` order, ascending: the entry with `displayOrder: 1` is first, then `2`,
  then `3`. The dashboard reports the order the view ranked — it does not re-sort by `potentialSaving`.
- **The order is not by `potentialSaving`**, and the seeded data proves it: the first tip has the
  largest `potentialSaving` (`29.00`) but a tip can equally lead with a smaller one, because the view's
  key is its own `rank_score`, which each rule weights differently. `rank_score` is **not published** —
  the array's order is the same fact. Do not expect the array to sort by the saving figure.
- `tips[0]` has **no `categoryId` key**. That tip is about the saving goal as a whole and names no
  category, so the field is absent rather than `null` and rather than `0`.
- `tips[1]` and `tips[2]` **do** have `categoryId`, naming a real expense category id. Every published
  `categoryId` is one you can look up in `GET /api/v1/categories`.
- Every tip has `title`, `body`, `potentialSaving` and `displayOrder`; `body` is populated (the
  templates render their placeholders — expect `80.0%`, `127.3%`-style figures inside the text).
- **No tip carries a `periodMonth`**, and none carries `rankScore`. The month is on the response once.

**Result:** [ ] Pass   [ ] Fail

---

### M7-11 — A pinned tip leads; a dismissed tip never comes back

**Covers:** UC-12 B3; BR-14 (pinned tips first, dismissed tips gone).

> **This case changes a tip's state through module 9's endpoint** —
> `POST /api/v1/tips/{id}/state` with `{"state": "PINNED" | "DISMISSED" | "NEW"}`. The effect on the
> dashboard is what this case is about; the write itself is UC-18 and is exercised fully in
> `MODULE_09_MANUAL_TEST.md`. Restore each tip to `NEW` so the seeded database is left unchanged.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded.

**Steps:**

1. `GET /api/v1/dashboard` as owner A. Record the ids of the three tips, in order, as **T1**, **T2**,
   **T3**.
2. Pin the **last** one (`T3`) — so the change is visible and cannot be confused with the order it
   already had:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/tips/<T3>/state \
     -H "Authorization: Bearer ${USER_A_JWT}" -H "Content-Type: application/json" \
     -d '{ "state": "PINNED" }'
   ```

3. `GET /api/v1/dashboard` again as owner A.
4. Dismiss **T1** (the tip that led before step 2):

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/tips/<T1>/state \
     -H "Authorization: Bearer ${USER_A_JWT}" -H "Content-Type: application/json" \
     -d '{ "state": "DISMISSED" }'
   ```

5. `GET /api/v1/dashboard` again.
6. Restore both tips so the database is seeded again:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/tips/<T1>/state \
     -H "Authorization: Bearer ${USER_A_JWT}" -H "Content-Type: application/json" \
     -d '{ "state": "NEW" }'      # T1 (allowed — it was NEW before step 4)
   curl -i -X POST http://localhost:8080/api/v1/tips/<T3>/state \
     -H "Authorization: Bearer ${USER_A_JWT}" -H "Content-Type: application/json" \
     -d '{ "state": "NEW" }'      # T3: un-pin
   ```

7. `GET /api/v1/dashboard` once more.

**Expected result:**

- Step 3: **`T3` is now first**, and it reads `state: "PINNED"`. The other two follow in their previous
  relative order. Pinning wins over `rank_score` — that is BR-14's "pinned tips lead the list".
- Step 5: **`T1` is absent entirely.** It does not move to the end, it does not appear with
  `state: "DISMISSED"`, and the array is one shorter. A dismissed tip is filtered out by the view, so no
  request can bring it back.
- Step 6's `T1` restore returns `400` — **dismissal is one-way**, so a dismissed tip cannot be moved
  back to `NEW` through the API. Restore `T1` directly in the database instead:

  ```bash
  mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
    "UPDATE user_tips SET state = 'NEW' WHERE tip_id = <T1> AND user_id = \
     (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu');"
  ```

- Step 7: the array is the seeded three again, in the seeded order, all `state: "NEW"`.
- The tip count after step 5 is one fewer than after step 3 — not the same count with one marked.
- **Restoring the state is part of the case.** A live database left with `T1` dismissed makes M7-08's
  count wrong for the next tester, and dismissal cannot be undone through the API.

**Result:** [ ] Pass   [ ] Fail

---

### M7-12 — An administrator-targeted announcement is invisible to a student

**Covers:** UC-12 B3; **the module's one reachable disclosure, closed by the application** (§5.2).

> **This case inserts the notices directly, because it is about the dashboard's read filter, not about
> the write route.** Raising an announcement through the API is `POST /api/v1/admin/announcements`
> (module 11), but that path needs an `ADMIN` token and an announcement id to withdraw afterwards;
> inserting the three rows keeps the case focused on one server, one response and one diff. The
> statements are paired so the seeded state is restored. **Run them only against your dev database.**

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. The two seeded notices are `STUDENTS`.

**Steps:**

1. `GET /api/v1/dashboard` as owner A; record the announcement ids and titles. Confirm the seeded two
   are present.
2. Insert one notice for each audience, all currently live:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'STUDENT-ONLY NOTICE', 'visible to students', 'INFO', 'STUDENTS', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, id FROM users WHERE email = 'admin@campuscoin.edu';
      INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'STAFF-ONLY NOTICE', 'internal maintenance', 'WARNING', 'ADMINS', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, id FROM users WHERE email = 'admin@campuscoin.edu';
      INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'EVERYONE NOTICE', 'for all readers', 'SUCCESS', 'ALL', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, id FROM users WHERE email = 'admin@campuscoin.edu';"
   ```

3. `GET /api/v1/dashboard` again as owner A.
4. Remove the three rows again:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "DELETE FROM announcements WHERE title IN ('STUDENT-ONLY NOTICE','STAFF-ONLY NOTICE','EVERYONE NOTICE');"
   ```

5. `GET /api/v1/dashboard` once more.

**Expected result:**

- Step 3: `STUDENT-ONLY NOTICE` and `EVERYONE NOTICE` are **present**; **`STAFF-ONLY NOTICE` is
  absent.** This is the case's purpose: `v_active_announcements` answers only "is this notice within
  its window", so without the application's audience filter the internal notice would appear on every
  student's dashboard.
- The `WARNING` severity is `STAFF-ONLY NOTICE`'s and goes with it — no entry in the response carries
  `severity: "WARNING"` at step 3.
- The response publishes **no `audience` field at all**, so a reader cannot tell *why* a notice is
  theirs. That is intentional: the filter is the server's job and its criteria are not part of the
  contract (M7-16).
- Step 5: the announcement list is back to the seeded set. If the internal notice appears at step 3,
  the DAO's audience predicate has been lost — report it as a **disclosure**, with priority over any
  other finding in this procedure.
- There is no admin-side equivalent to check here: an administrator is refused this whole endpoint
  (M7-15), so there is no reader who should receive the `ADMINS` half.

**Result:** [ ] Pass   [ ] Fail

---

### M7-13 — Live means within the window, and an open-ended notice has no end

**Covers:** UC-12 B3; the announcement window; `ck_ann_window`; the nullable `endsAt`.

**Preconditions:** Owner A (`${USER_A_JWT}`). Steps 2 and 6 write to the database and undo themselves;
run them only against your dev database.

**Steps:**

1. `GET /api/v1/dashboard` as owner A; note the seeded notices and their `startsAt`/`endsAt`.
2. Insert three notices with different windows — one **not yet started**, one **already ended**, and
   one **open-ended** (`ends_at IS NULL`):

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'FUTURE NOTICE', 'not yet', 'INFO', 'STUDENTS', DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), 1, id FROM users WHERE email = 'admin@campuscoin.edu';
      INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'PAST NOTICE', 'over', 'INFO', 'STUDENTS', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 1, id FROM users WHERE email = 'admin@campuscoin.edu';
      INSERT INTO announcements (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
      SELECT 'OPEN-ENDED NOTICE', 'runs until switched off', 'INFO', 'STUDENTS', NOW(), NULL, 1, id FROM users WHERE email = 'admin@campuscoin.edu';"
   ```

3. `GET /api/v1/dashboard`.
4. Switch one **live** notice off and read the dashboard again:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "UPDATE announcements SET is_active = 0 WHERE title = 'OPEN-ENDED NOTICE';"
   ```

5. Switch it back on and read the dashboard again.
6. Remove the three test rows:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "DELETE FROM announcements WHERE title IN ('FUTURE NOTICE','PAST NOTICE','OPEN-ENDED NOTICE');"
   ```

**Expected result:**

- Step 3: **`OPEN-ENDED NOTICE` is present** and **has no `endsAt` key** — an absent value is an absent
  key, not `null`, and a notice that never ends is live rather than expired. `FUTURE NOTICE` and
  `PAST NOTICE` are **both absent**: one has not started, the other has ended. Only the window matters,
  not the notice's existence.
- Step 4: `OPEN-ENDED NOTICE` is **absent** — `is_active = 0` takes it out regardless of its window.
- Step 5: it is **back**. The two conditions (switched on, inside the window) are independent and both
  are required.
- The seeded notices carry `startsAt` values that may be **identical** (both were inserted with the
  same `NOW()`). Their relative order is then decided by `id`, descending, so the order is stable
  between calls. Two notices sharing a timestamp are not a bug, and a different order between two runs
  would be.
- **Every timestamp in a notice is a local time with no zone offset** — `"2026-09-25T00:00:00"`, not
  `"…Z"`. The database stores a `DATETIME` in `+07:00`; render it as a local time.
- The insertion above is refused by `ck_ann_window` if you give an end **earlier** than the start —
  that constraint is the database's, and this endpoint only reads what it permits.

**Result:** [ ] Pass   [ ] Fail

---

### M7-14 — Reading the dashboard changes nothing

**Covers:** UC-12; the endpoint writes nothing and is idempotent.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. Record the tip states from M7-11 step 1's
query if you need a before/after.

**Steps:**

1. `GET /api/v1/notifications` as owner A; count the messages.
2. `GET /api/v1/dashboard` three times in a row.
3. Compare the three responses.
4. `GET /api/v1/notifications` again; count the messages.
5. Re-run M7-11 step 1's query against `v_dashboard_tips` and compare the states with what the
   dashboard reported.

**Expected result:**

- Steps 2–3: the three responses are **identical** in every field, including the tip order and the
  announcement order. `periodMonth` is the same month in all three.
- Step 4: the notification count is **unchanged**. Opening the dashboard does not mark anything read,
  does not create a message and does not touch a tip. There is no "seen" record, no view counter and no
  cache to warm — the figures are computed per read from the views.
- Step 5: no tip's `state` changed. The dashboard reports tips; it does not pin, dismiss or acknowledge
  them.
- If the three responses differ, the difference is the data changing underneath (another test writing
  to the same account concurrently), not the endpoint being non-deterministic. Confirm by checking
  whether a write happened between the calls.

**Result:** [ ] Pass   [ ] Fail

---

### M7-15 — The dashboard belongs to the caller, and only a student has one

**Covers:** §15 (role `STUDENT` enforced server-side; a token required); BR-02 (ownership).

**Preconditions:** Owner A (`${USER_A_JWT}`) and owner B (`${USER_B_JWT}`), both freshly seeded.

**Steps:**

1. `GET /api/v1/dashboard` with **no** `Authorization` header.
2. `GET /api/v1/dashboard` with `Authorization: Bearer not.a.token`.
3. `GET /api/v1/dashboard` with `Authorization: Bearer ${ADMIN_JWT}`.
4. `GET /api/v1/dashboard` with `${USER_A_JWT}`; record `summary`.
5. `GET /api/v1/dashboard` with `${USER_B_JWT}`; record `summary`.

**Expected result:**

- Step 1: `401 Unauthorized` with `errorCode: "UNAUTHENTICATED"`.
- Step 2: `401 Unauthorized`. A malformed token is not a `400`, and **no stack trace or token text** is
  returned or logged.
- Step 3: `403 Forbidden`. An administrator is **not** a student; the rule is on `/api/v1/dashboard/**`
  and Spring Security enforces it *before* the controller. An administrator has no dashboard.
- Step 4: owner A's seeded figures (M7-01's).
- Step 5: owner B's figures — zeroed or whatever owner B's own testing produced, and **not** owner A's
  `260.00` / `189.00` under any circumstances. The two accounts' `topCategory` and `tips` differ too.
- **There is no way to ask for another student's dashboard**: no `?userId=`, no `/dashboard/{id}`, no
  body. Ownership is structural here — no method at any layer takes a user identifier — so there is no
  identifier to tamper with and nothing to test beyond "the figures are the caller's".
- Owner B's dashboard **never** contains a tip, an announcement or a category that belongs to owner A
  alone. The announcements are shared by design (`ALL`/`STUDENTS`), and that is not an ownership leak.

**Result:** [ ] Pass   [ ] Fail

---

### M7-16 — The response never names the owner, and carries nothing else it should not

**Covers:** UC-12; BR-02; §15 (no sensitive or internal field in a response).

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:**

1. `GET /api/v1/dashboard` and capture the raw body:

   ```bash
   curl -s http://localhost:8080/api/v1/dashboard \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_dashboard.json
   ```

2. Search it for each key in the table below.
3. List the keys the body actually contains, at every level:

   ```bash
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_dashboard.json | sort -u
   ```

**Expected result:**

- None of these appears, at any level:

  | Must not appear | Why |
  |---|---|
  | `userId`, `user_id`, `user` | The response is the caller's by construction. A `userId` is one more place an identity could leak into a log or a proxy cache |
  | `email`, `fullName`, `full_name` | The view selects `full_name`; the mapper deliberately does not publish it. The screen already knows who is signed in |
  | `audience`, `created_by`, `createdBy` | An announcement's audience and author are internal. Publishing `audience` would also tell a reader the criteria by which notices are filtered |
  | `rankScore`, `rank_score` | The ranking key, deliberately unpublished (§3.4 of the contract) |
  | `displayOrder`, `display_order` | The view's ranking column. The array's order is the same fact; the column would invite a client to re-sort by an implementation detail |
  | `isActive`, `is_active`, `period_month` | Internal lifecycle and storage columns. `periodMonth` is published once, on the response, in `YYYY-MM` |
  | `passwordHash`, `password_hash`, `tokenVersion`, `refreshToken` | Never present in any response; checked globally by `OpenApiContractIT` |

- Step 3's key list is a subset of: `periodMonth`, `totalIncome`, `totalExpense`, `netAmount`,
  `monthlyAllowanceBaseline`, `monthlySavingsGoal`, `savingsGoalPct`, `categoryId`, `categoryName`,
  `totalAmount`, `categoryIcon`, `categoryColor`, `id`, `title`, `body`, `potentialSaving`, `state`,
  `displayOrder` (inside tips), `severity`, `startsAt`, `endsAt`. Anything else appearing is a finding.
- The whole body contains **no** nested user object and **no** per-tip month.

**Result:** [ ] Pass   [ ] Fail

---

### M7-17 — There is no other dashboard route

**Covers:** §13 (no duplicate capabilities); the endpoint inventory; §7.5.

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:** Send each request below as owner A and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/dashboard/summary` | `404` — the summary is a block of the one response, not its own route |
| b | `GET /api/v1/dashboard/top-category` | `404` |
| c | `GET /api/v1/dashboard/tips` | `404` — tips are a block here; the tips *screen* is UC-18, at `/api/v1/tips` |
| d | `GET /api/v1/dashboard/announcements` | `404` |
| e | `GET /api/v1/dashboard/1` | `404` — a dashboard belongs to an account, not to a record |
| f | `POST /api/v1/dashboard` | `405` or `404` — a dashboard render writes nothing |
| g | `PUT /api/v1/dashboard` | `405` or `404` |
| h | `PATCH /api/v1/dashboard` | `405` or `404` |
| i | `DELETE /api/v1/dashboard` | `405` or `404` |
| j | `GET /api/v1/dashboard?userId=2` | `200`, with the **caller's** own figures — the parameter is ignored (M7-03) |

**Expected result:**

- a–i: **none succeeds.** The exact code is less important than that nothing is created, changed or
  deleted, and that no route returns a partial dashboard. Four block-routes would cost four round-trips
  and allow a mixed-month paint; the one route is deliberate.
- j: `200` with the caller's figures, never `userId=2`'s. An ignored parameter is the correct outcome;
  a `200` returning student 2's data would be a **critical** finding.
- The OpenAPI document at `/api-docs` lists **exactly one** dashboard operation. This is
  machine-checked by `OpenApiContractIT` (22 distinct paths, 35 operations overall as of module 7;
  the count grows as later modules add routes), and the path count is asserted deliberately — adding
  a route without adding it to `docs/api/API_INVENTORY.md` fails that test on purpose.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Three behaviours a tester is most likely to misread

All three are deliberate and documented. Read this section before marking anything in §4 as a failure.
None is a defect in the module.

> ### 5.1 Nine tips in the table, three on the screen
>
> The tips screen (UC-18) and the dashboard (UC-12) read the same view, and the view returns **every
> month** the student has tips for. Its `PARTITION BY user_id, period_month` exists only so the ranking
> restarts per month — it is not a filter. The demo account therefore has **nine** rows in
> `v_dashboard_tips` (three months × three tips, written by three `sp_generate_tips` calls in
> `db/06_demo.sql`) and the dashboard returns **three**.
>
> **The application adds `AND period_month = <the month the summary reported>`.** Without it a student
> would see three months of advice interleaved under one heading, in an order
> (`1, 4, 7, 2, 5, 8, 3, 6, 9`) that looks arbitrary because it interleaves three rankings.
>
> **Do not report "the dashboard shows the wrong tips" without first counting the view's rows for that
> month** (M7-08 step 1). Nine-versus-three is correct; nine-versus-nine is the bug.
>
> Related and also deliberate: the dashboard's month is read from the summary row, not computed in
> Java, so one response cannot mix two months. That is **not** a timezone guarantee, and the module
> does not claim one — what is guaranteed is that a single request does not straddle a month boundary.

> ### 5.2 `?month=` is ignored rather than refused
>
> `GET /api/v1/dashboard?month=2020-01` answers `200` with the **current** month's data. The parameter
> is not read at all.
>
> This is the right outcome, and a `400` would be wrong: an unrecognised query parameter on a read is
> not a malformed request. The alternative — honouring the parameter — would be worse still, because
> the totals, the top category and the announcement window all derive their month from the database's
> own clock. Honouring `?month=` could return **the current month's figures under a requested month's
> heading**, with every individual field still plausible. That is why there is no parameter and why the
> response reports the month it used instead of taking one.
>
> **Do not report the ignored parameter as a missing validation** (M7-03). It is stated in
> `docs/api/dashboard.md` §4 so a client integrates correctly.

> ### 5.3 An administrator-targeted notice must be invisible, and the top category has no tie-break
>
> Two smaller properties, both correct:
>
> - **The audience filter is the application's.** `v_active_announcements` answers "is this notice
>   within its window", not "is it for this reader", and the view has no audience predicate. The
>   endpoint adds `IN ('ALL', 'STUDENTS')`. If you insert an `ADMINS` notice (M7-12) and it appears on a
>   student's dashboard, that is a **disclosure** — the internal notice content, and the existence of a
>   staff-only channel — and it takes priority over every other finding in this procedure. The response
>   publishes no `audience` field, so the leak would be invisible without a test like M7-12.
> - **The top category has no tie-break.** `v_top_category_current_month` orders by `total_amount DESC`
>   alone, so two expense categories with equal totals have **no defined winner** — the view returns
>   whichever row its window function picked. This is a property of the schema, which §4 forbids
>   changing, so it is documented rather than fixed
>   (`docs/api/dashboard.md` §3.3) and a client must not depend on which category it gets. A tie is not
>   a bug; **a different category on two consecutive reads without an intervening write would be**, and
>   is worth reporting if you can reproduce it with the same totals.

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M7-01 | UC-12 B1, B2, B3 together; the seeded month's totals, goal percentage, top category, tips and announcements |
| M7-02 | UC-12 empty state; every nullable field's absence rule (`savingsGoalPct`, `topCategory`, `tips`) |
| M7-03 | UC-12; the month is the database's and is not selectable (`?month=` ignored, not refused) |
| M7-04 | UC-12 B1; BR-05 (the category's type splits income from expense); BR-09 (a deleted record stops counting) |
| M7-05 | UC-12 B1; `savingsGoalPct` present only when a goal exists; a real `0.00` vs an absent field |
| M7-06 | UC-12 B1; a net-negative month reports a negative percentage, unclamped in both directions |
| M7-07 | UC-12 B2; BR-05 (income is never the top spending category); BR-09 (a trashed expense does not count); the icon/colour join |
| M7-08 | UC-12 B3; the tips view has no time filter and the application adds one (nine rows, three shown) |
| M7-09 | UC-12 B3; BR-14 (the block is bounded by `tips.max_dashboard`) |
| M7-10 | UC-12 B3; BR-14 (the order is the view's `rank_score` order); a categoriless tip omits `categoryId`; `rankScore` is unpublished |
| M7-11 | UC-12 B3; BR-14 (a pinned tip leads; a dismissed tip never comes back) |
| M7-12 | UC-12 B3; the announcement view has no audience filter and the application adds one (`ADMINS` invisible) |
| M7-13 | UC-12 B3; the announcement window; an open-ended notice is live and has no `endsAt`; `is_active`; `ck_ann_window` |
| M7-14 | UC-12; the read writes nothing and is idempotent |
| M7-15 | §15 (role `STUDENT` enforced server-side, a token required, admin `403`); BR-02 (ownership) |
| M7-16 | UC-12; BR-02 (no response names the owner); §15 (no internal or sensitive field published) |
| M7-17 | §13 (no duplicate capability); the endpoint inventory; §7.5 (one route, read-only) |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass. None of these is reachable by hand through
`GET /api/v1/dashboard` alone.

| Not covered | Why |
|---|---|
| The pin/dismiss route itself (UC-18) | Module 9 owns `POST /api/v1/tips/{id}/state` and `MODULE_09_MANUAL_TEST.md` exercises it. M7-11 uses it only to produce the dashboard-visible effect, and unpins what it pinned |
| Raising announcements through the API (UC-21) | Module 11 owns `POST /api/v1/admin/announcements`. M7-12 and M7-13 insert rows directly because they test the dashboard's *read* filter, and say so |
| Generating tips for a month (`sp_generate_tips`) | The procedure is a database object, not an endpoint. This module reads the rows it wrote; the demo script's three calls are what produced M7-01's tips |
| A month other than the current one | The endpoint takes no month and no other month can be requested (§3.4). Producing one would mean changing the server's clock |
| Concurrency | A read with no lock, no write and no pagination has nothing to race. `readingTheDashboardChangesNoState` pins idempotency instead, which is the property that does apply |
| A disabled account's token, a revoked session, an expired token | Disabling is module 11's action and revocation needs module 1's sign-out. M7-15 covers the missing, malformed and wrong-role cases |
| Two equal top-category totals | Not reproducible on demand by hand; the tie-break's absence is stated in §5.3 and in `docs/api/dashboard.md` §3.3 |
| Budget progress and the notifications list | Deliberately not on this screen (§1 of the module report): `GET /api/v1/budgets` and `GET /api/v1/notifications` own them. Covered by module 6's procedure |
| A client-side rendering of a nullable field | The Angular notes are documented (`docs/api/dashboard.md` §10); no Angular source is changed by this module, and all its services are still mock-only |
| The `500`-level failure path | Not reachable through the API. There is no input to make fail and no fault injection |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/dashboard.md`,
`docs/modules/MODULE_07_DASHBOARD.md`, `docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md`,
`db/02_views.sql`, `db/06_demo.sql` or the implementation under
`backend/src/main/java/com/campuscoin/dashboard/`.
