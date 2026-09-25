# Module 4 — Transactions: Manual Test Procedure

**Module:** Transactions (`/api/v1/transactions/**`) · **Use cases:** UC-07, UC-10 · **Endpoints:** 16–21
**Audience:** a QA engineer or the project owner, executing by hand against a running stack.

This is a manual procedure. It is meant to be followed without reading the source code. Every
expected status code, field name and error code below is taken from
[`docs/api/transactions.md`](../../api/transactions.md) and
[`docs/modules/MODULE_04_TRANSACTIONS.md`](../../modules/MODULE_04_TRANSACTIONS.md).

---

## 1. How to run this procedure

### 1.1 Starting the stack

```bash
docker compose up -d          # MySQL 8 + Adminer
# start the Spring Boot backend (see README.md)
```

| What | Where |
|---|---|
| API base URL | `http://localhost:8080/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Database browser (Adminer) | `http://localhost:8081` |

The database is initialised from `db/merged/campuscoin_full.sql`, which already contains the schema,
views, procedures, triggers, the seed data and the demo data. A fresh volume therefore has the
twelve shared categories and the demo budgets described below. To rebuild from scratch:
`docker compose down -v && docker compose up -d`.

### 1.2 Obtaining the tokens (never paste a token into this document or into any file)

Call the login endpoint and copy the `accessToken` value **out of the response** into a shell
variable. The response shape is in
[`docs/api/authentication.md`](../../api/authentication.md).

| Placeholder | What it stands for | How to obtain it |
|---|---|---|
| `${JWT}` | A valid **STUDENT** token for the account under test (called *Student A* below) | `POST /api/v1/auth/login` with Student A's email and password, then copy `accessToken` |
| `${USER_A_JWT}` | A valid **STUDENT** token for **Student A** — the same account as `${JWT}`, obtained separately when a test needs both students named explicitly | `POST /api/v1/auth/login` as Student A |
| `${USER_B_JWT}` | A valid **STUDENT** token for a **second, different student** (called *Student B*) | `POST /api/v1/auth/login` as Student B |
| `${ADMIN_JWT}` | A valid **ADMIN** token | `POST /api/v1/admin/auth/login` |

Those four are the **only credential placeholders** in this document, and no real token or password
value appears anywhere in it. Assign each copied value to its shell variable once, then run the
`curl` commands as written.

A second, non-credential convention is used in the bodies and paths: angle brackets such as
`<ID>`, `<today>` and `<A_CAT>` mark a value you must substitute from your own run (a transaction id
you noted, a date, a category id, or a variable you computed). They never hold a token or a password.

**Accounts.** Use the seeded student and administrator accounts listed in
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md). Student A should be the student with demo data
(used for the budget-alert case, M4-22); Student B should be the empty account. Passwords are in
that document and are deliberately **not** repeated here.

**Swagger UI alternative.** Open `/swagger-ui.html`, click **Authorize**, paste the raw token value
(no `Bearer ` prefix) into the `bearerAuth` box, and use **Try it out** on each operation. The
method, path, body shape and status codes in every test case below map directly onto the Swagger
operations.

### 1.3 Category identifiers used below

A fresh database seeds twelve **shared default** categories, in this insert order, so their ids are
normally:

| Type | Ids and names |
|---|---|
| `INCOME` | `1` Allowance · `2` Part-time Job · `3` Scholarship · `4` Gift · `5` Other Income |
| `EXPENSE` | `6` Food · `7` Transport · `8` Hostel/Rent · `9` Academics · `10` Subscriptions · `11` Entertainment · `12` Miscellaneous |

Confirm the real values before you start:

```bash
curl -s http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer ${JWT}"
```

In the JSON array, read `id` where `name` is `Food` (an `EXPENSE`) and where `name` is `Allowance`
(an `INCOME`). Where a test case below says *"the Food category id"*, substitute the id you read.
The demo account also owns personal categories; prefer the shared defaults for the shared steps.

### 1.4 The response shape a successful call returns

A transaction read or written through this module is always this object. `categoryIcon`,
`categoryColor`, `description` and `deletedAt` are **omitted entirely** when they have no value —
they are never sent as `null`.

```json
{
  "id": 31,
  "categoryId": 6,
  "categoryName": "Food",
  "categoryIcon": "utensils",
  "categoryColor": "#F97316",
  "type": "EXPENSE",
  "amount": 12.50,
  "txnDate": "2026-09-24",
  "source": "MANUAL",
  "isDeleted": false
}
```

There is **no `userId`**, **no `type` in the request**, and **no writable `isDeleted`**. Those are
deliberate: see M4-03 and M4-18 for what they mean in practice.

### 1.5 What this module does **not** implement (so do not test for it)

| Not present | Consequence for this procedure |
|---|---|
| Any `type` field on create or update | Sending one changes nothing — M4-03 |
| Any `source`, `userId`, `isDeleted`, `deletedAt` field on a request | Sending one changes nothing |
| A `PUT` for the whole transaction | Only `PATCH` exists; a `PUT` answers `400 INVALID_REQUEST` |
| Pagination (`page`, `size`, `offset`, `sort`) | No such query parameter exists. The list is returned whole — M4-08 |
| A filter by `type`, `categoryId` or a text search | No such query parameter exists. The only filters are `from`, `to` and `includeDeleted` — M4-09 |
| A hard-delete endpoint | No endpoint removes a transaction outright — M4-16 |
| Any endpoint that shows a budget alert or a notification | Budget alerts are UC-14, **module 6**, reached through the notifications API there — M4-22 states what is and is not observable here |
| Any endpoint that shows balances or totals | Figures are computed server-side from views for the dashboard and reports (modules 6 and 7). M4-11 verifies the effect on totals through the database, and says so |

---

## 2. Test cases

Each case has an ID, a title, the UC/BR it covers, preconditions, numbered steps with the exact
HTTP method, path and JSON body, an expected result, and a result checkbox.

> **Date note.** "Today" in this module is judged in `Asia/Ho_Chi_Minh` (+07:00), the same offset
> the database session uses. Write dates as `YYYY-MM-DD` with no time and no zone. Where a step says
> *today*, use the current date in that zone. The literal dates that appear in the example bodies
> below (`2026-09-24`, `2026-09-01`) are illustrative past dates taken from the API document —
> substitute a real past date, and never one later than today (see M4-06).

---

### M4-01 — Record an expense

| | |
|---|---|
| **ID** | M4-01 |
| **Title** | Record an expense under an expense category |
| **Covers** | UC-07 (record an income or expense); the `201` contract of `POST /api/v1/transactions` |
| **Preconditions** | Stack running. `${JWT}` is a valid STUDENT token. You know the **Food** category id (here written as `6`). |

**Steps**

