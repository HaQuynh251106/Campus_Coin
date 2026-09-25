# Module 05 — Recurring Rules (UC-09) — Manual Test Procedure

**Scope:** endpoints 22–26, `/api/v1/recurring-rules/**`, role `STUDENT` only.
**Sources of truth for this procedure:** `docs/api/recurring.md` (contract, §9 lifecycle, §10 delete,
§11 scheduler), `docs/modules/MODULE_05_RECURRING.md` (§5.8–§5.12),
`docs/OVERNIGHT_BLOCKERS.md` (OB-009, OB-010), `docs/api/API_INVENTORY.md` (endpoints 22–26),
`docs/CREDENTIALS.md` (seeded accounts), and the implementation under
`backend/src/main/java/com/campuscoin/recurring/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 22 | `GET` | `/api/v1/recurring-rules` | `200` array |
| 23 | `GET` | `/api/v1/recurring-rules/{id}` | `200` rule |
| 24 | `POST` | `/api/v1/recurring-rules` | `201` rule |
| 25 | `PATCH` | `/api/v1/recurring-rules/{id}` | `200` rule |
| 26 | `DELETE` | `/api/v1/recurring-rules/{id}` | `204`, no body |

Pausing, resuming and ending are **not** separate endpoints. All three are `PATCH` of `status`, and
`ENDED` is final. There is no `PUT`, no `?status=` filter, no pagination, no
`/recurring-rules/{id}/occurrences`, and no "run the scheduler now" endpoint. An endpoint that is not
in the table above does not exist — if a step here asks you to call one, the document is wrong, not
the server.

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

There are no other placeholders in this document. Where a numeric id appears (a `ruleId`, a
`categoryId`), it is an **example**; always use the id you recorded in your own run.

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

Every date in this module is a plain calendar date, `"YYYY-MM-DD"`, with no time and no zone. "Today"
is judged in **`Asia/Ho_Chi_Minh` (`+07:00`)** — the same offset the database session is pinned to,
and the clock the scheduler uses. The concrete dates below (2026) are examples; substitute a
comparable past or future date for the day you run this. A rule's dates **may** be in the future: a
rule is a schedule, not a record, so the "no future dates" restriction that applies to module 4's
transactions does not apply here.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with data (`db/06_demo.sql`), including **two recurring rules**, both
  `ACTIVE` and both with `nextRunDate` in the month after the seed month, so nothing has been posted
  by them yet. Alex also has the shared default categories available.
- **Bella Tran** is the seeded **empty** account: for the ownership cases she creates her own
  category and rule through the API.
- Shared default categories (`Allowance`, `Food`, `Subscriptions`, …) are visible to every student;
  a student's own categories are visible only to them.
- Numeric category ids are whatever `GET /api/v1/categories` returns. Use it to pick a category and
  to check its `type` (`INCOME` / `EXPENSE`) and `isActive` before you use its id.

### 3.2 Authentication

All five endpoints require `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403`. A missing, malformed, expired or revoked
token answers `401`.

### 3.3 What a rule looks like

A rule response carries: `id`, `categoryId`, `categoryName`, `type`, `amount`, `frequency`,
`intervalCount`, `startDate`, `nextRunDate`, `status`, and — when they have a value — `categoryIcon`,
`categoryColor`, `description`, `endDate`, `lastRunDate`. A field with no value is **absent from the
JSON, not `null`**. `type` is the **category's** type and is read-only. `amount` always shows exactly
two decimals (`8.00`).

### 3.4 The scheduler: how posting is observed (and how it is not)

**Posting is a background job, not an API action.** `sp_post_recurring_transactions` runs once a day
shortly after midnight in `Asia/Ho_Chi_Minh`, driven by the application:

| Property | Effect |
|---|---|
| `campuscoin.recurring.scheduler.enabled` (env `RECURRING_SCHEDULER_ENABLED`) | Enables/disables the job. Default `true`; set to `false` to stop it entirely. |
| `campuscoin.recurring.scheduler.cron` (env `RECURRING_SCHEDULER_CRON`) | The run time. Default `0 5 0 * * *` (00:05:00 daily), pinned to `Asia/Ho_Chi_Minh`. |

The job selects only rules with `status = 'ACTIVE'` whose category is still active and whose
`nextRunDate` is on or before today in `+07:00`. It posts one transaction per missed period, catches
up on periods missed during downtime, and never creates a row dated after today.

**There is no endpoint that runs the scheduler.** The contract says so explicitly, and the OpenAPI
document at `/api-docs` lists exactly the five endpoints in §1. Do not call
`POST /recurring-rules/{id}/run` or anything of the kind — it does not exist, and the module's design
is that a client cannot trigger posting for every student in the system.

The only two supported ways to make a rule post without waiting until 00:05 are:

1. **Move the rule's `nextRunDate` backwards** with `PATCH` so a period is already due, then wait for
   the next scheduled run. This is the API-side action the contract documents.
2. **Re-time the run by configuration** — set `RECURRING_SCHEDULER_CRON` to a near-future minute
   (for example every minute) and restart the backend, so "the next scheduled run" arrives within a
   minute instead of at 00:05. The module documents the job as re-timed by configuration; this is a
   configuration change on your own stack, not an API call.

Only M5-24 and M5-25 need a scheduler run. Every other case can be completed without one.

### 3.5 Reading the transactions a rule produced

There is no per-rule transactions endpoint. Read them through module 4:
`GET /api/v1/transactions?from=<rule startDate>&to=<today>`, then look for rows with
`"source": "RECURRING"`. Note that module 4's default upper bound is **today**, so omit nothing if
you expect a past-dated row.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M5-01 — Create a recurring rule

**Covers:** UC-09 (set up a repeating income or expense); BR-08 (a rule's dates may be in the future).

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`). You have a non-retired `categoryId` from
`GET /api/v1/categories`.

**Steps:**

1. `POST /api/v1/recurring-rules` with `Authorization: Bearer ${USER_A_JWT}` and body:

   ```json
   {
     "categoryId": 10,
     "amount": 25.00,
     "description": "Gym membership",
     "frequency": "MONTHLY",
     "intervalCount": 1,
     "startDate": "2026-11-01"
   }
   ```

   (Use a real category id and a start date a few weeks ahead so nothing is due yet.)

