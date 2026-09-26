# Campus Coin — API Inventory

Every endpoint the backend exposes, with the use case that requires it and whether it is public.

This file is the authoritative list. An endpoint that is not in this table does not exist, and
adding one requires a use case that asks for it. Several capabilities the database has are
**deliberately not exposed**: there is no token refresh endpoint, no change-password endpoint, no
session-listing endpoint and no `/users/*` alias, because UC-01, UC-02, UC-03 and UC-05 do not
define those flows. "The column exists" is not a reason to build an API.

Base path: `/api/v1`. All endpoints consume and produce `application/json`.

**76 operations on 56 paths**, numbered 1–76 across the twelve modules. The count is pinned by
`OpenApiContractIT`, which reads the generated OpenAPI document and fails if it disagrees with this
list — so a documented endpoint that does not exist, or an endpoint that is not documented here,
breaks the build rather than the contract.

## Module 1 — Authentication

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 1 | POST | `/api/v1/auth/register` | UC-01 | Public | `201` no body | Authentication |
| 2 | POST | `/api/v1/auth/login` | UC-02 | Public | `200` token + user summary | Authentication |
| 3 | POST | `/api/v1/auth/logout` | UC-02 | Bearer token | `204` no body | Authentication |
| 4 | POST | `/api/v1/auth/password-reset/request` | UC-03 | Public | `200` generic message | Authentication |
| 5 | POST | `/api/v1/auth/password-reset/verify` | UC-03 | Public | `200` `{valid: true}` | Authentication |
| 6 | POST | `/api/v1/auth/password-reset/complete` | UC-03 | Public | `200` confirmation message | Authentication |
| 7 | POST | `/api/v1/admin/auth/login` | UC-05 | Public | `200` token + user summary | Authentication |

**Total: 7 endpoints.** No duplicates: each path appears once, and no two paths serve the same
purpose. `/api/v1/auth/login` and `/api/v1/admin/auth/login` both authenticate, but they are not
duplicates — UC-05 requires a separate administrator portal with its own expected role, and the two
differ in URL, in the role they admit and in the policy applied. The authentication logic behind
them is a single service method, so the *endpoints* are distinct without the *implementation* being
duplicated.

## Module 2 — Profile & Preferences

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 8 | GET | `/api/v1/profile/me` | UC-04, UC-27 | Bearer token | `200` profile | Profile & Preferences |
| 9 | PATCH | `/api/v1/profile/me` | UC-04 | Bearer token | `200` profile | Profile & Preferences |
| 10 | PATCH | `/api/v1/profile/me/preferences` | UC-27 | Bearer token | `200` profile | Profile & Preferences |

**Total: 3 endpoints.** No duplicates: each path and method pair appears once.

There is deliberately no `/api/v1/users/{id}` route, and no `PUT` for the whole profile. UC-04 is a
student managing *their own* profile, so every endpoint acts on the account in the bearer token and
none accepts a user identifier — which is what makes BR-02 structural rather than a check that
could be forgotten. An administrator acting on another account is UC-22, in module 11, with its own
contract.

`GET /api/v1/profile/me` serves both use cases because UC-04 and UC-27 return the same record; a
second read endpoint for the preferences alone would be a duplicate.

## Module 3 — Personal Categories

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 11 | GET | `/api/v1/categories` | UC-06 | Bearer token, role `STUDENT` | `200` category list | Personal Categories |
| 12 | GET | `/api/v1/categories/{id}` | UC-06 | Bearer token, role `STUDENT` | `200` category | Personal Categories |
| 13 | POST | `/api/v1/categories` | UC-06 | Bearer token, role `STUDENT` | `201` category | Personal Categories |
| 14 | PATCH | `/api/v1/categories/{id}` | UC-06 | Bearer token, role `STUDENT` | `200` category | Personal Categories |
| 15 | DELETE | `/api/v1/categories/{id}` | UC-06 | Bearer token, role `STUDENT` | `204` no body | Personal Categories |

**Total: 5 endpoints.** No duplicates: each path and method pair appears once.

Two decisions worth recording, because both are the kind that quietly becomes a duplicate later:

- **The list is not filtered by type and is not split into two endpoints.** A picker offers one set
  of choices, and the two types are already distinguished by the `type` field in the response. A
  `?type=` filter or a `/categories/expense` alias would be a second way to ask the same question.
- **There is no `/api/v1/profile/me/categories`.** The profile module manages one record per student
  with no identifier in the path, so ownership there is implied by the URL. A category is one of
  many and is addressed by id, so ownership has to be enforced by the query instead — which
  `CategoryRepository.findByIdAndUserId` does. Putting the list under `/profile/me` would blur that
  distinction and suggest the wrong mental model to the next module.

**Administrators are refused here (`403`).** `/api/v1/categories/**` requires the `STUDENT` role.
UC-06 is a student's own categories; the administrator's route to the same table is UC-20
(`sp_admin_upsert_default_category`) under `/api/v1/admin/**`, in module 11. Allowing an
administrator through this route would have the service write a row owned by that administrator,
silently creating a personal category through a student-facing API.

The five endpoints cover the whole of UC-06 and nothing else: `PUT` is absent (UC-06 edits values,
it does not replace a record), and `category_rules` — UC-08, AI auto-categorisation — is module 12.

## Module 4 — Transactions

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 16 | GET | `/api/v1/transactions` | UC-10 | Bearer token, role `STUDENT` | `200` transaction list | Transactions |
| 17 | GET | `/api/v1/transactions/{id}` | UC-10 | Bearer token, role `STUDENT` | `200` transaction | Transactions |
| 18 | POST | `/api/v1/transactions` | UC-07 | Bearer token, role `STUDENT` | `201` transaction | Transactions |
| 19 | PATCH | `/api/v1/transactions/{id}` | UC-10 | Bearer token, role `STUDENT` | `200` transaction | Transactions |
| 20 | DELETE | `/api/v1/transactions/{id}` | UC-10 | Bearer token, role `STUDENT` | `204` no body | Transactions |
| 21 | POST | `/api/v1/transactions/{id}/restore` | UC-10 | Bearer token, role `STUDENT` | `200` transaction | Transactions |

**Total: 6 endpoints.** No duplicates: each path and method pair appears once.

`GET` carries two query parameters — `from`, `to` and `includeDeleted` — rather than generating
extra paths. The four decisions worth recording, because each is the kind that quietly becomes a
duplicate later:

- **Delete and restore are separate operations, not a `PATCH` of a flag.** BR-09 requires the
  trailing history to show the removal and the return, and it is `sp_soft_delete_transaction` and
  `sp_restore_transaction` that append those rows. A request body able to set `isDeleted` would
  move the record without the log entry, which is the one thing the soft-delete design exists to
  prevent. So `isDeleted` is not a writable field anywhere, and the two transitions have their own
  endpoints.
- **There is no `/api/v1/transactions/deleted`, `/all` or `/list` alias.** `GET /api/v1/transactions`
  already answers all three questions: the default is the live records, `includeDeleted=true` adds
  the trash, and `from`/`to` narrow the range. Three paths would be three ways to ask one thing.
- **There is no `/api/v1/profile/me/transactions`.** Unlike the profile, a transaction is one of
  many and is addressed by id, so ownership is enforced by the query
  (`TransactionRepository`) rather than implied by the URL. Filing the list under `/profile/me`
  would suggest the wrong model to module 5 and module 6, which also address transactions by id.
- **`source` and `type` are not accepted on create or update.** `type` is the category's (BR-05,
  `transactions` has no type column), and `source` is fixed at `MANUAL` by the entity's factory.
  This is a security boundary rather than tidiness: `sp_validate_transaction` exempts `RECURRING`
  from the BR-08 future-date check, so a client able to set `source` could record an expense dated
  in the future — and a future-dated expense corrupts every balance until the date arrives.

**Administrators are refused here (`403`).** `/api/v1/transactions/**` requires the `STUDENT` role.
UC-07 and UC-10 are a student's own records; the administrator reads aggregates through UC-21 and
UC-22 under `/api/v1/admin/**`, in module 11, and has no endpoint that writes a transaction.