1. Send:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{
           "categoryId": 6,
           "amount": 14.50,
           "txnDate": "2026-09-24",
           "description": "Campus Dining Hall"
         }'
   ```

   (Replace `2026-09-24` with today or any past date — see M4-06 and M4-07.)
2. Read the response body and note the `id`.

**Expected result**

- Status `201 Created`.
- Body is the transaction object of §1.4, including the server-assigned numeric `id`.
- `type` is `"EXPENSE"` — derived from the Food category, never sent.
- `source` is `"MANUAL"`.
- `isDeleted` is `false`.
- `categoryId` is `6`, `categoryName` is `"Food"`, and `categoryIcon`/`categoryColor` come back
  filled in from the category.
- `description` is `"Campus Dining Hall"`.
- There are **no** `userId`, `createdAt` or `updatedAt` keys anywhere in the body.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-02 — Record an income

| | |
|---|---|
| **ID** | M4-02 |
| **Title** | Record an income under an income category |
| **Covers** | UC-07 |
| **Preconditions** | As M4-01. You know the **Allowance** category id (here written as `1`). |

**Steps**

1. Send:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{
           "categoryId": 1,
           "amount": 200.00,
           "txnDate": "2026-09-01",
           "description": "Monthly allowance"
         }'
   ```

2. Read the response body.

**Expected result**

- Status `201 Created`.
- `type` is `"INCOME"` — derived from the Allowance category.
- `amount` is `200.00` and is **positive**; the direction of the money is carried by `type`, never
  by the sign of `amount`.
- `categoryName` is `"Allowance"`.
- `source` is `"MANUAL"`, `isDeleted` is `false`.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-03 — The type is the category's, not the request's (BR-05)

| | |
|---|---|
| **ID** | M4-03 |
| **Title** | Sending `type` on create changes nothing; the category decides |
| **Covers** | BR-05 (a transaction's type agrees with its category's type); the deliberate absence of a `type` field |
| **Preconditions** | `${JWT}` is a valid STUDENT token. You know the Food (`EXPENSE`) and Allowance (`INCOME`) category ids. |

**Steps**

1. Send a create body that **sends `type`, `source`, `isDeleted` and `userId`** even though the
   contract does not accept them:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{
           "categoryId": 6,
           "amount": 5.00,
           "txnDate": "2026-09-24",
           "type": "INCOME",
           "source": "RECURRING",
           "isDeleted": true,
           "userId": 999999
         }'
   ```

2. Read the response body.

**Expected result**

- Status `201 Created` — the extra keys are accepted and **ignored**; they do not cause an error.
- `type` is `"EXPENSE"`, because category `6` (Food) is an expense category. The `"INCOME"` the
  client sent had no effect: `transactions` has no type column, so there is no second value able to
  disagree with the category's.
- `source` is `"MANUAL"`, not `"RECURRING"`. Provenance is fixed by the server, so a client cannot
  claim a source the database exempts from the future-date check.
- `isDeleted` is `false` — state is not client-writable.
- The record is owned by the token's account, not by `userId: 999999` (which is not in the response;
  confirm in M4-18 that Student B cannot reach this record).

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-04 — Moving a record to another category changes its type (BR-05)

| | |
|---|---|
| **ID** | M4-04 |
| **Title** | `PATCH {"categoryId": …}` moves the record and changes its `type` |
| **Covers** | BR-05; UC-10 (edit a transaction) |
| **Preconditions** | A transaction was created under the Food category (M4-01). You know the Allowance category id. |

**Steps**

1. Note the `id` of a transaction created under Food, and confirm it is `"EXPENSE"`.
2. Send:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": 1 }'
   ```

   (Substitute the real id for `<ID>`. This is the only place a path id is written generically; every
   later case assumes you substitute the real numeric id.)

**Expected result**

- Status `200 OK`.
- `categoryId` is `1`, `categoryName` is `"Allowance"`, and **`type` is now `"INCOME"`**.
- No other field changed: the amount, date and description are as they were.
- This is a legitimate correction of a misfiled record, not an error.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-05 — The amount must be strictly positive and fit the column

| | |
|---|---|
| **ID** | M4-05 |
| **Title** | Amount boundaries: zero, negative, three decimal places and over-length are all refused |
| **Covers** | BR-08 (the positive-amount rule, as traced in `MODULE_04_TRANSACTIONS.md` §9); `ck_txn_amount`; the `DECIMAL(15,2)` column |
| **Preconditions** | `${JWT}` is a valid STUDENT token. You know the Food category id. |

**Steps** — send each body to `POST /api/v1/transactions` with `Content-Type: application/json`.

1. The smallest accepted value:

   ```json
   { "categoryId": 6, "amount": 0.01, "txnDate": "2026-09-24" }
   ```

2. Zero:

   ```json
   { "categoryId": 6, "amount": 0.00, "txnDate": "2026-09-24" }
   ```

3. Negative:

   ```json
   { "categoryId": 6, "amount": -5.00, "txnDate": "2026-09-24" }
   ```

4. Three decimal places:

   ```json
   { "categoryId": 6, "amount": 1.005, "txnDate": "2026-09-24" }
   ```

5. Fourteen digits before the decimal point (the column holds thirteen):

   ```json
   { "categoryId": 6, "amount": 10000000000000.00, "txnDate": "2026-09-24" }
   ```

**Expected result**

| Step | Status | `errorCode` | `fieldErrors` |
|---|---|---|---|
| 1 (`0.01`) | `201 Created` | — | — |
| 2 (`0.00`) | `400` | `VALIDATION_ERROR` | one entry with `"field": "amount"` |
| 3 (`-5.00`) | `400` | `VALIDATION_ERROR` | one entry with `"field": "amount"` |
| 4 (`1.005`) | `400` | `VALIDATION_ERROR` | one entry with `"field": "amount"` |
| 5 (14 digits) | `400` | `VALIDATION_ERROR` | one entry with `"field": "amount"` |

Additional checks:

- `-5.00` is refused as a **field error**, not read as an expense. There is no sign convention here:
  `amount` is always positive, and `type` says which direction the money moved.
- `1.005` is refused rather than rounded. Three decimal places would be silently rounded to `1.01` by
  the column, so the value the student typed and the value stored would differ with nothing on screen
  saying so.
- The property name is `fieldErrors[].field` — **never** `path` (project-wide convention).
- The `400` bodies contain no SQL, no constraint name, no table or column name and no Java class name.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-06 — A future date is refused (BR-08)

| | |
|---|---|
| **ID** | M4-06 |
| **Title** | Tomorrow and any later date are refused on create and on edit |
| **Covers** | BR-08 (a record may not be dated in the future) |
| **Preconditions** | `${JWT}` is a valid STUDENT token. You know the Food category id. You can work out tomorrow's date in `Asia/Ho_Chi_Minh` (+07:00). |

