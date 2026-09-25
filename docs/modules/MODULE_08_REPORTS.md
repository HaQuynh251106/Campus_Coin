# Module 8 — Reports & Export (UC-15, UC-16)

| | |
|---|---|
| **Endpoints** | `GET /api/v1/reports` (36), `GET /api/v1/reports/spending` (37) |
| **Requirements** | UC-15, UC-16, BR-02, BR-05, BR-09, BR-17, BR-18, UAT-09, VĐ-10, SRS §7.5, §13 |
| **Schema objects read** | `v_monthly_income_expense`, `v_category_month_totals`, `v_monthly_income_expense_6m`, `v_daily_spending_current_month`, `v_weekly_spending_current_month` (+ `categories`, `dim_month` inside the views/joints) |
| **Schema objects written** | None. `db/` is unchanged from module 7's — every file's modification time predates this module (newest 2026-09-24); `db/` is untracked, so `git diff` is not usable evidence (§3.6) |
| **Tests** | 41 new (20 `ReportsApiIT` + 20 `SpendingReportApiIT` + 1 `OpenApiContractIT`); suite 421 → **462** |
| **Status** | Complete. No blocker, nothing deferred |

---

## 1. Scope

UC-15 is the reports screen: a student reviews a month of their own activity. The requirement names
six things to show — the month's income, its spending, the net between them, the split of each by
category, the six-month trend, and a breakdown of the spending by day or by week. UC-16 is the export
of that screen to a file.

Read against the five views, the six items are **not** one thing. Four of them are keyed by a month
the caller can choose; the other two are not. That single asymmetry decides the module's whole shape,
and §4 is about it.

**Two endpoints, and the split is the module's central decision rather than a preference.**

| # | Endpoint | Carries |
|---|---|---|
| 36 | `GET /api/v1/reports` | The month's totals, the two category blocks, the six-month trend |
| 37 | `GET /api/v1/reports/spending` | The same month's spending by day or by ISO week |

Both are `STUDENT`-only. There are no aliases, no `/export`, no `{id}` route, no `?userId=`, and no
`POST` of any kind — §2 lists what was considered and rejected with the reason.

### What is deliberately not built