The six endpoints cover the whole of UC-07 and UC-10 and nothing else: `PUT` is absent (UC-10 edits
values, it does not replace a record), and the CSV import (UC-11), the recurring scheduler (UC-09),
the AI suggestion columns (UC-08) and the anomaly flags (UC-24) are later modules that write their
own rows through their own contracts.

## Module 5 — Recurring

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 22 | GET | `/api/v1/recurring-rules` | UC-09 | Bearer token, role `STUDENT` | `200` rule list | Recurring |
| 23 | GET | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer token, role `STUDENT` | `200` rule | Recurring |
| 24 | POST | `/api/v1/recurring-rules` | UC-09 | Bearer token, role `STUDENT` | `201` rule | Recurring |
| 25 | PATCH | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer token, role `STUDENT` | `200` rule | Recurring |
| 26 | DELETE | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer token, role `STUDENT` | `204` no body | Recurring |

**Total: 5 endpoints.** No duplicates: each path and method pair appears once.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **Pausing, resuming and ending are `PATCH {"status": "..."}`, not three endpoints.** They set one
  column with three values, so `/pause`, `/resume` and `/end` would be three names for one write and
  could disagree with each other. The lifecycle those values form is `ACTIVE ↔ PAUSED`, either of
  those `→ ENDED`, and `ENDED` is final — a request that would move a rule out of `ENDED` answers
  `409 RECURRING_RULE_ENDED`, because ending is how a rule that has already posted is retired (see the
  constraint on endpoint 26 below) and un-ending it would re-open a schedule whose past periods were
  deliberately abandoned. There is also no "run the rule now" endpoint: which periods are due is
  `sp_post_recurring_transactions`'s decision and a client able to trigger it would trigger posting
  for every student in the system.
- **There is no `/recurring-rules/{id}/occurrences`.** The periods a rule has covered are rows in
  `recurring_occurrences`, which the scheduler owns. A client reads their *effect* through module 4's
  `GET /api/v1/transactions`, which is the question a UI actually asks.
- **There is no `/api/v1/recurring-rules/all`, `/list` or `/api/v1/recurring` alias**, and no
  `?status=` filter. `GET /api/v1/recurring-rules` returns every status the caller owns, ordered by
  `nextRunDate`; the client filters, exactly as module 3 decided about `?type=`.
- **There is no `/api/v1/profile/me/recurring-rules`.** A rule is one of many and is addressed by
  id, so ownership is enforced by the query (`RecurringRuleRepository`) rather than implied by the
  URL — the same reasoning modules 3 and 4 recorded.
- **`type`, `startDate` and `lastRunDate` are not accepted on the write endpoints.** `type` is the
  chosen category's (BR-05), `startDate` is the rule's origin and the periods already posted are a
  function of it, and `lastRunDate` is the scheduler's cursor — a client able to move it could make
  the scheduler skip or repeat periods.
- **`dayOfMonth` and `dayOfWeek` are neither accepted nor returned.** The schema describes them as
  UI hints and the scheduler does not read either; publishing a value with no consumer, that could
  disagree with the date the rule actually runs on, would be worse than deriving a label from
  `startDate`.

**Administrators are refused here (`403`).** `/api/v1/recurring-rules/**` requires the `STUDENT`
role. UC-09 is a student's own schedule, and the scheduler posts on students' behalf; letting an
administrator through would create a rule owned by that administrator. The administrator reads
aggregates through UC-21 and UC-22 in module 11.

The five endpoints cover the whole of UC-09 and nothing else: `PUT` is absent, and the CSV import
(UC-11) and the AI suggestion columns (UC-08) are later modules that write their own rows through
their own contracts.