**Steps**

1. Send a create with **tomorrow's** date:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": 6, "amount": 1.00, "txnDate": "<tomorrow, YYYY-MM-DD>" }'
   ```

2. Read the response body in full.
3. Send a create with a date far ahead, e.g. `"txnDate": "2030-01-01"`, and read the body.
4. Try to move an existing transaction to the future:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "txnDate": "<tomorrow, YYYY-MM-DD>" }'
   ```

**Expected result**

- Every one of the three requests returns **`400 Bad Request`**.
- `errorCode` is **`"VALIDATION_ERROR"`**.
- `fieldErrors` contains exactly one entry for this problem, with
  `"field": "txnDate"` and a message telling the student to choose today or an earlier date.
- No transaction is created and no field of the edited transaction changes.

> **Why `400 VALIDATION_ERROR` and not a `409`:** the service checks the date before the write
> (`requireNotInTheFuture`) purely so it can name the field. The database's
> `sp_validate_transaction`, reached through `trg_transactions_before_insert` and
> `trg_transactions_before_update`, enforces the same rule independently and would refuse the row
> anyway. The `409 DATA_CONFLICT` path exists only for a change that slips past the pre-check
> (for example the day rolling over mid-request), and is not what you should see here.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-07 — Today and past dates are accepted (BR-08 boundary)

| | |
|---|---|
| **ID** | M4-07 |
| **Title** | The date bound is inclusive at today: today and earlier are accepted |
| **Covers** | BR-08 (the boundary is the present day, not the future); UC-07 |
| **Preconditions** | As M4-06. |

**Steps**

1. Create a transaction dated **today**:

   ```json
   { "categoryId": 6, "amount": 2.00, "txnDate": "<today, YYYY-MM-DD>", "description": "today" }
   ```

2. Create a transaction dated **thirty days ago**:

   ```json
   { "categoryId": 6, "amount": 3.00, "txnDate": "<today minus 30 days, YYYY-MM-DD>", "description": "long ago" }
   ```

**Expected result**

- Both return `201 Created`.
- `txnDate` in each response echoes the date that was sent, unchanged.
- The boundary is inclusive: BR-08 forbids the **future**, not the present.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-08 — List history: content, ordering and the absence of paging

| | |
|---|---|
| **ID** | M4-08 |
| **Title** | `GET /api/v1/transactions` returns my records newest first, in a total order, with no pagination |
| **Covers** | UC-10 (view transaction history); the ordering contract `txnDate DESC, id DESC` |
| **Preconditions** | `${JWT}` is a valid STUDENT token. At least three transactions exist for this account — create them with M4-01, M4-02 and M4-07 if needed, including two on the same date. |

**Steps**

1. Create two transactions dated **the same day** (e.g. two Food expenses today, amounts `20.00` and
   `30.00`), and one dated an earlier day. Note their `id`s.
2. Send:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}"
   ```

3. Inspect the order of `id` and `txnDate` in the returned array.
4. Ask for a page, which the contract does not define:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?page=0&size=5" \
     -H "Authorization: Bearer ${JWT}"
   ```

5. Using the empty Student B account, send step 2 with `${USER_B_JWT}`.

**Expected result**

- Step 2: `200 OK`, and the body is a JSON **array** (not an object with a `content` or `total`
  wrapper).
- Every row has the shape of §1.4 and each carries `isDeleted`.
- **Ordering:** `txnDate` descending. Where two rows share a date, the higher `id` comes first. The
  order is total, so running step 2 twice returns the identical sequence — the list must not appear
  to shuffle between two calls.
- **Pagination does not exist.** The request in step 4 returns **`200 OK`** with the **whole** list:
  `page` and `size` are not declared parameters, so they are simply ignored and the same array as
  step 2 comes back. The body is never a page envelope — there is no `content`, `totalElements`,
  `totalPages`, `page`/`size` key and no `next` link. No `page`, `size`, `offset` or `sort` parameter
  exists in this contract. If your client needs a narrower response, use `from`/`to` as in M4-09 —
  that is the mechanism UC-10 describes.
- Step 5: `200 OK` with `[]` — an account with no records gets an empty array, **not** `null` and
  **not** `404`.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-09 — Filtering by date range, and the failures around it

| | |
|---|---|
| **ID** | M4-09 |
| **Title** | `from`/`to` are inclusive and either may be omitted; an inverted range and a malformed date are both `400` but with different codes |
| **Covers** | UC-10 (narrow the history to a range); the parameter-level error contract |
| **Preconditions** | `${JWT}` is a valid STUDENT token. Transactions exist today, two days ago, three days ago and ten days ago (a "far past" one). |

**Steps**

1. Ask for a bounded range whose boundaries each match a record:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?from=<today-3>&to=<today-1>" \
     -H "Authorization: Bearer ${JWT}"
   ```

2. Ask for an upper bound only:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?to=<today-2>" \
     -H "Authorization: Bearer ${JWT}"
   ```

3. Ask for a lower bound only:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?from=<today-3>" \
     -H "Authorization: Bearer ${JWT}"
   ```

4. Ask for an **inverted** range (`from` later than `to`):

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?from=2026-09-20&to=2026-09-01" \
     -H "Authorization: Bearer ${JWT}"
   ```

5. Ask with a **malformed** date:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?from=not-a-date" \
     -H "Authorization: Bearer ${JWT}"
   ```

6. Choose a filter that matches nothing, e.g. a range entirely before the account existed.

**What the module actually does**

- Both bounds are **inclusive**.
- An omitted `from` reaches back to the earliest date the column can hold; an omitted `to` defaults
  to **today**. That default is a real bound: a record dated later than today is not in an unbounded
  list. Nothing this endpoint creates can be dated ahead (BR-08), so the default costs nothing here —
  but a future-dated `RECURRING` row written by module 5's scheduler will be absent from an
  unbounded list until its date arrives. Passing `to` explicitly is the override.
- The filters are only `from`, `to` and `includeDeleted`. There is **no** filter by `type`,
  `categoryId` or description text; a picker for those would be a parameter no use case asks for.

**Expected result**

| Step | Status | `errorCode` | Notes |
|---|---|---|---|
| 1 | `200` | — | Contains the records dated exactly on `from` and exactly on `to`, plus any between; excludes the one ten days ago |
| 2 | `200` | — | Everything dated on or before `to`, including the far-past record |
| 3 | `200` | — | Everything dated on or after `from`, up to and including today |
| 4 | `400` | `VALIDATION_ERROR` | `fieldErrors` has one entry with `"field": "from"`. An inverted range is a client bug and must **not** be answered as an empty list |
| 5 | `400` | `INVALID_REQUEST` | **No `fieldErrors` array at all.** A query parameter is not a body field, so there is nothing to name. Both this and step 4 are `400`, and the difference is the presence of `fieldErrors` |
| 6 | `200` | — | `[]` — an empty result is an empty array, not a `404` |

- No step returns `500`, and no response body contains a stack trace, a SQL fragment or a class name.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-10 — Read one transaction by id

| | |
|---|---|
| **ID** | M4-10 |
| **Title** | `GET /api/v1/transactions/{id}` reads one of mine; an unknown id is `404` |
| **Covers** | UC-10 (read one record) |
| **Preconditions** | `${JWT}` is a valid STUDENT token. You have a transaction id from M4-01. |

**Steps**

1. Send:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}"
   ```