| Excluded | Why |
|---|---|
| `userId` anywhere | The account is the bearer token's. A report is one named student's month, so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/reports/{id}` | A report is not a stored row: no table, no procedure, no identifier. It is a read of views |
| `GET /api/v1/profile/me/reports` | The collection is already the caller's. A second path would be a second name for it |
| `/reports/summary`, `/reports/monthly`, `/reports/categories` | Each is one block of the one report that already carries all three for the same month |
| `GET /api/v1/reports/export`, `?format=csv` | §4 — BR-18 is satisfied by the two reads; a third route would be a second way to ask one question (§13) |
| A `MONTHLY` granularity | Two breakdowns exist in the schema. A third would be a grouping no view computes |
| Budgets on the report | `GET /api/v1/budgets` already publishes each limit with its consumption |
| `POST`/`PATCH`/`PUT`/`DELETE` | UC-15 is reads. A report has nothing for a client to write |
| A per-category trend | Nothing in the schema computes one, and adding it here would be a second definition of a month's spending |

---

## 2. Endpoints

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 36 | `GET` | `/api/v1/reports` | UC-15, UC-16 | Bearer, `STUDENT` | `200` | `400` `401` `403` |
| 37 | `GET` | `/api/v1/reports/spending` | UC-15 | Bearer, `STUDENT` | `200` | `400` `401` `403` |

Parameters, all optional:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 36 | `month` | `yyyy-MM`, **any real month** | the current month |
| 37 | `month` | `yyyy-MM`, **the current month only** | the current month |
| 37 | `from`, `to` | `yyyy-MM-dd` **within** the current month | first and last day of the month |
| 37 | `granularity` | `DAILY` \| `WEEKLY` | `DAILY` |

There is no request body on either path, so there is no request schema and no mass-assignment
surface. The `OpenApiContractIT` asserts that absence explicitly (`CreateReportRequest`,
`UpdateReportRequest`, `ExportReportRequest` must not exist).

---

## 3. Database alignment

### 3.1 What the database owns, and this module therefore does not restate

| Concern | Owner | Where |
|---|---|---|
| The month's income / expense / net / count, split by the category's type | DB | `v_monthly_income_expense` (`CASE WHEN c.type = 'INCOME'`, `is_deleted = 0`) |
| One row per category per month, with its type | DB | `v_category_month_totals` |
| The six-month window and its zero-fill | DB | `v_monthly_income_expense_6m` (`dim_month` join, `IFNULL(..., 0)`, `role = 'STUDENT'`) |
| Which day a record falls in, and what an ISO week's boundaries are | DB | `v_daily_spending_current_month`, `v_weekly_spending_current_month` (`YEARWEEK(date, 3)`, `WEEKDAY`) |
| Trashed records excluded | DB | every view's `is_deleted = 0` (BR-09) |
| Direction is the category's type, not a sign | DB | `ck_txn_amount CHECK (amount > 0)` + the views' `c.type` filter (BR-05) |

`ReportService` never sums a transaction, never decides which six months, and never fills a gap. The
one figure it computes is the category share (§3.3), which no view computes.

### 3.2 The module's own work

- Turning a client's `yyyy-MM` into the first-of-month `DATE` the views are keyed by.
- Deciding which month a report may be asked about — §4, and the module's hardest decision.
- Splitting one view's rows into the income block and the expense block (the same rows read once, then
  partitioned by the `type` the view already published).
- Refusing a request that would name a month a view cannot answer about.

### 3.3 `percentage` is computed in Java, and the reason is that no view computes it

`v_category_month_totals` publishes `total_amount` and `txn_count` but no share of a month. A pie
chart needs one. `ReportMapper.toCategoryResponses` computes it from the sum of the totals **in the
block being published** — expense rows for `expenseByCategory`, income rows for `incomeByCategory` —
with `RoundingMode.HALF_UP` to two decimals, the mode MySQL's `ROUND` uses so a share computed here
and `consumed_pct` computed by a view round alike.

Using the block's own row sum as the denominator rather than an independent month total is deliberate:
the rows come from one view read, so their sum *is* the block total, no row can be counted twice, and
the share is a proportion of exactly what is published beside it.

**Each share is rounded on its own, so a block's shares do not in general total 100.** Three equal
thirds each read `33.33` and sum to `99.99`. This was the module's first real defect — §5.1.

### 3.4 The DAO joins `categories`, and the views are read as they stand

`v_category_month_totals` publishes no icon or colour, so `SELECT_CATEGORY_TOTALS` joins `categories`
on the row's own `category_id`. One row in, one row out: `category_id` is unique in `categories`, so
the join cannot widen the result or re-scope which student is reported on. The alternative — adding
the two columns to the view — would change the database to suit a DTO (§4), and the join is the same
choice `BudgetConsumptionDao` and `DashboardViewDao` each made.

### 3.5 Five queries, not one joined statement

The views are not keyed alike: totals are one row per student-month, the breakdown is one row per
category, the trend is six rows, and each series is one row per interval. A join would multiply the
totals row by the category count and require `DISTINCT`-ing or re-aggregating the sums — undoing the
guarantee that each figure comes from its view unaltered. Five reads of indexed views is cheaper and
clearer, and it is why `ReportViewDao` has five methods rather than one query.

### 3.6 `db/` is untouched

**A caveat added after this module was written: `db/` is untracked in this repository**
(`git status --short db/` reports `?? db/`, `git ls-files db/` is empty), so `git diff --stat -- db/`
prints nothing whatever the files contain — an untracked path produces no diff. The claim below was
originally offered as evidence via that command; the command is vacuous, so it is replaced here with
the evidence that actually holds.

What substantiates it: every file under `db/` has a modification time from **2026-09-24** (before any
module 8 work), the newest being `db/merged/campuscoin_full.sql`. No view, table, column, index,
constraint, procedure or seed row was changed for this module. The two series-granularity mismatches
(§4) are accommodated in the application, not in the schema, and this module's tests load
`db/merged/campuscoin_full.sql` into a fresh MySQL 8 container and pass against the unmodified
schema.

---

## 4. Implementation

### 4.1 The decision the module is built around: some sources are month-selectable and some are not

This is UC-15's central problem and it is worth stating in full, because every other choice follows
from it.

The five views split into two groups by how their range is decided:

| View | Range from |
|---|---|
| `v_monthly_income_expense` | its `period_month` key — **any month** |
| `v_category_month_totals` | its `period_month` key — **any month** |
| `v_monthly_income_expense_6m` | `CURDATE()` — the last six months, **no parameter** |
| `v_daily_spending_current_month` | `CURDATE()` — **the current month only** |
| `v_weekly_spending_current_month` | `CURDATE()` — **the current month only** |

So the totals and the category blocks can answer about September when it is December; the daily and
weekly series cannot be pointed at any month but the current one.

**Three ways to arrange this were considered.**

1. **One endpoint with a `month`, applying it to everything it can and ignoring it where it cannot.**
   Rejected: a caller asking for August would receive August's totals beside September's bars, every
   number individually correct and no field revealing the mismatch. This is the worst outcome, because
   it is invisible.
2. **One endpoint with no month at all** — the dashboard's approach. Rejected: UC-15 is explicitly a
   report a student reviews, and reviewing means choosing a month. Discarding the parameter discards
   the use case.
3. **Two endpoints: a month-selectable one and a current-month one that refuses what it cannot
   honour.** Chosen.

The second endpoint therefore **refuses** a month or window it cannot serve, with `400
VALIDATION_ERROR` and a field error naming the parameter, rather than silently narrowing to the
current month. The refusal is the honest answer: the parameter is genuinely useful (a chart's date
range is not a thing to remove), and the caller can correct the request once told which parameter was
wrong.

Naming the current month **explicitly** is accepted — it is the same request as omitting it, and
refusing it would be a trap.

### 4.2 `sixMonthTrend` ignores the selected month, and that is BR-17 rather than an oversight

`GET /api/v1/reports?month=2026-08` returns August's totals and August's category blocks, with the
trend being **April to September** — the last six months ending at the current one, not at the
selected one. BR-17 defines that window as fixed. Filtering the trend to the selected month would
leave a single point, which is not a trend.

What makes it honest rather than confusing is that **each trend point carries its own `periodMonth`**,
so a client draws the trend's x-axis from the points and never from the response's top-level
`periodMonth`, which names the selected month. `ReportResponse`'s javadoc, `docs/api/reports.md` §4,
and the `@Operation` text each say so, and
`theTrendIsUnaffectedByTheSelectedMonth` pins it: a report for an earlier month returns the same six
points as the current month's report.

### 4.3 The weekly predicate is an overlap, and the difference it makes is measured

`SELECT_WEEKLY` includes a bar when it touches the window at either end:

```sql
AND v.week_start <= :to
AND v.week_end   >= :from
```

not when it sits wholly inside it. A bar's `week_start` is the real Monday of an ISO week, which may
fall in the previous month, so a containment test would drop a bar whose Monday is outside the window
even though the bar carries spending from inside it.

**What this does *not* mean is that a weekly total can exceed the month's expense.** Both current-month
views filter `t.txn_date` to the month *before* grouping; they group the identical record set and
differ only in the grouping. So over a whole month the two granularities report the same total
(verified live: both `189.00`). The second defect of this module was documenting the opposite — §5.2.

What an overlap *does* mean is narrower: over a **narrowed** window a weekly total can exceed the
daily one, because a bar that merely touches the window is returned whole. Verified live over
`from=2026-09-08&to=2026-09-10`: DAILY `37.00`, WEEKLY `61.00`, the extra `24.00` being the rest of the
ISO week that contains 8–10 September. `aNarrowedWindowReturnsWholeWeeks` pins it.

### 4.4 The two granularities are one response shape

`ReportGranularity` has exactly two members because the schema computes exactly two groupings. The
`DAILY`/`WEEKLY` choice changes which view is read and nothing else, so the response shape, the point
shape and the field names are identical — a client draws one chart and widens its bars. `granularity`
is echoed in the response so the client never infers the mode from whether the two dates differ.

It is typed as the enum, not a `String`. That was a correction forced by the contract test — §5.3.

### 4.5 UC-16 has no endpoint, and the requirement is why

BR-18: an export file is produced **only when the user asks**, and **nothing is buffered** — "the file
is generated at call time".

That is exactly what endpoints 36 and 37 already do. They are computed on each call from the views,
they buffer nothing, and they can be called as often as a client likes with no stored file
accumulating.

An `/api/v1/reports/export` returning these same figures as CSV or PDF would therefore be **a second
way to ask one question** (§13). Its payload would be one of these two responses formatted differently,
from the same views; two routes to one set of figures is how the two eventually disagree.

**What the frontend already does is correct.** `ReportsComponent.exportReport()` calls
`window.print()` — BR-18 satisfied on the client: nothing buffered, generated when the button is
pressed, from the figures the screen already fetched. No backend call is involved and none is needed.

`OpenApiContractIT.noEndpointIsDuplicated` asserts the absence positively: `/api/v1/reports/export`,
`/reports/csv`, `/reports/download`, `/reports/summary`, `/reports/monthly`, `/reports/categories`,
`/reports/all`, `/reports/list`, `/reports/{id}` and `/profile/me/reports` must **not** be among the
registered paths, so a future developer adding one fails the build with the reason attached.

### 4.6 Class layout

```
com.campuscoin.reports
├── controller/ReportController            The two endpoints; @Operation text carries the contract
├── service/   ReportService               Month/window resolution and every refusal
├── repository/ReportViewDao               Five native view reads, projected by alias
├── mapper/    ReportMapper                Block splitting, shares, the yyyy-MM conversion
├── entity/    ReportScope                 The month + window a response describes
│              ReportGranularity           DAILY | WEEKLY
│              ReportTotals                One v_monthly_income_expense row, or empty
│              CategoryBreakdownRow        One v_category_month_totals row (+ icon/colour join)
│              MonthlyTrendPoint           One v_monthly_income_expense_6m row
│              SpendingPoint               One series row, either granularity
└── dto/       ReportResponse, ReportTotalsResponse, ReportCategoryResponse,
               ReportTrendPointResponse, SpendingSeriesResponse, SpendingPointResponse