**Expected result:**

- `201 Created`.
- The body is the rule as stored. It contains `id` (record it as **RULE_A**), `categoryId` equal to
  the one sent, `categoryName`, `type` (the category's type), `amount: 25.00` (two decimals),
  `description` trimmed, `frequency: "MONTHLY"`, `intervalCount: 1`, `startDate: "2026-11-01"`,
  `nextRunDate: "2026-11-01"`, `status: "ACTIVE"`.
- `endDate` and `lastRunDate` keys are **absent** (nothing has run, no end date set).
- No `userId`, no row timestamps, and no `dayOfMonth` / `dayOfWeek` keys appear.
- Nothing was posted: `GET /api/v1/transactions?to=<today>` shows no new `RECURRING` row.

**Result:** [ ] Pass   [ ] Fail

---

### M5-02 — The `type` is the category's, and a client-sent one is ignored

**Covers:** UC-09 create; BR-05 (the type is the category's and is read-only); server-owned creation
state.

**Preconditions:** As M5-01. Pick a category whose `type` is `EXPENSE`.

**Steps:**

1. `POST /api/v1/recurring-rules` as owner A, deliberately sending the fields the server owns:

   ```json
   {
     "categoryId": 10,
     "amount": 8.00,
     "frequency": "MONTHLY",
     "startDate": "2026-11-01",
     "type": "INCOME",
     "status": "PAUSED",
     "userId": 999
   }
   ```

2. Record the returned `id` as **RULE_B**.

**Expected result:**

- `201 Created`, with `"type": "EXPENSE"` — the **category's** type, not the `"INCOME"` that was sent.
- `"status": "ACTIVE"` — the server sets `ACTIVE` and the sent `"PAUSED"` changes nothing. A rule
  cannot be created already stopped.
- No `userId` key in the response; the rule is owned by the token's account, and sending `userId`
  changes nothing.
- `dayOfMonth` and `dayOfWeek` are neither accepted nor returned.

**Result:** [ ] Pass   [ ] Fail

---

### M5-03 — `nextRunDate` defaults to `startDate`

**Covers:** UC-09 create; BR-08 (a rule's dates may be in the future).

**Preconditions:** As M5-01.

**Steps:**

1. `POST /api/v1/recurring-rules` as owner A, **omitting** `nextRunDate`:

   ```json
   {
     "categoryId": 10,
     "amount": 12.00,
     "frequency": "MONTHLY",
     "startDate": "2026-11-15"
   }
   ```

   Record the id as **RULE_C**.

2. `POST /api/v1/recurring-rules` again, this time **sending** `nextRunDate` later than `startDate`:

   ```json
   {
     "categoryId": 10,
     "amount": 12.00,
     "frequency": "MONTHLY",
     "startDate": "2026-11-15",
     "nextRunDate": "2027-02-15"
   }
   ```

**Expected result:**

- Step 1: `201`, with `nextRunDate` **equal to** `startDate` (`2026-11-15`). The rule is due on the
  day it starts.
- Step 2: `201`, with `nextRunDate: "2027-02-15"` as sent and `startDate` still `2026-11-15`. A
  `nextRunDate` later than the start schedules the first occurrence later without firing on creation
  — this is how a rule is prepared in advance.
- `intervalCount` is `1` in both, because it was omitted and defaults to `1`.
- Future dates are accepted here (unlike module 4's transactions, where BR-08 forbids them).

**Result:** [ ] Pass   [ ] Fail

---

### M5-04 — The list: every status is included, soonest to run first

**Covers:** UC-09 (list the student's rules; a paused or ended rule stays visible); deterministic
ordering.

**Preconditions:** Signed in as owner A. You will create two throwaway rules in this case.

**Steps:**

1. Create a rule **RULE_L1** with `nextRunDate` about three weeks ahead.
2. Create a rule **RULE_L2** with `nextRunDate` about five weeks ahead.
3. `PATCH /api/v1/recurring-rules/{RULE_L1}` with `{"status": "PAUSED"}` → expect `200`.
4. `PATCH /api/v1/recurring-rules/{RULE_L2}` with `{"status": "ENDED"}` → expect `200`.
5. `GET /api/v1/recurring-rules` with `Authorization: Bearer ${USER_A_JWT}`.
6. `GET /api/v1/recurring-rules?status=ACTIVE` (to confirm there is no filter).

**Expected result:**

- Step 5: `200` and a JSON **array**.
- Both **RULE_L1** (with `"status": "PAUSED"`) and **RULE_L2** (with `"status": "ENDED"`) are present.
  Hiding them would leave a paused rule with no way to be found again.
- The array is ordered by `nextRunDate` ascending, ties broken by `id` ascending, so RULE_L1 (nearer)
  comes before RULE_L2 and two identical calls return the same order.
- Every element has the same shape as §3.3; a rule that has never run has **no** `lastRunDate` key.
- Step 6: the endpoint documents **no** parameters, and the same full list is returned — the client
  filters on `status` itself.

**Result:** [ ] Pass   [ ] Fail

---

### M5-05 — A student with no rules gets an empty array, not `404`

**Covers:** UC-09 list, empty state. (Relates to UC-18 A1, "new student with no data".)

**Preconditions:** Signed in as owner B (`${USER_B_JWT}`). Owner B is the seeded empty account; if an
earlier manual run left rules behind, delete them first (`DELETE` on each — see M5-15).

**Steps:**

1. `GET /api/v1/recurring-rules` with `Authorization: Bearer ${USER_B_JWT}`.

**Expected result:**

- `200 OK` with body `[]`.
- Not `404`, and not an empty body.

**Result:** [ ] Pass   [ ] Fail

---

### M5-06 — Read one rule by id

**Covers:** UC-09 (read one of my rules); parameter failure shape.

**Preconditions:** **RULE_A** exists and belongs to owner A (M5-01).

**Steps:**

1. `GET /api/v1/recurring-rules/{RULE_A}` with `Authorization: Bearer ${USER_A_JWT}`.
2. `GET /api/v1/recurring-rules/999999999` (an id that does not exist) with the same token.
3. `GET /api/v1/recurring-rules/not-a-number` with the same token.

**Expected result:**

- Step 1: `200`, one rule object with exactly the same shape as a list element.
- Step 2: `404`, `errorCode: "NOT_FOUND"`, message `"Recurring rule not found."`, and `path` naming
  the endpoint. No body field is named (`fieldErrors` absent) — a missing row is not a field error.
- Step 3: `400`, `fieldErrors` absent. A malformed **path parameter** is a `400` with nothing to
  name, unlike a **body** failure which carries `fieldErrors`. The contract's table records this
  failure as `400`; the implementation's own test `aNonNumericIdIsABadRequest` asserts the code
  `INVALID_REQUEST` for `/abc`.

**Result:** [ ] Pass   [ ] Fail

---

### M5-07 — Edit the amount and the description

**Covers:** UC-09 (edit a rule).

**Preconditions:** **RULE_A** exists (M5-01); note its `startDate`, `nextRunDate`, `frequency` and
`intervalCount` first.

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_A}` with `Authorization: Bearer ${USER_A_JWT}` and body:

   ```json
   { "amount": 30.00, "description": "Gym membership, monthly" }
   ```

2. `PATCH /api/v1/recurring-rules/{RULE_A}` with body `{ "description": "" }`.
3. `GET /api/v1/recurring-rules/{RULE_A}` to confirm the stored state.

**Expected result:**

- Step 1: `200`; `amount` is `30.00` and `description` is the new text; **every other field is
  unchanged** from M5-01 (`startDate`, `nextRunDate`, `frequency`, `intervalCount`, `status`,
  `categoryId`).
- Step 2: `200`; the `description` key is **absent** from the response — an empty string clears the
  field (the same convention `endDate` uses). An omitted field or an explicit `null` would have left
  it as it was.
- Step 3: `200` and the same values, confirming the change is stored and not merely echoed.

**Result:** [ ] Pass   [ ] Fail

---

### M5-08 — `startDate` is not editable

**Covers:** UC-09 (§7.5 — the client sends no history); the rule's origin.

**Preconditions:** **RULE_A** exists; note its current `startDate` (from M5-01).

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_A}` with body
   `{ "startDate": "2020-01-01", "amount": 31.00 }`.
2. `GET /api/v1/recurring-rules/{RULE_A}`.

**Expected result:**

- `200` for the `PATCH`, and `startDate` is **unchanged** — the field is not part of the update
  contract, so it is silently ignored rather than refused.
- The other field in the same request (`amount: 31.00`) **was** applied, proving the request itself
  succeeded and only `startDate` was discarded.
- To move a rule onto a different day, `nextRunDate` is the field to change, not `startDate`.

**Result:** [ ] Pass   [ ] Fail

---

### M5-09 — `ACTIVE → PAUSED → ACTIVE`

**Covers:** UC-09 (pause and resume are `PATCH` of `status`).

**Preconditions:** Signed in as owner A. No `/pause` or `/resume` endpoint exists.

**Steps:**

1. Create **RULE_P1** with a `startDate`/`nextRunDate` well in the future (so nothing posts).
2. `PATCH /api/v1/recurring-rules/{RULE_P1}` with `{ "status": "PAUSED" }`.
3. `GET /api/v1/recurring-rules/{RULE_P1}`.
4. `PATCH /api/v1/recurring-rules/{RULE_P1}` with `{ "status": "ACTIVE" }`.
5. `PATCH /api/v1/recurring-rules/{RULE_P1}` with `{ "status": "ACTIVE" }` again (already active).

**Expected result:**

- Step 2: `200`, `"status": "PAUSED"`. Every other field is unchanged, including `nextRunDate` —
  **the cursor does not move while a rule is paused** (see §5.1).
- Step 3: `200`, `"status": "PAUSED"`, and the rule is still in `GET /api/v1/recurring-rules`.
- Step 4: `200`, `"status": "ACTIVE"`.
- Step 5: `200`, not an error. Sending the status a rule already has is a harmless no-op, so a "save"
  that resends an unchanged value is not refused.
- No request anywhere in this case sends a `pause`/`resume` flag — `status` is the only mechanism.

**Result:** [ ] Pass   [ ] Fail

---

### M5-10 — `PAUSED → ENDED`

**Covers:** UC-09 (a paused rule can be ended for good).

**Preconditions:** **RULE_P1** exists and is `ACTIVE` (end of M5-09).

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_P1}` with `{ "status": "PAUSED" }`.
2. `PATCH /api/v1/recurring-rules/{RULE_P1}` with `{ "status": "ENDED" }`.
3. `GET /api/v1/recurring-rules/{RULE_P1}`.

**Expected result:**

- Step 1: `200`, `"status": "PAUSED"`.
- Step 2: `200`, `"status": "ENDED"`. Ending is reachable from a paused rule as well as from an
  active one.
- Step 3: `200`, `"status": "ENDED"`; the rule is **still listed** (ended rules are never hidden) and
  its other fields are unchanged.

**Result:** [ ] Pass   [ ] Fail

---

### M5-11 — `ACTIVE → ENDED`

**Covers:** UC-09 (ending is how a rule is retired).

**Preconditions:** Signed in as owner A.

**Steps:**

1. Create **RULE_E1** with a future `nextRunDate`.
2. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "status": "ENDED" }`.

**Expected result:**

- `200`, `"status": "ENDED"` directly from `ACTIVE`.
- No other field changes, and the rule remains readable and listed.

**Result:** [ ] Pass   [ ] Fail

---

### M5-12 — Re-sending `ENDED` is a `200` no-op

**Covers:** UC-09 (ending is idempotent, so a retried request does not fail).

**Preconditions:** **RULE_E1** is `ENDED` (M5-11).

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "status": "ENDED" }`.
2. `GET /api/v1/recurring-rules/{RULE_E1}`.

**Expected result:**

- Step 1: `200` — **not** `409`. The request changes nothing; it is accepted so that a client
  retrying an end request (after a timeout, say) does not see a spurious failure.
- Step 2: `200`, `"status": "ENDED"`, all other fields unchanged.

**Result:** [ ] Pass   [ ] Fail

---

### M5-13 — `ENDED → ACTIVE` answers `409 RECURRING_RULE_ENDED`

**Covers:** UC-09 (the lifecycle is `ACTIVE ↔ PAUSED`, either `→ ENDED`, and `ENDED` is final).

**Preconditions:** **RULE_E1** is `ENDED`.

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "status": "ACTIVE" }`.
2. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "status": "PAUSED" }`.
3. `GET /api/v1/recurring-rules/{RULE_E1}`.

**Expected result:**

- Both steps 1 and 2: `409 Conflict`, body:

  ```json
  {
    "status": 409,
    "errorCode": "RECURRING_RULE_ENDED",
    "message": "This rule has ended, and an ended rule cannot be restarted. Create a new rule if the schedule is needed again.",
    "path": "/api/v1/recurring-rules/{RULE_E1}"
  }
  ```

- Step 3: `200`, `"status": "ENDED"` — the refused requests changed nothing.
- This is **not retryable**: the remedy is to create a new rule. The rule keeps its history.
- The restriction is the application's, not the column's, so the refusal is deliberate even though a
  different value would fit the column.

**Result:** [ ] Pass   [ ] Fail

---

### M5-14 — An edit that is not a status change still works on an `ENDED` rule

**Covers:** UC-09 (§5.8 — only the move **out of** `ENDED` is refused, not editing the rule).

**Preconditions:** **RULE_E1** is `ENDED`.

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "description": "Ended, but still editable" }`.
2. `PATCH /api/v1/recurring-rules/{RULE_E1}` with `{ "amount": 42.00 }`.
3. `GET /api/v1/recurring-rules/{RULE_E1}`.

**Expected result:**

- Both steps: `200`, and the field is applied.
- Step 3: `200`, `description` and `amount` are the new values and `"status": "ENDED"` is unchanged.
- The refusal in M5-13 is specifically about leaving `ENDED`, not about the rule being frozen.

**Result:** [ ] Pass   [ ] Fail

---

### M5-15 — Delete a rule that has never posted

**Covers:** UC-09 (remove one that was created by mistake or prepared for a date that has not
arrived).

**Preconditions:** Signed in as owner A.

**Steps:**

1. Create **RULE_D1** with a `nextRunDate` well in the future, so it cannot have posted.
2. `DELETE /api/v1/recurring-rules/{RULE_D1}` with `Authorization: Bearer ${USER_A_JWT}`.
3. `GET /api/v1/recurring-rules/{RULE_D1}`.
4. `GET /api/v1/recurring-rules`.
5. `DELETE /api/v1/recurring-rules/{RULE_D1}` again.

**Expected result:**

- Step 2: `204 No Content`, with **no body**.
- Step 3: `404`, `errorCode: "NOT_FOUND"` — the rule is gone.
- Step 4: `200`, and RULE_D1 is no longer in the array.
- Step 5: `404` — a second delete finds nothing.

**Result:** [ ] Pass   [ ] Fail

---

### M5-16 — A rule that has posted cannot be deleted — end it instead

**Covers:** UC-09 (delete is refused once the rule has generated transactions; the remedy is `ENDED`);
BR-02 (ownership is checked before the generated count).

**Preconditions:** A rule owned by owner A that has posted **at least one** period. This requires a
scheduler run — see §3.4. (Prepare it by creating a rule with a past `nextRunDate` and letting the
job run, or reusing the rule from M5-24 after its posting steps.)

**Steps:**

1. `GET /api/v1/transactions?from=<rule startDate>&to=<today>` and confirm at least one row with
   `"source": "RECURRING"`. Record the rule's id as **RULE_USED** and the transaction's id as
   **TXN_GEN**.
2. `DELETE /api/v1/recurring-rules/{RULE_USED}` with `Authorization: Bearer ${USER_A_JWT}`.
3. Soft-delete the generated transaction: `DELETE /api/v1/transactions/{TXN_GEN}` (module 4) →
   expect `204`.
4. `DELETE /api/v1/recurring-rules/{RULE_USED}` again.
5. Apply the remedy: `PATCH /api/v1/recurring-rules/{RULE_USED}` with `{ "status": "ENDED" }`.
6. `GET /api/v1/transactions/{TXN_GEN}` and `GET /api/v1/transactions?includeDeleted=true` (module 4)
   — or, if the generated row was not soft-deleted, just the plain list.

**Expected result:**

- Step 2: `409 Conflict`, `errorCode: "RECURRING_RULE_IN_USE"`, message naming the count and the
  remedy, e.g. `"This rule has already generated 3 transactions, so it cannot be deleted. End it
  instead: it will stop posting and the transactions it created stay readable and editable."`
  (A rule with one generated transaction reads `"1 transaction"`.)
- Step 4: **still** `409 RECURRING_RULE_IN_USE`. The count includes **soft-deleted** transactions on
  purpose: they still point at the rule and are still restored through the same check, so removing
  the row from sight must not report the rule as removable.
- Step 5: `200`, `"status": "ENDED"`. Ending stops the rule posting for good and leaves everything it
  generated readable and editable — this is the operation the message points at.
- Step 6: the generated transaction is still readable (and, if it had been soft-deleted, restorable)
  once the rule has ended. Deleting the rule would have broken it.

**Result:** [ ] Pass   [ ] Fail

---

### M5-17 — Move a rule to another category; the type is re-derived

**Covers:** UC-09 (an edit); BR-05 (moving a rule is how its type changes, and the client does not
send a type).

**Preconditions:** Signed in as owner A. You have one `EXPENSE` category id and one `INCOME`
category id, both non-retired.

**Steps:**

1. Create **RULE_M** under the `EXPENSE` category; note `"type": "EXPENSE"`.
2. `PATCH /api/v1/recurring-rules/{RULE_M}` with `{ "categoryId": <the INCOME category id> }`.
3. `PATCH /api/v1/recurring-rules/{RULE_M}` with `{ "categoryId": <the INCOME category id> }` again
   (the same category it is already in).

**Expected result:**

- Step 2: `200`; `categoryId` is the new one, `categoryName`, `categoryIcon` and `categoryColor` are
  the **new** category's, and `type` is now `"INCOME"`.
- The request sent **no** `type`: the stored type is re-derived from the target category. Sending a
  type would change nothing.
- Step 3: `200` and a no-op — the request does not move the rule, so it is not treated as a move to a
  retired category (there is none) and changes nothing.

**Result:** [ ] Pass   [ ] Fail

---

### M5-18 — Moving a rule onto a retired category is refused

**Covers:** UC-09; BR-07 (a retired category cannot be chosen or moved onto).

**Preconditions:** Owner A signed in. You can use module 3 (`POST`/`PATCH /api/v1/categories`) to
create and retire a personal category.

**Steps:**

1. `POST /api/v1/categories` as owner A to create a personal category, and record its id as
   **CAT_RETIRED**.
2. `PATCH /api/v1/categories/{CAT_RETIRED}` with `{ "isActive": false }` → expect `200`.
   (Retiring is allowed even if something already references the category; the `409 CATEGORY_IN_USE`
   refusal applies only to changing a category's `type`.)
3. `PATCH /api/v1/recurring-rules/{RULE_M}` with `{ "categoryId": <CAT_RETIRED> }`.
4. `POST /api/v1/recurring-rules` with `"categoryId": <CAT_RETIRED>` and a valid amount, frequency
   and start date.

**Expected result:**

- Step 3: `400`, `errorCode: "VALIDATION_ERROR"`, and `fieldErrors` contains an entry with
  `"field": "categoryId"` (message: `"Choose a category that is still in use, or restore this one
  first."`). Here the caller **can** fix it by sending a different category, so it is reported as a
  field error rather than a conflict.
- Step 4: `400`, `errorCode: "VALIDATION_ERROR"`, `fieldErrors` naming `categoryId` — the same rule
  on create.
- RULE_M is unchanged (`GET` it to confirm): a refused write changes nothing.

**Result:** [ ] Pass   [ ] Fail

---

### M5-19 — Another student's rule is unreachable

**Covers:** UC-09; BR-02 (ownership is structural; there is no identifier probing).

**Preconditions:** Owner B creates a rule **RULE_B1** with a future `nextRunDate` using
`${USER_B_JWT}`; record its id. Owner A's token is `${USER_A_JWT}`.

**Steps:**

1. As owner A: `GET /api/v1/recurring-rules/{RULE_B1}`.
2. As owner A: `PATCH /api/v1/recurring-rules/{RULE_B1}` with `{ "amount": 1.00 }`.
3. As owner A: `DELETE /api/v1/recurring-rules/{RULE_B1}`.
4. As owner A: `GET /api/v1/recurring-rules/999999999` (an id that certainly does not exist).
5. As owner B: `GET /api/v1/recurring-rules`.

**Expected result:**

- Steps 1–3: `404`, `errorCode: "NOT_FOUND"` in every case — **not** `403`, and for the delete **not**
  `409`.
- Step 4: the status, `errorCode` and message are **identical** to steps 1–3. Another student's rule
  and a rule that does not exist are indistinguishable, so the endpoint cannot be used to discover
  which rule identifiers exist.
- Step 3 returns `404` rather than `409` because ownership is checked **before** the
  generated-transaction count: the refusal must not reveal whether another student's rule has posted
  anything.
- Step 5: owner B's list still contains RULE_B1, unchanged.
- No endpoint accepts a `userId`: the owner is always the account in the bearer token.

**Result:** [ ] Pass   [ ] Fail

---

### M5-20 — Another student's category cannot be used

**Covers:** UC-09; BR-02 (only the caller's own or a shared default category).

**Preconditions:** Owner B creates a **personal** category via `POST /api/v1/categories` and records
its id as **CAT_B**.

**Steps:**

1. As owner A: `POST /api/v1/recurring-rules` with `"categoryId": <CAT_B>` and valid `amount`,
   `frequency`, `startDate`.
2. As owner A: `PATCH /api/v1/recurring-rules/{RULE_A}` with `{ "categoryId": <CAT_B> }`.

**Expected result:**

- Both: `404`, `errorCode: "NOT_FOUND"`, message `"Category not found."`, and **no** `fieldErrors` —
  the id is not found rather than found-and-refused.
- Not `403`: a category belonging to somebody else is indistinguishable from one that does not exist.
- Shared **default** categories (visible to every student) remain usable and are not affected by this
  case.

**Result:** [ ] Pass   [ ] Fail

---

### M5-21 — A rule whose category was retired cannot be changed at all

**Covers:** UC-09; BR-07 (retiring a category freezes the rules filed under it) — **OB-009**. See the
marked section §5.2 before recording this as a failure.

**Preconditions:** Owner A signed in; module 3 available for category management.

**Steps:**

1. `POST /api/v1/categories` as owner A; record the id as **CAT_X**.
2. Create **RULE_X** under CAT_X with a future `nextRunDate`.
3. `PATCH /api/v1/categories/{CAT_X}` with `{ "isActive": false }` → expect `200`.
4. `PATCH /api/v1/recurring-rules/{RULE_X}` with `{ "amount": 9.00 }`.
5. `PATCH /api/v1/recurring-rules/{RULE_X}` with `{ "description": "changed" }`.
6. `PATCH /api/v1/recurring-rules/{RULE_X}` with `{ "status": "ENDED" }` — even ending it is refused.
7. `GET /api/v1/recurring-rules/{RULE_X}`.

**Expected result:**

- Steps 4, 5 and 6: `409 Conflict` with

  ```json
  {
    "status": 409,
    "errorCode": "CATEGORY_RETIRED",
    "message": "This rule's category has been retired, so the rule cannot be changed. Enable the category, or move the rule to one that is still in use.",
    "path": "/api/v1/recurring-rules/{RULE_X}"
  }
  ```

- It is not a field error: no field the caller sent is wrong, so the answer is a conflict with a
  message naming both workarounds.
- Step 7: `200` and every field unchanged — the rule is **frozen, not hidden and not deleted**. Reads
  and the list still work.
- The rule is **also silent**: the scheduler skips rules whose category is inactive, so it posts
  nothing while the category is retired.

**Result:** [ ] Pass   [ ] Fail

---

### M5-22 — Workaround 1: move the frozen rule to a category still in use

**Covers:** UC-09; BR-07 (the accepted remedy — the *target* category is what gets checked).

**Preconditions:** **RULE_X** is under retired **CAT_X** (M5-21). You have a live category id.

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_X}` with `{ "categoryId": <a live category id> }`.
2. `PATCH /api/v1/recurring-rules/{RULE_X}` with `{ "status": "ENDED" }`.
3. `GET /api/v1/recurring-rules/{RULE_X}`.

**Expected result:**

- Step 1: `200`. The move is **accepted** even though the rule's current category is retired, because
  the request names a different one and the new category is what is validated.
- Step 2: `200`, `"status": "ENDED"` — the rule is now filed under a live category, so it is editable
  again.
- Step 3: `200`, `categoryId` is the new category and its `type` is that category's.
- If instead you move it **onto another retired category**, the answer is `400` with `fieldErrors`
  naming `categoryId` — the same as M5-18.

**Result:** [ ] Pass   [ ] Fail

---

### M5-23 — Workaround 2: re-enable the category, change the rule, retire it again

**Covers:** UC-09; BR-07 (the second accepted remedy).

**Preconditions:** A rule **RULE_Y** under retired **CAT_X** (create a second one before retiring, or
repeat M5-21 steps 1–3 with a new rule).

**Steps:**

1. `PATCH /api/v1/recurring-rules/{RULE_Y}` with `{ "status": "ENDED" }` while CAT_X is still retired
   → expect `409 CATEGORY_RETIRED` (the state you are working around).
2. `PATCH /api/v1/categories/{CAT_X}` with `{ "isActive": true }` → expect `200`.
3. `PATCH /api/v1/recurring-rules/{RULE_Y}` with `{ "status": "ENDED" }` → now accepted.
4. `PATCH /api/v1/categories/{CAT_X}` with `{ "isActive": false }` → expect `200`.
5. `GET /api/v1/recurring-rules/{RULE_Y}`, then `PATCH` it with `{ "amount": 11.00 }`.

**Expected result:**

- Step 1: `409 CATEGORY_RETIRED` — the freeze is real.
- Step 3: `200`, `"status": "ENDED"`. The same request that was refused a moment ago now succeeds:
  the freeze is the category's state, not a property of the rule.
- Step 4: `200`. Retiring a category does not change the rules under it.
- Step 5: `GET` → `200` with `"status": "ENDED"` and the fields it had; the follow-up `PATCH` →
  `409 CATEGORY_RETIRED` again. The rule is frozen once more, but its ended state persisted.
- This is a process workaround, not a fix: a frozen rule can only be edited while its category is
  enabled.

**Result:** [ ] Pass   [ ] Fail

---

### M5-24 — Pausing defers periods rather than skipping them

**Covers:** UC-09 A1 (catch-up); UC-09 pause semantics — **OB-010** and §5.1 of this document;
BR-16 (each period posts once).

**Preconditions:** The scheduler is enabled and you can make a run happen (§3.4). Use a monthly rule
whose `nextRunDate` is in the past. **Read §5.1 before you judge the result.**

**Steps:**

1. Create **RULE_Q** with `frequency: "MONTHLY"`, `intervalCount: 1`, and `startDate` **and**
   `nextRunDate` set to the same day about four months ago (so several monthly periods are overdue):

   ```json
   {
     "categoryId": 10,
     "amount": 15.00,
     "description": "Deferred-period check",
     "frequency": "MONTHLY",
     "intervalCount": 1,
     "startDate": "2026-05-01",
     "nextRunDate": "2026-05-01"
   }
   ```

2. `PATCH /api/v1/recurring-rules/{RULE_Q}` with `{ "status": "PAUSED" }` → `200`.
3. Trigger a scheduler run (§3.4).
4. `GET /api/v1/recurring-rules/{RULE_Q}`.
5. `GET /api/v1/transactions?from=<RULE_Q startDate>&to=<today>`.
6. `PATCH /api/v1/recurring-rules/{RULE_Q}` with `{ "status": "ACTIVE" }` → `200` (resume).
7. Trigger a second scheduler run.
8. `GET /api/v1/transactions?from=<RULE_Q startDate>&to=<today>` again.
9. `GET /api/v1/recurring-rules/{RULE_Q}` again.
10. Trigger a third scheduler run and repeat step 8.

**Expected result:**

- Step 4: `"status": "PAUSED"` and **`nextRunDate` is still the past date** — the cursor does not move
  while the rule is paused.
- Step 5: **no new `RECURRING` row** for this rule. A paused rule posts nothing.
- Step 6: `200`, `"status": "ACTIVE"`, `nextRunDate` still the old past date.
- Step 8: **every** monthly period whose date passed between `nextRunDate` and today has now been
  posted — not just the most recent one. Each row is dated on its **original scheduled date** (for
  example 2026-06-01, 2026-07-01, 2026-08-01, 2026-09-01), **not** on the resume date, and each has
  `"source": "RECURRING"`.
- Step 9: `nextRunDate` has advanced past today, and `lastRunDate` is now present.
- Step 10: the run posts **nothing further** — each period posts exactly once (BR-16), so the count
  from step 8 is unchanged.
- **This is correct behaviour, not a defect.** Transactions appearing after a resume is the
  documented consequence of OB-010. See §5.1.

**Result:** [ ] Pass   [ ] Fail

---

### M5-25 — An edit applies to future occurrences only

**Covers:** UC-09 A2 (an edit applies to the periods not yet posted); BR-16 (a covered period is never
re-posted or rewritten).

**Preconditions:** A rule that has posted at least one period — reuse **RULE_Q** from M5-24 after
step 9, or prepare one as in M5-24.

**Steps:**

1. `GET /api/v1/transactions?from=<rule startDate>&to=<today>` and note the `amount` on each
   `RECURRING` row generated by the rule.
2. `PATCH /api/v1/recurring-rules/{RULE_Q}` with `{ "amount": 99.00 }` → expect `200`.
3. `GET /api/v1/transactions?from=<rule startDate>&to=<today>` again.
4. If (and only if) a further period is due, trigger a scheduler run and repeat step 3.

**Expected result:**

- Step 3: every already-generated transaction keeps the amount it was posted with. `PATCH` does **not**
  rewrite history, and does not re-post a covered period.
- The rule's own `amount` is `99.00` from step 2 onwards; the next period posted takes the new value.
- Step 4, if applicable: the newly posted row carries `99.00`; the older rows are still unchanged and
  there is still exactly one transaction per period.
- Transactions already posted are the student's own records: they are edited one at a time through
  module 4, never by editing the rule.

**Result:** [ ] Pass   [ ] Fail

---

### M5-26 — No token, a bad token, and an administrator token

**Covers:** §15 (role enforced server-side; no endpoint works without a token).

**Preconditions:** All five endpoints are in scope; `${ADMIN_JWT}` obtained from
`POST /api/v1/admin/auth/login`.

**Steps:**

1. `GET /api/v1/recurring-rules` with **no** `Authorization` header.
2. `GET /api/v1/recurring-rules` with a deliberately altered token — change one character in the
   middle of a real one (`Authorization: Bearer <altered>`), which breaks the signature.
3. `GET /api/v1/recurring-rules` with `Authorization: Bearer ${ADMIN_JWT}`.
4. `POST /api/v1/recurring-rules` with `Authorization: Bearer ${ADMIN_JWT}` and a valid body.
5. `GET /api/v1/recurring-rules` with `Authorization: Bearer ${USER_A_JWT}`.

**Expected result:**

- Steps 1 and 2: `401`, `errorCode: "UNAUTHENTICATED"`.
- Steps 3 and 4: `403`, `errorCode: "ACCESS_DENIED"`. `/api/v1/recurring-rules/**` requires the
  `STUDENT` role — an administrator is refused, because the scheduler posts on students' behalf and a
  rule created here would be owned by that administrator.
- Step 5: `200`. The same call succeeds with a student token, so the `403` is about the role and not
  about sign-in.
- A token whose account was disabled after it was issued is also rejected as `401 UNAUTHENTICATED` by
  the token filter — the code `ACCOUNT_DISABLED` is returned by the sign-in endpoint only, and is not
  seen here.

**Result:** [ ] Pass   [ ] Fail

---

### M5-27 — Field validation the tester should not be surprised by

**Covers:** UC-09 create/update validation; BR-08 (amount positive and fits the column).

**Preconditions:** Signed in as owner A; a valid category id. Send each row as the body of a
`POST /api/v1/recurring-rules` (or, where noted, a `PATCH`), with the other required fields valid.

**Steps and expected result:**

| Sent | Expected |
|---|---|
| `"amount": 0` (or `-1`) | `400 VALIDATION_ERROR`, `fieldErrors[].field` = `amount`, message `"Amount must be greater than zero."` |
| `"amount": 1.005` (three decimals) | `400`, field `amount` — refused rather than rounded, so the stored value always matches what was typed |
| `"intervalCount": 0` (or `1000`) | `400`, field `intervalCount` — bounds are 1–999 |
| `"frequency": "monthly"` (lower case) | `400`; the contract reports `fieldErrors` naming `frequency`. Enum values are the member names in upper case. What must **not** happen is a `500`. |
| `"frequency": 2` (a number) | `400` — the ordinal is not accepted as the member name |
| `"endDate": "2026-02-30"` | `400`, field `endDate` — a well-formed but non-existent date is a field error, not an internal error |
| `"endDate": "2026-01-01"` before `startDate` | `400`, field `endDate` |
| `"nextRunDate": "2027-06-01"` with `endDate` `"2026-12-01"` | `400`, field `nextRunDate` — a next run past the end date would sit `ACTIVE` for ever, posting nothing |
| omit `"startDate"` (and omit `"categoryId"`, `"amount"`, `"frequency"` in turn) | `400`, `fieldErrors` naming each missing field individually (`"Start date is required."`, `"Category is required."`, …) |
| `"endDate": ""` on a `POST` or `PATCH` | Accepted; the rule is open-ended and the `endDate` key is **absent** from the response — an empty string is how a date is cleared |

- Every refusal is a `400` with `fieldErrors`; no refusal exposes an SQL statement, a constraint name
  or a trigger's text.
- A `PATCH` with body `{}` is valid: `200`, the rule unchanged, and nothing is written.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Two behaviours a tester is most likely to misread

Both are deliberate, documented behaviour. Read this section before marking anything in §4 as a
failure. Neither is a defect in the module.

> ### 5.1 A pause **defers** periods; it does not skip them (OB-010)
>
> The wording of UC-09 suggests that pausing skips the periods that fall inside the pause. The
> implementation does not do that, and the requirement is not what the locked procedure does.
>
> **What actually happens, exactly:**
>
> - Nothing in the scheduler selects a `PAUSED` rule, so **its cursor does not advance**. The stored
>   `nextRunDate` stays exactly where it was for the whole pause.
> - A paused rule posts nothing while it is paused.
> - **Resuming posts every period whose date passed during the pause**, dated on their **original
>   scheduled dates** — not on the resume date. Pausing a monthly rule for three months and resuming
>   it posts four periods at once.
> - This is the same mechanism as the catch-up after downtime: one behaviour, not two.
>
> **Do not report "transactions appeared after I resumed" as a bug.** It is OB-010, it is pinned by
> the automated test `pauseDefersPeriodsRatherThanSkippingThem`, and it is recorded in
> `docs/OVERNIGHT_BLOCKERS.md` for the owner's decision.
>
> **Consequences a tester will see, all of them correct:**
>
> - `nextRunDate` does not move while the rule is paused — it is the field that says "resumes from
>   here".
> - Resuming a long pause produces a burst of transactions dated in the past.
> - Those backfilled rows are ordinary expenses, so they count towards their own months' budgets and
>   can raise `BUDGET_NEAR` / `BUDGET_EXCEEDED` alerts retroactively.
> - A student who wants to avoid the burst should **end** the rule and create a new one, or move
>   `nextRunDate` forward before resuming — not pause and resume.
>
> Exercise this in **M5-24**.

> ### 5.2 A rule whose category was retired cannot be changed **at all** (OB-009)
>
> Retiring a category in module 3 **freezes every recurring rule filed under it**. The rule cannot be
> edited in any way, including:
>
> - its `amount`, `description`, `frequency`, `intervalCount` or `nextRunDate`;
> - **setting `status: "ENDED"`** — the one write a student most plausibly wants.
>
> Every one of those answers `409 CATEGORY_RETIRED`:
>
> ```json
> {
>   "status": 409,
>   "errorCode": "CATEGORY_RETIRED",
>   "message": "This rule's category has been retired, so the rule cannot be changed. Enable the category, or move the rule to one that is still in use.",
>   "path": "/api/v1/recurring-rules/{id}"
> }
> ```
>
> This is enforced by the **database**, not by the API: the rule table's update trigger re-validates
> the category on every update and has no "skip the active check" escape — unlike the transaction
> trigger, which does. It is recorded as OB-009 and is a schema-owned limitation, not a missing
> feature.
>
> **The two workarounds that are actually accepted:**
>
> 1. **Move the rule** to a category that is still in use — send `categoryId`. This is accepted even
>    while the old category is retired, because the **new** category is what gets validated. If the
>    target is itself retired, the answer becomes a `400` with `fieldErrors` naming `categoryId`.
>    (M5-22)
> 2. **Re-enable the category** (module 3: `PATCH /api/v1/categories/{id}` with
>    `{"isActive": true}`), change the rule, then retire the category again. (M5-23)
>
> **Also correct, and not a defect:** retiring a category does **not** change any rule and does
> **not** break listing or reading it. It also does not stop it posting — it cannot post, because the
> scheduler skips rules whose category is inactive. Retiring a category only stops its rules being
> *edited*.
>
> Exercise this in **M5-21**, **M5-22** and **M5-23**.

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M5-01 | UC-09 set up a repeating income or expense; BR-08 (a rule's dates may be in the future) |
| M5-02 | UC-09 create; BR-05 (the type is the category's, read-only); no client-supplied identity or creation state |
| M5-03 | UC-09 (`nextRunDate` defaults to `startDate`); BR-08 |
| M5-04 | UC-09 list; paused and ended rules stay visible; deterministic order (`nextRunDate`, then `id`); no `?status=` filter |
| M5-05 | UC-09 list, empty state (`200 []`) |
| M5-06 | UC-09 read one; `404 NOT_FOUND` vs `400 BAD_REQUEST` for a path parameter |
| M5-07 | UC-09 edit fields; `description` cleared with `""` |
| M5-08 | UC-09 §7.5 (`startDate` is not editable) |
| M5-09 | UC-09 pause and resume (`ACTIVE ↔ PAUSED`); idempotent status |
| M5-10 | UC-09 `PAUSED → ENDED` |
| M5-11 | UC-09 `ACTIVE → ENDED` |
| M5-12 | UC-09 ending is idempotent (`200` no-op) |
| M5-13 | UC-09 `ENDED` is final; `409 RECURRING_RULE_ENDED` |
| M5-14 | UC-09 an ended rule still accepts non-status edits |
| M5-15 | UC-09 remove a rule that has never posted; `204` |
| M5-16 | UC-09 delete refused once posted (`409 RECURRING_RULE_IN_USE`); the end-instead remedy; BR-02 (ownership checked before the count, including soft-deleted rows) |
| M5-17 | UC-09 edit; BR-05 (moving a category re-derives the stored type) |
| M5-18 | UC-09; BR-07 (a retired category cannot be chosen or moved onto) |
| M5-19 | UC-09; BR-02 (another student's rule is unreachable; no identifier probing) |
| M5-20 | UC-09; BR-02 (only the caller's own or a shared category) |
| M5-21 | UC-09; BR-07 (a retired category freezes its rules) — **OB-009** |
| M5-22 | UC-09; BR-07 (workaround 1: move to a live category) |
| M5-23 | UC-09; BR-07 (workaround 2: re-enable, edit, retire again) |
| M5-24 | UC-09 A1 (catch-up); UC-09 pause semantics — **OB-010**; BR-16 (each period posts once) |
| M5-25 | UC-09 A2 (an edit applies to future periods only); BR-16 |
| M5-26 | §15 (role `STUDENT` enforced server-side; a token is required on every endpoint); BR-03 context (`401` for a token that stopped being valid) |
| M5-27 | UC-09 validation on create/update; BR-08 (amount strictly positive and within the column); BR-07 (per-field `categoryId` errors) |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass. None of these is reachable by hand through
`/api/v1/recurring-rules/**` alone.

| Not covered | Why |
|---|---|
| Running the scheduler on demand | No endpoint runs it, by design. Posting is observed only through the daily job or a re-timed configuration (§3.4). |
| The occurrence bookkeeping (`recurring_occurrences`, period keys) | The scheduler owns it; the API exposes no occurrence or period-history endpoint. Its effect is read through module 4's transactions. |
| The 500-period catch-up cap | Reaching it needs a ~700-period backlog; the automated test `catchUpIsBoundedAndSettles` proves it settles over two runs. |
| Concurrency (two simultaneous deletes; a pause racing a scheduler run) | Racy by nature and unsafe to reproduce by hand against a shared stack. Pinned by the automated tests. |
| A disabled account's token (`401 UNAUTHENTICATED`) | Disabling an account is an administrator action (module 11); the student-reachable behaviour is covered by M5-26. |
| A revoked session / expired token | Needs module 1's sign-out or a token aged past `expiresIn`. |
| `lastRunDate` being non-writable | It is simply absent from both request types; M5-01 and M5-04 assert it is absent until the scheduler writes it. |
| The budget-alert interlock (UC-09 B4, module 6) | It belongs to module 6's manual procedure; the scheduled row goes through the same insert trigger as a hand-entered one. |
| Transaction history rows (BR-09) | No student-facing history endpoint exists. |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/recurring.md`,
`docs/modules/MODULE_05_RECURRING.md`, `docs/OVERNIGHT_BLOCKERS.md`, `docs/api/API_INVENTORY.md`,
`docs/CREDENTIALS.md` or the implementation under `backend/src/main/java/com/campuscoin/recurring/`.