2. Send the same request for an id that certainly does not exist:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/99999999 \
     -H "Authorization: Bearer ${JWT}"
   ```

3. Send the same request with a non-numeric id:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/not-a-number \
     -H "Authorization: Bearer ${JWT}"
   ```

4. Compare the step 1 body against the same row as it appears in the list from M4-08.

**Expected result**

- Step 1: `200 OK`, one transaction object in the shape of §1.4 — same `id`, `categoryId`,
  `categoryName`, `type`, `amount`, `txnDate`, `source`, `isDeleted` as the list row.
- Step 2: `404 Not Found`, `errorCode` `"NOT_FOUND"`. The same `404` is what an unknown id, another
  student's record and a deleted record each answer — deliberately indistinguishable (see M4-18).
- Step 3: `400 Bad Request`, `errorCode` `"INVALID_REQUEST"`. A path id that is not a number, is not
  an integer, or is too large for the column is a bad request, not a `404`.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-11 — Soft-delete a transaction, and the row stops counting towards totals (BR-09)

| | |
|---|---|
| **ID** | M4-11 |
| **Title** | `DELETE` moves the record to the trash: `204`, gone from the list and by id, and excluded from totals |
| **Covers** | UC-10 (delete a transaction); BR-09 (the removal is soft and every report view filters `is_deleted = 0`) |
| **Preconditions** | `${JWT}` is a valid STUDENT token. A transaction exists with a distinctive `amount` (e.g. `123.45`) and a known id. You can open Adminer at `http://localhost:8081` — or run the SQL below with the MySQL client — for the totals steps. |

**Steps**

1. Record the current month totals **before** deleting. In the database:

   ```sql
   SELECT period_month, total_expense, txn_count
     FROM v_monthly_income_expense
    WHERE user_id = <your student id>
    ORDER BY period_month DESC
    LIMIT 1;
   ```

   Also note this one row:

   ```sql
   SELECT id, amount, is_deleted, deleted_at
     FROM transactions
    WHERE id = <ID>;
   ```

2. Delete through the API:

   ```bash
   curl -i -X DELETE http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}"
   ```

3. Read it back by id:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}"
   ```

4. List the records and confirm the row is absent:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?from=<first-of-month>&to=<today>" \
     -H "Authorization: Bearer ${JWT}"
   ```

5. Re-run the two SQL statements from step 1.

**Expected result**

- Step 2: `204 No Content`, with **no body**.
- Step 3: `404 Not Found`, `errorCode` `"NOT_FOUND"` — a deleted record is no longer readable by id.
- Step 4: the array does **not** contain the deleted `id`.
- Step 5: the row **still exists**, with `is_deleted = 1` and a non-null `deleted_at`. This is a
  **soft** delete: the row is kept and its history is preserved, which is the whole of BR-09.
- Step 5: in `v_monthly_income_expense`, `total_expense` has **dropped by the deleted amount** (or
  `total_income` has, if you deleted an income), and `txn_count` is one lower. Every reporting view
  filters `is_deleted = 0`, so a deleted record is excluded from every balance and every report.
- No endpoint in this API removes a transaction outright — see M4-16.

> **Note.** This module exposes no totals endpoint. The effect on totals is defined by the database
> views that the dashboard and report modules read; verifying it therefore requires the SQL above.
> That is why this step is a database check and not an API call.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-12 — Deleting twice is a conflict

| | |
|---|---|
| **ID** | M4-12 |
| **Title** | Deleting an already-deleted transaction answers `409 TRANSACTION_ALREADY_DELETED`, not a second `204` |
| **Covers** | UC-10 (delete); the state-conflict contract for a repeated delete |
| **Preconditions** | A transaction was deleted in M4-11 and its id is known. |

**Steps**

1. Delete the same id again:

   ```bash
   curl -i -X DELETE http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}"
   ```

2. Read the body in full.

**Expected result**

- Status `409 Conflict`.
- `errorCode` is **`"TRANSACTION_ALREADY_DELETED"`**.
- The body has this shape:

  ```json
  {
    "timestamp": "…",
    "status": 409,
    "errorCode": "TRANSACTION_ALREADY_DELETED",
    "message": "This transaction has already been deleted.",
    "path": "/api/v1/transactions/<ID>"
  }
  ```

- There is **no** `fieldErrors` key: there is no body field at fault. The client's copy of the
  record's state is stale and the remedy is to reload.
- The row is **not** removed by this call and no second history row is written.
- `409` is used rather than `404` because the row is still there — reporting "not found" would be
  false — and rather than a second `204`, because nothing happened.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-13 — View the trash with `includeDeleted=true` (UC-10 A1)

| | |
|---|---|
| **ID** | M4-13 |
| **Title** | The deleted record is still findable, carrying `isDeleted: true` and a `deletedAt` |
| **Covers** | UC-10 A1 (find a record again in order to restore it); BR-09 |
| **Preconditions** | A transaction was deleted in M4-11. |

**Steps**

1. Send:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?includeDeleted=true" \
     -H "Authorization: Bearer ${JWT}"
   ```

2. Find the deleted `id` in the array and read its fields.
3. Send the same request with `includeDeleted=false` (or omit the parameter).

**Expected result**

- Step 1: `200 OK`. The array now contains the deleted record alongside the live ones.
- The deleted row has **`isDeleted: true`** and a **`deletedAt`** value in the form
  `"2026-09-25T09:30:00"` (a date-time without an offset). All other fields are as they were.
- Live rows still have `isDeleted: false` and **no** `deletedAt` key at all — nullable fields are
  omitted rather than sent as `null`.
- Step 3: the deleted id is absent again.
- `isDeleted` is **never absent** from a row; it is a boolean with a value in every state, so a
  client can read it unconditionally.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-14 — Restore a soft-deleted transaction (UC-10 A1)

| | |
|---|---|
| **ID** | M4-14 |
| **Title** | `POST /api/v1/transactions/{id}/restore` brings the record back |
| **Covers** | UC-10 A1 (restore); BR-09 (the history shows `CREATE → DELETE → RESTORE` rather than being rewritten) |
| **Preconditions** | A transaction is in the trash (M4-11) and its id is known. You can query the database for the history check. |

**Steps**

1. Send:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions/<ID>/restore \
     -H "Authorization: Bearer ${JWT}"
   ```

   No body is needed.
