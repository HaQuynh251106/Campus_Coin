# Module 9 — Saving Tips (UC-18)

| | |
|---|---|
| **Endpoints** | `GET /api/v1/tips` (38), `GET /api/v1/tips/months` (39), `POST /api/v1/tips/generate` (40), `POST /api/v1/tips/{id}/state` (41) |
| **Requirements** | UC-18, UC-18 A1/A2, BR-02, BR-14, BR-15, VĐ-04, VĐ-05, VĐ-10, SRS §7.5, §13, §26 |
| **Schema objects read** | `v_dashboard_tips`, `user_tips`; `sp_generate_tips` reads `v_budget_consumption`, `v_category_spend_trend`, `v_category_month_totals` and `tip_templates` |
| **Schema objects written** | `user_tips` — by `sp_generate_tips` for the inserts, and by Hibernate for the one state change. **No schema object was changed** |
| **Tests** | 49 new (23 `TipsApiIT` + 11 `TipsRuleCoverageIT` + 7 `TipsStateConsistencyIT` + 7 `TipGenerationSchedulerTest` + `OpenApiContractIT` 11 → 12); suite 462 → **511** |
| **Status** | Complete. No blocker, nothing deferred |

---

## 1. Scope

UC-18 is the tips screen: a student is shown saving advice derived from their own spending, and may
keep the advice they care about or throw away the advice they do not.

**The module's defining fact is that the database writes the tip and the application writes only its
state.** Which tips exist, what they say, what each is worth and in what order they appear are
`sp_generate_tips`'s and `v_dashboard_tips`'s answers. The one column UC-18 lets a student change is
`state`, and §4.4 is about how that single column is written safely.

**Four endpoints, one per question UC-18 asks.**

| # | Endpoint | Carries |
|---|---|---|
| 38 | `GET /api/v1/tips?month=` | One month's tips, already ranked |
| 39 | `GET /api/v1/tips/months` | The months that have tips, so a picker offers only months that return something |
| 40 | `POST /api/v1/tips/generate` | "Give me this month's advice now" |
| 41 | `POST /api/v1/tips/{id}/state` | Pin, dismiss or clear one tip |

All four are `STUDENT`-only. §2 lists what was considered and rejected with the reason.

### What is deliberately not built

