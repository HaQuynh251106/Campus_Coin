# Campus Coin — API Inventory

Every endpoint the backend exposes, with the use case that requires it and whether it is public.

This file is the authoritative list. An endpoint that is not in this table does not exist, and
adding one requires a use case that asks for it. Several capabilities the database has are
**deliberately not exposed**: there is no token refresh endpoint, no change-password endpoint, no
session-listing endpoint and no `/users/*` alias, because UC-01, UC-02, UC-03 and UC-05 do not
define those flows. "The column exists" is not a reason to build an API.

Base path: `/api/v1`. All endpoints consume and produce `application/json`.

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

### Non-API paths

These are served for development and operations. They are not part of the application contract and
no frontend depends on them.

| Path | Purpose |
|------|---------|
| `/api-docs` | OpenAPI 3 document |
| `/swagger-ui.html` | Swagger UI (redirects to `/swagger-ui/index.html`) |
| `/actuator/health`, `/actuator/info` | Readiness and build information |

## Related documentation

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
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints.
