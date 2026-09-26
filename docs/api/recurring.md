# Recurring Rules API

Module 5 of the Campus Coin backend. Covers **UC-09 (set up, edit, pause, end and remove a recurring
income or expense)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint · **Required role** `STUDENT`

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [The type is the category's, not the rule's](#4-the-type-is-the-categorys-not-the-rules)
5. [`startDate` versus `nextRunDate`](#5-startdate-versus-nextrundate)
6. [`GET /api/v1/recurring-rules`](#6-get-apiv1recurring-rules)
7. [`GET /api/v1/recurring-rules/{id}`](#7-get-apiv1recurring-rulesid)
8. [`POST /api/v1/recurring-rules`](#8-post-apiv1recurring-rules)
9. [`PATCH /api/v1/recurring-rules/{id}`](#9-patch-apiv1recurring-rulesid)
10. [`DELETE /api/v1/recurring-rules/{id}`](#10-delete-apiv1recurring-rulesid)
11. [The scheduler: how a rule becomes transactions](#11-the-scheduler-how-a-rule-becomes-transactions)
12. [The rules the database enforces, not this module](#12-the-rules-the-database-enforces-not-this-module)
13. [Status codes](#13-status-codes)
14. [Angular integration notes](#14-angular-integration-notes)
15. [Security properties](#15-security-properties)
16. [Traceability](#16-traceability)

---

## 1. Scope and what is deliberately absent

UC-09 sets up a repeating income or expense, lets the student edit it, stop it temporarily or for
good, and remove one that was created by mistake. This module implements exactly that, and the
scheduler that turns due rules into transactions.

| Not implemented | Why |
|---|---|
| `type` on create or update | The type **is** the chosen category's type (BR-05). `recurring_rules.type` is a stored column, unlike `transactions`, but it is written from the category on every path and a client cannot send one — see §4 |
| `userId` on any request | The owner is the account in the bearer token. Accepting it would make BR-02 a check that could be forgotten rather than a structural property |
| `startDate` on update | It is the rule's origin, and the periods already posted are a function of it. Moving it would silently rewrite what the rule means and disagree with the occurrences already recorded. To move a rule onto a different day, change `nextRunDate` — see §5 |
| `lastRunDate` on any request | Written only by `sp_post_recurring_transactions`. A client able to move it could make the scheduler skip or repeat periods |
| `dayOfMonth` / `dayOfWeek` on any request | The schema describes them as UI hints and the scheduler does not read either — it schedules from `nextRunDate` alone. They are **not published in the response either**: storing a value with no consumer is bad, and one that could disagree with the date the rule actually runs on is worse. A "every month on the 5th" label is derived from `startDate`, which is the value the scheduler agrees with |
| `occurrenceCount` or any period history | The periods a rule has covered are rows in `recurring_occurrences`, which the scheduler owns. The client reads their *effect* — the transactions they produced, through module 4's `GET /api/v1/transactions` — and never the bookkeeping |
| A `/pause`, `/resume` or `/end` endpoint | Pausing, resuming and ending are one column with three values; three URLs would be three names for one write and could disagree with `PATCH` |
| A "run the scheduler now" endpoint | Which periods are due is the procedure's decision, made once a day. Exposing it would let a client trigger posting for every student in the system |
| `PUT` for the whole rule | UC-09 edits values; it does not replace a record. `PATCH` is the method that matches |
| `/recurring-rules/all`, `/list`, `/api/v1/recurring` | `GET /api/v1/recurring-rules` answers all of them. One path, one question |
| A status filter (`?status=ACTIVE`) | The response carries `status` and the client already knows what it wants to show. A second way to ask the same question becomes a duplicate endpoint later — the same decision module 3 made about `?type=` |
| Pagination | UC-09 does not ask for it. A student has a handful of rules, not thousands |
| The AI suggestion columns | UC-08, module 12 |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 22 | `GET` | `/api/v1/recurring-rules` | UC-09 | List my rules, soonest to run first | `200` |
| 23 | `GET` | `/api/v1/recurring-rules/{id}` | UC-09 | Read one of mine | `200` |
| 24 | `POST` | `/api/v1/recurring-rules` | UC-09 | Set up a repeating income or expense | `201` |
| 25 | `PATCH` | `/api/v1/recurring-rules/{id}` | UC-09 | Change some fields; pause, resume or end | `200` |
| 26 | `DELETE` | `/api/v1/recurring-rules/{id}` | UC-09 | Remove a rule that has never posted | `204` |

All five require `Authorization: Bearer <accessToken>` and an account whose role is `STUDENT`. An
administrator token is refused with `403` — see §15.

The two write endpoints that return a body return the rule **as it now is in the database**, so the
client can replace its cached copy rather than guess what the server did with the values it sent.

**Creating a rule posts nothing.** The scheduler turns due rules into transactions once a day. A
client that wants to show "your next allowance arrives on 1 October" reads `nextRunDate`; it does
not wait for a transaction to appear.

---

## 3. Field reference

The response of every endpoint, and the request fields of the two write endpoints.

| Field | Type | In request | Validation when supplied | Source |
|---|---|---|---|---|
| `id` | number | read-only | — | `recurring_rules.id` |
| `categoryId` | number | required on create, optional on update | must be a category the caller may use, and not retired | `recurring_rules.category_id` |
| `categoryName` | string | read-only | — | `categories.name` |
| `categoryIcon` | string | read-only | — | `categories.icon` |
| `categoryColor` | string | read-only | — | `categories.color` |
| `type` | string enum | **never accepted** | — | `categories.type` — derived, see §4 |
| `amount` | number | required on create, optional on update | strictly greater than zero, at most two decimal places, at most 13 digits before the point | `recurring_rules.amount` |
| `description` | string | optional | at most 255 characters after trimming, newlines permitted | `recurring_rules.description` — stored **encrypted**, see §15 |
| `frequency` | string enum | required on create, optional on update | one of `DAILY`, `WEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY` | `recurring_rules.frequency` |
| `intervalCount` | number | optional | at least 1, at most 999; defaults to `1` | `recurring_rules.interval_count` |
| `startDate` | string `YYYY-MM-DD` | required on create, **never on update** | — | `recurring_rules.start_date` |
| `endDate` | string `YYYY-MM-DD` or `""` | optional | on or after `startDate`; `""` clears it | `recurring_rules.end_date` |
| `nextRunDate` | string `YYYY-MM-DD` | optional | defaults to `startDate`; must not be after `endDate` | `recurring_rules.next_run_date` |
| `lastRunDate` | string `YYYY-MM-DD` | **never accepted** | — | `recurring_rules.last_run_date` — written by the scheduler |
| `status` | string enum | optional on create (ignored), optional on update | one of `ACTIVE`, `PAUSED`, `ENDED`; a new rule is always `ACTIVE` | `recurring_rules.status` |

### Fields that are absent rather than null

`categoryIcon`, `categoryColor`, `description`, `endDate` and `lastRunDate` are **omitted from the
response** when they have no value, rather than sent as `null`.

Every other field is always present. `intervalCount` is never absent: it is a number with a value in
every state, because the schema's column is `NOT NULL DEFAULT 1` and the service writes the default.

```json
{
  "id": 4,
  "categoryId": 10,
  "categoryName": "Subscriptions",
  "categoryIcon": "repeat",
  "categoryColor": "#8B5CF6",
  "type": "EXPENSE",
  "amount": 8.00,
  "description": "Music streaming plan",
  "frequency": "MONTHLY",
  "intervalCount": 1,
  "startDate": "2026-09-01",
  "nextRunDate": "2026-10-01",
  "status": "ACTIVE"
}
```

That rule is open-ended and has not run yet, so `endDate` and `lastRunDate` are both missing. There is
one rule to apply — "no value means the key is absent" — rather than two.

### Amounts

`amount` is a JSON number with at most two decimal places. It is always **positive**; whether the
money came in or went out is `type`, never the sign. `-8.00` is a field error, not an expense.

Three decimal places are refused rather than rounded. MySQL would round `1.005` to `1.01` on the way
in, so the value the student typed and the value stored would differ and no screen would say so.

The column is `DECIMAL(15,2)`: thirteen digits before the point, two after. The response always
carries exactly two decimal places — `8.00`, never `8` or `8.0` — because the database column owns
the scale and the API passes the value through untouched.

### Dates

Every date is a calendar date with no time and no zone: `"2026-10-01"`. A rule runs on a day in the
student's life, not at an instant on the server's clock.

**"Today" is judged in `Asia/Ho_Chi_Minh` (`+07:00`)** — the same offset the database session is
pinned to. This module's own code never compares a date to "today" at all: which periods are due is
the scheduler procedure's decision, made against `CURDATE()` in that session (VĐ-10). A rule with a
start date in the past is not special-cased, it is simply caught up on.

**A rule's dates may be in the future.** This is the opposite of module 4 and it is deliberate: a
rule is a *schedule*, not a record of something that happened, so BR-08's "no future dates"
restriction does not apply to it. `sp_validate_transaction` exempts `RECURRING`-sourced rows for the
same reason, which is what lets the scheduler create tomorrow's transaction today.

`endDate` is **inclusive**: a rule with `endDate` of 2026-12-01 posts a final occurrence on
2026-12-01 and then stops.

### `endDate` travels as a string, not a date

`endDate` is the one field in this module that can be **removed**. An update sending `"endDate": ""`
clears it and makes the rule open-ended; sending it with a value sets it; omitting it leaves it alone.

That requires a wire type able to express three states, and a JSON date cannot: a record of nullable
fields cannot tell "absent" from "explicit `null`" — both arrive as `null` in Java. So the field is a
string on both request types, and the empty string is the way to say "no end date". This is the same
convention module 2 uses for `academicYear`.

`endDate` on the **response** is a real date or absent. Only the requests use `""`.

### Enum values are member names, never numbers

All three enums accept and publish the member name the database stores, in upper case.
`frequency: 2` is rejected with a field error rather than read as the ordinal `MONTHLY`; lower case
(`"monthly"`) is also rejected. Send the member name exactly.

| Field | Permitted values |
|---|---|
| `type` | `INCOME`, `EXPENSE` |
| `frequency` | `DAILY`, `WEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY` |
| `status` | `ACTIVE`, `PAUSED`, `ENDED` |

### Defaults for a new rule

As the database declares them and as the service applies them: `description` is `null`, `intervalCount`
is `1`, `nextRunDate` is `startDate`, `endDate` is `null` (open-ended) and `status` is `ACTIVE`.
`categoryId`, `amount`, `frequency` and `startDate` are required.

---

## 4. The type is the category's, not the rule's

This is the central decision of the module and the one most likely to surprise a frontend that
already has a mock model.

`recurring_rules` **does** have a `type` column, unlike `transactions`, and a client still cannot
send one. The reason the column exists is that a rule can be created before any transaction exists,
so BR-05's "the type must agree with the category" has to be checkable at that moment rather than
when the scheduler eventually posts.

Java writes it from the category on every path — the entity's factory and `setCategory` both take it
from `category.getType()` — so the two cannot drift. The database re-checks it on every insert and
every update through `trg_recurring_rules_before_insert` and `..._before_update`, so a mismatched
pair cannot exist even if the application were wrong.

What follows for a client:

| Question | Answer |
|---|---|
| Can I send `type` on create? | No. The field is not in the request; sending it changes nothing |
| Can I change a rule's type? | Only by moving it to a category of the other type (`categoryId` on `PATCH`). The stored type is re-derived from the new category — you do not send it |
| Where does `type` in the response come from? | `categories.type` for the category the rule is filed under. It is read-only |
| What happens if the category's type is changed later? | It cannot be, once anything references it. `trg_categories_before_update` refuses the change, because it would silently rewrite history — every recorded expense would read back as income in the reports |
| What if the rule's category is retired? | Annoyingly: **the rule can no longer be changed at all**, not even to end it. See §9 |

A picker therefore needs nothing beyond the category list: choosing a category chooses the type.

---

## 5. `startDate` versus `nextRunDate`

Both are published and they are not the same thing. A UI that showed only one of them could not
explain a rule that has been running for months.

| | `startDate` | `nextRunDate` |
|---|---|---|
| What it means | The rule's origin — when the schedule was set up to begin | The date of the next occurrence the scheduler will post |
| Writable | On create only, never afterwards | On create (optional) and on update |
| Who changes it | Nobody | The scheduler, after each run; the student, to move the rule onto a different day |
| Sent on create | Required | Optional; defaults to `startDate` |

**How to move a rule onto a different day.** A student paying rent on the 1st who wants to move to
the 15th sends `{"nextRunDate": "2026-10-15"}`. Changing `startDate` is not offered and would be
wrong: the periods already posted were derived from it, so moving it would make the rule's own
occurrence history inconsistent with its definition.

**Preparing a rule in advance.** `nextRunDate` later than `startDate` schedules the first occurrence
later without firing on creation. That is how a rule is set up before it is wanted.

**The one date rule that is this module's own.** `nextRunDate` must not be after `endDate`. Nothing
in the schema expresses it, and it is worth refusing: the scheduler's cursor condition is
`next_run_date <= end_date`, so a rule in that state would match no run, never advance, and never be
marked `ENDED` — it would sit `ACTIVE` for ever, visible to the student, posting nothing and giving
no hint why.

---

## 6. `GET /api/v1/recurring-rules`

List every rule the caller owns, **soonest to run first**.

### Request

No parameters. No status filter, no date range, no pagination — see §1.

```
GET /api/v1/recurring-rules
Authorization: Bearer <accessToken>
```

### Response `200 OK`

An array, ordered by `nextRunDate` ascending then `id` ascending. **Every status is included**:
paused and ended rules are returned, because a paused rule is one the student means to resume and
hiding it would leave no way to find it again. The client filters on `status`.

The `id` tiebreak makes the order total: several rules commonly share a date, and without it MySQL
may return equal rows in either order, making the list appear to shuffle between two identical calls.

```json
[
  {
    "id": 4,
    "categoryId": 10,
    "categoryName": "Subscriptions",
    "categoryIcon": "repeat",
    "categoryColor": "#8B5CF6",
    "type": "EXPENSE",
    "amount": 8.00,
    "description": "Music streaming plan",
    "frequency": "MONTHLY",
    "intervalCount": 1,
    "startDate": "2026-09-01",
    "nextRunDate": "2026-10-01",
    "status": "ACTIVE"
  },
  {
    "id": 7,
    "categoryId": 1,
    "categoryName": "Allowance",
    "type": "INCOME",
    "amount": 200.00,
    "frequency": "MONTHLY",
    "intervalCount": 1,
    "startDate": "2026-09-01",
    "nextRunDate": "2026-11-01",
    "lastRunDate": "2026-10-01",
    "status": "PAUSED"
  }
]
```

An account with no rules gets `200` and `[]`, never `404`.

### Failures

| Status | When |
|---|---|
| `401` | No token, or the token is invalid, expired or revoked |
| `403` | The token's role is not `STUDENT` |

---

## 7. `GET /api/v1/recurring-rules/{id}`

Read one of the caller's own rules, so a client can refresh a single row after a change without
reloading the list.

### Request

| Parameter | In | Type | Notes |
|---|---|---|---|
| `id` | path | number | The rule's identifier |

```
GET /api/v1/recurring-rules/4
Authorization: Bearer <accessToken>
```

### Response `200 OK`

One rule object, the same shape as a list element.

### Failures

| Status | When |
|---|---|
| `400` | `id` is not a number (`BAD_REQUEST`) |
| `401` | No token, or the token is invalid, expired or revoked |
| `403` | The token's role is not `STUDENT` |
| `404` | No such rule **of the caller's** |

Another student's rule is not reachable here, and neither is one that does not exist. **Both answer
`404`, identically**, so the endpoint cannot be used to discover which rule identifiers exist.

---

## 8. `POST /api/v1/recurring-rules`

Set up a repeating income or expense owned by the caller.

### Request

```json
{
  "categoryId": 10,
  "amount": 8.00,
  "description": "Music streaming plan",
  "frequency": "MONTHLY",
  "intervalCount": 1,
  "startDate": "2026-09-01"
}
```

| Field | Required | Notes |
|---|---|---|
| `categoryId` | yes | One of the caller's own categories or a shared default, not retired |
| `amount` | yes | Strictly positive, at most two decimals |
| `frequency` | yes | One of the five member names |
| `startDate` | yes | The rule's origin |
| `description` | no | Trimmed; `""` stores no description |
| `intervalCount` | no | Defaults to `1` |
| `endDate` | no | `"YYYY-MM-DD"` or `""`; omitted means open-ended |
| `nextRunDate` | no | Defaults to `startDate` |
| `status` | **ignored** | The server sets `ACTIVE`; sending anything changes nothing |
| `type` | **ignored** | Derived from the category; sending anything changes nothing |

### Response `201 Created`

The rule as the database now holds it, including the `id` the client should use from here on.

```json
{
  "id": 12,
  "categoryId": 10,
  "categoryName": "Subscriptions",
  "categoryIcon": "repeat",
  "categoryColor": "#8B5CF6",
  "type": "EXPENSE",
  "amount": 8.00,
  "description": "Music streaming plan",
  "frequency": "MONTHLY",
  "intervalCount": 1,
  "startDate": "2026-09-01",
  "nextRunDate": "2026-09-01",
  "status": "ACTIVE"
}
```

A new rule is always `ACTIVE` and has no `lastRunDate`, because nothing has run yet.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A required field is missing, `amount` is not positive or has too many decimals, `frequency` is unknown, `intervalCount` is out of range, `endDate` is malformed, `endDate` is before `startDate`, or `nextRunDate` is after `endDate`. `fieldErrors` names each field |
| `400` | `VALIDATION_ERROR` | The chosen category is retired (BR-07). `fieldErrors` names `categoryId` |
| `400` | `BAD_REQUEST` | The body is not valid JSON |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | `categoryId` names no category the caller may use |
| `409` | `DATA_CONFLICT` | The category was retired or removed while the request was in flight |

---

## 9. `PATCH /api/v1/recurring-rules/{id}`

Change the supplied fields and leave the rest as they are. Send only what changed.

### Request

```json
{
  "amount": 12.00,
  "nextRunDate": "2026-10-15"
}
```

| Field | Notes |
|---|---|
| `categoryId` | Move the rule; the stored `type` is re-derived (BR-05). The new category must not be retired |
| `amount` | Strictly positive, at most two decimals |
| `description` | Send `""` to clear it |
| `frequency` | One of the five member names. Changing it does not re-post periods already covered |
| `intervalCount` | 1–999 |
| `endDate` | `"YYYY-MM-DD"` to set it, `""` to remove it and make the rule open-ended |
| `nextRunDate` | Move the rule onto a different day |
| `status` | `PAUSED` stops it posting without losing it, `ACTIVE` resumes, `ENDED` stops it for good |
| `startDate` | **Not accepted.** Silently ignored if sent; the rule's origin never changes |
| `lastRunDate` | **Not accepted.** The scheduler's |

### Partial update semantics

A field that is **absent** and a field sent as **explicit `null`** both mean "leave it as it is".
The only two fields with a "clear" state are `description` and `endDate`, and both use `""` for it.

An empty body `{}` is valid and changes nothing. The response is the rule unchanged, and no `UPDATE`
statement is issued — the entity is `@DynamicUpdate`, so a request that changes nothing writes
nothing and appends no history row.

### Pausing, resuming and ending are this endpoint

There is no `/pause`, `/resume` or `/end`. Each of those sets `status` on the same row, so three
endpoints would be three names for one write and could disagree with each other.

```json
{ "status": "PAUSED" }
```

| Value | Effect |
|---|---|
| `ACTIVE` | The scheduler will post due periods |
| `PAUSED` | The scheduler leaves it alone; the rule and everything it generated are kept. The periods it misses are posted on resume, not skipped — see §11 |
| `ENDED` | Stops permanently and **cannot be reversed**. This is how a rule is retired — see §10 |

**The lifecycle is `ACTIVE ↔ PAUSED`, either of those `→ ENDED`, and `ENDED` is final.** Setting a
rule back to `ACTIVE` from `PAUSED` resumes it; setting `PAUSED` from `ACTIVE` stops it. `ENDED` can
be reached from either, and re-sending `{"status": "ENDED"}` to an already-ended rule is accepted as a
no-op so a retried request does not fail.

Asking to move an **ended** rule back to `ACTIVE` or `PAUSED` answers:

```json
{
  "status": 409,
  "errorCode": "RECURRING_RULE_ENDED",
  "message": "This rule has ended, and an ended rule cannot be restarted. Create a new rule if the schedule is needed again.",
  "path": "/api/v1/recurring-rules/41"
}
```

The restriction is the application's, not the column's — `recurring_rules.status` is an `ENUM` that
would accept any value. The reason is that ending is the operation a student reaches for to retire a
rule that has already posted, and un-ending it would silently re-open a schedule whose past periods
were deliberately abandoned, in a way that contradicts the transactions it already generated. A
student who wants the schedule back creates a new rule; the old one keeps its history.

An ended rule still accepts edits that are **not** a status change — its description, for instance.
What is refused is only the move out of `ENDED`.

### A rule whose category has been retired cannot be changed at all

This is the most surprising behaviour in the module, it is enforced by the database rather than by
this API, and it answers `409 CATEGORY_RETIRED`:

> This rule's category has been retired, so the rule cannot be changed. Enable the category, or move
> the rule to one that is still in use.

Unlike the transaction triggers, `trg_recurring_rules_before_update` has no "skip the active check"
escape — it calls `sp_validate_recurring_rule` with the new values unconditionally. So retiring a
category in module 3 **freezes every rule filed under it**, including the rule's own ability to set
`status: "ENDED"`.

The two remedies are:

1. **Move the rule** to a category that is still in use — send `categoryId`. That is accepted even
   while the old category is retired, because the new one is what gets checked, so the error becomes
   a field error naming `categoryId` instead.
2. **Re-enable the category** (module 3), change the rule, and retire the category again.

Retiring a category does **not** stop its rules posting anything, because the scheduler already skips
rules on an inactive category. It only stops them being *edited*.

### Response `200 OK`

The rule as it now is in the database. Note that `nextRunDate` may have moved on its own, because the
scheduler runs in the background and advances it after each posting.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A supplied field is invalid, the target category is retired, the dates do not agree, or `endDate` is not a real date. `fieldErrors` names each field |
| `400` | `BAD_REQUEST` | `id` is not a number, or the body is not valid JSON |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such rule of the caller's, or the target `categoryId` is not usable |
| `409` | `CATEGORY_RETIRED` | The rule's own category has been retired and the request does not move it |
| `409` | `RECURRING_RULE_ENDED` | The rule has already ended and the request would move it out of `ENDED` |
| `409` | `DATA_CONFLICT` | The data changed underneath the request |

---

## 10. `DELETE /api/v1/recurring-rules/{id}`

Remove a rule outright. **Permitted only while the rule has generated no transactions.**

### Request

```
DELETE /api/v1/recurring-rules/12
Authorization: Bearer <accessToken>
```

### Response `204 No Content`

No body.

### Why a rule that has posted cannot be deleted, and what to do instead

A rule that has already generated transactions answers `409 RECURRING_RULE_IN_USE`:

> This rule has already generated 3 transactions, so it cannot be deleted. End it instead: it will
> stop posting and the transactions it created stay readable and editable.

This is not caution for its own sake, and it is worth understanding before wiring the UI.
`transactions.recurring_rule_id` deliberately carries **no foreign key** (`docs/DB_DESIGN.md` §4.8),
so nothing in the database would stop the delete. What it would leave behind is not a harmless
dangling reference but a **broken record**: `sp_validate_transaction` re-checks that reference on
every write, so a transaction whose rule no longer exists cannot be edited, soft-deleted or restored
again — each raising `BR-02: recurring rule does not exist`. Deleting the rule would therefore break
module 4 for rows that were working before.

The count includes **soft-deleted** transactions on purpose. They still carry the pointer and are
still restored through the same trigger, so counting only live rows would report a rule as removable
and leave the restored record permanently uneditable.

**`status: "ENDED"` is what the UI should offer** for "I don't want this any more". It stops the rule
posting for good, and everything it generated stays intact and editable. Deleting is for a rule set
up by mistake, or one prepared for a date that has not arrived.

Because `trg_transactions_before_delete` refuses a hard delete of a transaction, a rule's generated
count can only reach zero by never having posted — a further reason the rule is the right unit to
end rather than to remove.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `id` is not a number |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such rule of the caller's — **checked before the generated-transaction count**, so the refusal cannot be used to learn whether another student's rule has posted anything |
| `409` | `RECURRING_RULE_IN_USE` | The rule has already generated transactions |

---

## 11. The scheduler: how a rule becomes transactions

This is the half of UC-09 that has no endpoint, and a frontend needs to know it exists because it is
why a rule's transactions appear without the client doing anything.

**A rule posts nothing when it is created.** Once a day, the application calls
`sp_post_recurring_transactions`, which walks every `ACTIVE` rule whose category is still in use and
whose `nextRunDate` has arrived, and creates one transaction per missed period.

| Property | Behaviour |
|---|---|
| **When it runs** | Once a day, shortly after midnight in `Asia/Ho_Chi_Minh`. The cron is `campuscoin.recurring.scheduler.cron`, evaluated in that zone explicitly (`zone` on the `@Scheduled` method) so the run is pinned to the same day the database computes, and the run can be disabled with `campuscoin.recurring.scheduler.enabled=false` |
| **Which rules** | `status = 'ACTIVE'` **and** the category is still active. A paused, ended or retired-category rule posts nothing |
| **Catch-up** | A rule keeps its schedule after downtime. Stopping the application for a week and restarting it posts every period that was missed, not only the most recent |
| **What a pause does to those missed periods** | It defers them. Nothing selects a `PAUSED` rule, so the cursor does not move while the rule is paused — and that means resuming it backfills every period whose date passed during the pause, exactly as coming out of downtime does. A pause is "stop posting for now", not "skip these periods" |
| **Catch-up is bounded** | The procedure stops after 500 periods in one rule's loop, so a rule misconfigured to run daily from years ago cannot hold the whole run open. A rule that hits the cap advances 500 periods and is picked up by the next run; the test `catchUpIsBoundedAndSettles` proves a 700-period backlog settles over two runs |
| **No duplicates (BR-16)** | Each occurrence is recorded in `recurring_occurrences`, keyed uniquely on `(rule_id, period_key)`. A repeat run posts the same periods and no others, so running the scheduler twice — or running it on two application instances at once — produces one occurrence and one transaction per period |
| **Never in the future** | The procedure stops at the date it is given, so it never creates a row dated ahead of "today" |
| **Ending** | Once the cursor passes `endDate`, the rule is marked `ENDED` automatically |
| **Ownership** | The transaction is owned by the rule's owner, and its `source` is `RECURRING` |
| **Audit** | The generated transactions go through the same `trg_transactions_after_insert` as a hand-entered record, so they appear in `transaction_history` like any other |

**What a client sees.** A rule with a past `startDate` will already have transactions when the list
is first loaded, and its `nextRunDate` will have advanced. `lastRunDate` is absent for a rule that has
never run and present otherwise — that is how a client tells a new rule from a running one without
fetching transactions.

### Why a paused rule backfills rather than skips

This is the one place where the implemented behaviour is worth stating plainly, because the wording
of UC-09 suggests the opposite. "Pause" reads as "skip the periods that fall inside the pause", but
what the procedure actually does is defer them: its cursor selects only `ACTIVE` rules, so a paused
rule's `nextRunDate` stays where it was, and the next run after a resume walks forward from there,
posting every period whose date has passed. Pausing a monthly rule for three months and resuming it
posts four periods at once, not one.

The consequence for a client:

- **`nextRunDate` does not move while the rule is paused.** It still names the next period that has
  not been covered, which is why it is the field to show for "resumes from here".
- **Resuming a long pause can post a burst of transactions**, all dated on their original scheduled
  dates rather than on the resume date. A client that wants to avoid that should end the rule and
  create a new one, or move `nextRunDate` forward before resuming.
- **The budget position can jump accordingly.** Those backfilled transactions are ordinary expenses,
  so they count towards their month's budget and can raise alerts retroactively — see §9's note on
  `PATCH` and module 6.

This divergence between the requirement's wording and the locked procedure is recorded as **OB-010**
in `docs/OVERNIGHT_BLOCKERS.md`. It is documented rather than "fixed" because changing it would mean
duplicating the procedure's period arithmetic in Java — the thing this module is built not to do —
and `db/` is frozen. The behaviour is pinned by
`RecurringRuleApiIT#pauseDefersPeriodsRatherThanSkippingThem`, so it cannot change unnoticed.

**A rule on a retired category posts nothing.** The procedure joins `categories` on `is_active = 1`
for that reason: without it, retiring a category would leave the scheduler trying to write
transactions against it and failing mid-loop. Combined with §9, a retired category means a rule is
both frozen and silent until the category is re-enabled.

**Nothing about this is exposed as an API.** There is no "run now" endpoint, because which periods are
due is the procedure's decision and a client able to trigger it would trigger it for every student in
the system. To make a rule post early, move its `nextRunDate` backwards through `PATCH` and wait for
the next scheduled run.

---

## 12. The rules the database enforces, not this module

This module does not decide these rules and does not restate them in Java. They belong to the schema,
where they hold for every caller — including a hand-run SQL statement — and they are translated into
the errors above on the way out.

| Rule | Enforced by | Surfaced as |
|---|---|---|
| The category must exist and be the caller's own or a shared default (BR-02) | `sp_validate_recurring_rule`, via both triggers | `404` naming no field |
| The rule's type must equal the category's type (BR-05) | `sp_validate_recurring_rule` | `409 DATA_CONFLICT` (the service pre-empts it by deriving the type) |
| The category must not be retired (BR-07) | `sp_validate_recurring_rule` | `400` naming `categoryId` on create or on a move; `409 CATEGORY_RETIRED` when the rule's own category is retired |
| The amount must be strictly positive | `ck_recurring_amount` | `400` naming `amount` |
| The interval must be at least 1 | `ck_recurring_interval` | `400` naming `intervalCount` |
| The end date must not precede the start date | `ck_recurring_dates` | `400` naming `endDate` |
| Only `ACTIVE` rules on live categories post, and each period posts once (BR-16) | `sp_post_recurring_transactions`, `uk_occurrence_rule_period` | — (the scheduler posts, never refuses) |

**Why these are not in Java.** `sp_validate_recurring_rule` raises `SQLSTATE '45000'` for **four
different rules**, so a client told only "the write was refused" could not point at the field that
needs fixing. The service therefore asks three of the same questions first, purely so it can name the
field, and the trigger still runs afterwards. The database's rule is the one that holds; the
application's check is a stricter restatement made for the error message, and a request that slips
past it is caught by the translation below rather than accepted.

**Refusals are translated by SQLSTATE, never by message.** `SIGNAL SQLSTATE '45000'` arrives through
Spring as `InvalidDataAccessResourceUsageException` — **not** `PersistenceException` — so the
classifier walks the cause chain for the SQLSTATE rather than matching the driver's text, which is
localised and not a contract. A recognised refusal becomes `409 DATA_CONFLICT` with a message the
student can act on; anything unrecognised is rethrown and answered as an internal error rather than
mislabelled.

---

## 13. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body and replace the cached rule |
| `201` | Created | Add the returned rule to the cached list |
| `204` | Deleted, no body | Remove the rule from the list |
| `400` | Validation failed, a category is retired, or the body/parameter is malformed | If `fieldErrors` is present, show each `message` beside its `field`. Otherwise show `message` |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `403` | The token's role is not `STUDENT` | Send the user to their own area; this is not a sign-in problem |
| `404` | No such rule or category of the caller's | Reload; the row is gone, or was never the caller's |
| `409` | `RECURRING_RULE_IN_USE`, `CATEGORY_RETIRED`, `RECURRING_RULE_ENDED` or `DATA_CONFLICT` | Show `message`. The first three name the remedy in it; `DATA_CONFLICT` means reload and retry. `RECURRING_RULE_ENDED` is not retryable — the rule is final |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

`fieldErrors[].field` is the canonical property name project-wide. It is `field`, never `path`.

Note the difference between a **body** failure and a **parameter** failure: a validation failure on a
field has `fieldErrors`, and a malformed path parameter does not, because a parameter is not a body
field and there is nothing to name. Both are `400`.

### Error codes in full

The four conflict codes:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "RECURRING_RULE_IN_USE",
  "message": "This rule has already generated 3 transactions, so it cannot be deleted. End it instead: it will stop posting and the transactions it created stay readable and editable.",
  "path": "/api/v1/recurring-rules/12"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "CATEGORY_RETIRED",
  "message": "This rule's category has been retired, so the rule cannot be changed. Enable the category, or move the rule to one that is still in use.",
  "path": "/api/v1/recurring-rules/12"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "RECURRING_RULE_ENDED",
  "message": "This rule has ended, and an ended rule cannot be restarted. Create a new rule if the schedule is needed again.",
  "path": "/api/v1/recurring-rules/12"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "DATA_CONFLICT",
  "message": "The recurring rule could not be saved because the data it depends on changed. Refresh and try again.",
  "path": "/api/v1/recurring-rules"
}
```

A validation failure, with one error per bad field:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/recurring-rules",
  "fieldErrors": [
    { "field": "amount", "message": "Amount must be greater than zero." },
    { "field": "nextRunDate", "message": "Choose a next occurrence on or before the end date, or remove the end date." }
  ]
}
```

**Switch on `errorCode`, not on `message`.** The code is the stable part of the contract; the message
is human-readable text that may be reworded. The three named refusals are distinct because the remedy
differs — end the rule (`IN_USE`), enable the category or move the rule (`CATEGORY_RETIRED`), or create
a new rule (`RECURRING_RULE_ENDED`) — while `DATA_CONFLICT` is the generic one that only ever means
"retry".

---

## 14. Angular integration notes

### 14.1 The current frontend must be rewired

There is **no recurring-rule model and no recurring-rule service** in the frontend today. What exists
is a `recurringFrequency` field on `Transaction`:

```ts
export type RecurringFrequency = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY';

export interface Transaction {
  // ...
  recurringFrequency: RecurringFrequency;
}
```

This is a **different concept from this module** and four changes follow from it.

| Frontend today | This API | Change needed |
|---|---|---|
| `Transaction.recurringFrequency` of type `'NONE' \| 'DAILY' \| 'WEEKLY' \| 'MONTHLY'` | A separate `RecurringRule` resource with `frequency` of `'DAILY' \| 'WEEKLY' \| 'MONTHLY' \| 'QUARTERLY' \| 'YEARLY'` | **Delete the field from `Transaction`.** A transaction's only provenance field is `source` (`'MANUAL' \| 'CSV' \| 'RECURRING'`, module 4). Whether a *rule* exists is a different record with its own id |
| `'NONE'` as a frequency value | Not a value. A transaction with no rule is `source: 'MANUAL'`; a rule that no longer runs is `status: 'PAUSED' \| 'ENDED'` | Everything switching on `'NONE'` must switch on the new field instead |
| Three frequencies | **Five.** `QUARTERLY` and `YEARLY` are missing | Add them wherever the frequency is enumerated. The API rejects an unknown member with a field error, so a missing option is a `400` rather than a silent fallback |
| `id: string` | `id: number` | As in module 4. Anything comparing ids must compare numbers |

The four places the field is referenced today:

- `core/models/transaction.model.ts` — the declaration and the type alias
- `mock-data/transactions.mock.ts` — the seeded values
- `features/quick-add/quick-add.component.ts` — a "Make Recurring" toggle and a frequency `<select>`
  on the transaction form (lines ~243–248, ~415, ~502, ~544, ~568)
- `features/home-feed/home-feed.component.ts` — a `🔁 {{ tx.recurringFrequency | lowercase }}` badge
  (lines ~264–266)

**The quick-add toggle should not become a rule implicitly.** Record a transaction with
`POST /api/v1/transactions`, and — if the student asked for it to repeat — create the rule with
`POST /api/v1/recurring-rules` as a second call. The two records are genuinely separate: the
transaction happened once on a date, and the rule is a schedule. The rule's `description` and
`amount` are copied from the transaction by the client, not by the server.

**The home-feed badge becomes a rule lookup.** `source === 'RECURRING'` tells the row it was
generated by a rule; the rule's `frequency` is read from the rules the client has already loaded.
Neither `recurring_rule_id` nor any frequency is published on a transaction, so if the badge must
name the frequency the client needs the rule list.

### 14.2 Which call to make

- Entering the recurring screen: `GET /api/v1/recurring-rules`. One call; no parameters.
- Opening the edit modal: the row is already in the list — no call needed. Use
  `GET /api/v1/recurring-rules/{id}` only if the list may be stale.
- Saving a new rule: `POST /api/v1/recurring-rules` with `categoryId`, `amount`, `frequency` and
  `startDate`, plus any of the optional fields.
- Saving an edit: `PATCH /api/v1/recurring-rules/{id}` with only the changed fields.
- Pausing, resuming or ending: `PATCH /api/v1/recurring-rules/{id}` with `{"status": "..."}`.
- Removing a rule that never ran: `DELETE /api/v1/recurring-rules/{id}` — and be ready for `409`.
- Showing the transactions a rule produced:
  `GET /api/v1/transactions?from=...&to=...` (module 4). There is no per-rule transactions endpoint.

Send the token as `Authorization: Bearer <accessToken>`. No endpoint in this module works without
one.

### 14.3 Sending only what changed

Build the body from the form's **dirty** fields, as in modules 2, 3 and 4:

```ts
const dirty = Object.fromEntries(
  Object.entries(form.controls)
    .filter(([, control]) => control.dirty)
    .map(([name, control]) => [
      name,
      control.value === null && (name === 'description' || name === 'endDate') ? '' : control.value,
    ]),
);
if (Object.keys(dirty).length === 0) { return; }
this.recurringRuleService.updateRule(id, dirty).subscribe(...);
```

`description` and `endDate` are the two fields with an empty state, so they are the two that map
`null` to `''`. Doing that for `categoryId`, `amount`, `frequency` or `nextRunDate` would send a blank
where the API expects a value and turn an untouched field into a validation error.

Never send `type`, `startDate`, `lastRunDate`, `dayOfMonth` or `dayOfWeek`. They are not part of the
update contract, they change nothing, and sending them hides the fact that they are not writable.

### 14.4 Handling each status

```ts
this.recurringRuleService.createRule(body).subscribe({
  next: created => this.toast.show(`Rule created. Next run ${created.nextRunDate}.`),
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'VALIDATION_ERROR':
        response.error.fieldErrors?.forEach((e: { field: string; message: string }) =>
          this.form.get(e.field)?.setErrors({ server: e.message }));
        break;
      case 'RECURRING_RULE_IN_USE':
        // The rule has posted transactions, so the UI offered the wrong button. Offer "end" instead.
        this.confirmEndInstead(response.error.message);
        break;
      case 'CATEGORY_RETIRED':
        this.toast.show(response.error.message);
        this.offerCategoryMove();   // the one edit that is still accepted
        break;
      case 'RECURRING_RULE_ENDED':
        // Not a retry: the rule is final. Offer "create a new rule" and hide the resume control.
        this.toast.show(response.error.message);
        this.offerNewRule();
        break;
      case 'DATA_CONFLICT':
        this.reload();
        this.toast.show(response.error.message);
        break;
      case 'UNAUTHENTICATED':
        // Covers every reason this module answers 401, including a disabled account.
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

Two UI consequences worth designing for:

- **The delete button should be secondary.** Because a rule that has posted can never be deleted, the
  primary "stop this" action is `PATCH {"status": "ENDED"}`. Offer delete only for a rule with no
  `lastRunDate`, and handle `409` gracefully when that guess is wrong.
- **The edit form should be disabled when the category is retired.** The client can see
  `categoryId` in the category list and know the category's `isActive`; if it is false, no field of
  the rule can be saved except `categoryId`. Showing an editable form that cannot save is worse than
  showing why.

### 14.5 Dates without a timezone bug

Every date in this module is a plain `"YYYY-MM-DD"` string with no time. Do not parse it:
compare, sort and display the string itself.

```ts
readonly dueThisWeek = computed(() => {
  const today = new Date().toLocaleDateString('sv-SE'); // sv-SE yields YYYY-MM-DD
  const week = addDays(today, 7);
  return this.rules().filter(r => r.nextRunDate >= today && r.nextRunDate <= week);
});
```

`new Date('2026-10-01')` is parsed as UTC midnight, so a client west of UTC that formats it back to a
local date shows the 30th of September. Comparing and sorting the strings avoids the problem
entirely, and the API already returns them in the right order.

For the same reason, do not send `nextRunDate` computed from a `Date` object's `toISOString()` without
care: that converts to UTC first, which can shift the day by one.

### 14.6 Showing "every 2 weeks"

`frequency` and `intervalCount` are published separately, not as a combined label, so the client
builds its own text:

| `frequency` | `intervalCount` | Suggested label |
|---|---|---|
| `DAILY` | 1 | Every day |
| `DAILY` | 3 | Every 3 days |
| `WEEKLY` | 1 | Every week |
| `WEEKLY` | 2 | Every 2 weeks |
| `MONTHLY` | 1 | Every month |
| `MONTHLY` | 6 | Every 6 months |
| `QUARTERLY` | 1 | Every quarter |
| `YEARLY` | 1 | Every year |

`QUARTERLY` with `intervalCount: 2` means every two quarters, i.e. every six months. The API does not
normalise the two, so send what the student chose rather than converting.

---

## 15. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. Every single-row query takes the caller's id as well as the rule's (`RecurringRuleRepository`), so no service method can reach another student's rule. The database re-checks the category's owner through `sp_validate_recurring_rule`, because `fk_recurring_category` only proves the row exists |
| **No identifier probing** | Another student's rule and an unknown id both answer `404`, identically. On delete, ownership is checked **before** the generated-transaction count, so the refusal cannot be used to learn whether another student's rule has posted anything |
| **No client-supplied identity** | No request type has `userId`, `user_id` or `createdBy`. A body carrying them is accepted and ignored; the row is owned by the token's account |
| **No client-supplied type** | `type` is absent from both request types and derived from the category by the entity's factory and `setCategory`. A client able to send one could file an expense as income, and the reports would disagree with the category it sits under (BR-05) |
| **No client-supplied history** | `startDate` and `lastRunDate` are absent from the update request, and `lastRunDate` is mapped `updatable = false`. A client able to move the cursor could make the scheduler skip or repeat periods |
| **No client-supplied creation state** | `status` is ignored on create and fixed at `ACTIVE`. A rule cannot be created already stopped, and there is no way to create one `ENDED` to hide it from the list |
| **Role enforced server-side** | `/api/v1/recurring-rules/**` requires `hasRole("STUDENT")`. An administrator is refused with `403`: the scheduler posts on students' behalf and the seeded rules are read by the students who own them, so letting an administrator through here would create a rule owned by that administrator |
| **No sensitive fields** | `RecurringRuleMapper` is the single place that decides what leaves the server. `user_id`, `day_of_month`, `day_of_week` and the row timestamps are not mapped to the response |
| **The description is encrypted at rest** | `RecurringRuleService` writes `description` as a Base64 AES-256-GCM envelope and `RecurringRuleMapper` decrypts it on the way out, so a direct `SELECT` on `recurring_rules.description` reveals no text while the owner reads it normally. The **API contract is unchanged**: requests and responses still carry plaintext, and no client sees a key. The rule's `amount` is deliberately **not** encrypted — the procedure reads it and the views aggregate the transactions it generates — so a direct `SELECT` still reveals amounts (OB-013). Note that the scheduler copies this envelope into the posted transaction rather than re-encrypting: **OB-014**, pinned by `postedTransactionDescriptionSurvivesTheScheduler` |
| **Disabled account** | Rejected by the token filter before the request reaches the controller, as `401 UNAUTHENTICATED` (BR-03) |
| **Revoked session / stale token** | Rejected by the token filter on every request, as `401 UNAUTHENTICATED` (UC-02 B5) |
| **No lost update** | `RecurringRule` is annotated `@DynamicUpdate`, so an UPDATE names only the changed columns. Without it a plain Hibernate UPDATE would also write back the `next_run_date` and `last_run_date` it read, so an edit that changed only the amount could rewind the scheduler's cursor — re-posting periods already posted, or skipping ones that were due |
| **No state decided twice** | Update and delete take `SELECT ... FOR UPDATE` on the rule, so a status decided here is decided against a cursor the scheduler cannot move underneath the request. Two simultaneous deletes produce one deletion and one `404` |
| **No SQL, constraint name or trigger text in a response** | Refusals are translated by SQLSTATE, never forwarded. Asserted for every refusal path by `noResponseLeaksInternals` |
| **No schema identifiers in the module's own log lines** | A refused write logs the operation and the user id, deliberately not the exception: a trigger's `SIGNAL` text names the rule it guards, and MySQL's constraint messages name the table, the column and the offending value |
| **What is *not* claimed** | Hibernate's own `SqlExceptionHelper` logs the raw driver message at `ERROR` whenever a constraint or trigger actually fires. The module cannot suppress that without disabling Hibernate's SQL-error logging, which would hide genuine faults. The guarantee is exactly "our lines are clean", not "the log is clean" — the same bound modules 3 and 4 recorded |
| **The scheduler is not attackable through the API** | No endpoint runs it, so there is no route by which a client can trigger posting for every student in the system |
| **Concurrent scheduler runs are safe** | The procedure's `INSERT IGNORE` against `uk_occurrence_rule_period` makes a duplicate period impossible, so two instances racing produce one occurrence and one transaction. No distributed lock is needed, and adding one would be the application claiming a guarantee the database already provides |
| **Atomicity** | Each write is one transaction. A refused write changes nothing. The scheduler's posting is transactional per call, so a failure part-way leaves the occurrences and transactions it already committed and picks up the rest on the next run rather than double-posting |

---

## 16. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-09 | Set up a recurring income or expense | `POST /recurring-rules` | `RecurringRuleController.createRule` | `RecurringRuleService.create` | `recurring_rules` INSERT, `trg_recurring_rules_before_insert` | `createdRuleHasTheDocumentedShape`, `aNewRuleIsActiveAndHasNeverRun` |
| UC-09 | Every rule the student owns | `GET /recurring-rules` | `listRules` | `listRules` | `findForStudent` on `ix_recurring_user` | `theListIsScopedToTheCaller`, `emptyStateIsAnEmptyArray` |
| UC-09 | Soonest to run first | `GET /recurring-rules` | — | `listRules` | `ORDER BY next_run_date, id` | `rulesAreOrderedByNextRunDateThenId` |
| UC-09 | Paused and ended rules stay visible | `GET /recurring-rules` | — | `listRules` | no status predicate | `pausedAndEndedRulesAreStillListed` |
| UC-09 | Read one by id | `GET /recurring-rules/{id}` | `getRule` | `getRule` | `findByIdAndUserId` | `aRuleCanBeReadById` |
| BR-05 | The category decides the type, and it is read-only | `POST`, `PATCH` | — | `create`, `update` | `categories.type`, both `recurring_rules` triggers | `theTypeIsTheCategorysAndAClientSuppliedOneIsIgnored`, `movingACategoryRewritesTheStoredType` |
| UC-09 | The category is flattened into the response | every read | — | `RecurringRuleMapper` | `JOIN FETCH r.category` | `theCategoryIsFlattenedIntoTheResponse` |
| UC-09 | An absent nullable field is omitted | every read | — | `RecurringRuleMapper` | — | `absentValuesAreOmitted` |
| UC-09 | `nextRunDate` defaults to `startDate` | `POST` | — | `create` | `next_run_date NOT NULL` | `nextRunDateDefaultsToStartDate`, `nextRunDateCanBeLaterThanStartDate` |
| UC-09 | `intervalCount` defaults to 1 | `POST` | `CreateRecurringRuleRequest` | `create` (`DEFAULT_INTERVAL`) | `interval_count DEFAULT 1` | `intervalCountDefaultsToOneAndIsHonouredWhenSent` |
| BR-08 | A rule's dates may be in the future — it is a schedule, not a record | `POST`, `PATCH` | — | — | `sp_validate_transaction` exempts `RECURRING` | `aRuleInThePastIsAccepted`, `theSchedulerNeverWritesTheFuture` |
| BR-08 | The amount is strictly positive and fits the column | `POST`, `PATCH` | both request DTOs | — | `ck_recurring_amount`, `DECIMAL(15,2)` | `amountMustBePositive`, `amountMustFitTheColumn` |
| — | The interval is bounded | `POST`, `PATCH` | `@Min`, `@Max` | — | `ck_recurring_interval` | `intervalIsBounded` |
| — | The date triple must agree | `POST`, `PATCH` | `@Pattern` on `endDate` | `requireDatesAgree` | `ck_recurring_dates` | `endDateCannotPrecedeStartDate`, `nextRunDateCannotBeAfterEndDate`, `endDateShapeIsValidated`, `aNonExistentDateIsAFieldError` |
| — | Required fields are named individually | `POST` | `CreateRecurringRuleRequest` | — | NOT NULL columns | `requiredFieldsAreEachReported` |
| — | Enums accept member names, not numbers | `POST`, `PATCH` | both request DTOs | — | ENUM columns | `frequencyMustBeOneOfTheFiveMembers`, `enumNumbersAreRejected`, `anUnknownStatusIsRefused` |
| BR-02 | Only the caller's own or a shared category | `POST`, `PATCH` | — | `requireUsableCategory` | `sp_validate_recurring_rule` | `anotherStudentsCategoryIsNotFoundRatherThanForbidden` |
| BR-02 | Another student's rule is unreachable | all five | `@AuthenticationPrincipal` | every `requireOwnRule` lookup | `findByIdAndUserId` | `anotherStudentsRuleIsUnreachable`, `notFoundAndNotYoursAreIndistinguishable` |
| BR-07 | A retired category cannot be chosen or moved onto | `POST`, `PATCH` | — | `requireActiveCategory` | `sp_validate_recurring_rule` | `aRetiredCategoryCannotBeChosenOnCreate`, `aRetiredCategoryCannotBeMovedOnto` |
| BR-07 | A retired category freezes every update to its rules | `PATCH` | — | `update`, `CategoryRetiredException` | `trg_recurring_rules_before_update`, no `require_active` escape | `aRetiredCategoryBlocksEveryUpdateToItsRules`, `retiringACategoryDoesNotChangeTheRule` |
| UC-09 | Edit some fields | `PATCH /recurring-rules/{id}` | `updateRule` | `update` | `@DynamicUpdate` | `updateChangesOnlyWhatItSends` |
| UC-09 | An empty update changes nothing | `PATCH` | — | `update` | `@DynamicUpdate` | `anEmptyUpdateIsANoOp` |
| UC-09 | Pause, resume, end | `PATCH` | — | `update` | `status ENUM` | `pauseResumeAndEndRunThroughStatus` |
| UC-09 | Set or clear the end date | `PATCH` | `endDate` as a String | `parseOptionalDate` | nullable `end_date` | `endDateCanBeSetAndCleared` |
| UC-09 | Clear the description | `PATCH` | — | `trimToNull` | nullable `description` | `descriptionCanBeClearedAndIsTrimmed` |
| §7.5 | `startDate` is not editable | `PATCH` | `UpdateRecurringRuleRequest` has no `startDate` | `update` | `start_date` no setter | `startDateIsNotEditable` |
| §7.5 | Server-owned fields are ignored on create | `POST` | `CreateRecurringRuleRequest` field set | entity factory | — | `serverOwnedFieldsAreIgnoredOnCreate` |
| UC-09 | Remove a rule that never posted | `DELETE /recurring-rules/{id}` | `deleteRule` | `delete` | `recurring_rules` DELETE | `aRuleThatHasNeverPostedCanBeDeleted` |
| UC-09 | A rule that posted cannot be deleted | `DELETE` | — | `delete`, `RecurringRuleInUseException` | `countTransactionsGeneratedBy` | `aRuleThatHasPostedCannotBeDeleted`, `aSoftDeletedTransactionStillBlocksTheDelete` |
| UC-09 | Ending is the safe alternative and leaves module 4 working | `PATCH`, `DELETE`, `POST /restore` | — | — | `sp_validate_transaction`'s recurring-rule check | `endingARuleLeavesItsTransactionsEditable`, `deletingARuleNeverTouchesTransactions`, `transactionEndpointsStillBehaveAfterScheduledPosting` |
| BR-02 | Ownership is checked before the generated count | `DELETE` | — | `delete` ordering | — | `deleteIsOwnershipCheckedBeforeTheGeneratedCount` |
| UC-09 A1 | The scheduler posts a due rule | — | `RecurringProcedureDao.postDueOccurrences` | `RecurringScheduler` | `sp_post_recurring_transactions` | `aDueRuleIsPosted`, `aRuleThatIsNotDueIsNotPosted` |
| UC-09 A1 | Catch-up after downtime | — | `postDueOccurrences` | — | the procedure's `WHILE` loop | `catchUpPostsEveryMissedPeriod`, `catchUpCreatesOneTransactionPerPeriodKey` |
| BR-16 | Each period posts once | — | — | — | `recurring_occurrences`, `uk_occurrence_rule_period`, `INSERT IGNORE` | `runningTwicePostsOneOccurrencePerPeriod`, `concurrentSchedulerRunsAreIdempotent` |
| UC-09 | A paused or ended rule posts nothing | — | — | — | the cursor's `status = 'ACTIVE'` predicate | `aPausedRulePostsNothing`, `anEndedRulePostsNothing` |
| BR-07 | A rule on a retired category posts nothing | — | — | — | the cursor's `c.is_active = 1` join | `aRuleOnARetiredCategoryPostsNothing` |
| UC-09 | The end date stops posting and ends the rule | — | — | — | `v_next <= v_end`, `status = 'ENDED'` | `theSchedulerHonoursTheEndDate` |
| — | The scheduler never writes a future-dated row | — | — | — | `WHILE v_next <= v_as_of` | `theSchedulerNeverWritesTheFuture` |
| — | "Today" is the database's clock, not the JVM's | — | `postDueOccurrences(null)` | — | `CURDATE()` in the `+07:00` session | `theSchedulerUsesTheDatabaseClock` |
| BR-09 | A scheduled transaction is audited like any other | — | — | — | `trg_transactions_after_insert` | `aScheduledTransactionIsAuditedByTheSameTrigger` |
| — | Intervals are honoured, skipped periods are never posted | — | — | — | `interval_count` in `period_key` | `aSkippedIntervalIsNeverPosted` |
| BR-05, BR-07 | The trigger is the authority, not a second opinion | — | — | — | both `recurring_rules` triggers | `theTriggerIsTheAuthorityNotASecondOpinion` |
| — | The CHECK constraints hold for direct SQL too | — | — | — | `ck_recurring_amount`, `ck_recurring_interval`, `ck_recurring_dates` | `theCheckConstraintsHoldForEveryCaller` |
| — | A refused write rolls back with its transaction | — | — | `@Transactional` | — | `aRefusedWriteRollsBackWithinItsTransaction` |
| §7.5 | Every endpoint needs a token, and only `STUDENT` | all five | `SecurityConfig` role rule | `@AuthenticationPrincipal` | `users.role` | `everyEndpointRequiresAToken`, `anAdminTokenIsForbidden` |
| BR-03 | A revoked session and a disabled account stop working | all five | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.status`, `users.token_version` | `aRevokedTokenIsRejected`, `aDisabledAccountIsRejected` |
| §7.6 | Responses keep the schema out | all writes | — | `RecurringRuleWriteFailure` | — | `noResponseLeaksInternals`, `errorShapeIsConsistent`, `aNonNumericIdIsABadRequest` |
| §13 P5 | Concurrency | `DELETE`, `PATCH` | — | `@Lock(PESSIMISTIC_WRITE)` lookups | `SELECT ... FOR UPDATE` | `twoSimultaneousDeletesProduceOneDeletion`, `twoSimultaneousUpdatesBothSucceedOnTheLockedRow` |
| §13 P5 | Refusal classification | — | — | `RecurringRuleWriteFailure` | — | `RecurringRuleWriteFailureTest` |
| §16 | Cross-module regression against modules 3 and 4 | `PATCH /categories`, `POST`, `DELETE` | — | — | — | `categoryRetirementInteractsAsDocumented`, `creatingTheSameRuleTwiceIsAllowedAndIsNotIdempotent`, `endingARuleLeavesItsTransactionsEditable` |

`RecurringRuleWriteFailureTest` is a unit test rather than an integration test on purpose: it
verifies the SQLSTATE classification with the exact exceptions MySQL and Spring produce.
`sp_validate_recurring_rule` signals four different rules with one SQLSTATE and the service pre-checks
them all, so the signalled branch is nearly unreachable through the API — leaving it to an integration
test would leave it unverified.

---

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, interceptors, the shared error contract, the enum reference and the master
  table of all 76 operations
- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [categories.md](categories.md) — module 3, whose `categoryId` rule and retired-category behaviour this module inherits
- [transactions.md](transactions.md) — module 4, where the transactions the scheduler creates are read and edited
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
- [../modules/MODULE_05_RECURRING.md](../modules/MODULE_05_RECURRING.md) — the module report
