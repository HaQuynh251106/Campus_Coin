# Reports API

**Endpoints 36–37.** `GET /api/v1/reports` — one month's financial report: the month's totals, every
category's share of it, and the six-month trend (UC-15). `GET /api/v1/reports/spending` — the same
month's spending broken down by day or by ISO week (UC-15). Between them they are the figures a
student exports (UC-16).

**The module's central constraint is that its sources are not equally month-selectable, and that is
why there are two endpoints rather than one.** `v_monthly_income_expense` and
`v_category_month_totals` are keyed by `period_month` and answer about any month; the two views behind
the spending series derive their range from `CURDATE()` **inside the database session** and cannot be
asked about another one. §4 and §5 are the parts worth reading before changing anything here. §12
records why UC-16 has no route of its own.

---

## Contents

| § | |
|---|---|
| 1 | [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent) |
| 2 | [Endpoints at a glance](#2-endpoints-at-a-glance) |
| 3 | [Field reference — `GET /api/v1/reports`](#3-field-reference--get-apiv1reports) |
| 4 | [The selected month, and the one figure that ignores it](#4-the-selected-month-and-the-one-figure-that-ignores-it) |
| 5 | [`GET /api/v1/reports/spending`](#5-get-apiv1reportsspending) |
| 6 | [Absent versus zero](#6-absent-versus-zero) |
| 7 | [`percentage` does not sum to 100](#7-percentage-does-not-sum-to-100) |
| 8 | [Status codes](#8-status-codes) |
| 9 | [Security properties](#9-security-properties) |
| 10 | [Angular integration notes](#10-angular-integration-notes) |
| 11 | [Traceability](#11-traceability) |
| 12 | [Why there is no export endpoint](#12-why-there-is-no-export-endpoint) |

---

## 1. Scope and what is deliberately absent

| Use case | Behaviour |
|---|---|
| UC-15 | The month's income, spending, net figure and record count; each category's total and share; the six-month trend; the same month broken down by day or by ISO week |
| UC-16 | Export a file only when the user asks, with nothing buffered — §12 |
| UC-15 BA | Every figure is the schema's. A report that recomputed a total would eventually disagree with the dashboard reading the same views |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` parameter | The account is the bearer token's. A report is one named student's month, so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/reports/export` and every other export shape | §12 — BR-18 generates the file at call time with nothing buffered, which is what these two reads already do. A CSV route would be a second way to ask one question (section 13) |
| `GET /api/v1/reports/{id}` | A report is not a stored row. It has no table, no procedure and no identifier — it is a read of views |
| `GET /api/v1/profile/me/reports` | The collection is already the caller's own. A second path would be a second name for it |
| `/reports/summary`, `/reports/monthly`, `/reports/categories` | Each would be one block of the one report that already carries all of them, for the same month, in one response |
| A `%month=` on the spending series other than the current one | §5 — refused with `400`, not ignored |
| A `MONTHLY` granularity | The two values are the two breakdowns the schema computes. A third would be a grouping no view produces |
| Budgets on the report | `GET /api/v1/budgets` carries each limit with its consumption. Publishing them again would be a second route to the same rows |
| Marking a tip pinned or dismissed | A report render must not cause a state change. Nothing on either path writes |
| `POST`, `PATCH`, `PUT`, `DELETE` on either path | UC-15 is a set of reads. A report has nothing for a client to write |
| A per-category trend, or a trend over a selectable window | BR-17 defines the trend as a fixed six months. Nothing in the schema computes a per-category trend, and inventing one here would be a second definition of a month's spending |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 36 | `GET` | `/api/v1/reports` | UC-15, UC-16 | Bearer token, role `STUDENT` | `200` report |
| 37 | `GET` | `/api/v1/reports/spending` | UC-15 | Bearer token, role `STUDENT` | `200` spending series |

**Two paths, no aliases.** An administrator token is `403` on both (§9).

| Endpoint | Query parameters | All optional |
|---|---|---|
| 36 | `month` | `yyyy-MM`. Defaults to the current month. Any real month is accepted — §4 |
| 37 | `month`, `from`, `to`, `granularity` | `month` accepts **only** the current month; `from`/`to` are `yyyy-MM-dd` **within** the current month and default to its first and last days; `granularity` is `DAILY` or `WEEKLY` and defaults to `DAILY` — §5 |

---

## 3. Field reference — `GET /api/v1/reports`

### 3.1 Top level

| Field | Type | Presence | Notes |
|---|---|---|---|
| `periodMonth` | string `yyyy-MM` | always | The month `totals` and the two category blocks describe — §4 |
| `currency` | string | always | The account's own `users.currency`, not the `app.currency` setting — §3.5 |
| `totals` | object | always | §3.2. Its own figures may all be absent |
| `expenseByCategory` | array | always, possibly `[]` | §3.3, largest total first |
| `incomeByCategory` | array | always, possibly `[]` | §3.3, same shape |
| `sixMonthTrend` | array | always, exactly six points | §3.4, oldest first |

Every amount is `DECIMAL(15,2)` and serialises with that scale — `189.00`, not `189`.

### 3.2 `totals`

| Field | Type | Presence | Notes |
|---|---|---|---|
| `income` | decimal | **absent** when the month holds no records | The month's income, from `v_monthly_income_expense` |
| `expense` | decimal | **absent** likewise | The month's spending. Positive — see below |
| `net` | decimal | **absent** likewise | `income - expense`. **May be negative** |
| `transactionCount` | integer | **absent** likewise | How many live records the month's figures came from |

**All four are absent together, or all four present.** They come from one view row, and that view emits
no row for a month with no records — so the block is an empty object rather than four zeroes. See §6
for why that distinction is kept rather than smoothed over.

**`expense` is positive.** `ck_txn_amount` requires `amount > 0`, so a record's direction is its
category's type (BR-05) and never a sign on the number. `expense` is a magnitude, and a client that
subtracts it should subtract it, not add it.

Trashed records are excluded from every figure here (BR-09), by the views.

### 3.3 A category slice

| Field | Type | Presence | Notes |
|---|---|---|---|
| `categoryId` | integer | always | For linking to that category's transactions |
| `categoryName` | string | always | `v_category_month_totals`' own column |
| `categoryIcon` | string | absent when the category has none | An icon name for the client to resolve |
| `categoryColor` | string | absent when the category has none | Hex, e.g. `#F97316` |
| `type` | string | always | `INCOME` or `EXPENSE`. The block already says which, but the field lets one array shape serve both |
| `total` | decimal | always | That category's total for the month |
| `percentage` | decimal | always | That category's own share of the block it appears in, to two decimals — §7 |
| `transactionCount` | integer | always | How many live records make up `total` |

`categoryIcon` and `categoryColor` are absent rather than substituted with a default when a category
has none, so a client chooses its own placeholder and the response does not claim the database holds a
value it does not. The two columns come from a join to `categories`; the view itself publishes neither.

The slices are ordered **largest total first**, with `categoryId` as a tie-break, so two equally-sized
categories keep a stable order between two identical calls.

### 3.4 A trend point

| Field | Type | Presence | Notes |
|---|---|---|---|
| `periodMonth` | string `yyyy-MM` | always | The point's own month, so the six cannot be mislabelled by `periodMonth` above |
| `income` | decimal | always | `0.00` for a month with no records |
| `expense` | decimal | always | `0.00` likewise |
| `net` | decimal | always | `income - expense` |

Unlike `totals`, a trend point's figures are **always present**, because BR-17 requires all six months
and the view behind it zero-fills through `dim_month`. §6.

### 3.5 `currency` is the account's, not the system default

It is read from `users.currency`, not from the `app.currency` setting. The two answer different
questions: the setting is what a **new** account starts with (VĐ-08), while the column is what **this**
account's figures are denominated in. A student whose currency was changed after registration must see
their own, and it is the same value `GET /api/v1/profile/me` publishes.

---

## 4. The selected month, and the one figure that ignores it

`month` selects the month for `totals` and the two category blocks. **Any real month is accepted,**
including a future one: the two views behind them are keyed by `period_month` and simply return
nothing for a month the student has no records in, which is an honest answer. A month that is not a
real month in `yyyy-MM` form is refused — §8.

**`sixMonthTrend` is the exception, and it is not a bug.** BR-17 defines it as the last six months
ending at the **current** one: a fixed window with no parameter. So the six points are the same six
whatever `month` says. Each point carries its own `periodMonth`, which is what keeps this honest — a
client reading the array cannot mistake a trend point for the selected month:

```json
"periodMonth": "2026-08",
"sixMonthTrend": [
  { "periodMonth": "2026-04", ... }, { "periodMonth": "2026-05", ... },
  { "periodMonth": "2026-06", ... }, { "periodMonth": "2026-07", ... },
  { "periodMonth": "2026-08", ... }, { "periodMonth": "2026-09", ... }
]
```

The alternative — filtering the trend to the selected month — would leave one point, which is not a
trend. Returning it beside the selected month's totals, each labelled, is the arrangement the
requirement describes.

**The month is resolved in `Asia/Ho_Chi_Minh`**, the same offset the database session is pinned to by
Hikari's `connection-init-sql` (VĐ-10). That matters more here than for a default value: whether a
requested month *is* the current one decides whether §5's endpoint answers or refuses. Judging that in
the JVM's default zone would accept a request at 06:00 on the first of a month and answer it from the
previous month, for seven hours in every twenty-four.

**This is not a timezone guarantee.** The session's zone is a deployment setting. What the module
guarantees is that one request is judged against one value.

---

## 5. `GET /api/v1/reports/spending`

```json
{
  "granularity": "DAILY",
  "from": "2026-09-01",
  "to": "2026-09-30",
  "currency": "USD",
  "totalExpense": 189.00,
  "points": [
    { "intervalStart": "2026-09-03", "intervalEnd": "2026-09-03", "totalExpense": 120.00, "transactionCount": 1 },
    { "intervalStart": "2026-09-05", "intervalEnd": "2026-09-05", "totalExpense": 8.00,   "transactionCount": 1 },
    { "intervalStart": "2026-09-07", "intervalEnd": "2026-09-07", "totalExpense": 24.00,  "transactionCount": 1 },
    { "intervalStart": "2026-09-08", "intervalEnd": "2026-09-08", "totalExpense": 12.00,  "transactionCount": 1 },
    { "intervalStart": "2026-09-10", "intervalEnd": "2026-09-10", "totalExpense": 25.00,  "transactionCount": 1 }
  ]
}
```

Same month, `granularity=WEEKLY`:

```json
{
  "granularity": "WEEKLY",
  "from": "2026-09-01",
  "to": "2026-09-30",
  "currency": "USD",
  "totalExpense": 189.00,
  "points": [
    { "intervalStart": "2026-08-31", "intervalEnd": "2026-09-06", "totalExpense": 128.00, "transactionCount": 2 },
    { "intervalStart": "2026-09-07", "intervalEnd": "2026-09-13", "totalExpense": 61.00,  "transactionCount": 3 }
  ]
}
```

| Field | Type | Presence | Notes |
|---|---|---|---|
| `granularity` | string | always | `DAILY` or `WEEKLY` — what the client asked for, echoed so the chart can label its axis |
| `from` | string `yyyy-MM-dd` | always | The first day the read covered |
| `to` | string `yyyy-MM-dd` | always | The last day the read covered |
| `currency` | string | always | The account's |
| `totalExpense` | decimal | always | The sum of the points. `0.00` when nothing was spent |
| `points` | array | always, possibly `[]` | Oldest interval first |

### 5.1 This is the one report that cannot be asked about another month

Both views behind it derive their range from `CURDATE()` inside the database session. Neither takes a
month parameter. A request naming any month but the current one, or a `from`/`to` outside the current
month, is **refused** with `400 VALIDATION_ERROR` and a field error naming the offending parameter —
not quietly narrowed to the current month. §8 has the exact responses.

The alternative was to accept the value and answer from the current month anyway. That would put one
month's bars under another month's heading with no field revealing it, every number individually
correct — the failure mode the dashboard's absent `?month=` avoids by having no parameter at all.
Here the parameter is genuinely useful, so the honest answer is to refuse what cannot be honoured.

**Naming the current month explicitly is accepted.** `month=2026-09` when September is current returns
the same response as omitting it.

### 5.2 A weekly bar is a real ISO week, and its dates may leave the month

`intervalStart` is the week's Monday and `intervalEnd` the following Sunday, computed by the view from
`YEARWEEK(txn_date, 3)` — so a month boundary never splits an ISO week (VĐ-10). In the example above
the first bar begins on **31 August**, a day of the previous month.

**What the bar counts, however, is only the current month's records.** The view filters `txn_date` to
the month before grouping, exactly as the daily view does, and groups the identical set of rows — only
the grouping differs. So:

- over the **whole month** the two granularities report the same `totalExpense` (both `189.00` above);
- a bar's **dates** can extend outside the month while its **amount** cannot include a day of spending
  from outside it.

This is worth stating because it is easy to assume otherwise from the bar's width alone.

### 5.3 What a narrowed window does, and the one case where the two granularities differ

`from`/`to` narrow the window within the month and are completed rather than required: giving `from`
alone means "from then to the end of the month", giving neither means the whole month.

For `DAILY` the window is a straightforward restriction — a point is a day, so a day outside the
window is dropped.

For `WEEKLY` the predicate is an **overlap**, not a containment: a bar is returned when it touches the
window at either end (`week_start <= to AND week_end >= from`), with **all** of its own week's
spending. A containment test would drop a bar whose Monday falls in the previous month even though the
bar carries spending from the requested window.

The visible consequence, over the same narrow window:

```bash
curl -H "Authorization: Bearer ${JWT}" \
  "http://localhost:8080/api/v1/reports/spending?from=2026-09-08&to=2026-09-10"
```

```json
{ "granularity": "DAILY", "from": "2026-09-08", "to": "2026-09-10", "currency": "USD",
  "totalExpense": 37.00,
  "points": [
    { "intervalStart": "2026-09-08", "intervalEnd": "2026-09-08", "totalExpense": 12.00, "transactionCount": 1 },
    { "intervalStart": "2026-09-10", "intervalEnd": "2026-09-10", "totalExpense": 25.00, "transactionCount": 1 }
  ] }
```

```bash
curl -H "Authorization: Bearer ${JWT}" \
  "http://localhost:8080/api/v1/reports/spending?granularity=WEEKLY&from=2026-09-08&to=2026-09-10"
```

```json
{ "granularity": "WEEKLY", "from": "2026-09-08", "to": "2026-09-10", "currency": "USD",
  "totalExpense": 61.00,
  "points": [
    { "intervalStart": "2026-09-07", "intervalEnd": "2026-09-13", "totalExpense": 61.00, "transactionCount": 3 }
  ] }
```

`61.00` against `37.00`, because the ISO week that contains 8–10 September also contains the 7th and
the 11th–13th. **Neither total can exceed the month's expense**, because both views filter to the
month.

A client that wants the two granularities to agree must ask for the whole month, which is their
default when `from` and `to` are omitted.

### 5.4 `from`/`to` are published even though empty intervals are not returned

The view returns one row per interval that **has** spending, so a day on which nothing was spent is a
missing row rather than a zero. The response therefore publishes the window it covered, so a chart
knows where its axis starts and ends without inferring it from the array's length — a month whose
first days were quiet returns no point for them, and the axis must still begin at the first.

Do not draw the x-axis from `points.length`. Draw it from `from` to `to`. §6.

---

## 6. Absent versus zero

The two endpoints use absence and zero deliberately, and **differently**, and the difference comes from
the schema rather than from preference:

| Value | An empty month produces | Because |
|---|---|---|
| `totals.income` / `.expense` / `.net` / `.transactionCount` | **absent** (`"totals": {}`) | `v_monthly_income_expense` emits one row per month that **has** data. No row means no such month — not a month whose figures were summed to zero |
| a `sixMonthTrend` point's `income` / `expense` / `net` | **`0.00`**, point always present | `v_monthly_income_expense_6m` joins `dim_month`, so BR-17 and UAT-09 are satisfied: six points always, empty months as zero |
| a `points[]` entry for a quiet day or week | **absent**, no entry | Neither spending view zero-fills. The window is published instead — §5.4 |

An empty month, in full:

```json
{
  "periodMonth": "2026-05",
  "currency": "USD",
  "totals": {},
  "expenseByCategory": [],
  "incomeByCategory": [],
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

**"Recorded nothing" and "recorded activity that netted to nothing" are different claims**, and only
the first is true of May above. A client should render `"totals": {}` as "no records for this month",
not as `$0.00` — a student reconciling their records against their memory is exactly the reader for
whom that distinction matters.

The schema decides which report fills its gaps. This module does not overrule it, and does not
zero-fill one and not the other by accident: it passes through what each view returned.

---

## 7. `percentage` does not sum to 100

`percentage` is the one figure the API computes, because no view computes a share of a month. It is
that category's **own** share of the block it appears in — the sum of the totals in the same array is
the denominator — rounded to two decimals with `HALF_UP`, the mode MySQL's `ROUND` uses.

**Each share is rounded independently, so a block's shares generally do not total exactly 100.** Three
categories of `10.00` in a block of `30.00` each read `33.33`, and `33.33 × 3` is `99.99`. August's
expense block in the seeded data is a real example:

| Category | `total` | `percentage` |
|---|---|---|
| Hostel/Rent | `120.00` | `57.97` |
| Academics | `30.00` | `14.49` |
| Food | `22.00` | `10.63` |
| Transport | `16.00` | `7.73` |
| Entertainment | `11.00` | `5.31` |
| Subscriptions | `8.00` | `3.86` |
| **Sum** | **`207.00`** | **`99.99`** |

**No slice is adjusted to absorb the remainder.** The common alternative — give the difference to the
largest slice so a pie chart closes — would publish one category's share as a number that is not that
category's share, and a report is the wrong place for a figure that is deliberately wrong. The
shortfall is bounded instead: each rounded share is within half a unit of the last decimal place of
the true share, so no individual figure is misleading.

**A client drawing a pie should size the arcs from `total`,** which is exact, and may use `percentage`
for a legend. Do not assert that a block sums to 100.

`percentage` is `0.00` when the block total is zero, which a real response cannot produce — a category
appears only because it has records, and each record's amount is positive — but which keeps the
arithmetic total rather than relying on that, since a `BigDecimal` division by zero is an exception
rather than a wrong number.

---

## 8. Status codes

| Status | `errorCode` | When |
|---|---|---|
| `200` | — | The report or series |
| `400` | `VALIDATION_ERROR` | A month that is not `yyyy-MM` or is not a real month; a `from`/`to` that is not a date; **a spending `month` other than the current one**; a `from`/`to` outside the current month; `from` after `to`; a `granularity` that is not `DAILY` or `WEEKLY` |
| `401` | `UNAUTHENTICATED` | No token, or one that is malformed, expired or revoked |
| `403` | `ACCESS_DENIED` | A token belonging to an account that is not a student |
| `500` | `INTERNAL_ERROR` | Unexpected. Carries no SQL, stack trace or credential |

Every `400` carries `fieldErrors` naming the parameter that caused it. The failures a reports screen
is most likely to hit, verbatim:

```bash
curl -H "Authorization: Bearer ${JWT}" "http://localhost:8080/api/v1/reports?month=2026-13"
```

```bash
curl -H "Authorization: Bearer ${JWT}" "http://localhost:8080/api/v1/reports?month=2026-13"
```

```json
{
  "timestamp": "2026-09-25T09:09:35.767164Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "The month is not a valid month.",
  "path": "/api/v1/reports",
  "fieldErrors": [
    { "field": "month", "message": "Enter a real month in yyyy-MM form, for example 2026-09." }
  ]
}
```

The spending endpoint's own refusal, for a month it cannot honour (§5.1):

```bash
curl -H "Authorization: Bearer ${JWT}" "http://localhost:8080/api/v1/reports/spending?month=2026-08"
```

```json
{
  "timestamp": "2026-09-25T09:09:35.784083Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "A breakdown by day or week is only available for the current month.",
  "path": "/api/v1/reports/spending",
  "fieldErrors": [
    { "field": "month",
      "message": "The daily and weekly breakdowns are computed for the current month only. Omit the month to use the current one, or ask for a whole month's totals instead." }
  ]
}
```

A window edge outside the month is refused with the same message but the edge named, so a client can
tell which of `from`/`to` it got wrong:

```bash
curl -H "Authorization: Bearer ${JWT}" "http://localhost:8080/api/v1/reports/spending?from=2026-08-01"
```

```json
{
  "timestamp": "2026-09-25T09:09:42.498768Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "A breakdown by day or week is only available for the current month.",
  "path": "/api/v1/reports/spending",
  "fieldErrors": [
    { "field": "from",
      "message": "The daily and weekly breakdowns are computed for the current month only, so this date is outside the range that can be reported." }
  ]
}
```

Two more, for the errors a hand-written URL is most likely to produce:

```json
{ "status": 400, "errorCode": "VALIDATION_ERROR",
  "message": "The granularity is not a supported value.",
  "path": "/api/v1/reports/spending",
  "fieldErrors": [ { "field": "granularity", "message": "Choose DAILY or WEEKLY." } ] }
```

```json
{ "status": 400, "errorCode": "VALIDATION_ERROR",
  "message": "The start of the window is after its end.",
  "path": "/api/v1/reports/spending",
  "fieldErrors": [ { "field": "from", "message": "The start of the window must not be later than its end." } ] }
```

**There is no `404`.** Neither endpoint takes an identifier, so there is nothing to not find. The
service's `NotFoundException` exists only for the case where a valid token's account cannot be read,
which no valid token should produce.

---

## 9. Security properties

| Property | How |
|---|---|
| **Identity is the token** | Both methods take `@AuthenticationPrincipal`. No endpoint accepts a user id — §1 |
| **Ownership is structural, not checked** | Every view query binds the token's `user_id` as a predicate. There is no code path that reads a report and then verifies whose it is, because there is no way to ask for another's (BR-02) |
| **Role is enforced before the controller** | `/api/v1/reports/**` → `hasRole("STUDENT")` in `SecurityConfig`. An administrator's token is refused with `403` and never reaches the service |
| **A report never names its owner** | `userId` is not mapped on any DTO. The response says what was spent, not whose |
| **Nothing is written** | Both methods are `@Transactional(readOnly = true)`. There is no POST, PATCH, PUT or DELETE on either path, no view-recording and no caching behind a GET |
| **No parameter can name another student** | There is no `userId`, no email, no session id. The parameters are a month, two dates and a granularity |
| **Invalid input is refused, not coerced** | A malformed month is `400`, never parsed loosely into a different one — `2026-9`, `202609` and `September` are all refused |
| **Amounts are scoped to one string** | `currency` is the account's own, so a figure cannot be read as being in a currency it is not |

**Why an administrator is refused here.** UC-21's administrative reports are sums over many students
and live under `/api/v1/admin/**`. Admitting the role here would expose exactly the per-student detail
those reports are built not to name: one response carries a named student's income, spending and
category-by-category habits.

---

## 10. Angular integration notes

**The current `ReportsComponent` is entirely mock and does not call this API.**
`frontend/src/app/features/reports/reports.component.ts` computes every figure client-side from
`MOCK_TRANSACTIONS` through `TransactionService`, whose `useMockData` path serves everything from an
in-memory array. Nothing in the frontend source is changed by this module; this section records what a
rewiring would have to reconcile.

### 10.1 Which call replaces which computation

| Screen element today | Today's source | The endpoint to call |
|---|---|---|
| KPI cards (expense, income, net, savings rate) | `getMonthlyBalance(period)` | `GET /api/v1/reports?month=` → `totals` |
| Category donut | `getCategoryBreakdown(period)` | same → `expenseByCategory` |
| Six-month bar chart | `get6MonthTrend()` | same → `sixMonthTrend` |
| Daily spend line | `getDailySpending('2026-09')` | `GET /api/v1/reports/spending?granularity=DAILY` |
| "Export Report (PDF / Print)" | `exportReport() { window.print(); }` | **No call.** `window.print()` is already BR-18-compliant: nothing buffered, generated when asked — §12 |

### 10.2 Divergences to reconcile

| # | The mock does | The contract does | Consequence |
|---|---|---|---|
| 1 | `percentage: Math.round((item.total / totalExpense) * 100)` — a whole number | Two decimals, `HALF_UP` | A legend shown as `13%` becomes `13.23`. The donut should size arcs from `total`, not `percentage` — §7 |
| 2 | `monthlyBalance.savingsRate` — a client-computed field | No such field. The nearest figure is `v_dashboard_summary.savings_goal_pct`, on `GET /api/v1/dashboard`, which is `null` unless a goal is set | The savings-rate KPI has no source in this response. Either read it from the dashboard, or drop it from the report screen |
| 3 | `getMonthlyBalance` returns `0` for an empty month | `totals` is `{}` — every figure absent | A `0` KPI card becomes blank/"no records". §6 |
| 4 | `MonthlyTrendItem.periodCode` + `monthLabel` + rounded figures | `periodMonth` only; figures are `DECIMAL(15,2)`, never rounded | The label must be derived client-side from `periodMonth` |
| 5 | Hard-coded `selectedPeriod = '2026-09'` and a hard-coded six-month list inside `get6MonthTrend()` | Any month via `?month=`; the trend is always the current six — §4 | The period selector can become real. The trend array must not be filtered by the selected month |
| 6 | `getDailySpending('2026-09')` — a month argument | The spending series accepts **only** the current month — §5.1 | A month selector must not be wired to the daily chart. Either grey it out away from the current month, or handle the `400` |
| 7 | `CategoryBreakdownItem.categoryId: string` | Integer | Type change |
| 8 | `getCategoryBreakdown` filters `type === 'EXPENSE'` only | The response carries `type` on every slice, and two separate blocks | Income categories now have a home; a client can render one donut per block |
| 9 | The export button suggests "PDF" | Nothing on the server produces a PDF | Keeping `window.print()` is correct; the label should not promise a server-side file — §12 |
| 10 | No daily-spend window is shown | `from`/`to` are published and empty days are absent | Draw the axis from `from`/`to`; do not infer it from `points.length` — §5.4 |

### 10.3 Handling the failures

| Response | What the client should do |
|---|---|
| `401` | The token is gone or revoked — return to sign-in |
| `403` | The session is not a student — the sign-in screen, not a retry |
| `400` on `/reports` | `fieldErrors[0].field` is `month` — keep the selector's previous value and show the message |
| `400` on `/reports/spending` | The requested month or window is not the current one. Ask for the current month, or show the totals report instead — do not retry unchanged |
| — | **No call should be made for the export button.** §12 |

---

## 11. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-15 | The month's totals | `GET /reports` | `getReport` | `getReport` | `v_monthly_income_expense` | `totalsCountIncomeAndExpenseByCategoryType` |
| UC-15 | Every category's total for the month | same | — | `findCategoryTotals` | `v_category_month_totals` + the icon/colour join | `categorySlicesCarryThePresentationColumnsAndTheirShare` |
| UC-15 | The month's spending by day | `GET /reports/spending` | `getSpendingSeries` | `findDailySpending` | `v_daily_spending_current_month` | `dailyPointsCoverOnlyTheDaysWithSpending` |
| UC-15 | …and by ISO week | same | — | `findWeeklySpending` | `v_weekly_spending_current_month` | `weeklyPointsAreRealIsoWeeks` |
| UC-15 | A category's share of the month | `GET /reports` | — | `ReportMapper` | — (computed) | `categorySharesAreEachTheirOwnAndAreRoundedIndependently` |
| BR-17, UAT-09 | Six months always, empty ones as `0.00`, ending at the current month | same | — | `findSixMonthTrend` | `v_monthly_income_expense_6m` + `dim_month` | `theTrendAlwaysHasSixMonthsOldestFirst`, `emptyMonthsInTheTrendAreZeroNotMissing`, `theTrendIsUnaffectedByTheSelectedMonth` |
| BR-05 | The category's type decides income vs expense | same | — | the two blocks | the view's `CASE WHEN c.type = ...` | `categoriesAreSplitByTheirType`, `incomeIsNotPartOfTheSpendingSeries` |
| BR-09 | Trashed records stop counting | both | — | — | the views' `is_deleted = 0` | `deletedTransactionsAreExcludedFromTheTotals`, `deletedRecordsLeaveTheDailySeries` |
| BR-18 | The file is generated when asked, nothing buffered | — | — | — | — | §12 — no route exists, and `window.print()` already satisfies it |
| VĐ-10 | A month boundary never splits an ISO week | `/reports/spending` | — | — | `YEARWEEK(t.txn_date, 3)`, `WEEKDAY()` | `weeklyPointsAreRealIsoWeeks`, `aWeekMayReachOutsideTheMonthAndIsNotClipped` |
| BR-02 | Ownership | both | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `aReportContainsOnlyTheCallersRecords`, `noParameterCanNameAnotherStudent`, `theSpendingSeriesIsScopedToTheCaller` |
| BR-02 | No response names the owner | both | — | `ReportMapper` | — | `theResponseCarriesExactlyTheDocumentedFields` |
| §7.5 | Student-only, administrator refused | both | — | — | — | `aReportRequiresAStudentToken`, `theSpendingSeriesRequiresAStudentToken` |
| §26 | Both endpoints are in the inventory and the document | both | — | — | — | `OpenApiContractIT#documentMatchesTheInventory`, `#noEndpointIsDuplicated`, `#reportSchemasMatchTheDocumentedContract` |
| UC-15 | Reading writes nothing | both | — | `readOnly = true` | — | `readingAReportChangesNoState` |

---

## 12. Why there is no export endpoint

**UC-16 has no route, and that is a decision rather than an omission.**

BR-18 requires that an export file be produced **only when the user asks**, and that nothing be
buffered — "the file is generated at call time". That is precisely what `GET /api/v1/reports` and
`GET /api/v1/reports/spending` already do. They are computed on each call from the views, they buffer
nothing, and either may be called as many times as a client likes without a stored file accumulating.

An `/api/v1/reports/export` route returning these same figures as CSV or PDF would therefore be a
**second way to ask one question** — section 13's forbidden duplicate. Its payload would be one of
these two responses, formatted differently, and its figures could only come from the same views. Two
routes to one set of figures is how the two eventually disagree.

**What the frontend already does is correct.** `ReportsComponent.exportReport()` calls
`window.print()`. That is BR-18 satisfied on the client: nothing buffered, generated when the user
presses the button, from the figures the screen already fetched. No backend call is involved and none
is needed.

**What is deliberately not built, with the reason:**

| Not built | Why |
|---|---|
| `GET /api/v1/reports/export` | A second route to the two reads above |
| `?format=csv` / `?format=pdf` on either existing endpoint | The same second-question problem inside one path. A format parameter would also mean the server choosing a file format, which no requirement asks of it |
| An export history or a stored-file table | BR-18 says explicitly there is no buffer table. Records of exports would need a table the schema does not have |
| A server-generated PDF | No library for it is in the stack, and `window.print()` already produces one from the screen |
| A pre-signed download URL | Nothing is stored to download |

If a future requirement asks for a server-rendered file, it will need its own use case, its own
requirement text, and its own justification for why the same figures need a second route. BR-18 as
written does not.

---

## Related documentation

- [`API_INVENTORY.md`](API_INVENTORY.md) — endpoints 36–37, and the decisions recorded with them
- [`dashboard.md`](dashboard.md) — the other read of `v_monthly_income_expense` and
  `v_category_month_totals`, and why its month is not selectable where this one's is
- [`transactions.md`](transactions.md) — where the records behind every figure here are written
- [`budgets.md`](budgets.md) — `v_budget_consumption`, the other view with a `consumed_pct`
  convention this module's `percentage` follows
- [`../modules/MODULE_08_REPORTS.md`](../modules/MODULE_08_REPORTS.md) — the module report,
  including §4 on UC-16
- [`../SECURITY.md`](../SECURITY.md) — the security decisions behind these endpoints
