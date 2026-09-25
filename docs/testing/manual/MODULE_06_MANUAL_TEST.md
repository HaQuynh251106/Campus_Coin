# Module 06 — Budget & Notifications (UC-13, UC-14) — Manual Test Procedure

**Scope:** endpoints 27–34 — `/api/v1/budgets/**` (27–31) and `/api/v1/notifications/**` (32–34),
role `STUDENT` only.

**Sources of truth for this procedure:** `docs/api/budgets.md` (the UC-13 contract, §5 "setting a
limit raises no alert", §9 "a budget whose category has been retired cannot be changed", §14 Angular
integration notes), `docs/api/notifications.md` (the UC-14 contract, §5 "how an alert comes to
exist", §5 the two messages verbatim, §6 the seven types), `docs/modules/MODULE_06_BUDGET.md` (§5.1
and §5.2), `docs/OVERNIGHT_BLOCKERS.md` (OB-011, and OB-002/OB-010 which touch this module),
`docs/api/API_INVENTORY.md` (endpoints 27–34), `docs/CREDENTIALS.md` (seeded accounts), and the
implementation under `backend/src/main/java/com/campuscoin/budget/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 27 | `GET` | `/api/v1/budgets` | `200` array |
| 28 | `GET` | `/api/v1/budgets/{id}` | `200` budget |
| 29 | `POST` | `/api/v1/budgets` | `201` budget |
| 30 | `PATCH` | `/api/v1/budgets/{id}` | `200` budget |
| 31 | `DELETE` | `/api/v1/budgets/{id}` | `204`, no body |
| 32 | `GET` | `/api/v1/notifications` | `200` array |
| 33 | `GET` | `/api/v1/notifications/{id}` | `200` notification |
| 34 | `POST` | `/api/v1/notifications/{id}/read` | `200` notification |

Eight endpoints in two groups. Setting, changing and removing a limit are separate methods on the
same resource; there is **no** `/budgets/{id}/check`, `/budgets/{id}/spend`, `/budgets/alerts`,
`/budgets/recalculate`, `POST /notifications`, `DELETE /notifications/{id}`,
`/notifications/read-all`, or any endpoint that marks a message *unread*. The two list endpoints take
**no** `?status=`, `?type=`, `?unread=` or pagination parameter. An endpoint that is not in the table
above does not exist — if a step here asks you to call one, the document is wrong, not the server.

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema and seed data applied.

Every step below can be done either in Swagger UI (each endpoint renders its request body from the
live annotations) or with `curl`. The `curl` form is shown where a body matters.

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
| `${USER_A_JWT}` | The `STUDENT` token of **owner A**. This is the same account as `${JWT}`; the ownership cases name it explicitly so the two sides of the case are unambiguous. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of **owner B**, a different student. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` access token, obtained from the administrator sign-in endpoint. | System Administrator |

There are no other placeholders in this document. Where a numeric id appears (a `budgetId`, a
`categoryId`, a `notificationId`), it is an **example**; always use the id you recorded in your own
run.

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

### 2.3 Dates in this document

A budget **month** is written `"YYYY-MM"` (no day), and a **transaction date** is a plain calendar
date `"YYYY-MM-DD"` with no time and no zone. "Today" is judged in **`Asia/Ho_Chi_Minh` (`+07:00`)** —
the same offset the database session is pinned to and the clock the default month is resolved with.

Two consequences to keep in mind while testing:

- **BR-08 refuses a future-dated transaction.** Every expense you record in §4 must be dated **today**
  or in the past. Use `date +%F` for today.
- A budget's `periodMonth` **may** be a future month (a limit is a plan, not a record), so you can set
  a limit for next month; but no spending can exist in it yet, so its `spentAmount` is `0.00`.

The current month in `+07:00` is what `periodMonth` defaults to. If your clock is near midnight on the
last day of a month, set the month **explicitly** rather than relying on the default.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with data (`db/06_demo.sql`) including **five current-month budgets**:
  Food `30.00`, Transport `25.00`, Entertainment `40.00`, Subscriptions `15.00`, Hostel/Rent
  `160.00`. Food already has **one `BUDGET_NEAR` notification** — the seed's `24.00` of `30.00` is
  exactly 80%, which is UAT-07's number. Hostel/Rent's `120.00` of `160.00` is 75% and raises nothing.
- **Bella Tran** is the seeded **empty** account: for the ownership cases she creates her own category
  and budget through the API.
- Shared default categories (`Allowance`, `Food`, `Transport`, `Hostel/Rent`, `Academics`,
  `Subscriptions`, `Entertainment`, `Miscellaneous`, `Part-time Job`, `Scholarship`, `Gift`,
  `Other Income`) are visible to every student. A budget can only be set on an **`EXPENSE`** category.
- Numeric category ids are whatever `GET /api/v1/categories` returns. Use it to pick a category and
  to check its `type` (`INCOME` / `EXPENSE`) and `isActive` before you use its id.

### 3.2 Authentication

All eight endpoints require `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403`. A missing, malformed, expired or revoked
token answers `401`.

### 3.3 What a budget looks like

A budget response carries: `id`, `categoryId`, `categoryName`, `periodMonth`, `limitAmount`,
`spentAmount`, `remainingAmount`, `consumedPct`, `consumptionStatus`, and — when they have a value —
`categoryIcon`, `categoryColor`. A field with no value is **absent from the JSON, not `null`**.
`limitAmount`, `spentAmount` and `remainingAmount` always show exactly two decimals (`30.00`).
`consumedPct` is a number. `consumptionStatus` is one of `ON_TRACK`, `NEAR`, `EXCEEDED`.

The four figures `spentAmount`, `remainingAmount`, `consumedPct` and `consumptionStatus` are
**computed on every read** from the live transactions — they are not stored, and sending any of them
in a request body changes nothing.

### 3.4 What a notification looks like

A notification response carries: `id`, `type`, `title`, `body`, `isRead`, `readAt`, `createdAt`, and —
when they have a value — `linkUrl`, `refEntityType`, `refEntityId`. There is **no `userId`** key.
`type` is one of seven values: `BUDGET_NEAR`, `BUDGET_EXCEEDED`, `RECURRING_POSTED`, `TIP`,
`INSIGHT_READY`, `ANNOUNCEMENT`, `SYSTEM`. For a budget alert, `refEntityType` is `"BUDGET"` and
`refEntityId` is the budget's id, and `linkUrl` is `"/budgets"`.

`isRead` and `readAt` always agree: an unread message has `isRead: false` and **no `readAt` key**; a
read one has `isRead: true` and a `readAt`. `readAt` is a **local time with no zone offset** —
`"2026-09-25T14:18:12"` — because the database stores a `DATETIME` in `+07:00`. Render it as a local
time; do not attach a `Z`.

