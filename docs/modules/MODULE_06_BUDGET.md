# Module 6 — Budget & Notifications (UC-13, UC-14)

**Status:** DONE WITH DEFERRED NON-CRITICAL ITEMS

A student sets a monthly spending limit for one expense category, changes it, removes it, and is told
when spending approaches or passes it. Eight endpoints: five for the limits (UC-13) and three for the
messages (UC-14).

**This module's central finding is architectural, and it changed what was built.** A budget alert is
not raised by anything this module does. It is raised by the *transaction* that crosses a threshold —
`sp_check_budget_alerts`, called from `trg_transactions_after_insert` and `trg_transactions_after_update`
in module 4. So there is no "check", "recalculate" or "raise alert" endpoint here (§3.1), and setting,
changing or removing a limit writes no notification at all. A client that synthesises alerts from
current budget state is wrong about both the wording and the "told once" semantics (§5).

**Two items need the owner's attention**, and neither is a missing feature:

1. **A retired category freezes its budgets, exactly as it freezes module 5's rules** (§5.2). The
   first draft of this module's documentation claimed the opposite — that a budget stays editable
   the way module 4's transactions do. Reading `trg_budgets_before_update` showed the claim was
   wrong, a test was written to settle it empirically, and the test failed against the original
   documentation and passed against the trigger's actual behaviour. The service was then changed to
   pre-check the condition and answer `409 CATEGORY_RETIRED` with both remedies, which is what module
   5 does for the identical constraint. Recorded as **OB-011**; `db/` is frozen by §4, so the
   constraint itself is documented rather than changed.
2. **The frontend's budget alerts must be deleted, not adapted** (§5.3). `BudgetService.getBudgetAlerts()`
   synthesises alert objects and their user-facing prose client-side. That is a second, divergent
   source of text for an event the server already words, and it cannot represent BR-12 — the alert is
   a row that was written once, not a condition recomputed on every reload.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-13 | Set a monthly spending limit for one expense category; list the month's limits with each one's consumption; read one; change the limit; remove the limit |
| UC-14 | Be told when spending reaches the near threshold and when it passes the limit; list the messages; read one; mark one read; the read state is one-way |
| UC-13 BA | The spend figures are computed by `v_budget_consumption` on every read, not stored, so a screen never shows a stale total |
| UC-14 BA | A threshold alerts at most once per category per month (BR-12), enforced by `uk_alert_budget_threshold` and `INSERT IGNORE` |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` on any request | The owner is the account in the bearer token, which is what makes BR-02 structural rather than a comparison someone has to remember |
| `categoryId` or `periodMonth` on update | Together with the owner they **are** the row: `uk_budget_user_cat_month` is built from exactly those three columns. Moving a limit onto another is a different budget, which delete-then-create already expresses — and a writable `categoryId` would be a second way to put a limit on an income category, slipping past the BR-11 check the insert trigger makes and which no update trigger repeats |
| `spentAmount`, `remainingAmount`, `consumedPct`, `consumptionStatus` on any request | Derived by `v_budget_consumption`, and the same figures `sp_check_budget_alerts` compares. A client able to send one could show itself on track while the alert log disagreed |
| `type` on any request | A budget is only ever on an expense category (BR-11), enforced by `sp_validate_budget` through the insert trigger. There is no value to send |
| An endpoint that raises, clears or recomputes an alert | §3.1 — the alert belongs to the transaction |
| A threshold-setting endpoint | The two thresholds live in `system_settings` and are an administrator's concern (module 11) |
| `/budgets/{id}/spend`, `/status`, `/check` | The spend figure and the status are columns of the same row, computed on every read. Each would be a second name for the row itself |
| `/budgets/recalculate`, `/budgets/alerts` | A second trigger for `sp_check_budget_alerts`, one that could write the alert-log row BR-12's unique key has already, correctly, decided against |
| `POST /api/v1/notifications`, `DELETE /notifications/{id}` | Every notification is written by the procedure that owns it. A student able to write one could write one **to somebody else**; able to delete one could erase the record of having been warned |
| Marking a notification **unread** | The transition is one-way and the database owns it. `sp_mark_notification_read` has `AND is_read = 0`, and `ck_notif_read` treats the pair as read-with-a-timestamp or unread-without-one |
| `PATCH /notifications/{id}` with `{"isRead": ...}` | The read-state change is one statement that also proves ownership (`user_id` in the same `UPDATE`). A body able to set the field would move the row without that predicate and would allow un-reading |
| `/notifications/read-all` | Not a UC-14 step, and no bulk statement exists — the only procedure is per-notification |
| `?status=`, `?categoryId=`, `?type=`, `?unread=` filters | The response carries the flag and the client already knows what it wants to show — the same decision module 3 made about `?type=` |
| Pagination | UC-13 and UC-14 do not ask for it. A student has a handful of limits and a bounded number of messages |
| `PUT` | UC-13 edits a value; it does not replace a record |
| `/budgets/all`, `/list`, `/profile/me/budgets`, `/notifications/all`, `/notifications/unread` | The collection endpoints answer all of them. One path, one question |
| A "roll the limit forward to next month" endpoint | A budget is per month by construction. Carrying a limit forward is a client action: read last month's and `POST` it |
| Email or push delivery | Not a stated requirement, and no provider is configured. The in-app list is the notification — see **OB-002** for the parallel case in module 1 |
| Real-time delivery (websocket/SSE) | Not a stated requirement, and no transport is configured |
| The announcement (UC-21), tip (UC-18) and insight (UC-17) generators | Later modules. Their `type` values are already readable here, because the column is a MySQL `ENUM` and an unmapped member would fail to load the whole list |

---

## 2. Endpoints

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 27 | `GET` | `/api/v1/budgets` | UC-13 | List the month's limits with consumption | `200` |
| 28 | `GET` | `/api/v1/budgets/{id}` | UC-13 | Read one | `200` |
| 29 | `POST` | `/api/v1/budgets` | UC-13 | Set a limit for a category and month | `201` |
| 30 | `PATCH` | `/api/v1/budgets/{id}` | UC-13 | Change the limit | `200` |
| 31 | `DELETE` | `/api/v1/budgets/{id}` | UC-13 | Remove a limit | `204` |
| 32 | `GET` | `/api/v1/notifications` | UC-14 | List the caller's messages, newest first | `200` |
| 33 | `GET` | `/api/v1/notifications/{id}` | UC-14 | Read one (**does not** mark it read) | `200` |
| 34 | `POST` | `/api/v1/notifications/{id}/read` | UC-14 | Mark one read | `200` |

All eight require `hasRole("STUDENT")`; an administrator token is `403`. Ownership is enforced by
queries that take the caller's id alongside the row's, so it is a property of the SQL rather than a
comparison a service method has to remember.

The contracts are in [`docs/api/budgets.md`](../api/budgets.md) and
[`docs/api/notifications.md`](../api/notifications.md); the inventory rows are 27–34 in
[`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md).