2. Read the response body.
3. Read the record by id:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}"
   ```

4. Confirm it is back in the default list (M4-08) and that `includeDeleted=true` still shows it with
   `isDeleted: false`.
5. Inspect the audit trail in the database:

   ```sql
   SELECT action, changed_fields, changed_at
     FROM transaction_history
    WHERE transaction_id = <ID>
    ORDER BY id;
   ```

**Expected result**

- Step 1: `200 OK`, and the body is the restored transaction with **`isDeleted: false`** and **no
  `deletedAt`** key.
- Step 3: `200 OK` — the record is readable by id again.
- Step 4: the record is back in the default list, so it returns to the balances and the reports.
- Step 5: the history contains the earlier steps in order — the log reads something like
  `CREATE`, then `DELETE`, then `RESTORE`. The earlier rows are left exactly as they were; restoring
  appends, it does not rewrite. That is what BR-09 requires of the log.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-15 — Restoring a live record is a conflict

| | |
|---|---|
| **ID** | M4-15 |
| **Title** | Restoring a record that is not deleted answers `409 TRANSACTION_NOT_DELETED` |
| **Covers** | UC-10 A1 (the refusal case) |
| **Preconditions** | A transaction is **live** (not deleted) and its id is known — use the record restored in M4-14. |

**Steps**

1. Send:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions/<ID>/restore \
     -H "Authorization: Bearer ${JWT}"
   ```

2. Read the body in full.

**Expected result**

- Status `409 Conflict`.
- `errorCode` is **`"TRANSACTION_NOT_DELETED"`**.
- Body shape:

  ```json
  {
    "timestamp": "…",
    "status": 409,
    "errorCode": "TRANSACTION_NOT_DELETED",
    "message": "This transaction is not deleted, so there is nothing to restore.",
    "path": "/api/v1/transactions/<ID>/restore"
  }
  ```

- No `fieldErrors` key.
- The record is unchanged: still live, still `isDeleted: false`, no new history row.
- A `409` rather than a silent success, because answering "restored" for a request that changed
  nothing would misreport the outcome for a client whose list is out of date.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-16 — A hard delete is impossible (BR-09)

| | |
|---|---|
| **ID** | M4-16 |
| **Title** | No API deletes a transaction outright, and the database refuses a direct `DELETE` |
| **Covers** | BR-09 (a soft delete only) |
| **Preconditions** | A live transaction id is known. You can run SQL against the database (Adminer or the MySQL client), and you have **write** access for the direct statement — a read-only account cannot run it, which is itself a useful observation. |

**Steps**

1. Through the API, confirm there is no hard-delete route. `DELETE /api/v1/transactions/{id}` is the
   only delete, and M4-11 showed it is a soft delete. Try a `PUT` on the collection, which UC-10 does
   not define:

   ```bash
   curl -i -X PUT http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "amount": 1.00 }'
   ```

2. Run a direct hard delete against the database:

   ```sql
   DELETE FROM transactions WHERE id = <ID>;
   ```

3. Check that the row is still there:

   ```sql
   SELECT id, is_deleted FROM transactions WHERE id = <ID>;
   ```

**Expected result**

- Step 1: `400 Bad Request` with `errorCode` `"INVALID_REQUEST"` (the HTTP method is not supported by
  this endpoint). There is no `PUT` and no hard-delete endpoint in the contract.
- Step 2: the statement **fails**. MySQL raises an error with **SQLSTATE `45000`** (client error
  number `1644`) and a message naming **BR-09** — the trigger `trg_transactions_before_delete`
  refuses every `DELETE`, for every caller, including a hand-run statement. The refusal is by the
  schema, not by the application, so it does not depend on the API being the only caller.
- Step 3: the row is **still present**. Nothing was deleted.
- Consequence: the record's history can never be lost by deleting the record, which is what makes the
  audit trail BR-09 requires meaningful.

> **Where the documented `409 DATA_CONFLICT` applies.** The mapping table in
> `docs/api/transactions.md` §11 lists a hard delete as surfaced `409 DATA_CONFLICT`. That is the
> answer if a refusal reaches the **API's** write path, where `translateWriteFailure` classifies it
> by SQLSTATE. This test case refuses the statement at the database, outside the API, so what you
> observe is the raw MySQL error, not an HTTP status.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-17 — Partial update semantics on `PATCH`

| | |
|---|---|
| **ID** | M4-17 |
| **Title** | Only the fields sent change; `{}` changes nothing, `""` clears the description, `null` leaves it |
| **Covers** | UC-10 (edit some fields); the partial-update contract |
| **Preconditions** | A live transaction with a known id, `amount` and `description` (use the M4-01 record). |

**Steps**

1. Note the current `amount`, `txnDate` and `description` (call it "original").
2. Send an **empty** body:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{}'
   ```

3. Change **only** the amount:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "amount": 15.00 }'
   ```

4. Clear the description with an empty string:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "description": "" }'
   ```

5. Send `null`, which means "not changed":

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "description": null, "amount": 16.00 }'
   ```

6. Try to un-delete through the edit, and to set the owner:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "isDeleted": false, "userId": 1 }'
   ```

**Expected result**

| Step | Status | Effect |
|---|---|---|
| 2 (`{}`) | `200` | Nothing changes. The response equals the current record. No history row is written: the trigger logs only columns that actually changed |
| 3 (`amount`) | `200` | Only `amount` is `15.00`; `txnDate`, `categoryId` and `description` are unchanged |
| 4 (`description: ""`) | `200` | `description` is **cleared**: the **key is absent** from the response. An empty string is how a nullable field is unset |
| 5 (`description: null, amount: 16.00`) | `200` | `amount` becomes `16.00`; the previously cleared `description` is **left as it is** (still absent). `null` and an absent key mean the same thing: unchanged |
| 6 (`isDeleted`, `userId`) | `200` | Both are ignored. `isDeleted` stays as it is — restoring is `POST /{id}/restore`, which writes the history row BR-09 requires — and the owner cannot be changed |

- Across all steps, `"null"` and `""` are **not** interchangeable on any field. For `description` an
  empty string clears it; for `categoryId`, `amount` and `txnDate` a blank is a validation error,
  because those fields have no empty state.
- A validation failure writes **nothing**: a body with one valid and one invalid field is rejected
  whole, and the valid half is not applied. To confirm, send
  `{ "amount": 20.00, "txnDate": "<tomorrow>" }` — expect `400 VALIDATION_ERROR` and that `amount`
  did **not** become `20.00`.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-18 — Ownership: another student's transaction is unreachable (BR-02)

| | |
|---|---|
| **ID** | M4-18 |
| **Title** | Student B cannot read, edit, delete or restore Student A's transaction, and cannot see it in any list |
| **Covers** | BR-02 (ownership enforced server-side, structurally) |
| **Preconditions** | `${USER_A_JWT}` and `${USER_B_JWT}` are valid STUDENT tokens for two **different** accounts. Student A owns a transaction whose id is known (created with `${USER_A_JWT}`). |

**Steps** — all four with **Student B's** token against **Student A's** id.

1. Read it:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions/<A_ID> \
     -H "Authorization: Bearer ${USER_B_JWT}"
   ```