```

`ReportScope` exists so that the month and the window are decided **once** and carried through the
whole read: a response cannot mix one month's totals with another month's bars, because there is one
value and it is resolved before any query runs.

---

## 5. Defects found and fixed

### 5.1 The first draft published shares that were asserted to add to 100 — and they do not

`ReportsApiIT` had a test named `categorySharesInABlockSumTo100`, and it failed:
`expected: 100.00 but was: 99.99`.

Three equal thirds each round to `33.33`. `33.33 × 3 = 99.99`. Independent per-slice rounding cannot
sum to 100 when the division is inexact, and the seed's August data produces the same effect with six
unequal categories (`57.97 + 14.49 + 10.63 + 7.73 + 5.31 + 3.86 = 99.99`).

**The fix was not to change the wording, and not to make the code produce 100.** Both would have been
wrong. The mapper was already correct: a slice's percentage is a property of that slice, and the
alternative — give the remainder to the largest slice so the pie closes — publishes one category's
share as a number that is not that category's share. A report is exactly where such a figure cannot
be afforded.

So three things changed:

1. **The test** was rewritten as `categorySharesAreEachTheirOwnAndAreRoundedIndependently`, asserting
   each share is `33.33`, the sum is `99.99`, and the shortfall is under one unit of the last decimal
   place per slice. The old assertion encoded a false claim; the new one encodes the true property.
2. **Four production documentation sites** that had repeated the false claim were corrected:
   `ReportMapper`'s class javadoc and `toCategoryResponses`, `ReportController.getReport`'s
   `@Operation`, and `ReportCategoryResponse`'s class javadoc and `percentage` `@Schema`. Each now
   states that the shares need not total 100, gives the `33.33 × 3` example, and says `total` is what
   a whole pie should be drawn from.
3. **`docs/api/reports.md` §7** was written to document it with the real August figures.

`OpenApiContractIT`'s `reportSchemasMatchTheDocumentedContract` was given a comment recording why the
schema check deliberately does **not** pin the shares as summing to 100.

### 5.2 The weekly-total claim was false, and reading the view bodies is what caught it

This was the more serious defect, and it was in my own production javadoc.

I had documented — in `ReportViewDao`'s `SELECT_WEEKLY`, `ReportController.getSpendingSeries`,
`SpendingPoint`, `ReportMapper.toSeriesResponse` and `SpendingSeriesResponse` — that a weekly series
can exceed **the month's** expense, on the reasoning that its bars include days from neighbouring
months.

`db/02_views.sql` says otherwise. `v_daily_spending_current_month` (line 222) and
`v_weekly_spending_current_month` (line 256) **both** filter `t.txn_date` to the current month *before*
grouping:

```sql
WHERE t.is_deleted = 0 AND c.type = 'EXPENSE'
  AND t.txn_date >= CAST(DATE_FORMAT(CURDATE(),'%Y-%m-01') AS DATE)
  AND t.txn_date <  DATE_ADD(CAST(DATE_FORMAT(CURDATE(),'%Y-%m-01') AS DATE), INTERVAL 1 MONTH)
