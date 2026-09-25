# Module 5 — Recurring (UC-09)

**Status:** DONE WITH DEFERRED NON-CRITICAL ITEMS

A student sets up a repeating income or expense, edits it, pauses it, ends it, and removes one that
was created by mistake. Five endpoints. The rule's type is never sent because it is the category's;
the periods it has posted are never computed here because `sp_post_recurring_transactions` owns that
and `uk_occurrence_rule_period` makes a repeat impossible; and a rule that has already posted
transactions is not deletable, because deleting it would break module 4 rather than merely lose a row.

This report is the module record: what was built, what was tested, what the two reviews found, and the
traceability from requirement to test.

**Two items need the owner's attention**, and neither is a missing feature:

1. **A retired category freezes every rule filed under it** (§5.1, §11). `trg_recurring_rules_before_update`
   calls `sp_validate_recurring_rule` unconditionally, and that procedure has no `require_active`
   escape — unlike the transaction trigger, which does. So once module 3 retires a category, no field
   of any rule under it can be written, **including `status: "ENDED"`**. §4 forbids changing the
   procedure or trigger, so this is recorded as **OB-009** with the remedy options. It is documented
   as first-class behaviour in `docs/api/recurring.md` §9, the error message names both workarounds,
   and two tests pin it.
2. **Module 4's deferred list bound is closed here** (§5.6). Module 4's unbounded list stops at today,
   so a future-dated `RECURRING` row is not in it until its date arrives. `theSchedulerNeverWritesTheFuture`
   now proves the procedure's `WHILE v_next <= v_as_of` bound means **no such row is ever created**, so
   the bound on module 4's default is correct rather than merely conservative, and module 4's deferred
   item can be closed. No contract change on either side.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-09 | Set up a repeating income or expense; list, read, edit, pause, resume, end and remove one; the scheduler turns due rules into transactions |
| UC-09 A1 | Catch-up: a rule posts every period it missed, not only the most recent, and never more than once per period |
| UC-09 BA | The scheduler runs as an application job because the schema has no `EVENT` and §4 forbids adding one |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `type` field on any request | The type **is** the category's type (BR-05). `recurring_rules` does keep the column — a rule can exist before any transaction does, so BR-05 needs something to compare at that moment — but Java writes it from the category on every path, and the triggers re-check it. A schema column is not a reason to expose a field |
| `userId` on any request | The owner is the account in the bearer token, which is what makes BR-02 structural rather than a comparison someone has to remember |
| `status` on create | Every rule is created `ACTIVE`. Accepting one would be a second way to do what pause and end already do, and would let a client create a rule that is already stopped |
| `startDate` on update | It is the rule's origin, and the periods already posted are a function of it. Moving it would make the rule disagree with its own occurrence history. `nextRunDate` is the field that moves a rule onto a different day |
| `lastRunDate` on any request | Written only by `sp_post_recurring_transactions`. A client able to move the cursor could make the scheduler skip or repeat periods |
| `dayOfMonth`, `dayOfWeek` — in or out | Schema columns described as UI hints that the scheduler does not read. Writing them would store a value with no consumer and one that could disagree with the date the rule actually runs on. Publishing them would be worse: a "monthly on the 5th" label must derive from `startDate`, the value the scheduler agrees with |
| An occurrence count or period history | The periods are rows in `recurring_occurrences`, which the scheduler owns. The client reads their *effect* through module 4's `GET /transactions` |
| `/pause`, `/resume`, `/end` | Three sets of one column. Three URLs would be three names for one write and could disagree with `PATCH` |
| A "run the scheduler now" endpoint | Which periods are due is the procedure's decision, made once a day. A client able to trigger it would trigger it for every student in the system |
| A `?status=` filter | The response carries `status` and the client already knows what it wants to show — the same decision module 3 made about `?type=` |
| `PUT` for the whole rule | UC-09 edits values; it does not replace a record |
| `/recurring-rules/all`, `/list`, `/api/v1/recurring`, `/recurring-rules/{id}/occurrences` | `GET /api/v1/recurring-rules` answers all of them. One path, one question |
| `/profile/me/recurring-rules` | A rule is one of many and is addressed by id, so ownership belongs in the query. Filing it under `/profile/me` would suggest the wrong model to modules 6–10 |
| Pagination | UC-09 does not ask for it. A student has a handful of rules, not thousands |
| A `@Scheduled` database `EVENT` | §4 forbids adding a schema object; `docs/DB_DESIGN.md` §335 lists the application-side scheduler as the alternative |
| The CSV import (UC-11), the AI suggestion columns (UC-08), the anomaly flags (UC-24) | Later modules that write their own rows through their own contracts |

---

## 2. Endpoints

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 22 | GET | `/api/v1/recurring-rules` | UC-09 | Bearer, role `STUDENT` | `200` rule list |
| 23 | GET | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer, role `STUDENT` | `200` rule |
| 24 | POST | `/api/v1/recurring-rules` | UC-09 | Bearer, role `STUDENT` | `201` rule |
| 25 | PATCH | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer, role `STUDENT` | `200` rule |
| 26 | DELETE | `/api/v1/recurring-rules/{id}` | UC-09 | Bearer, role `STUDENT` | `204` no body |

A rule is one of many and is addressed by id, so ownership cannot be implied by the URL. It is enforced
by the query instead: every lookup in `RecurringRuleRepository` takes the caller's id alongside the
rule's, so no service method can reach another student's row even by mistake.

**Pausing, resuming and ending are `PATCH` of `status`, not three endpoints.** They set one column
with three values; three URLs would be three names for one write, and could disagree with each other
the moment one was changed. The contract documents the single-value `PATCH` explicitly, because it is
the most common thing a student does to a rule.

**Delete is refused once the rule has posted anything** — `409 RECURRING_RULE_IN_USE`, with the
remedy in the message: end it instead. See §5.2; this is the most important behaviour in the module.

Administrators are refused with `403`. `/api/v1/recurring-rules/**` requires `STUDENT`. UC-09 is a
student's own schedule and the scheduler posts on students' behalf; letting an administrator through
would create a rule owned by that administrator.

---

## 3. Database alignment