2. List Student B's records, including the trash:

   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/transactions?includeDeleted=true" \
     -H "Authorization: Bearer ${USER_B_JWT}"
   ```

3. Edit it:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/transactions/<A_ID> \
     -H "Authorization: Bearer ${USER_B_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "amount": 9999.00 }'
   ```

4. Delete it:

   ```bash
   curl -i -X DELETE http://localhost:8080/api/v1/transactions/<A_ID> \
     -H "Authorization: Bearer ${USER_B_JWT}"
   ```

5. Restore it:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions/<A_ID>/restore \
     -H "Authorization: Bearer ${USER_B_JWT}"
   ```

6. Re-read it with Student A's token and compare with the values noted before step 1.

**Expected result**

- Steps 1, 3, 4 and 5 each return **`404 Not Found`** with `errorCode` `"NOT_FOUND"` — **not** `403`.
  A response that distinguished "not yours" from "does not exist" would let a client probe for the
  existence of other students' records, so all cases answer identically.
- Step 2: Student A's record is **absent** from Student B's list, even with `includeDeleted=true`.
- Step 6: Student A's record is byte-for-byte unchanged — `amount`, `description` and `isDeleted` are
  exactly as they were. The failed edit and the failed delete changed nothing.
- Ownership is structural, not a check someone remembered: every single-row query takes the caller's
  id alongside the record's, so no endpoint can reach another student's row.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-19 — Ownership of the category, and the retired-category rule

| | |
|---|---|
| **ID** | M4-19 |
| **Title** | Student B cannot file under Student A's personal category (`404`); a retired category is refused with a field error on `categoryId` (BR-07) |
| **Covers** | BR-02 (only the caller's own category or a shared default); BR-07 (a retired category takes no new record) |
| **Preconditions** | `${USER_A_JWT}` and `${USER_B_JWT}`. Student A has a **personal** category (create one via `POST /api/v1/categories` with Student A's token if needed — it is owned by the caller). The Food category id is known. |

**Steps**

1. Create a personal category for Student A:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/categories \
     -H "Authorization: Bearer ${USER_A_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "name": "Coffee runs", "type": "EXPENSE" }'
   ```

   Note the returned `id` (call it `<A_CAT>`).

2. With **Student B's** token, try to file a transaction under it:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${USER_B_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": <A_CAT>, "amount": 1.00, "txnDate": "<today>" }'
   ```

3. With Student B's token, try a category id that does not exist:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${USER_B_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": 99999999, "amount": 1.00, "txnDate": "<today>" }'
   ```

4. With **Student A's** token, retire that same personal category:

   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/categories/<A_CAT> \
     -H "Authorization: Bearer ${USER_A_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "isActive": false }'
   ```

5. With Student A's token, try to file a **new** record under the retired category:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${USER_A_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": <A_CAT>, "amount": 1.00, "txnDate": "<today>" }'
   ```

**Expected result**

- Step 1: `201 Created`; the new category is owned by Student A.
- Step 2: **`404 Not Found`** with `errorCode` `"NOT_FOUND"` — a category belonging to another
  student is treated exactly like one that does not exist, so the endpoint cannot be used to discover
  which category ids exist.
- Step 3: `404 Not Found`, `"NOT_FOUND"` — the same answer as step 2, which is the point.
- Step 4: `200 OK`; the category is now retired (`isActive: false`).
- Step 5: **`400 Bad Request`** with `errorCode` `"VALIDATION_ERROR"` and a `fieldErrors` entry whose
  `"field"` is **`"categoryId"`**. Retirement is how BR-07 stops new records being filed under a
  category while keeping the records already filed under it readable.

> **Related, optional.** A record that was filed **before** its category was retired stays editable:
> retiring a category would otherwise freeze every record inside it. Editing such a record's
> `amount` or `description` (without moving it) should still return `200`. Moving it to a *different*
> retired category, however, is a new filing decision and is refused with the same `categoryId` field
> error.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-20 — An administrator's token is refused, and an anonymous caller is refused

| | |
|---|---|
| **ID** | M4-20 |
| **Title** | `ADMIN` gets `403 ACCESS_DENIED`; no token gets `401 UNAUTHENTICATED` |
| **Covers** | The role rule on `/api/v1/transactions/**`; BR-03 (the token itself is what is checked) |
| **Preconditions** | `${ADMIN_JWT}` was obtained from `POST /api/v1/admin/auth/login`. A transaction id is known. |

**Steps**

1. List with the administrator's token:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${ADMIN_JWT}"
   ```

2. Create with the administrator's token:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${ADMIN_JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": 6, "amount": 1.00, "txnDate": "<today>" }'
   ```

3. Delete with the administrator's token:

   ```bash
   curl -i -X DELETE http://localhost:8080/api/v1/transactions/<ID> \
     -H "Authorization: Bearer ${ADMIN_JWT}"
   ```