GROUP BY t.user_id, t.txn_date            -- daily
GROUP BY t.user_id, iso_year, iso_week    -- weekly, over the same filtered set
```

Only the `GROUP BY` differs. The two views group the **identical record set**, so they agree on the
month's total — verified live: both `189.00`.

The false claim had survived because it was never tested: no test asserted the property, so nothing
contradicted it. What found it was reading the view bodies while drafting the contract document, and
then verifying the corrected property empirically rather than assuming the correction was right.

**Fixed at all six sites**, plus a stale comment in the test that repeated it. The corrected property,
now stated everywhere, is:

> A bar's **dates** can reach outside the month, but its **total** cannot. Over a whole month the two
> granularities agree on the total. Over a **narrowed** window a weekly total can exceed the daily one,
> because the overlap predicate returns a bar whole.

A new test, `aNarrowedWindowReturnsWholeWeeks`, pins the one case where the two do differ, asserting
DAILY `30.00` against WEEKLY `40.00` when the week's Monday is still in-month, and equality otherwise.

The lesson recorded for later modules: **a claim about what a view does must be read from the view,
not inferred from its name.**

### 5.3 `SpendingSeriesResponse.granularity` was a `String` where every sibling publishes its enum

`OpenApiContractIT.reportSchemasMatchTheDocumentedContract` failed with
`SpendingSeriesResponse.granularity must publish its enum members`.

The DTO declared `String granularity` while `ReportCategoryResponse.type` and every enum-valued field
in the other modules publish their members as an enum, which is what lets a client generate a typed
client and what the contract test exists to enforce.

**Fixed by changing the DTO** — `String` → `ReportGranularity`, plus an import — and having the mapper
pass `scope.granularity()` straight through instead of `.name()`. The assertion was not weakened.

### 5.4 The path count in the contract test was arithmetically wrong

`OpenApiContractIT.noEndpointIsDuplicated` asserted `assertThat(paths).hasSize(22)`. Two new
single-method paths take the distinct-path count 22 → **24**, and the operation count 35 → **37**.

The inherited working note had recorded "23 paths", which is neither. Corrected to 24, with the
operation arithmetic written out in the comment: 22 operations on 9 multi-method paths plus 15
single-method paths = 37 across 24 distinct paths.

### 5.5 A wrong test name had been written into the inventory

`docs/api/API_INVENTORY.md` cited `ReportsApiIT#theTrendIsTheFixedSixMonthWindow`. The method's actual
name is `theTrendIsUnaffectedByTheSelectedMonth`. Found by listing the real method names rather than
trusting the draft, and corrected. Every test name in `docs/api/reports.md` §11 and this report's §9
was checked against the source after that.

### 5.6 A false "the response loses its decimal scale" finding, caught before it changed anything