---

## 3. Database alignment

No schema object was created, changed or dropped. `git diff --stat db/` is empty (`db/` is untracked,
so this was confirmed by inspection). `ddl-auto: validate` holds against the running MySQL 8 in both
profiles, which is what proves the entities match the real tables.

### 3.1 What the database owns, and this module therefore does not restate

| Concern | Owner | Evidence |
|---|---|---|
| A budget is only ever on an **expense** category the caller may use, still active (BR-11, BR-02, BR-07) | `sp_validate_budget` | Called by `trg_budgets_before_insert` **and** `trg_budgets_before_update` |
| At most one limit per student, category and month (BR-11) | `uk_budget_user_cat_month` | `(user_id, category_id, period_month)` exactly |
| A month is stored as its first day | `ck_budget_month` | `DAYOFMONTH(period_month) = 1` |
| A limit is strictly positive | `ck_budget_limit` | `CHECK (limit_amount > 0)` |
| The spent figure counts only live records (BR-09) | `v_budget_consumption` | its derived `SUM` is `WHERE is_deleted = 0` |
| The status is `NEAR`/`EXCEEDED` against the configured thresholds | `v_budget_consumption` | a `CASE` over `spent / limit` using `system_settings` |
| **A threshold alerts at most once per budget per month (BR-12)** | `budget_alert_log`, `uk_alert_budget_threshold`, `INSERT IGNORE` | the procedure's `IF ROW_COUNT() > 0` guard |
| **An alert is raised by a transaction, not by a budget write** | `sp_check_budget_alerts` called from the two `transactions` triggers | **the finding of this module — §5.1** |
| The alert log cascades away with its budget | `fk_alert_budget ... ON DELETE CASCADE` | so a re-created limit can alert again |
| Read state is one-way and carries its own timestamp | `sp_mark_notification_read`, `ck_notif_read` | `UPDATE ... AND is_read = 0`, setting `is_read` and `read_at` together |
| A notification cannot be read without a timestamp, or unread with one | `ck_notif_read` | `(is_read = 0 AND read_at IS NULL) OR (is_read = 1 AND read_at IS NOT NULL)` |

The mapping is deliberately **narrower than the schema**: this module writes nothing to
`budget_alert_log`, never calls `sp_check_budget_alerts`, and never inserts, updates or deletes a
`notifications` row. `Notification` is `@Immutable` to make that structural — an accidental `save`
cannot interleave with the procedure that owns the table.

### 3.2 The view that is the module's read path

`v_budget_consumption` is the only source of every derived figure. Its SELECT list is
`budget_id, user_id, category_id, category_name, period_month, limit_amount, spent_amount,
consumed_pct, remaining_amount, consumption_status` — note that it does **not** expose
`category_icon` or `category_color`. Those two are joined from `categories` in this module's own
`BudgetConsumptionDao`, because the API publishes them and the view does not carry them. That is a
deliberate gap rather than an oversight: the view answers "how much of the limit is used", and the
icon and colour are presentation.

The view's status `CASE` checks `EXCEEDED` first, then `NEAR`, so a limit set above the exceeded
threshold cannot make `NEAR` unreachable by accident.

---

## 4. Implementation

| Concern | Where | Note |
|---|---|---|
| Five budget endpoints | `budget/controller/BudgetController` | `@AuthenticationPrincipal` on every method; no endpoint takes a user id |
| Three notification endpoints | `budget/controller/NotificationController` | Mark-read is `POST /{id}/read`, not a field write |
| Budget logic | `budget/service/BudgetService` | `ZoneId.of("Asia/Ho_Chi_Minh")` for the default month — the same zone module 4 uses and the one the DB session is pinned to (VĐ-10) |
| Notification logic | `budget/service/NotificationService` | Ownership is checked with a `COUNT` (`existsByIdAndUserId`), not a loaded entity — §4.1 |
| Refusal classification | `budget/service/BudgetWriteFailure` | By SQLSTATE, never by message — §4.2 |
| Read projection | `budget/repository/BudgetConsumptionDao` | Two native queries joining `categories` for `icon`/`color` |
| Read-only access | `budget/repository/NotificationRepository` | No write method; every query takes the owner's id |
| The one write | `budget/repository/NotificationProcedureDao` | `CALL sp_mark_notification_read(:id, :userId)` |
| Projection | `budget/mapper/BudgetMapper`, `NotificationMapper` | The single gate for what leaves the server |
| Month conversion | `BudgetMapper.toMonthString` / `toPeriodMonth` | Both directions in one class — §4.3 |
| Role rules | `auth/security/SecurityConfig` | `/api/v1/budgets/**` and `/api/v1/notifications/**` → `hasRole("STUDENT")` |

### 4.1 The mark-read path checks ownership with a COUNT, not a loaded entity

`NotificationService.markRead` calls `existsByIdAndUserId` before the procedure and loads the row
after it. That ordering is load-bearing: `sp_mark_notification_read` changes the row with SQL
Hibernate never sees, so if the ownership check had loaded the entity first, the later read would be
served from the persistence context's stale copy and the response would report the notification as
still unread. A `COUNT` populates no entity, so the row is loaded exactly once, after the write, and
shows its new `read_at`.

