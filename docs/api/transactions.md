# Transactions API

Module 4 of the Campus Coin backend. Covers **UC-07 (record income and expenses)** and
**UC-10 (view, edit and delete transactions)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint · **Required role** `STUDENT`

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [The type is the category's, not the record's](#4-the-type-is-the-categorys-not-the-records)
5. [GET /api/v1/transactions](#5-get-apiv1transactions)
6. [GET /api/v1/transactions/{id}](#6-get-apiv1transactionsid)
7. [POST /api/v1/transactions](#7-post-apiv1transactions)
8. [PATCH /api/v1/transactions/{id}](#8-patch-apiv1transactionsid)
9. [DELETE /api/v1/transactions/{id}](#9-delete-apiv1transactionsid)
10. [POST /api/v1/transactions/{id}/restore](#10-post-apiv1transactionsidrestore)
11. [The rules the database enforces, not this module](#11-the-rules-the-database-enforces-not-this-module)
12. [Status codes](#12-status-codes)
13. [Angular integration notes](#13-angular-integration-notes)
14. [Security properties](#14-security-properties)
15. [Traceability](#15-traceability)

---

## 1. Scope and what is deliberately absent

UC-07 records one income or expense. UC-10 lists records, reads one, edits one and removes one,
with the removal recoverable. This module implements exactly that.

| Not implemented | Why |
|---|---|
| A `type` field on create or update | `transactions` has no type column. The type **is** the chosen category's type (BR-05), so accepting one would create a second value able to disagree with the first — see §4 |
| A `source` field on create or update | Provenance, not a user choice. Every row this API writes is `MANUAL`. This is a security boundary rather than tidiness: `sp_validate_transaction` exempts `RECURRING` from the BR-08 date check, so a client able to claim it could record a future-dated expense, and a future-dated expense corrupts every balance until the date arrives |
| `userId` on any request | The owner is the account in the bearer token. Accepting it would make BR-02 a check that could be forgotten instead of a structural property |
| `isDeleted` / `deletedAt` on any request | UC-10's soft delete owns them, and BR-09 needs the removal logged. A body able to set the flag would move the record without a history row, which is the one thing the soft-delete design exists to prevent |
| `PUT` for the whole transaction | UC-10 edits values; it does not replace a record. `PATCH` is the method that matches |
| `/transactions/deleted`, `/all`, `/list` | `GET /api/v1/transactions` answers all three: the default is live records, `includeDeleted=true` adds the trash, `from`/`to` narrow the range. Three paths would be three ways to ask one thing |
| `/profile/me/transactions` | Unlike the profile, a transaction is one of many and is addressed by id, so ownership is enforced by the query rather than implied by the URL. Filing the list under `/profile/me` would suggest the wrong model to modules 5 and 6, which also address transactions by id |
| Pagination | Neither UC-07 nor UC-10 asks for it, and the frontend loads the list whole to build its month view and running totals. The date range is the mechanism the use case describes for narrowing a result — **but note that a request with no `from` reaches back to the earliest representable date**, so the bound it provides is the student's own history, not a page. If a later screen needs paging, this is the decision to revisit, and `from`/`to` are already the right parameters to build it on |
| A bulk or batch create | The CSV import is UC-11, module 12, and it writes its own rows through its own contract |
| The AI suggestion columns | UC-08, module 12 |
| The anomaly flags (`isFlagged`, `flagType`, `flagNote`) | UC-24, module 12. A client able to set `flagType` could mark its own record as reviewed, which is exactly the signal the feature exists to raise |
| Budget alerts | UC-14, module 6 — raised by the database's own triggers. This module does not know that budgets exist; recording an expense raises any alert it crosses without the client asking |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 16 | `GET` | `/api/v1/transactions` | UC-10 | List my records, optionally in a date range | `200` |
| 17 | `GET` | `/api/v1/transactions/{id}` | UC-10 | Read one of mine | `200` |
| 18 | `POST` | `/api/v1/transactions` | UC-07 | Record an income or expense | `201` |
| 19 | `PATCH` | `/api/v1/transactions/{id}` | UC-10 | Change some fields of one of mine | `200` |
| 20 | `DELETE` | `/api/v1/transactions/{id}` | UC-10 | Move one of mine to the trash | `204` |
| 21 | `POST` | `/api/v1/transactions/{id}/restore` | UC-10 A1 | Bring one back out of the trash | `200` |

All six require `Authorization: Bearer <accessToken>` and an account whose role is `STUDENT`. An
administrator token is refused with `403` — see §14.

Both write endpoints that return a body return the transaction **as it now is in the database**, so
the client can replace its cached copy rather than guess what the server did with the values it sent.

---

## 3. Field reference

The response of every endpoint, and the request fields of the two write endpoints.

| Field | Type | In request | Validation when supplied | Source |
|---|---|---|---|---|
| `id` | number | read-only | — | `transactions.id` |
| `categoryId` | number | required on create, optional on update | must be a category the caller may use, and not retired | `transactions.category_id` |
| `categoryName` | string | read-only | — | `categories.name` |
| `categoryIcon` | string | read-only | — | `categories.icon` |
| `categoryColor` | string | read-only | — | `categories.color` |
| `type` | string enum | **never accepted** | — | `categories.type` — derived, see §4 |
| `amount` | number | required on create, optional on update | strictly greater than zero, at most two decimal places, at most 13 digits before the point | `transactions.amount` |
| `txnDate` | string `YYYY-MM-DD` | required on create, optional on update | not in the future (BR-08) | `transactions.txn_date` |
| `description` | string | optional | at most 255 characters after trimming, newlines permitted | `transactions.description` |
| `source` | string enum | **never accepted** | — | `transactions.source` — always `MANUAL` here |
| `isDeleted` | boolean | **never accepted** | — | `transactions.is_deleted` |
| `deletedAt` | string date-time | **never accepted** | — | `transactions.deleted_at` |

### Fields that are absent rather than null

`categoryIcon`, `categoryColor`, `description` and `deletedAt` are **omitted from the response**
when they have no value, rather than sent as `null`. The Angular model marks `description` and
`note` optional, which is only truthful if the API omits rather than fills.

Every other field is always present. In particular `isDeleted` is never absent: it is a boolean
with a value in every state, so a client can read it unconditionally.

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

That record has no `description` and is not deleted, so both keys are missing. There is one rule to
apply — "no value means the key is absent" — rather than two.

### Amounts

`amount` is a JSON number with at most two decimal places. It is always **positive**; whether the
money came in or went out is `type`, never the sign. `-12.50` is a field error, not an expense.

Three decimal places are refused rather than rounded. MySQL would round `1.005` to `1.01` on the way
in, so the value the student typed and the value stored would differ and no screen would say so.

The column is `DECIMAL(15,2)`: thirteen digits before the point, two after.

### Dates and times

`txnDate` is a calendar date with no time and no zone: `"2026-09-24"`. It is the date the money
moved, which is a fact about the student's day, not about the server's clock.

**"Today" is judged in `Asia/Ho_Chi_Minh` (`+07:00`)** — the same offset the database session is
pinned to, so the API and MySQL's `CURDATE()` cannot disagree about which day it is. A client in
another zone should send the date as the student experienced it, not as UTC.

`deletedAt` is a date-time without an offset, e.g. `"2026-09-25T09:30:00"`, and only appears on a
record in the trash.

### Enum values are member names, never numbers

`type` accepts the member name the database stores, in upper case: `"INCOME"` or `"EXPENSE"`. It
does **not** accept a number — `0` is rejected with a field error rather than read as the ordinal
`INCOME`. Lower case (`"expense"`) is also rejected. Send the member name exactly.

`source` is one of `MANUAL`, `CSV`, `RECURRING`. Only `MANUAL` is reachable through this module,
and only as a read.

### Defaults for a new record

As the database declares them: `description` is `null`, `source` is `MANUAL` (set by the server, not
defaulted by the column), `isDeleted` is `false` and `deletedAt` is `null`. Nothing else is optional
— `categoryId`, `amount` and `txnDate` are all required.

---

## 4. The type is the category's, not the record's

This is the central decision of the module and the one most likely to surprise a frontend that
already has a mock model.

`transactions` has **no `type` column**. A record's type is its category's type, and BR-05 requires
the two to agree. The only way to guarantee that is to keep one source of truth, so the source of
truth is `categories.type` and the transaction simply does not have a second copy of it.

What follows for a client:

| Question | Answer |
|---|---|
| Can I send `type` on create? | No. The field is not in the request; sending it changes nothing |
| Can I change a record's type? | Only by moving it to a category of the other type (`categoryId` on `PATCH`). Moving an expense to an income category is a legitimate correction of a misfiled record and is allowed |
| Where does `type` in the response come from? | `categories.type` for the category the record is filed under. It is read-only |
| What happens if the category's type is changed later? | It cannot be, once anything references it. `trg_categories_before_update` refuses the change, because it would silently rewrite history — every recorded expense would read back as income in the reports. To genuinely move a category, create a new one and move the data across |

A picker therefore needs nothing beyond the category list: choosing a category chooses the type.

---

## 5. `GET /api/v1/transactions`

Returns the caller's transactions, newest first.

### Request

| Parameter | In | Type | Required | Default |
|---|---|---|---|---|
| `from` | query | date `YYYY-MM-DD` | no | the earliest date the column can hold |
| `to` | query | date `YYYY-MM-DD` | no | today |
| `includeDeleted` | query | boolean | no | `false` |

Both bounds are **inclusive**. Either may be omitted.

**The default `to` is today, so omitting it hides any record dated later.** Nothing this API creates
can be dated ahead (BR-08), so in this module the default costs nothing. It is stated plainly here
because it is a real bound: BR-08 exempts recurring records, and the recurrence scheduler (UC-09,
module 5) writes occurrences ahead of their date. Those will not appear in an unbounded list until
their date arrives. If a client wants them, it passes `to` explicitly — the parameter is already
there, and no contract change is needed when module 5 lands.

`to` may also be set later than today. Nothing changes in this module, because a row only this API
creates cannot be dated ahead — but the bound is honoured literally rather than clamped, so a client
that asks for a later range is answered for that range.

```bash
curl -X GET "http://localhost:8080/api/v1/transactions?from=2026-09-01&to=2026-09-30" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

```bash
curl -X GET "http://localhost:8080/api/v1/transactions?includeDeleted=true" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

A JSON **array**, ordered by `txnDate` descending and then by `id` descending. The `id` tie-break
matters: several records commonly share a date, and without it MySQL may return equal rows in either
order, so the list would appear to shuffle between two identical calls. The order is total and is
the order to display in.

```json
[
  {
    "id": 31,
    "categoryId": 6,
    "categoryName": "Food",
    "categoryIcon": "utensils",
    "categoryColor": "#F97316",
    "type": "EXPENSE",
    "amount": 14.50,
    "txnDate": "2026-09-24",
    "description": "Campus Dining Hall",
    "source": "MANUAL",
    "isDeleted": false
  },
  {
    "id": 30,
    "categoryId": 1,
    "categoryName": "Allowance",
    "categoryIcon": "wallet",
    "categoryColor": "#22C55E",
    "type": "INCOME",
    "amount": 200.00,
    "txnDate": "2026-09-01",
    "source": "MANUAL",
    "isDeleted": false
  }
]
```

An account with no records gets `[]`, not `null` and not `404`.

`includeDeleted=true` adds the records in the trash, each carrying `"isDeleted": true` and a
`deletedAt`. That is how a client finds a record again in order to restore it (UC-10 A1).

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `from` is later than `to`; `fieldErrors` names `from` |
| `400` | `INVALID_REQUEST` | A date parameter is malformed, or `includeDeleted` is not a boolean. No `fieldErrors`, because a query parameter is not a body field |
| `401` | `UNAUTHENTICATED` | No token, or it is malformed, tampered with, expired, revoked, or the account was disabled after the token was issued (BR-03) |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |

An inverted range is a `400` rather than an empty list. A client that wrote the two bounds the wrong
way round has a bug, and answering "no records" would let that bug look like an empty month.

---

## 6. `GET /api/v1/transactions/{id}`

Reads one of the caller's own transactions, so a client can refresh a single row without reloading
the list.

### Request

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | number | yes |

```bash
curl -X GET http://localhost:8080/api/v1/transactions/31 \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

One transaction in the shape of §3.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `{id}` is not a number, is not an integer, or is too large for the column |
| `401` | `UNAUTHENTICATED` | As §5 |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such transaction **of the caller's** — including one belonging to another student, one that does not exist, and one that is in the trash |

A deleted record answers `404` here, and so does another student's. Both are the same answer on
purpose: a response that distinguished them would let a client probe for the existence of other
students' records. To read a deleted record, use the list with `includeDeleted=true`.

---

## 7. `POST /api/v1/transactions`

Records one income or expense owned by the caller.

### Request

| Field | Type | Required | Notes |
|---|---|---|---|
| `categoryId` | number | **yes** | A category the caller may file under, not retired |
| `amount` | number | **yes** | Strictly greater than zero, at most two decimal places |
| `txnDate` | string `YYYY-MM-DD` | **yes** | Not in the future (BR-08) |
| `description` | string | optional | At most 255 characters after trimming; an empty string stores nothing |

```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "categoryId": 6,
        "amount": 14.50,
        "txnDate": "2026-09-24",
        "description": "Campus Dining Hall"
      }'
```

The category may be the caller's own or one of the twelve shared default categories. A category
belonging to **another student** is not usable, and answers `404` rather than `403`, identically to
an id that does not exist.

A **retired** category (`isActive: false`) answers `400` naming `categoryId`. BR-07 retires a
category instead of deleting it so that the records already filed under it keep their category; the
retirement is what stops new records being filed under it.

### Response `201 Created`

The transaction that was recorded, in the shape of §3, including its assigned `id`. `type` and
`source` come back filled in.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A field failed validation, or the category is retired, or the date is in the future; `fieldErrors` names the field |
| `400` | `MALFORMED_REQUEST` | The body is missing, truncated, or not JSON |
| `401` | `UNAUTHENTICATED` | As §5 |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such category of the caller's to file under |
| `409` | `DATA_CONFLICT` | The category or the day changed while the request was in flight. Reload and try again |

**Recording an expense may also create a notification.** UC-14 raises a budget alert when spending
crosses 80% and again at 100% of a category's monthly limit. The client does not ask for that, cannot
suppress it, and does not see it in this response — it arrives through the notifications API in
module 6. Each threshold alerts **once per budget** however many times the record is edited
(BR-12), which the database enforces with a unique key rather than the application.

---

## 8. `PATCH /api/v1/transactions/{id}`

Changes the supplied fields of one of the caller's own transactions and leaves the rest as they are.

### Request

A JSON object containing **only the fields to change**. `categoryId`, `amount`, `txnDate` and
`description` are accepted; every other field in §3 is rejected by omission — sending one changes
nothing.

```bash
curl -X PATCH http://localhost:8080/api/v1/transactions/31 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "amount": 15.00, "description": "Campus Dining Hall (corrected)" }'
```

### Partial update semantics

| Body | Effect |
|---|---|
| `{}` | Nothing changes. No `UPDATE` statement is issued, and no history row is written |
| `{ "amount": "15.00" }` | Only `amount` changes |
| `{ "description": "" }` | `description` is **cleared** (set to `null`) |
| `{ "description": null }` | `description` is **left as it is** |
| `{ "categoryId": 8 }` | The record moves category, and its `type` changes with it if the new category's type differs (§4) |
| `{ "amount": "15.00", "description": "" }` | Both change |
| `{ "isDeleted": false }` | Ignored. Restoring is `POST /{id}/restore`, which writes the history row BR-09 requires |
| `{ "userId": 5 }` | Ignored. The owner cannot be changed |
| `{ "isFlagged": true }` | Ignored. The anomaly flags belong to UC-24 |

The asymmetry is deliberate and matches modules 2 and 3: `description` is nullable, so it needs a way
to be unset — an empty string does that. `categoryId`, `amount` and `txnDate` have no empty state, so
a blank there is a validation error rather than a silent no-op. For every field, `null` and an absent
key mean the same thing: unchanged.

**`null` and `""` are therefore not interchangeable on any field.**

A validation failure writes **nothing**: a body with one valid and one invalid field is rejected
whole, and the valid half is not applied.

### The active-category rule applies only to a move

Choosing a category is a filing decision, so the new category must be active — the same rule as on
creation. But a record already filed under a category that was **retired later stays editable**:
retiring a category would otherwise freeze every record inside it. So the check is applied exactly
when the record is being moved, and not otherwise.

### Response `200 OK`

The updated transaction in the shape of §3.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` / `MALFORMED_REQUEST` | As §7 |
| `400` | `INVALID_REQUEST` | `{id}` is not a number |
| `401` | `UNAUTHENTICATED` | As §5 |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such transaction or category of the caller's. A **deleted** record also answers `404` here |
| `409` | `DATA_CONFLICT` | The record or its category changed while the request was in flight |

A deleted record cannot be edited. Restore it first with `POST /{id}/restore`.

Repeating an identical update is idempotent and writes nothing the second time: the history trigger
records only the columns that actually changed, so a second identical `PATCH` adds no history row.

---

## 9. `DELETE /api/v1/transactions/{id}`

Moves the record to the trash.

### Request

```bash
curl -X DELETE http://localhost:8080/api/v1/transactions/31 \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `204 No Content`

No body.

**This is a soft delete.** The row is kept, its history is preserved, and it can be brought back with
§10. No endpoint in this API removes a transaction outright — the database refuses a hard `DELETE`
outright, for every caller, which is BR-09.

A deleted record disappears from the list, from `GET /{id}`, from every balance and from every
report. The row survives with `isDeleted: true` and a `deletedAt` stamp, and the change is appended
to `transaction_history` as a `DELETE` row.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `{id}` is not a number |
| `401` | `UNAUTHENTICATED` | As §5 |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such transaction of the caller's |
| `409` | `TRANSACTION_ALREADY_DELETED` | The record is already in the trash |

Deleting an already-deleted record answers `409` rather than a second `204`. The row is still there —
reporting "not found" would be false — and reporting success would tell the client something happened
that did not. The caller's copy of the record's state is out of date, and the remedy is to reload.

---

## 10. `POST /api/v1/transactions/{id}/restore`

Brings a record back out of the trash (UC-10 A1).

### Request

No body.

```bash
curl -X POST http://localhost:8080/api/v1/transactions/31/restore \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

The restored transaction in the shape of §3, with `isDeleted: false` and no `deletedAt`. The record
returns to the list, the balances and the reports.

Its history keeps every step: the log reads `CREATE` → `DELETE` → `RESTORE` rather than being
rewritten. The earlier rows are left exactly as they were, which is what BR-09 requires of a log.

To find a deleted record in order to restore it, list with `includeDeleted=true` (§5).

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `{id}` is not a number |
| `401` | `UNAUTHENTICATED` | As §5 |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such transaction of the caller's |
| `409` | `TRANSACTION_NOT_DELETED` | The record is not in the trash, so there is nothing to restore |

Restoring a live record answers `409`. The database would accept the call and change nothing;
answering "restored" for a request that did nothing would misreport the outcome for a client whose
list is out of date.

---

## 11. The rules the database enforces, not this module

This module does not decide these rules and does not restate them in Java. They belong to the schema,
where they hold for every caller — including a hand-run SQL statement — and they are translated into
the errors above on the way out.

| Rule | Enforced by | Surfaced as |
|---|---|---|
| A record's date may not be in the future (BR-08) | `sp_validate_transaction`, via `trg_transactions_before_insert` and `..._before_update` | `400` naming `txnDate` |
| A record may only be filed under the caller's own category or a shared default (BR-02) | `sp_validate_transaction` | `404` naming no field |
| A record may not be filed under a retired category (BR-07) | `sp_validate_transaction`, with `require_active = 1` on insert only | `400` naming `categoryId` |
| The amount must be strictly positive | `ck_txn_amount` | `400` naming `amount` |
| The delete/restore pair is consistent with the timestamp | `ck_txn_deleted` | `409 DATA_CONFLICT` |
| A transaction may not be hard-deleted (BR-09) | `trg_transactions_before_delete` | `409 DATA_CONFLICT` |
| Every create, edit, delete and restore is logged with the full before/after payload (BR-09) | `trg_transactions_after_insert`, `trg_transactions_after_update` | — (the log is written, never refused) |
| A budget threshold alerts once per budget (BR-12) | `budget_alert_log` unique key `(budget_id, threshold_type)`, via `sp_check_budget_alerts` | — (the notification is raised, not refused) |

**Why these are not in Java.** `sp_validate_transaction` signals six different rules with the same
`SQLSTATE '45000'`, so a client told only "the write was refused" could not point at the field that
needs fixing. The service therefore asks three of the same questions first, purely so it can name the
field, and the trigger still runs afterwards. The database's rule is the one that holds; the
application's check is a stricter restatement made for the error message, and a request that slips
past it is caught by the translation below rather than accepted.

**Refusals are translated by SQLSTATE, never by message.** `SIGNAL SQLSTATE '45000'` arrives through
Spring as `InvalidDataAccessResourceUsageException` — not `PersistenceException` — so the classifier
walks the cause chain for the SQLSTATE rather than matching the driver's text, which is localised and
not a contract. A recognised refusal becomes `409 DATA_CONFLICT` with a message the student can act
on; anything unrecognised is rethrown and answered as an internal error rather than mislabelled.

---

## 12. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body and replace the cached record |
| `201` | Created | Add the returned record to the cached list |
| `204` | Deleted, no body | Remove the record from the list, or mark it hidden |
| `400` | Validation failed, a category is retired, a date is in the future, or the body/parameter is malformed | If `fieldErrors` is present, show each `message` beside its `field`. Otherwise show `message` |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `403` | The token's role is not `STUDENT` | Send the user to their own area; this is not a sign-in problem |
| `404` | No such transaction or category of the caller's | Reload; the row is gone, deleted, or was never the caller's |
| `409` | `TRANSACTION_ALREADY_DELETED`, `TRANSACTION_NOT_DELETED` or `DATA_CONFLICT` | Reload the record and show `message`; the client's copy of the state is stale |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

`fieldErrors[].field` is the canonical property name project-wide. It is `field`, never `path`.

Note the difference between a **body** failure and a **parameter** failure: a validation failure on a
field has `fieldErrors`, and a malformed query or path parameter does not, because a parameter is not
a body field and there is nothing to name. Both are `400`.

### Error codes in full

The two state-conflict codes:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "TRANSACTION_ALREADY_DELETED",
  "message": "This transaction has already been deleted.",
  "path": "/api/v1/transactions/31"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "TRANSACTION_NOT_DELETED",
  "message": "This transaction is not deleted, so there is nothing to restore.",
  "path": "/api/v1/transactions/31/restore"
}
```

A validation failure, with one error per bad field:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
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

A refusal the service could not pre-empt, so the client cannot point at a field:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "DATA_CONFLICT",
  "message": "The transaction could not be saved because the data it depends on changed. Refresh and try again.",
  "path": "/api/v1/transactions"
}
```

**Switch on `errorCode`, not on `message`.** The code is the stable part of the contract; the message
is human-readable text that may be reworded. The two `TRANSACTION_*` codes are distinct because the
remedy differs — reload and see it already gone, versus reload and see it already live.

---

## 13. Angular integration notes

### 13.1 The current frontend must be rewired

`frontend/src/app/core/models/transaction.model.ts` declares:

```ts
export interface Transaction {
  id: string;
  userId: string;
  type: TransactionType;
  amount: number;
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  date: string;               // ISO date string YYYY-MM-DD
  description: string;
  recurringFrequency: RecurringFrequency;
  isDeleted?: boolean;
  createdAt?: string;
  note?: string;
}
```

Six changes are needed:

| Frontend today | API field | Change needed |
|---|---|---|
| `id: string` | `id: number` | The API returns the numeric primary key, not a generated `"tx-2026-09-01"` string. Anything comparing ids must compare numbers, and the mock generator that built string ids goes away |
| `date` | `txnDate` | **Renamed.** The column is `txn_date` and the published field is `txnDate`. Every read of `t.date` — including the grouping key in `home-feed.component.ts` and the row in `quick-add.component.ts` — becomes `t.txnDate` |
| `userId` | — | Not in the contract. Ownership is implied by the token; the API never says who owns a row |
| `recurringFrequency` | `source` | **Different concept.** `recurringFrequency` is `'NONE' \| 'DAILY' \| 'WEEKLY' \| 'MONTHLY'`; the API publishes `source`, which is `'MANUAL' \| 'CSV' \| 'RECURRING'`. The frequency of a recurring rule is UC-09, module 5, and does not live on the transaction. For a row created through this API the value is always `'MANUAL'` |
| `note` | — | Not a column. The mock carries both `description` *and* `note`, which are the same idea; the API has one field, `description` |
| `createdAt` | — | Not published. No screen renders it |
| `description: string` | `description?: string` | The API omits it when there is none rather than sending `""` |
| `categoryIcon: string` / `categoryColor: string` | optional | Omitted when the category has none |
| — | `deletedAt?: string` | **New.** Present only on a record in the trash |

`type` stays, and stays read-only: it is the category's type (§4). The Angular `TransactionType` union
already matches the API's two member names.

`transaction.service.ts` is **entirely mock** — it keeps a `signal` seeded from `MOCK_TRANSACTIONS`
and never calls `HttpClient`. Its computed helpers (`getMonthlyBalance`, `getCategoryBreakdown`,
`get6MonthTrend`, `getDailySpending`) are client-side aggregations of that mock, and they are **not**
what the API offers: the dashboard and reports modules compute those figures server-side from the
views (UC-12, UC-15), so they should be dropped here rather than reimplemented against the real
endpoint. `getTransactions`, `getRecentTransactions`, `addTransaction`, `updateTransaction` and
`deleteTransaction` are the five that map onto this contract.

A `getRecentTransactions(limit)` has no endpoint of its own: request the list and slice it, or narrow
it with `from`.

### 13.2 Which call to make

- Entering the transactions screen: `GET /api/v1/transactions` with the month's `from` and `to`. One
  call fills the list and every total the screen shows.
- Opening the edit modal: the row is already in the list — no call needed. Use
  `GET /api/v1/transactions/{id}` only if the list may be stale.
- Saving a new record: `POST /api/v1/transactions` with `categoryId`, `amount`, `txnDate` and
  optionally `description`.
- Saving an edit: `PATCH /api/v1/transactions/{id}` with only the changed fields.
- Deleting: `DELETE /api/v1/transactions/{id}`.
- Opening the trash: `GET /api/v1/transactions?includeDeleted=true`.
- Restoring: `POST /api/v1/transactions/{id}/restore`.

Send the token as `Authorization: Bearer <accessToken>`. No endpoint in this module works without
one.

### 13.3 Sending only what changed

Build the body from the form's **dirty** fields, as in modules 2 and 3:

```ts
const dirty = Object.fromEntries(
  Object.entries(form.controls)
    .filter(([, control]) => control.dirty)
    .map(([name, control]) => [
      name,
      control.value === null && name === 'description' ? '' : control.value,
    ]),
);
if (Object.keys(dirty).length === 0) { return; }
this.transactionService.updateTransaction(id, dirty).subscribe(...);
```

`description` is the only field with an empty state, so it is the only one that maps `null` to `''`.
Doing that for `categoryId`, `amount` or `txnDate` would send a blank where the API expects a value
and turn an untouched field into a validation error.

Never send `type`, `source`, `isDeleted`, `deletedAt`, `userId` or the flag fields. They are not part
of the contract, they change nothing, and sending them hides the fact that they are not writable.

### 13.4 Handling each status

```ts
this.transactionService.createTransaction(body).subscribe({
  next: created => this.toast.show(`Recorded ${created.amount}.`),
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'VALIDATION_ERROR':
        response.error.fieldErrors?.forEach((e: { field: string; message: string }) =>
          this.form.get(e.field)?.setErrors({ server: e.message }));
        break;
      case 'TRANSACTION_ALREADY_DELETED':
      case 'TRANSACTION_NOT_DELETED':
        // The client's copy of the record's state is stale - reload rather than retry.
        this.reload();
        this.toast.show(response.error.message);
        break;
      case 'DATA_CONFLICT':
        this.reload();
        this.toast.show(response.error.message);
        break;
      case 'UNAUTHENTICATED':
        // Covers every reason this module answers 401 - including a disabled account, which the
        // token filter reports as UNAUTHENTICATED rather than as its own code (see §12).
        this.auth.signOutLocally();
        this.router.navigate(['/login']);
        break;
      case 'ACCESS_DENIED':
        this.router.navigate(['/forbidden']);
        break;
      default:
        this.toast.show(response.error?.message ?? 'Something went wrong.');
    }
  },
});
```

A `404` on a record the screen just displayed means it was deleted in another tab; reload rather than
showing an error.

### 13.5 Grouping by day without a timezone bug

`txnDate` is a plain `"YYYY-MM-DD"` string with no time. Parse it as a **local** date or, better,
do not parse it at all — group by the string itself:

```ts
readonly groupedByDay = computed(() => {
  const groups = new Map<string, Transaction[]>();
  for (const t of this.transactions()) {
    (groups.get(t.txnDate) ?? groups.set(t.txnDate, []).get(t.txnDate)!).push(t);
  }
  return [...groups.entries()].sort(([a], [b]) => b.localeCompare(a));
});
```

`new Date('2026-09-24')` is parsed as UTC midnight, so a client west of UTC that formats it back to a
local date shows the 23rd. Comparing and sorting the strings avoids the problem entirely, and the API
already returns them in the right order.

---

## 14. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. Every single-row query takes the caller's id as well as the record's (`TransactionRepository`), so no service method can reach another student's row. `sp_validate_transaction` checks the category's owner in the database too, because `fk_txn_category` only proves the row exists |
| **No identifier probing** | Another student's record, a deleted record and an unknown id all answer `404`, identically, so the endpoints cannot be used to discover which ids exist |
| **No client-supplied identity** | No request type has `userId`, `user_id`, `createdBy` or `id`. A body carrying them is accepted and ignored, and the row is owned by the token's account |
| **No client-supplied provenance** | `source` is absent from both request types and fixed at `MANUAL` by the entity's factory. A client able to claim `RECURRING` could record a future-dated transaction, because that is the one source `sp_validate_transaction` exempts from BR-08 — and a future-dated expense corrupts every balance until the date arrives. Asserted by `claimingRecurringDoesNotUnlockFutureDates` |
| **No client-supplied state** | `isDeleted` and `deletedAt` are absent from every request and mapped `updatable = false` on the entity, so no JPA statement can compete with `sp_soft_delete_transaction` and `sp_restore_transaction`, which own them |
| **No client-supplied flags** | The anomaly flags (UC-24) are unmapped on the entity, so no statement this module builds can write them. A client able to set `flagType` could mark its own record as reviewed |
| **Role enforced server-side** | `/api/v1/transactions/**` requires `hasRole("STUDENT")`. An administrator is refused with `403`: the administrative read path is UC-21/UC-22 under `/api/v1/admin/**`, and letting an administrator through here would create a transaction owned by that administrator |
| **No sensitive fields** | `TransactionMapper` is the single place that decides what leaves the server. `user_id`, the AI columns, the anomaly flags and the row timestamps are not mapped to the response |
| **Disabled account** | Rejected by the token filter before the request reaches the controller, as `401 UNAUTHENTICATED` (BR-03) |
| **Revoked session / stale token** | Rejected by the token filter on every request, as `401 UNAUTHENTICATED` (UC-02 B5) |
| **No lost update** | `Transaction` is annotated `@DynamicUpdate`, so an UPDATE names only the changed columns. Without it a plain Hibernate UPDATE would also write back the `is_deleted` it read, silently un-deleting a record a concurrent request had just deleted |
| **No un-delete without a log row** | Deleting and restoring go through the procedures, and a `PATCH` cannot reach a deleted record at all. Clearing the flag through an edit would change the state without the history row BR-09 exists to require |
| **No SQL, constraint name or trigger text in a response** | Refusals are translated by SQLSTATE, never forwarded. Asserted for every refusal path by `refusalsDoNotLeakDatabaseInternals` |
| **No schema identifiers in the module's own log lines** | A refused write logs the operation and the user id, deliberately not the exception: a trigger's `SIGNAL` text names the rule it guards, and MySQL's constraint messages name the table, the column and the offending value. Asserted by `SecurityHardeningIT.transactionRefusalsKeepTheSchemaOutOfEverything` |
| **What is *not* claimed** | Hibernate's own `SqlExceptionHelper` logs the raw driver message at `ERROR` whenever a constraint or trigger actually fires. The module cannot suppress that without disabling Hibernate's SQL-error logging, which would hide genuine faults. The guarantee is exactly "our lines are clean", not "the log is clean" — the same bound module 3 recorded |
| **Atomicity** | Each write is one transaction, and the budget alert the trigger raises commits or rolls back with the record. A refused write changes nothing, including the history |
| **Concurrency** | The write paths take `SELECT ... FOR UPDATE` on the record, so a state check cannot be made twice. Two simultaneous deletes produce one deletion and one `409`; two simultaneous edits are serialised and neither loses an update |
| **Duplicate submissions** | Two identical creates are two records — a ledger has no natural key, so this is correct rather than a defect. A double-clicked delete produces one deletion and one `409` |

---

## 15. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-10 | List my records, newest first | `GET /transactions` | `TransactionController.listTransactions` | `TransactionService.listTransactions` | `findForStudent` on `ix_txn_user_date` | `TransactionApiIT.emptyStateIsAnEmptyArray`, `recordsAreOrderedNewestFirstWithATotalOrder` |
| UC-10 | Narrow to a date range | `GET /transactions` | as above | as above | `txn_date` range, both bounds inclusive | `dateRangeIsInclusiveAndEitherBoundMayBeOmitted` |
| UC-10 | Reject an inverted range | `GET /transactions` | — | `listTransactions` | — | `invertedRangeIsAFieldError` |
| UC-10, UC-09 | The default upper bound of today hides a future recurring row | `GET /transactions` | — | `listTransactions` (`EARLIEST_DATE`, `today()`) | `txn_date` range; `sp_validate_transaction` exempts `RECURRING` from BR-08 | `futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven` |
| UC-10 | Read one by id | `GET /transactions/{id}` | `getTransaction` | `getTransaction` | `findActiveByIdAndUserId` | `ownTransactionCanBeReadById` |
| UC-10 | An absent nullable field is omitted | every read | — | `TransactionMapper` | — | `absentNullableFieldsAreOmitted` |
| UC-10 | See the trash | `GET /transactions?includeDeleted=true` | `listTransactions` | `listTransactions` | `is_deleted = 0 OR :includeDeleted` | `deletedRecordsAreHiddenUnlessAskedFor` |
| UC-10 | A deleted record is not readable by id | `GET /transactions/{id}` | `getTransaction` | `requireActiveTransaction` | `is_deleted = 0` | `deletedRecordIsNotFoundById` |
| UC-07 | Record an expense | `POST /transactions` | `createTransaction` | `create` | `transactions` INSERT | `creatingAnExpenseDerivesEverythingFromTheCategory` |
| BR-05 | The category decides the type | `POST`, `PATCH` | — | `create`, `update` | `categories.type`, no `transactions.type` column | `theCategoryAloneDecidesIncomeOrExpense`, `movingToAnotherCategoryChangesTheType` |
| BR-08 | The date cannot be in the future | `POST`, `PATCH` | — | `requireNotInTheFuture` | `trg_transactions_before_insert`, `sp_validate_transaction` | `theDateBoundaryIsTodayInTheApplicationZone`, `futureDateIsRefusedOnUpdate`, `futureDateIsRefusedByTheDatabaseToo` |
| BR-08 | The amount is strictly positive | `POST`, `PATCH` | `CreateTransactionRequest`, `UpdateTransactionRequest` | — | `ck_txn_amount`, `DECIMAL(15,2)` | `amountBoundariesMatchTheColumn` |
| BR-02 | Only the caller's own or a shared category | `POST`, `PATCH` | — | `requireUsableCategory` | `sp_validate_transaction` | `unusableCategoryIsNotFound`, `anotherStudentsCategoryIsRefusedByTheDatabaseToo` |
| BR-07 | A retired category takes no new records | `POST`, `PATCH` | — | `requireActiveCategory` | `sp_validate_transaction` with `require_active = 1` | `retiredCategoryCannotBeUsedForANewRecord`, `recordUnderARetiredCategoryStaysEditable` |
| UC-10 | Edit some fields | `PATCH /transactions/{id}` | `updateTransaction` | `update` | `transactions` UPDATE, `@DynamicUpdate` | `partialUpdateLeavesTheOtherFieldsAlone`, `emptyUpdateBodyChangesNothing` |
| UC-10 | Clear the description | `PATCH /transactions/{id}` | — | `trimToNull` | nullable `description` | `emptyStringClearsTheDescription` |
| UC-10 | An identical repeat changes nothing | `PATCH /transactions/{id}` | — | `update` | `trg_transactions_after_update` writes only changed columns | `repeatedIdenticalUpdatesAreIdempotent` |
| UC-10 | Delete (soft) | `DELETE /transactions/{id}` | `deleteTransaction` | `delete` | `sp_soft_delete_transaction` | `deleteIsSoftAndKeepsTheRecord` |
| BR-09 | A hard delete is impossible | — | — | — | `trg_transactions_before_delete` | `hardDeleteIsRefusedByTheDatabase` |
| UC-10 | Deleting twice is a conflict | `DELETE /transactions/{id}` | — | `TransactionStateException.alreadyDeleted` | `sp_soft_delete_transaction` | `deletingTwiceIsAConflict` |
| UC-10 A1 | Restore | `POST /transactions/{id}/restore` | `restoreTransaction` | `restore` | `sp_restore_transaction` | `restoreBringsTheRecordBack` |
| UC-10 A1 | Restoring a live record is a conflict | `POST /{id}/restore` | — | `TransactionStateException.notDeleted` | `sp_restore_transaction` | `restoringALiveRecordIsAConflict` |
| BR-09 | Every change is logged with the full payload | `POST`, `PATCH`, `DELETE`, `POST /restore` | — | — | `trg_transactions_after_insert`, `trg_transactions_after_update`, `transaction_history` | `createWritesAFullHistoryRow`, `updateRecordsOnlyWhatChanged`, `restoreBringsTheRecordBack` |
| UC-14 | Recording or editing raises the budget alerts | `POST`, `PATCH` | — | — | `sp_check_budget_alerts` | `recordingAnExpenseRaisesTheBudgetAlerts`, `budgetAlertsRespectTheCategoryAndTheMonth`, `budgetAlertsReachOnlyTheOwner` |
| BR-12 | Each threshold alerts once per budget | `PATCH` | — | — | `budget_alert_log` unique key | `budgetAlertsAreNotDuplicated` |
| BR-02 | Another student's record is unreachable | all six | `@AuthenticationPrincipal` | every `require...` lookup | `find*ByIdAndUserId` | `anotherStudentsTransactionIsUnreachable` |
| §7.5 | No client-supplied identity, provenance or state | `POST`, `PATCH` | DTO field set, `SecurityConfig` role rule | entity setter set and `updatable = false` mappings | `users.role` | `createBodyCannotSetServerOwnedFields`, `updateBodyCannotSetServerOwnedFields`, `claimingRecurringDoesNotUnlockFutureDates`, `administratorTokenIsRefused`, `anonymousCallerIsRefused` |
| BR-03 | A revoked session and a disabled account stop working | all six | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.status`, `users.token_version` | `revokedSessionIsRefused`, `disabledAccountIsRefusedAsUnauthenticated` |
| §7.6 | Refusals keep the schema out of the response and the log | all writes | — | `translateWriteFailure` | — | `SecurityHardeningIT.transactionRefusalsKeepTheSchemaOutOfEverything` |
| §7.7 | No internals in a response | all six | — | `TransactionWriteFailure` | — | `refusalsDoNotLeakDatabaseInternals`, `malformedBodyIsRejectedWithoutLeakingInternals`, `malformedDateParameterIsRejected`, `malformedIdentifierIsBadRequest` |
| §13 P5 | Concurrency | `DELETE`, `PATCH` | — | `@Lock(PESSIMISTIC_WRITE)` lookups | `SELECT ... FOR UPDATE` | `simultaneousDeletesProduceOneDeletion`, `simultaneousEditsAreSerialised` |
| §13 P5 | Refusal classification | — | — | `TransactionWriteFailure` | — | `TransactionWriteFailureTest` |
| UC-07 | A ledger has no natural key | `POST` | — | — | `transactions` | `identicalRecordsAreNotDeduplicated` |
| UC-10 | UAT: an empty state is usable | `GET /transactions` | — | — | empty `transactions` | `emptyStateIsAnEmptyArray` |

`TransactionWriteFailureTest` is a unit test rather than an integration test on purpose: it verifies
the SQLSTATE classification with the exact exceptions MySQL and Spring produce.
`sp_validate_transaction` signals six rules with one SQLSTATE and the service pre-checks them all, so
the signalled branch is nearly unreachable through the API — leaving it to an integration test would
leave it unverified.

---

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [categories.md](categories.md) — module 3, whose `PATCH` semantics and `categoryId` rule this module follows
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
- [../modules/MODULE_04_TRANSACTIONS.md](../modules/MODULE_04_TRANSACTIONS.md) — the module report