A check that the documented claim "amounts are `DECIMAL(15,2)` and serialise with that scale" held
appeared to fail: `260.00` came back as `260.0`. The cause was the verification command, not the API —
`python3 -m json.tool` re-emits JSON numbers as floats and destroys the trailing zero.

The **raw** `curl` bytes carry the scale: `{"income":260.00,"percentage":12.70}`. No documentation or
code was changed on the strength of the false reading. The rule recorded is that every worked example
in the documentation is copied from raw response bytes, never from a pretty-printer's output.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `reports/ReportsApiIT.java` | 20 | HTTP → Controller → Security → Service → DAO → MySQL 8 (Testcontainers) |
| `reports/SpendingReportApiIT.java` | 20 | same, for endpoint 37 |
| `support/OpenApiContractIT.java` | 11 | Contract — 1 of its tests is new for this module, 3 others extended |

Full suite: **462 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (1:35 min). Module 7's
baseline was **421**; module 8 adds **41** (20 + 20 + `OpenApiContractIT` 10 → 11).

Per-class totals (`Tests run:` from the surefire report):

| Class | Tests | | Class | Tests |
|---|---|---|---|---|
| `RecurringRuleApiIT` | 87 | | `PasswordResetApiIT` | 14 |
| `TransactionApiIT` | 57 | | `BudgetWriteFailureTest` | 12 |
| `BudgetApiIT` | 45 | | `RecurringRuleWriteFailureTest` | 11 |
| `CategoryApiIT` | 44 | | `OpenApiContractIT` | 11 |
| `ProfileApiIT` | 31 | | `TransactionWriteFailureTest` | 9 |
| `DashboardApiIT` | 27 | | `AdminAuthApiIT` | 8 |
| `NotificationApiIT` | 20 | | `CategoryWriteFailureTest` | 8 |
| **`ReportsApiIT`** | **20** | | `CampusCoinApplicationTests` | 1 |
| **`SpendingReportApiIT`** | **20** | | `SecurityHardeningIT` | 19 |
| `AuthenticationApiIT` | 18 | | **Total** | **462** |

### What the two suites cover

**`ReportsApiIT` (endpoint 36, UC-15/UC-16)** — the totals split by category type; trashed records
excluded; the two category blocks; each slice's icon, colour, total, count and share; the shares
rounded independently; a student's own category treated like a default one; an earlier month reported
against the current one; an empty month reporting absence rather than zero; a future month answered
honestly rather than refused; a malformed month refused with a field error naming it; a non-month
refused rather than parsed loosely; the trend always six rows oldest-first and unaffected by the
selected month; the empty months present as `0.00`; the report scoped to the caller; no parameter able
to name another student; the exact documented field set; `401` without a token and `403` for an
administrator; and that reading changes no state.

**`SpendingReportApiIT` (endpoint 37, UC-15)** — the endpoint's defining property is a **refusal**, so
it is a suite of its own. The default window is the whole month; an in-month window is honoured;
naming the current month explicitly is accepted; and then the refusals: a previous month, a future
month, `from` outside, `to` outside, an inverted window, `MONTHLY`/`hourly`/`DAILY-` granularities and
malformed edges (`2026-9-1`, `01/09/2026`, `yesterday`), each asserting `400 VALIDATION_ERROR` with a
field error naming the right parameter. Then the behaviour it does have: DAILY one bar per day with
quiet days absent; a deleted record leaving the series; income ignored; WEEKLY real Monday–Sunday ISO
weeks; a week's dates reaching outside the month; `dailyAndWeeklyAgreeOverTheWholeMonth` (both
`36.00`); and `aNarrowedWindowReturnsWholeWeeks`.

### Test design note

The suite is fixture-light compared with the dashboard's, and for a structural reason. **A report has
no writer** — no table, no procedure, no migration — so unlike `DashboardApiIT` it needs no direct
database writes to produce its input. Every figure it asserts against is written through
`POST /api/v1/transactions` and read back through HTTP, exercising the path a real caller takes. The
direct JDBC connection is used only to *read*, to confirm a response matches the row the schema holds.