### 3.5 How an alert is actually raised — read this before §4

**A budget alert is written by a transaction, not by a budget.** `sp_check_budget_alerts` is called
from the **transactions** insert and update triggers (module 4). Setting, changing or removing a
limit calls it **never**. So the way to see a notification appear is to **record an expense** that
pushes a category's spending across a threshold.

There is no endpoint that raises, clears or recomputes an alert. Do not call
`POST /budgets/{id}/check` or anything of the kind — it does not exist, by design. The contract states
this explicitly (`docs/api/budgets.md` §5) and the OpenAPI document at `/api-docs` lists exactly the
eight endpoints in §1.

The full path is:

```
POST /api/v1/transactions
      → trg_transactions_after_insert
      → sp_check_budget_alerts(user, category, first-of-month(txn_date))
      → (INSERT IGNORE into budget_alert_log -- at most once per threshold)
      → (INSERT into notifications) -- only if the ignore did not swallow it
```

**BR-12 caps this at two messages per category per month** — one `BUDGET_NEAR` and one
`BUDGET_EXCEEDED`, enforced by the unique key `uk_alert_budget_threshold (budget_id, threshold_type)`
on `budget_alert_log` plus `INSERT IGNORE`. A third crossing in the same month writes nothing. The
`EXCEEDED` check runs **first**, so once a category is over its limit the `NEAR` threshold is never
revisited.

**Two escape hatches that make an alert possible again:** deleting the budget cascades its alert-log
rows away (so re-setting the limit lets it alert again), and a soft-deleted transaction stops counting
towards the total (so the position can move back below a threshold).

### 3.6 How the thresholds are set

The two percentages are rows in `system_settings`, not constants in the code:

| Setting | Seeded value | Meaning |
|---|---|---|
| `budget.near_threshold_pct` | `80` | at or above this percentage of the limit → `NEAR` |
| `budget.exceeded_threshold_pct` | `100` | at or above this percentage of the limit → `EXCEEDED` |

They are read by the view and the procedure on every read, so changing them takes effect on the next
request with **no redeploy**. That is an administrator action (module 11); a student cannot change
them, and no endpoint here accepts a threshold.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M6-01 — Set a limit