**A rule that has already generated transactions cannot be deleted** — it answers `409
RECURRING_RULE_IN_USE` and the remedy is `PATCH {"status": "ENDED"}`. This is not an extra endpoint,
it is a constraint on endpoint 26; the reasoning is in
[recurring.md §10](recurring.md#10-delete-apiv1recurring-rulesid). That remedy is what makes `ENDED`
final: the two operations would otherwise contradict each other, which is recorded in
[recurring.md §9](recurring.md#pausing-resuming-and-ending-are-this-endpoint).

**A pause defers its periods rather than skipping them.** No endpoint changes, but a client needs to
know it: nothing selects a `PAUSED` rule, so its cursor never advances and resuming it posts every
period whose date passed during the pause — dated on their original scheduled dates, and counting
towards those months' budgets. The wording of UC-09 reads the other way, so the divergence is recorded
as **OB-010** in [OVERNIGHT_BLOCKERS.md](../OVERNIGHT_BLOCKERS.md) rather than silently resolved;
[recurring.md §11](recurring.md#11-the-scheduler-how-a-rule-becomes-transactions) states the
consequences for a client.

## Module 6 — Budget & Notifications

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 27 | GET | `/api/v1/budgets` | UC-13 | Bearer token, role `STUDENT` | `200` budget list | Budget & Notifications |
| 28 | GET | `/api/v1/budgets/{id}` | UC-13 | Bearer token, role `STUDENT` | `200` budget | Budget & Notifications |
| 29 | POST | `/api/v1/budgets` | UC-13 | Bearer token, role `STUDENT` | `201` budget | Budget & Notifications |
| 30 | PATCH | `/api/v1/budgets/{id}` | UC-13 | Bearer token, role `STUDENT` | `200` budget | Budget & Notifications |
| 31 | DELETE | `/api/v1/budgets/{id}` | UC-13 | Bearer token, role `STUDENT` | `204` no body | Budget & Notifications |
| 32 | GET | `/api/v1/notifications` | UC-14 | Bearer token, role `STUDENT` | `200` notification list | Budget & Notifications |
| 33 | GET | `/api/v1/notifications/{id}` | UC-14 | Bearer token, role `STUDENT` | `200` notification | Budget & Notifications |
| 34 | POST | `/api/v1/notifications/{id}/read` | UC-14 | Bearer token, role `STUDENT` | `200` notification | Budget & Notifications |

**Total: 8 endpoints.** No duplicates: each path and method pair appears once.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **There is no endpoint that raises, clears or recomputes a budget alert, and this is the central
  decision of the module.** An alert belongs to the *transaction* that crossed a threshold: it is
  written by `sp_check_budget_alerts`, called from `trg_transactions_after_insert` and
  `trg_transactions_after_update` (module 4). Setting, changing or removing a budget writes **no**
  alert. A `/budgets/{id}/check` or `/budgets/recalculate` route would be a second trigger for the
  same procedure — and one that could write the alert-log row BR-12's unique key has already,
  correctly, decided against. The consequence for a client is stated in
  [budgets.md §5](budgets.md#5-setting-a-limit-raises-no-alert) and
  [notifications.md §4](notifications.md#4-how-an-alert-comes-to-exist).
- **There is no `POST /api/v1/notifications` and no `DELETE`.** Every notification is written by the
  procedure that owns it — a budget alert by `sp_check_budget_alerts`, an announcement by the
  administrator procedure in module 11, a tip or insight by later modules. Giving a student a way to
  write one would be a way to write one *to somebody else*; giving them a way to delete one would
  erase the record of having been warned.
- **Marking read is `POST /notifications/{id}/read`, not a `PATCH` of `isRead`.** The change is a
  single statement that also proves ownership (`user_id` in the same `UPDATE`), the transition is
  one-way, and the schema's `ck_notif_read` pair treats un-reading as not something that happens. A
  request body able to set the field would move the row without the ownership predicate and would
  additionally allow un-reading. There is **no** unread route, and `thereIsNoUnreadEndpoint` asserts
  it.
- **`GET /notifications/{id}` deliberately does not mark the message read.** Opening a message and
  acknowledging it are different actions; a client that merely links to one should not silently clear
  the unread marker. The two paths are two operations, not a duplicate.
- **The budget's category and month are not editable through `PATCH`.** Together with the owner they
  are the row's identity — `uk_budget_user_cat_month` is built from exactly those three columns — so
  moving a limit onto another one is a *different* budget, which `DELETE` then `POST` already
  expresses. A writable `categoryId` would additionally be a second way to put a limit on an income
  category, slipping past the BR-11 check the insert trigger makes and which no update trigger
  repeats for a value it was given at creation.
- **`spentAmount`, `remainingAmount`, `consumedPct` and `consumptionStatus` are read-only.** They are
  derived by `v_budget_consumption` and are the same figures `sp_check_budget_alerts` compares, so a
  client able to send one could show itself on track while the alert log disagreed.
- **There is no `/api/v1/budgets/all`, `/list`, `/profile/me/budgets`, `/budgets/{id}/spend`,
  `/status`, `/check` or `/budgets/alerts`**, and no `/api/v1/notifications/all`, `/list`,
  `/unread`, `/read-all` or `/profile/me/notifications`. Each named collection answers all of the
  first group; the rest are second names for a row or a write. No `?status=`, `?type=`, `?unread=`
  or `?categoryId=` filter either — the response carries the flag, the same decision module 3 made
  about `?type=`. Pagination is absent: UC-13 and UC-14 do not ask for it, and neither list is
  large.

**Administrators are refused here (`403`).** `/api/v1/budgets/**` and `/api/v1/notifications/**`
require the `STUDENT` role. A budget measures one student's spending and a notification is that
student's own message; letting an administrator through would create a limit owned by that
administrator, and an administrator has no UC-14 list of their own. The administrator reads
aggregates through UC-21 and UC-22 in module 11.

The eight endpoints cover the whole of UC-13 and UC-14's reading half and nothing else: `PUT` is
absent, and the announcement (UC-21), tip (UC-18) and insight (UC-17) generators are later modules
that write their own rows through their own contracts — though their types are already readable
through endpoint 32, because `notifications.type` is a MySQL `ENUM` and an unmapped member would fail
to load.

**A budget whose category has been retired cannot be changed** — it answers `409 CATEGORY_RETIRED`
and the remedies are to restore the category or remove the budget. This is not an extra endpoint, it
is a constraint on endpoint 30, and it is the same freeze module 5's rules are under: both
`trg_budgets_before_update` and `trg_recurring_rules_before_update` re-run their validation procedure
on every update, with no "skip the active check" escape. Deleting is **not** frozen, because a delete
fires no `BEFORE UPDATE` trigger — which is why removal stays the student's way out of the state. See
[budgets.md §9](budgets.md#a-budget-whose-category-has-been-retired-cannot-be-changed).

**BR-12 bounds the notification list: at most one message per threshold, per category, per month.**
No endpoint changes — but it is why "my budget reads `EXCEEDED` and I have one notification" is a
reconcilable state rather than a bug, and why a client must not synthesise alerts from current
budget state. The mechanism is `budget_alert_log`'s
`uk_alert_budget_threshold (budget_id, threshold_type)` with `INSERT IGNORE`; see
[notifications.md §5](notifications.md#5-br-12-one-message-per-threshold-per-category-per-month).

## Module 7 — Dashboard

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 35 | GET | `/api/v1/dashboard` | UC-12 | Bearer token, role `STUDENT` | `200` dashboard | Dashboard |

**Total: 1 endpoint.** No duplicates: one path, one method, no query parameters.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **There is no `?month=` parameter, and the absence is a correctness decision rather than an
  omission.** `v_dashboard_summary` and `v_top_category_current_month` both derive their month from
  `CURDATE()` **inside the database session** and cannot be asked about any other month. A parameter
  would therefore be accepted and ignored — or worse, honoured for the heading while the figures came
  from the current month. The endpoint takes none and states which month it answered for, in
  `periodMonth`. A month-selectable view is a report, which is UC-15 and module 8, reading different
  views. Verified by `DashboardApiIT#theMonthIsNotSelectable`.
- **There is no `?userId=` and no `/dashboard/{id}`.** The account is the bearer token's, and no
  method anywhere in the module — controller, service or DAO — takes a user id. Reading another
  student's dashboard is therefore *impossible* rather than refused, which matters more here than
  anywhere else in the API: one response carries a named student's income, spending, top category,
  habits and personal advice.
- **Budget progress bars (UC-13) are deliberately not part of the payload.**
  `GET /api/v1/budgets` already returns every limit with `spentAmount`, `remainingAmount`,
  `consumedPct` and `consumptionStatus`, computed by `v_budget_consumption`. Publishing them here too
  would be a second route to the same rows, read twice in one request, and a second place for the
  thresholds to be applied.
- **Notifications (UC-14) are deliberately not part of the payload.** A notification has state — read
  or unread — and marking one read is a write. `GET /api/v1/notifications` is where that lives; a
  dashboard that carried the list would either have to write behind its GET or show a count that goes
  stale. `DashboardApiIT#readingTheDashboardChangesNoState` asserts that nothing moves.
- **There is no write on this path at all** — no marking read, no pinning or dismissing a tip, no
  recording a view. Each state change is reachable through the endpoint that owns it: UC-14's
  `/notifications/{id}/read` and UC-18's tip actions in module 9. `DashboardApiIT` asserts the GET
  writes nothing across three calls.
- **There is no `/api/v1/dashboard/summary`, `/top-category`, `/tips` or `/announcements`.** These
  are four blocks of one screen describing one month of one student. Four routes would make the
  screen's first paint wait on four round-trips, and — because the month is derived per call from the
  database clock — would let a client paint one month's totals beside another month's tips if the
  clock crossed a boundary between two of them. One response states the month once.
- **Nothing is recomputed in Java.** All four blocks are the schema's: `v_dashboard_summary` for the
  totals and the goal `CASE`, `v_top_category_current_month` for the top category,
  `v_dashboard_tips` for the ranking and the dismissal filter, `v_active_announcements` for the
  window. The module reads and maps; it does not add up a transaction.

**Two filters the schema leaves to the application are applied here, and both are load-bearing.**

- **`v_dashboard_tips` has no time filter.** It partitions by `period_month` for its `ROW_NUMBER` but
  returns every month a student has tips for. The DAO adds `AND period_month = :periodMonth`, bound
  to the month the summary reported. Without it, the demo account's nine tips — three months' worth —
  would all appear under this month's heading. `DashboardApiIT#tipsFromAnotherMonthAreNotShown` pins
  it.
- **`v_active_announcements` has no audience filter.** It answers "is this notice within its window",
  not "is this notice for this reader". The DAO adds `audience IN ('ALL','STUDENTS')`. Without it, a
  notice written for administrators would appear on every student's dashboard — a disclosure, not a
  cosmetic slip. `DashboardApiIT#announcementsAreFilteredByAudience` inserts one of each audience and
  requires the `ADMINS` one to be absent. There is no mirror-image case, because an administrator is
  refused this whole endpoint.

**Administrators are refused here (`403`).** `/api/v1/dashboard/**` requires the `STUDENT` role. UC-23's
usage statistics are aggregates over many students; admitting the role here would create a route that
names one student's spending, which is exactly what the rest of the administration module is careful
not to do. The rule sits in `SecurityConfig` with the other student paths and carries a comment
explaining why.

See [dashboard.md](dashboard.md) for the full contract, including the two filters above and the
frontend divergences.

## Module 8 — Reports & Export

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 36 | GET | `/api/v1/reports` | UC-15, UC-16 | Bearer token, role `STUDENT` | `200` report | Reports & Export |
| 37 | GET | `/api/v1/reports/spending` | UC-15 | Bearer token, role `STUDENT` | `200` spending series | Reports & Export |

**Total: 2 endpoints.** No duplicates: no alias path, and no third route for UC-16.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **There are two endpoints because the module's sources are not equally month-selectable, and that
  difference is a correctness constraint rather than a design preference.** `v_monthly_income_expense`
  and `v_category_month_totals` are keyed by `period_month` and answer about any month, so
  `GET /api/v1/reports?month=yyyy-MM` can review September in December.
  `v_daily_spending_current_month` and `v_weekly_spending_current_month` cannot: both derive their
  range from `CURDATE()` **inside the database session** and take no month parameter. Putting them in
  the same response would produce one payload whose totals described the requested month and whose
  bars described the current one, and no field would reveal it — every number individually correct.
  So the day/week breakdown is its own endpoint, and it **refuses** a month it cannot honour
  (`400 VALIDATION_ERROR`, field error on `month`/`from`/`to`) rather than silently answering from the
  wrong month. Verified by `SpendingReportApiIT#anotherMonthIsRefused` and
  `#aWindowOutsideTheMonthIsRefused`.
- **The six-month trend is the exception inside the first endpoint, and it is returned beside the
  selected month rather than filtered by it.** BR-17 defines it as the last six months ending at the
  **current** one: a fixed window with no parameter. Each point carries its own `periodMonth`, so
  nothing is mislabelled, and asking for an earlier month returns that month's totals and breakdowns
  beside the same six trend points. Verified by `ReportsApiIT#theTrendIsUnaffectedByTheSelectedMonth`.
- **UC-16 is not a third endpoint, and there is deliberately no `/export`.** BR-18 requires the export
  file to be generated only when the user asks and nothing to be buffered — "the file is generated at
  call time" — which is exactly what these two reads already do. An export route returning the same
  figures as CSV would be a **second way to ask one question** (section 13): the payload would be one
  of these two responses, formatted differently. A client exports what it fetched. Recorded in full in
  [`MODULE_08_REPORTS.md` §4](../modules/MODULE_08_REPORTS.md).
- **There is no `?userId=` and no `/reports/{id}`.** A report is not a stored row — it has no table,
  no procedure and no identifier — and no method at any layer takes a user id, so reading another
  student's report is *impossible* rather than refused (BR-02).
- **`percentage` is the only figure computed in Java**, because no view computes a share of a month.
  The denominator is the row sum of the block the slice belongs to, so each share is that category's
  own proportion. Shares are rounded independently to two decimals and therefore **do not in general
  sum to exactly 100** — three equal thirds read `33.33` each and total `99.99`. No slice is adjusted
  to absorb a remainder, because a slice's percentage is a property of that slice; `total` is exact.
  Verified by `ReportsApiIT#categorySharesAreEachTheirOwnAndAreRoundedIndependently`.
- **Nothing else is recomputed in Java.** The month totals come from `v_monthly_income_expense`
  (income and expense split by the category's type, BR-05; trashed records excluded, BR-09), the
  per-category totals from `v_category_month_totals`, the trend from `v_monthly_income_expense_6m`,
  and the bars from the two current-month views. The module reads and maps; it does not add up a
  transaction.
- **The weekly series is not clipped to the month, deliberately.** Each `WEEKLY` point is a real
  Monday-to-Sunday ISO week, so the first and last bars may include days from the neighbouring months
  and the series' `totalExpense` may exceed the month's expense. The DAO's predicate is an
  **overlap** (`week_start <= :to AND week_end >= :from`), not a containment — a containment test
  would silently drop the month's first and last bars. Verified by
  `SpendingReportApiIT#aWeekMayReachOutsideTheMonthAndIsNotClipped`.
- **Absent and zero are used deliberately, and differently between the two endpoints.**
  `v_monthly_income_expense` emits no row for an empty month, so the totals block's four figures are
  **absent** rather than `0.00` — "recorded nothing" and "netted to nothing" are different
  statements. `v_monthly_income_expense_6m` zero-fills because BR-17 requires all six rows, so a
  trend point is always present. The daily and weekly views return only the intervals that have
  spending, so a quiet day is **absent** rather than a zero bar. The schema decides which report fills
  its gaps, and the module does not overrule it.
- **Nothing on either path writes.** Both are `@Transactional(readOnly = true)` and there is no POST,
  PATCH, PUT or DELETE, no `/reports/list`, no `/reports/all` and no `/profile/me/reports`.

**Administrators are refused here (`403`).** `/api/v1/reports/**` requires the `STUDENT` role. UC-21's
administrative reports are sums over many students and live under `/api/v1/admin/**`; admitting the
role here would expose exactly the per-student detail those reports are built not to name.

See [reports.md](reports.md) for the full contract, including the frontend divergences and the
rendering rules the absent/zero asymmetry implies.

## Module 9 — Saving Tips

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 38 | GET | `/api/v1/tips` | UC-18 | Bearer token, role `STUDENT` | `200` one month's ranked tips | Saving Tips |
| 39 | GET | `/api/v1/tips/months` | UC-18 | Bearer token, role `STUDENT` | `200` `{months: [...]}` | Saving Tips |
| 40 | POST | `/api/v1/tips/generate` | UC-18 | Bearer token, role `STUDENT` | `200` the month's tips, after generating | Saving Tips |
| 41 | POST | `/api/v1/tips/{id}/state` | UC-18 | Bearer token, role `STUDENT` | `200` the tip, in its new state | Saving Tips |

**Total: 4 endpoints.** No duplicates: no `/{id}` read, no per-transition routes, and no export.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **There are four endpoints because UC-18 has four questions in it**, and no two of them is a
  restatement of another. `GET /tips?month=` returns one month's advice; `GET /tips/months` returns
  which months have advice, which is what a picker has to know *before* it can ask the first
  question; `POST /tips/generate` runs the generator; `POST /tips/{id}/state` records a decision the
  student made. Collapsing the last into a `PATCH` of a field and the first two into one response
  would each be one route serving two operations with different failure modes.
- **Reading does not generate, and generating is not a side effect of reading.** `GET /tips` is
  `@Transactional(readOnly = true)`; `POST /tips/generate` is the only route that calls
  `sp_generate_tips`. A student opening the tips screen therefore cannot change what is on it, which
  is what separates a review screen from an action. Verified by
  `TipsApiIT#readingAMonthDoesNotGenerateTips`.
- **`POST /tips/generate` takes no month.** It always runs for the current month as the application
  judges it (`Asia/Ho_Chi_Minh`, the same offset the DB session is pinned to via VĐ-10), so a client
  cannot pull advice about an arbitrary past month. The month is not a parameter, so it cannot be
  forged; the response names the month it used. Verified by
  `TipsApiIT#generatingReturnsTheListItProduced`.
- **Generating is idempotent, and that is the property that makes it safe to expose.**
  `sp_generate_tips` ends in `INSERT IGNORE` against `uk_tip_dedupe`, whose key is
  `user_id|period_month|tip_template_id|category_id`. A second call in the same month adds no row and
  — the part that matters — **cannot resurrect a tip the student dismissed or move one they pinned**,
  because the row's dedupe key already exists. Pressing "refresh" cannot undo the student's own
  choices. Verified by `TipsApiIT#generatingTwiceChangesNothing` and
  `#aDismissedTipStaysDismissedAcrossAGeneration`.
- **The state change is a `POST` to a `/{id}/state` sub-path, not a `PATCH` of a field**, because the
  transition writes two columns together: `ck_tip_state` pairs `PINNED` with a `pinned_at`,
  `DISMISSED` with a `dismissed_at`, and `NEW` with neither. A client that could set `pinnedAt`
  directly could produce a pinned tip with no pinned time — the row the CHECK exists to refuse. This
  is the same shape `POST /api/v1/notifications/{id}/read` uses, for the same reason. The pairing is
  asserted against the real schema by `TipsStateConsistencyIT`. See
  [tips.md](tips.md) for the full contract, including the frontend divergences.
- **Three states, one route — not a route per transition.** `PINNED`, `DISMISSED` and `NEW` are one
  column with three values, so `/pin`, `/unpin` and `/dismiss` would be three names for one write
  (§13). One consequence is worth stating: **`NEW` is how a tip is unpinned.** Dismissal is one-way —
  a `DISMISSED` tip cannot be moved back — because bringing back advice the student threw away would
  make the state meaningless. Verified by `TipsApiIT#aDismissedTipCannotBeRestored`.
- **Asking for the state a tip already has is not an error** (a second pin returns the tip and its
  original timestamp, verified by `TipsApiIT#askingForTheStateATipAlreadyHoldsIsNotAnError`). This is
  the same idempotent-read treatment `sp_mark_notification_read` gives a second mark-read.
- **A month with no tips is `200` with an empty array, never `404`** — the month exists; the student
  simply has no advice for it. `GET /tips/months` reads the same view, so the months it offers are
  exactly the months that would return something. A month whose tips were **all** dismissed drops out
  of both, because it would show an empty screen. Verified by
  `TipsApiIT#aMonthWhoseOnlyTipWasDismissedLeavesTheMonthsList`.
- **There is no `/tips/{id}` read, no create, no edit and no delete.** A tip is only ever shown as part
  of its month's ranked list — a bare `/{id}` would be the same row read without the ordering that
  gives it meaning. Every tip is written by `sp_generate_tips` applying the six rules and rendering
  the text through `fn_render_template`; nothing about the text is the student's to author, and a
  delete would remove the row whose dedupe key keeps a dismissed tip from coming back. The only
  writable column a student owns is the state.
- **Nothing is recalculated in Java.** Which tips exist, their order (pinned first, then by
  `rank_score` — BR-14), and their `potential_saving` are all the database's; the module reads
  `v_dashboard_tips` and maps. The list arrives already ranked and is passed through unaltered.

**A scheduled run drives the same generator.** `TipGenerationScheduler` runs once a day for every
active student, calling the same procedure through the same DAO, with `null` for the month so the DB
session's date is used. It is *not* a fifth endpoint and not a second implementation — it is the
timer that keeps a student's tips current without them asking, while `POST /tips/generate` is the
on-demand path for a student who wants the advice a just-recorded purchase should produce. Both are
`@ConditionalOnProperty`-gated (`campuscoin.tips.scheduler.enabled`, off in tests) and both are safe
to run repeatedly or concurrently, because idempotency is the unique key's rather than the caller's.
Whether the run holds one transaction or one per student is the module's own decision and is asserted
by `TipGenerationSchedulerTest`.

**Administrators are refused here (`403`).** `/api/v1/tips/**` requires the `STUDENT` role. A tip's
title and body are readable prose naming a category and an amount from one student's own spending, so
admitting the role would let it read a named student's figures through a route never meant to name
anyone — the same reasoning that guards `/api/v1/dashboard/**`. Administrator work on tip *templates*
(UC-20) is a different table and lives under `/api/v1/admin/**` in module 11.

## Module 10 — Bookmarks / Notes

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 42 | GET | `/api/v1/bookmarks` | UC-19 | Bearer token, role `STUDENT` | `200` the saved items, newest first | Bookmarks / Notes |
| 43 | POST | `/api/v1/bookmarks` | UC-19 | Bearer token, role `STUDENT` | `201` the saved item | Bookmarks / Notes |
| 44 | PATCH | `/api/v1/bookmarks/{id}` | UC-19 | Bearer token, role `STUDENT` | `200` the saved item, with its note | Bookmarks / Notes |
| 45 | DELETE | `/api/v1/bookmarks/{id}` | UC-19 | Bearer token, role `STUDENT` | `204` no body | Bookmarks / Notes |

**Total: 4 endpoints.** No duplicates: no `/{id}` read, no `/note` sub-resource, and no change of
target.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **There are four endpoints because UC-19 has four acts in it** — B1 save, B2 note it, B3/B4 read the
  list, B4 let it go — and no two of them is a restatement of another. `POST` and `GET` share one URL
  because saving and listing are two views of one collection, exactly as `/categories` and `/budgets`
  do. `PATCH /{id}` and `DELETE /{id}` share one URL because they are two different writes to one row.
- **"Un-mark" is `DELETE`, not a flag.** UC-19 B4 is "let it go when it is no longer needed", and the
  row's disappearance is that. A `PATCH {"isDeleted": true}` would add a column the schema does not
  have and a state the list would then have to remember to filter.
- **There is no `/bookmarks/{id}` read.** A saved entry is only ever shown as part of the list, which
  the module orders by when things were saved (UC-19's postcondition is that the list is there later
  and in the order the student made it). A bare `/{id}` would return the same row with none of the
  ordering that gives it meaning — the same reasoning that keeps `/tips/{id}` out of module 9.
- **There is no `/pin`, `/unpin` or `/star`.** VĐ-03 settled that "pin" and "bookmark" are different
  acts on different rows: pinning is `user_tips.state = 'PINNED'`, it controls display order, and it
  lives under `POST /api/v1/tips/{id}/state`. Bookmarking is this collection, it saves an item for
  later, and it is removed here. Adding either act to the other's URL would be the overlap VĐ-03
  exists to prevent.
- **The note is edited with `PATCH`, not by removing and saving again.** Re-creating the entry would
  give it a new `created_at`, move it to the top of a list ordered by when things were saved, and
  re-fire `trg_bookmarks_before_update`, whose job is to make a bookmark's target unchangeable.
  Verified by `BookmarksApiIT#editingANoteKeepsTheSavedTimeAndPlace`.
- **A bookmark's target cannot be changed, and the request says so.** `UpdateBookmarkNoteRequest` has
  exactly one field. `trg_bookmarks_before_update` re-checks that the target is the caller's, so a
  route promising to re-point a bookmark would promise something the schema declines.
- **Saving something already saved is `409 BOOKMARK_ALREADY_EXISTS`, not an idempotent success.**
  `uk_bookmark_dedupe` makes it impossible; answering `201` with the existing row would tell the caller
  a bookmark was made when none was, and would discard the `note` the request carried. The remedy is
  named: the item is already in the list, and `PATCH` changes its note. The same treatment
  `BudgetAlreadyExistsException` gives the same kind of collision for UC-13.
- **Un-marking is idempotent (`204`, twice).** "It is not in my list" is the end state the caller
  asked for, so a retry or two devices acting at once settle on it rather than reporting a failure —
  the treatment a second logout gets. Verified by `BookmarksApiIT#unmarkingIsIdempotent`.
- **A tip that is not the caller's answers the same `404` as a tip that does not exist.** BR-02 is the
  trigger's (`trg_bookmarks_before_insert`), and its `45000` is reported exactly as the foreign key's
  `23000` is, so the endpoint cannot be used to enumerate other students' tip identifiers one request
  at a time (section 7.5). Verified by
  `BookmarksApiIT#anotherStudentsTipIsIndistinguishableFromAMissingOne`.
- **No endpoint takes a user id.** The account comes from the verified bearer token and every query is
  bound with the caller's id, so reading another student's saved list is *impossible* rather than
  refused (BR-02). Nothing on either write path loads a tip, so this module cannot make a second,
  weaker decision about who owns the item it is saving.
- **A dismissed tip stays in the saved list.** BR-14's dismissal rule is applied where the tips are
  read; the saved list is a different question. Keeping an item and displaying it are different acts
  (VĐ-03), and B4's remedy for an entry no longer wanted is un-marking it — which is a request only
  the student can make. Filtering the row out here would remove something the student did not remove.
  Verified by `BookmarksApiIT#aDismissedTipStaysInTheSavedList`.
- **The `INSIGHT` branch is refused, not served, and the refusal names the reason.** `item_type` is
  `ENUM('TIP','INSIGHT')` and UC-19 B1 says "a tip or an insight", but insights are **UC-17, inside
  module 12, which is locked** — and `insights` has no read path anywhere in the repository: no view
  in `db/02_views.sql`, no endpoint. `POST` therefore accepts `itemType` and answers `INSIGHT` with a
  `400` field error naming UC-17, rather than exposing a locked module's contract or narrowing the
  enum into a JSON parsing failure that would call a real column value "invalid". This is the same
  treatment module 9 gives `LOW_SAVINGS_RATE`: the value exists in the schema and the module records
  what it does not serve. Verified by `BookmarksApiIT#anInsightIsRefusedByName`; recorded as an open
  blocker in [`OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md).
- **The note is encrypted at rest.** `bookmarks.note` holds a Base64 AES-256-GCM envelope; the service
  encrypts on write and the mapper decrypts on read, so a direct `SELECT` does not reveal what the
  student typed. Verified at rest and through the API by
  `BookmarksApiIT#aNoteIsCiphertextAtRestAndPlaintextThroughTheApi`, and against the log by
  `#aNoteNeverReachesTheLog`. See [../SECURITY.md](../SECURITY.md) §12.

**Administrators are refused here (`403`).** `/api/v1/bookmarks/**` requires the `STUDENT` role. A
saved entry is the same readable prose about one student's spending that the tips rule protects, plus
text the student typed, so admitting the role would let it reach a named student's private jottings
through a route with no use case for them. There is no administrative counterpart at all — no view
over `bookmarks` in `db/02_views.sql` and no UC-20…UC-23 operation on the table — so refusing the role
costs nothing.

See [bookmarks.md](bookmarks.md) for the full contract, including the frontend divergences.

## Module 11 — Administration

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 46 | GET | `/api/v1/admin/users` | UC-22 B1 | Bearer token, role `ADMIN` | `200` the accounts | Administration |
| 47 | POST | `/api/v1/admin/users/{id}/status` | UC-22 B3 | Bearer token, role `ADMIN` | `200` the account, in its new state | Administration |
| 48 | POST | `/api/v1/admin/users/{id}/password-reset` | UC-22 B4 | Bearer token, role `ADMIN` | `202` a message, never a token | Administration |
| 49 | GET | `/api/v1/admin/categories` | UC-20 | Bearer token, role `ADMIN` | `200` the default categories | Administration |
| 50 | POST | `/api/v1/admin/categories` | UC-20 | Bearer token, role `ADMIN` | `201` the category | Administration |
| 51 | PATCH | `/api/v1/admin/categories/{id}` | UC-20 | Bearer token, role `ADMIN` | `200` the category | Administration |
| 52 | GET | `/api/v1/admin/announcements` | UC-21 | Bearer token, role `ADMIN` | `200` every announcement | Administration |
| 53 | POST | `/api/v1/admin/announcements` | UC-21 B1 | Bearer token, role `ADMIN` | `201` the announcement | Administration |
| 54 | PATCH | `/api/v1/admin/announcements/{id}` | UC-21 B2 | Bearer token, role `ADMIN` | `200` the announcement, in its new state | Administration |
| 55 | GET | `/api/v1/admin/tip-templates` | UC-21 | Bearer token, role `ADMIN` | `200` the templates | Administration |
| 56 | POST | `/api/v1/admin/tip-templates` | UC-21 B3 | Bearer token, role `ADMIN` | `201` the template | Administration |
| 57 | PATCH | `/api/v1/admin/tip-templates/{id}` | UC-21 B4 | Bearer token, role `ADMIN` | `200` the template | Administration |
| 58 | GET | `/api/v1/admin/settings` | UC-23 / VĐ-05 | Bearer token, role `ADMIN` | `200` every setting | Administration |
| 59 | PATCH | `/api/v1/admin/settings/{key}` | UC-23 / VĐ-05 | Bearer token, role `ADMIN` | `200` the setting, with its new value | Administration |
| 60 | GET | `/api/v1/admin/stats` | UC-23 | Bearer token, role `ADMIN` | `200` one aggregate row | Administration |
| 61 | GET | `/api/v1/admin/stats/top-categories` | UC-23 | Bearer token, role `ADMIN` | `200` the ranked categories | Administration |

**Total: 16 endpoints.** No duplicates: no `PUT` and no `DELETE` anywhere, no `GET /admin/users/{id}`,
no `/admin/audit-log`, no per-state `/activate` or `/deactivate` sub-resources, no search or filter
parameters on 46, and no `/admin/insights/**`, `/admin/anomalies/**` or `/admin/ai/**`.

The decisions worth recording, because each is the kind that quietly becomes a duplicate later:

- **The database was already complete for this module, and that shapes every choice below.** All
  eight administrative procedures exist, each calling `sp_require_admin` as its single authorisation
  gate and writing its own `admin_audit_log` row; the four admin views exist. Module 11 adds **no
  table, view, procedure or trigger** — the work is the endpoint set, the refusal classification, and
  the "what must never leave the server" boundary. `git diff --stat -- db/` is empty, and that is a
  review line rather than an accident.
- **Every write is a `CALL`, never a `save`.** The application account holds direct table grants, so a
  `JpaRepository#save` on `users` or `categories` would write the row while **bypassing
  `sp_require_admin` and leaving no audit row**. `CategoryRepository`, `UserRepository` and
  `SystemSettingRepository` are therefore read-only in this module, and each administrative write has
  a `*ProcedureDao`. (`CategoryService` still legitimately uses `save` for *personal* rows — UC-20's
  row belongs to nobody, which is what BR-06 guards.) This is also what preserves OB-005's claim: no
  unaudited write path exists in the API.
- **47 is `POST .../{id}/status`, not `PATCH .../{id} {status}`.** The write is a *transition* whose
  side effects exceed the named column: `sp_set_user_status` revokes every open session with
  `revoked_reason = 'ADMIN_DISABLE'` and bumps `token_version`, so a token issued before the disable
  stops working immediately (BR-06). This is the shape `POST /notifications/{id}/read` and
  `POST /tips/{id}/state` already use. It returns the account in its new state, which is why there is
  no `GET /admin/users/{id}`.
- **48 is not a duplicate of `POST /auth/password-reset/request`.** That one is public, addressed by
  *email*, and answers identically whether the address exists (BR-04 anti-enumeration). This one is
  ADMIN-only, addressed by *id*, answers `404` for a missing target (the caller is entitled to know),
  and leaves an audit row. Same table, two use cases, two disclosure policies. The response never
  carries the token: the raw link goes only to the notifier port, and the row stores a 64-hex
  `token_hash`, exactly as the public route does.
- **Reading a user list discloses whether an id exists, and that is intended here.** `404` on 47 and
  48 tells an administrator which ids are real. Module 10 deliberately makes "not yours" and "does not
  exist" look identical; the difference is who is asking. The disclosure is safe *only* while the
  `hasRole("ADMIN")` rule stands ahead of every other rule in `SecurityConfig` — which is what
  `AdminSecurityIT` asserts on all 16 routes.
- **49 exists because no other route can serve it.** `GET /api/v1/categories` is `STUDENT`-only and
  its `findVisibleToUser` merges the caller's own rows with the defaults; an administrator needs
  exactly `user_id IS NULL`, which no student route returns.
- **52 is not the dashboard's announcement slice.** `DashboardViewDao` reads
  `v_active_announcements`, adds an `audience IN ('ALL','STUDENTS')` filter and drops `audience`. An
  administrator must see `ADMINS` rows, inactive rows and out-of-window rows in order to toggle 54 —
  reusing the view would be a duplicate capability with a wrong answer. 52 reads the table.
- **54 writes `isActive` only.** `announcements` has no content-update procedure, while
  `tip_templates` does have an upsert — the asymmetry is deliberate. Content is create-once; a typo is
  fixed by posting a corrected notice and deactivating the old one. `UpdateAnnouncementRequest` has
  exactly one field. This is also what keeps *every* administrative write on a procedure, and so
  always behind `sp_require_admin` with an audit row.
- **Tip-template `code` is immutable, and the silent path was the dangerous one.**
  `sp_admin_upsert_tip_template`'s UPDATE branch does not touch `code`, so a `PATCH` carrying a
  different one would **succeed and silently ignore it** — a client told "saved" while nothing moved.
  The service loads the row first: a different code is `409 TIP_TEMPLATE_CODE_IMMUTABLE`; an equal one
  is accepted as a no-op so a client can round-trip a full representation. A duplicate code on insert
  is `409 TIP_TEMPLATE_CODE_TAKEN`, classified from `uk_tip_template_code` by constraint name.
  Verified by `AdminTipTemplateApiIT#aDifferentCodeIsRefusedAndTheStoredCodeIsUnchanged`.
- **`condition_params` is deliberately not exposed.** The schema does not document its meaning and
  nothing in this build reads it; four of the seven seeded templates carry one. Publishing a JSON blob
  nothing interprets would invite a client to depend on it. Recorded as a follow-up rather than
  guessed at, and asserted absent at every depth by `AdminTipTemplateApiIT#theListPublishesExactlyTheDocumentedFields`.
- **59 puts the key in the path, not the body**, so one setting occupies one URL and the allow-list is
  discoverable from the routes alone. Six keys are adjustable
  (`budget.near_threshold_pct`, `budget.exceeded_threshold_pct`, `insight.spike_threshold_pct`,
  `insight.spike_baseline_months`, `tips.max_dashboard`, `auth.reset_token_ttl_minutes`); the other ten
  answer `409 THRESHOLD_NOT_ADJUSTABLE`, and every row says which it is through its `adjustable` flag.
  The range and shape checks are Java's; `sp_admin_set_threshold` keeps the authoritative allow-list.
- **The two `insight.*` keys are adjustable, not locked.** An earlier reading held them to be
  module-12 surfaces. They are not: `sp_generate_tips` reads `insight.spike_threshold_pct` to decide
  BR-15's category-spike tip, and `v_category_spend_trend` joins both keys — that is UC-25/BR-15 spike
  detection, **shipped in module 9** and pinned by `TipsRuleCoverageIT`. Refusing them would leave a
  shipped behaviour permanently untunable, against VĐ-05. Module 12's own surfaces — the `insights`
  table, UC-17 — have no settings key and no route here, and
  `AdminStatsApiIT#theInsightsFigureIsTheTablesOwnCount` pins that: the statistic is the table's own
  row count (three on a seeded database, because `db/06_demo.sql` calls `sp_generate_monthly_insight`
  for three demo months) and nothing in this module moves it.
- **60 and 61 stay separate** because they are two views of two shapes: `v_admin_usage_stats` is one
  scalar aggregate row, `v_admin_top_categories` is a ranked list of every category. Merging would
  nest a list in a scalar row or drop `category_id`. Both come from views; nothing is counted in Java.
- **`v_admin_top_categories` has no `ORDER BY`, so the DAO supplies a total order** —
  `txn_count DESC, total_amount DESC, category_id ASC`, with the id as final tie-break, or two equal
  categories could swap between two identical calls. **No `LIMIT`**: the view's definition is every
  category, unused ones included, which is the more useful answer for a review screen. Verified by
  `AdminStatsApiIT#twoIdenticalCallsReturnTheSameOrder` and `#anUnusedCategoryStillAppears`.
- **Reading is not a write here either.** 46, 49, 52, 55, 58, 60 and 61 are
  `@Transactional(readOnly = true)` and touch only views, tables and the already-mapped
  `SystemSetting` entity. Every `CALL` is plain `@Transactional`, because MySQL refuses any `CALL` on a
  read-only connection.
- **`AdminUserResponse` publishes eight fields and no more**: `id`, `email`, `fullName`, `role`,
  `status`, `academicYear`, `lastLoginAt`, `createdAt`. `email` is published because the admin screens
  list and search by it and the reset flow is addressed to it. `lastLoginAt` answers UC-23's notion of
  an active user. `password_hash` (BR-01) and **`token_version`** are excluded structurally: the
  projection record has no component for either. `token_version` is the entire security meaning of a
  JWT's `tv` claim — publishing it would let an attacker decide whether a stolen token is still live.
  `AdminSecurityIT` scans the raw JSON of every response for the forbidden key set.
- **No response in this module carries a monetary amount or a student's own figures.** The two
  statistics endpoints return `SUM`s over many students; 46 returns account metadata. Nothing here
  is a per-student money read. See OB-013 for the plaintext-aggregate exposure those `SUM`s rest on —
  a deferred decision this module serves rather than hides.
- **`DELETE` is absent from the whole module, and each absence has a named reason.** There is no
  `DELETE /admin/users/{id}` (VĐ-06: an administrator only *sends* a reset link; no procedure, no use
  case), no delete for categories (retirement is `PATCH {isActive: false}`, the same answer BR-07 gets
  in module 3), and no delete for announcements or tip templates (no procedures, and
  `user_tips.tip_template_id` is `ON DELETE RESTRICT` — `LOW_SAVINGS_RATE` is legitimately
  seeded-but-unused).
- **`GET /admin/audit-log` does not exist.** UC-22 B5 requires the administrator to *log*, not to
  view; there is no view over `admin_audit_log` in `db/02_views.sql` and no endpoint. The audit trail
  is write-only from the application's side, and this is the owner decision recorded in the module
  report.

**Administrators are admitted, students are refused (`403`).** `/api/v1/admin/**` requires the
`ADMIN` role, and `SecurityConfig`'s rule sits ahead of the student routes so a new endpoint added
under this prefix inherits it. `AdminSecurityIT` asserts both directions — student token →
`403 ACCESS_DENIED`, no token → `401 UNAUTHENTICATED` — over all 16 method+path pairs, so an endpoint
added outside the rule fails a test rather than shipping an unguarded surface. `sp_require_admin`
checks the role **and** an `ACTIVE` status in `users` on every write, so a token held by an
administrator disabled after it was issued is refused by the database as well as by the filter.

See [administration.md](administration.md) for the full contract.

## Module 12 — Optional / Advanced

Seven features from the SRS's optional block: CSV import (UC-11), AI categorisation (UC-08), monthly
insight (UC-17), unusual/duplicate detection (UC-24), forecast (UC-25), and recent activity (UC-26).
They are grouped in one module because the SRS groups them, but they are six independent surfaces with
their own packages.

| # | Method | Endpoint | UC | Auth | Success | Module |
|---|--------|----------|----|------|---------|--------|
| 62 | POST | `/api/v1/imports` | UC-11 A1 | Bearer token, role `STUDENT` | `201` the stored preview | CSV import |
| 63 | GET | `/api/v1/imports` | UC-11 | Bearer token, role `STUDENT` | `200` the import history | CSV import |
| 64 | GET | `/api/v1/imports/{batchId}` | UC-11 B5 | Bearer token, role `STUDENT` | `200` the batch and its rows | CSV import |
| 65 | PATCH | `/api/v1/imports/{batchId}/rows/{rowId}` | UC-11 B6 | Bearer token, role `STUDENT` | `200` the row, in its new state | CSV import |
| 66 | POST | `/api/v1/imports/{batchId}/commit` | UC-11 B9 | Bearer token, role `STUDENT` | `200` the batch after importing | CSV import |
| 67 | POST | `/api/v1/imports/{batchId}/cancel` | UC-11 A2 | Bearer token, role `STUDENT` | `200` the cancelled batch | CSV import |
| 68 | POST | `/api/v1/ai/suggest-category` | UC-08 | Bearer token, role `STUDENT` | `200` a proposal, or `NONE` | Categorisation |
| 69 | GET | `/api/v1/insights` | UC-17 | Bearer token, role `STUDENT` | `200` the month's insight | Insights |
| 70 | GET | `/api/v1/insights/months` | UC-17 | Bearer token, role `STUDENT` | `200` the months that have one | Insights |
| 71 | POST | `/api/v1/insights/generate` | UC-17 | Bearer token, role `STUDENT` | `200` the insight after generating | Insights |
| 72 | GET | `/api/v1/anomalies` | UC-24 | Bearer token, role `STUDENT` | `200` the flagged records | Anomalies |
| 73 | POST | `/api/v1/anomalies/scan` | UC-24 | Bearer token, role `STUDENT` | `200` the scan's tally | Anomalies |
| 74 | GET | `/api/v1/forecast` | UC-25 | Bearer token, role `STUDENT` | `200` the projection | Forecast |
| 75 | GET | `/api/v1/recent-activity` | UC-26 | Bearer token, role `STUDENT` | `200` the caller's activity | Recent activity |
| 76 | POST | `/api/v1/recent-activity` | UC-26 | Bearer token, role `STUDENT` | `201` the recorded entry | Recent activity |

**Total: 15 endpoints.** No duplicates: no `/{id}` read on any of the four collections, no alias path,
no `PUT` or `DELETE`, no multipart upload, and no second route for a UC that already has one. Each
decision below is the kind that quietly becomes a duplicate later.

Module 12 is the one module the project brief called **locked**, and the lock is why several things
that would otherwise be reasonable are absent. Nothing here is a route onto the M1–M11 surfaces that
were approved earlier: the module adds tables and procedures only where the schema had already
reserved them, and the six packages are new.

### The AI boundary — the rule every AI endpoint obeys

Three of these surfaces involve a model: UC-08 (suggest a category), UC-17 (write the month's
narrative). The SRS and the project's security rules fix the flow, and it is enforced by the shape of
the code rather than by convention:

```
Angular → Spring Boot → this backend reads and filters the student's own rows
                       → a prepared context object → the AI provider
       ← this backend validates the answer ←
```

- **The provider never sees the database.** `AiSuggestionPort` (the port the services call) has no
  repository: an implementation can send only what a service deliberately handed it and cannot fetch
  anything for itself.
- **The key never leaves the server.** `GEMINI_API_KEY` is read from the environment, is never
  written to `application.yml`, never stored in MySQL, never put in a JWT and never sent to Angular.
  It is not logged.
- **No credential is a supported deployment.** With `GEMINI_API_KEY` unset the application starts
  normally and installs `NoopAiSuggestionPort`: UC-08 falls back to the student's own learned rules
  and UC-17 keeps the `RULE_BASED` summary a stored procedure already wrote. Nothing is faked — the
  `generatedBy` column records `RULE_BASED` rather than `AI`.
- **The answer is advice, and the API says so.** A suggestion never files a record on its own, and an
  insight is advisory rather than financial advice (BR-13).
- **`ai.enabled` and `ai.send_aggregates_only` are respected.** They live in `system_settings` and are
  read before a call is made.

### The `db/` changes Module 12 carries

Unlike Module 11, this module **does** change the database, and two of those changes are worth a
review line because they are corrections rather than additions:

- **`sp_flag_transaction` (new)** — UC-24's write path. The three anomaly columns (`is_flagged`,
  `flag_type`, `flag_note`), `ix_txn_flagged` and the two `anomaly.*` settings existed in the schema
  from the start, but no procedure read or wrote them. The procedure is the sole write path so that
  the ownership check (`BR-02`) lives in the database, next to `fk_txn_user`, rather than being
  restated in Java. **It is not reachable as a client-supplied flag**: the API only ever passes a
  value its own detector computed, which is the UC-24 instruction "do not allow the client to
  arbitrarily set anomaly flags" made structural.
- **`sp_apply_csv_batch` counter fix** — the commit path derived the duplicate count by subtracting
  the imported and error rows from the total. That is wrong, because the walk it subtracted from only
  visits rows the preview had already left `VALID`: a row refused in the preview (an unreadable date,
  a typo in the amount) was counted as "you already recorded this". The three counters are now read
  directly from `import_rows`, so the preview's own counter refresh and this commit agree by
  construction — one definition per counter, both paths using it.
- **`insights`** and **`import_batches` / `import_rows`** tables are read and written through existing
  procedures (`sp_generate_monthly_insight`, `sp_apply_csv_batch`); no new table was added for the
  module.
- `db/merged/campuscoin_full.sql` stays in lockstep with `db/03_procedures.sql`. It is what the
  integration suite loads, so the two files are the same schema seen twice, not a script and its
  snapshot.

### The decisions worth recording

- **The commit is `POST .../commit`, not `PATCH {status}`.** A commit steps through every importable
  row and generates transactions, then rewrites the batch's counters — its effect exceeds any single
  column, and the student is asking for the work to be done, not naming a state. Same shape as
  `POST /notifications/{id}/read` and `POST /anomalies/scan`.
- **A CSV file is sent as JSON text, not as a multipart upload.** There is no `MultipartFile`, no
  multipart configuration and no over-size handler anywhere in this build; adding all three for one
  endpoint would leave the refusal a multipart route is most likely to hit answered by Spring's
  default rather than by this API's error contract. `POST /api/v1/imports` takes
  `{"filename": "...", "content": "..."}`.
- **An unreadable row does not fail the file.** It is stored as an `ERROR` row carrying a sentence in
  the student's terms, and the rows around it are previewed normally — so a file that will import
  nothing is still a preview, and the student can see which rows to fix. This is UC-11's "invalid rows
  identifiable, valid rows importable".
- **A duplicate is decided from the student's own data and cannot be set from a request.** The
  detector compares a row against the student's own records on category, amount and a date within a
  few days. Nothing in the request names a flag, and nothing in the request names a batch's status.
- **The scan is explicit (`POST /anomalies/scan`), the read is not (`GET /anomalies`).** Reading
  flagged records never writes; asking for a scan is a separate, deliberate call that examines the
  student's own history and rewrites the marks. A `GET` that mutated would be a `GET` a browser
  prefetch could trigger.
- **The forecast has no `{month}` and no `?month=`.** The month is the one after the month in progress,
  which is what UC-25 asks for and what the schema's stored projection is keyed on — a documented
  judgement rather than a client choice. The months the estimate rests on are published as
  `recentMonths` inside the response rather than as a separate route.
- **`GET /recent-activity` is not `GET /transactions?sort=recent`.** It is the student's own log of
  having *opened or changed* a record — a different table (`recent_activity`), a different fact, and a
  different owner. `POST /recent-activity` is what the client calls when a student opens or edits a
  transaction; it records an entry, it does not change the transaction.
- **UC-08 stores what it learns, and the learning is the point.** Each imported row — and each filing
  — maps a description to the category it was filed under, in `category_rules`, so the next record
  from the same merchant is suggested correctly. `RULE` beats `AI` in the response's `source`: a
  mapping the student taught wins over a model's guess. The preserved example is "Campus Cafe → Food".
- **The insight is generated through a procedure that already existed.** `sp_generate_monthly_insight`
  writes the `RULE_BASED` figures; the AI narrative is layered on top by the backend and never
  replaces them, so the numbers are always the database's own and the prose is always marked with its
  author.

### Non-API paths


These are served for development and operations. They are not part of the application contract and
no frontend depends on them.

| Path | Purpose |
|------|---------|
| `/api-docs` | OpenAPI 3 document |
| `/swagger-ui.html` | Swagger UI (redirects to `/swagger-ui/index.html`) |
| `/actuator/health`, `/actuator/info` | Readiness and build information |

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for a
  frontend developer: base URL and environments, authentication, the Angular interceptors to
  install, the common error contract, the data ownership rule, the full enum reference, the
  per-module endpoint reference, the integration flows and the master quick-reference table of all
  76 operations.
- [authentication.md](authentication.md) — request and response contract for endpoints 1–7,
  including Angular integration notes.
- [profile.md](profile.md) — request and response contract for endpoints 8–10, including Angular
  integration notes.
- [categories.md](categories.md) — request and response contract for endpoints 11–15, including
  Angular integration notes.
- [transactions.md](transactions.md) — request and response contract for endpoints 16–21, including
  Angular integration notes and the divergences between the current Angular mock model and this
  contract.
- [budgets.md](budgets.md) — request and response contract for endpoints 27–31, including Angular
  integration notes and the divergences between the current Angular mock model and this contract.
- [notifications.md](notifications.md) — request and response contract for endpoints 32–34, how a
  budget alert comes to exist, and the BR-12 bound on how many a student can receive.
- [dashboard.md](dashboard.md) — request and response contract for endpoint 35, the two filters the
  dashboard applies that the views do not, and why budget progress and notifications are not repeated
  there.
- [reports.md](reports.md) — request and response contract for endpoints 36–37, why the month is
  selectable for one and not the other, the absent-versus-zero asymmetry, and why UC-16 has no route
  of its own.
- [recurring.md](recurring.md) — request and response contract for endpoints 22–26, the scheduler
  that turns due rules into transactions, and the recurring fields the current Angular mock model
  gets wrong.
- [tips.md](tips.md) — request and response contract for endpoints 38–41, how a tip comes to exist and
  why reading never generates one, and the three rules the six templates apply.
- [bookmarks.md](bookmarks.md) — request and response contract for endpoints 42–45, the VĐ-03
  distinction between pinning and bookmarking, and why an insight is refused rather than saved.
- [administration.md](administration.md) — request and response contract for endpoints 46–61, why
  every write is a `CALL` to a procedure that audits itself, and the fields that must never leave the
  server.
- [imports.md](imports.md) — request and response contract for endpoints 62–67, the JSON-text upload,
  the preview/commit/cancel workflow, and how a duplicate is decided.
- [ai-and-insights.md](ai-and-insights.md) — request and response contract for endpoints 68–71, the
  AI boundary (Angular → Spring Boot → provider, with the context prepared by this backend and the
  answer validated on return), the `RULE`-over-`AI` precedence, and the `generatedBy` field BR-13
  turns on.
- [advanced.md](advanced.md) — request and response contract for endpoints 72–76: anomaly flagging,
  the forecast, and recent activity.
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints.