No schema object was changed. `recurring_rules` and `recurring_occurrences` already had everything
UC-09 needs: the two triggers, the three CHECK constraints, the two indexes, the two foreign keys and
`sp_post_recurring_transactions`.

**What the database owns, and Java does not restate:**

| Rule | Owner |
|---|---|
| The category must exist and be the caller's own or a shared default (BR-02) | `sp_validate_recurring_rule`, through `trg_recurring_rules_before_insert` and `..._before_update` |
| The rule's type must equal the category's type (BR-05) | `sp_validate_recurring_rule` |
| The category must not be retired (BR-07) | `sp_validate_recurring_rule` |
| The amount must be strictly positive | `ck_recurring_amount` |
| The interval must be at least 1 | `ck_recurring_interval` |
| The end date must not precede the start date | `ck_recurring_dates` |
| Only `ACTIVE` rules on live categories post | `sp_post_recurring_transactions`' cursor |
| Each period posts exactly once (BR-16) | `uk_occurrence_rule_period` plus the procedure's `INSERT IGNORE` |
| Every generated transaction is audited like any other | `trg_transactions_after_insert` |

Java performs the write and translates the refusals. **Three checks are the application's, and each is
a stricter restatement of a rule that already holds**, present only so the error can name the field:
the category is usable, the category is active, and the dates agree. `sp_validate_recurring_rule`
signals four different rules with one `SQLSTATE '45000'`, so a client told only "the write was refused"
could not point at what needs fixing. Every one is checked before the write *and* the trigger still
runs; a request that slips past is caught by `translateWriteFailure` and answered `409`.

**The one date rule that is Java's own** is `nextRunDate <= endDate`. Nothing in the schema expresses
it, and it is worth refusing: the procedure's cursor condition is `next_run_date <= end_date`, so a
rule in that state would match no run, never advance, and never be marked `ENDED` — it would sit
`ACTIVE` for ever, visible to the student, posting nothing, and giving no hint why.

**Verified:** `git diff --stat db/` is empty; `find db -type f -newermt "2026-09-25 00:00"` returns
nothing; `ddl-auto: validate` is the setting in both `application-dev.yml` and `application-prod.yml`;
no schema-altering statement appears in any surefire report.

---

## 4. Implementation

| File | Role |
|---|---|
| `recurring/controller/RecurringRuleController.java` | Five endpoints, `@AuthenticationPrincipal` identity, full OpenAPI annotations, and a class javadoc explaining the three routes that deliberately do not exist |
| `recurring/service/RecurringRuleService.java` | The three application checks, the date agreement rule, the deletion guard, and the refusal translation |
| `recurring/service/RecurringRuleWriteFailure.java` | Classifies a database refusal by SQLSTATE, never by message |
| `recurring/repository/RecurringRuleRepository.java` | Every single-row query takes the owner's id; the write paths take a row lock; `countTransactionsGeneratedBy` counts both delete states |
| `recurring/repository/RecurringProcedureDao.java` | `CALL sp_post_recurring_transactions(:asOf)` — the module's only call into the scheduler |
| `recurring/scheduler/RecurringScheduler.java` | `@Scheduled`, once a day in `Asia/Ho_Chi_Minh` explicitly, passes `null` so "today" is the database's clock |
| `recurring/mapper/RecurringRuleMapper.java` | The one place deciding which columns may leave the server |
| `recurring/entity/RecurringRule.java` | The row; `@DynamicUpdate`; no setter for `user_id`, `startDate`, `lastRunDate` or `type` |
| `recurring/entity/RecurringFrequency.java`, `RecurringStatus.java` | `DAILY`/`WEEKLY`/`MONTHLY`/`QUARTERLY`/`YEARLY` and `ACTIVE`/`PAUSED`/`ENDED`, the columns' own ENUMs. `RecurringStatus`'s javadoc states the lifecycle, including that `ENDED` is final |
| `recurring/dto/RecurringRuleResponse.java` | 15 fields out; `userId` and the row timestamps are not among them |
| `recurring/dto/CreateRecurringRuleRequest.java`, `UpdateRecurringRuleRequest.java` | No ownership, type or history field on either; `endDate` typed as a String because it is removable |
| `common/exception/RecurringRuleInUseException.java` | `409 RECURRING_RULE_IN_USE`, with the remedy in the message |
| `common/exception/CategoryRetiredException.java` | `409 CATEGORY_RETIRED`, distinct because the remedy differs |
| `common/exception/RecurringRuleEndedException.java` | `409 RECURRING_RULE_ENDED`, for a request that would move a rule out of the terminal `ENDED` state |

**`@DynamicUpdate` is a correctness requirement, not an optimisation.** A plain Hibernate `UPDATE`
writes every mapped column back with the values read when the request began — including
`next_run_date`, which the scheduler advances by SQL while the request is in flight. Restricting the
statement to the columns that actually changed removes that class of lost update: without it, an edit
that changed only the amount could rewind the scheduler's cursor and re-post periods that were already
posted, or skip ones that were due. `last_run_date` is additionally mapped `updatable = false`, so
the procedure is its only writer.

**The write paths take `SELECT ... FOR UPDATE`, and the read path does not.** A state decision made
from a lock-free read can be made twice: pausing a rule and ending it could both see it `ACTIVE` and
both report success, with the second silently overwriting the first. It matters more here than on most
tables because the scheduler also writes this row, so the lock also keeps a status decision from being
made against a cursor that moves mid-request. The locking query deliberately omits `JOIN FETCH` — a
lock on a join also locks the shared `categories` row, which every rule under that category shares,
so two students editing rules in the same category would block each other for no reason.

**The scheduler is an application job, and that is forced rather than chosen.** The schema has no
`EVENT` object, and §4 forbids adding one. `docs/DB_DESIGN.md` §335 lists the application-side
scheduler as the alternative, and that is what this is: `@EnableScheduling` plus a `@Scheduled` cron,
**disabled or re-timed by configuration** (`campuscoin.recurring.scheduler.enabled`,
`...cron`), and passing `null` so the procedure falls back to `CURDATE()` in the session pinned to
`+07:00` (VĐ-10) rather than to the JVM's clock.