This is the kind of defect that would have looked like an intermittent bug — "sometimes the timestamp
is missing" — rather than a deterministic one, because it depends on whether the entity happened to be
in the context.

### 4.2 Refusals are translated by SQLSTATE, and the ordering matters

`sp_validate_budget` raises `SQLSTATE '45000'` for four different rules and is reached through
triggers rather than called. MySQL's `2601`/`3819` CHECK violations and the `1062` duplicate arrive as
different SQLSTATEs entirely. `BudgetWriteFailure` walks the cause chain:

| Recogniser | SQLSTATE | Result |
|---|---|---|
| `mentionsDuplicateLimit` | `23000` + the key's name | `409 BUDGET_ALREADY_EXISTS` — the one case with a remedy to name |
| `isSignalledRefusal` | `45000` | `409 DATA_CONFLICT` |
| `isConstraintViolation` | `23000` / `HY000` | `409 DATA_CONFLICT` |
| *(nothing)* | — | rethrown, answered as `500` rather than mislabelled |

The duplicate check runs **first**, because a duplicate is also a constraint violation and the two
must not be merged: telling a caller whose failure had nothing to do with BR-11 to go and change an
existing limit is worse than saying nothing. The name match is case-insensitive (`Locale.ROOT`)
because MySQL reports identifiers in the case the statement used, and the matched string is a schema
literal rather than translated output.

**Why this is a unit test.** The service pre-checks all three rules a client can see, so the signalled
branch is nearly unreachable through the API. `BudgetWriteFailureTest` verifies the classification
directly against the exact exception shapes MySQL and Spring produce — including
`InvalidDataAccessResourceUsageException`, which is what a `SIGNAL` arrives as and which is **not**
`PersistenceException`. Code that caught only `DataIntegrityViolationException` would let every one of
the procedure's rules escape as a `500`.

### 4.3 The month conversion lives in one place

The database stores a month as the `DATE` of its first day; the API names a month `YYYY-MM`.
`BudgetMapper` owns both directions, so no other class has to remember the convention and no path can
decide the first of the month while another decides the fifteenth. `toPeriodMonth` uses
`YearMonth.atDay(1)` rather than `LocalDate.parse(month + "-01")`, because the latter goes through a
formatter that would accept an out-of-range day — asking `YearMonth` for the first of its own month
cannot produce a date outside that month at all.

`periodMonth` is a **String** on both request types for a related reason: it is a month, not a date,
and `"2026-09-15"` should be refused rather than silently truncated. The `@Pattern` anchors the shape,
and `resolvePeriodMonth` catches the well-formed-but-impossible case (`2026-13`) as a field error.

---

## 5. Defects found and fixed

### 5.1 A budget alert is raised by a transaction, not by a budget write — and the first design assumed otherwise

The initial plan for UC-14 was a `/budgets/{id}/check`-style route: something the client calls after
changing a limit, or a service method that recomputes alerts when a budget changes. Reading
`db/04_triggers.sql` and `db/03_procedures.sql` showed that both would be wrong.

`sp_check_budget_alerts(user_id, category_id, month)` is called from
`trg_transactions_after_insert` and `trg_transactions_after_update`, guarded by
`IF NEW.is_deleted = 0`. Nothing calls it from the budget side. So:

| Action | Alert written? |
|---|---|
| Record an expense that crosses a threshold | **Yes** |
| Set a limit | No |
| Change a limit | No |
| Remove a limit | No |
| Import spending (module 12) | Yes, because it writes ordinary transactions |

Two consequences were designed in rather than documented away:

1. **No alert endpoint exists.** A `/check` or `/recalculate` route would be a second trigger for the
   same procedure — one that could write the alert-log row `uk_alert_budget_threshold` has already,
   correctly, decided against.
2. **The test fixtures had to be transactions.** Every UC-14 test raises its alert by recording an
   expense through module 4's endpoint, never by inserting a `notifications` row. A test that inserted
   one directly would prove the mapper works and nothing about UC-14. It also means the read-path
   tests double as proof of the flow the use case describes.