| Excluded | Why |
|---|---|
| `userId` anywhere | The account is the bearer token's. A tip names a category and an amount from one student's spending, so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/tips/{id}` | A tip is only meaningful inside its month's ranked list. A bare `{id}` read would be the same row without the ordering that gives it meaning |
| `POST /api/v1/tips` | No human writes a tip — the text is rendered by `fn_render_template` from a `tip_templates` row |
| `PATCH`/`PUT /api/v1/tips/{id}` | The state change writes two columns together (§4.4), so it is not a field patch |
| `DELETE /api/v1/tips/{id}` | Deleting the row removes the `dedupe_key` that stops the generator bringing the tip straight back. Dismissal is how a tip is retired |
| `POST /{id}/pin`, `/{id}/unpin`, `/{id}/dismiss` | Three names for one write of one column with three values (§13) |
| `?state=` on endpoint 38 | The response carries the state per row and is already ordered pinned-first. A filter would restate the view's own rule |
| `month` on endpoint 40 | Generating for an arbitrary past month would let a client pull advice about a month whose tips were never meant to be shown |
| An export route | A tip list is not a report; BR-18 is about reports (§13) |
| A `LOW_SAVINGS_RATE` rule | The template row exists but the generator never uses it. That rule is UC-17's — module 12, which is **locked** (§4.3) |

---

## 2. Endpoints

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 38 | `GET` | `/api/v1/tips` | UC-18 | Bearer, `STUDENT` | `200` | `400` `401` `403` |
| 39 | `GET` | `/api/v1/tips/months` | UC-18 | Bearer, `STUDENT` | `200` | `401` `403` |
| 40 | `POST` | `/api/v1/tips/generate` | UC-18 | Bearer, `STUDENT` | `200` | `401` `403` |
| 41 | `POST` | `/api/v1/tips/{id}/state` | UC-18 | Bearer, `STUDENT` | `200` | `400` `401` `403` `404` |

Parameter surface: `month` (optional, `yyyy-MM`) on 38; `id` in the path on 41; one body field
(`state`) on 41. **No endpoint takes a request body except 41, and 41's body has exactly one field**,
so there is no mass-assignment surface. `OpenApiContractIT` asserts that no `CreateTipRequest`,
`UpdateTipRequest`, `DeleteTipRequest`, `TipRequest` or `GenerateTipsRequest` schema exists.

`SecurityConfig` gained one rule, before the `/api/**` catch-all:

```java
.requestMatchers("/api/v1/tips/**").hasRole("STUDENT")
```

A tip's title and body are readable prose about one student's spending. Admitting the administrator
role would let it read a named student's figures through a route never meant to name anyone — the
reason `/api/v1/dashboard/**` is guarded the same way. Administrator work on tip *templates* (UC-20)
is a different table and belongs under `/api/v1/admin/**` in module 11.

---

## 3. Database alignment

### 3.1 What the database owns, and this module therefore does not restate

| Concern | Owner | Where |
|---|---|---|
| Which tips exist, and what they say | DB | `sp_generate_tips` rules 1–6 (`db/03_procedures.sql` 455–585), text rendered by `fn_render_template` |
| What a tip could save, and how tips rank | DB | the procedure's `potential_saving` / `rank_score`, and BR-14's `ROW_NUMBER() OVER (ORDER BY rank_score DESC, potential_saving DESC)` |
| How many tips are shown | DB | `tips.max_dashboard` in `system_settings`, read by the procedure when `p_max_tips IS NULL` |
| Which tips are visible | DB | `v_dashboard_tips`'s `WHERE state <> 'DISMISSED'` (BR-14) |
| The order tips are shown in | DB | the view's `ORDER BY (state = 'PINNED') DESC, rank_score DESC` as `display_order` |
| That a tip is not generated twice | DB | `INSERT IGNORE` against `uk_tip_dedupe` = `user_id\|period_month\|tip_template_id\|category_id` |
| Which state may carry which timestamp | DB | `ck_tip_state` |
| Every threshold the rules compare against | DB | `budget.near_threshold_pct`, `budget.exceeded_threshold_pct`, `insight.spike_threshold_pct`, `insight.spike_baseline_months`, `app.currency_symbol` |

`TipService` never composes advice, never decides how many tips, never ranks one, never fills a gap.
The only figure it produces is a string: the first-of-month `DATE` as `yyyy-MM`.

### 3.2 The module's own work

- Which month a request means, and refusing one that is not a month (§4.2).
- That a tip is reachable only by its owner, structurally.
- Which transitions are offered, and that dismissal is one-way (§4.4).
- When to ask the generator to run — on demand, and daily (§4.5).
- Keeping `state` and its paired timestamp consistent on the write.

### 3.3 `TipViewDao` restates the view's order, and that is not a second ranking

SQL makes no promise about the order of an unordered result, so `v_dashboard_tips` returning rows in
its window order is not something to rely on. `SELECT_TIPS` names the same two keys the view ranks by
— pinned first, then `display_order` — and adds `tip_id` so two equally-ranked tips cannot swap
places between two calls of the same endpoint.

It **asks for** the order rather than re-deriving it: `display_order` is read, not recomputed. The
ranking is still the view's, and the `tip_id` tie-break is a determinism guarantee the view does not
give. `DashboardViewDao` makes the same restatement for the same reason.

### 3.4 The view has no time filter, so every query names a month

`v_dashboard_tips` partitions by `period_month` for its `ROW_NUMBER` but returns **every** month the
student has tips for. Left uncorrected, a tips screen would show September's advice interleaved with
August's under one heading — the same gap module 7's `DashboardViewDao` has to close, found the same
way (reading the view body, not its name).

Every query in `TipViewDao` carries `AND v.period_month = :periodMonth`, including the months query,
where the predicate is the query's whole subject.

### 3.5 The entity is loaded to write one column, so `@DynamicUpdate` is applied

`UserTip` is loaded, its state is changed, and it is flushed. Without `@DynamicUpdate`, Hibernate
writes every mapped column back with the values read when the request began — including `title`,
`body`, `potential_saving` and `rank_score`, which only `sp_generate_tips` may set. A concurrent
generation of the next month's tips would then be partially undone by a pin request carrying a stale
copy of the row. `TipsStateConsistencyIT#aStateChangeDoesNotRewriteTheGeneratedColumns` asserts it.

`dedupe_key` is deliberately **unmapped**: it is a `VIRTUAL` generated column that exists so
`uk_tip_dedupe` can work, and mapping it would invite Hibernate to try to write a value the database
computes itself — the same choice made for `scope_key` on `Category` and `key_hash` on
`PasswordResetToken`.

### 3.6 `db/` is untouched

**A caveat on the usual evidence.** `db/` is **untracked** in this repository
(`git status --short db/` reports `?? db/`, and `git ls-files db/` is empty), so `git diff --stat --
db/` prints nothing **whatever the files contain** — an untracked path produces no diff. That command
therefore cannot substantiate "`db/` is unmodified" here, and is not offered as proof.

What does substantiate it: every file under `db/` has a modification time from **2026-09-24**, before
any Module 9 work began, and none was modified on 2026-09-25 (`find db/ -name '*.sql' -newermt
'2026-09-25'` returns nothing). The newest is `db/merged/campuscoin_full.sql` at 2026-09-24 20:57. No
table, column, index, constraint, trigger, view, procedure, function or seed row was changed, and the
module's own test runs confirm it — they load `db/merged/campuscoin_full.sql` into a fresh MySQL 8
container and the schema-level assertions (`CampusCoinApplicationTests` under `ddl-auto=validate`,
`TipsStateConsistencyIT` against `ck_tip_state`) pass unmodified.

The one thing that looked like it needed a schema change — no procedure exists for a tip's state —
was accommodated in the application (§4.4) rather than by adding one.

---

## 4. Implementation

### 4.1 Layering

```
TipController ──► TipService ──┬──► TipViewDao            (native queries on v_dashboard_tips)
                               ├──► UserTipRepository     (the entity, for the one write)
                               ├──► TipGenerationDao      (CALL sp_generate_tips)
                               └──► TipMapper             (the single publication gate)

TipGenerationScheduler ──► TipGenerationDao ──► sp_generate_tips
```

`tips/entity/TipRow` is a record, not an entity: every read is a native projection by alias, so a
column added to or reordered in the view cannot break the mapping — the pattern `DashboardViewDao`,
`BudgetConsumptionDao` and `ReportViewDao` each established.

### 4.2 The month is resolved once, and strictly

`TipService.resolveMonth` accepts `yyyy-MM` through `YearMonth.parse` and refuses `2026-9`, `202609`,
`2026-13`, `2026`, `2026-09-01`, `September` and `not-a-month` — each with `400 VALIDATION_ERROR`
carrying a field error naming `month`. A malformed month is never coerced into a different, valid one,
because a request that means October must not be answered as September.

The zone is `Asia/Ho_Chi_Minh` (`+07:00`), the same offset the database session is pinned to by
`hikari.connection-init-sql: SET time_zone = '+07:00'`, and the same constant `ReportService` and
`TransactionService` use. **`TipService` is the only place that resolves "this month" for a tips
request**, so an omitted `month` and `/generate` cannot disagree about which month is current.

### 4.3 `LOW_SAVINGS_RATE` is a template the generator never uses

`tip_templates` holds seven rows; `sp_generate_tips` names six of them — `db/03_procedures.sql` lines
473, 490, 507, 525, 548, 563. `LOW_SAVINGS_RATE` is the seventh and no code path reaches it.

That was found by reading the procedure body while drafting the contract document, not by a test, and
it is **recorded rather than fixed**: the rule is UC-17's (monthly insights), which is module 12 and
is locked. Implementing it here would implement a use case this module does not own. The row is seed
data; nothing in `db/` was touched.

### 4.4 The state change: an entity write, because the schema provides no procedure

The budget and notification modules move their one writable field with a stored procedure
(`sp_mark_notification_read`, `sp_soft_delete_transaction`) because the schema provides one, and the
procedure folds the ownership check into the same statement as the write. **The schema provides no
procedure for a tip's state** — pinning and dismissing are not among the 24.

So the closest available arrangement is the one the recurring-rule module uses for its `status`: a
single-column update whose ownership is checked first by the load that names the caller.
`UserTipRepository.findByIdAndUserId` therefore carries `@Lock(LockModeType.PESSIMISTIC_WRITE)`, with
the javadoc recording why the row is locked: both of the method's decisions — whether dismissal is
being reversed, and whether anything is changing — are answers to "what state is it in now", so two
lock-free decisions could both be taken and the later write silently replace the earlier. A caller
who pinned could be told `PINNED` while the row ended up dismissed.

There is deliberately **no `JOIN FETCH`** on that query: `SELECT … FOR UPDATE` on a join locks rows in
every joined table, and the tip's category is not needed to make the decision.

**The write is two columns, which is what makes it a `POST` sub-path rather than a `PATCH` of a
field.** `ck_tip_state` requires `PINNED` to carry a `pinned_at`, `DISMISSED` a `dismissed_at`, and
`NEW` neither. `UserTip.setState(TipState, LocalDateTime)` derives both fields from the one argument,
so a pinned tip with no pinned time is unrepresentable through this class. That is the same shape
`POST /api/v1/notifications/{id}/read` uses, for the same reason: the server decides what a state
change writes.

### 4.5 Two callers of one generator, on purpose

| Caller | Month passed | Why |
|---|---|---|
| `TipService.generateTips` (endpoint 40) | The current month, resolved by the application | An explicit value makes the endpoint's semantics deterministic and identical to a `null` for a current-month run |
| `TipGenerationScheduler` | `null` | So the procedure uses `CURDATE()` as the **database session** sees it. A JVM in another zone cannot disagree with the database about the current month for part of every day |

`TipGenerationDao` exposes `generateTips(userId, month)` and `findActiveStudentIds()`, and
deliberately **does not** expose a bulk method: the transaction boundary is the caller's to draw,
which is what lets a bulk run continue past one student's failure.

### 4.6 The scheduler's properties, and the one that is not a guarantee

| Property | Where |
|---|---|
| Off in tests | `campuscoin.tips.scheduler.enabled = false` set by `AbstractMySqlIntegrationTest`, so a timer cannot write `user_tips` rows mid-assertion |
| Daily at `0 10 0 * * *`, `Asia/Ho_Chi_Minh` | `campuscoin.tips.scheduler.cron`, `${TIPS_SCHEDULER_CRON:0 10 0 * * *}` |
| No DB `EVENT` | The schema is locked and schedules nothing else; a DB event would also be invisible to the application's logs and configuration. The application-side schedule is the arrangement module 5 established |
| Safe on more than one instance | `INSERT IGNORE` against `uk_tip_dedupe`; no leader election, for the reason `RecurringScheduler` records (BR-16) |
| One transaction per student | The loop is in the scheduler, calling `generateTips` once per student |
| A failure never escapes | `catch (RuntimeException)` and log. A scheduled method that throws is logged by the framework and not retried until the next tick; catching it keeps that behaviour while letting the log line say what failed |
| No retry inside a run | A failure here is a fault, not a transient collision — the dedupe key makes a collision impossible. The next tick is the retry |
| Who it covers | Every `STUDENT` account with `status = 'ACTIVE'`, not just those with spending |

**The ten-minute offset from the recurring run is a configuration convenience, not a guarantee.** The
intent is that on the first of the month the recurring job posts that day's transactions before the
tips are generated, so the advice sees them. A failed or delayed recurring run makes that a lag, not a
correctness problem: the procedure is idempotent, so a later run simply produces the tips with the
transactions in view. It is not stated anywhere as a guarantee that the two ran in order.

---

## 5. Defects found and fixed

### 5.1 A bulk generate would have rolled back every other student's tips (found by reasoning, before any test)

The first draft of `TipGenerationDao` had a single `@Transactional` method
`generateTipsForActiveStudents(...)` holding the loop over every active student, with each student's
failure caught, counted, and **rethrown at the end**.

That arrangement is wrong in a way that only shows up when something else fails. A method annotated
`@Transactional` that catches an exception and then throws another one at the end marks the
transaction **rollback-only** at the point of the first caught failure — Spring's default for a
non-checked exception crossing a transactional boundary. Every tip already generated for every
student earlier in the loop is then discarded, and the count the method returned describes work that
never happened.

**Fixed by removing the bulk method entirely**, exposing `findActiveStudentIds()` instead and moving
the loop into `TipGenerationScheduler` so each `generateTips` call is its own transaction. That is
what makes "a failed student costs its own student and no other" true rather than aspirational, and
`TipGenerationSchedulerTest#aFailedStudentDoesNotStopTheRun` asserts it with the failing student in the
middle of three.

### 5.2 A duplicated constant that would not compile

`TipViewDao` declared `SELECT_MONTHS` **twice** — a copy-paste artefact of the same SQL block. Two
identical `private static final String` declarations with the same name in one class is a compile
error, so it would not have survived a build; it was caught by reading the file back rather than by
the compiler, because the build had not been run since the second copy was pasted.

Fixed by deleting the second copy with no other change.

### 5.3 A fixture silently moved a transaction into the wrong month

`AbstractTipsApiIT.spendInCategoryWithoutBudget` originally clamped the transaction date to today
when the requested month's first day was in the future:

```java
LocalDate date = month.withDayOfMonth(1).isAfter(today()) ? today() : month.withDayOfMonth(1);
```

A test that asked for November would have had its transaction filed in September — and then asserted
about a tip in November. The test would have passed for the wrong reason or failed for a reason that
had nothing to do with the module.

**Fixed by refusing the fixture instead of adjusting it:**

```java
assertThat(date).as("cannot file spending in a month that has not started").isBeforeOrEqualTo(today());
```

with a comment recording that BR-08 refuses a future-dated manual transaction, so a month that has not
started cannot be spent in at all. A fixture that cannot honour what it was asked for should say so.

### 5.4 A tautological assertion that could never fail

`askingForTheStateATipAlreadyHoldsIsNotAnError` compared a tip's `pinned_at` to **itself** behind a
pointless ternary:

```java
assertThat(tipColumnOf(tipId, "pinned_at"))
        .isEqualTo(tipColumnOf(tipId, "pinned_at") == null ? null : ...);
```

It asserted nothing. Replaced with a real before/after: capture `pinned_at` after the first pin,
assert it is not null, then assert the second pin did **not** restamp it. The property the test claims
— the timestamp is the one from when the state was first taken — is now actually tested.

### 5.5 A test that reimplemented the code it was testing

`TipGenerationRunTest` was written first. It contained its own copy of the scheduler's loop — iterate
the student ids, call the DAO, catch per student — and asserted the copy's behaviour.

That test would have passed after any change to `TipGenerationScheduler`, including deleting the
per-student catch, because it never touched the real class.

**Deleted and replaced with `TipGenerationSchedulerTest`**, which constructs the **real**
`TipGenerationScheduler` with a Mockito stub of `TipGenerationDao` and asserts who called whom, with
what, how many times, and what happens when one of them fails. The javadoc records exactly this
reasoning.

### 5.6 A Mockito matcher that cannot match `null`

The scheduler passes a literal `null` for the month. The first draft verified the call with
`verify(dao).generateTips(eq(2L), any(LocalDate.class))`. **`any(LocalDate.class)` does not match
`null`** in Mockito 5 — it means "any non-null `LocalDate`" — so that verification fails.

Fixed by using the literal `null` (which Mockito asserts *is* null) where the argument is checked, and
`isNull()` from `ArgumentMatchers` where a matcher is required in a stubbing position:

```java
verify(dao, times(1)).generateTips(2L, null);
doThrow(new IllegalStateException("...")).when(dao).generateTips(eq(7L), isNull());
```

The distinction is load-bearing: the month being null **is the contract** — it is what leaves "this
month" to the database session (VĐ-10). A matcher that silently accepted a non-null value would have
stopped asserting it.

### 5.7 The contract test over-claimed the `state` enum's reachable values

`Text` in the first draft of `tipSchemasMatchTheDocumentedContract` asserted that `TipResponse.state`
publishes only `NEW` and `PINNED`, on the reasoning that the view excludes `DISMISSED`.

It does exclude it — but **both sides of that assertion are generated from the same Java type**,
`TipState`, so Swagger publishes all three members on both the response and the request. The
assertion failed, and it was the assertion that was wrong, not the type: collapsing the response enum
to two members would mean a second Java enum over one column, which is exactly the drift the shared
type avoids.

**Fixed in three places rather than by weakening the test:**

1. The contract test now asserts all three members are published on both, and that the two member
   lists are **equal to each other** — which is the real property (one column, one vocabulary).
2. `TipResponse`'s `state` `@Schema` description had independently claimed only two values reach a
   response. It now says the schema advertises three because the type is shared, and that the rule
   keeping `DISMISSED` out is the **view's**, not the type's.
3. `TipsApiIT#aTipCarriesItsRenderedTextAndNothingInternal` asserts the actual reachable values
   against real rows, because a schema cannot express a rule that belongs to a view.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `tips/TipsApiIT.java` | 23 | HTTP → Controller → Security → Service → DAO/Repository → MySQL 8 (Testcontainers) |
| `tips/TipsRuleCoverageIT.java` | 11 | HTTP only — the four generation rules reachable through the budgets, profile and transactions routes (`OVER_BUDGET`, `NEAR_BUDGET`, `CATEGORY_SPIKE`, `SAVINGS_GOAL_AT_RISK`) |
| `tips/TipsStateConsistencyIT.java` | 7 | The real `ck_tip_state` constraint, exercised by applying the *wrong* writes directly |
| `tips/TipGenerationSchedulerTest.java` | 7 | Unit — the real scheduler class with a stubbed DAO |
| `support/OpenApiContractIT.java` | 12 | Contract — 1 of its tests is new for this module, 3 others extended |

Full suite: **511 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (1:50 min). Module 8's
baseline was **462**; module 9 adds **49** (23 + 11 + 7 + 7 + `OpenApiContractIT` 11 → 12).

Per-class totals (`Tests run:` from the surefire report):

| Class | Tests | | Class | Tests |
|---|---|---|---|---|
| `RecurringRuleApiIT` | 87 | | `PasswordResetApiIT` | 14 |
| `TransactionApiIT` | 57 | | `BudgetWriteFailureTest` | 12 |
| `BudgetApiIT` | 45 | | **`OpenApiContractIT`** | **12** |
| `CategoryApiIT` | 44 | | **`TipsRuleCoverageIT`** | **11** |
| `ProfileApiIT` | 31 | | `RecurringRuleWriteFailureTest` | 11 |
| `DashboardApiIT` | 27 | | `TransactionWriteFailureTest` | 9 |
| **`TipsApiIT`** | **23** | | `AdminAuthApiIT` | 8 |
| `NotificationApiIT` | 20 | | `CategoryWriteFailureTest` | 8 |
| `ReportsApiIT` | 20 | | **`TipsStateConsistencyIT`** | **7** |
| `SpendingReportApiIT` | 20 | | **`TipGenerationSchedulerTest`** | **7** |
| `SecurityHardeningIT` | 19 | | `CampusCoinApplicationTests` | 1 |
| `AuthenticationApiIT` | 18 | | | |
| | | | **Total** | **511** |

### What the four suites cover

**`TipsApiIT` (all four endpoints, UC-18)** — reading: an omitted month answers and names the current
one; a month with no tips is `200` with an empty array; reading generates nothing; a tip carries its
rendered text and none of the internal columns; five malformed months refused with a field error;
pinned tips lead while the rest keep the database's order. State changes: pinning stamps the state and
its timestamp together; clearing a pin returns the tip to `NEW`; a dismissed tip stays dismissed
across a regeneration; a month whose only tip was dismissed leaves the months list; asking for the
state a tip already holds is not an error and does **not** restamp it; a dismissed tip cannot be
restored; an unknown or missing state is refused. Ownership: tips are scoped to their owner; an
unknown tip answers the same `404` as another student's; no token is `401`/`403` and no route takes a
user id. Generating: generating twice changes nothing; the response is the resulting list; a student
with no data gets rule 6's tip; the months list matches the months that have tips; a student with no
tips is offered no months. Concurrency: two pins of one tip settle on one state; generating while
pinning does not duplicate a tip.

**`TipsStateConsistencyIT`** — this suite asserts the **schema's** constraint, by applying the writes
the API must never make: pinning without a timestamp, pinning with a stale dismissal timestamp, a
`NEW` tip carrying either timestamp, dismissing without a timestamp. Each is refused by MySQL with a
`SQLException`, and each verifies the row is untouched. Then the positive side: the write the API
performs **is** accepted; a state change does not rewrite the generated columns (`@DynamicUpdate`);
and a tip's month is stored as the first of its month.

**`TipsRuleCoverageIT` (11, the four rules the first suite cannot reach)** — rules 1 and 2 at their
band edges: spending exactly on the exceeded threshold produces `OVER_BUDGET` alone with a `0.00`
figure, spending exactly on the near threshold produces `NEAR_BUDGET` alone, and spending inside the
band produces one tip rather than one per rule. Rule 3: a rise against a **single** earlier month
spikes (and the view's `baseline_months` is asserted to be `1`, pinning the §4.6 gate), while a first
purchase in a category is not a rise. Rule 5: net below the goal produces the tip with the shortfall
as its figure and no `categoryId` key at all, net meeting the goal produces none, and rule 5 coexists
with rule 4 in one month. Plus BR-14: the same four candidate tips are stored three ways and five
ways, proving the bound is the caller's parameter and the setting decides when it is unset; and rule
4's own `LIMIT 2` keeps the two largest unbudgeted categories. The suite identifies a rule from
`tip_template_id`, never from the rendered title (see §6), and pins the two-decimal wire scale against
the raw body. Every fixture builds its precondition through the public budgets, profile and
transactions routes.

**`TipGenerationSchedulerTest`** — the run covers every active student once and leaves the month to
the database (a literal `null`); it keeps the order it was given; a failed student does not stop the
run and does not skip the ones after it; a failed student is not retried within the run; a failure to
read the student list does not propagate; an empty run is not a failure.

### Test design note

**The fixtures write spending, not tips.** A tip is not something a test can sensibly invent: which
tips exist, what they say and what they are worth are `sp_generate_tips`'s answers about the
student's own records, so a hand-inserted `user_tips` row would test the API against data the
generator would never produce — a title naming a category the student never spent in, or a
`rank_score` that does not match BR-14's ordering. Every fixture builds the **spending** and then
either lets the endpoint generate the tips or calls the same procedure directly with an explicit
month.

**The one exception is stated as one.** `TipsStateConsistencyIT` writes `user_tips` rows directly —
that is its entire purpose, since a constraint can only be tested by asking the database to refuse a
write. It writes them with the values the generator would produce (a real category, a real template)
and asserts the *schema's* response, not the API's.

### All six rules are driven, and two suites do it

**Rules 1, 2, 3 and 5 of `sp_generate_tips` each need a precondition that no single API call
creates** — a limit set and then crossed (rules 1, 2), an earlier month to compare a spike against
(rule 3), or a savings goal with a month whose net falls below it (rule 5). `TipsApiIT` drives
**rule 4** (`NO_BUDGET_SET`, reachable from one transaction in an unbudgeted category) and **rule
6** (`GENERIC`, reachable by having no records at all).

`TipsRuleCoverageIT` (11 tests) was written for the other four. Every precondition it needs is
reachable through a **public route** — `POST /api/v1/budgets` for a limit, `PATCH
/api/v1/profile/me` for the savings goal, `POST /api/v1/transactions` for the spending — so the
fixtures build them the way a client would and nothing in the suite writes to the database to
create what the API could not. An earlier draft of this document recorded those four rules as a
**coverage limitation**; that was a limitation of the fixtures I had written, not of the schema, and
it is corrected here rather than left standing.

Two things the rule tests deliberately do not assert:

- **The rendered text.** A test identifies the rule that fired from `user_tips.tip_template_id` —
  the database's own statement of which rule produced the row, and the value `uk_tip_dedupe` is
  built from — never by matching the title. Matching the title would assert
  `fn_render_template`'s output and the template's wording, which this module neither owns nor may
  restate (§3 / §13).
- **Which tips win the ranking.** Ranking is BR-14's and is asserted as a **bound**
  (`theStoredTipCountIsBoundedByTheSettingsTopN`), not as a predicted order: predicting the order
  would mean recomputing every rule's `rank_score` in Java, which is the duplication §13 forbids.

The one rule figure that *is* asserted is `potential_saving`, because it is published and it is the
rule's arithmetic — e.g. rule 2's `(30.00 - 24.00) * 0.5 = 3.00`, rule 5's `100.00 - 50.00 = 50.00`.
`aPublishedSavingCarriesTwoDecimalsOnTheWire` pins the wire scale against the **raw body**, since a
parsed node cannot distinguish `8.00` from `8.0`.

The seeded month still carries the output of rules 2, 3 and 5 as rows, read by module 7's
`DashboardApiIT#theDemoDashboardReportsTheSeededMonth` and by this module's manual procedure M9-02 —
but that is coverage of the *output*. The rule **boundaries** are covered by `TipsRuleCoverageIT`,
which places spending exactly on a threshold (`30.00` against a `30.00` limit) and one step inside it.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every field traces to UC-18 or to a view (§9). Nothing is published that no use case asks for; `rankScore`, `dedupeKey` and the timestamps are deliberately unmapped |
| B. Duplicate / missing endpoints | None. Four paths, four methods, one per question UC-18 asks. `OpenApiContractIT#noEndpointIsDuplicated` carries an explicit `doesNotContain` block for `/tips/all`, `/list`, `/my-tips`, `/for-me`, `/profile/me/tips`, `/{id}`, `/{id}/pin`, `/{id}/unpin`, `/{id}/dismiss`, `/export`, `/history` and `/current`, so an alias fails the build |
| C. Layering | Controller → Service → DAO/Repository → MySQL. `TipRow` is a record, not an entity; no DAO is called from the controller |
| D. Validation placement | In the service for the month (so the refusal carries a field error in the standard shape) and by `@Valid @NotNull` on the request body for the state. Every refusal names its parameter |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`. **No new error code was added** — every failure is the pre-existing `VALIDATION_ERROR`, `UNAUTHENTICATED`, `ACCESS_DENIED` or `NOT_FOUND` |
| F. Transaction boundaries | Reads `readOnly = true`; generate and the state change are writes. One `@Transactional` per student in a bulk run (§5.1). Not annotated mechanically |
| G. N+1 and fetch strategy | One query per read, none in a loop. The state change is one `SELECT … FOR UPDATE` and one `UPDATE`. `open-in-view: false` |
| H. Locking | `PESSIMISTIC_WRITE` on the state change, with the reason recorded on the repository (§4.4). No `JOIN FETCH`, so the lock covers one table |
| I. Schema coupling | `ddl-auto: validate` holds. Native queries projected by alias; the enum column carries `columnDefinition` so `validate` accepts the MySQL `ENUM` |
| J. Security | §9 of [`docs/api/tips.md`](../api/tips.md): identity from the token only, every query scoped by `user_id`, role enforced in `SecurityConfig` |
| K. Sensitive output | `TipMapper` is the single gate. `userId`, `rankScore`, `dedupeKey`, `generatedAt`, `pinnedAt`, `dismissedAt` and `tipTemplateId` are unmapped, and `aTipCarriesItsRenderedTextAndNothingInternal` plus `OpenApiContractIT#tipSchemasMatchTheDocumentedContract` compare against literal field lists |
| L. Logging | Two lines: the generation (`userId`, `periodMonth`) and the scheduler's summary (`students`, `failures`). No tip text, no token, no credential. The state change logs `userId`, `tipId`, and the two states — not the tip's prose |
| M. Dead code | `TipRow` is used by the mapper; `TipState`'s three members all reach the service; the DAO's `findActiveStudentIds` is used only by the scheduler and the bulk method was removed rather than left (§5.1) |
| N. Naming | `TipViewDao` mirrors `DashboardViewDao`; `TipGenerationDao` mirrors `RecurringProcedureDao`; `TipMapper` mirrors the other mappers; `TipGenerationScheduler` mirrors `RecurringScheduler` |
| O. Documentation | [`docs/api/tips.md`](../api/tips.md), inventory rows 38–41, this report, and the manual procedure |
| P. Tests through HTTP | 49 new, all against real MySQL 8 except the scheduler unit test, whose subject is a class the HTTP path cannot reach deterministically (a timer). `TipsRuleCoverageIT` reaches its preconditions through the public routes rather than writing to the database, so a rule asserted there is reached the way a client reaches it |
| Q. Empty state | Asserted separately for a month with no tips (empty array, `200`), a month whose only tip was dismissed (drops out of the months list), and a student who has never generated (empty months array) — three different absences with three different consequences |
| R. Determinism | The list carries an explicit `tip_id` tie-break, so two equally-ranked tips cannot swap between calls. The months query orders newest-first explicitly |
| S. Idempotency | `generatingTwiceChangesNothing` for the generator, and asking for a state a tip already holds for the write |
| T. Restart behaviour | No state in memory. The month is resolved per request; the scheduler holds nothing between runs |
| U. Configurability | `campuscoin.tips.scheduler.enabled` / `.cron` with env overrides, both documented in `application.yml` and `.env.example`. `max_tips` is left to `system_settings` rather than sent from code (VĐ-05) |
| V. Language | All artifacts English |

Two behaviour defects (§5.1 was found by reasoning before any test; §5.4 was a test asserting nothing)
and one compile-blocking defect (§5.2) were found and fixed in this pass, with three test-quality
defects (§5.3, §5.5, §5.6) and one over-claim (§5.7) corrected. No criterion failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's tips | Impossible — no method at any layer takes a user id. `tipsAreScopedToTheirOwner` proves the other student's month is empty |
| Name another student with a parameter | No parameter can. `?userId=2` on a tokenless request is still `401`/`403` — the parameter is not read at all |
| Act on another student's tip | `404`, identical to a tip that does not exist, so tip identifiers cannot be enumerated (§4.4) |
| Use a missing token | `401`/`403` before the controller |
| Use an administrator's token | `403 ACCESS_DENIED` before the controller — §2 |
| Have the tip prose leaked to an administrator | Cannot: the role is refused in `SecurityConfig`, and the title/body name a category and an amount |
| Ask for a month that is not a month | `400`, field error naming `month`. `2026-9`, `2026-13`, `2026-09-01`, `2026` and `not-a-month` all refused, never coerced |
| Ask for an arbitrary past month on `/generate` | Impossible — the month is not a parameter. The endpoint always runs for the current month |
| Restore a dismissed tip | `400`, field error on `state`. The row is verified still `DISMISSED` with its original timestamp |
| Un-dismiss a tip through a second device | Same refusal — the transition is not offered at all, not offered-then-checked |
| Pin and dismiss the same tip concurrently | The row is locked; the second request waits and decides against what the first wrote. `twoPinsOfOneTipSettleOnOneState` runs two requests behind a `CyclicBarrier` |
| Generate while the same student pins a tip | No duplicate: `INSERT IGNORE` against `uk_tip_dedupe`, and the pinned state survives. `generatingWhilePinningDoesNotDuplicateATip` |
| Have a repeated generate resurrect a dismissed tip | It cannot — the row exists with its dedupe key, so the insert is ignored. `aDismissedTipStaysDismissedAcrossAGeneration` |
| Have a repeated generate create a second copy of a tip | It cannot — same key. `generatingTwiceChangesNothing` |
| Make the generator run for a month whose tips should not be shown | `/generate` has no month parameter; the scheduler passes `null` so the database's own month is used |
| Pin a tip twice and have the timestamp move | It does not — the second pin returns the tip unchanged with its original `pinned_at` (§5.4) |
| Write a pinned state without a timestamp | `ck_tip_state` refuses it at the database. `TipsStateConsistencyIT#pinningWithoutATimestampIsRefusedByTheSchema` |
| Write a `NEW` tip carrying a stale timestamp | Refused by the same CHECK (§5.4's suite) |
| Have a state change rewrite the generated columns | `@DynamicUpdate` writes only `state` and its timestamp; `aStateChangeDoesNotRewriteTheGeneratedColumns` asserts title, body, `potential_saving` and `rank_score` are unchanged |
| Have a pin request undo a concurrent tip generation | Prevented by `@DynamicUpdate` — the write does not carry the stale copy of the other columns |
| Have a scheduled run die and take its timer with it | The run catches every `RuntimeException`. `aFailureToReadTheStudentListDoesNotPropagate` |
| Have one student's generation failure lose the others' tips | The boundary is per student (§5.1). `aFailedStudentDoesNotStopTheRun`, `aFailureOnTheFirstStudentDoesNotSkipTheRest` |
| Have a failed student retried in a tight loop inside the run | It is not — `times(1)`. The next tick is the retry |
| Have the scheduler generate for a disabled or administrator account | The query filters `role = 'STUDENT' AND status = 'ACTIVE'` |
| Have the scheduler generate for an arbitrary month | It passes `null`, so the month is the database session's (VĐ-10) |
| Find `userId`, `rankScore` or the tip's owner in any payload | Absent. Two tests pin the exact field sets |
| Have a field added to the DTO widen the contract silently | `tipSchemasMatchTheDocumentedContract` compares against literal lists, so the build fails first |
| Have a route added without the inventory being updated | `documentMatchesTheInventory` and the 41-entry `DOCUMENTED_ENDPOINTS` fail, and `noEndpointIsDuplicated`'s `doesNotContain` block names twelve rejected aliases |
| Have a tip response carry a `DISMISSED` tip | The view excludes it, and `aTipCarriesItsRenderedTextAndNothingInternal` asserts the reachable states |
| Have a month with no tips answer `404` | It answers `200` with an empty array — the month exists; the student has no advice for it |
| Have a month whose only tip was dismissed still offered by the picker | It drops out of both, because both read the same view |
| Read a tip from another month under this month's heading | Impossible — every query names the month, which is the gap the view leaves (§3.4) |
| Make a tips read write something | `readOnly = true`, and `readingAMonthDoesNotGenerateTips` proves the month stays empty |

**Nothing in this pass failed.** The module accommodates what the schema offers — no procedure for the
state change (§4.4), no time filter on the view (§3.4), a template the generator never uses (§4.3) —
and refuses what it cannot honour rather than bending the schema to fit.

**All six generation rules are driven:** rules 4 and 6 by `TipsApiIT`, rules 1, 2, 3 and 5 by
`TipsRuleCoverageIT` (§6). What is deliberately *not* asserted is the rendered template text and the
raw ranking order — the first is `fn_render_template`'s and the templates' output, the second is
asserted as a bound rather than predicted — and both are written down rather than left for a reader of
the traceability table to assume otherwise.

**A second limitation:** the ten-minute offset between the recurring run and the tips run is a
configuration choice, not a guarantee that the two ran in order (§4.6). Both procedures are
idempotent, so a delayed run produces correct tips later rather than wrong ones now.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-18 | Show the tips generated from the student's own spending | `GET /tips` | `listTips` | `listTips` | `v_dashboard_tips` | `aTipCarriesItsRenderedTextAndNothingInternal` |
| UC-18 | Ranked by what following them saves, pinned first | same | — | `TipMapper` | the view's `ORDER BY (state='PINNED') DESC, rank_score DESC` | `pinnedTipsLeadTheList` |
| UC-18 | A picker offers only months with tips | `GET /tips/months` | `listMonths` | `listMonths` | `v_dashboard_tips` (`DISTINCT period_month`) | `theMonthsListMatchesTheMonthsThatHaveTips` |
| UC-18 A1 | A student with too little data still gets advice | `POST /tips/generate` | `generateTips` | `generateTips` | rule 6 | `aStudentWithNoDataGetsTheGenericTip` |
| UC-18 A2 | A month that is not a month is refused | `GET /tips` | — | `resolveMonth` | — | `anUnparseableMonthIsRefused` |
| UC-18 | Pin a tip so it keeps its place | `POST /{id}/state` | `changeState` | `changeState` | `user_tips.state` + `pinned_at` | `pinningStampsTheStateAndItsTimestamp`, `pinnedTipsLeadTheList` |
| UC-18 | Un-pin a tip | same | — | `setState(NEW, …)` | the same two columns | `clearingAPinReturnsTheTipToNew` |
| UC-18 | Dismiss a tip so it stops being shown | same | — | `setState(DISMISSED, …)` | `state` + `dismissed_at` | `aDismissedTipStaysDismissedAcrossAGeneration` |
| BR-14 | A dismissed tip never comes back | `GET /tips` | — | — | the view's `state <> 'DISMISSED'` **and** the procedure's `INSERT IGNORE` | `aDismissedTipStaysDismissedAcrossAGeneration`, `aMonthWhoseOnlyTipWasDismissedLeavesTheMonthsList` |
| BR-12 | Over-budget and near-budget advice at the configured thresholds | — | — | — | rules 1 & 2, `budget.exceeded_threshold_pct` (100%), `budget.near_threshold_pct` (80%) | `TipsRuleCoverageIT#spendingOnTheExceededThresholdProducesOnlyTheOverBudgetTip` (exactly on the exceeded edge → `OVER_BUDGET` alone), `#spendingOnTheNearThresholdProducesTheNearBudgetTip` (exactly on the near edge → `NEAR_BUDGET` alone), `#approachingTheLimitNeverProducesBothBudgetTips` (inside the band → one tip, not two) |
| BR-14 | Top N only, N from a setting | `POST /generate` | — | the DAO passes `NULL` for `p_max_tips` | `tips.max_dashboard` | `TipsRuleCoverageIT#theStoredTipCountIsBoundedByTheSettingsTopN` (the bound is the caller's parameter; the setting applies when none is passed); module 7's `DashboardApiIT#theTipBlockIsBounded` covers the setting's effect (§6) |
| BR-15 | A category rising abnormally against the student's own history | — | — | — | rule 3, `v_category_spend_trend`, `insight.spike_threshold_pct` | `TipsRuleCoverageIT#aRiseAgainstASingleEarlierMonthProducesTheSpikeTip`, `#aCategoryWithNoEarlierSpendingProducesNoSpikeTip`; manual M9-02 reads rule 3's seeded output (§6) |
| VĐ-04 | The savings goal at risk | — | — | — | rule 5, `users.monthly_savings_goal` | `TipsRuleCoverageIT#aMonthWhoseNetFallsBelowTheGoalProducesTheSavingsGoalTip`, `#aMonthWhoseNetMeetsTheGoalProducesNoSavingsGoalTip`, `#theSavingsGoalTipAndAnUnbudgetedCategoryTipCoexist`; manual M9-02 reads rule 5's seeded output (§6) |
| VĐ-05 | A threshold comes from a setting, not from code | `POST /generate` | — | `generateTips` | `system_settings` | `TipsRuleCoverageIT#theStoredTipCountIsBoundedByTheSettingsTopN` (the setting decides when no count is passed); the threshold settings themselves are read by `sp_generate_tips`, so the DAO never overrides them |
| VĐ-10 | A month boundary is the database's | all | — | `currentMonth()` on the read paths; `null` from the scheduler | the session's `time_zone` | `theRunCoversEveryActiveStudentOnce` (asserts the literal `null`), `generatingReturnsTheListItProduced` |
| BR-02 | Ownership | all | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `tipsAreScopedToTheirOwner` |
| BR-02 | No response names the owner | 38, 40, 41 | — | `TipMapper` | — | `aTipCarriesItsRenderedTextAndNothingInternal`, `OpenApiContractIT#tipSchemasMatchTheDocumentedContract` |
| BR-02 | Another student's tip is not discoverable | 41 | — | `findByIdAndUserId` | the `user_id` predicate | `anUnknownTipIsNotFound` |
| §7.5 | Student-only, administrator refused | all | — | — | — | `tipsRequireAToken` |
| §13 | No duplicate endpoint | all | — | — | — | `OpenApiContractIT#noEndpointIsDuplicated` (the tips alias block) |
| §26 | Every endpoint is in the inventory and the document | all | — | — | — | `OpenApiContractIT#documentMatchesTheInventory` |
| UC-18 | Reading writes nothing | 38, 39 | — | `readOnly = true` | — | `readingAMonthDoesNotGenerateTips` |
| UC-18 | Generating is idempotent | 40 | — | — | `uk_tip_dedupe` + `INSERT IGNORE` | `generatingTwiceChangesNothing` |
| `ck_tip_state` | A state carries the timestamp it pairs with | 41 | — | `UserTip.setState` | `ck_tip_state` | `TipsStateConsistencyIT` (6 assertions of the constraint) |
| UC-18 | A state change is decided from a locked row | 41 | — | `findByIdAndUserId` (`PESSIMISTIC_WRITE`) | — | `twoPinsOfOneTipSettleOnOneState`, `generatingWhilePinningDoesNotDuplicateATip` |
| UC-18 | The scheduled run is per-student and cannot die | — | — | `TipGenerationScheduler` | `sp_generate_tips` | `TipGenerationSchedulerTest` (7) |

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` (28 paths, 41 operations, as the document stood when this module shipped; module 10 adds the four bookmark routes) |
| Every field traced to a documented requirement | Yes — §9, and [`docs/api/tips.md` §12](../api/tips.md#12-traceability) |
| Validation with per-field errors using `field` | Yes — the month and the state each name their parameter |
| Ownership enforced server-side, structurally | Yes — no method at any layer takes a user id |
| No sensitive field in any response | Yes — seven internal fields unmapped; two tests compare against literal field lists |
| Tests through HTTP against real MySQL 8 | Yes — 42 of the 49; the scheduler's 7 are unit tests of a class no HTTP path can reach deterministically |
| No schema change; `validate` holds | Yes — every `db/` file predates this module (newest 2026-09-24 20:57) and none was modified on 2026-09-25; `ddl-auto=validate` starts against the freshly loaded schema. `db/` is untracked, so `git diff --stat` is not usable as evidence here (§3.6) |
| API document written; Angular can integrate without guessing | Yes — [`docs/api/tips.md`](../api/tips.md), including the nine mock-vs-contract divergences a rewiring must reconcile |
| Inventory updated; no duplicate endpoint | Yes — endpoints 38–41 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 511 tests, 0 failures, 0 errors, 0 skipped |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — UC-18 is satisfied by four endpoints and nothing is deferred |
| Coverage gaps recorded rather than implied away | Yes — the rendered template text and the raw ranking order are deliberately not asserted (§6), the scheduler's run-order is a convenience not a guarantee (§4.6), the concurrency case is asserted rather than assumed impossible, and the `db/`-integrity evidence carries its untracked-path caveat (§3.6) |

---

## 11. Deferred / blocked

**Nothing in this module is blocked, and no new blocker is recorded.** `db/` is untouched (by
modification time; §3.6 records why `git diff` is not usable evidence for an untracked path) — the
properties this module works around are the schema's, not defects in it:

| Property | How it is handled |
|---|---|
| No procedure exists for a tip's state | An entity write with a `PESSIMISTIC_WRITE` lock, following the recurring-rule precedent (§4.4) |
| `v_dashboard_tips` has no time filter | Every query names a month (§3.4) |
| `tip_templates.LOW_SAVINGS_RATE` is never used by the generator | Recorded, not implemented — it is UC-17's rule and module 12 is locked (§4.3) |
| `p_max_tips` must come from a setting | Passed as `NULL` (VĐ-05) |

**Global items that touch this module:**

| Item | Effect here |
|---|---|
| **OB-001** — the authoritative source documents are not on disk | The same caveat as every module: UC-18's field-level requirements were taken from the UC table in [`docs/ERD.md`](../ERD.md) and cross-checked against `user_tips`, `tip_templates`, `v_dashboard_tips` and `sp_generate_tips`, which are named for the use case and are the schema's own statement of what the screen shows |
| **OB-002** — no production email provider | Unaffected. A tip is not delivered by mail; it is read from the screen |
| **OB-003** — production secrets management | Unaffected. The module adds no credential. The scheduler reads two settings that have no secret value |
| **OB-004** — throttle counters are per instance | Unaffected to the same degree as every other module; `/generate` is a write and is **not** throttled, so a client could call it in a loop. Each call is idempotent and costs one procedure call, so the cost is bounded by that rather than by a rate limit — recorded rather than claimed to be protected |
| **OB-011** / **OB-009** — a retired category freezes its budgets and rules | Visible here and handled correctly: the generator reads the *views*, which do not filter a retired category's transactions, so a tip about a retired category's spending is still produced. Rules 1, 2 and 4 read `budgets` rows, and a retired category's budgets are frozen rather than deleted, so the same tip is produced until the budget is removed — a consequence of "frozen", not of this module |

**All six generation rules are driven:** rules 4 and 6 by `TipsApiIT`, rules 1, 2, 3 and 5 by
`TipsRuleCoverageIT`, whose fixtures reach every precondition through a public route (§6). An earlier
draft of this report recorded those four as a coverage limitation; that was a limitation of the
fixtures, not of the schema or the rules, and §6 corrects it. What remains genuinely uncovered is the
*rendered text* of a template — deliberately, because it is `fn_render_template`'s output and the
templates' wording, which this module neither owns nor may restate — and the raw ranking order, which
is asserted as a bound rather than predicted (§6).

**A second limitation — the scheduler's ordering is a convenience:** the ten-minute offset from the
recurring run is intended to let the first-of-month transactions post before tips are generated. It is
not a guarantee that they did, and nothing in the code asserts the order (§4.6). Both procedures are
idempotent, so a delayed tips run produces correct tips later.

**A third limitation — no production AI:** UC-18's advice is produced entirely by SQL rules over the
student's own views. There is no model, no provider and no API key anywhere in this module, and none
was requested. The narrative insights a mock frontend shows belong to UC-17, which is module 12 and is
locked pending the project owner's approval.

**The frontend is still mock-only and has no tips screen.** `InsightsComponent` shows
`MOCK_INSIGHTS` — one long narrative per month, bookmarked in `localStorage` — which is UC-17's shape,
not this contract's. It is **not changed by this module**.
[`docs/api/tips.md` §10](../api/tips.md#10-angular-integration-notes) records the nine divergences a
rewiring must reconcile, most importantly that the mock's bookmark lives in the browser while this
module's `state` lives in the database and is terminal once dismissed.

---

## Related documentation

- [`docs/api/tips.md`](../api/tips.md) — the UC-18 contract, the four-endpoint reasoning, the state
  machine, and what a client may not assume
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — endpoints 38–41, and the decisions
  recorded with them
- [`docs/testing/manual/MODULE_09_MANUAL_TEST.md`](../testing/manual/MODULE_09_MANUAL_TEST.md) — the
  hand-run procedure, including the two cases that read the seeded rules this suite does not drive
- [`docs/modules/MODULE_07_DASHBOARD.md`](MODULE_07_DASHBOARD.md) — the other reader of
  `v_dashboard_tips`, the module that first found the view's missing time filter, and the source of
  the unbounded-run defect's shape
- [`docs/modules/MODULE_05_RECURRING.md`](MODULE_05_RECURRING.md) — the scheduler pattern this
  module's timer follows, and the locking precedent its state change follows
- [`docs/modules/MODULE_06_BUDGET.md`](MODULE_06_BUDGET.md) — the budget thresholds rules 1, 2 and 4
  compare against, and the `consumed_pct` band the tip rules deliberately share with the alerts
- [`docs/modules/MODULE_08_REPORTS.md`](MODULE_08_REPORTS.md) — the module this one follows
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — the global items