**Covers:** UC-13 (set a monthly limit for a category); BR-11 (an expense category, one per month).

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`). You have an `EXPENSE` `categoryId` from
`GET /api/v1/categories` that has **no** budget this month (use one you did not use in the seed —
`Academics` is a good choice).

**Steps:**

1. `POST /api/v1/budgets` with `Authorization: Bearer ${USER_A_JWT}` and body:

   ```json
   {
     "categoryId": 12,
     "periodMonth": "2026-09",
     "limitAmount": 50.00
   }
   ```

   (Use a real category id and the **current** month in `+07:00`.)

**Expected result:**

- `201 Created`.
- The body contains `id` (record it as **BUDGET_A**), `categoryId` equal to the one sent,
  `categoryName`, `periodMonth: "2026-09"`, `limitAmount: 50.00` (two decimals),
  `spentAmount: 0.00`, `remainingAmount: 50.00`, `consumedPct: 0`, `consumptionStatus: "ON_TRACK"`.
- `categoryIcon` and `categoryColor` are present (the category has them).
- No `userId` key, and no row timestamps.
- **No notification was created.** `GET /api/v1/notifications` as owner A shows the same messages as
  before this step. This is correct — §3.5.

**Result:** [ ] Pass   [ ] Fail

---

### M6-02 — The month defaults to the current one

**Covers:** UC-13 create; the `Asia/Ho_Chi_Minh` default month.

**Preconditions:** As M6-01, with another unused `EXPENSE` category.

**Steps:**

1. `POST /api/v1/budgets` as owner A, **omitting** `periodMonth`:

   ```json
   { "categoryId": 13, "limitAmount": 20.00 }
   ```

2. Note the current month in `+07:00` (`date -u -v+7H +%Y-%m` on macOS, or `TZ=Asia/Ho_Chi_Minh date
   +%Y-%m`).

**Expected result:**

- `201 Created`, with `periodMonth` equal to the **current month in `+07:00`**, stored as
  `"YYYY-MM"`.
- The response's `periodMonth` is the **1st** of that month's row in the database, rendered as a
  month with no day. The API never returns a day component.
- Sending `"2026-09"` explicitly produces the same month as omitting it, when 2026-09 is current.

**Result:** [ ] Pass   [ ] Fail

---

### M6-03 — An income category cannot be limited

**Covers:** UC-13; BR-11 (a limit is only ever on an expense category).

**Preconditions:** As M6-01. Find a category whose `type` is `INCOME` (`Allowance` is one).

**Steps:**

1. `POST /api/v1/budgets` as owner A with an `INCOME` `categoryId`:

   ```json
   { "categoryId": 1, "periodMonth": "2026-09", "limitAmount": 10.00 }
   ```

**Expected result:**

- `400 Bad Request` with `errorCode: "VALIDATION_ERROR"`.
- `fieldErrors` contains an entry whose `field` is `"categoryId"` — not `limitAmount`, not a bare
  message. A client can attach the error to the right control.
- No budget row is created: `GET /api/v1/budgets` does not gain a row for that category.
- The same refusal happens for **every** caller and is enforced by the database, not only by the
  service: even a hand-written `INSERT` into `budgets` pointing at an income category is refused by
  `sp_validate_budget` through the insert trigger.

**Result:** [ ] Pass   [ ] Fail

---

### M6-04 — A second limit for the same category and month is refused

**Covers:** UC-13; BR-11 (one limit per student, category and month); the remedy the error names.

**Preconditions:** BUDGET_A from M6-01 exists, for category `12` and month `2026-09`.

**Steps:**

1. `POST /api/v1/budgets` as owner A with the **same** category and month:

   ```json
   { "categoryId": 12, "periodMonth": "2026-09", "limitAmount": 99.00 }
   ```

2. Read the `message` field closely.

**Expected result:**

- `409 Conflict` with `errorCode: "BUDGET_ALREADY_EXISTS"` — **not** the generic `DATA_CONFLICT`.
- The `message` names the existing budget's id, so a client can switch to `PATCH` on it instead of
  retrying the `POST`. This is the whole reason the duplicate has its own code.
- The stored limit is unchanged at `50.00`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-05 — The same category can be limited in two different months

**Covers:** UC-13; BR-11 (the uniqueness is per month, not per category overall).

**Preconditions:** BUDGET_A exists for category `12`, month `2026-09`.

**Steps:**

1. `POST /api/v1/budgets` as owner A with the **same category** but the **next** month:

   ```json
   { "categoryId": 12, "periodMonth": "2026-10", "limitAmount": 45.00 }
   ```

2. `GET /api/v1/budgets?month=2026-09` and `GET /api/v1/budgets?month=2026-10`.

**Expected result:**

- Step 1: `201 Created` — a different month is a different budget.
- Step 2: the `2026-09` list contains the `50.00` limit and **not** the `45.00` one; the `2026-10`
  list contains the `45.00` limit. The `?month=` filter is exact.
- The `2026-10` budget reads `spentAmount: 0.00` and `consumptionStatus: "ON_TRACK"` — no spending has
  happened in a future month.

**Result:** [ ] Pass   [ ] Fail

---

### M6-06 — The list computes consumption from live transactions

**Covers:** UC-13 (see how much of each limit is used); BR-09 (deleted rows stop counting).

**Preconditions:** Signed in as owner A. The seeded **Food** budget is `30.00` and the seeded expense
is `24.00`.

**Steps:**

1. `GET /api/v1/budgets?month=2026-09` (the current month) as owner A.
2. Find the **Food** row. Record its `spentAmount`, `consumedPct` and `consumptionStatus`.
3. Record **Food**'s `categoryId` as **CAT_FOOD**.
4. `POST /api/v1/transactions` as owner A, dated **today**, to push Food over 30:

   ```json
   {
     "categoryId": 8,
     "amount": 10.00,
     "description": "Late-night noodles",
     "txnDate": "<today in +07:00>",
     "source": "MANUAL"
   }
   ```

5. `GET /api/v1/budgets?month=2026-09` again and re-read the Food row.

**Expected result:**

- Step 2: Food reads `spentAmount: 24.00`, `consumedPct: 80`, `consumptionStatus: "NEAR"` — the
  seeded UAT-07 position.
- Step 5: Food reads `spentAmount: 34.00`, `consumedPct: 113.33`, `consumptionStatus: "EXCEEDED"`,
  `remainingAmount: -4.00`. **No redeploy, no recompute call** — the figures are derived on read.
- The other four budget rows are unchanged.
- `remainingAmount` goes **negative** once the limit is passed. That is correct, not a bug.

**Result:** [ ] Pass   [ ] Fail

---

### M6-07 — Read one limit matches the list

**Covers:** UC-13 read one; `404` vs a path-parameter error.

**Preconditions:** BUDGET_A from M6-01.

**Steps:**

1. `GET /api/v1/budgets/${BUDGET_A}` as owner A.
2. `GET /api/v1/budgets/999999999` as owner A.
3. `GET /api/v1/budgets/not-a-number` as owner A.

**Expected result:**

- Step 1: `200`, and every field is **identical** to the same budget's row in the `GET /budgets`
  list — the two endpoints share one query, so they cannot disagree.
- Step 2: `404 Not Found` with `errorCode: "NOT_FOUND"`.
- Step 3: `400 Bad Request`. A path parameter that is not a number is a malformed request, not a
  missing resource.

**Result:** [ ] Pass   [ ] Fail

---

### M6-08 — A student with no budgets gets an empty array, not `404`

**Covers:** UC-13 list, empty state.

**Preconditions:** Signed in as owner B (`${USER_B_JWT}`), who has no budgets.

**Steps:**

1. `GET /api/v1/budgets` as owner B, on a month with no limits (e.g. a month far in the past).

**Expected result:**

- `200 OK` with body `[]` — an empty array, never `404` and never `null`.
- Owner B's list never contains any of owner A's budgets, on any month.

**Result:** [ ] Pass   [ ] Fail

---

### M6-09 — Change the limit

**Covers:** UC-13 (change a limit); the row's identity is unchanged.

**Preconditions:** BUDGET_A (`50.00`) from M6-01.

**Steps:**

1. `PATCH /api/v1/budgets/${BUDGET_A}` as owner A:

   ```json
   { "limitAmount": 75.00 }
   ```

2. `GET /api/v1/budgets/${BUDGET_A}` to confirm.

**Expected result:**

- `200 OK`, with `limitAmount: 75.00` and the **same `id`** as before. An edit keeps the row; it does
  not delete and re-create it (so its alert log survives — see M6-16).
- `periodMonth` and `categoryId` are unchanged.
- `remainingAmount` and `consumedPct` are recomputed against the new limit immediately.

**Result:** [ ] Pass   [ ] Fail

---

### M6-10 — Lowering a limit below spending raises no second alert

**Covers:** UC-14; the reconcilable state described in `docs/api/budgets.md` §5.

**Preconditions:** Owner A has a category with **no** alert this month and some spending in it.
`Entertainment` (`40.00` limit, `25.00` seeded spend) is **not** a good choice because the seeded
`25.00` already raises a `NEAR` alert. Use a fresh category: create one (module 3), set a limit above
the spending so nothing alerts, record an expense in it, then come back here.

**Steps:**

1. `GET /api/v1/notifications` as owner A and note how many messages there are.
2. `PATCH` the limit **below** the amount already spent, e.g. from `100.00` to `5.00`.
3. `GET /api/v1/budgets` and read the row: it now reads `consumptionStatus: "EXCEEDED"`.
4. `GET /api/v1/notifications` again.

**Expected result:**

- Step 3: the row is immediately `EXCEEDED` with a negative `remainingAmount`.
- Step 4: **no new message.** The count is exactly what it was in step 1.
- This is correct and is not a bug: the alert belongs to the **transaction** that crossed the
  threshold, and no transaction happened when the limit moved. The screen can therefore legitimately
  show a limit the student is over while the message list has not changed. See §5.1.

**Result:** [ ] Pass   [ ] Fail

---

### M6-11 — The category and the month are not editable

**Covers:** UC-13; BR-11 (the owner, category and month **are** the row's identity).

**Preconditions:** BUDGET_A from M6-01.

**Steps:**

1. `PATCH /api/v1/budgets/${BUDGET_A}` as owner A, sending fields that are not part of the update
   request:

   ```json
   {
     "limitAmount": 80.00,
     "categoryId": 3,
     "periodMonth": "2027-01",
     "spentAmount": 0.00,
     "consumptionStatus": "ON_TRACK",
     "userId": 999
   }
   ```

2. `GET /api/v1/budgets/${BUDGET_A}`.

**Expected result:**

- `200 OK`, with `limitAmount: 80.00` applied.
- `categoryId` is **still the original** — the sent `3` changed nothing.
- `periodMonth` is **still the original month** — the sent `2027-01` changed nothing.
- `spentAmount` and `consumptionStatus` are the computed values, not the sent `0.00` / `"ON_TRACK"`.
- No `userId` appears in the response; ownership did not move.
- There is no way to move a limit onto another category through this endpoint. That is deliberate:
  a writable `categoryId` would be a second path onto an income category, slipping past the check the
  insert trigger makes and no update trigger repeats. Moving a limit is delete-then-create.

**Result:** [ ] Pass   [ ] Fail

---

### M6-12 — Remove a limit

**Covers:** UC-13 (remove a limit); the transactions are untouched.

**Preconditions:** A budget you are willing to delete, in a category that has transactions.

**Steps:**

1. Note the category's transactions with
   `GET /api/v1/transactions?from=<month start>&to=<today>`.
2. `DELETE /api/v1/budgets/${budgetId}` as owner A.
3. `GET /api/v1/budgets` → the row is gone.
4. `GET /api/v1/transactions` again.

**Expected result:**

- Step 2: `204 No Content` with an **empty body**.
- Step 3: the budget no longer appears; the other rows are unaffected.
- Step 4: **every transaction is still there.** Removing a limit does not touch spending. Re-creating
  the limit later yields the same `spentAmount` as before.
- `DELETE` on an id that is already gone answers `404`, not `500`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-13 — Another student's budget is unreachable

**Covers:** UC-13; BR-02 (ownership enforced server-side); no identifier probing.

**Preconditions:** BUDGET_A belongs to owner A. Signed in as owner B (`${USER_B_JWT}`).

**Steps:**

1. As owner B: `GET /api/v1/budgets/${BUDGET_A}`.
2. As owner B: `PATCH /api/v1/budgets/${BUDGET_A}` with `{"limitAmount": 1.00}`.
3. As owner B: `DELETE /api/v1/budgets/${BUDGET_A}`.
4. As owner B: `GET /api/v1/budgets/999999999` (an id that does not exist).

**Expected result:**

- Steps 1–3: `404 Not Found` with `errorCode: "NOT_FOUND"`.
- Step 4: `404 Not Found`, **identical in status and shape** to steps 1–3. A caller cannot tell
  "someone else's budget" from "no such budget" — that is the point, and it is why these are not
  `403`.
- As owner A afterwards, BUDGET_A is unchanged (still `80.00` from M6-11, still present).

**Result:** [ ] Pass   [ ] Fail

---

### M6-14 — Another student's category cannot be limited

**Covers:** UC-13; BR-02 (a limit can only reference the caller's own or a shared category).

**Preconditions:** Owner A has a **personal** category (module 3). Record its id as **CAT_A_PRIVATE**.

**Steps:**

1. As owner B: `POST /api/v1/budgets` with `categoryId: ${CAT_A_PRIVATE}`.
2. As owner B: `POST /api/v1/budgets` with a **shared** category id (e.g. `Academics`).

**Expected result:**

- Step 1: `404 Not Found` naming **no** field — the id is *not found* rather than
  *found-and-refused*, so owner B cannot learn that the category exists.
- Step 2: `201 Created`. A shared category is usable by every student, so a personal budget on it is
  correct.
- The refusal is enforced by the database as well as the service: `sp_validate_budget` raises
  `'BR-02: category belongs to another student'` for every caller.

**Result:** [ ] Pass   [ ] Fail

---

### M6-15 — Setting a limit raises no alert

**Covers:** UC-14; the central architectural fact of the module (§3.5).

**Preconditions:** Signed in as owner A. Pick a category with **no** budget this month and **no**
spending in it.

**Steps:**

1. `GET /api/v1/notifications` as owner A. Count the messages and record the count as **N**.
2. `POST /api/v1/budgets` as owner A setting a limit **far below** any spending (the category has
   none) — e.g. `{"categoryId": <cat>, "periodMonth": "<this month>", "limitAmount": 1.00}`.
3. `GET /api/v1/notifications` again.

**Expected result:**

- Step 2: `201 Created`, and the response reads `spentAmount: 0.00`, `consumedPct: 0`,
  `consumptionStatus: "ON_TRACK"` — even though the limit is `1.00`.
- Step 3: the count is **still N**. Setting a limit created **no** message, not even though the limit
  is trivially small.
- Recorded in `docs/OVERNIGHT_BLOCKERS.md`'s sibling documentation and pinned by the automated test
  `settingALimitRaisesNoAlert`. This is the behaviour that decides the whole design: there is no
  alert endpoint, and the client must not synthesise one.

**Result:** [ ] Pass   [ ] Fail

---

### M6-16 — A transaction crossing 80% raises exactly one `BUDGET_NEAR` message

**Covers:** UC-14 (be told when spending approaches the limit); BR-12 (once per threshold per month).

**Preconditions:** Owner A. A budget for a fresh category with **no spending and no alert** yet —
set the limit to `25.00`, so 80% is `20.00`.

**Steps:**

1. `POST /api/v1/transactions` as owner A, dated **today**, amount **`19.00`**, in that category.
2. `GET /api/v1/notifications`.
3. `POST /api/v1/transactions` again, dated **today**, amount **`1.00`** more (`20.00` total = 80%).
4. `GET /api/v1/notifications` again.
5. `POST /api/v1/transactions` again, dated **today**, amount **`2.00`** more (`22.00` total = 88%).
6. `GET /api/v1/notifications` again.

**Expected result:**

- Step 2: **no** new message. `19.00 / 25.00 = 76%`, below 80%.
- Step 4: **exactly one** new message, with:
  - `type: "BUDGET_NEAR"`,
  - `title: "Approaching budget limit: <category name>"`,
  - `body` of the form `You have used 80.00% of your <category> budget (20.00 of 25.00).`
    — the percentage is the procedure's `CAST(v_pct AS CHAR)` of the rounded value, so it carries
    two decimals; the amounts carry the column's two,
  - `linkUrl: "/budgets"`, `refEntityType: "BUDGET"`, `refEntityId` equal to the budget's id,
  - `isRead: false` and **no `readAt` key**.
- Step 6: the count is **unchanged** from step 4. `88%` is still `NEAR` and BR-12 has already told
  this student once — see §5.1's second half.
- **The prose is the database's, verbatim.** Do not expect the client's wording (the frontend's
  current mock builds its own sentence — §5.3). The title and body above come from
  `sp_check_budget_alerts`'s `CONCAT`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-17 — A further transaction crossing 100% raises exactly one `BUDGET_EXCEEDED` message

**Covers:** UC-14 (be told when the limit is passed); BR-12 (the second and final threshold).

**Preconditions:** Continue from M6-16: the category stands at `22.00` of `25.00`, with one `NEAR`
message.

**Steps:**

1. `POST /api/v1/transactions` as owner A, dated **today**, amount **`4.00`** (`26.00` total = 104%).
2. `GET /api/v1/notifications`.
3. `POST /api/v1/transactions` again, dated **today**, amount **`5.00`** more (`31.00` = 124%).
4. `GET /api/v1/notifications` again.

**Expected result:**

- Step 2: **exactly one** new message:
  - `type: "BUDGET_EXCEEDED"`,
  - `title: "Budget exceeded: <category name>"`,
  - `body` of the form `You have spent 26.00 of 25.00 (104.00%) on <category>.`
- Step 4: **no** new message. The `EXCEEDED` threshold has already been recorded for this category
  this month, and `EXCEEDED` is checked **before** `NEAR`, so the `NEAR` threshold is not revisited
  either.
- The category's budget row now reads `consumptionStatus: "EXCEEDED"`.
- **A client that recomputed alerts from current state would show a message on every reload.** Check
  that it does not: reload `GET /api/v1/notifications` several times and confirm the count is stable.
  This is what a real notification (a row written once) gives you and a synthesised one cannot.

**Result:** [ ] Pass   [ ] Fail

---

### M6-18 — BR-12: at most two messages per category per month

**Covers:** BR-12 in full; the unique key and `INSERT IGNORE`.

**Preconditions:** Continue from M6-17 (one `NEAR` + one `EXCEEDED` for the category, this month).

**Steps:**

1. Record the total notification count for owner A.
2. `POST /api/v1/transactions` several more times in the same category, dated **today**, with
   amounts that would each cross a threshold (e.g. `10.00` three times).
3. `GET /api/v1/notifications`.
4. **Soft-delete** enough of the category's transactions to bring the total back **below** 80%, then
   record a new expense that crosses 80% again.
5. `GET /api/v1/notifications` again.
6. Delete the budget, re-create it with the same limit, then record an expense that crosses the
   threshold once more.

**Expected result:**

- Step 3: the total count is **exactly as in step 1**. A category cannot produce a third budget
  message in a month, however many times it is crossed. Enforced by
  `uk_alert_budget_threshold (budget_id, threshold_type)` and `INSERT IGNORE`, not by the application.
- Step 5: **still no** new message. Moving the position back below a threshold does not re-arm it —
  the alert log row remains, and BR-12 says "once per threshold per month", not "once per crossing".
  This is the case a tester most often reports as a bug; it is correct (§5.1).
- Step 6: **one new** message. Deleting the budget cascaded its alert-log rows away
  (`fk_alert_budget ... ON DELETE CASCADE`), so the re-created limit is genuinely new and may alert
  again.
- **Also correct:** deleting a budget does **not** withdraw the notifications it already produced.
  The student was told; that record stays. Only the *alert log* cascades, not the messages.

**Result:** [ ] Pass   [ ] Fail

---

### M6-19 — Spending in a category with no limit raises nothing

**Covers:** UC-14; the procedure's `IF v_budget_id IS NOT NULL` guard.

**Preconditions:** Owner A. A category with **no** budget this month (e.g. `Miscellaneous`).

**Steps:**

1. `GET /api/v1/notifications` and record the count.
2. `POST /api/v1/transactions` in that category for `500.00`, dated **today**.
3. `GET /api/v1/notifications`.

**Expected result:**

- Step 3: **no** new message. With no limit there is no percentage, so no threshold can be crossed.
  The procedure looks the budget up first and does nothing when it finds none.
- `GET /api/v1/budgets` shows no row magically appeared for that category. Spending does not create a
  budget.

**Result:** [ ] Pass   [ ] Fail

---

### M6-20 — The message list: newest first, read and unread together

**Covers:** UC-14 (list the messages); the deterministic order.

**Preconditions:** Owner A has at least three messages from M6-16…M6-18.

**Steps:**

1. `GET /api/v1/notifications` as owner A.
2. `POST /api/v1/notifications/${someId}/read` on the **oldest** one.
3. `GET /api/v1/notifications` again.

**Expected result:**

- Step 1: an array ordered **newest `createdAt` first**, with ties broken by `id` descending so the
  order is stable across calls (two messages written in the same second still have a fixed order).
- Read **and** unread messages are both present — there is no read-state filter and no unread-only
  endpoint.
- Each entry has **no `userId`** key: the list is scoped to the caller by the query, not by a field.
- Step 3: the marked message is still in the same **position** (its `createdAt` did not change) and
  now reads `isRead: true` with a `readAt`. Ordering is by `createdAt`, not by read state.
- Owner B's list contains none of these messages.

**Result:** [ ] Pass   [ ] Fail

---

### M6-21 — Reading one message does not mark it read

**Covers:** UC-14; the deliberate separation of "reading" from "acknowledging".

**Preconditions:** An **unread** message id for owner A — record it as **NOTIF_U**.

**Steps:**

1. `GET /api/v1/notifications/${NOTIF_U}` as owner A.
2. `GET /api/v1/notifications` and find the same message.

**Expected result:**

- Step 1: `200 OK` with the full message, `isRead: false` and **no `readAt` key**.
- Step 2: it is **still** `isRead: false`. Opening a message does not acknowledge it; only
  `POST /{id}/read` does. A client that wants the badge to clear must call the read endpoint
  explicitly.
- `GET /api/v1/notifications/999999999` answers `404 NOT_FOUND`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-22 — Mark a message read: the flag and the timestamp arrive together

**Covers:** UC-14 B4; `ck_notif_read` (the two fields always agree).

**Preconditions:** An unread message id for owner A — **NOTIF_U** from M6-21.

**Steps:**

1. `POST /api/v1/notifications/${NOTIF_U}/read` with an **empty** body as owner A.
2. Note the `readAt` value.

**Expected result:**

- `200 OK`, returning the message (not a bare `204`): `isRead: true` and a `readAt` **in the same
  response**, so a client needs no follow-up call to render the "read" state.
- `readAt` is a local time with **no `Z` and no offset** — `"2026-09-25T14:18:12"`. It is the
  database's `NOW()` in `+07:00`.
- `readAt` is **at or after** `createdAt`. It can never be before it.
- `isRead: true` and a missing `readAt`, or `isRead: false` with a `readAt`, are **impossible** —
  `ck_notif_read` refuses both combinations at the database level.
- The other messages are untouched and still unread.

**Result:** [ ] Pass   [ ] Fail

---

### M6-23 — Marking read twice keeps the first timestamp

**Covers:** UC-14 B4; the read transition is one-way and does not move.

**Preconditions:** NOTIF_U is now read, with the `readAt` recorded in M6-22.

**Steps:**

1. Wait at least a few seconds (or a minute, so a second `NOW()` would visibly differ).
2. `POST /api/v1/notifications/${NOTIF_U}/read` again.
3. Compare the `readAt` with the value from M6-22.

**Expected result:**

- `200 OK` — a repeated acknowledgement is **not** an error. Marking read is idempotent.
- `readAt` is **exactly the same value** as the first time. The procedure's `AND is_read = 0`
  predicate means a second call changes nothing, so "when the student read it" is preserved rather
  than moved later.
- `isRead` is still `true`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-24 — There is no way to un-read, create or delete a message

**Covers:** UC-14 B5; the record of having been warned is not erasable; no client-authored messages.

**Preconditions:** NOTIF_U is read.

**Steps:**

1. `PATCH /api/v1/notifications/${NOTIF_U}` with `{"isRead": false}`.
2. `PUT /api/v1/notifications/${NOTIF_U}/read`.
3. `DELETE /api/v1/notifications/${NOTIF_U}`.
4. `POST /api/v1/notifications` with a body containing `{"type": "SYSTEM", "title": "hi"}`.
5. `GET /api/v1/notifications/${NOTIF_U}`.

**Expected result:**

- Steps 1–4: each answers a **4xx**. The paths and methods do not exist (an unsupported method on a
  known path answers `400`, a genuinely unknown path `404`) — the exact code is less important than
  that none of them succeeds.
  - `PATCH` on a notification cannot move the field: the read-state change is one `UPDATE` that also
    proves ownership (`user_id` in the same predicate). A body able to set `isRead` would move the
    row without that predicate *and* would allow un-reading.
  - `POST /notifications` does not exist: every notification is written by the procedure that owns it.
    A student able to write one could write one **to somebody else**.
  - `DELETE` does not exist: a student cannot erase the record of having been warned about a limit.
- Step 5: NOTIF_U is **still read** and **still present**. None of steps 1–4 changed anything.
- The OpenAPI document at `/api-docs` lists exactly the three notification endpoints — this is
  machine-checked by `OpenApiContractIT`.

**Result:** [ ] Pass   [ ] Fail

---

### M6-25 — A retired category cannot be limited

**Covers:** UC-13; BR-07 (a retired category cannot be filed against).

**Preconditions:** Owner A has a **personal** `EXPENSE` category (module 3). Mark it retired with
`PATCH /api/v1/categories/{id}` and `{"isActive": false}`, then try to use it.

**Steps:**

1. `POST /api/v1/budgets` as owner A with the retired category's id, for a month with no limit on it.
2. Re-enable the category (`{"isActive": true}`).
3. `POST /api/v1/budgets` again with the same category and month.

**Expected result:**

- Step 1: `400 Bad Request` with `fieldErrors` naming `categoryId`. Enforced by `sp_validate_budget`
  for every caller, not just this module.
- Step 3: `201 Created` — re-enabling the category makes it usable again.
- Retiring a category does **not** delete, change or hide any existing budget; it only blocks new ones
  and edits (M6-26).

**Result:** [ ] Pass   [ ] Fail

---

### M6-26 — A budget whose category was retired afterwards cannot be changed (OB-011)

**Covers:** UC-13; BR-07 as the **update** trigger applies it. **This is the module's open item.**

**Preconditions:** A budget **BUDGET_F** on a **personal** `EXPENSE` category, set while the category
was active. Note its `limitAmount`.

**Steps:**

1. Retire the category: `PATCH /api/v1/categories/{id}` with `{"isActive": false}`.
2. `PATCH /api/v1/budgets/${BUDGET_F}` with `{"limitAmount": 250.00}`.
3. `GET /api/v1/budgets/${BUDGET_F}`.

**Expected result:**

- Step 2: `409 Conflict` with `errorCode: "CATEGORY_RETIRED"` — **not** the generic `DATA_CONFLICT`.
  The message names **both** remedies:
  "This budget's category has been retired, so the limit cannot be changed. Restore the category, or
  remove the budget if it is no longer needed."
- Step 3: the limit is **unchanged**. The refusal rolled back with its transaction; nothing was
  half-written.
- **Why this happens:** `trg_budgets_before_update` calls `sp_validate_budget` on **every** update, and
  that procedure has four `SIGNAL`s — including `'BR-07: category has been disabled'` — with **no**
  "skip the active check" escape. Unlike module 4's transactions (whose trigger has such an escape),
  a budget under a retired category is frozen, exactly as module 5's recurring rules are.
- This is recorded as **OB-011** in `docs/OVERNIGHT_BLOCKERS.md` for the owner's decision. It is a
  schema-owned limitation, **not** a missing feature, and §4 forbids changing the procedure or the
  trigger. Report it as a finding, not as a failure of this procedure.
- See §5.2.

**Result:** [ ] Pass   [ ] Fail

---

### M6-27 — Remedy 1: re-enable the category, change the budget, retire it again

**Covers:** UC-13; BR-07 (the first of the two remedies the error message names).

**Preconditions:** Continue from M6-26: BUDGET_F is frozen on a retired category.

**Steps:**

1. Re-enable the category: `PATCH /api/v1/categories/{id}` with `{"isActive": true}`.
2. `PATCH /api/v1/budgets/${BUDGET_F}` with `{"limitAmount": 250.00}`.
3. Retire the category again (`{"isActive": false}`).
4. Retry step 2's `PATCH`.

**Expected result:**

- Step 2: `200 OK` with `limitAmount: 250.00`. Once the category is active the trigger's check passes.
- Step 4: `409 CATEGORY_RETIRED` again. The freeze returns with the retirement — it is a condition on
  the category, not a one-time flag.
- The stored limit stays at `250.00` throughout; the refusals in step 4 change nothing.

**Result:** [ ] Pass   [ ] Fail

---

### M6-28 — Remedy 2: a frozen budget can still be *removed*

**Covers:** UC-13; BR-07 (the second remedy); the deliberate asymmetry with delete.

**Preconditions:** A budget frozen on a retired category (M6-26).

**Steps:**

1. `GET /api/v1/budgets` as owner A and confirm the frozen budget is **present** with its consumption.
2. `DELETE /api/v1/budgets/${BUDGET_F}`.
3. `GET /api/v1/budgets` again.
4. Re-create a limit on the same category with `POST`.

**Expected result:**

- Step 1: the budget is still there and still reports `spentAmount`, `consumedPct` and a
  `consumptionStatus`. Retirement **freezes** the budget; it does not delete or hide it.
- Step 2: `204 No Content`. **This is the asymmetry, and it is deliberate**: a `DELETE` fires no
  `BEFORE UPDATE` trigger, so removal is accepted where an edit is not. Without it the only exit from
  the frozen state would be restoring the category.
- Step 3: the row is gone; the transactions in that category are untouched.
- Step 4: `400` naming `categoryId` while the category stays retired — a **new** limit still cannot be
  created on it. Removing an existing one is allowed; adding one is not. Both are correct.

**Result:** [ ] Pass   [ ] Fail

---

### M6-29 — No token, a bad token, an administrator token, and another student's message

**Covers:** §15 (role `STUDENT` enforced server-side; a token is required on every endpoint); BR-02.

**Preconditions:** A message id belonging to owner A — **NOTIF_A**.

**Steps:**

1. `GET /api/v1/budgets` with **no** `Authorization` header.
2. `GET /api/v1/budgets` with `Authorization: Bearer not.a.token`.
3. `GET /api/v1/budgets` with `Authorization: Bearer ${ADMIN_JWT}`.
4. `GET /api/v1/notifications` with `${ADMIN_JWT}`.
5. `GET /api/v1/notifications/${NOTIF_A}` as owner B (`${USER_B_JWT}`).
6. `POST /api/v1/notifications/${NOTIF_A}/read` as owner B.

**Expected result:**

- Step 1: `401 Unauthorized` with `errorCode: "UNAUTHENTICATED"`.
- Step 2: `401 Unauthorized`. A malformed token is not a `400` and no stack trace is returned.
- Steps 3–4: `403 Forbidden`. An administrator is **not** a student; the role rule is on every path
  in both groups, and it is enforced by Spring Security *before* the service.
- Steps 5–6: `404 Not Found`, identical in shape to a message that does not exist. The `user_id`
  predicate sits inside the same statement, so nothing changes even if the check were bypassed.
- As owner A afterwards, NOTIF_A is unchanged (still whatever state M6-22…M6-24 left it in).

**Result:** [ ] Pass   [ ] Fail

---

### M6-30 — Field validation the tester should not be surprised by

**Covers:** UC-13 validation; the check constraints; the month format.

**Steps:** Send each of the following as owner A and record the status and `fieldErrors`:

| # | Request | Expected |
|---|---|---|
| a | `POST /budgets` with `limitAmount: 0` | `400`, `fieldErrors` naming `limitAmount` (`ck_budget_limit` and `@DecimalMin(exclusive)` both require `> 0`) |
| b | `POST /budgets` with `limitAmount: -5` | `400`, `limitAmount` |
| c | `POST /budgets` with `limitAmount: 10.005` (three decimals) | `400`, `limitAmount` (`@Digits(fraction = 2)`) |
| d | `POST /budgets` with `periodMonth: "2026-9"` (one digit) | `400`, `periodMonth` — the pattern anchors four digits |
| e | `POST /budgets` with `periodMonth: "2026-13"` | `400`, `periodMonth` — well-formed but impossible, refused as a field error, **not** a `500` from a parser |
| f | `POST /budgets` with `periodMonth: "2026-09-15"` | `400`, `periodMonth` — a month is not a date; refused rather than silently truncated to the 1st |
| g | `POST /budgets` with `categoryId` missing | `400`, `categoryId` (`@NotNull`) |
| h | `POST /budgets` with `limitAmount: "abc"` | `400`, `limitAmount` — a non-numeric value is a field error |
| i | `POST /budgets` with a well-formed body to a **nonexistent** category id | `404 NOT_FOUND`, naming no field |

**Expected result:**

- Every row answers as stated, each with `errorCode: "VALIDATION_ERROR"` where a field is named, and
  **no** stack trace, SQL text or schema identifier in any response.
- a–c also hold at the database level (`ck_budget_limit`), so a hand-written `INSERT` with `0` is
  refused too — the API is not the only thing standing between the student and a broken row.
- The `field` value is `categoryId`, `periodMonth` or `limitAmount` exactly — the JSON name, not the
  column name.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Two behaviours a tester is most likely to misread

Both are deliberate, documented behaviour. Read this section before marking anything in §4 as a
failure. Neither is a defect in the module.

> ### 5.1 A budget write raises no alert; the alert belongs to the transaction
>
> This is the module's central fact and the source of nearly every "I lowered my limit and nothing
> happened" report.
>
> **What actually happens, exactly:**
>
> - `sp_check_budget_alerts` is called from the **transactions** insert and update triggers. Setting,
>   changing or removing a **budget** calls it never.
> - So a limit can be set below spending that has already happened and the row will read `EXCEEDED`
>   **immediately**, while the message list does not change at all. The screen says "over budget"; the
>   inbox is silent. Both are correct.
> - **An *edited* transaction can raise an alert too** — the update trigger calls the procedure as
>   well, so correcting a past expense upward can cross a threshold.
> - **BR-12 caps a category at two messages per month**, one `NEAR` and one `EXCEEDED`, enforced by
>   `uk_alert_budget_threshold` and `INSERT IGNORE`. A third crossing writes nothing; moving the
>   position back below a threshold and re-crossing it writes nothing **in the same month**. The rule
>   is "alert once per threshold per month", not "alert on every crossing".
> - `EXCEEDED` is checked **before** `NEAR`, so once a category is over its limit the `NEAR`
>   threshold is never revisited.
>
> **Consequences a tester will see, all of them correct:**
>
> - Setting a limit and expecting a message: none arrives (M6-15).
> - Lowering a limit below spending: the status changes, no message arrives (M6-10).
> - Re-crossing a threshold in the same month: no second message (M6-18).
> - Deleting a budget does **not** withdraw the messages it already produced — only the hidden alert
>   log cascades. The student was told.
> - Deleting the budget, re-creating it, and crossing again **does** alert: the cascade made the log
>   row go away, so the new limit is genuinely new (M6-18 step 6).
>
> **Do not report "no alert after I changed the limit" as a bug.** It is pinned by
> `settingALimitRaisesNoAlert` and `loweringALimitRaisesNoSecondAlert`, and it is documented in
> `docs/api/budgets.md` §5 and `docs/modules/MODULE_06_BUDGET.md` §5.1.
>
> **And do not expect the client's wording.** The title and body are the procedure's `CONCAT`
> (M6-16, M6-17 quote them). A frontend that builds its own sentence would produce a second, drifting
> source of user-facing text and could not represent "told once" — `docs/api/notifications.md` §12.1.

> ### 5.2 A budget whose category was retired cannot be changed **at all** (OB-011)
>
> Retiring a category in module 3 **freezes every budget filed under it** — exactly as it freezes
> module 5's recurring rules (OB-009). `PATCH /api/v1/budgets/{id}` answers:
>
> ```json
> {
>   "status": 409,
>   "errorCode": "CATEGORY_RETIRED",
>   "message": "This budget's category has been retired, so the limit cannot be changed. Restore the category, or remove the budget if it is no longer needed.",
>   "path": "/api/v1/budgets/{id}"
> }
> ```
>
> This is enforced by the **database**: `trg_budgets_before_update` re-validates the category on every
> update and `sp_validate_budget` has no "skip the active check" escape — unlike the transaction
> trigger, which does. It is a schema-owned limitation recorded as OB-011, not a missing feature.
>
> **The two remedies that are actually accepted:**
>
> 1. **Re-enable the category** (module 3: `PATCH /api/v1/categories/{id}` with `{"isActive": true}`),
>    change the budget, retire it again. (M6-27)
> 2. **Remove the budget** — `DELETE` is **still accepted** on a frozen budget. (M6-28)
>
> **The asymmetry between remedies 1 and 2 is deliberate and worth stating:** a `DELETE` fires no
> `BEFORE UPDATE` trigger, so removal works where an edit does not. That is the one action which
> leaves the category alone, and it has to remain the student's way out without restoring the
> category. Removing an existing limit is allowed; **creating a new one** on the retired category is
> not (M6-28 step 4).
>
> **Also correct, and not a defect:** retiring a category does **not** delete, change or hide an
> existing budget, and does **not** stop its consumption being shown. It only freezes **edits**.
>
> Exercise this in **M6-25**, **M6-26**, **M6-27** and **M6-28**.

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M6-01 | UC-13 set a monthly limit; BR-11 (an expense category, one per month); no alert on a budget write |
| M6-02 | UC-13 `periodMonth` defaults to the current month in `Asia/Ho_Chi_Minh` |
| M6-03 | UC-13; BR-11 (only an expense category) |
| M6-04 | UC-13; BR-11 (one limit per category per month); the named `BUDGET_ALREADY_EXISTS` remedy |
| M6-05 | UC-13; BR-11 (uniqueness is per month) |
| M6-06 | UC-13 (consumption computed per read); BR-09 (deleted rows stop counting); a negative `remainingAmount` |
| M6-07 | UC-13 read one; `404 NOT_FOUND` vs `400` for a malformed path parameter |
| M6-08 | UC-13 list, empty state (`200 []`) |
| M6-09 | UC-13 change a limit; an edit keeps the row's identity |
| M6-10 | UC-14; a limit moved below spending raises no alert (the reconcilable state) |
| M6-11 | UC-13 §7.5 (`categoryId`, `periodMonth` and the derived figures are not editable) |
| M6-12 | UC-13 remove a limit; `204`; the transactions are untouched |
| M6-13 | UC-13; BR-02 (another student's budget is unreachable; no identifier probing) |
| M6-14 | UC-13; BR-02 (a limit may only reference the caller's own or a shared category) |
| M6-15 | UC-14; a budget write raises no alert (the module's central fact) |
| M6-16 | UC-14 crossing the near threshold; BR-12; the procedure's verbatim prose |
| M6-17 | UC-14 crossing the exceeded threshold; BR-12; `EXCEEDED` checked before `NEAR` |
| M6-18 | UC-14; BR-12 (at most two per category per month); the alert log cascades but the messages do not; a re-created limit alerts again |
| M6-19 | UC-14; spending with no limit raises nothing |
| M6-20 | UC-14 list; deterministic order (`createdAt` desc, `id` desc); no read-state filter; no `userId` |
| M6-21 | UC-14 read one; reading does **not** mark read |
| M6-22 | UC-14 B4; `isRead` and `readAt` arrive together; `ck_notif_read` |
| M6-23 | UC-14 B4; marking read is idempotent and keeps the first timestamp |
| M6-24 | UC-14 B5; read state is one-way; no create, no delete, no un-read |
| M6-25 | UC-13; BR-07 (a retired category cannot be limited) |
| M6-26 | UC-13; BR-07 (a retired category freezes its budgets) — **OB-011** |
| M6-27 | UC-13; BR-07 (remedy 1: re-enable the category, change the budget, retire again) |
| M6-28 | UC-13; BR-07 (remedy 2: a frozen budget can still be removed; the delete asymmetry) |
| M6-29 | §15 (role `STUDENT` enforced server-side; a token is required on all eight endpoints); BR-02 |
| M6-30 | UC-13 validation; BR-11 (`ck_budget_limit`); the `YYYY-MM` month format and `ck_budget_month` |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass. None of these is reachable by hand through
`/api/v1/budgets/**` or `/api/v1/notifications/**` alone.

| Not covered | Why |
|---|---|
| Concurrency (two simultaneous sets; a lowered limit racing a record) | Racy by nature and unsafe to reproduce by hand against a shared stack. Pinned by `twoSimultaneousSetsProduceOneLimit`, `twoSimultaneousChangesSettleOnOneValue`, `twoSimultaneousDeletesProduceOneDeletion` and `aLoweredLimitRacingARecordSettlesConsistently`. The last asserts only what is guaranteed — the alert's **content**, not its existence. |
| The alert-deduplication race itself (two transactions crossing one threshold at once) | It is settled by module 4's write path — `uk_alert_budget_threshold` and `INSERT IGNORE` inside the transaction trigger. Module 6 writes no alert, so it has no concurrency test of its own for this and `docs/api/notifications.md` §14 says so explicitly. |
| The four `SIGNAL` branches of `sp_validate_budget` as raised by the trigger | The service pre-checks the two a client can cause, so the signalled branch is nearly unreachable through the API. Verified directly by the unit test `BudgetWriteFailureTest` against the real exception shapes. |
| A disabled account's token (`401 UNAUTHENTICATED`) | Disabling an account is an administrator action (module 11); the student-reachable behaviour is covered by M6-29. |
| A revoked session / expired token | Needs module 1's sign-out or a token aged past `expiresIn`. |
| TIP, INSIGHT_READY, ANNOUNCEMENT and SYSTEM notifications | Their generators are later modules (UC-17, UC-18, UC-21). They are readable here because the column is a MySQL `ENUM` and an unmapped member would fail to load the whole list; the budget types are what UC-14 asks for. |
| `RECURRING_POSTED` notifications | Written by the scheduler in module 5, not by any endpoint. Their budget-alert side effect is covered in module 5's procedure; OB-010 records that a resumed pause can produce a burst of past-dated alerts. |
| The scheduler and the transaction triggers (module 4) | Covered by modules 4 and 5. This document uses a recorded expense as the trigger and asserts the resulting message, nothing about the scheduler. |
| Changing the thresholds | An administrator action (module 11). §3.6 states the seeded values; a student has no endpoint that accepts a threshold. |
| The 500-level failure path | `BudgetWriteFailure` rethrows an unrecognised failure as a `500` with a generic message. Forcing it by hand needs a fault injection the API does not offer. |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/budgets.md`,
`docs/api/notifications.md`, `docs/modules/MODULE_06_BUDGET.md`, `docs/OVERNIGHT_BLOCKERS.md`,
`docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md` or the implementation under
`backend/src/main/java/com/campuscoin/budget/`.
