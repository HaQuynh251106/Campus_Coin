# Module 7 — Dashboard (UC-12)

**Status:** DONE

The student's home screen in one response: the current month's totals and saving-goal progress
(UC-12 B1), the highest-spending expense category (UC-12 B2), the saving tips to show and the live
announcements (UC-12 B3). One endpoint, `GET /api/v1/dashboard`.

**This module's central finding is that its four views are not equally finished, and the difference
decides what the application layer must do.** Reading each view body rather than trusting its name
surfaced three things the requirement does not state:

1. **`v_dashboard_tips` has no time filter at all.** It partitions by `period_month` for its
   `ROW_NUMBER` and returns every month a student has tips for. A dashboard that took it as-is would
   show three months of advice under one month's heading — for the demo account, nine tips instead of
   three. The DAO adds the month predicate, and it is bound to the month the *summary view* reported
   rather than to a month Java computed (§5.1).
2. **`v_active_announcements` has no audience filter.** It answers "is this notice within its
   window", not "is this notice for this reader", and `announcements.audience` has three values. Left
   out, a notice written for administrators would appear on every student's dashboard. That is a
   **disclosure**, not a styling bug (§5.2).
3. **`v_top_category_current_month` publishes no icon or colour,** so the DAO joins `categories` for
   the two presentation columns the API publishes (§5.3).

**The module adds one endpoint and writes nothing.** Everything on the screen is a read of a view
some other module owns, and the three state changes the screen *displays* — a notification's read
flag, a tip's pinned or dismissed state, a limit's progress — are all reachable only through the
endpoint that owns them. §8 of [`docs/api/dashboard.md`](../api/dashboard.md) lists what was
considered for the payload and left out, with the reason for each.

**No blocker is recorded for this module**, and `git diff --stat db/` is empty: nothing in the schema
needed changing, and the two missing predicates are the application's to apply by design.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-12 B1 | The month's income, spending and net figure, with the progress against the saving goal |
| UC-12 B2 | The expense category the most was spent on this month, with its icon and colour |
| UC-12 B3 | The saving tips to show, ranked, and the live announcements addressed to the student |
| UC-12 BA | Every figure is the schema's. A dashboard that recomputed a total would eventually disagree with the reports screen reading the same views |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` parameter | The account is the bearer token's. This is the most personal read in the API, so there is no identifier to tamper with (BR-02) |
| A `?month=` parameter | §5.1 — the totals and top-category views derive their month from the database's own clock. Accepting the parameter would return the current month's figures under the requested month's heading |
| Budget progress bars (UC-13) | `GET /api/v1/budgets` already returns every limit with its consumption. A second copy would be the same rows read twice in one request and a second place the thresholds apply |
| Notifications (UC-14) | `GET /api/v1/notifications` is the list, and it is stateful: a message is read or unread, and marking one read is a write. Composing them would put a write behind this GET |
| Marking a notification read; pinning or dismissing a tip | Each is a state change owned by the module whose use case describes it. A dashboard render must not cause one |
| Raise, clear or recompute anything | Nothing here writes. A writing GET would need a lock |
| `/dashboard/{id}` | A dashboard belongs to an account, not to a record |
| `/dashboard/summary`, `/top-category`, `/tips`, `/announcements` | Four routes for four blocks of one screen describing one month. It would cost four round-trips and allow a mixed-month paint (§5.1) |
| `PUT`, `POST`, `PATCH`, `DELETE` on this path | UC-12 is a list of things to show |
| Insights (UC-17) | A different table with a different generator and lifecycle. UC-12 B3 asks for tips and announcements |
| Announcement administration (UC-21) | Module 11 owns it. This endpoint reads one audience's slice |
| Real-time updates (websocket/SSE) | Not a stated requirement, and no transport is configured. The window is published so a client can expire a banner itself |

---

## 2. Endpoints

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 35 | `GET` | `/api/v1/dashboard` | UC-12 | The home screen, all four blocks, current month | `200` |