**Concurrency is the database's guarantee, not a lock the application adds.** Two scheduler runs
racing — a restart during a run, or two instances — produce one occurrence per period, because the
procedure's `INSERT IGNORE` against `uk_occurrence_rule_period` makes the second insert a no-op and
only a `ROW_COUNT() > 0` leads to a transaction being created. No distributed lock is needed, and
adding one would be the application claiming a guarantee the schema already provides.

**Transactions.** The two reads are `readOnly = true`; create, update, delete and the scheduler call
are `@Transactional`. Not annotated blindly.

---

## 5. Defects found and fixed

### 5.1 A retired category freezes every rule under it, and the first version reported it as a field error

`trg_recurring_rules_before_update` calls `sp_validate_recurring_rule` with the **new** values on
every update, and that procedure has no `require_active` parameter — unlike `sp_validate_transaction`,
which `trg_transactions_before_update` calls with `0` precisely so an old transaction under a
since-retired category stays editable. The consequence is that retiring a category in module 3 freezes
every rule filed under it: not its amount, not its `status`, not even `ENDED`.

Found by the adversarial pass (§8), which asked what happens to module 5 when module 3 does the thing
module 3 is allowed to do. The initial implementation let the trigger's refusal surface through
`translateWriteFailure` as a generic `409 DATA_CONFLICT` — "the data changed underneath the request" —
which is accurate but useless: nothing had changed, and the caller had no way to learn what to do.
Fixed by pre-checking the case in `RecurringRuleService.update` and answering it deliberately:

- **When the rule stays where it is**, the answer is `409 CATEGORY_RETIRED` with a message naming both
  workarounds ("Enable the category, or move the rule to one that is still in use").
- **When the request moves the rule**, the *target* category is what gets checked, so the answer is a
  field error naming `categoryId` — which is correct, because there the caller can fix it by sending
  a different category.

A dedicated `CategoryRetiredException` and `ErrorCode.CATEGORY_RETIRED` were added rather than reusing
`DATA_CONFLICT`, because the two need different UI: one is "reload and retry", the other is "this row
cannot be edited in its current state".

The underlying limitation cannot be fixed in the application — no JPA statement reaches the table
without firing the trigger — and §4 forbids changing the procedure or the trigger body. It is recorded
as **OB-009** with three remedy options, documented as first-class behaviour in `docs/api/recurring.md`
§9 and §11, and pinned by two tests so it cannot drift unnoticed.

### 5.2 Deleting a rule that has posted would break module 4, and the count has to include soft-deleted rows

`transactions.recurring_rule_id` deliberately carries **no foreign key** (`docs/DB_DESIGN.md` §4.8),
so the database would happily allow the delete. What it would leave behind is not a harmless dangling
pointer but a broken record: `sp_validate_transaction` re-checks that reference on every write, so a
transaction whose rule no longer exists raises `BR-02: recurring rule does not exist` on edit, on soft
delete and on restore. The damage would not stay inside module 5 — it would break module 4's
`PATCH`, `DELETE` and `POST /restore` for rows that were working before.