4. Make the same list call with **no** `Authorization` header:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions
   ```

5. Make the same list call with a **tampered** token — take the real token and change one character
   in its middle:

   ```bash
   curl -i -X GET http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer <the tampered value>"
   ```

**Expected result**

- Steps 1, 2 and 3: **`403 Forbidden`** with `errorCode` `"ACCESS_DENIED"`. The whole
  `/api/v1/transactions/**` path requires the `STUDENT` role. An administrator reads aggregates
  through the administration API (modules 11), and letting one through here would create a
  transaction owned by that administrator. This is not a sign-in problem, and the client should send
  the user to their own area rather than to the sign-in screen.
- Step 4: **`401 Unauthorized`** with `errorCode` `"UNAUTHENTICATED"`.
- Step 5: **`401 Unauthorized`** with `errorCode` `"UNAUTHENTICATED"` — the signature check rejects a
  tampered token before the controller is reached.
- Neither `401` nor `403` reveals whether any record exists.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-21 — Per-field validation errors on create

| | |
|---|---|
| **ID** | M4-21 |
| **Title** | Missing required fields, an over-long description and a malformed body each answer with the right code and the right field |
| **Covers** | UC-07; the `fieldErrors[].field` contract; `MALFORMED_REQUEST` for a body that is not JSON |
| **Preconditions** | `${JWT}` is a valid STUDENT token. The Food category id is known. |

**Steps**

1. An **empty** body:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{}'
   ```

2. Missing `amount`:

   ```json
   { "categoryId": 6, "txnDate": "<today>" }
   ```

3. Missing `txnDate`:

   ```json
   { "categoryId": 6, "amount": 1.00 }
   ```

4. `txnDate` sent explicitly as `null`:

   ```json
   { "categoryId": 6, "amount": 1.00, "txnDate": null }
   ```

5. A description of 256 characters (the limit is 255, measured **after trimming**):

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d "{\"categoryId\": 6, \"amount\": 1.00, \"txnDate\": \"<today>\", \"description\": \"$(printf 'x%.0s' {1..256})\"}"
   ```

6. A body that is **not JSON**:

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d 'this is not json'
   ```

**Expected result**

| Step | Status | `errorCode` | `fieldErrors` |
|---|---|---|---|
| 1 (`{}`) | `400` | `VALIDATION_ERROR` | entry with `"field": "categoryId"` (all three required fields may be listed; `categoryId` must be among them) |
| 2 | `400` | `VALIDATION_ERROR` | entry with `"field": "amount"` |
| 3 | `400` | `VALIDATION_ERROR` | entry with `"field": "txnDate"` |
| 4 | `400` | `VALIDATION_ERROR` | entry with `"field": "txnDate"` — sending `null` is the same as omitting it |
| 5 | `400` | `VALIDATION_ERROR` | entry with `"field": "description"` |
| 6 | `400` | `MALFORMED_REQUEST` | **no** `fieldErrors` — there is no field to point at |

Additional checks:

- `categoryId`, `amount` and `txnDate` are all **required**: a body that omits one is refused rather
  than defaulted. In particular a missing date is not filled in with today — the date is a fact about
  when the money moved, not a server choice.
- The failure body has this shape:

  ```json
  {
    "timestamp": "…",
    "status": 400,
    "errorCode": "VALIDATION_ERROR",
    "message": "Request validation failed.",
    "path": "/api/v1/transactions",
    "fieldErrors": [
      { "field": "amount", "message": "Amount must be greater than zero." },
      { "field": "txnDate", "message": "Choose today or an earlier date." }
    ]
  }
  ```

- The property is `fieldErrors[].field` — **never** `path`. Assert this explicitly.
- No response body contains a stack trace, a SQL statement, a driver message, a table or column name,
  or a Java class name.
- A field error names a **body** field. A malformed query or path parameter (M4-09 step 5, M4-10
  step 3) is also `400` but carries **no** `fieldErrors`, because a parameter is not a body field.

**Result:** `- [ ] Pass  - [ ] Fail`

---

### M4-22 — A large expense raises a budget alert (UC-14), observed in the database

| | |
|---|---|
| **ID** | M4-22 |
| **Title** | Recording an expense that crosses a budget threshold raises exactly one alert per threshold |
| **Covers** | UC-14 and BR-12, as they are reached **through** this module's write path |
| **Preconditions** | Use the demo student account (the one preloaded by `db/06_demo.sql`), which has a **Food** budget of `30.00` for the current month and `24.00` already spent on Food in it — the seeded state is exactly 80%, which has already raised one "approaching" alert. You can query the database. |

> **Run this case first, or on a clean account.** The numbers below assume the Food spending for the
> current month is exactly the seeded `24.00`. If you have already run M4-01, M4-05 or M4-07 against
> the same account with the Food category, the month's spend is higher and the percentages in the
> expected result will not match. Either run M4-22 before the other cases, or read the actual
> `spent_amount` in step 1 and adjust the amounts you send so the total crosses the limit — the
> observable behaviour (one new `EXCEEDED` row, and no repeat on the second crossing) is the same.

**Scope note — read this first.** This module **does not know that budgets exist** and exposes no
budget or notification endpoint. The alert is raised inside the database, by the same triggers that
write the transaction, and it is delivered through the **notifications API of module 6** (UC-14).
What a manual tester can verify from this module is therefore the *side effect on the record*: the
expense is recorded normally (`201`), the response contains nothing about the alert, and the alert
rows appear in the database. Do not expect a field in the `201` response, and do not expect this
module to let you read or suppress the alert.

**Steps**

1. Record the Food state before the change:

   ```sql
   SELECT id, limit_amount FROM budgets
    WHERE category_id = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Food')
      AND period_month = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE);
   ```

   And the alert rows already present for it:

   ```sql
   SELECT threshold_type, threshold_pct, consumed_pct, spent_amount
     FROM budget_alert_log ORDER BY id;
   ```

2. Through the API, record a **`7.00`** Food expense dated **today** (so it falls in the current
   month):

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/transactions \
     -H "Authorization: Bearer ${JWT}" \
     -H "Content-Type: application/json" \
     -d '{ "categoryId": 6, "amount": 7.00, "txnDate": "<today, YYYY-MM-DD>", "description": "Budget alert trigger" }'
   ```

3. Re-run the two SQL statements from step 1.

4. Record a **second** expense under the same Food category in the same month (e.g. `5.00`), so the
   spend stays above 100%.

5. Re-run the alert query again.

**Expected result**

- Step 2: `201 Created`, and the body is the ordinary transaction object of §1.4. It contains **no**
  alert field, no `notificationId` and no budget information at all.
- Step 3: a **new** row appears in `budget_alert_log` for that budget with
  `threshold_type = 'EXCEEDED'`, `threshold_pct = 100` and `consumed_pct` above 100 (24.00 + 7.00 =
  31.00 against a limit of 30.00). The earlier **`NEAR`** row at exactly 80% from the seeded data is
  still there, unchanged — it was not rewritten.
- A matching row appears in `notifications` with `type = 'BUDGET_EXCEEDED'` and a title naming the
  category. That is the notification module 6 delivers; this module neither writes nor reads it
  directly.
- Step 5: **no second `EXCEEDED` row** is created for that budget. Each threshold alerts **once per
  budget** (BR-12), however many times the record is edited or however many further expenses are
  added. The database enforces this with a unique key on `(budget_id, threshold_type)` combined with
  an `INSERT IGNORE`, so the guarantee does not depend on the application.
- The alert commits or rolls back **with the transaction**: a record that is refused leaves the alert
  log untouched.

> **What is not verifiable through this module.** Whether the alert is delivered, read or marked as
> read is module 6's contract, and there is no endpoint here that lists notifications. Only the
> database side effect is observable from this module's world.

**Result:** `- [ ] Pass  - [ ] Fail`

---

## 3. Traceability

| Test ID | Title (short) | UC | BR | Endpoint(s) |
|---|---|---|---|---|
| M4-01 | Record an expense | UC-07 | — | `POST /api/v1/transactions` (18) |
| M4-02 | Record an income | UC-07 | — | `POST /api/v1/transactions` (18) |
| M4-03 | The type is the category's, not the request's | UC-07 | BR-05 | `POST /api/v1/transactions` (18) |
| M4-04 | Moving a record changes its type | UC-10 | BR-05 | `PATCH /api/v1/transactions/{id}` (19) |
| M4-05 | Amount strictly positive and fits the column | UC-07 | BR-08 (amount), `ck_txn_amount`, `DECIMAL(15,2)` | `POST /api/v1/transactions` (18) |
| M4-06 | A future date is refused | UC-07, UC-10 | BR-08 | `POST` (18), `PATCH` (19) |
| M4-07 | Today and past dates are accepted | UC-07 | BR-08 (boundary) | `POST /api/v1/transactions` (18) |
| M4-08 | List history: ordering, total order, no pagination | UC-10 | — | `GET /api/v1/transactions` (16) |
| M4-09 | Date-range filtering and its failures | UC-10 | — | `GET /api/v1/transactions` (16) |
| M4-10 | Read one by id | UC-10 | — | `GET /api/v1/transactions/{id}` (17) |
| M4-11 | Soft delete, and the row leaves the totals | UC-10 | BR-09 | `DELETE /api/v1/transactions/{id}` (20) |
| M4-12 | Deleting twice is a conflict | UC-10 | — | `DELETE /api/v1/transactions/{id}` (20) |
| M4-13 | View the trash | UC-10 A1 | BR-09 | `GET /api/v1/transactions?includeDeleted=true` (16) |
| M4-14 | Restore a deleted record | UC-10 A1 | BR-09 (history appended) | `POST /api/v1/transactions/{id}/restore` (21) |
| M4-15 | Restoring a live record is a conflict | UC-10 A1 | — | `POST /api/v1/transactions/{id}/restore` (21) |
| M4-16 | A hard delete is impossible | UC-10 | BR-09 | no endpoint (schema: `trg_transactions_before_delete`) |
| M4-17 | Partial update semantics | UC-10 | — | `PATCH /api/v1/transactions/{id}` (19) |
| M4-18 | Another student's transaction is unreachable | UC-10 | BR-02 | `GET/{id}` (17), `GET` (16), `PATCH` (19), `DELETE` (20), `POST /restore` (21) |
| M4-19 | Another student's category; the retired-category rule | UC-07, UC-10 | BR-02, BR-07 | `POST /api/v1/transactions` (18) |
| M4-20 | Administrator refused; anonymous refused | UC-07, UC-10 | BR-03 | all six (16–21) |
| M4-21 | Per-field validation errors | UC-07 | — | `POST /api/v1/transactions` (18) |
| M4-22 | A large expense raises a budget alert | UC-14 (module 6) | BR-12 | `POST /api/v1/transactions` (18), observed in the database |

### UC / BR coverage summary

| Requirement | Covered by |
|---|---|
| UC-07 — record an income or expense | M4-01, M4-02, M4-03, M4-05, M4-07, M4-19, M4-21, M4-22 |
| UC-10 — view, edit and delete transactions | M4-04, M4-06, M4-08, M4-09, M4-10, M4-11, M4-12, M4-17, M4-18 |
| UC-10 A1 — restore | M4-13, M4-14, M4-15 |
| BR-02 — ownership | M4-18, M4-19 |
| BR-03 — a revoked or invalid token stops working | M4-20 |
| BR-05 — the category decides the type | M4-03, M4-04 |
| BR-07 — a retired category takes no new record | M4-19 |
| BR-08 — no future date; positive amount | M4-05, M4-06, M4-07 |
| BR-09 — soft delete only, with full history | M4-11, M4-13, M4-14, M4-16 |
| BR-12 — each threshold alerts once per budget | M4-22 |
| UC-14 — budget alerts (raised by the database; delivered by module 6) | M4-22 (database side effect only) |

---

## 4. Not covered by this procedure, and why

These are recorded deliberately rather than left as gaps. None of them is a missing test — each is a
behaviour the implementation does not expose, so there is nothing to execute by hand.

| Not covered | Reason |
|---|---|
| Pagination of the history | Not implemented. Neither UC-07 nor UC-10 asks for it, and no `page`, `size`, `offset` or `sort` parameter exists. M4-08 records this explicitly instead of testing a parameter that is not there. |
| Filtering the history by `type`, `categoryId` or description text | Not implemented. The only filters are `from`, `to` and `includeDeleted`. Inventing a filter test would invent a contract. |
| A hard-delete **endpoint** | Does not exist. BR-09 is enforced in the schema; M4-16 verifies it with a direct SQL statement and states that no HTTP status is involved. |
| The `409 DATA_CONFLICT` translation for a hard delete | Not reachable through the API, because no API call can attempt a hard delete. It is exercised only by the module's own test suite, not by a manual API call. |
| Reading or acting on a budget alert / notification | UC-14 belongs to **module 6**. This module raises the alert as a side effect but exposes no endpoint to read, list or suppress it. M4-22 verifies the database effect and says so. |
| Balances, running totals, dashboard figures, reports | Computed server-side from views for modules 6 and 7. This module has no totals endpoint; M4-11 checks the effect on totals directly in the database. |
| Editing or clearing `isDeleted` / `deletedAt` through a request body | Not accepted by design (BR-09). The fields are absent from every request; M4-17 verifies they are ignored, and M4-11/M4-14 verify the transitions happen only through `DELETE` and `/restore`. |
| Changing a category's own `type` once records reference it | Belongs to UC-06 / module 3. `trg_categories_before_update` refuses it. Only the *transaction's* type change (by moving category) is tested here, in M4-04. |
| CSV import, AI suggestions, anomaly flags, recurring occurrences | UC-11, UC-08, UC-24 and UC-09 — later modules. Those write their own rows through their own contracts, and the corresponding columns are deliberately unmapped on this module's entity. |

---

## Related documentation

- [`docs/api/transactions.md`](../../api/transactions.md) — the authoritative endpoint, field,
  status and error-code reference for endpoints 16–21.
- [`docs/modules/MODULE_04_TRANSACTIONS.md`](../../modules/MODULE_04_TRANSACTIONS.md) — the module
  report: behaviours, the database-enforced rules, and the adversarial review.
- [`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) — endpoints 16–21 in the full inventory.
- [`docs/CREDENTIALS.md`](../../CREDENTIALS.md) — the seeded accounts used to obtain the tokens.
- [`docs/api/authentication.md`](../../api/authentication.md) — how to call sign-in and read
  `accessToken` from the response.
- [`docs/api/categories.md`](../../api/categories.md) — module 3, whose `categoryId` rule this module
  follows.