Requires `hasRole("STUDENT")`; an administrator token is `403`. No query parameters. The contract is
in [`docs/api/dashboard.md`](../api/dashboard.md); the inventory row is 35 in
[`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md).

---

## 3. Database alignment

No schema object was created, changed or dropped. `git diff --stat db/` is empty. `ddl-auto: validate`
holds against MySQL 8 in both profiles, which is what proves the mapping matches the real views.

### 3.1 What the database owns, and this module therefore does not restate

| Concern | Owner | Evidence |
|---|---|---|
| The month's totals, split by category type (BR-05) | `v_dashboard_summary` | its `CASE WHEN c.type = ...` over `v_monthly_income_expense` |
| Trashed records stop counting (BR-09) | the views | each filters `is_deleted = 0` |
| The net figure is the difference (BR-10) | `v_dashboard_summary` | `IFNULL(m.net_amount, 0)`, computed in the view |
| The goal percentage, and its absence when there is no goal | `v_dashboard_summary` | `CASE WHEN u.monthly_savings_goal > 0 THEN ... END` — `NULL` otherwise |
| Which category is the top one | `v_top_category_current_month` | it restricts itself to the current month, `EXPENSE`, and `rn = 1` |
| Pinned tips lead (BR-14) | `v_dashboard_tips` | `ROW_NUMBER() OVER (... ORDER BY (state = 'PINNED') DESC, rank_score DESC)` |
| A dismissed tip never returns | `v_dashboard_tips` | `WHERE state <> 'DISMISSED'` |
| Which announcements are live | `v_active_announcements` | `is_active = 1` and the `starts_at`/`ends_at` window |
| An open-ended announcement is still running | `v_active_announcements`, `ck_ann_window` | `ends_at IS NULL` is permitted and the view treats it as running |
| How many tips exist at all | `sp_generate_tips` | BR-14's bound is `tips.max_dashboard`, read by the procedure — not by this module |

`v_dashboard_summary` restricts itself to `role = 'STUDENT'`, so an administrator id matches no row.
The DAO returns `Optional` and the service raises `NotFoundException` for that case rather than
assuming it cannot happen; through the API it is unreachable, because the endpoint requires the
student role.

### 3.2 The three gaps in the views this module fills

| Gap | Where it is filled | Why it matters |
|---|---|---|
| `v_dashboard_tips` has **no time filter** | `SELECT_TIPS` adds `AND period_month = :periodMonth` | Without it, every month's tips appear under one heading — §5.1 |
| `v_active_announcements` has **no audience filter** | `SELECT_ANNOUNCEMENTS` adds `audience IN ('ALL','STUDENTS')` | Without it, an administrator-targeted notice reaches students — §5.2 |
| `v_top_category_current_month` publishes **no `icon`, `color`** | `SELECT_TOP_CATEGORY` joins `categories` | The API publishes both. One row in, one row out, on the row's own `category_id` — §5.3 |

The first two are the module's real work. The third is the same gap module 6's
`BudgetConsumptionDao` fills for `v_budget_consumption`, and for the same reason: the view answers
"how much", and the icon and colour are presentation.

---

## 4. Implementation

| Concern | Where | Note |
|---|---|---|
| The endpoint | `dashboard/controller/DashboardController` | `@AuthenticationPrincipal`; no parameter of any kind |
| Assembly and the month decision | `dashboard/service/DashboardService` | `@Transactional(readOnly = true)`; passes the summary's month to the tips query |
| The four view reads | `dashboard/repository/DashboardViewDao` | Four native `Tuple` queries, one per view, each with a stated predicate |
| Projection | `dashboard/mapper/DashboardMapper` | The single gate for what leaves the server; also owns the month-to-string conversion |
| View projections | `dashboard/entity/*` | Six records/enums, one per view row shape |
| Response shapes | `dashboard/dto/*` | Five records; nullable fields carry `@JsonInclude(NON_NULL)` |
| Role rule | `auth/security/SecurityConfig` | `/api/v1/dashboard/**` → `hasRole("STUDENT")` |

**Four queries, not one join.** The views are not keyed the same way — the summary is one row per
student, the top category at most one, the tips many, the announcements not per-student at all. A
join would multiply the summary's row by the number of tips and require the totals to be
`DISTINCT`-ed or re-aggregated, undoing the guarantee that the figure comes from the view unaltered.

### 4.1 The month comes from the summary row, not the application clock

`v_dashboard_summary` reports `period_month` as `CURDATE()` **inside the database session** (pinned to
`+07:00`, VĐ-10). `v_top_category_current_month` scopes itself to that same value. The tips view does
not — it has no filter at all — so `getDashboard` passes the summaries' month to `findTips`.

The alternative, computing "the current month" in Java with `BudgetService`'s
`ZoneId.of("Asia/Ho_Chi_Minh")`, would be *nearly* right and occasionally wrong: two clocks, one
value, and a request landing across a month boundary could report one month's totals beside another
month's tips. Every field would still be individually correct, which is what would make it hard to
notice. Reading the month out of the row is one source for one value.

**This is not a timezone guarantee, and the code does not claim one.** The database session's zone is
a deployment setting; what is guaranteed is that a single request does not mix two months.

### 4.2 The response publishes the month once

`periodMonth` is on `DashboardResponse` and nowhere else: not on `summary`, not on each tip. A tip
carries no month, because a second copy would be a second value to keep in step with the heading.
`DashboardTip` briefly carried one during development and was changed once the response shape settled
— the removal is why `SELECT_TIPS` no longer selects `v.period_month`.

### 4.3 The tips order is restated, and that is not a second ranking

SQL makes no promise that a view's `ROW_NUMBER()` ordering survives into the selecting statement's
result order, so `SELECT_TIPS` names the same two keys the view ranks by — pinned first, then score —
and adds `tip_id` so two equally-scored tips cannot swap places between calls. This asks for the order
the view already computes; it does not define one. The test compares against
`v_dashboard_tips.display_order` rather than restating the rule.

---

## 5. Defects found and fixed

### 5.1 `v_dashboard_tips` has no time filter, and the first draft assumed it did

The view's name and its `PARTITION BY user_id, period_month` read as though it returned one month.
It does not: the partition exists so `ROW_NUMBER` restarts per month, and the result set is every
month the student has tips for. The view is also UC-18's source for the tips screen, where a
multi-month result is correct — so the gap is not a defect in the view. It is a gap that the caller
must close, and this module is a caller that needs it closed.

Reading the body settled it. `db/06_demo.sql` calls `sp_generate_tips(@u1, @m2, 3)`,
`sp_generate_tips(@u1, @m1, 3)` and `sp_generate_tips(@u1, @m0, 3)`, so the demo account has **nine**
tips. Querying the view directly shows them:

```
tip_id  category_id  title                                          display_order
1       8            Hostel/Rent spending is up 200.0%              1     ← m2
4       8            No budget set for Hostel/Rent                  1     ← m1
7       NULL         Your savings goal is at risk                    1     ← m0
2       8            No budget set for Hostel/Rent                  2     ← m2
5       8            Hostel/Rent spending is up 50.0%               2     ← m1
8       11           Entertainment spending is up 127.3%            2     ← m0
3       9            No budget set for Academics                    3     ← m2
6       9            No budget set for Academics                    3     ← m1
9       6            Food has used 80.0% of its budget              3     ← m0
```

Three rows carry `display_order = 1`, one per month. A dashboard that read this view as-is would show
all nine, ordered as `1, 4, 7, 2, 5, 8, 3, 6, 9` — a list in which one month's advice is interleaved
with another's, under a single heading. The DAO adds `AND v.period_month = :periodMonth`, and
`tipsFromAnotherMonthAreNotShown` creates a tip for last month and requires the response to be empty.
`theDemoDashboardReportsTheSeededMonth` asserts the demo account returns exactly its current month's
three.

### 5.2 `v_active_announcements` has no audience filter — a disclosure, not a styling bug

`v_active_announcements` selects from `announcements` with `is_active = 1 AND starts_at <= NOW() AND
(ends_at IS NULL OR ends_at >= NOW())`. Nothing narrows `audience`, which has three values: `ALL`,
`STUDENTS`, `ADMINS`.

A student's dashboard reading the view unchanged would therefore receive administrator-targeted
notices. On a system where an announcement might say "scheduled maintenance, student records are
read-only until 14:00" or name an internal process, that is a disclosure of internal content to
every student — and it would be invisible in the response, because the field that would reveal it
(`audience`) is exactly the one the API does not publish.

The DAO applies `a.audience IN ('ALL', 'STUDENTS')`.
`announcementsAreFilteredByAudience` inserts one `ADMINS`, one `ALL` and one `STUDENTS` notice and
requires the first to be absent and the other two present, then removes all three so the shared
container keeps its seeded state.

There is no mirror-image case. An administrator is refused this whole endpoint by `SecurityConfig`
(§4), so there is no administrator dashboard that would need the `ADMINS` half.

### 5.3 The top-category view publishes no icon or colour

`v_top_category_current_month` selects `user_id, period_month, category_id, category_name,
total_amount`. The API publishes `categoryIcon` and `categoryColor`, so the DAO joins `categories` on
the row's own `category_id`. One row in, one row out: `category_id` is the join key and unique in
`categories`, and the view has already reduced the ranking to `rn = 1`.

The alternative — reading the whole category list and matching in Java — would be a second read and a
second implementation of "find the category". `theDemoDashboardReportsTheSeededMonth` asserts the
seeded `home` / `#EF4444` reach the response, which is what proves the join found the right row
rather than merely producing two non-null strings.

### 5.4 A dashboard suite that writes to the shared seeded account breaks its own read assertions

The first version of `DashboardApiIT` had a read-only test on the seeded demo account
(`theDemoDashboardReportsTheSeededMonth`) and a second test that recorded a `130.00` expense on that
same account to check the top-category block follows the data. The second ran first and the first
failed: `summary.totalExpense` was `319.00` where the seed's `189.00` was expected.

The cause is structural rather than a mistake in either test. **Every test in this suite goes through
HTTP, and an HTTP write commits in its own transaction** — there is no JUnit transaction for the test
framework to roll back, which is deliberate: the suite tests the real stack end to end, and wrapping
it in a rolled-back transaction would stop testing what the application actually does. The seeded
account is shared state that no test can restore.

The fix is a rule rather than a special case: **tests on the seeded account are read-only.** All three
of them (`theDemoDashboardReportsTheSeededMonth`, `theTipBlockIsBounded`, and the profile-backed
assertions inside them) read and never write, and the section of the class says so in a comment. The
behaviour the removed test covered — "the top category follows the data rather than caching" — is
covered against a per-test student by `topCategoryIsTheLargestExpenseCategory` and
`topCategoryIgnoresTrashedTransactions`, which is where a test that must write belongs.

The general form of this applies to every module from here: a test that writes to a seeded account
leaks into every other test that reads it, because nothing rolls it back.

### 5.5 The tips view's `rank_score` is not published, and the sample response nearly implied it was

Drafting the example response in [`docs/api/dashboard.md`](../api/dashboard.md) from the seeded data
surfaced a documentation defect rather than a code one: the first draft showed three plausible-looking
tips whose ids and figures were invented. Two things were wrong with that.

The smaller one is that invented figures in a contract document are worse than no example: a reader
cannot tell which numbers are properties of the system and which are the author's invention. The
larger one is that it hid §5.1 — the demo account has nine tips, and a reader comparing a hand-written
"three tips" example against a real response would have had no way to see that the interesting fact
was the three showing at all.

The example is now taken from the running database and is introduced as such. It also documents that
`rank_score`, which is the actual ranking key, is **not** published — the array's order is the same
fact, and publishing the score would invite a client to re-sort by a number whose meaning (each rule
weights differently) is not in the response.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `dashboard/DashboardApiIT.java` | 27 | HTTP → Controller → Security → Service → DAO → MySQL 8 (Testcontainers) |
| `support/OpenApiContractIT.java` | 10 | Contract — 2 of its assertions cover this module |

Full suite: **421 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.** Module 6's baseline was
**394**; module 7 adds 27 HTTP tests, and two of `OpenApiContractIT`'s existing assertions were
extended to cover it (the endpoint-list entry, and the path count 21 → 22 — its own test count is
unchanged).

Both `OpenApiContractIT` assertions were updated deliberately: `DOCUMENTED_ENDPOINTS` gained
`GET /api/v1/dashboard`, and `noEndpointIsDuplicated`'s `hasSize(21)` became `hasSize(22)` with its
explanatory comment corrected — 22 paths, 35 operations, 13 single-method paths. This is §26's gate
working as intended: adding the route without updating the inventory fails the test.

**Coverage against §13 Phase 5's categories**, all through HTTP:

| # | Category | Tests |
|---|---|---|
| 1 | Happy path | `newStudentDashboardIsEmptyButValid`, `theDemoDashboardReportsTheSeededMonth`, `topCategoryIsTheLargestExpenseCategory` |
| 2 | Validation | Not applicable — the endpoint takes no input. `theMonthIsNotSelectable` covers the one parameter a caller might send |
| 3 | Unauthenticated | `anonymousCallersAreRefused`, `malformedTokenIsRefused` |
| 4 | Unauthorized role | `administratorsAreRefused` |
| 5 | Ownership violation | `dashboardShowsOnlyTheCallersOwnFigures` |
| 6 | Not found | Not reachable through the API: the endpoint takes no identifier. The DAO's `Optional` is defensive |
| 7 | Invalid identifiers | Not applicable — no identifiers are accepted |
| 8 | Boundary values | `negativeGoalPercentageIsReportedRatherThanClamped` (a net-negative month), `savingsGoalPercentageAppearsOnlyWhenAGoalIsSet` (goal `0` vs `100.00`) |
| 9 | DB constraints and triggers | `openEndedAnnouncementIsLiveAndHasNoEnd` (`ck_ann_window`), `topCategoryIgnoresTrashedTransactions` |
| 10 | Stored procedure behaviour | `theDemoDashboardReportsTheSeededMonth` and `theTipBlockIsBounded` read tips that `sp_generate_tips` generated; `pinnedTipsLeadTheList` and `dismissedTipsNeverComeBack` exercise the two state transitions the view filters on |
| 11 | Rollback | `readingTheDashboardChangesNoState` — nothing to roll back, asserted rather than assumed |
| 12 | Duplicate requests | `readingTheDashboardChangesNoState` — three identical calls, identical outcome |
| 13 | Concurrency / idempotency | `readingTheDashboardChangesNoState` (idempotent). No concurrency test: a read with no lock and no write has nothing to race |
| 14 | Error consistency | `administratorsAreRefused` (`403 ACCESS_DENIED`), `anonymousCallersAreRefused` (`401`) |
| 15 | Security regressions | `theResponseNeverNamesTheOwner`, `announcementsAreFilteredByAudience`, `administratorsAreRefused` |
| 16 | Empty state | `newStudentDashboardIsEmptyButValid` — zeroed totals, no top category, empty tips |
| 17 | UAT | `theDemoDashboardReportsTheSeededMonth` (the figures a reviewer will check by hand), `tipsAreTheCurrentMonthsAndAlreadyRanked` |
| — | Cross-module (§16) | `totalsCountIncomeAndExpenseByCategoryType` (module 4 writes, this module reads), `softDeletedTransactionDropsOutOfTheTotals` (UC-10's delete), `savingsGoalPercentageAppearsOnlyWhenAGoalIsSet` (module 2's profile), `tipsFromAnotherMonthAreNotShown` (module 9's generator, by procedure) |

**The fixtures that touch tables the API has no route for say so.** `insertAnnouncement` writes an
`announcements` row directly, because creating one is UC-21 and belongs to module 11; `generateTips`,
`pinTip` and `dismissTip` write the tips tables, because those are UC-18's and module 9 does not exist.
Each is documented in `AbstractDashboardApiIT` with the reason, and each announcement a test creates
is deleted afterwards so the shared container keeps its seeded state. Where the API *can* write the
row — a transaction, a saving goal, a budget — the fixture uses the API.

**One fixture detail worth keeping.** `AbstractDashboardApiIT.bind` converts a `LocalDate` to
`java.sql.Date` before binding it. The connector would otherwise send a `LocalDate` as a character
value and let the server cast it, which works for a `DATE` column but coerces anything else —
including the temporal arguments `sp_generate_tips` declares. Converting means the procedure is called
with the type it declares, so the tips the tests read are the tips a real caller would get.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every field traces to UC-12 or to a view (§9). Nothing is published that no use case asks for |
| B. Duplicate / missing endpoints | None. One path, one method. The four-block split was rejected in writing (§1); no `/dashboard/all`, `?month=`, `?userId=` or per-block route exists, and the inventory records why |
| C. Layering | Controller → Service → DAO → MySQL. The view projections are records, not entities; no DAO is called from the controller |
| D. Validation placement | Not applicable — no input. The one parameter shape a caller might invent is answered by `theMonthIsNotSelectable` |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`. **No new error code was needed**: `404` is unreachable through the API and `403` is the pre-existing `ACCESS_DENIED` |
| F. Transaction boundaries | One read, `@Transactional(readOnly = true)`. Not annotated mechanically |
| G. N+1 and fetch strategy | Exactly four queries, one per view, none in a loop. `open-in-view: false`, so a forgotten fetch fails loudly |
| H. Locking | None, deliberately: nothing is written and every read is a consistent read of a view. A lock would serialise readers of a dashboard for no gain |
| I. Schema coupling | `ddl-auto: validate`; no schema change; native queries projected by alias, so a column added to a view cannot break the mapping |
| J. Security | §15 of [`docs/api/dashboard.md`](../api/dashboard.md): identity from the token only, every per-student query scoped, role enforced in `SecurityConfig`, audience filtered |
| K. Sensitive output | `DashboardMapper` is the single gate. `userId`, `created_by`, `audience`, `displayOrder` and `rank_score` are all deliberately unmapped, each with a stated reason |
| L. Logging | Nothing is logged by this module. There is no write to log and the read carries no parameter that could leak into a message |
| M. Dead code | `createCategory` and `CATEGORIES_URL` in `AbstractDashboardApiIT` are used; `DashboardTip`'s `periodMonth` component and the corresponding SELECT column were removed during development rather than left unused |
| N. Naming | Matches the surrounding modules: `DashboardViewDao` mirrors `BudgetConsumptionDao`, `DashboardMapper` mirrors the other mappers |
| O. Documentation | `docs/api/dashboard.md`, the inventory row 35, and this report |
| P. Tests through HTTP | All 27, against real MySQL 8. No unit tests are needed and none were added: there is no branch to isolate — the module has no decision the database does not make, beyond the two predicates §5.1 and §5.2 cover through HTTP |
| Q. Empty state | Asserted for both lists and for `topCategory` and `savingsGoalPct` individually, because each has a different absence rule |
| R. Determinism | The DAO's `tip_id` tie-break makes two equally-scored tips stable between calls; the announcements order falls to `id DESC` when two share a `starts_at`, which the seeded pair does. **The view's own tie-break is the one remaining non-determinism** — `v_top_category_current_month` orders by `total_amount DESC` alone, so two equal category totals have no defined winner. `db/` is frozen by §4, so this is documented in [`docs/api/dashboard.md` §3.3](../api/dashboard.md#33-topcategory) as a property a client must not depend on, rather than papered over |
| S. Idempotency | `readingTheDashboardChangesNoState` calls the endpoint three times and asserts no tip state and no notification count moved |
| T. Restart behaviour | No state in memory. The month, the totals and the ranking are all computed per read |
| U. Configurability | Port 8080 unchanged; `tips.max_dashboard` is read by the procedure, not hard-coded here |
| V. Language | All artifacts English |

One defect was found and fixed in this pass (§5.4) and one documentation defect was corrected (§5.5).
No criterion failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's dashboard | Impossible — no method takes a user id, at any layer. `dashboardShowsOnlyTheCallersOwnFigures` proves the figures differ |
| Ask for another month with `?month=2020-01` | Ignored; the response reports the current month. `theMonthIsNotSelectable` |
| Ask for an administrator's dashboard | `403 ACCESS_DENIED` before the controller |
| Use a missing, malformed, expired, revoked or foreign JWT | `401` before the controller |
| Use a disabled account's token | `401` before the controller |
| **See an administrator-targeted announcement** | **Not returned.** The DAO's audience filter — §5.2. This was the module's most serious reachable defect |
| See a notice that has been switched off, has not started, or has ended | Not returned; the view's window applies |
| See a tip from another month | Not returned; the DAO's month predicate — §5.1 |
| See a dismissed tip | Not returned, and not merely moved to the end (`dismissedTipsNeverComeBack`) |
| Have a tip's array position depend on `potentialSaving` | It does not; the order is the view's `rank_score`. `tipsAreTheCurrentMonthsAndAlreadyRanked` compares against `display_order` |
| Re-sort the tips client-side and get the same list | Not guaranteed, and §3.4 of the contract tells a client not to try |
| See a tip that belongs to no category rendered with a null link | `categoryId` is absent, not null; `tipsWithoutACategoryOmitTheField` |
| See a count of spending that includes a trashed record | `softDeletedTransactionDropsOutOfTheTotals` and `topCategoryIgnoresTrashedTransactions` |
| See income counted as the top spending category | Impossible; the view ranks `EXPENSE` only (`incomeAloneDoesNotProduceATopCategory`) |
| Read a `savingsGoalPct` of `0` for a student with no goal | Absent instead — the distinction the view makes and the mapper preserves |
| See a goal percentage clamped at zero for a net-negative month | Not clamped: `-40.00` is reported. `negativeGoalPercentageIsReportedRatherThanClamped` |
| Make the dashboard write something (mark seen, record a view, refresh a cache) | Nothing is written. Three calls, no state change (`readingTheDashboardChangesNoState`) |
| Deduce another student's spending from an error message or a count | No `404` is reachable and no count is published; the only failures are `401` and `403`, which carry no data |
| Find `userId`, `created_by`, `audience`, `displayOrder` or `rank_score` in the payload | Absent. `theResponseNeverNamesTheOwner` asserts the exact field sets and greps the serialised body |
| Infer the owner's identity from an announcement's author | `created_by` is not selected from the view |
| Have the response mix two months | Prevented by reading the month from the summary row — §4.1 |
| Cross which month it is between two calls of the same request | The month is read once, from one row, and everything else is scoped to it |
| Add a field to a view and have it appear in the response | It cannot: the DAO selects named columns and the mapper maps named components |
| Add a field to a record and have the published contract widen silently | `theResponseNeverNamesTheOwner` compares against literal field lists, so the test fails first |
| Assume a client sending `?month=` is being refused | It is not; it is ignored and the month is reported. Documented in §4 so a tester does not read it as a bug |

**Nothing in this pass failed except the two constraints the schema owns and this module documents
rather than changes** — the tip view's missing time filter and the announcement view's missing
audience filter, both of which the application applies correctly — and the one view-level
non-determinism recorded under R above. No fixable security defect was left as a documentation note.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-12 B1 | The month's totals | `GET /dashboard` | `getDashboard` | `getDashboard` | `v_dashboard_summary` | `newStudentDashboardIsEmptyButValid`, `totalsCountIncomeAndExpenseByCategoryType` |
| UC-12 B1 | Saving-goal progress | same | — | — | the view's `CASE` | `savingsGoalPercentageAppearsOnlyWhenAGoalIsSet`, `negativeGoalPercentageIsReportedRatherThanClamped` |
| UC-12 B2 | The highest-spending category | same | — | — | `v_top_category_current_month` + the icon/colour join | `topCategoryIsTheLargestExpenseCategory` |
| UC-12 B3 | Tips, ranked | same | — | `findTips` | `v_dashboard_tips` + the month predicate | `tipsAreTheCurrentMonthsAndAlreadyRanked`, `theDemoDashboardReportsTheSeededMonth` |
| UC-12 B3 | The month filters the tips | same | — | the month argument | the DAO's `AND period_month = :periodMonth` | `tipsFromAnotherMonthAreNotShown` |
| BR-14 | Pinned first | same | — | — | the view's `ORDER BY` | `pinnedTipsLeadTheList` |
| BR-14 | The block is bounded | same | — | — | `tips.max_dashboard` via `sp_generate_tips` | `theTipBlockIsBounded` |
| UC-12 B3 | A dismissed tip never returns | same | — | — | `state <> 'DISMISSED'` | `dismissedTipsNeverComeBack` |
| UC-12 B3 | Live announcements | same | — | `findAnnouncementsForStudent` | `v_active_announcements` + the audience filter | `announcementsAreFilteredByAudience`, `announcementsOutsideTheirWindowAreNotShown` |
| UC-12 B3 | An open-ended notice is live | same | — | — | `ck_ann_window`, the view | `openEndedAnnouncementIsLiveAndHasNoEnd` |
| BR-02 | Ownership | same | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every per-student query | `dashboardShowsOnlyTheCallersOwnFigures` |
| BR-02 | No response carries the owner | same | — | `DashboardMapper` | — | `theResponseNeverNamesTheOwner` |
| BR-05 | The category's type decides income vs expense | same | — | — | the view's `CASE WHEN c.type = ...` | `totalsCountIncomeAndExpenseByCategoryType`, `incomeAloneDoesNotProduceATopCategory` |
| BR-09 | Trashed records stop counting | same | — | — | the views' `is_deleted = 0` | `softDeletedTransactionDropsOutOfTheTotals`, `topCategoryIgnoresTrashedTransactions` |
| BR-10 | The net figure is the difference | same | — | — | `v_dashboard_summary` | `negativeGoalPercentageIsReportedRatherThanClamped` |
| UC-12 | The month is the database's and is not selectable | — | no `@RequestParam` | `summary.periodMonth()` | `CURDATE()` in the view | `theMonthIsNotSelectable` |
| UC-12 | Reading writes nothing | same | — | `readOnly = true` | — | `readingTheDashboardChangesNoState` |
| §7.5 | Student-only, administrator refused | same | — | — | — | `administratorsAreRefused`, `anonymousCallersAreRefused`, `malformedTokenIsRefused` |
| §26 | The endpoint is in the inventory and the document | same | — | — | — | `OpenApiContractIT#documentMatchesTheInventory`, `#noEndpointIsDuplicated` |
| §16 | Cross-module regression | modules 2, 4, 9 | — | — | — | `savingsGoalPercentageAppearsOnlyWhenAGoalIsSet`, `softDeletedTransactionDropsOutOfTheTotals`, `tipsFromAnotherMonthAreNotShown` |

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` (22 paths, 35 operations) |
| Every field traced to a documented requirement | Yes — §9, and [`docs/api/dashboard.md` §11](../api/dashboard.md#11-traceability) |
| Validation with per-field errors using `field` | Not applicable — no input. Stated rather than omitted |
| Ownership enforced server-side, structurally | Yes — no method at any layer takes a user id |
| No sensitive field in any response | Yes — `userId`, `created_by`, `audience`, `displayOrder` and `rank_score` are unmapped, and `theResponseNeverNamesTheOwner` pins it |
| Tests through HTTP against real MySQL 8 | Yes — 27 |
| No schema change; `validate` holds | Yes — §3 |
| API document written; Angular can integrate without guessing | Yes — [`docs/api/dashboard.md`](../api/dashboard.md), including the rendering rules the nullable fields imply |
| Inventory updated; no duplicate endpoint | Yes — endpoint 35 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 421 tests |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — nothing is deferred |

---

## 11. Deferred / blocked

**Nothing in this module is blocked, and no new blocker is recorded.** For the first time in the
project the schema needed no accommodation: the three gaps in the views (§3.2) are gaps the
*application* is designed to fill, not defects in the database, and `db/` is untouched.

**Global items that touch this module:**

| Item | Effect here |
|---|---|
| **OB-001** — the authoritative source documents are not on disk | The same caveat as every module: UC-12's field-level requirements were taken from the UC table in [`docs/ERD.md`](../ERD.md) and cross-checked against the four views, which are named for the use case and are the schema's own statement of what the screen shows |
| **OB-002** — no production email provider | Unaffected. The dashboard sends nothing; announcements are in-app |
| **OB-003** — production secrets management | Unaffected: the module adds no credential and reads no setting beyond what the views read |
| **OB-004** — throttle counters are per instance | Unaffected: the dashboard is a read and is not throttled |
| **OB-011** / **OB-009** — a retired category freezes its budgets and rules | Visible here, and correctly: a frozen budget's spending still counts towards the month's totals and can still be the top category, because the transactions are unaffected by the freeze. The dashboard reports the figures; it does not report budget status, which is why the freeze is not a dashboard concern |

**A limitation stated so it is not mistaken for coverage:** there is no concurrency test for this
module. The endpoint is a read with no lock, no write and no pagination, so there is nothing to race.
`readingTheDashboardChangesNoState` covers idempotency — three calls, identical outcome, no state
change — which is the property that does apply.

**A property a client must not depend on, documented rather than fixed:**
`v_top_category_current_month` orders by `total_amount DESC` with no tie-break, so two expense
categories with equal totals have no defined winner. `db/` is frozen by §4 and re-ranking in Java
would impose an order the schema did not choose, so
[`docs/api/dashboard.md` §3.3](../api/dashboard.md#33-topcategory) states it and the module reports
whichever row the view selected.

---

## Related documentation

- [`docs/api/dashboard.md`](../api/dashboard.md) — the UC-12 contract, the two filters, and what is
  deliberately not in the payload
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — endpoint 35
- [`docs/modules/MODULE_04_TRANSACTIONS.md`](MODULE_04_TRANSACTIONS.md) — where the records behind
  every figure on this screen are written
- [`docs/modules/MODULE_06_BUDGET.md`](MODULE_06_BUDGET.md) — the module this one follows, and the
  source of the view-plus-join pattern §5.3 repeats
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — the global items