Each test registers a fresh student with a random address, so no test depends on another's rows and the
seeded accounts — including the demo account with three months of history — are never modified. The
generation-source comment on `AbstractReportsApiIT` records this.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every field traces to UC-15, UC-16 or a view (§9). Nothing is published that no use case asks for |
| B. Duplicate / missing endpoints | None. Two paths, two methods. The single-endpoint arrangement was rejected in writing (§4.1); no `/export`, `?format=`, `{id}` or `/profile/me/reports` exists, and the contract test asserts their absence |
| C. Layering | Controller → Service → DAO → MySQL. View projections are records, not entities; no DAO is called from the controller |
| D. Validation placement | In the service, not the controller, except the granularity parse which is in the controller only so its failure carries a field error in the standard shape. Every refusal names its parameter |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`. **No new error code was added**: every failure is the pre-existing `VALIDATION_ERROR`, `UNAUTHENTICATED` or `ACCESS_DENIED`. There is no reachable `404` |
| F. Transaction boundaries | Both methods `@Transactional(readOnly = true)`. Not annotated mechanically |
| G. N+1 and fetch strategy | Exactly five queries across the two endpoints, none in a loop. The category join is on the row's own unique key, so it cannot fan out. `open-in-view: false` |
| H. Locking | None, deliberately: nothing is written and every read is a consistent read of a view |
| I. Schema coupling | `ddl-auto: validate`; no schema change; native queries projected by alias, so a column added to a view cannot break the mapping |
| J. Security | §9 of [`docs/api/reports.md`](../api/reports.md): identity from the token only, every query scoped by `user_id`, role enforced in `SecurityConfig` |
| K. Sensitive output | `ReportMapper` is the single gate. `userId` is unmapped on every DTO, and `theResponseCarriesExactlyTheDocumentedFields` / `theSeriesCarriesExactlyTheDocumentedFields` compare against literal field lists so a new component fails the build |
| L. Logging | Nothing is logged by this module. The reads carry only a month, two dates and a granularity, none of which is sensitive |
| M. Dead code | `ReportScope.monthEnd()` is used by the service's window completion; `monthKey`/`lastDayOfMonthKey` in the abstract base are used by the suites. `ReportGranularity`'s two members both reach a view |
| N. Naming | Matches the surrounding modules: `ReportViewDao` mirrors `BudgetConsumptionDao`/`DashboardViewDao`; `ReportMapper` mirrors the other mappers |
| O. Documentation | [`docs/api/reports.md`](../api/reports.md), inventory rows 36–37, and this report |
| P. Tests through HTTP | All 41, against real MySQL 8. No unit tests were added: the module's only Java-computed figure is the share, and it is covered end-to-end by `categorySharesAreEachTheirOwnAndAreRoundedIndependently` against real rows rather than a synthetic list |
| Q. Empty state | Asserted separately for the totals block (absent figures), the two arrays (empty), and the series (empty points with a published window) — because each has a *different* absence rule (§6 of the contract) |
| R. Determinism | The category order carries an explicit `category_id ASC` tie-break, and the series an explicit date order, so two equally-sized results are stable between calls. `ORDER BY` is restated in the selecting statement rather than relied upon from the view, because SQL makes no promise a view's ordering survives |
| S. Idempotency | `readingAReportChangesNoState` calls the endpoint and asserts no row count moved |
| T. Restart behaviour | No state in memory. The month, the window and the shares are computed per read |
| U. Configurability | Port and profile unchanged; no setting is read by this module beyond `users.currency` |
| V. Language | All artifacts English |

Two behaviour defects (§5.1, §5.2) and one type defect (§5.3) were found and fixed in this pass, and
two documentation defects (§5.4, §5.5) corrected. No criterion failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's report | Impossible — no method at any layer takes a user id. `aReportContainsOnlyTheCallersRecords` proves the figures differ |
| Name another student with a parameter | No parameter can: the request carries a month, two dates and a granularity. `noParameterCanNameAnotherStudent` |
| Ask for an administrator's report | `403 ACCESS_DENIED` before the controller |
| Use a missing, malformed, expired, revoked or foreign JWT | `401` before the controller |
| Ask for an earlier or future month on the spending series | `400`, field error naming `month`. Not answered from the current month — §4.1 |
| Smuggle a window outside the month past `from`/`to` | `400`, field error naming the edge that is wrong |
| Send `from` after `to` | `400`, field error naming `from` |
| Send a granularity the enum does not have | `400`, field error naming `granularity`. `ReportGranularity.valueOf` is guarded, not left to throw |
| Send `2026-9`, `202609`, `September` as a month | All refused. `YearMonth.parse` is strict, and the refusal is a field error rather than a coerced month |
| Send `yesterday` or `01/09/2026` as a window edge | Refused the same way |
| Have a quiet day invented as a zero | It is not returned at all — `dailyPointsCoverOnlyTheDaysWithSpending` |
| Have the two granularities disagree over a whole month | They do not; both count the same month-filtered rows (`dailyAndWeeklyAgreeOverTheWholeMonth`) |
| Have a weekly total include spending from a neighbouring month | It cannot — the view filters `txn_date` to the month. §5.2 |
| Have a weekly bar's dates clipped to the month | Not clipped: the bar is a real ISO week, exactly as VĐ-10 requires (`aWeekMayReachOutsideTheMonthAndIsNotClipped`) |
| Have a trashed record still counted | Excluded by the views; `deletedTransactionsAreExcludedFromTheTotals`, `deletedRecordsLeaveTheDailySeries` |
| Have income counted as spending | Impossible; the views filter `c.type`, and `incomeIsNotPartOfTheSpendingSeries` |
| Read a share of `0.00` where a category has records | Cannot arise: a category appears only with a positive amount, and the zero-guard is for the arithmetic rather than the data |
| Have a block's shares silently forced to 100 | They are not; each is that slice's own share — §5.1 |
| Find `userId`, or the owner's identity, in any payload | Absent. `theResponseCarriesExactlyTheDocumentedFields` and `theSeriesCarriesExactlyTheDocumentedFields` pin the exact field sets |
| Have the response mix two months | Prevented by resolving the month once into `ReportScope` before any query runs |
| Have a report for July carry July's trend | It carries the current six months, each labelled — BR-17, and documented in §4.2 rather than left to surprise |
| Make a report write something (record a view, cache its own figures) | Nothing is written; both methods are `readOnly = true` and `readingAReportChangesNoState` checks it |
| Deduce another student's figures from an error message | No `404` is reachable and no count is published; the only failures are `400`, `401` and `403` |
| Add a field to a view and have it appear in a response | It cannot: each query selects named columns and each mapper maps named components |
| Add a field to a DTO and have the contract widen silently | Two field-set tests compare against literal lists, so the build fails first |
| Call an export route | None exists — §4.5 — and the contract test asserts each rejected shape is absent |

**Nothing in this pass failed.** The module accommodates what the schema offers (§4.1) and refuses
what it cannot honour, and no reachable security defect was left as a documentation note. The one
property a client must not assume — that the two granularities agree over a *narrowed* window — is
stated on the response, on the DTO, in the DAO and in the contract document rather than left to be
discovered.

**A limitation stated so it is not mistaken for coverage:** there is no concurrency test for this
module. Both endpoints are reads with no lock, no write and no pagination, so there is nothing to
race. `readingAReportChangesNoState` covers idempotency — the property that does apply.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-15 | The month's totals | `GET /reports` | `getReport` | `getReport` | `v_monthly_income_expense` | `totalsCountIncomeAndExpenseByCategoryType` |
| UC-15 | Every category's total for the month | same | — | `findCategoryTotals` | `v_category_month_totals` + the icon/colour join | `categorySlicesCarryThePresentationColumnsAndTheirShare` |
| UC-15 | A student's own category included | same | — | — | the view's `user_id IS NULL OR user_id = ...` | `personalCategoriesAppearInTheBreakdown` |
| UC-15 | The month's spending by day | `GET /reports/spending` | `getSpendingSeries` | `findDailySpending` | `v_daily_spending_current_month` | `dailyPointsCoverOnlyTheDaysWithSpending`, `dailyPointsSumPerDayAndAreOrdered` |
| UC-15 | …and by ISO week | same | — | `findWeeklySpending` | `v_weekly_spending_current_month` | `weeklyPointsAreRealIsoWeeks` |
| UC-15 | A category's share of its block | `GET /reports` | — | `ReportMapper.toCategoryResponses` | — (computed) | `categorySharesAreEachTheirOwnAndAreRoundedIndependently` |
| UC-15 | Any month can be reported on | `GET /reports` | `@RequestParam month` | `resolveAnyMonth` | the `period_month` keys | `anyMonthWithRecordsCanBeReportedOn`, `aFutureMonthReportsNothing` |
| UC-15 | A month that is not a month is refused | same | — | `parseMonth` | — | `malformedMonthIsRefused`, `unparseableMonthIsRefused` |
| BR-17, UAT-09 | Six months always, empty ones as `0.00`, ending at the current month | `GET /reports` | — | `findSixMonthTrend` | `v_monthly_income_expense_6m` + `dim_month` | `theTrendAlwaysHasSixMonthsOldestFirst`, `emptyMonthsInTheTrendAreZeroNotMissing`, `theTrendIsUnaffectedByTheSelectedMonth` |
| BR-05 | The category's type decides income vs expense | both | — | the two blocks | the views' `CASE WHEN c.type = ...` | `categoriesAreSplitByTheirType`, `totalsCountIncomeAndExpenseByCategoryType`, `incomeIsNotPartOfTheSpendingSeries` |
| BR-09 | Trashed records stop counting | both | — | — | the views' `is_deleted = 0` | `deletedTransactionsAreExcludedFromTheTotals`, `deletedRecordsLeaveTheDailySeries` |
| BR-18 | The file is generated when asked, nothing buffered | — | — | — | — | §4.5 — no route exists; `OpenApiContractIT` asserts its absence |
| VĐ-10 | A month boundary never splits an ISO week | `/reports/spending` | — | — | `YEARWEEK(t.txn_date, 3)`, `WEEKDAY()` | `weeklyPointsAreRealIsoWeeks`, `aWeekMayReachOutsideTheMonthAndIsNotClipped` |
| §4.1 | The series refuses a month or window it cannot honour | same | — | `resolveScope`, `requireCurrentMonth` | the views' `CURDATE()` | `anotherMonthIsRefused`, `aFutureMonthIsRefused`, `aWindowOutsideTheMonthIsRefused`, `anInvertedWindowIsRefused`, `anUnknownGranularityIsRefused`, `aMalformedWindowEdgeIsRefused` |
| §4.3 | A bar that overlaps the window is returned whole | same | — | — | the DAO's overlap predicate | `aNarrowedWindowReturnsWholeWeeks`, `dailyAndWeeklyAgreeOverTheWholeMonth` |
| §6 | Absent and zero are used as the schema decides | both | — | — | the views' presence rules | `anEmptyMonthHasAbsentFiguresRatherThanZeroes`, `newStudentReportIsEmptyButValid` |
| BR-02 | Ownership | both | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `aReportContainsOnlyTheCallersRecords`, `noParameterCanNameAnotherStudent`, `theSpendingSeriesIsScopedToTheCaller` |
| BR-02 | No response names the owner | both | — | `ReportMapper` | — | `theResponseCarriesExactlyTheDocumentedFields`, `theSeriesCarriesExactlyTheDocumentedFields` |
| §7.5 | Student-only, administrator refused | both | — | — | — | `aReportRequiresAStudentToken`, `theSpendingSeriesRequiresAStudentToken` |
| §26 | Both endpoints are in the inventory and the document | both | — | — | — | `OpenApiContractIT#documentMatchesTheInventory`, `#noEndpointIsDuplicated`, `#reportSchemasMatchTheDocumentedContract` |
| §13 | No duplicate endpoint | both | — | — | — | `OpenApiContractIT#noEndpointIsDuplicated` (the alias `doesNotContain` block) |
| UC-15 | Reading writes nothing | both | — | `readOnly = true` | — | `readingAReportChangesNoState` |

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` (24 paths, 37 operations, as the document stood when this module shipped; later modules add more) |
| Every field traced to a documented requirement | Yes — §9, and [`docs/api/reports.md` §11](../api/reports.md#11-traceability) |
| Validation with per-field errors using `field` | Yes — every refusal carries a field error naming the parameter, including all six of endpoint 37's |
| Ownership enforced server-side, structurally | Yes — no method at any layer takes a user id |
| No sensitive field in any response | Yes — `userId` is unmapped everywhere; two tests compare against literal field lists |
| Tests through HTTP against real MySQL 8 | Yes — 41 |
| No schema change; `validate` holds | Yes — every `db/` file predates this module (newest 2026-09-24) and none was modified after; `db/` is untracked, so `git diff --stat` is not usable as evidence here (§3.6) |
| API document written; Angular can integrate without guessing | Yes — [`docs/api/reports.md`](../api/reports.md), including the ten mock-vs-contract divergences a rewiring must reconcile |
| Inventory updated; no duplicate endpoint | Yes — endpoints 36–37 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 462 tests |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — UC-16 is satisfied without a new route (§4.5), and nothing is deferred |

---

## 11. Deferred / blocked

**Nothing in this module is blocked, and no new blocker is recorded.** `db/` is untouched (by
modification time; §3.6 records why `git diff` is not usable evidence for an untracked path): the two
mismatches between the views (§4.1) are properties of the schema the *application* accommodates, not
defects in it, and the module refuses what it cannot serve rather than bending the schema to fit.

**Global items that touch this module:**

| Item | Effect here |
|---|---|
| **OB-001** — the authoritative source documents are not on disk | The same caveat as every module: UC-15/UC-16's field-level requirements were taken from the UC table in [`docs/ERD.md`](../ERD.md) and cross-checked against the five views, which are named for the use case and are the schema's own statement of what the screen shows |
| **OB-002** — no production email provider | Unaffected. A report sends nothing |
| **OB-003** — production secrets management | Unaffected: the module adds no credential and reads no setting beyond the account's own currency |
| **OB-004** — throttle counters are per instance | Unaffected: both endpoints are reads and are not throttled |
| **OB-011** / **OB-009** — a retired category freezes its budgets and rules | Visible here, and correctly: a frozen category's transactions still count towards the month's totals and its slice still appears, because the freeze affects budgets and rules, not records. The report states the figures; it does not report budget status |

**A limitation stated so it is not mistaken for coverage:** there is no concurrency test, because
there is nothing to race — see §8.

**A property a client must not depend on, documented rather than fixed:** over a **narrowed** window
the weekly total can exceed the daily one, because an overlapping bar is returned whole. Both `db/`'s
views and the DAO's predicate are correct for a report whose unit is the week; the property is stated
on the response, the DTO, the DAO and [`docs/api/reports.md` §5.3](../api/reports.md#53-what-a-narrowed-window-does-and-the-one-case-where-the-two-granularities-differ)
rather than left to be discovered.

**The frontend is still mock-only.** `ReportsComponent` computes every figure from `MOCK_TRANSACTIONS`
and is **not changed by this module**. [`docs/api/reports.md` §10](../api/reports.md#10-angular-integration-notes)
records the ten divergences a rewiring must reconcile — most importantly that the mock rounds shares to
whole numbers and sums them as though they were percentages of 100, and that its export button's
`window.print()` is already BR-18-compliant and needs no backend call.

---

## Related documentation

- [`docs/api/reports.md`](../api/reports.md) — the UC-15/UC-16 contract, the two-endpoint decision, and
  what a client may not assume
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — endpoints 36–37, and the decisions recorded
  with them
- [`docs/modules/MODULE_07_DASHBOARD.md`](MODULE_07_DASHBOARD.md) — the module this one follows, and
  the other reader of `v_monthly_income_expense` and `v_category_month_totals`
- [`docs/modules/MODULE_04_TRANSACTIONS.md`](MODULE_04_TRANSACTIONS.md) — where the records behind
  every figure here are written
- [`docs/modules/MODULE_06_BUDGET.md`](MODULE_06_BUDGET.md) — the source of the view-plus-join pattern
  §3.4 repeats
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — the global items
