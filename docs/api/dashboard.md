# Dashboard API

**Endpoint 35.** `GET /api/v1/dashboard` — the student's home screen in one response: the current
month's totals and saving-goal progress (UC-12 B1), the highest-spending expense category (UC-12 B2),
the saving tips to show and the live announcements (UC-12 B3).

**This module adds one endpoint, and the interesting part of it is what is *not* in it.** The screen
has four blocks; three of the four are read-only views onto capabilities other modules already own
(budgets, notifications, tips, announcements). What this endpoint adds is a month-consistent
composition of them, and the filters the schema leaves to the application. §4, §8 and §9 are the
parts worth reading before changing anything here.

---

## Contents

| § | |
|---|---|
| 1 | [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent) |
| 2 | [Endpoints at a glance](#2-endpoints-at-a-glance) |
| 3 | [Field reference](#3-field-reference) |
| 4 | [`periodMonth` is the month the database thinks it is](#4-periodmonth-is-the-month-the-database-thinks-it-is) |
| 5 | [The four views, and the two filters the application adds](#5-the-four-views-and-the-two-filters-the-application-adds) |
| 6 | [`GET /api/v1/dashboard`](#6-get-apiv1dashboard) |
| 7 | [Status codes](#7-status-codes) |
| 8 | [What is not on this screen, and where it lives](#8-what-is-not-on-this-screen-and-where-it-lives) |
| 9 | [Security properties](#9-security-properties) |
| 10 | [Angular integration notes](#10-angular-integration-notes) |
| 11 | [Traceability](#11-traceability) |

---

## 1. Scope and what is deliberately absent

| Use case | Behaviour |
|---|---|
| UC-12 B1 | The month's income, spending and net figure, and progress against the saving goal |
| UC-12 B2 | The expense category the most was spent on this month |
| UC-12 B3 | The saving tips to show, and the live system announcements |
| UC-12 BA | Every figure is the schema's: a dashboard that recomputed a total would eventually disagree with the reports screen reading the same views |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` parameter | The account is the bearer token's. This is the most personal read in the API — one response carries a named student's income, spending, habits and personal advice — so there is no identifier to tamper with (BR-02) |
| A `?month=` parameter | §4 — the views that produce the totals and the top category derive their month from the database's own clock and cannot answer about another month. Accepting the parameter would return the current month's figures under the requested month's heading |
| Budget progress bars (UC-13) | `GET /api/v1/budgets` already returns every limit with its consumption, computed by `v_budget_consumption`. Publishing them again here would be a second route to the same rows, read twice in one request, and a second place for the thresholds to be applied |
| Notifications (UC-14) | `GET /api/v1/notifications` is the list, and it is stateful in a way a dashboard is not: a message is read or unread, and marking one read is a write. Composing the two would put a write behind this GET |
| Marking a notification read, pinning or dismissing a tip | Each is a state change owned by the module whose use case describes it. A dashboard render must not cause one |
| Raise, clear or recompute anything | Nothing here writes. A GET that wrote would be a GET that needs a lock |
| `/api/v1/dashboard/{id}` | A dashboard belongs to an account, not to a record. There is no identifier for a caller to name |
| `/api/v1/dashboard/summary`, `/top-category`, `/tips`, `/announcements` | Four routes for four blocks of one screen. Each block describes the same month of the same student, so splitting them would make the screen's first paint wait on four round-trips and would let a client paint one month's totals beside another month's tips if the clock crossed a boundary between two calls |
| `PUT`, `POST`, `PATCH`, `DELETE` on this path | UC-12 is a list of things to show. Every write it displays is reachable through the endpoint that owns it |
| Insights (UC-17) and the tip actions (UC-18) | Later modules. UC-12 B3 shows tips; generating, pinning and dismissing them is UC-18, and `state` is published here so the screen can render a pinned tip differently without owning the transition |
| Announcement administration (UC-21) | `announcements` is written by module 11. This endpoint reads one audience's slice of it and has no counterpart that writes |
| Real-time updates (websocket/SSE) | Not a stated requirement, and no transport is configured. `startsAt`/`endsAt` are published so a client can expire a banner on its own clock |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 35 | `GET` | `/api/v1/dashboard` | UC-12 | Bearer token, role `STUDENT` | `200` dashboard |

**One endpoint, one path, no query parameters.** An administrator token is `403` (§9).

---

## 3. Field reference

### 3.1 Top level

| Field | Type | Presence | Notes |
|---|---|---|---|
| `periodMonth` | string `yyyy-MM` | always | The month every block below describes. See §4 |
| `summary` | object | always | §3.2 |
| `topCategory` | object | **absent** when nothing was spent this month | §3.3 |
| `tips` | array | always, possibly `[]` | §3.4 |
| `announcements` | array | always, possibly `[]` | §3.5 |

`topCategory` is omitted rather than sent as `null` or as a zero row. `tips` and `announcements` are
always present, empty when there is nothing to show: an empty array is an answer ("there is nothing
here"), whereas an absent one would be indistinguishable from a field a client forgot to read.

### 3.2 `summary`

| Field | Type | Presence | Notes |
|---|---|---|---|
| `currency` | string | always | The account's currency, `CHAR(3)`. Seeded accounts are `USD` |
| `totalIncome` | decimal | always | `0.00` when nothing was recorded |
| `totalExpense` | decimal | always | `0.00` when nothing was recorded |
| `netAmount` | decimal | always | `totalIncome - totalExpense`. **May be negative** |
| `monthlyAllowanceBaseline` | decimal | always | The reference figure the student recorded. `0.00` when none |
| `monthlySavingsGoal` | decimal | always | `0.00` when none |
| `savingsGoalPct` | decimal | **absent** when no goal is set | May be negative |

Amounts are `DECIMAL(15,2)` and serialise with that scale — `24.00`, not `24`. A client should format
them, not parse them out of a string.

**`savingsGoalPct` absent is not `savingsGoalPct: 0`.** The view computes it only when
`monthly_savings_goal > 0`. A goal of zero has no progress to report, whereas a student who set a goal
and then spent past their income has genuinely negative progress — and that is reported, not clamped:

| Goal | Net | `savingsGoalPct` |
|---|---|---|
| `0.00` | `200.00` | *absent* |
| `100.00` | `200.00` | `200.00` |
| `100.00` | `-40.00` | `-40.00` |

### 3.3 `topCategory`

| Field | Type | Presence | Notes |
|---|---|---|---|
| `categoryId` | integer | always | For linking to that category's transactions |
| `categoryName` | string | always | |
| `categoryIcon` | string | absent when the category has none | An icon name for the client to resolve |
| `categoryColor` | string | absent when the category has none | Hex, e.g. `#F97316` |
| `totalAmount` | decimal | always | The month's total for that category |

Only **expense** categories are ranked, so a month with income and no spending has no `topCategory`
— income cannot become the "highest-spending" category.

`categoryIcon` and `categoryColor` are joined from `categories`, because
`v_top_category_current_month` does not publish them (§5). The amount itself is the view's.

**Ties.** The view's `ROW_NUMBER()` orders by `total_amount DESC` with no secondary key, so when two
expense categories have the same total, which one is named is the database's decision and is not
guaranteed to be stable between calls. A client should not depend on which of two equal totals wins,
and this module does not re-rank to impose an order the schema did not choose.

### 3.4 `tips[]`

| Field | Type | Presence | Notes |
|---|---|---|---|
| `id` | integer | always | For the pin/dismiss actions in UC-18 |
| `categoryId` | integer | **absent** for a tip about no single category | e.g. the savings-goal tip |
| `title` | string | always | Stored prose, rendered by the database from a template |
| `body` | string | always | Stored prose |
| `potentialSaving` | decimal | always | `0.00` for advice with no figure attached |
| `state` | `NEW` \| `PINNED` | always | `DISMISSED` never appears |

**The array order is the contract.** It arrives already ranked — pinned first, then by how much each
could save (BR-14) — and a client renders it as it arrives. Re-sorting by `potentialSaving` client-side
would disagree with what the database ranks by, and the tip the student pinned would drift down the
page.

**`DISMISSED` is not a value a client must handle.** `v_dashboard_tips` excludes it, so a dismissed tip
cannot appear here at all. `state` has exactly two members on this endpoint.

`title` and `body` are rendered by `fn_render_template` from `tip_templates`, in English. The amounts
and percentages inside them are the same figures every other part of the dashboard reports. Render
them as text; do not parse the numbers out.

### 3.5 `announcements[]`

| Field | Type | Presence | Notes |
|---|---|---|---|
| `id` | integer | always | |
| `title` | string | always | |
| `body` | string | always | |
| `severity` | `INFO` \| `WARNING` \| `SUCCESS` | always | How prominently to show it |
| `startsAt` | datetime | always | |
| `endsAt` | datetime | **absent** for an open-ended announcement | |

Every row is active, inside its window, and addressed to students or to everyone. **`audience` is not
published** — after the filter it could only have been `ALL` or `STUDENTS`, and nothing about rendering
a banner depends on which. Ordering is newest first.

---

## 4. `periodMonth` is the month the database thinks it is

`v_dashboard_summary` and `v_top_category_current_month` both derive their month from `CURDATE()`
**inside the database session**, which is pinned to `+07:00` (`VĐ-10`). Neither view can be asked
about any other month.

This has two consequences the caller can see:

1. **There is no `?month=` parameter.** A caller sending `month=2020-01` is not refused; the parameter
   is ignored and the current month is returned, with `periodMonth` stating which month that was. The
   alternative — accepting the parameter and returning the current month's figures under the requested
   heading — would be silently wrong, which is worse than being ignored. `theMonthIsNotSelectable`
   asserts this.
2. **`periodMonth` is read back out of the summary row rather than computed in Java.** The tips view
   has no time filter at all (§5), so the month in this response is the value the tips query was
   scoped by. If Java had computed "the current month" from its own clock, a request landing across a
   month boundary could report September's totals beside October's tips — and every individual field
   would still be correct, which is what would make it hard to notice.

**This is not a timezone guarantee.** It says the month is one value, taken from one place, so that the
four blocks agree. The database session's zone is a deployment setting (`VĐ-10`); what is guaranteed
here is that a single request does not mix two months.

A month-selectable view is a report, which is UC-15 and module 8, reading different views.

---

## 5. The four views, and the two filters the application adds

| Block | View | Who owns the numbers |
|---|---|---|
| `summary` | `v_dashboard_summary` | The view: the income/expense split by category type (BR-05), the `is_deleted = 0` predicate (BR-09), the net subtraction, and the goal percentage's `CASE` |
| `topCategory` | `v_top_category_current_month` | The view: it restricts itself to the current month, to `EXPENSE`, and to `rn = 1` |
| `tips` | `v_dashboard_tips` | The view: `state <> 'DISMISSED'` and the pinned-first ranking (BR-14) |
| `announcements` | `v_active_announcements` | The view: `is_active = 1` and the `starts_at`/`ends_at` window |

The service reads all four and computes none of them. Two predicates, however, are **not** in the
views, and the application applies both. They are the reason this module is not a pure pass-through.

### 5.1 The tips view has no time filter

`v_dashboard_tips` partitions by `period_month` for its `ROW_NUMBER` but returns **every** month a
student has tips for. That is harmless for the view, which is also UC-18's source for the tips screen,
but it is wrong for a dashboard: a dashboard shows one month's tips, and without a predicate a student
would see January's tip under September's heading. `DashboardViewDao.SELECT_TIPS` adds
`AND v.period_month = :periodMonth`, bound to the month the summary reported. Asserted by
`tipsFromAnotherMonthAreNotShown`, which creates a tip for last month and requires it not to appear.

### 5.2 The announcements view has no audience filter

`v_active_announcements` answers *"is this notice within its window"*, not *"is this notice for this
reader"*. `announcements.audience` has three values — `ALL`, `STUDENTS`, `ADMINS` — and the view
filters on none of them. A student's dashboard admits the first two and refuses the third:

```sql
WHERE a.audience IN ('ALL', 'STUDENTS')
```

Without it, a notice written for administrators would appear on every student's dashboard. That is a
**disclosure**, not a cosmetic slip. Asserted by `announcementsAreFilteredByAudience`, which inserts one
of each audience and requires the `ADMINS` one to be absent.

An administrator is refused this endpoint entirely (§9), so there is no mirror-image case to handle.

### 5.3 The top-category view publishes no icon or colour

`v_top_category_current_month` selects `user_id, period_month, category_id, category_name,
total_amount`. It does not carry `icon` or `color`, both of which the API publishes, so the DAO joins
`categories` on the row's own `category_id`:

```sql
JOIN categories c ON c.id = v.category_id
```

One row in, one row out — `category_id` is the join key and is unique in `categories`. This is the
same gap module 6's `BudgetConsumptionDao` fills for `v_budget_consumption`, and for the same reason:
the view answers "how much", and the icon and colour are presentation.

### 5.4 The tips order is restated, and that is not a second ranking

SQL makes no promise that a view's `ROW_NUMBER()` ordering survives into the selecting statement's
result order, so `SELECT_TIPS` names the same keys the view ranks by:

```sql
ORDER BY (v.state = 'PINNED') DESC, v.display_order ASC, v.tip_id ASC
```

This is not a second definition of the ranking — it asks for the order the view already computes. The
`tip_id` tie-break is added so two equally-scored tips cannot swap places between two calls of the same
endpoint. `tipsAreTheCurrentMonthsAndAlreadyRanked` compares the response against
`v_dashboard_tips.display_order` rather than restating the rule, so the test asserts that the endpoint
reports the view's ranking instead of inventing one.

**Ties in the view itself are a different matter.** `v_dashboard_tips` ranks on
`(state = 'PINNED') DESC, rank_score DESC` with no third key, so two tips with equal scores have no
defined relative order at the view level. The DAO's added `tip_id` key settles it deterministically
for this endpoint.

---

## 6. `GET /api/v1/dashboard`

### Request

```
GET /api/v1/dashboard
Authorization: Bearer <student access token>
```

No parameters, no body. A `?month=` sent anyway is ignored (§4).

### Response `200 OK`

The seeded demo account (Alex Nguyen, `db/06_demo.sql`), whose current month holds income `200.00 +
60.00`, spending `120.00 + 8.00 + 24.00 + 12.00 + 25.00`, five limits and three generated tips:

```json
{
  "periodMonth": "2026-09",
  "summary": {
    "currency": "USD",
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
    "categoryIcon": "home",
    "categoryColor": "#EF4444",
    "totalAmount": 120.00
  },
  "tips": [
    {
      "id": 7,
      "title": "Your savings goal is at risk",
      "body": "Your income minus spending is currently 71.00, below your goal of 100.00. Income this month is 260.00. Consider cutting one non-essential expense to get back on target.",
      "potentialSaving": 29.00,
      "state": "NEW"
    },
    {
      "id": 8,
      "categoryId": 11,
      "title": "Entertainment spending is up 127.3%",
      "body": "This month you spent 25.00 on Entertainment, against your usual 11.00. A rise of 127.3% is worth a look - try setting a weekly cap for this category.",
      "potentialSaving": 14.00,
      "state": "NEW"
    },
    {
      "id": 9,
      "categoryId": 6,
      "title": "Food has used 80.0% of its budget",
      "body": "You have 30.00 left minus what you have already spent (24.00) on Food. Set a weekly cap so you do not go over 30.00 this month.",
      "potentialSaving": 3.00,
      "state": "NEW"
    }
  ],
  "announcements": [
    {
      "id": 2,
      "title": "Import your past spending from a CSV file",
      "body": "You can upload a CSV file to bring your earlier spending into the system. Every row is previewed before anything is written to your ledger.",
      "severity": "SUCCESS",
      "startsAt": "2026-09-25T01:16:12",
      "endsAt": "2026-11-24T01:16:12"
    },
    {
      "id": 1,
      "title": "Welcome to Campus Coin",
      "body": "Record every income and expense to get saving tips based on your own habits. All analysis is a suggestion only, not financial advice.",
      "severity": "INFO",
      "startsAt": "2026-09-25T01:16:12",
      "endsAt": "2026-12-24T01:16:12"
    }
  ]
}
```

Four details of that example are worth noticing, because every one is the database's decision rather
than this module's:

- **Three tips are shown, and the demo account has nine.** `db/06_demo.sql` calls
  `sp_generate_tips` for the current month and the two before it — ids 1–3, 4–6 and 7–9 respectively —
  and this response contains only the current month's three. That is §5.1 made concrete: the tips
  view has no time filter, so without the DAO's `period_month` predicate all nine would be returned
  under September's heading — including two earlier months' "No budget set for Hostel/Rent" and
  "No budget set for Academics" — presented as advice about this month.
- **The first tip has no `categoryId`.** `SAVINGS_GOAL_AT_RISK` is about the goal, not a category, so
  `user_tips.category_id` is `NULL` there and the field is omitted. The other two carry one.
- **The order is the view's `rank_score`, not `potentialSaving`.** Here the two happen to agree, but
  the ranking weights each rule differently (BR-14) and they need not: a client renders the array as
  it arrives (§3.4).
- **Both announcements have the same `startsAt`** — they were seeded by one statement — so the
  newest-first order falls to the id tie-break, and id 2 comes first.

Amounts render with the column's scale — `71.00`, not `71` — and the percentages inside `body` come
from `ROUND(pct, 1)`, which is why the Food tip reads `80.0%` rather than `80%`. The announcement
timestamps depend on when `db/05_seed.sql` ran, since the seed computes them with `NOW()`.

A new account's response — the shape a client must not crash on:

```json
{
  "periodMonth": "2026-09",
  "summary": {
    "currency": "USD",
    "totalIncome": 0.00,
    "totalExpense": 0.00,
    "netAmount": 0.00,
    "monthlyAllowanceBaseline": 0.00,
    "monthlySavingsGoal": 0.00
  },
  "tips": [],
  "announcements": [
    {
      "id": 1,
      "title": "Welcome to Campus Coin",
      "body": "...",
      "severity": "INFO",
      "startsAt": "2026-09-01T00:00:00",
      "endsAt": "2026-11-30T00:00:00"
    }
  ]
}
```

Note what is absent: `topCategory` (nothing was spent) and `summary.savingsGoalPct` (no goal). Both are
normal, not errors.

### Failures

| Status | Code | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | No token, or a malformed, tampered, expired or revoked one |
| `403` | `ACCESS_DENIED` | The token belongs to an account that is not a student |
| `500` | `INTERNAL_ERROR` | Unexpected. No stack trace, SQL or schema identifier reaches the body |

There is no `400`: the endpoint takes no input to validate.

---

## 7. Status codes

`200` is the only success. `401` and `403` are decided before the controller by the security filter
chain (`SecurityConfig`), and the differentiation matters: `401` means "sign in", `403` means "signed
in, but this is not your screen".

---

## 8. What is not on this screen, and where it lives

This is the section to read before adding a field to the dashboard payload. Each of these was a
candidate and each was left out for a reason that is about duplication, not effort.

| Not here | Where it is | Why not both |
|---|---|---|
| Budget limits with consumption | `GET /api/v1/budgets` | `v_budget_consumption` already computes each limit's spend, remaining amount and status. A second copy in the dashboard would be the same rows read twice in one request and a second place the thresholds apply |
| Notifications and their read state | `GET /api/v1/notifications` | A notification has state — read or unread — and marking one read is a write. Putting the list behind a GET that must not write would either make the dashboard write or make the count stale |
| Insights (UC-17) | Module 9's own contract | `insights` is a different table with a different generator and a different lifecycle (an AI-generated narrative). UC-12 B3 asks for tips and announcements |
| Announcement administration | Module 11, UC-21 | The dashboard reads one audience's slice. Creating, editing and deactivating is the administrator's contract |
| Tip generation, pinning, dismissing | Module 9, UC-18 | This endpoint shows tips and publishes their `state` so a client can render a pinned one differently. It has no route that moves one |

**A note on the tips list and UC-18.** The dashboard's tip block and UC-18's tips screen read the same
view, `v_dashboard_tips`. They are not duplicates: UC-18 adds the actions (generate, pin, dismiss) and
their own endpoints, and the dashboard is a read of the top of the ranked list as part of a home
screen. When module 9 is built, the actions belong there and the read continues to come from the view
both use.

---

## 9. Security properties

| Property | How |
|---|---|
| **Identity is the token, never a parameter** | `@AuthenticationPrincipal AuthenticatedUser` is the only source of the account. No dashboard method anywhere takes a user id — not the controller, not the service, not the DAO — so reading another student's dashboard is impossible rather than refused |
| **Every query is scoped to the caller** | All three per-student queries bind `:userId`. The fourth (announcements) is not per-student and is scoped by audience instead |
| **No response carries the owner** | The views carry `user_id` and `v_active_announcements` carries `created_by`; neither is selected. `theResponseNeverNamesTheOwner` asserts the exact field sets and that the serialised body contains no `userId`, `user_id`, `createdBy` or `created_by` |
| **An administrator is refused (`403`)** | `/api/v1/dashboard/**` requires `hasRole("STUDENT")`. UC-23's usage statistics are aggregates over many students; letting an administrator through here would create a route that names one student's spending |
| **No write behind a GET** | The service is `@Transactional(readOnly = true)` and the DAO writes nothing. `readingTheDashboardChangesNoState` calls the endpoint three times and asserts no tip state and no notification count moved |
| **The announcement audience is filtered** | §5.2. Without it the endpoint would disclose administrator-targeted notices to students |
| **Stored prose is passed through unaltered** | `title` and `body` are the database's `fn_render_template` output. The API does not re-render, re-round or re-word them |

### What the response does not contain, deliberately

- **No `userId`,** on any block.
- **No `audience`** on an announcement (§3.5).
- **No `createdBy`** on an announcement — the API does not publish an announcement's author.
- **No `displayOrder`,** on a tip: the array's order is the same fact, and a second expression of it
  would be a value to keep in step with the array.
- **No `rankScore`,** on a tip. It is the view's ordering input, not a figure a client shows.
- **No `periodMonth` inside `summary` or on a tip.** The response states the month once, at the top.

---

## 10. Angular integration notes

### 10.1 The current frontend is mock-only, and its dashboard types do not match

`HOME_FEED_MOCK` and the services behind the home screen serve hard-coded objects (`useMockData: true`).
No Angular source is changed by this module. Three specific divergences a rewiring will have to
resolve:

1. **`frontend/src/app/core/models/insight.model.ts` declares `SpendingSummary`,** which no service
   references. It is closest in shape to this endpoint's `summary` block, and it is the type to
   replace rather than adapt — its field names are not this contract's.
2. **`home-feed.component.ts` renders a hard-coded "September 2026" label.** The month must come from
   `periodMonth` in the response, which is the month the figures actually describe. A label written in
   the component is a claim the payload cannot correct.
3. **The legacy `frontend/src/app/models/campus-coin.models.ts` and
   `services/campus-coin.service.ts`** are dead code from the deleted e-wallet domain and should be
   deleted, not rewired.

### 10.2 Which call to make

```ts
this.http.get<Dashboard>('/api/v1/dashboard')
```

One call on home-screen load. There is nothing to pass: no month, no user id.

### 10.3 Rendering rules the response implies

| Situation | Rule |
|---|---|
| `topCategory` absent | Render the "no spending yet" state. Do **not** fall back to `summary.totalExpense === 0` as the condition — the absence is the signal |
| `savingsGoalPct` absent | Show "set a saving goal to track progress" linking to the profile. A `0` here would mean "made no progress" |
| `savingsGoalPct` negative | Show it as it is. A month that spent past its income is a real state, and flooring it at zero hides the one thing the student needs to see |
| `tips` | Render in array order. Do not re-sort by `potentialSaving` |
| `tips[].categoryId` absent | Omit the "see this category" link rather than linking to `null` |
| `announcements[].endsAt` absent | The notice is open-ended. Do not treat it as expired |
| `announcements` | Expire a banner on `endsAt` if you cache the response across a session; do not assume the array is still current |
| Both lists | They are always present. `[]` means "nothing to show", and it is not an error |

### 10.4 Handling each status

| Status | Client behaviour |
|---|---|
| `401` | Clear the token and go to sign-in — the session is expired or revoked |
| `403` | The account is not a student. An administrator should never reach this screen |
| `500` | Show a retry affordance. Nothing the client sent caused it |

### 10.5 Months without a timezone bug

Do not compute the current month client-side to label the screen, and do not send one. Render
`periodMonth` as received. The month is derived from the database session's clock (§4); a browser in
another timezone computing "this month" could disagree with it at a boundary and label correct figures
with the wrong month.

---

## 11. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-12 | The seeded account's dashboard | `GET /dashboard` | `getDashboard` | `getDashboard` | all four views | `theDemoDashboardReportsTheSeededMonth` |
| UC-12 B1 | The month's totals | `GET /dashboard` | `getDashboard` | `getDashboard` | `v_dashboard_summary` | `newStudentDashboardIsEmptyButValid`, `totalsCountIncomeAndExpenseByCategoryType` |
| UC-12 B1 | Saving-goal progress | same | — | — | the view's `CASE` | `savingsGoalPercentageAppearsOnlyWhenAGoalIsSet`, `negativeGoalPercentageIsReportedRatherThanClamped` |
| UC-12 B2 | The highest-spending category | same | — | — | `v_top_category_current_month` | `topCategoryIsTheLargestExpenseCategory`, `incomeAloneDoesNotProduceATopCategory` |
| UC-12 B3 | Tips, ranked | same | — | `findTips` | `v_dashboard_tips` + the DAO's month predicate (§5.1) | `tipsAreTheCurrentMonthsAndAlreadyRanked`, `tipsFromAnotherMonthAreNotShown`, `theDemoDashboardReportsTheSeededMonth` |
| BR-14 | The tip block is bounded | same | — | — | `tips.max_dashboard` read by `sp_generate_tips` | `theTipBlockIsBounded` |
| UC-12 B3 | Pinned first (BR-14) | same | — | — | the view's `ORDER BY` | `pinnedTipsLeadTheList` |
| UC-12 B3 | A dismissed tip never returns | same | — | — | the view's `state <> 'DISMISSED'` | `dismissedTipsNeverComeBack` |
| UC-12 B3 | Live announcements | same | — | `findAnnouncementsForStudent` | `v_active_announcements` + the DAO's audience filter (§5.2) | `announcementsAreFilteredByAudience`, `announcementsOutsideTheirWindowAreNotShown` |
| UC-12 B3 | An open-ended notice is live | same | — | — | `ck_ann_window`, the view | `openEndedAnnouncementIsLiveAndHasNoEnd` |
| BR-02 | Ownership | same | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every per-student query | `dashboardShowsOnlyTheCallersOwnFigures` |
| BR-02 | No response carries the owner | same | — | `DashboardMapper` | — | `theResponseNeverNamesTheOwner` |
| BR-05 | The category's type decides income vs expense | same | — | — | the view's `CASE WHEN c.type = ...` | `totalsCountIncomeAndExpenseByCategoryType`, `incomeAloneDoesNotProduceATopCategory` |
| BR-09 | Trashed records stop counting | same | — | — | the views' `is_deleted = 0` | `softDeletedTransactionDropsOutOfTheTotals`, `topCategoryIgnoresTrashedTransactions` |
| BR-10 | The net figure is the difference | same | — | — | `v_dashboard_summary` | `negativeGoalPercentageIsReportedRatherThanClamped` |
| UC-12 | The month is not selectable | — | no `@RequestParam` | `summary.periodMonth()` | `CURDATE()` in the view | `theMonthIsNotSelectable` |
| UC-12 | Reading writes nothing | same | — | `readOnly = true` | — | `readingTheDashboardChangesNoState` |
| §7.5 | Student-only, administrator refused | same | — | — | — | `administratorsAreRefused`, `anonymousCallersAreRefused`, `malformedTokenIsRefused` |
| §26 | The endpoint is in the inventory and the document | same | — | — | — | `OpenApiContractIT` |

---

## Related documentation

- [`FRONTEND_API_GUIDE.md`](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, interceptors, the shared error contract, the enum reference and the master
  table of all 76 operations
- [`API_INVENTORY.md`](API_INVENTORY.md) — endpoint 35
- [`budgets.md`](budgets.md) — UC-13's limits and consumption, deliberately not repeated here
- [`notifications.md`](notifications.md) — UC-14's messages and their read state
- [`transactions.md`](transactions.md) — where the records behind every figure on this screen are written
- [`profile.md`](profile.md) — the saving goal and allowance baseline the summary reports
- [`../modules/MODULE_07_DASHBOARD.md`](../modules/MODULE_07_DASHBOARD.md) — the module report: tests, reviews, traceability
- [`../OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — the open items affecting this module