The user-visible state this creates — a budget reading `EXCEEDED` with no `BUDGET_EXCEEDED`
notification, because the limit was lowered below spending that had already happened — is described
in [`docs/api/budgets.md` §5](../api/budgets.md#5-setting-a-limit-raises-no-alert) as a reconcilable
state rather than a bug, and pinned by `settingALimitRaisesNoAlert` and
`loweringALimitRaisesNoSecondAlert`.

### 5.2 A retired category freezes its budgets too — and the first draft said it did not

This module's API document was first written claiming that a budget whose category was retired later
remains editable, "unlike module 5's rules", because a limit change does not choose a category again.
That reasoning is about what the *request* does. The trigger cares about what the *row* holds.

`trg_budgets_before_update` is:

```sql
CREATE TRIGGER trg_budgets_before_update
BEFORE UPDATE ON budgets FOR EACH ROW
BEGIN
  CALL sp_validate_budget(NEW.user_id, NEW.category_id);
END
```

and `sp_validate_budget` signals `'BR-07: category has been disabled'` on `NEW.category_id`. So the
check is not confined to creation, and it re-runs on **every** update — the same shape as
`trg_recurring_rules_before_update`, which module 5 recorded as OB-009. The claim in the document was
simply wrong.

It was settled empirically rather than by argument.
`retiringTheCategoryFreezesTheExistingLimit` retires a category that already has a budget, then
attempts `PATCH {"limitAmount": ...}`. Against the original code that test failed: the write was
refused by the trigger and surfaced as a generic `409 DATA_CONFLICT` whose message says "refresh and
try again" — advice that cannot work while the category stays retired. The test's own comment now
records that finding.

**The fix is in the service, not the schema** (§4 forbids changing the trigger). `BudgetService.update`
now pre-checks `Boolean.FALSE.equals(budget.getCategory().getIsActive())` and throws
`CategoryRetiredException`, so the caller is told which rule refused them and what to do:

> This budget's category has been retired, so the limit cannot be changed. Restore the category, or
> remove the budget if it is no longer needed.

This is exactly what module 5 does for the identical constraint, so the two modules now answer the
same `409 CATEGORY_RETIRED` for the same BR-07 rule instead of one naming the remedy and the other
not. The behaviour was already correct; what was wrong was the diagnosis the caller received.

**The asymmetry with delete is deliberate.** `retiringTheCategoryLeavesTheLimitReadable` also asserts
that `DELETE` **is** accepted on a frozen budget, because a delete fires no `BEFORE UPDATE` trigger.
That is the one action which leaves the category alone, so it has to remain the student's way out of
the state without restoring the category. Both halves are pinned by tests, and both remedies are in
the message.

### 5.3 The frontend synthesises its own budget alerts, in its own words

`frontend/src/app/core/services/budget.service.ts` has a `getBudgetAlerts()` that maps over the budget
list and builds a `message` string in the client:

```ts
message: `Over budget! You have spent $${b.spent} of your $${b.monthlyLimit} limit (${percent}%) on ${b.categoryName}.`
```

Three independent problems, none of which is fixable by rewording:

1. **Two sources of user-facing prose for one event.** The real text is the procedure's (§5.4). They
   will drift, and the client's cannot be changed without a release.
2. **BR-12 is not representable client-side.** A synthesised alert reappears on every reload because
   it is recomputed from current state. A real notification is a row written once and then read or
   unread — which is the whole point of "alert once per threshold per month".
3. **The status vocabulary differs.** The frontend uses `'WARNING' | 'DANGER'`; the API uses
   `BUDGET_NEAR` / `BUDGET_EXCEEDED`.

This module changes no Angular source. The rewiring — delete `getBudgetAlerts` and the
`BudgetAlertNotification` type, call `GET /api/v1/notifications`, and remap the budget model — is
specified in [`docs/api/notifications.md` §12](../api/notifications.md#12-angular-integration-notes)
and [`docs/api/budgets.md` §14](../api/budgets.md#14-angular-integration-notes), following the same
"document the divergence, change no Angular source" convention modules 2–5 used.

### 5.4 The alert prose is the procedure's, and the lengths it stores are its own

The two messages are built by `sp_check_budget_alerts` with `CONCAT`, so their exact wording and the
formatting of the amounts inside them are fixed at the database. The API publishes `title` and `body`
verbatim. This was verified against the procedure's source rather than assumed, and the literals are
quoted in [`docs/api/notifications.md` §5](../api/notifications.md#the-two-messages-verbatim), so a
test that asserts them is asserting the contract rather than the current phrasing of a helper.

The consequence for a client is worth stating: because the amounts inside `body` are the procedure's
`CONCAT` of `DECIMAL(15,2)` values, they carry the column's scale — `320.00`, not `320`. A client
should render `body` as text rather than parse the numbers out of it.

### 5.5 Three driver-rendering facts the tests had to be written around

Each was found by a failing assertion, and each is a property of the connector rather than of this
module. They are recorded because they will recur in every later module that reads a boolean, a
timestamp or a decimal from MySQL.

| Symptom | Cause | Resolution |
|---|---|---|
| `columnInDatabase(..., "is_read")` returned `"true"`, not `"1"` | MySQL `TINYINT(1)` is reported by the connector as a Java `Boolean`, so `String.valueOf` gives `"true"`/`"false"` | Added `booleanInDatabase` using `ResultSet.getBoolean`, with a docstring on the conversion |
| `2026-09-25T14:18:12` did not equal `2026-09-25 14:18:12.0` | Jackson renders a `LocalDateTime` as ISO with a `T`; `Timestamp.toString()` uses a space and a trailing `.0` — and the `.0` is the JDBC type's rendering, not a value | Added `instantOf(String)` that normalises both to a `LocalDateTime` and compares instants |
| `"300.0"` did not equal `"350.00"` | `BigDecimal.asText()` on the wire is not pinned to the column's scale | Compare `.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString()` on both sides — asserting the text would be asserting the serialiser |

The third is the one most worth remembering: it appeared in a concurrency test where the two values
were `300.00` and `350.00`, and comparing the serialised strings made a correct result look wrong.

### 5.6 The edit-versus-spend race asserted a guarantee the design does not make

`aLoweredLimitRacingARecordSettlesConsistently` initially asserted that a lowered limit racing a
transaction settles on one alert state. That over-claims. `sp_check_budget_alerts` reads the limit as
part of the trigger's own statement, and that read is an ordinary consistent read — it may see the
limit as of the pre-edit or the post-edit commit. So the alert's **existence** is not deterministic;
only its **content** is.

The test was rewritten to assert exactly what is guaranteed and nothing more: the stored limit is
`30.00`, the reported `spentAmount` is `25.00`, the status is `NEAR`, every alert row — if any — is
`NEAR` with `83.33`, there is at most one, and the notification count matches the alert count. Its
comment states why the existence is not asserted. This is the difference between a concurrency test
that proves something and one that passes by luck.

### 5.7 The OpenAPI path-count arithmetic was wrong in a comment

`OpenApiContractIT` asserts the generated document's path count. The comment justifying it said
"twenty-one distinct paths for thirty-four operations", which reads as though 21 paths hold 34
operations with 13 left over — but the arithmetic as written did not add up, because it listed only
some of the shared paths. The assertion value **21 was correct**; the explanation was not.

The real distribution is 34 operations over 21 paths: nine paths carry more than one method (the four
collections `/categories`, `/transactions`, `/recurring-rules`, `/budgets` each serve `GET` and
`POST`; `/profile/me` serves `GET` and `PATCH`; the four `{id}` paths each serve `GET`, `PATCH` and
`DELETE`), giving 22 operations on those nine paths and 12 single-method paths. The comment now says
that, so the number can be checked by reading it. This is the class of error a test cannot catch — the
test was green the whole time; what was wrong was the claim about why.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `budget/BudgetApiIT.java` | 45 | HTTP → Controller → Security → Service → Repository → MySQL 8 (Testcontainers) |
| `budget/NotificationApiIT.java` | 20 | HTTP → … → MySQL 8 (Testcontainers) |
| `budget/service/BudgetWriteFailureTest.java` | 12 | Unit — SQLSTATE classification against the real exception shapes |
| `support/OpenApiContractIT.java` | 10 | Contract — 2 of its assertions cover this module |

Full suite: **394 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.** Module 5's baseline was
**315**; module 6 adds 77 in the budget package plus 2 contract assertions.

`BudgetWriteFailureTest` is a unit test rather than an integration test for the reason §4.2 gives:
`sp_validate_budget` signals four rules with one SQLSTATE and the service pre-checks them all, so the
signalled branch is nearly unreachable through the API. It covers `isSignalledRefusal` on an
`InvalidDataAccessResourceUsageException` wrapping `SQLException(msg, "45000")`, all four of the
procedure's literal messages, deeper nesting, the two CHECKs as `DataIntegrityViolationException` +
`SQLException(..., "HY000", 3819)`, the duplicate as `SQLSTATE 23000` with `1062`, the case-insensitive
name match, a **different** duplicate key on the same table not being claimed as BR-11's, the
non-overlap of the recognisers, unrelated failures left unrecognised, a null message, and a
self-referencing cause chain terminating.

**Every UC-14 fixture is a transaction.** `raiseNearAlert` sets a limit, records an expense through
module 4's endpoint, asserts that exactly one message appeared, and returns its id. Nothing inserts a
`notifications` row. This is what makes the read-path tests evidence for UC-14 rather than for the
mapper.

**Coverage against §13 Phase 5's categories**, all through HTTP where the behaviour is observable
through HTTP:

| # | Category | Tests |
|---|---|---|
| 1 | Happy path | `createdBudgetHasTheDocumentedShape`, `theListCarriesOneRowPerCategory`, `readingOneLimitMatchesTheList`, `aMessageHasTheDocumentedShape` |
| 2 | Validation | `theLimitIsValidated`, `theMonthFormatIsValidated`, `anImpossibleMonthIsRefused`, `anInvalidNewLimitIsRefused` |
| 3 | Unauthenticated | `noTokenIsRefused` (both classes) |
| 4 | Unauthorized role | `anAdministratorTokenIsRefused` (both classes) |
| 5 | Ownership violation | `anotherStudentsLimitIsUnreachable`, `anotherStudentsMessageIsUnreachable`, `anotherStudentsCategoryCannotBeLimited`, `theListShowsOnlyTheCallersMessages` |
| 6 | Not found | `aMissingBudgetIsNotFound`, `aMissingMessageIsNotFound` |
| 7 | Invalid identifiers | `notYoursAndNotFoundAreIndistinguishable` |
| 8 | Boundary values | `theLimitIsValidated` (0 and negative), `theLimitCheckConstraintHoldsForEveryCaller` |
| 9 | DB constraints and triggers | `theTriggerRefusesAnIncomeCategoryForEveryCaller`, `theTriggerRefusesAnotherStudentsCategoryForEveryCaller`, `theTriggerRefusesARetiredCategoryForEveryCaller`, `theUniqueKeyRefusesASecondLimitForEveryCaller`, `theMonthCheckConstraintHoldsForEveryCaller`, `theLimitCheckConstraintHoldsForEveryCaller`, `retiringTheCategoryFreezesTheExistingLimit` |
| 10 | Stored procedure behaviour | `crossingTheNearThresholdRaisesOneAlert`, `crossingTheExceededThresholdRaisesOneAlert`, `spendingWithoutALimitRaisesNothing`, `markingReadSetsFlagAndTimestampTogether`, `markingReadTwiceKeepsTheFirstTimestamp` |
| 11 | Rollback | `retiringTheCategoryFreezesTheExistingLimit` (the limit is unchanged after the refusal) |
| 12 | Duplicate requests | `aSecondLimitForTheSameMonthIsRefused`, `theSameCategoryCanBeLimitedInTwoMonths`, `markingReadTwiceKeepsTheFirstTimestamp` |
| 13 | Concurrency / idempotency | `twoSimultaneousSetsProduceOneLimit`, `twoSimultaneousChangesSettleOnOneValue`, `twoSimultaneousDeletesProduceOneDeletion`, `aLoweredLimitRacingARecordSettlesConsistently` (corrected — §5.6) |
| 14 | Error consistency | `notYoursAndNotFoundAreIndistinguishable`, `thereIsNoUnreadEndpoint` |
| 15 | Security regressions | `thereIsNoUnreadEndpoint`, `anAdministratorTokenIsRefused`, `categoryIsNotEditable`, `aMessageHasTheDocumentedShape` (no `userId`) |
| 16 | Empty state | `emptyStateIsAnEmptyArray` (both classes) |
| 17 | UAT | `settingALimitRaisesNoAlert`, `loweringALimitRaisesNoSecondAlert`, `deletingTheRecordDoesNotWithdrawTheAlert`, `removingALimitKeepsItsNotifications` |
| — | Cross-module (§16) | `theListIsScopedToTheRequestedMonth`, `deletedRecordsStopCounting`, `removingALimitKeepsTheTransactions`, `incomeDoesNotCountTowardsALimit` |

**The concurrency guarantee for alerts is tested where the write happens, not here.** Two transactions
crossing the same threshold at once are settled by `uk_alert_budget_threshold` and `INSERT IGNORE`,
which is module 4's write path. Module 6 has therefore no concurrency test *of its own* for alert
deduplication, and
[`docs/api/notifications.md` §14](../api/notifications.md#notes-on-the-tests) states that explicitly
rather than implying coverage this module does not have. What module 6 does test concurrently is its
own three write paths, each of which takes `SELECT ... FOR UPDATE` or relies on the unique key.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every endpoint and field traces to UC-13, UC-14 or a database object (§9). No endpoint exists without a requirement |
| B. Duplicate / missing endpoints | None. 21 distinct paths serve 34 operations; the budget and notification alias blocks in `OpenApiContractIT` fail if a `/check`, `/spend`, `/alerts`, `/unread` or `/read-all` route is ever added |
| C. Layering | Controller → Service → Repository → MySQL. No entity leaves the controller; no repository is called from a controller |
| D. Validation placement | Bean Validation for shape, the service for the cross-field rules (expense type, active category, real month), the database for the authoritative version of all of them |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`, `field` canonical. Two codes added: `BUDGET_ALREADY_EXISTS` and the pre-existing `CATEGORY_RETIRED`, each distinct for a reason |
| F. Transaction boundaries | Reads `readOnly`; writes transactional; the procedure call is transactional. Not annotated mechanically |
| G. N+1 and fetch strategy | The list reads one query; `open-in-view: false`, so a forgotten fetch fails loudly |
| H. Locking | `SELECT ... FOR UPDATE` on the budget write path only; the read path deliberately does not lock, because the consumption is computed fresh and a lock would serialise readers for no gain |
| I. Schema coupling | `ddl-auto: validate`; no schema change; `@DynamicUpdate` on `Budget` justified as correctness |
| J. Security | §15 of each API document. No client identity, measurement or threshold accepted anywhere; role enforced server-side; refusals translated by SQLSTATE |
| K. Sensitive output | `BudgetMapper` and `NotificationMapper` are the single gates; `userId` and the row timestamps are not mapped |
| L. Logging | Only the operation, the user id and the row id. The exception is deliberately not logged on a recognised refusal — trigger `SIGNAL` text and MySQL constraint messages name schema identifiers |
| M. Dead code | None found. The `Budget.entity` deliberately does not map `user_id` as an association — the id is enough for the ownership queries and a `@ManyToOne` would load a row nothing reads |
| N. Naming | Matches the surrounding modules; `BudgetWriteFailure` mirrors `TransactionWriteFailure`, `CategoryWriteFailure` and `RecurringRuleWriteFailure` |
| O. Documentation | `docs/api/budgets.md`, `docs/api/notifications.md`, the inventory rows 27–34, and this report |
| P. Tests through HTTP | 65 of the 77, against real MySQL 8 |
| Q. Empty state | `[]`, never `404`, on both lists |
| R. Determinism | **Found and corrected the over-claimed race assertion** — §5.6, and the non-total sort was pre-empted by the `id` tiebreak on both lists |
| S. Idempotency | Documented and tested: setting the same limit twice is refused by the unique key (not silently merged), and marking a notification read twice is a `200` that does not move the timestamp |
| T. Restart behaviour | No state is held in memory; the consumption is computed per read, so a restart changes nothing |
| U. Configurability | Port 8080 unchanged; the two thresholds are settings, not constants, and are read through the view (VĐ-05) |
| V. Language | All artifacts English |

Two defects were found and fixed in this pass (§5.2, §5.7) and one over-claim was corrected (§5.6). No
criterion failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's budget by id | `404`, indistinguishable from a budget that does not exist (`notYoursAndNotFoundAreIndistinguishable`) |
| Read another student's notification by id | `404`, identically |
| Mark another student's notification read | `404`; the `user_id` predicate is inside the same `UPDATE`, so nothing changes even if the check were bypassed |
| Set a limit on another student's category | `404` naming no field; the id is not found rather than found-and-refused (`anotherStudentsCategoryCannotBeLimited`) |
| Set a limit on an **income** category | `400` naming `categoryId`; `sp_validate_budget` refuses it for every caller, proved by a hand-written SQL insert |
| Set a limit on a retired category | `400` naming `categoryId` |
| Set a second limit for the same category and month | `409 BUDGET_ALREADY_EXISTS`, naming the existing budget id so the client can switch to "change it" |
| Set the same limit twice concurrently | One row, not two (`twoSimultaneousSetsProduceOneLimit`) |
| Send `userId` to create a budget owned by somebody else | Not in the request type |
| Send `spentAmount` to show spending that did not happen | Not in either request type |
| Send `categoryId` on update to move a limit onto an income category | Silently ignored; the stored category is unchanged (`categoryIsNotEditable`) |
| Send `periodMonth` on update to move a limit onto another month | Not a field of the update request |
| Send `consumptionStatus` to claim `ON_TRACK` while over the limit | Not in either request type; the status is the view's |
| Set a limit of 0, a negative value, or one with three decimals | `400` naming `limitAmount`; `ck_budget_limit` and `@Digits` both hold |
| Send `2026-13` as a month | `400` naming `periodMonth`, not a `500` from a parser |
| Send `2026-09-15` as a month | `400`; the anchored pattern refuses it rather than truncating to the 1st |
| Send a non-numeric id | `400 BAD_REQUEST` |
| Use an expired, revoked or foreign JWT | `401` before the controller |
| Use a disabled account's token | `401` before the controller |
| Use an administrator token | `403` |
| **Expect an alert after setting a limit** | **No alert. Documented as correct — §5.1** |
| **Expect a second alert after lowering a limit below spending** | **No second alert; the status changes to `EXCEEDED` immediately** (`loweringALimitRaisesNoSecondAlert`) |
| **Edit a budget whose category was retired** | **`409 CATEGORY_RETIRED` with both remedies** — §5.2, **OB-011** |
| **Delete a budget whose category was retired** | **`204`.** The asymmetry is deliberate and pinned: a delete fires no `BEFORE UPDATE` trigger |
| Expect a deleted budget's alerts to be withdrawn | They are not. The logs cascade, the notifications stay — the student was told |
| Remove a limit, then set it again and cross the threshold | Alerts again: the cascade cleared the alert log, so this is a genuinely new limit |
| Expect a third alert for a category in one month | Impossible; at most two, one per threshold (`uk_alert_budget_threshold`) |
| Expect a soft-deleted expense to keep counting | It does not (`deletedRecordsStopCounting`) |
| Expect income to count towards a limit | It does not (`incomeDoesNotCountTowardsALimit`) |
| Mark a notification unread | No route exists; the probe is asserted, not assumed (`thereIsNoUnreadEndpoint`) |
| Set `isRead` through a request body | No such field exists on any route |
| Set `readAt` to a chosen time | Impossible; `NOW()` decides it |
| Delete a notification | No route exists — the record of having been warned is not erasable |
| Mark the same notification read from two devices | Both answer `200`; `readAt` is the **first** time (`markingReadTwiceKeepsTheFirstTimestamp`) |
| Read the notification list and expect it to write | Nothing is written; `Notification` is `@Immutable` (`readingDoesNotWrite`) |
| Make a mark-read response show a stale unread state | Prevented by counting for ownership instead of loading the entity — §4.1 |
| Find an internal error the driver would leak | A refused write is translated by SQLSTATE; an unrecognised failure is rethrown and answered as a `500` with a generic message |
| Retire a category that budgets reference, then interact with them | The documented, tested, reported behaviour of §5.2 |
| Change the thresholds and expect a redeploy | No redeploy: the view and the procedure both read `system_settings` |
| Change a category's type once a budget references it | Impossible: `trg_categories_before_update` refuses the type change |
| Expect a budget list to include categories with no limit | Only rows with a limit appear; a category with none is not a zero row |

**Nothing in this pass failed except the two limitations already recorded as deliberate (§3.1's
architectural constraint and §5.2's schema-owned freeze) and the three defects fixed (§5.2, §5.6,
§5.7).** No fixable security defect was left as a documentation note.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-13 | Set a limit | `POST /budgets` | `createBudget` | `create` | `budgets` INSERT, `trg_budgets_before_insert` | `createdBudgetHasTheDocumentedShape` |
| UC-13 | List the month's limits | `GET /budgets` | `listBudgets` | `listBudgets` | `v_budget_consumption` | `theListCarriesOneRowPerCategory` |
| UC-13 | Read one | `GET /budgets/{id}` | `getBudget` | `getBudget` | `v_budget_consumption` by id | `readingOneLimitMatchesTheList` |
| UC-13 | Change the limit | `PATCH /budgets/{id}` | `updateBudget` | `update` | `@DynamicUpdate` | `changingALimitKeepsItsIdentity` |
| UC-13 | Remove a limit | `DELETE /budgets/{id}` | `deleteBudget` | `delete` | `budgets` DELETE | `removingALimitDeletesTheRow` |
| UC-13 | The month defaults to the current one | `GET`, `POST` | DTO | `resolvePeriodMonth` | `ck_budget_month` | `anOmittedMonthDefaultsToTheCurrentOne` |
| UC-13 | The spend is computed per read, not stored | every read | — | — | `v_budget_consumption` | `deletedRecordsStopCounting`, `incomeDoesNotCountTowardsALimit` |
| UC-13 | The status follows the configured thresholds | every read | — | — | the view's `CASE`, `system_settings` | `theStatusFollowsTheConfiguredThresholds` |
| BR-11 | At most one limit per category per month | `POST` | — | `create` pre-check | `uk_budget_user_cat_month` | `aSecondLimitForTheSameMonthIsRefused`, `theUniqueKeyRefusesASecondLimitForEveryCaller` |
| BR-11 | Only an expense category | `POST` | `CreateBudgetRequest` | `requireExpenseCategory` | `sp_validate_budget` | `anIncomeCategoryCannotBeLimited`, `theTriggerRefusesAnIncomeCategoryForEveryCaller` |
| BR-11 | The category and month are the row's identity | `PATCH` | `UpdateBudgetRequest` field set | `update` | `uk_budget_user_cat_month` | `categoryIsNotEditable` |
| BR-02 | Ownership | all eight | `@AuthenticationPrincipal` | every lookup | owner id in every query | `anotherStudentsLimitIsUnreachable`, `anotherStudentsMessageIsUnreachable` |
| BR-02 | No response carries the owner | every read | — | both mappers | — | `aMessageHasTheDocumentedShape` |
| BR-07 | A retired category cannot be chosen | `POST` | — | `requireActiveCategory` | `sp_validate_budget` | `aRetiredCategoryCannotBeLimited` |
| BR-07 | …**and freezes its budgets** | `PATCH` | — | `update`, `CategoryRetiredException` | `trg_budgets_before_update` | `retiringTheCategoryFreezesTheExistingLimit` — **OB-011** |
| BR-07 | …but the budget stays readable, and removable | `GET`, `DELETE` | — | `delete` (no `BEFORE UPDATE` trigger) | `budgets` DELETE | `retiringTheCategoryLeavesTheLimitReadable` |
| BR-09 | Deleted records stop counting | every read | — | — | the view's `is_deleted = 0` | `deletedRecordsStopCounting` |
| BR-12 | Each threshold alerts once per category per month | — | — | — | `budget_alert_log`, `uk_alert_budget_threshold`, `INSERT IGNORE` | `crossingTheNearThresholdRaisesOneAlert`, `crossingTheExceededThresholdRaisesOneAlert` |
| UC-14 | **A transaction raises the alert, not a budget write** | `POST /transactions` | — | — | both `transactions` triggers → `sp_check_budget_alerts` | `settingALimitRaisesNoAlert`, `budgetWritesRaiseNoMessages` |
| UC-14 | Spending with no limit raises nothing | — | — | — | the procedure's `IF v_budget_id IS NOT NULL` guard | `spendingWithoutALimitRaisesNothing` |
| UC-14 | Deleting the budget does not withdraw the alert | `DELETE /budgets/{id}` | — | — | `notifications` untouched | `deletingTheRecordDoesNotWithdrawTheAlert` |
| UC-14 | List the messages, newest first | `GET /notifications` | `listNotifications` | `listNotifications` | `findForStudent`, `ix_notif_user_unread` | `theListShowsOnlyTheCallersMessages`, `theListIsNewestFirst` |
| UC-14 | Read and unread together | `GET /notifications` | — | — | no read-state predicate | `markingOneReadLeavesTheOthersUnread` |
| UC-14 | Read one, without marking it | `GET /notifications/{id}` | `getNotification` | `getNotification` | `findByIdAndUserId` | `readingOneMessageDoesNotMarkItRead` |
| UC-14 B4 | Mark read, flag and timestamp together | `POST /notifications/{id}/read` | `markRead` | `markRead` | `sp_mark_notification_read` | `markingReadSetsFlagAndTimestampTogether` |
| UC-14 B4 | Marking twice keeps the first timestamp | `POST .../read` | — | `markRead`, `existsByIdAndUserId` | the procedure's `AND is_read = 0` | `markingReadTwiceKeepsTheFirstTimestamp` |
| UC-14 B5 | Read-state is one-way | — | route set | — | `ck_notif_read` | `thereIsNoUnreadEndpoint` |
| UC-14 | Reading writes nothing | every read | — | — | `Notification` is `@Immutable` | `readingDoesNotWrite` |
| UC-14 | The message prose is the procedure's | every read | `NotificationResponse` | `NotificationMapper` | `sp_check_budget_alerts`'s `CONCAT` | `aMessageHasTheDocumentedShape`, `crossingTheNearThresholdRaisesOneAlert` |
| §7.5 | No client identity, measurement or threshold | all eight | DTO field sets | entity setter sets | — | `categoryIsNotEditable`, `aMessageHasTheDocumentedShape` |
| §7.6 | No schema identifiers in a response | all writes | — | `translateWriteFailure` | — | asserted by the module's refusal tests |
| §13 P5 | Concurrency | `POST`, `PATCH`, `DELETE` | — | `@Lock(PESSIMISTIC_WRITE)`, `uk_budget_user_cat_month` | `SELECT ... FOR UPDATE` | `twoSimultaneousSetsProduceOneLimit`, `twoSimultaneousChangesSettleOnOneValue`, `twoSimultaneousDeletesProduceOneDeletion`, `aLoweredLimitRacingARecordSettlesConsistently` |
| §13 P5 | Refusal classification | — | — | `BudgetWriteFailure` | — | `BudgetWriteFailureTest` |
| §16 | Cross-module regression | modules 3, 4 | — | — | — | `removingALimitKeepsTheTransactions`, `theListIsScopedToTheRequestedMonth` |
| §26 | The endpoints are in the inventory and the document | all eight | — | — | — | `OpenApiContractIT` |

The full UC → API → Controller → Service → Database → Test matrix, including every validation rule and
every refusal code, is in [`docs/api/budgets.md` §16](../api/budgets.md#16-traceability) and
[`docs/api/notifications.md` §14](../api/notifications.md#14-traceability). The module-5 interlock —
that a recurring rule's scheduled transaction raises a budget alert through the same trigger as a
hand-entered one — is asserted from module 5's side by
`RecurringRuleApiIT#aScheduledTransactionRaisesTheBudgetAlertLikeAnyOther`.

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` |
| Every field traced to a documented requirement | Yes — §9, and the API documents' fuller matrices |
| Validation with per-field errors using `field` | Yes |
| Ownership enforced server-side, structurally | Yes — every single-row query takes the owner's id |
| No sensitive field in any response | Yes — both mappers omit `userId`; the timestamps are not mapped |
| Tests through HTTP against real MySQL 8 | Yes — 65 HTTP tests + 12 unit tests |
| No schema change; `validate` holds | Yes — §3 |
| API documents written; Angular can integrate without guessing | Yes — `docs/api/budgets.md`, `docs/api/notifications.md`, including the rewiring the current mock needs |
| Inventory updated; no duplicate endpoint | Yes — endpoints 27–34 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 394 tests |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — the two open items are a schema-owned constraint recorded as OB-011 and an architectural constraint that is the database's design, not a missing feature |

---

## 11. Deferred / blocked

Nothing in this module is blocked. One constraint is recorded for a decision, and two global items
affect it.

**Recorded for the owner's decision — OB-011: retiring a category freezes every budget filed under
it.** `trg_budgets_before_update` calls `sp_validate_budget` on every update, and that procedure has
no `require_active` escape — the same shape as module 5's OB-009. §4 forbids changing the procedure or
the trigger, so the constraint is documented as first-class behaviour
([`docs/api/budgets.md` §9](../api/budgets.md#a-budget-whose-category-has-been-retired-cannot-be-changed)),
the error names both remedies, and two tests pin it. The remedies are to restore the category, or to
remove the budget — the latter being why delete is deliberately *not* frozen.

**Global items that touch this module:**

| Item | Effect here |
|---|---|
| **OB-002** — no production email provider | UC-14 is an in-app notification only. There is no email or push delivery, and none is claimed. The notification list is the whole of the requirement as implemented |
| **OB-003** — production secrets management | Unaffected: the module adds no credential. The two thresholds are `system_settings` rows, not secrets |
| **OB-010** — a pause defers its periods rather than skipping them | Touches this module indirectly: backfilled recurring transactions are ordinary expenses, so they raise budget alerts *retroactively* when the scheduler posts them. That is correct — the alert belongs to the transaction and the transaction was just written — but it means a resumed pause can produce a burst of alerts whose dates are in the past. Recorded in [`docs/api/recurring.md` §11](../api/recurring.md#11-the-scheduler-how-a-rule-becomes-transactions) |

**A deliberate limitation, stated so it is not mistaken for coverage:** the alert-deduplication
guarantee (BR-12) is tested where the write happens — module 4's transaction path and its trigger —
rather than in this module, which writes no alert at all. The module's own concurrency tests cover
its three write paths and nothing else.
[`docs/api/notifications.md` §14](../api/notifications.md#notes-on-the-tests) says so explicitly.

---

## Related documentation

- [`docs/api/budgets.md`](../api/budgets.md) — the UC-13 contract, including the frontend divergence
- [`docs/api/notifications.md`](../api/notifications.md) — the UC-14 contract, including how an alert comes to exist and the BR-12 bound
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — endpoints 27–34
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — OB-011, and the global items
- [`docs/modules/MODULE_05_RECURRING.md`](MODULE_05_RECURRING.md) — the sibling constraint (OB-009), the scheduler whose transactions raise these alerts, and the module report this one follows
- [`docs/modules/MODULE_04_TRANSACTIONS.md`](MODULE_04_TRANSACTIONS.md) — where the writes that raise a budget alert happen
