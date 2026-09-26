# Budgets API

Module 6 of the Campus Coin backend. Covers **UC-13 (set and manage a monthly spending limit per
category)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint · **Required role** `STUDENT`

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [The limit and the consumption are one row](#4-the-limit-and-the-consumption-are-one-row)
5. [Setting a limit raises no alert](#5-setting-a-limit-raises-no-alert)
6. [`GET /api/v1/budgets`](#6-get-apiv1budgets)
7. [`GET /api/v1/budgets/{id}`](#7-get-apiv1budgetsid)
8. [`POST /api/v1/budgets`](#8-post-apiv1budgets)
9. [`PATCH /api/v1/budgets/{id}`](#9-patch-apiv1budgetsid)
10. [`DELETE /api/v1/budgets/{id}`](#10-delete-apiv1budgetsid)
11. [The thresholds](#11-the-thresholds)
12. [The rules the database enforces, not this module](#12-the-rules-the-database-enforces-not-this-module)
13. [Status codes](#13-status-codes)
14. [Angular integration notes](#14-angular-integration-notes)
15. [Security properties](#15-security-properties)
16. [Traceability](#16-traceability)

---

## 1. Scope and what is deliberately absent

UC-13 sets a monthly spending limit for one expense category, lets the student change it, and lets
them remove it. This module implements exactly that.

| Not implemented | Why |
|---|---|
| A `userId` on any request | The owner is the account in the bearer token. Accepting it would make BR-02 a check that could be forgotten rather than a structural property |
| `categoryId` or `periodMonth` on update | Together with the owner they are the row's identity — `uk_budget_user_cat_month` is built from exactly those three columns. Moving a limit onto another category or month is a request for a *different* budget, which delete-then-create already expresses; and a writable `categoryId` would be a second way to put a limit on an income category |
| `spentAmount`, `remainingAmount`, `consumedPct`, `consumptionStatus` on any request | All four are derived by `v_budget_consumption` and are also what `sp_check_budget_alerts` compares. A client able to send one could show itself on track while the alert log disagreed |
| `type` on any request | A budget is only ever on an expense category (BR-11), enforced by `sp_validate_budget` through the insert trigger. There is no value to send: the category decides, and an income category is simply refused |
| A threshold-setting endpoint | The thresholds live in `system_settings` and are an administrator's concern (module 11), not a student's |
| An endpoint that raises, clears or recomputes an alert | An alert belongs to the *transaction* that crossed a threshold (BR-12), raised by the transaction triggers — see §5 |
| `/budgets/{id}/spend`, `/status`, `/check` | The spent figure and the status are columns of the same row, computed by the view on every read. Each would be a second name for the row itself |
| `/budgets/recalculate`, `/budgets/alerts` | A second trigger for `sp_check_budget_alerts`, one that could write the alert-log row BR-12's unique key has already, correctly, decided against |
| `/budgets/all`, `/list`, `/profile/me/budgets` | `GET /api/v1/budgets` answers all of them. Ownership belongs in the query, not in the URL — the same decision modules 3, 4 and 5 recorded |
| A `?status=` or `?categoryId=` filter | The response carries `categoryId` and `consumptionStatus`, and a month holds at most one row per category (BR-11). A second way to ask the same question becomes a duplicate endpoint later |
| Pagination | UC-13 does not ask for it. A student has one limit per category they care about, not thousands |
| `PUT` for the whole budget | UC-13 edits a value; it does not replace a record. `PATCH` is the method that matches |
| Rolling over a limit to the next month | A budget is per month by construction (`ck_budget_month`). Carrying a limit forward is a client action — read last month's and `POST` it for this one |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 27 | `GET` | `/api/v1/budgets` | UC-13 | List my limits for a month, with spending | `200` |
| 28 | `GET` | `/api/v1/budgets/{id}` | UC-13 | Read one of mine | `200` |
| 29 | `POST` | `/api/v1/budgets` | UC-13 | Set a limit for a category and month | `201` |
| 30 | `PATCH` | `/api/v1/budgets/{id}` | UC-13 | Change the limit | `200` |
| 31 | `DELETE` | `/api/v1/budgets/{id}` | UC-13 | Remove a limit | `204` |

All five require `Authorization: Bearer <accessToken>` and an account whose role is `STUDENT`. An
administrator token is refused with `403` — see §15.

Every response carries the month's consumption as the database computes it, so the create, update
and read responses all have one shape and a client never has to fetch a budget twice.

**There is no endpoint here that raises an alert, and that is the central design decision of the
module.** See §5.

---

## 3. Field reference

| Field | Type | In request | Validation when supplied | Source |
|---|---|---|---|---|
| `id` | number | read-only | — | `budgets.id` |
| `categoryId` | number | required on create | must be a category the caller may use, **an expense** category, and not retired | `budgets.category_id` |
| `categoryName` | string | read-only | — | `categories.name` |
| `categoryIcon` | string | read-only | — | `categories.icon` |
| `categoryColor` | string | read-only | — | `categories.color` |
| `periodMonth` | string `YYYY-MM` | optional on create; **never on update** | `^\s*\d{4}-\d{2}\s*$`, and must name a real month | `budgets.period_month` |
| `limitAmount` | number | required on create, optional on update | strictly greater than zero, at most two decimal places, at most 13 digits before the point | `budgets.limit_amount` |
| `spentAmount` | number | **never accepted** | — | `v_budget_consumption.spent_amount` |
| `remainingAmount` | number | **never accepted** | — | `v_budget_consumption.remaining_amount` |
| `consumedPct` | number | **never accepted** | — | `v_budget_consumption.consumed_pct` |
| `consumptionStatus` | string enum | **never accepted** | — | `v_budget_consumption.consumption_status` |

### Fields that are absent rather than null

`categoryIcon` and `categoryColor` are **omitted from the response** when the category has none,
rather than sent as `null`. That is the only place it happens in this module: every other field is
always present, because every other field has a value in every state.

```json
{
  "id": 3,
  "categoryId": 5,
  "categoryName": "Food & Drinks",
  "categoryIcon": "utensils",
  "categoryColor": "#F97316",
  "periodMonth": "2026-09",
  "limitAmount": 300.00,
  "spentAmount": 244.50,
  "remainingAmount": 55.50,
  "consumedPct": 81.50,
  "consumptionStatus": "NEAR"
}
```

### Amounts

`limitAmount` is a JSON number with at most two decimal places. It is always **positive**; a limit of
zero is refused by `ck_budget_limit` as well as by the annotation, because a limit that nothing can
be under is not a limit.

Three decimal places are refused rather than rounded, for the reason module 5 gives: MySQL would
round `1.005` to `1.01` on the way in, so the value the student typed and the value stored would
differ and no screen would say so.

The column is `DECIMAL(15,2)`. `limitAmount`, `spentAmount` and `remainingAmount` come back with two
decimal places. **`consumedPct` is a percentage, not money** — the view rounds it to two places too,
so `81.5` arrives as `81.50`, but a client should not format it with a currency pipe.

### The month is a string, `YYYY-MM`

A student sets a limit for a month, and `2026-09` is how they name one. The column is a `DATE` whose
day is pinned to the first (`ck_budget_month`), but that day is an implementation detail of how a
month is stored rather than something to show or to send.

`periodMonth` is a **string on both sides**, not a date:

- On the **request**, sending `"2026-09-15"` is refused by the pattern rather than quietly truncated
  to the 1st, and `"2026-9"` is refused rather than accepted as September. Both would otherwise be
  a way to send a date in a field that means a month.
- On the **response**, it is always `YYYY-MM`. The conversion lives in `BudgetMapper`, in both
  directions, so no other class has to remember the convention and no path can decide the first of
  the month while another decides the fifteenth.

`"2026-13"` matches the pattern and names no real month, so the service checks it and answers with a
field error naming `periodMonth` — not a `500` from a parser.

### Enum values are member names, never numbers

`consumptionStatus` publishes one of three member names the database stores, in upper case:

| Value | Meaning | Threshold |
|---|---|---|
| `ON_TRACK` | Below the near threshold | `< budget.near_threshold_pct` |
| `NEAR` | Approaching the limit | `>= budget.near_threshold_pct` (80% by default) |
| `EXCEEDED` | Limit reached or passed | `>= budget.exceeded_threshold_pct` (100%) |

`consumptionStatus: 2` is not a value; a client sends nothing here at all, and the field is
read-only. The **client** switches on the string.

### Defaults for a new budget

`periodMonth` is optional and defaults to **the current month** in `Asia/Ho_Chi_Minh` — the month a
budgets screen opens on, and the month whose alerts are live. `categoryId` and `limitAmount` are
required.

---

## 4. The limit and the consumption are one row

The most important thing for a frontend to understand is that **a budget response is not a stored
row**. The `budgets` table holds three columns — owner, category, month — and a limit. Everything
else in the response is computed at read time by the view `v_budget_consumption`:

| Field | Where it comes from |
|---|---|
| `spentAmount` | `SUM(amount)` over the category's **live** transactions in the month |
| `remainingAmount` | `limit_amount - spentAmount`. **Negative** once the limit is passed |
| `consumedPct` | `ROUND(spentAmount / limit_amount * 100, 2)`, `0` with no spending |
| `consumptionStatus` | A `CASE` over `consumedPct` against the two configured thresholds |

Two consequences a client should design for:

- **Nothing is cached in the database.** A student who records an expense sees the budget's
  `spentAmount` change on the very next read, with no write to the budget at all. Reloading the list
  is enough to refresh every figure; there is no invalidation step.
- **"Spent" counts only records that are not in the trash** (BR-09). Soft-deleting an expense in
  module 4 lowers `spentAmount` on the next read, and restoring it raises the figure again. A CSV
  import (module 12) counts the same way, because it writes ordinary transactions.

`spentAmount` is **never** recomputed in Java. The view's comparison is the same one
`sp_check_budget_alerts` makes when it decides whether to raise an alert, and a second definition of
"80% of the limit" is how a screen showing `ON_TRACK` and an alert log with a `BUDGET_NEAR` row
would come to disagree.

---

## 5. Setting a limit raises no alert

This is the point most likely to surprise a client, and it is deliberate.

**A budget alert is raised when a *transaction* pushes a month's spending past a threshold.** The
mechanism is `sp_check_budget_alerts(user_id, category_id, month)`, called from the transaction
triggers — `trg_transactions_after_insert` and `trg_transactions_after_update` — whenever a live
transaction is written. It is UC-14's behaviour, driven by UC-07's writes, and it is documented
where it fires: [transactions.md](transactions.md) and [notifications.md](notifications.md).

What this means for a budget endpoint:

| Action | Alert written? | Why |
|---|---|---|
| `POST /budgets` — set a limit | **No** | Setting a limit is a *target*, not a thing that gets exceeded. Nothing was spent |
| `PATCH /budgets/{id}` — lower a limit below what is already spent | **No** | The status is recomputed on every read and reads `EXCEEDED` at once, but BR-12 fires each threshold at most **once per category per month**, and this one already fired if it was going to |
| `PATCH /budgets/{id}` — raise a limit | **No** | Nothing was spent, and a threshold that has already fired does not fire again |
| `DELETE /budgets/{id}` | **No** | The notifications already sent are the student's record and are left alone — see §10 |
| `POST /transactions` — an expense | **Yes**, if it crosses a threshold | This is the only thing that raises a budget alert |

So a `consumptionStatus` of `EXCEEDED` on a budget and no `BUDGET_EXCEEDED` notification is a
**reconcilable state, not a bug**: the student is over the limit now, and was already warned when
they crossed it — or crossed it by setting the limit below where they already were, which is not an
event worth a message. The two are views of one fact at different moments, not two sources of truth.

A client that wants to know whether the student has been *told* reads the notifications
([notifications.md](notifications.md)); a client that wants to know where they *stand* reads the
budget. Neither derives the other.

---

## 6. `GET /api/v1/budgets`

List the caller's limits for one month, each with that month's consumption.

### Request

| Parameter | In | Type | Notes |
|---|---|---|---|
| `month` | query | string `YYYY-MM` | Optional. Defaults to the current month |

```
GET /api/v1/budgets?month=2026-09
Authorization: Bearer <accessToken>
```

### Response `200 OK`

An unordered-then-ordered array — ordered by `categoryId` ascending so the list is stable between
calls — with one row per category the caller has a limit for. **Only categories with a limit
appear**; a category with no limit has nothing to report and is not a zero row.

```json
[
  {
    "id": 3,
    "categoryId": 5,
    "categoryName": "Food & Drinks",
    "categoryIcon": "utensils",
    "categoryColor": "#F97316",
    "periodMonth": "2026-09",
    "limitAmount": 300.00,
    "spentAmount": 244.50,
    "remainingAmount": 55.50,
    "consumedPct": 81.50,
    "consumptionStatus": "NEAR"
  },
  {
    "id": 8,
    "categoryId": 7,
    "categoryName": "Transport",
    "categoryIcon": "bus",
    "categoryColor": "#3B82F6",
    "periodMonth": "2026-09",
    "limitAmount": 150.00,
    "spentAmount": 0.00,
    "remainingAmount": 150.00,
    "consumedPct": 0.00,
    "consumptionStatus": "ON_TRACK"
  }
]
```

A month in which the caller has set nothing gets `200` and `[]`, never `404`. A **past or future
month** is a valid question — there is no restriction on which month can be asked about, only on
which can be written, and even that is unrestricted. September's limits are still readable in
December.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `month` is not `YYYY-MM`, or matches the pattern but names no real month (`2026-13`). The field named is `periodMonth` |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |

---

## 7. `GET /api/v1/budgets/{id}`

Read one of the caller's own limits, so a client can refresh a single row after a change without
reloading the list.

### Request

| Parameter | In | Type | Notes |
|---|---|---|---|
| `id` | path | number | The budget's identifier |

```
GET /api/v1/budgets/3
Authorization: Bearer <accessToken>
```

### Response `200 OK`

One budget object, the same shape as a list element, with the consumption as it stands **now**.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `id` is not a number |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such budget **of the caller's** |

Another student's budget is not reachable here, and neither is one that does not exist. **Both
answer `404`, identically**, so the endpoint cannot be used to discover which budget identifiers
exist.

---

## 8. `POST /api/v1/budgets`

Set a monthly limit for one expense category, owned by the caller.

### Request

```json
{
  "categoryId": 5,
  "periodMonth": "2026-09",
  "limitAmount": 300.00
}
```

| Field | Required | Notes |
|---|---|---|
| `categoryId` | yes | Must be one of the caller's own or a shared default, an **expense** category, and not retired |
| `limitAmount` | yes | Strictly positive, at most two decimals |
| `periodMonth` | no | `YYYY-MM`; omitted means the current month |
| `userId` | **ignored** | The owner is the token's account. Sending one changes nothing |
| `type` | **ignored** | Derived from the category; an income category is refused, not typed |

### Response `201 Created`

The limit as the database now holds it, including its `id` and the month's consumption so far.

```json
{
  "id": 21,
  "categoryId": 5,
  "categoryName": "Food & Drinks",
  "categoryIcon": "utensils",
  "categoryColor": "#F97316",
  "periodMonth": "2026-09",
  "limitAmount": 300.00,
  "spentAmount": 0.00,
  "remainingAmount": 300.00,
  "consumedPct": 0.00,
  "consumptionStatus": "ON_TRACK"
}
```

`spentAmount` is not necessarily zero — a student can set a limit for a month they have already been
spending in, and the response reports what they have spent. See §5 for why that raises no alert.

### One limit per category per month (BR-11)

`uk_budget_user_cat_month` is `(user_id, category_id, period_month)` — BR-11 stated exactly. Setting
a second limit for the same category and month answers:

```json
{
  "status": 409,
  "errorCode": "BUDGET_ALREADY_EXISTS",
  "message": "A spending limit already exists for this category in 2026-09 (budget 21). Change it instead of creating a second one.",
  "path": "/api/v1/budgets"
}
```

The **same category in a different month is a different budget** and is allowed: a limit is per
month by construction. Carrying one forward is a client action — read last month's and `POST` it for
this one.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A required field is missing, `limitAmount` is not positive or has too many decimals, `periodMonth` is malformed or names no real month, the category is an **income** category (BR-11), or the category is retired (BR-07). `fieldErrors` names `categoryId` for the last two |
| `400` | `BAD_REQUEST` | The body is not valid JSON |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | `categoryId` names no category the caller may use |
| `409` | `BUDGET_ALREADY_EXISTS` | A limit already exists for this category and month |
| `409` | `DATA_CONFLICT` | The category was retired or deleted, or the same limit was set concurrently, while the request was in flight |

**Why an income category is a `400` and a missing category is a `404`.** An income category *exists*
and the caller *may use it* — they simply may not put a limit on it, because a limit measures
spending and there is nothing to measure against income. That is a fixable choice of field, so it
names `categoryId`. A category that is not the caller's is indistinguishable from one that does not
exist, so it is `404` — the same rule modules 3, 4 and 5 follow.

---

## 9. `PATCH /api/v1/budgets/{id}`

Change the limit. **`limitAmount` is the only field this operation takes.**

### Request

```json
{
  "limitAmount": 350.00
}
```

| Field | Notes |
|---|---|
| `limitAmount` | Strictly positive, at most two decimals |
| `categoryId` | **Not accepted.** Silently ignored if sent; the category is the row's identity (BR-11) |
| `periodMonth` | **Not accepted.** Silently ignored if sent; the month is the row's identity (BR-11) |
| `spentAmount`, `consumedPct`, `consumptionStatus` | **Not accepted.** Derived by the view |

An empty body `{}` is valid and changes nothing; the response is the budget unchanged and no
`UPDATE` is issued, because the entity is `@DynamicUpdate`.

### Why the category and month are not editable

The other three columns — the owner, the category and the month — are the row's identity, and
`uk_budget_user_cat_month` is built from exactly them. Moving a limit onto another category or month
is not an edit of this budget but a request for a *different* one, which `DELETE` then `POST`
expresses without ambiguity. A writable `categoryId` would additionally be a second way to put a
limit on an income category, slipping past the BR-11 check the insert trigger makes and which no
update trigger repeats for a value it was given at creation.

### Lowering a limit below what is already spent

This is allowed, and it is the operation a student reaches for when they realise they are
overspending. The response reports the new state immediately — `remainingAmount` goes **negative**
and `consumptionStatus` becomes `EXCEEDED`, because the view recomputes on every read. No second
`BUDGET_EXCEEDED` notification appears, for the reason §5 gives: BR-12 fires each threshold at most
once per category per month.

### Response `200 OK`

The budget as it now is in the database, with the consumption recomputed.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `limitAmount` is not positive or has too many decimals |
| `400` | `BAD_REQUEST` | `id` is not a number, or the body is not valid JSON |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such budget of the caller's |
| `409` | `CATEGORY_RETIRED` | The budget's own category has been retired |
| `409` | `DATA_CONFLICT` | The budget changed underneath the request |

### A budget whose category has been retired cannot be changed

This is the most surprising behaviour in the module, it is enforced by the database rather than by
this API, and it answers `409 CATEGORY_RETIRED`:

> This budget's category has been retired, so the limit cannot be changed. Restore the category, or
> remove the budget if it is no longer needed.

`trg_budgets_before_update` calls `sp_validate_budget` on **every** update, and that procedure has no
"skip the active check" escape — unlike the transaction trigger, which exempts an update that does not
move the category. So retiring a category in module 3 **freezes the limit** exactly as it freezes
module 5's rules. The service pre-checks this one condition so the caller is told which rule refused
them and what to do; without that check the trigger would refuse anyway and the answer would be a
generic `DATA_CONFLICT` whose advice — "refresh and try again" — cannot work while the category stays
retired.

The two remedies are:

1. **Restore the category** (module 3), change the limit, and retire it again if desired.
2. **Remove the budget** — see §10. A delete fires no `BEFORE UPDATE` trigger, so it is always
   accepted. This asymmetry is deliberate: removing the limit is the one action that leaves the
   category alone, so it must remain the student's way out of the state.

**The budget stays readable and keeps reporting.** The row is not deleted, the list still returns it,
and `spentAmount` still counts its category's live transactions — so a budgets screen shows a limit
the student can see but not edit until they act. `retiringTheCategoryLeavesTheLimitReadable` pins
that, and `retiringTheCategoryFreezesTheExistingLimit` pins the freeze and both remedies.

---

## 10. `DELETE /api/v1/budgets/{id}`

Remove a limit outright.

### Request

```
DELETE /api/v1/budgets/21
Authorization: Bearer <accessToken>
```

### Response `204 No Content`

No body.

### Nothing depends on a limit

Unlike a recurring rule, **a budget can always be removed**, and there is no `409` for being in use.
Nothing points at it:

| Thing that could have depended on it | What actually happens |
|---|---|
| The spending it measured | Belongs to the **transactions**, not to the limit. Every transaction survives untouched; a category with no limit simply stops being tracked against one |
| The alert history (`budget_alert_log`) | Cascades on the budget's delete (`fk_alert_budget ... ON DELETE CASCADE`) — the log rows are bookkeeping for thresholds, and thresholds for a limit that no longer exists mean nothing |
| The **notifications** already sent about it | **Left alone.** A notification is a message the student received, and deleting the limit it was about should not erase the fact that they were told. The rows survive, and so does their `refEntityType: "BUDGET"` pointer — which now names a budget that no longer exists. A client opening the link should be ready for `404` and show "that budget is no longer set" rather than an error |

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `id` is not a number |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such budget of the caller's |

---

## 11. The thresholds

The two percentages that separate `ON_TRACK` from `NEAR` and `EXCEEDED` are **not constants in this
module**. They are rows in `system_settings`, seeded by the schema:

| Setting key | Default | Meaning |
|---|---|---|
| `budget.near_threshold_pct` | `80` | At or above this, a budget reads `NEAR` |
| `budget.exceeded_threshold_pct` | `100` | At or above this, it reads `EXCEEDED` |

`v_budget_consumption` reads both through a `LEFT JOIN`, and `sp_check_budget_alerts` reads the same
two rows. So changing a setting changes **the label a screen shows and the moment an alert fires
together, from one value, with no redeploy** (VĐ-05).

For a client this means:

- **Do not hard-code 80 and 100.** They are the seeded values, not the contract. A client that wants
  a progress bar's "warning" colour to match the API's classification must use
  `consumptionStatus`, which is the API's own answer.
- **The two thresholds are independent.** An administrator could set `NEAR` to 95 and `EXCEEDED` to
  100, narrowing the near band; or set `near` above `exceeded`, which would make `NEAR` unreachable
  — the view's `CASE` checks `EXCEEDED` first, so `EXCEEDED` wins. The seeded values are the
  sensible ones.

`budget.near_threshold_pct` is also what the *test suite* pins: the module's tests read the seeded
values rather than assuming them, so a schema change that moved a threshold would change what the
tests assert rather than silently passing.

---

## 12. The rules the database enforces, not this module

This module does not decide these rules and does not restate them in Java. They belong to the
schema, where they hold for every caller — including a hand-run SQL statement — and they are
translated into the errors above on the way out.

| Rule | Enforced by | Surfaced as |
|---|---|---|
| At most one limit per student, category and month (BR-11) | `uk_budget_user_cat_month` | `409 BUDGET_ALREADY_EXISTS` (the service pre-checks it to name the remedy) |
| A limit may only be on an expense category (BR-11) | `sp_validate_budget`, via both triggers | `400` naming `categoryId` |
| The category must be the caller's own or a shared default (BR-02) | `sp_validate_budget` | `404` |
| The category must not be retired (BR-07) | `sp_validate_budget`, via **both** triggers | `400` naming `categoryId` on create; `409 CATEGORY_RETIRED` when an existing budget's own category is retired |
| The category must exist | `sp_validate_budget` | `404` |
| The limit must be strictly positive | `ck_budget_limit` | `400` naming `limitAmount` |
| The month must be the first of a month | `ck_budget_month` | `400` naming `periodMonth` (the mapper's `YearMonth.atDay(1)` cannot produce any other day) |
| Each threshold alerts at most once per category per month (BR-12) | `uk_alert_budget_threshold`, `INSERT IGNORE` | — (the trigger's own guard, on the transaction path) |

**Why these are not in Java.** `sp_validate_budget` raises `SQLSTATE '45000'` for **four different
rules**, so a client told only "the write was refused" could not point at the field that needs
fixing. The service therefore asks three of the same questions first, purely so it can name the
field, and the trigger still runs afterwards. The database's rule is the one that holds; the
application's check is a stricter restatement made for the error message, and a request that slips
past it is caught by the translation below rather than accepted.

**Refusals are translated by SQLSTATE, never by message.** `SIGNAL SQLSTATE '45000'` arrives through
Spring as `InvalidDataAccessResourceUsageException` — **not** `PersistenceException` — so
`BudgetWriteFailure` walks the cause chain for the SQLSTATE rather than matching the driver's text,
which is localised and not a contract. A recognised refusal becomes `409 DATA_CONFLICT` with a
message the student can act on; the BR-11 unique key gets its own `409 BUDGET_ALREADY_EXISTS`;
anything unrecognised is rethrown and answered as an internal error rather than mislabelled.
`BudgetWriteFailureTest` verifies the classification directly, because the service's pre-checks make
the signalled branch nearly unreachable through the API.

---

## 13. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body and replace the cached budget |
| `201` | Created | Add the returned budget to the cached list, or replace the row it collides with |
| `204` | Deleted, no body | Remove the budget from the list |
| `400` | Validation failed, the category is an income category or retired, or the body/parameter is malformed | If `fieldErrors` is present, show each `message` beside its `field`. Otherwise show `message` |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `403` | The token's role is not `STUDENT` | Send the user to their own area; this is not a sign-in problem |
| `404` | No such budget or category of the caller's | Reload; the row is gone, was never the caller's, or a notification's link points at a budget that has been removed |
| `409` | `BUDGET_ALREADY_EXISTS`, `CATEGORY_RETIRED` or `DATA_CONFLICT` | `BUDGET_ALREADY_EXISTS` names the remedy in its message: change the existing limit instead. `CATEGORY_RETIRED` means restore the category or remove the budget — retrying cannot work. `DATA_CONFLICT` means reload and retry |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

`fieldErrors[].field` is the canonical property name project-wide. It is `field`, never `path`.

Note the difference between a **body** failure and a **parameter** failure: a validation failure on a
field has `fieldErrors`, and a malformed path parameter does not, because a parameter is not a body
field and there is nothing to name. Both are `400`.

### Error codes in full

The two conflict codes:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "BUDGET_ALREADY_EXISTS",
  "message": "A spending limit already exists for this category in 2026-09 (budget 21). Change it instead of creating a second one.",
  "path": "/api/v1/budgets"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "DATA_CONFLICT",
  "message": "The budget could not be saved because the data it depends on changed. Refresh and try again.",
  "path": "/api/v1/budgets"
}
```

A validation failure, with one error per bad field:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/budgets",
  "fieldErrors": [
    { "field": "limitAmount", "message": "Limit amount must be greater than zero." },
    { "field": "categoryId", "message": "Choose an expense category. Income categories cannot have a limit." }
  ]
}
```

**Switch on `errorCode`, not on `message`.** The code is the stable part of the contract; the message
is human-readable text that may be reworded. `BUDGET_ALREADY_EXISTS` is distinct from
`DATA_CONFLICT` because their remedies differ — change the existing limit, versus reload and retry.

---

## 14. Angular integration notes

### 14.1 The current frontend must be rewired

The frontend has a budget model and a budget service, but both are **mock-only**
(`useMockData: true`) and neither matches this contract. Four divergences, all of which a client
must resolve:

| Frontend today (`core/models/budget.model.ts`) | This API | Change needed |
|---|---|---|
| `alertStatus: 'SAFE' \| 'WARNING' \| 'DANGER'` | `consumptionStatus: 'ON_TRACK' \| 'NEAR' \| 'EXCEEDED'` | **Rename and remap.** It is the API's own classification, not a client-side comparison — do not compute it from `spent / limit` |
| `monthlyLimit: number` | `limitAmount` (string on the wire, number after parsing) | Rename |
| `spent: number` | `spentAmount` | Rename |
| `period: string` | `periodMonth` | Rename |
| `id: string`, `categoryId: string` | `id: number`, `categoryId: number` | As in modules 4 and 5. Anything comparing ids must compare numbers |
| *(no such field)* | `remainingAmount`, `consumedPct` | **Add them.** A progress bar needs both, and the API already computed them — recomputing `limit - spent` in the client is how the two drift apart |
| *(no such field)* | `categoryIcon`, `categoryColor` | Already present in the mock, absent from the API shape documented above only when the category has none |

```ts
export type ConsumptionStatus = 'ON_TRACK' | 'NEAR' | 'EXCEEDED';

export interface Budget {
  id: number;
  categoryId: number;
  categoryName: string;
  categoryIcon?: string;
  categoryColor?: string;
  periodMonth: string;        // 'YYYY-MM'
  limitAmount: number;
  spentAmount: number;
  remainingAmount: number;
  consumedPct: number;
  consumptionStatus: ConsumptionStatus;
}
```

**The client-side recomputation must go.** `budget.service.ts` currently derives `alertStatus` by
comparing `spent / monthlyLimit` against hard-coded `0.8` and `1.0`, and recomputes `spent` from the
transaction list. Both are wrong against this API:

- The thresholds are **configuration** (`system_settings`), not constants — see §11.
- `spentAmount` is the database's `SUM` over live transactions, which the client cannot reproduce
  faithfully once soft deletes and imports exist.

Read the figures; do not recompute them.

### 14.2 Notifications are a separate module

`BudgetService.getBudgetAlerts()` invents `BudgetAlertNotification` objects by filtering budgets and
synthesising a `message` string client-side. **That is exactly what UC-14 provides server-side**, and
the synthesised text will not match the real alerts word-for-word. Replace the whole method with
`GET /api/v1/notifications` and filter `type === 'BUDGET_NEAR' || type === 'BUDGET_EXCEEDED'`. The
real messages are generated by `sp_check_budget_alerts`, carry a `linkUrl` of `/budgets` and a
`refEntityId` naming the budget — see [notifications.md](notifications.md).

The one thing the client-side version cannot represent is that an alert is **raised once per
threshold per month** (BR-12): the synthesised list would re-show the same warning on every reload,
while the real one is a message that was sent once and is then read or unread.

### 14.3 Which call to make

- Entering the budgets screen: `GET /api/v1/budgets?month=2026-09`. One call; no pagination.
- Changing a limit: `PATCH /api/v1/budgets/{id}` with `{"limitAmount": ...}`.
- Setting a limit for a category that has none: `POST /api/v1/budgets` with `categoryId` and
  `limitAmount`. **Expect `409`** if the client's cached state is stale and a limit already exists —
  fall back to `PATCH` using the id the error names.
- Removing a limit: `DELETE /api/v1/budgets/{id}`. No confirmation about dependencies is needed;
  nothing depends on it.
- Showing the spending behind a figure: `GET /api/v1/transactions?from=...&to=...&categoryId=...`
  (module 4). There is no per-budget transactions endpoint.

Send the token as `Authorization: Bearer <accessToken>`. No endpoint in this module works without
one.

### 14.4 Handling each status

```ts
this.budgetService.updateLimit(id, { limitAmount }).subscribe({
  next: updated => this.replaceRow(updated),
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'VALIDATION_ERROR':
        response.error.fieldErrors?.forEach((e: { field: string; message: string }) =>
          this.form.get(e.field)?.setErrors({ server: e.message }));
        break;
      case 'BUDGET_ALREADY_EXISTS':
        // The client offered "add" for a category that already has a limit.
        // Switch to the update flow using the id named in the message.
        this.reload();                       // fetch the real row
        this.toast.show(response.error.message);
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

- **The category picker should offer expense categories that do not already have a limit for the
  chosen month.** The client can compute that from `GET /categories` and `GET /budgets`, and doing so
  turns a `409` into a disabled option. Handle `409` anyway, because the list can be stale.
- **A negative `remainingAmount` is over-budget, not a bug.** Render it as such — "150.00 over" rather
  than "−150.00 remaining". `consumptionStatus === 'EXCEEDED'` is the same fact.

### 14.5 Months without a timezone bug

`periodMonth` is a plain `"YYYY-MM"` string with no day and no time. Do not parse it:

```ts
readonly currentMonth = new Date().toLocaleDateString('sv-SE').slice(0, 7);  // 'YYYY-MM'
```

`new Date('2026-09')` is parsed as `2026-09-01T00:00:00Z`, so a client west of UTC that formats it
back shows **August**. Comparing and displaying the string avoids the problem entirely. If the client
must compute the current month, take it from a local date formatted as `YYYY-MM` (the `sv-SE` trick),
never from `toISOString().slice(0, 7)` — that converts to UTC first and can name the wrong month on
the last day of a month.

Because the server defaults an omitted `periodMonth` to the current month **in `Asia/Ho_Chi_Minh`**,
a client near a month boundary should send the month explicitly rather than rely on the default, if
what it wants is the month the *student* is in. For a student in Vietnam the two agree; for a client
running with a different system zone they need not.

---

## 15. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. Every single-row query takes the caller's id as well as the budget's (`BudgetConsumptionDao`, `BudgetRepository`), so no service method can reach another student's budget. The database re-checks the category's owner through `sp_validate_budget`, because `fk_budget_category` only proves the row exists |
| **No identifier probing** | Another student's budget and an unknown id both answer `404`, identically. A category that is not the caller's also answers `404`, never `403` |
| **No client-supplied identity** | No request type has `userId`, `user_id` or `createdBy`. A body carrying them is accepted and ignored; the row is owned by the token's account |
| **No client-supplied measurement** | `spentAmount`, `remainingAmount`, `consumedPct` and `consumptionStatus` are absent from both request types and derived by `v_budget_consumption`. A client able to send one could show itself on track while the alert log disagreed |
| **No client-supplied threshold** | The two thresholds come from `system_settings`, which no student endpoint writes. A client cannot narrow its own warning band |
| **No second alert trigger** | There is no endpoint that calls `sp_check_budget_alerts`. Alert creation stays on the one path BR-12 describes — the transaction — so the alert log cannot be written around its own unique key |
| **Role enforced server-side** | `/api/v1/budgets/**` requires `hasRole("STUDENT")`, with the same for `/api/v1/notifications/**`. An administrator is refused with `403`: a budget measures one student's spending, and an administrator writing one would create a limit owned by that administrator |
| **No sensitive fields** | `BudgetMapper` is the single place that decides what leaves the server. `user_id` and the row timestamps are not mapped to the response |
| **Disabled account** | Rejected by the token filter before the request reaches the controller, as `401 UNAUTHENTICATED` (BR-03) |
| **Revoked session / stale token** | Rejected by the token filter on every request, as `401 UNAUTHENTICATED` (UC-02 B5) |
| **No lost update** | `Budget` is annotated `@DynamicUpdate`, so an UPDATE names only the changed column. Without it a plain Hibernate UPDATE would write back every column it read |
| **No state decided twice** | Update and delete take `SELECT ... FOR UPDATE` on the budget, so two simultaneous edits settle on one value rather than both reporting success from a lock-free read. Two simultaneous deletes produce one deletion and one `404` |
| **No SQL, constraint name or trigger text in a response** | Refusals are translated by SQLSTATE, never forwarded. Asserted for every refusal path by the module's tests |
| **No schema identifiers in the module's own log lines** | A refused write logs the operation and the user id, deliberately not the exception: a trigger's `SIGNAL` text names the rule it guards, and MySQL's constraint messages name the table, the column and the offending value |
| **What is *not* claimed** | Hibernate's own `SqlExceptionHelper` logs the raw driver message at `ERROR` whenever a constraint or trigger actually fires. The module cannot suppress that without disabling Hibernate's SQL-error logging, which would hide genuine faults. The guarantee is exactly "our lines are clean", not "the log is clean" — the same bound modules 3, 4 and 5 recorded |
| **Atomicity** | Each write is one transaction. A refused write changes nothing |

---

## 16. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-13 | Set a limit for a category and month | `POST /budgets` | `BudgetController.createBudget` | `BudgetService.create` | `budgets` INSERT, `trg_budgets_before_insert` | `createdBudgetHasTheDocumentedShape` |
| UC-13 | The category is flattened into the response | every read | — | `BudgetMapper` | `v_budget_consumption` joins `categories` | `theCategoryIsFlattenedIntoTheResponse` |
| UC-13 | List the month's limits | `GET /budgets` | `listBudgets` | `listBudgets` | `v_budget_consumption` filtered by user and month | `theListCarriesOneRowPerCategory`, `emptyStateIsAnEmptyArray` |
| UC-13 | The month defaults to the current one | `GET`, `POST` | `@RequestParam`, DTO | `resolvePeriodMonth` | `ck_budget_month` | `anOmittedMonthDefaultsToTheCurrentOne` |
| UC-13 | Read one limit by id | `GET /budgets/{id}` | `getBudget` | `getBudget` | `v_budget_consumption` by id | `readingOneLimitMatchesTheList` |
| UC-13 | A month with nothing set is an empty array | `GET /budgets` | `listBudgets` | `listBudgets` | no predicate beyond user and month | `emptyStateIsAnEmptyArray` |
| UC-13 | Change the limit | `PATCH /budgets/{id}` | `updateBudget` | `update` | `@DynamicUpdate` | `changingALimitKeepsItsIdentity` |
| UC-13 | An empty change is a no-op | `PATCH` | — | `update` | `@DynamicUpdate` | `anEmptyChangeIsANoOp` |
| UC-13 | Remove a limit | `DELETE /budgets/{id}` | `deleteBudget` | `delete` | `budgets` DELETE, `fk_alert_budget` cascade | `removingALimitDeletesTheRow` |
| UC-13 | Removing a limit keeps the transactions | `DELETE` | — | `delete` | `budgets` DELETE only | `removingALimitKeepsTheTransactions` |
| UC-13 | Removing a limit keeps its notifications | `DELETE` | — | `delete` | `notifications` untouched | `removingALimitKeepsItsNotifications` |
| UC-13 | The spend counts only live records (BR-09) | every read | — | — | `v_budget_consumption`'s `is_deleted = 0` | `deletedRecordsStopCounting` |
| UC-13 | Income never counts towards a limit | every read | — | — | the view groups by category, not by type | `incomeDoesNotCountTowardsALimit` |
| UC-13 | The status follows the configured thresholds | every read | — | — | the view's `CASE` over `system_settings` | `theStatusFollowsTheConfiguredThresholds` |
| BR-11 | At most one limit per category per month | `POST` | — | `create` pre-check | `uk_budget_user_cat_month` | `aSecondLimitForTheSameMonthIsRefused`, `theUniqueKeyRefusesASecondLimitForEveryCaller` |
| BR-11 | The same category in another month is a different budget | `POST` | — | `create` | `uk_budget_user_cat_month` | `theSameCategoryCanBeLimitedInTwoMonths` |
| BR-11 | Only an expense category may be limited | `POST` | `CreateBudgetRequest` | `requireExpenseCategory` | `sp_validate_budget` | `anIncomeCategoryCannotBeLimited`, `theTriggerRefusesAnIncomeCategoryForEveryCaller` |
| BR-07 | A retired category cannot be limited | `POST` | — | `requireActiveCategory` | `sp_validate_budget` | `aRetiredCategoryCannotBeLimited`, `theTriggerRefusesARetiredCategoryForEveryCaller` |
| BR-07 | A retired category freezes every update to its budgets | `PATCH` | — | `update`, `CategoryRetiredException` | `trg_budgets_before_update` → `sp_validate_budget`, no `require_active` escape | `retiringTheCategoryFreezesTheExistingLimit` |
| BR-07 | A retired category leaves the budget readable, and removable | `GET`, `DELETE` | — | `delete` (no `BEFORE UPDATE` trigger) | `notifications` untouched, `budgets` DELETE | `retiringTheCategoryLeavesTheLimitReadable` |
| BR-02 | Only the caller's own or a shared category | `POST` | — | `requireUsableCategory` | `sp_validate_budget` | `anotherStudentsCategoryCannotBeLimited`, `theTriggerRefusesAnotherStudentsCategoryForEveryCaller` |
| BR-02 | Another student's budget is unreachable | all five | `@AuthenticationPrincipal` | every ownership lookup | `findOne(userId, id)`, `findByIdAndUserId` | `anotherStudentsLimitIsUnreachable`, `notYoursAndNotFoundAreIndistinguishable` |
| — | The category and month are not editable | `PATCH` | `UpdateBudgetRequest` field set | `update` | `uk_budget_user_cat_month` identity | `categoryIsNotEditable` |
| — | The limit is validated | `POST`, `PATCH` | both request DTOs | — | `ck_budget_limit`, `DECIMAL(15,2)` | `theLimitIsValidated`, `anInvalidNewLimitIsRefused`, `theLimitCheckConstraintHoldsForEveryCaller` |
| — | The month's shape is validated | `GET`, `POST` | `@Pattern`, `@RequestParam` | `resolvePeriodMonth` | `ck_budget_month` | `theMonthFormatIsValidated`, `anImpossibleMonthIsRefused`, `theMonthCheckConstraintHoldsForEveryCaller` |
| — | A personal category can be limited | `POST` | — | `requireUsableCategory` | `categories` owner | `aPersonalCategoryCanBeLimited` |
| UC-13, BR-12 | **Setting a limit raises no alert** | `POST` | — | `create` | the alert is written by the transaction triggers, not here | `settingALimitRaisesNoAlert`, `budgetWritesRaiseNoMessages` |
| UC-13, BR-12 | **Lowering a limit raises no second alert** | `PATCH` | — | `update` | `uk_alert_budget_threshold` | `loweringALimitRaisesNoSecondAlert` |
| UC-13 | The list is scoped to the requested month | `GET` | `month` parameter | `listBudgets` | the view's `period_month` predicate | `theListIsScopedToTheRequestedMonth` |
| §7.5 | Every endpoint needs a token, and only `STUDENT` | all five | `SecurityConfig` role rule | `@AuthenticationPrincipal` | `users.role` | `noTokenIsRefused`, `anAdministratorTokenIsRefused` |
| BR-03 | A revoked session and a disabled account stop working | all five | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.status`, `users.token_version` | covered by the module-1 filters; asserted for this module by `noTokenIsRefused` |
| — | A missing budget is `404`, and a non-numeric id is `400` | `GET`, `PATCH`, `DELETE` | `@PathVariable` | `requireOwn*` | — | `aMissingBudgetIsNotFound` |
| §13 P5 | Concurrency | `POST`, `PATCH`, `DELETE` | — | `@Lock(PESSIMISTIC_WRITE)` on the update/delete lookup, `uk_budget_user_cat_month` on create | `SELECT ... FOR UPDATE` | `twoSimultaneousSetsProduceOneLimit`, `twoSimultaneousChangesSettleOnOneValue`, `twoSimultaneousDeletesProduceOneDeletion`, `aLoweredLimitRacingARecordSettlesConsistently` |
| — | Refusal classification | — | — | `BudgetWriteFailure` | — | `BudgetWriteFailureTest` |
| §26 | The endpoints are in the inventory and the OpenAPI document | all five | — | — | — | `OpenApiContractIT#documentMatchesTheInventory`, `#budgetSchemasMatchTheDocumentedContract` |

`BudgetWriteFailureTest` is a unit test rather than an integration test on purpose: it verifies the
SQLSTATE classification with the exact exceptions MySQL and Spring produce.
`sp_validate_budget` signals four different rules with one SQLSTATE and the service pre-checks them
all, so the signalled branch is nearly unreachable through the API — leaving it to an integration
test would leave it unverified.

---

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, interceptors, the shared error contract, the enum reference and the master
  table of all 76 operations
- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [categories.md](categories.md) — module 3, whose `categoryId` rule and retired-category behaviour this module inherits
- [transactions.md](transactions.md) — module 4, the writes that actually raise a budget alert
- [notifications.md](notifications.md) — module 6's other half, UC-14, where the alerts are read
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
- [../modules/MODULE_06_BUDGET.md](../modules/MODULE_06_BUDGET.md) — the module report