So `RecurringRuleService.delete` counts the generated transactions first and refuses with
`409 RECURRING_RULE_IN_USE`, naming the remedy ("End it instead: it will stop posting and the
transactions it created stay readable and editable").

**A first version counted only live transactions, and that was a real defect.** A rule whose only
generated transaction had been soft-deleted would have reported as removable; the delete would have
succeeded; and the record would then have been permanently uneditable, because restoring it still runs
through `sp_validate_transaction` and still finds a missing rule. The count is now over
`transactions WHERE recurring_rule_id = ?` with no delete-state predicate, which is the correct
question: what matters is whether any row still points at this rule, not whether that row is currently
visible. Pinned by `aSoftDeletedTransactionStillBlocksTheDelete`.

**Ownership is checked before the count.** `requireOwnRuleForUpdate` runs first, so the refusal cannot
be used to learn whether another student's rule has posted anything — a rule belonging to somebody
else answers `404` regardless of how much it has generated. Pinned by
`deleteIsOwnershipCheckedBeforeTheGeneratedCount`.

### 5.3 `endDate` had to be a String, and a first version typed it as a `LocalDate`

`endDate` is the one field in this module that can be **removed** — sending `""` makes a rule
open-ended again. A record of nullable fields cannot express three states: absent, `null`, and a value.
Both absent and explicit `null` arrive as `null` in Java, so with `LocalDate endDate` there was no way
to say "clear the end date" once one had been set. The field is therefore typed `String` on both
request records, with a `@Pattern` for the shape, and the service parses it through
`parseOptionalDate`, where an empty string means null. This is the convention the profile module
already established for `academicYear`, which is the only other nullable non-text column in the API.

The same method closes a second hole the String typing opened: `2026-02-30` matches the pattern and
would have thrown `DateTimeParseException` out of the service and been answered as an **internal
error**. It is a validation failure and is now reported as one, naming the field. Pinned by
`aNonExistentDateIsAFieldError`.

### 5.4 `nextRunDate > endDate` is expressible in the schema and produces a rule that never fires and never ends

`ck_recurring_dates` covers `end_date >= start_date`. Nothing covers the relationship between
`nextRunDate` and `endDate`, and the procedure's loop condition is `v_next <= v_end`. A rule created
with `startDate` 2026-09-01, `endDate` 2026-10-01 and `nextRunDate` 2026-12-01 is therefore accepted
by the database, sits `ACTIVE` for ever, matches no run, never advances and is never marked `ENDED` —
visible to the student, posting nothing, and offering no explanation.

The service now refuses it, as a field error naming `nextRunDate`. This is the one date rule that is
Java's own rather than a restatement of a database rule, and it is justified in §3. A **past** end
date is deliberately still allowed: it is not a mistake, and the scheduler catches up on it, which is
the documented A1 behaviour after downtime.

### 5.5 The list's ordering was not total

`ORDER BY next_run_date` alone leaves rows on the same date in whatever order MySQL chooses, so two
identical calls could return the same set in different orders and a UI would appear to shuffle. The
order is now `next_run_date ASC, id ASC`, which is total. Found in the Phase 9 pass, which asked of
each read endpoint whether its result was deterministic. Pinned by
`rulesAreOrderedByNextRunDateThenId`.

### 5.6 Module 4's deferred list bound is closed by a proof, not by an assumption

Module 4 deferred one item: its unbounded list stops at today, so a future-dated `RECURRING` row is not
in it until its date arrives. The question that belonged to module 5 was whether the scheduler ever
*creates* such a row.

It does not, and that is now proven rather than assumed: the procedure's inner loop is
`WHILE v_next <= v_as_of`, so it cannot advance the cursor past the date it was given, and the
application passes `null` so that date is `CURDATE()` in the `+07:00` session. `theSchedulerNeverWritesTheFuture`
asserts it directly. Module 4's bound is therefore correct rather than merely conservative, its
deferred item closed, and no contract change was needed on either side — the parameter that would
override it is already in module 4's contract.

### 5.7 The trigger, not the application, is the authority — and the tests said so

An early draft of `aRetiredCategoryCannotBeChosenOnCreate` asserted that the *service* refused a
retired category on create. The service does refuse it, but asserting only that would have left the
database's own enforcement untested, and the two are not equivalent: the service's check is a
restatement, and a request that slips past it is caught by the trigger. The test class now proves both
layers separately — `theTriggerIsTheAuthorityNotASecondOpinion` raises the refusal by direct SQL,
bypassing the application entirely, and `theCheckConstraintsHoldForEveryCaller` does the same for the
three CHECK constraints. Phase 9 criterion: "is the rule enforced where the schema put it, or only
where the application happens to look?"

### 5.8 `ENDED` was reachable in both directions, and UC-09's state machine does not allow that

The first version left `status` fully symmetric: the update path set whatever the request named, so a
rule that had ended could be set back to `ACTIVE`. The API document defended this as intentional
("ending a rule is 'stop this now', not an irreversible tombstone").

That reading is wrong for this system, and the reason is the delete rule. Ending is the operation a
student is pointed at precisely when a rule **has already posted** and therefore cannot be deleted
(§10). If `ENDED` were reversible, the same endpoint would be both the way to retire a rule whose past
periods were deliberately abandoned *and* the way to silently re-open it — and the schedules the
student thought they had stopped would start posting again, including the backfilled periods the
pause/resume rule would then produce (§5.12). The two operations contradict each other, so one of them
had to give.

The fix makes `ENDED` final in the application: `RecurringRuleService.requireEndableState` refuses a
request that would move a rule out of `ENDED`, answering `409 RECURRING_RULE_ENDED` with the remedy
("create a new rule") in the message. Re-sending `ENDED` is still a `200` no-op, so a retried request
does not fail. The restriction is deliberately the application's and not the schema's — the column is
an `ENUM('ACTIVE','PAUSED','ENDED')` that would accept any of the three at any time — and the entity's
javadoc says so, because a reader who finds the asymmetry in Java will otherwise go looking for the
constraint that does not exist.

The change is four files plus a test: the service, `ErrorCode.RECURRING_RULE_ENDED`,
`RecurringRuleEndedException`, and the documentation that claimed the opposite —
`docs/api/recurring.md` §9 and the `@Schema` on `UpdateRecurringRuleRequest.status`. The class javadoc
on `RecurringStatus` was rewritten from "may be moved between them in any direction" to the actual
lifecycle.

### 5.9 The pause race asserted an invariant the scheduler's cursor cannot provide

`aPauseRacingTheSchedulerSettlesConsistently` first asserted that if the rule ended up `PAUSED`, no
period had been posted. It failed, and the failure was correct.

`sp_post_recurring_transactions` opens a **cursor** over the rules that are `ACTIVE` when the cursor
is declared. A cursor in MySQL is a snapshot read: it takes no row locks, so it does not see the other
transaction's `SELECT ... FOR UPDATE`, and it cannot. The interleaving that breaks the old assertion is
therefore ordinary — the cursor reads the rule as `ACTIVE`, `PATCH` then takes the row lock and commits
`PAUSED`, and the scheduler (already past its cursor, holding the lock) inserts the transaction and
advances the cursor. The rule ends `PAUSED`, and one period was posted.

Nothing is lost by that. The pause's guarantee is about periods that are still **in the future**: once
it commits, no further period is posted while the rule stays paused, because the cursor's
`status = 'ACTIVE'` predicate excludes it from the next run. The period that landed was already due and
being posted at the moment the pause committed.

The assertion was replaced with the invariant that actually holds in both interleavings — the cursor and
the transaction count settle **together**, because the procedure advances `next_run_date` in the same
transaction that inserts the transaction. Either the cursor is still on today and nothing was posted,
or it has moved to next month and exactly one period was. A moved cursor with no transaction, or a
transaction with an unmoved cursor, would be a period posted without being recorded or recorded without
being posted; neither is reachable. The test also asserts the pause itself is never lost (the
scheduler's `UPDATE` only ever writes `ENDED`, and only for a rule with an end date), and that no
occurrence is left without its transaction.

This is the same class of correction as §5.7: the test's premise was wrong, not the code.

### 5.10 The scheduler's cron was not pinned to the application's zone

`RecurringScheduler`'s `@Scheduled` had no `zone`, so Spring evaluated the cron in the JVM's default
zone. On a server whose clock is UTC, the default `0 5 0 * * *` would fire at 07:00 local time
(+07:00) rather than at 00:05 — a five-hour drift between "the day the timer thinks it is" and "the day
`CURDATE()` computes", which is what the procedure uses. A rule could post a day early or a day late
depending on where the process happened to run, and the drift would be invisible in any test that ran
on a machine already set to the application's zone.

Fixed by declaring `zone = "Asia/Ho_Chi_Minh"` on the method, matching the Hikari
`connection-init-sql: SET time_zone = '+07:00'` (VĐ-10). `theSchedulerCronIsPinnedToTheApplicationZone`
reads the annotation reflectively and asserts both the zone and the cron placeholder, so removing
either is a test failure rather than a silent drift. §27's rule against claiming a timezone guarantee
from a database setting alone is why this is asserted on the timer as well as on the session.

### 5.11 The catch-up cap was undocumented and unproven

The procedure stops after 500 periods in one rule's loop — a deliberate safety stop so a misconfigured
rule cannot hold the whole run open. Nothing documented it, and nothing proved the system still
converges when it is hit: a cap that never settles would be a rule permanently stuck mid-catch-up.

`catchUpIsBoundedAndSettles` builds a 700-day daily backlog and asserts the three observable states:
the first run posts exactly 500 occurrences and leaves `next_run_date` on day 500, the second run posts
the remaining 201 and leaves the cursor on tomorrow, and the third run changes nothing. §11 of the API
document now states the cap and the settling behaviour.

### 5.12 A pause defers its periods rather than skipping them, and the requirement reads the other way

UC-09's wording suggests that pausing skips the periods that fall inside the pause. The locked
procedure does not do that: nothing selects a `PAUSED` rule, so its cursor never advances, and the run
after a resume walks forward from where it stopped — posting every period whose date passed, exactly as
it does after downtime. Pausing a monthly rule for three months and resuming posts four periods at
once.

The behaviour is the procedure's and cannot be changed here: the procedure is in `db/`, which §4
freezes, and matching the requirement's wording would mean duplicating the period arithmetic
(`DATE_FORMAT`/`WEEK`/`QUARTER` keys, the `INSERT IGNORE` against `uk_occurrence_rule_period`) in Java
— the thing this module is built not to do, and a second implementation that could disagree with the
first.

So it is documented and pinned rather than "fixed":
`RecurringRuleApiIT#pauseDefersPeriodsRatherThanSkippingThem` asserts that the cursor does not move
while paused and that resume posts all four month keys; `docs/api/recurring.md` §9 and §11 state the
consequence for a client; and the divergence from the requirement's wording is raised as **OB-010** in
`docs/OVERNIGHT_BLOCKERS.md` for the owner to decide. §4's rule is that a documented contradiction with
a genuine blocking quality is surfaced, not silently papered over — this one is surfaced with the
alternative costed.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `recurring/RecurringRuleApiIT.java` | 87 | HTTP → Controller → Security → Service → Repository → MySQL 8 (Testcontainers) |
| `recurring/service/RecurringRuleWriteFailureTest.java` | 11 | Unit — SQLSTATE classification against the real exception shapes |
| `support/OpenApiContractIT.java` | 8 | Contract — the published document matches the implementation (5 of its assertions cover this module) |

Full suite: **315 tests, 0 failures, 0 errors.** Module 5 added 77 + 11, and extended
`OpenApiContractIT` from its module-4 state by one test and five endpoint rows. The PHASE B audit added
ten more to `RecurringRuleApiIT` (§5.8–§5.12) and two to `ProfileApiIT`, which is what takes the
per-class counts to 87 and 31 and the total from 303 to 315.

**The lifecycle is asserted, not just implemented.** `theLifecycleAllowsExactlyTheDocumentedTransitions`
walks `ACTIVE → PAUSED → ACTIVE`, `PAUSED → ENDED`, `ACTIVE → ENDED`, then asserts that `ENDED →
{ACTIVE, PAUSED}` is `409 RECURRING_RULE_ENDED` and that re-sending `ENDED` is a `200` no-op, so a
retried request is not a failure. `anEndedRuleStillAcceptsEditsThatAreNotAStatusChange` pins the
narrower half of the same rule: an ended rule can still have its description changed, so the refusal
is specifically about leaving `ENDED` rather than about the rule being frozen.

`RecurringRuleWriteFailureTest` is a unit test rather than an integration test on purpose: it verifies
the classification with the exact exceptions MySQL and Spring produce. `sp_validate_recurring_rule`
signals four different rules with one SQLSTATE and the service pre-checks all of them, so the
signalled branch is nearly unreachable through the API — leaving it to an integration test would leave
it effectively unverified. It covers `isSignalledRefusal` on an
`InvalidDataAccessResourceUsageException` wrapping `SQLException(msg, "45000")`, all four procedure
messages, deeper nesting, the three CHECKs as `DataIntegrityViolationException` +
`SQLException(..., "HY000", 3819)`, FK `1451` and duplicate `1062` as SQLSTATE `23000`, the
non-overlap of the two recognisers, unrelated failures left unrecognised, null messages, a
self-referencing cause chain terminating, and `mentionsRetiredCategory` being advisory only.

**Coverage against §13 Phase 5's seventeen categories**, all through HTTP where the behaviour is
observable through HTTP:

| # | Category | Tests |
|---|---|---|
| 1 | Happy path | `createdRuleHasTheDocumentedShape`, `aNewRuleIsActiveAndHasNeverRun`, `aDueRuleIsPosted` |
| 2 | Validation | `requiredFieldsAreEachReported`, `amountMustBePositive`, `intervalIsBounded`, `descriptionIsBounded`, `enumNumbersAreRejected`, `endDateShapeIsValidated` |
| 3 | Unauthenticated | `everyEndpointRequiresAToken` |
| 4 | Unauthorized role | `anAdminTokenIsForbidden` |
| 5 | Ownership violation | `anotherStudentsRuleIsUnreachable`, `theListIsScopedToTheCaller`, `anotherStudentsCategoryIsNotFoundRatherThanForbidden` |
| 6 | Not found | `updatingAMissingRuleIsNotFound`, `aRuleCanBeReadById` (negative half) |
| 7 | Invalid identifiers | `aNonNumericIdIsABadRequest` |
| 8 | Boundary values | `amountMustFitTheColumn`, `intervalIsBounded`, `descriptionIsBounded`, `aRuleInThePastIsAccepted` |
| 9 | DB constraints and triggers | `theTriggerIsTheAuthorityNotASecondOpinion`, `theCheckConstraintsHoldForEveryCaller` |
| 10 | Stored procedure behaviour | `catchUpCreatesOneTransactionPerPeriodKey`, `aSkippedIntervalIsNeverPosted`, `theSchedulerUsesTheDatabaseClock`, `theSchedulerHonoursTheEndDate`, `theSchedulerNeverWritesTheFuture`, `aScheduledTransactionIsAuditedByTheSameTrigger`, `pauseDefersPeriodsRatherThanSkippingThem` (§5.12), `catchUpIsBoundedAndSettles` (§5.11), `theSchedulerCronIsPinnedToTheApplicationZone` (§5.10) |
| 11 | Rollback | `aRefusedWriteRollsBackWithinItsTransaction` |
| 12 | Duplicate requests | `creatingTheSameRuleTwiceIsAllowedAndIsNotIdempotent`, `runningTwicePostsOneOccurrencePerPeriod` |
| 13 | Concurrency / idempotency | `concurrentSchedulerRunsAreIdempotent`, `twoSimultaneousDeletesProduceOneDeletion`, `twoSimultaneousUpdatesBothSucceedOnTheLockedRow`, `aPauseRacingTheSchedulerSettlesConsistently` (§5.9), `anEndRacingTheSchedulerNeverPostsAfterEnding`, `aDeleteRacingTheSchedulerLeavesNoOrphanOccurrence` |
| 14 | Error consistency | `errorShapeIsConsistent`, `notFoundAndNotYoursAreIndistinguishable` |
| 15 | Security regressions | `noResponseLeaksInternals`, `aRevokedTokenIsRejected`, `aDisabledAccountIsRejected`, `serverOwnedFieldsAreIgnoredOnCreate`, `startDateIsNotEditable` |
| 16 | Empty state | `emptyStateIsAnEmptyArray` |
| 17 | UAT | `catchUpPostsEveryMissedPeriod` (UC-09 A1), `endingARuleLeavesItsTransactionsEditable` (the end-instead-of-delete path), `aRuleThatHasPostedCannotBeDeleted` |
| — | Cross-module (§16) | `categoryRetirementInteractsAsDocumented`, `transactionEndpointsStillBehaveAfterScheduledPosting` |
| — | Module 6 interlock (UC-09 B4) | `aScheduledTransactionRaisesTheBudgetAlertLikeAnyOther` — a scheduled occurrence reaches `sp_check_budget_alerts` through the same trigger as a hand-entered one |

**Two tests failed only in the full-suite run and passed in isolation, and the cause was a bug in this
test class rather than in the application.** The helper `patch(token, ruleId, body)` was hard-wired to
`/api/v1/recurring-rules`, but `endingARuleLeavesItsTransactionsEditable` and
`transactionEndpointsStillBehaveAfterScheduledPosting` passed a **transaction** id — so the request
went to `PATCH /api/v1/recurring-rules/{txnId}`, which correctly answers `404`. The helper was renamed
`patchRule` (30 call sites) and a separate `patchTransaction` added for the two transaction cases,
with a javadoc recording why the names differ. The application was correct throughout; what was wrong
was that the test's own helper made the mistake invisible at the call site.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every endpoint and field traces to UC-09 or a database object (§10). No endpoint exists without a requirement |
| B. Duplicate / missing endpoints | None. Sixteen distinct paths serve 26 operations; the recurring alias block in `OpenApiContractIT` fails if a `/pause`, `/run` or `/occurrences` route is ever added |
| C. Layering | Controller → Service → Repository → MySQL. No entity leaves the controller; no repository is called from a controller |
| D. Validation placement | Bean Validation for shape, the service for the three cross-field rules, the database for the authoritative version of all of them |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`, `field` canonical. Two conflict codes added, both distinct for a reason |
| F. Transaction boundaries | Reads `readOnly`, writes transactional, no method annotated blindly |
| G. N+1 and fetch strategy | `JOIN FETCH r.category` on both read queries; `open-in-view: false`, so a forgotten fetch fails loudly rather than quietly |
| H. Locking | `SELECT ... FOR UPDATE` on the write path only; deliberately no `JOIN FETCH` on the locking query, with the reason recorded |
| I. Schema coupling | `ddl-auto: validate`; no schema change; `@DynamicUpdate` justified as correctness rather than optimisation |
| J. Security | §15 of the API document. No client identity, type or history accepted anywhere; role enforced server-side; refusals translated by SQLSTATE |
| K. Sensitive output | `RecurringRuleMapper` is the single gate; `userId` and the row timestamps are not mapped |
| L. Logging | Only the operation and the user id. The exception is deliberately not logged — trigger `SIGNAL` text and MySQL constraint messages name schema identifiers |
| M. Dead code | None found; the entity deliberately leaves `day_of_month` and `day_of_week` unmapped, with the reason on the class rather than a TODO |
| N. Naming | Matches the surrounding modules; `RecurringRuleWriteFailure` mirrors `TransactionWriteFailure` and `CategoryWriteFailure` |
| O. Documentation | `docs/api/recurring.md`, the inventory rows, and this report |
| P. Tests through HTTP | 87 of the 98, against real MySQL 8 |
| Q. Empty state | `[]`, never `404` |
| R. Determinism | **Found the non-total sort** — §5.5. Fixed |
| S. Idempotency | Documented and tested: creating the same rule twice is allowed and is *not* idempotent (two rules), whereas the scheduler *is* idempotent per period |
| T. Restart behaviour | The scheduler catches up after downtime; proven by `catchUpPostsEveryMissedPeriod` |
| U. Configurability | Port 8080 unchanged; the scheduler's enable flag and cron are configuration, not constants |
| V. Language | All artifacts English |

One defect was found and fixed in this pass (§5.5). No criterion failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's rule by id | `404`, indistinguishable from a rule that does not exist (`notFoundAndNotYoursAreIndistinguishable`) |
| Delete another student's rule that has posted | `404`, not `409` — ownership is checked before the generated count, so the refusal cannot be used to probe |
| Create a rule under another student's category | `404` naming no field; the id is not found rather than found-and-refused |
| Send `type` on create to file an expense as income | Ignored. The entity takes the type from the category and the trigger re-checks it (`theTypeIsTheCategorysAndAClientSuppliedOneIsIgnored`) |
| Send `startDate` on update to rewrite the rule's origin | Not in the DTO; silently ignored (`startDateIsNotEditable`) |
| Send `lastRunDate` to make the scheduler skip a period | Not in either DTO |
| Send `status` on create to create a rule already stopped | Ignored; the server sets `ACTIVE` (`serverOwnedFieldsAreIgnoredOnCreate`) |
| Send `userId` to create a rule owned by somebody else | Not in either DTO |
| Move a rule onto a retired category | `400` naming `categoryId` |
| Edit any field of a rule whose category was retired | `409 CATEGORY_RETIRED` with both workarounds in the message — see §5.1, and **OB-009** |
| Delete a rule that has posted | `409 RECURRING_RULE_IN_USE`, remedy in the message |
| Delete a rule whose only transaction was soft-deleted | `409` — the defect in §5.2, now fixed and pinned |
| Send `amount: 0`, `-1`, or an over-precise value | `400` naming `amount`; `ck_recurring_amount` and `@Digits` both hold |
| Send `nextRunDate` after `endDate` | `400` naming `nextRunDate` — §5.4 |
| Send `2026-02-30` as an end date | `400` naming `endDate`, not a `500` — §5.3 |
| Send a frequency or status as a number | `400`; `fail-on-numbers-for-enums` is on project-wide |
| Send malformed JSON, or a non-numeric id | `400 BAD_REQUEST`, no schema identifiers in the body (`noResponseLeaksInternals`) |
| Use an expired, revoked or foreign JWT | `401` before the controller |
| Use a disabled account's token | `401` before the controller |
| Use an administrator token | `403` |
| Run the scheduler twice concurrently | One occurrence per period, one transaction per period (`concurrentSchedulerRunsAreIdempotent`) |
| Run the scheduler on two application instances | Same guarantee, by `INSERT IGNORE` against `uk_occurrence_rule_period` |
| Restart the application after a missed period | Catch-up posts every missed period, exactly once each |
| Reach "today" with a rule whose start date is in the past | Accepted, and caught up on — not special-cased |
| Make the scheduler create a future-dated row | Impossible: `WHILE v_next <= v_as_of` (`theSchedulerNeverWritesTheFuture`) |
| Delete the same rule from two requests at once | One deletion, one `404` (`twoSimultaneousDeletesProduceOneDeletion`) |
| Pause and end the same rule at once | Both succeed on the locked row, serialised; the second wins and reports it (`twoSimultaneousUpdatesBothSucceedOnTheLockedRow`) |
| Pause a rule while the scheduler is posting it | The pause is never lost, and the cursor and transaction count settle together — either nothing posted and the cursor has not moved, or the period committed and the cursor advanced (§5.9, `aPauseRacingTheSchedulerSettlesConsistently`) |
| End a rule while a run is in flight | No period is posted after the end takes effect (`anEndRacingTheSchedulerNeverPostsAfterEnding`) |
| Delete a rule while a run is in flight | Either `204` with no occurrence left behind, or `409` because the run posted first — never an occurrence without its transaction (§5.9, `aDeleteRacingTheSchedulerLeavesNoOrphanOccurrence`) |
| Restart an ended rule | `409 RECURRING_RULE_ENDED` — §5.8 |
| Pause a rule and expect its missed periods to be dropped | They are deferred and posted on resume — §5.12, **OB-010** |
| Run the scheduler on a server whose clock is UTC | The cron is pinned to `Asia/Ho_Chi_Minh`, so the run stays on the database's day — §5.10 |
| Make an edit rewind the scheduler's cursor | Prevented by `@DynamicUpdate` plus the row lock |
| Retire a category that has rules, then interact with them | The documented, tested, reported behaviour of §5.1 |
| Point a rule at a category, then change that category's type | Impossible: `trg_categories_before_update` refuses the type change once anything references the category |
| Find an internal error the driver would leak | A refused write rolls back with its transaction; an unrecognised failure is rethrown and answered as a `500` with a generic message |

**Nothing in this pass failed except the two limitations already recorded as deliberate (§5.1 → OB-009)
and fixed (§5.2).** No fixable security defect was left as a documentation note.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-09 | Set up a rule | `POST` | `createRule` | `create` | `recurring_rules`, `trg_recurring_rules_before_insert` | `createdRuleHasTheDocumentedShape` |
| UC-09 | List the student's rules | `GET` | `listRules` | `listRules` | `ix_recurring_user` | `theListIsScopedToTheCaller` |
| UC-09 | Read one | `GET /{id}` | `getRule` | `getRule` | `findByIdAndUserId` | `aRuleCanBeReadById` |
| UC-09 | Edit some fields | `PATCH` | `updateRule` | `update` | `@DynamicUpdate` | `updateChangesOnlyWhatItSends` |
| UC-09 | Pause / resume / end | `PATCH` | `updateRule` | `update` | `status ENUM` | `pauseResumeAndEndRunThroughStatus` |
| UC-09 | The lifecycle is `ACTIVE ↔ PAUSED`, either → `ENDED`, `ENDED` final | `PATCH` | `updateRule` | `update`, `requireEndableState` | `status ENUM` (the application's rule, not the schema's) | `theLifecycleAllowsExactlyTheDocumentedTransitions`, `anEndedRuleStillAcceptsEditsThatAreNotAStatusChange` |
| UC-09 | Pausing defers periods rather than skipping them | `PATCH` | `updateRule` | — | the cursor's `status = 'ACTIVE'` predicate | `pauseDefersPeriodsRatherThanSkippingThem` — **OB-010** |
| UC-09 B4 | A scheduled occurrence raises module 6's budget alert | — | — | — | `trg_transactions_after_insert` → `sp_check_budget_alerts` | `aScheduledTransactionRaisesTheBudgetAlertLikeAnyOther` |
| UC-09 A2 | An edit applies to future periods only | `PATCH` | `updateRule` | `update` | `uk_occurrence_rule_period` | `editingARuleAfterAnOccurrenceAppliesToFuturePeriodsOnly` |
| UC-09 | Remove one that never ran | `DELETE` | `deleteRule` | `delete` | `recurring_rules` DELETE | `aRuleThatHasNeverPostedCanBeDeleted` |
| UC-09 A1 | Catch-up after downtime | — | `postDueOccurrences` | `RecurringScheduler` | `sp_post_recurring_transactions` | `catchUpPostsEveryMissedPeriod` |
| BR-02 | Ownership | all five | `@AuthenticationPrincipal` | every lookup | `findByIdAndUserId` | `anotherStudentsRuleIsUnreachable` |
| BR-05 | The type is the category's | `POST`, `PATCH` | DTO field sets | `create`, `update` | both `recurring_rules` triggers | `theTypeIsTheCategorysAndAClientSuppliedOneIsIgnored` |
| BR-07 | A retired category is refused | `POST`, `PATCH` | — | `requireActiveCategory` | `sp_validate_recurring_rule` | `aRetiredCategoryCannotBeChosenOnCreate` |
| BR-07 | …and freezes its rules | `PATCH` | — | `update`, `CategoryRetiredException` | `trg_recurring_rules_before_update` | `aRetiredCategoryBlocksEveryUpdateToItsRules` |
| BR-08 | Future dates are allowed on a schedule | `POST`, `PATCH` | — | — | `sp_validate_transaction` exempts `RECURRING` | `aRuleInThePastIsAccepted`, `theSchedulerNeverWritesTheFuture` |
| BR-16 | Each period posts once | — | — | — | `uk_occurrence_rule_period` | `runningTwicePostsOneOccurrencePerPeriod` |
| §7.5 | No client identity, type or history | `POST`, `PATCH` | DTO field sets | entity setter set | — | `serverOwnedFieldsAreIgnoredOnCreate`, `startDateIsNotEditable` |
| §7.6 | No schema identifiers in a response | all five | — | `translateWriteFailure` | — | `noResponseLeaksInternals` |
| §13 P5 | Concurrency / idempotency | `PATCH`, `DELETE` | — | `@Lock(PESSIMISTIC_WRITE)` | `SELECT ... FOR UPDATE` | `twoSimultaneousDeletesProduceOneDeletion` |
| §16 | Cross-module regression | modules 3, 4 | — | — | — | `categoryRetirementInteractsAsDocumented`, `transactionEndpointsStillBehaveAfterScheduledPosting` |

The full UC → API → Controller → Service → Database → Test matrix, including every validation rule and
every refusal code, is in [`docs/api/recurring.md` §16](../api/recurring.md#16-traceability).

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` |
| Every field traced to a documented requirement | Yes — §9, and the API document's fuller matrix |
| Validation with per-field errors using `field` | Yes |
| Ownership enforced server-side, structurally | Yes — every single-row query takes the owner's id |
| No sensitive field in any response | Yes — `RecurringRuleMapper` omits `userId`, the row timestamps and the two day-hint columns |
| Tests through HTTP against real MySQL 8 | Yes — 87 HTTP tests + 11 unit tests |
| No schema change; `validate` holds | Yes — §3 |
| API document written; Angular can integrate without guessing | Yes — `docs/api/recurring.md`, including the rewiring the current mock needs |
| Inventory updated; no duplicate endpoint | Yes — endpoints 22–26 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 315 tests |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — the two open items are a schema-owned limitation recorded as OB-009, and not a missing feature |

---

## 11. Deferred / blocked

Nothing in this module is blocked. One limitation is recorded for a decision, and three global items
affect it and are in `OVERNIGHT_BLOCKERS.md`.

**Recorded for the owner's decision — OB-009:**

- **A retired category freezes every rule filed under it**, including the rule's ability to be ended
  (§5.1). The module behaves correctly and safely within the schema's rule; the schema's rule is what
  cannot be worked around. Three remedies are listed in OB-009, the smallest being a `p_require_active`
  parameter on `sp_validate_recurring_rule` mirroring what `sp_validate_transaction` already has — a
  schema change, and therefore the owner's call under §4. Pinned by two tests.

**Recorded for the owner's decision — OB-010:**

- **A pause defers its periods rather than skipping them** (§5.12), which is the opposite of what
  UC-09's wording suggests. It is the procedure's behaviour and the procedure is frozen, so the choice
  is to accept the deferral as the intended reading, approve a procedure change under §4, or fix the
  frontend's wording. Pinned by `pauseDefersPeriodsRatherThanSkippingThem`, and its client-visible
  consequences are documented in `docs/api/recurring.md` §11.

**Closed by this module:**

- **Module 4's deferred list bound** (§5.6). `theSchedulerNeverWritesTheFuture` proves the scheduler
  cannot create a future-dated row, so module 4's default upper bound of today is correct rather than
  conservative, and its deferred item is closed with no contract change on either side.

**Global, not fixable in this module:**

- **OB-012** (`insights`/`import_rows` stay plaintext with the locked module 12) and **OB-013**
  (amounts deliberately not encrypted) apply as they do project-wide. A recurring rule's `amount` is
  one of the columns OB-013 covers: `sp_post_recurring_transactions` reads it and the views aggregate
  the transactions it produces, so it cannot be encrypted without moving the reporting tier.
- **OB-001** (source documents absent) applies as it does to every module: UC-09's behaviour is derived
  from the schema and its procedure bodies, which encode BR-16, the catch-up loop, the period keys and
  the retired-category rule directly, rather than quoted from the Use Case document.
- **OB-003** (secret store) and **OB-004** (per-instance throttle) are unaffected by this module — it
  has no credentials and no authentication endpoint.
- **OB-006** (`import_rows` ownership) is module 12.

**Added after this module closed — application-level field encryption, and OB-014:**

- **`recurring_rules.description` now holds ciphertext at rest.** Same treatment as
  `transactions.description`: `RecurringRuleService` encrypts on write, `RecurringRuleMapper`
  decrypts on read, and the column is `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin`.
  The rule's `amount` is **not** encrypted (OB-013).
- **The scheduler copies the rule's envelope into the posted transaction.** `sp_post_recurring_transactions`
  builds each posted row from the rule and copies `r.description` through unchanged; a procedure
  cannot decrypt, and must not, because that would require the key inside MySQL. The copied value is
  a valid envelope under the current key, so the posted transaction reads back as the rule's original
  text — verified end to end. This is a **limitation with no user-visible effect**, not a defect, and
  it is recorded as **OB-014** with the reasoning and the alternative that was rejected.
- **A real defect this work introduced and fixed:** the procedure's local variable was `VARCHAR(255)`,
  too small for an envelope of up to 2048 characters, so every run failed with
  `Data too long for column 'v_desc'`. It is now `VARCHAR(2048)`, sized to the column. Caught by six
  failing recurring tests before it reached the suite.
- Pinned by `postedTransactionDescriptionSurvivesTheScheduler` and by M5-28 in the manual procedure,
  which tells a tester explicitly that two identical ciphertexts across the rule and its posted
  transaction are expected rather than a reused-IV bug.

**Not claimed:** this module is production-ready. It is implemented, tested, reviewed and documented,
and it depends on no external service that is missing. Its one open item is a schema-owned limitation
awaiting a decision, not an unimplemented behaviour.
