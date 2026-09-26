# Saving Tips API

**Endpoints 38–41.** `GET /api/v1/tips` — one month's saving tips, already ranked (UC-18).
`GET /api/v1/tips/months` — the months that have tips, so a picker offers only months that return
something. `POST /api/v1/tips/generate` — run the generator for the current month now.
`POST /api/v1/tips/{id}/state` — pin, dismiss or clear one tip.

**The module's central property is that a tip is written by the database and only its state is written
by the application.** Which tips exist, what they say, how much they could save and in what order
are all decided by `sp_generate_tips` and `v_dashboard_tips`; the one column UC-18 lets a student
change is `state`, and §6 is about it. **Reading never generates** — §5 explains why that separation
is what makes the screen safe to open.

---

## Contents

| § | |
|---|---|
| 1 | [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent) |
| 2 | [Endpoints at a glance](#2-endpoints-at-a-glance) |
| 3 | [Field reference](#3-field-reference) |
| 4 | [Where a tip comes from — the six rules](#4-where-a-tip-comes-from--the-six-rules) |
| 5 | [Reading never generates; generating is its own call](#5-reading-never-generates-generating-is-its-own-call) |
| 6 | [Pinning and dismissing — the state machine](#6-pinning-and-dismissing--the-state-machine) |
| 7 | [The month](#7-the-month) |
| 8 | [Status codes](#8-status-codes) |
| 9 | [Security properties](#9-security-properties) |
| 10 | [Angular integration notes](#10-angular-integration-notes) |
| 11 | [The scheduled run](#11-the-scheduled-run) |
| 12 | [Traceability](#12-traceability) |

---

## 1. Scope and what is deliberately absent

| Use case | Behaviour |
|---|---|
| UC-18 | Show the saving tips generated from the student's own spending, let them pin one to keep it in view, and let them dismiss one so it stops being shown |
| UC-18 A1 | A student with too little data to analyse gets a single "record your first transactions" tip rather than an empty screen |
| UC-18 A2 | A month that is not a month is **refused**, not read as another one |
| BR-14 | Tips are ranked by what following them could save, and only the top N (`tips.max_dashboard`, 3) are shown; a dismissed tip is never shown again |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` parameter | The account is the bearer token's. A tip is one named student's advice, and it names their categories and the amounts they spent on them — so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/tips/{id}` | A tip is only ever shown as part of its month's ranked list. A bare `/{id}` read would be the same row without the ordering that gives it meaning (§13) |
| `POST /api/v1/tips` (create) | No human writes a tip. The text is rendered by `fn_render_template` from a `tip_templates` row, and the figures inside it come from views. A create route would let a client write prose the rules never produced |
| `PATCH`/`PUT /api/v1/tips/{id}` | The one writable column is `state`, and it is written through `POST /{id}/state` because a state change writes two columns together — §6 |
| `DELETE /api/v1/tips/{id}` | Deleting the row would remove the `dedupe_key` that stops the generator from bringing the tip straight back. Dismissal is how a tip is retired — §6 |
| `POST /{id}/pin`, `/{id}/unpin`, `/{id}/dismiss` | Three names for one write of one column with three values (§13). §6 gives the single route |
| `GET /api/v1/tips/export` | A tip list is not a report and BR-18 is about reports. No use case asks for it |
| A `month` parameter on `/generate` | Generating is for the current month only, so a client cannot pull advice about a month whose tips were never meant to be shown — §5 |
| A `state` **filter** (`?state=PINNED`) | The list already carries the state on every row and is already ordered pinned-first. A filter would be a second expression of the view's own rule |
| `LOW_SAVINGS_RATE` tips | The template row exists in `tip_templates`, but **`sp_generate_tips` never produces it** — §4.4. That rule belongs to UC-17 (monthly insights), which is module 12 |

---

## 2. Endpoints at a glance

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 38 | `GET` | `/api/v1/tips` | UC-18 | Bearer, `STUDENT` | `200` | `400` `401` `403` |
| 39 | `GET` | `/api/v1/tips/months` | UC-18 | Bearer, `STUDENT` | `200` | `401` `403` |
| 40 | `POST` | `/api/v1/tips/generate` | UC-18 | Bearer, `STUDENT` | `200` | `401` `403` |
| 41 | `POST` | `/api/v1/tips/{id}/state` | UC-18 | Bearer, `STUDENT` | `200` | `400` `401` `403` `404` |

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 38 | `month` | `yyyy-MM` — any real month | the current month |
| 41 | `id` (path) | The id of one of the caller's tips | — |

`POST /api/v1/tips/generate` has **no request body**. `POST /api/v1/tips/{id}/state` has exactly one
body field, `state`. The `OpenApiContractIT` asserts that no `CreateTipRequest`, `UpdateTipRequest`,
`DeleteTipRequest`, `TipRequest` or `GenerateTipsRequest` schema exists.

---

## 3. Field reference

### 3.1 `TipListResponse` (endpoints 38 and 40)

| Field | Type | Always present | Notes |
|---|---|---|---|
| `periodMonth` | string | yes | The month answered, `yyyy-MM`. Echoed back rather than assumed — §7 |
| `tips` | array of `TipResponse` | yes | Present and **empty** when the month has nothing to show. Never `null`, never absent |

The month is named once on the wrapper rather than repeated on every row: `v_dashboard_tips` carries
every month a student has tips for, so the month is what each query narrows on, and a copy per row
would be the same string repeated. It is the shape `DashboardResponse` uses for the same reason.

### 3.2 `TipResponse`

| Field | Type | Always present | Notes |
|---|---|---|---|
| `id` | integer | yes | Used by the pin and dismiss actions |
| `categoryId` | integer | **no** | Omitted when the tip belongs to no category — §3.6 |
| `title` | string | yes | The headline, rendered by the database |
| `body` | string | yes | The advice itself, rendered by the database |
| `potentialSaving` | number | yes | What following the advice could save, in the account's currency. `0.00` for advice with no figure |
| `state` | string | yes | `NEW` or `PINNED` — §3.5 on why `DISMISSED` never appears here |

`userId`, `periodMonth`, `rankScore`, `tipTemplateId`, `dedupeKey`, `generatedAt`, `pinnedAt` and
`dismissedAt` are all **absent**, and `OpenApiContractIT#tipSchemasMatchTheDocumentedContract` pins
the field set to the six above so a new column cannot appear in a response silently.

**The order of the array is the contract.** Pinned tips lead; the rest follow the database's ranking
(BR-14). A client renders the array as it arrives and **must not re-sort** it — sorting by
`potentialSaving` would disagree with the ranking the view computes, and the tip the student pinned
would drift down the page.

### 3.3 `TipMonthsResponse` (endpoint 39)

| Field | Type | Notes |
|---|---|---|
| `months` | array of string | `yyyy-MM`, newest first. Empty when no tips have been generated for the caller |

Read from the same view as endpoint 38, so the months offered are exactly the months that would
return something. A month whose tips have **all** been dismissed drops out: it would return an empty
array if asked for, and offering it would be an empty screen behind a menu entry.

### 3.4 `UpdateTipStateRequest` (endpoint 41)

| Field | Type | Required | Notes |
|---|---|---|---|
| `state` | string | yes | `PINNED`, `DISMISSED` or `NEW` |

One field, because a tip's state is one column.

### 3.5 `TipState` — three members, two of them ever returned

`PINNED`, `DISMISSED`, `NEW`. Members are matched **by name, never by ordinal**, so a value the enum
does not know fails at the read instead of shifting meaning to the next member.

The response schema advertises all three members even though a `TipResponse` in practice carries only
`NEW` or `PINNED`. That is not an oversight: the field is typed by the same `TipState` the request
uses, because a tip's state is one column and two Java enums over it would be two vocabularies that
could drift. The rule that keeps `DISMISSED` out of a response is the **view's**
(`v_dashboard_tips` has `WHERE state <> 'DISMISSED'`), applied before the type is ever constructed —
and a schema cannot express a rule that belongs to a view. `TipsApiIT` asserts it instead.

### 3.6 `categoryId` is absent rather than null

Two of the six rules produce a tip about no single category: the savings-goal tip (rule 5) and the
"too little data" tip (rule 6). `user_tips.category_id` is nullable and so is this field, marked
`@JsonInclude(NON_NULL)`. A client links a tip to that category's transactions when `categoryId` is
present, and omits the link when it is not.

### 3.7 Amounts

`potentialSaving` is `DECIMAL(15,2)` and serialises with that scale: `14.00`, not `14`. The currency
is not published on the response — the same figure is the one every other screen reports, and the
account's currency symbol is already in the tip's rendered body text, inserted there by
`fn_render_template` from the `app.currency_symbol` setting.

---

## 4. Where a tip comes from — the six rules

**A tip is never composed in Java.** `sp_generate_tips(user_id, period_month, max_tips)` applies six
SQL rules, renders each tip's text, ranks the results, and inserts the top N. Everything below is the
procedure's; the application decides only *when* to call it and *for whom*.

| # | Rule | Template code | Fires when |
|---|---|---|---|
| 1 | Over budget | `OVER_BUDGET` | `consumed_pct >= budget.exceeded_threshold_pct` (100) |
| 2 | Near budget | `NEAR_BUDGET` | `budget.near_threshold_pct` (80) `<= consumed_pct <` the exceeded threshold |
| 3 | Category spiking | `CATEGORY_SPIKE` | The category's month-on-month rise is at least `insight.spike_threshold_pct` (30%), its baseline average is above zero, and at least one earlier month exists to average over (BR-15) — see §4.6 |
| 4 | Heavy category with no budget | `NO_BUDGET_SET` | An expense category with spending this month and **no** `budgets` row for it. At most 2, largest first |
| 5 | Savings goal at risk | `SAVINGS_GOAL_AT_RISK` | `monthly_savings_goal > 0` and net (income − spending) is below it (VĐ-04) |
| 6 | Too little data | `GENERIC` | The month has **no** row in `v_category_month_totals` — UC-18 A1 |

Then the ranking, which is BR-14: `ROW_NUMBER() OVER (ORDER BY rank_score DESC, potential_saving
DESC)`, keeping rows where `rn <= max_tips`. `max_tips` is passed as `NULL` by this module so the
bound comes from `tips.max_dashboard` in `system_settings` (3 by default) rather than from code —
VĐ-05 forbids overriding configuration from the application.

### 4.1 Rules 1 and 2 share a band edge on purpose

Rule 2's band is `[near, exceeded)` and rule 1's is `[exceeded, ∞)`. That is the same split
`sp_check_budget_alerts` uses, so a tip and a budget alert about the same category always agree about
which side of the limit it is on. Two independent spellings of "approaching" would eventually
disagree at exactly the threshold.

### 4.2 Rule 6 is why an empty month is never an error

A student with no records at all still gets one tip, so UC-18 A1's "not enough data" case is a
populated screen rather than an empty one. It has `potentialSaving: 0.00` — advice with no figure
attached — and `categoryId` absent.

### 4.3 Idempotency is the unique key's, not the caller's

The procedure ends with `INSERT IGNORE` against `uk_tip_dedupe`, whose key is
`user_id | period_month | tip_template_id | category_id`. A tip that already exists for its key is
skipped. Two consequences the API depends on:

- **A repeated run produces the same tips and no others**, which is what makes `POST /generate` safe
  to expose and the scheduled run safe to repeat or to run on more than one instance (§11).
- **A pinned or dismissed tip is never regenerated.** Once the student has acted on a tip, its row
  exists with that key, so a later run skips it. "Dismissed for good" is therefore true rather than
  true until the next tick.

### 4.4 `LOW_SAVINGS_RATE` is a template the generator never uses

`tip_templates` holds seven rows; `sp_generate_tips` names six of them (`db/03_procedures.sql` lines
473, 490, 507, 525, 548, 563). `LOW_SAVINGS_RATE` is the seventh, and no code path reaches it.

That is recorded rather than "fixed": the rule is UC-17's (monthly insights), which is module 12 and
is **locked**. Adding a rule for it here would implement a use case this module does not own. The row
is seed data and `db/` is untouched — every file under `db/` predates this module (newest 2026-09-24
20:57, none modified on 2026-09-25). Note `db/` is untracked, so `git diff --stat -- db/` is empty
regardless of content and is not evidence; see [`docs/modules/MODULE_09_TIPS.md` §3.6](../modules/MODULE_09_TIPS.md).

### 4.5 Which of the six rules this module's tests reach

**The six rules are the database's.** Each is a multi-step precondition — rule 1 and rule 2 need a
limit set and then crossed, rule 3 needs an earlier month to compare against, rule 5 needs a savings
goal and a month whose net falls below it — so no single API call produces them. That is a fact about
the rules, not a limit on what can be tested: every precondition above is reachable through public
routes (`POST /api/v1/budgets`, `PATCH /api/v1/profile/me`, `POST /api/v1/transactions`), so both
suites build them the way a client would rather than writing to the database.

| Rule | Template | Asserted by |
|---|---|---|
| 1 over budget | `OVER_BUDGET` | `TipsRuleCoverageIT.spendingOnTheExceededThresholdProducesOnlyTheOverBudgetTip` |
| 2 approaching | `NEAR_BUDGET` | `TipsRuleCoverageIT.spendingOnTheNearThresholdProducesTheNearBudgetTip`, `approachingTheLimitNeverProducesBothBudgetTips` |
| 3 spike | `CATEGORY_SPIKE` | `TipsRuleCoverageIT.aRiseAgainstASingleEarlierMonthProducesTheSpikeTip`, `aCategoryWithNoEarlierSpendingProducesNoSpikeTip` |
| 4 unbudgeted | `NO_BUDGET_SET` | `TipsApiIT` (count), `TipsRuleCoverageIT.aCategoryWithNoEarlierSpendingProducesNoSpikeTip`, `ruleFourIsCappedAtTheTwoLargestUnbudgetedCategories` |
| 5 savings goal | `SAVINGS_GOAL_AT_RISK` | `TipsRuleCoverageIT.aMonthWhoseNetFallsBelowTheGoalProducesTheSavingsGoalTip`, `aMonthWhoseNetMeetsTheGoalProducesNoSavingsGoalTip`, `theSavingsGoalTipAndAnUnbudgetedCategoryTipCoexist`, `theStoredTipCountIsBoundedByTheSettingsTopN` |
| 6 no records | `GENERIC` | `TipsApiIT` (an empty month) |

**What the rule tests assert, and what they deliberately do not.** A test identifies the rule that
fired by reading `user_tips.tip_template_id` — the database's own statement of which rule produced
the row, and the value its uniqueness key is built from — never by matching the rendered title, which
would assert `fn_render_template`'s output and the template's wording that this module neither owns
nor may restate. The one figure that *is* asserted is `potential_saving`, because it is published and
it is the rule's arithmetic: §3.7's two-decimal scale is pinned against the raw body by
`aPublishedSavingCarriesTwoDecimalsOnTheWire`, since a parsed node cannot tell `8.00` from `8.0`.
Ranking is asserted as a **bound** (`theStoredTipCountIsBoundedByTheSettingsTopN`), never as a
predicted order — predicting it would mean recomputing every rule's `rank_score` in Java, which is
exactly the duplication §13 forbids.

Beyond the rules themselves, this module's suites test the module's own work: which month, whose
tips, which state transitions, idempotency, the `ck_tip_state` constraint, the pin/generate
concurrency, and the scheduler.

### 4.6 Rule 3's baseline gate is not the setting

`insight.spike_baseline_months` (3) is the **window** the baseline is averaged over, and it is read
inside `v_category_spend_trend` where the baseline and the month count are both computed. It is *not*
the gate the procedure applies. The procedure's own three conditions are
(`db/03_procedures.sql` 510–512):

```sql
AND s.baseline_months    >= 1        -- at least one earlier month, not three
AND s.baseline_avg_spend >  0
AND s.pct_change         >= v_spike_pct
```

So the gate is **one** earlier month, not three. Setting the window to 3 makes the view divide the
earlier months' spending by 3 whether or not three of them exist — so a single earlier month of
spending is averaged over three and comes out **one third** of its true size, which inflates
`pct_change` and makes a rise look larger than it is.

**This is the schema's behaviour and the module does not restate or correct it.** The seed shows it
plainly: for the demo account's `2026-07` — the oldest month it has — every category has
`baseline_months = 1` and a `pct_change` derived from a third of its single earlier month, and six
categories spike at once. By `2026-09` the account has `baseline_months = 3`, the baseline is a real
three-month average, and only `Entertainment` spikes. A client must not re-derive `pct_change`; the
figure in a tip's title is the view's.

**`insight.spike_baseline_months` is still a genuine setting**, and `v_category_spend_trend` falls
back to `3` when it is missing, zero or not a number — `NULLIF(CAST(... AS UNSIGNED), 0)` guards the
division explicitly. What the procedure adds is a second, independent condition on `baseline_months`
that no setting controls.

---

## 5. Reading never generates; generating is its own call

`GET /api/v1/tips` is `@Transactional(readOnly = true)` and calls no procedure. Opening the tips
screen cannot change what is on it. `POST /api/v1/tips/generate` is the only route that calls
`sp_generate_tips`.

**Why both exist rather than one.**

- A scheduled run (§11) keeps a student's tips current without them doing anything, but it runs on a
  timer. A student who records a large purchase and wants the advice it should produce would
  otherwise wait until the next tick — `/generate` is that, on demand.
- If reading generated, a student's own screen would be the thing that changed their data, and a
  client that re-fetched on every render would be writing on every render. The separation is what
  makes the read safe to repeat freely.

**`/generate` takes no month.** It always runs for the current month as the application judges it
(`Asia/Ho_Chi_Minh`, the same offset the database session is pinned to — §7), so a client cannot pull
advice about a month whose tips were never meant to be shown. The month is not a parameter, so it
cannot be forged, and the response names the month it used.

**The response is the resulting list, not an acknowledgement.** The caller sees the advice the run
produced without a second request — the generate is the action and the list is its result.

---

## 6. Pinning and dismissing — the state machine

```
            POST /{id}/state {state: "PINNED"}          POST {state: "NEW"}
   NEW  ────────────────────────────────────────►  PINNED ────────────────────►  NEW
    │                                                 │
    │ POST {state: "DISMISSED"}                       │ POST {state: "DISMISSED"}
    ▼                                                 ▼
 DISMISSED ─────────── ✗ terminal: nothing moves it back ───────────►
```

| From | To | Result |
|---|---|---|
| `NEW` | `PINNED` | `200` — pinned, `pinned_at` stamped |
| `PINNED` | `NEW` | `200` — unpinned, `pinned_at` cleared. **This is how a tip is unpinned** |
| `NEW` or `PINNED` | `DISMISSED` | `200` — dismissed, `dismissed_at` stamped |
| any | the same state | `200` — the tip comes back unchanged, with its **original** timestamp |
| `DISMISSED` | `NEW` or `PINNED` | **`400 VALIDATION_ERROR`** with a field error on `state` |

### 6.1 The write is two columns, which is why it is not a `PATCH` of a field

`ck_tip_state` requires `PINNED` to carry a `pinned_at`, `DISMISSED` to carry a `dismissed_at`, and
`NEW` to carry neither. A client that could set `pinnedAt` directly could produce a pinned tip with no
pinned time — a row the schema refuses, and one with no meaning. The server decides both fields from
the one value the client sends. `TipsStateConsistencyIT` asserts the constraint against the real
schema by applying the *wrong* writes directly and requiring each to be refused.

### 6.2 Why dismissal is one-way

A tip the student threw away is not brought back by asking again. The alternative — allowing
`DISMISSED → NEW` — would let a stale client or a replayed request restore advice the student
deleted, and would make "dismissed" mean "hidden until someone asks". This is the one transition the
request is refused for rather than applied, and it is refused as a **validation** error: the value
sent is a valid `TipState`, it just does not apply to this tip's current state.

There is no "undo dismissal" and none is planned. Un-pinning exists because pinning is a display
preference; dismissing is a decision.

### 6.3 Asking for the state a tip already holds is not an error

A second pin returns the tip and the timestamp it received the first time. That makes the action safe
to retry and lets two devices acting at once settle on the state that already holds — the same
treatment `sp_mark_notification_read` gives a second mark-read.

### 6.4 The row is locked, and the transition is decided from the locked state

`UserTipRepository.findByIdAndUserId` is `@Lock(PESSIMISTIC_WRITE)`. Both of this endpoint's decisions
— whether dismissal is being reversed, and whether anything is changing at all — are answers to "what
state is it in now", so two of them taken from a lock-free read could both be taken. A pin and a
dismiss racing could each see `NEW` and each succeed, with the later write silently replacing the
earlier: the caller who pinned would be told `PINNED` while the row ended up dismissed. The lock makes
the second request wait and decide against what the first actually wrote.
`TipsApiIT#twoPinsOfOneTipSettleOnOneState` exercises it with a `CyclicBarrier`.

### 6.5 A tip that is not the caller's is `404`, not `403`

Ownership is decided by the query that loads the tip — `findByIdAndUserId` takes the caller's id — so
another student's tip is **not found** rather than found and refused. "Not yours" and "does not exist"
answer identically, so the endpoint cannot be used to discover which tip identifiers exist.

---

## 7. The month

`month` is `yyyy-MM` and is optional on endpoint 38. Omitted or blank, it means the current month as
the application judges it, and the response says which month it answered.

**Parsing is strict.** `YearMonth.parse` accepts `2026-09` and refuses `2026-9`, `202609`, `2026-13`,
`2026`, `2026-09-01`, `September` and `not-a-month`. A malformed month is a `400` with a field error
naming `month` — never coerced into a different, valid one. A request that means October must not be
answered as September.

**The month boundary is the application zone's.** `Team`-wide the offset is `+07:00`
(`Asia/Ho_Chi_Minh`), which is also what the database session is pinned to via
`hikari.connection-init-sql: SET time_zone = '+07:00'`. The application resolves "this month" in that
zone in exactly one place, so an omitted `month` and `/generate` cannot disagree about which month is
current.

**This is a deployment setting, not a guarantee.** A server whose clock or zone differs from the
database's can still disagree with it. What the module guarantees is narrower and testable: one
request is judged against **one** value, resolved before any query runs.

**`v_dashboard_tips` has no time filter**, so every query names a month. Without it the view would
return every month the student has tips for, interleaved — the gap `DashboardViewDao` corrects the
same way for the same reason.

---

## 8. Status codes

| Status | `errorCode` | When |
|---|---|---|
| `200` | — | The tips, the months, or the tip in its new state |
| `400` | `VALIDATION_ERROR` | `month` is not a valid month; `state` is missing or not a member; the tip is dismissed and the request would revive it |
| `401` | `UNAUTHENTICATED` | No token, or one that is malformed, expired or revoked |
| `403` | `ACCESS_DENIED` | A token belonging to an account that is not a student |
| `404` | `NOT_FOUND` | No such tip of the caller's — including one belonging to somebody else (§6.5) |
| `500` | `INTERNAL_ERROR` | Unexpected. Carries no SQL, stack trace or credential |

Every `400` carries `fieldErrors` naming the parameter that caused it.

A malformed month:

```bash
curl -H "Authorization: Bearer ${JWT}" "http://localhost:8080/api/v1/tips?month=2026-13"
```

```json
{
  "timestamp": "2026-09-25T09:31:12.418397Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "The month is not a valid month.",
  "path": "/api/v1/tips",
  "fieldErrors": [
    { "field": "month", "message": "Enter a real month in yyyy-MM form, for example 2026-09." }
  ]
}
```

Reviving a dismissed tip — the **only** state transition the module refuses:

```bash
curl -X POST -H "Authorization: Bearer ${JWT}" -H "Content-Type: application/json" \
  -d '{"state":"PINNED"}' "http://localhost:8080/api/v1/tips/9/state"
```

```json
{
  "timestamp": "2026-09-25T09:31:20.902145Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "A dismissed tip cannot be brought back.",
  "path": "/api/v1/tips/9/state",
  "fieldErrors": [
    { "field": "state", "message": "This tip was dismissed and cannot be restored." }
  ]
}
```

A tip that is not the caller's, and one that does not exist, answer identically:

```json
{
  "timestamp": "2026-09-25T09:31:26.117640Z",
  "status": 404,
  "errorCode": "NOT_FOUND",
  "message": "Tip not found.",
  "path": "/api/v1/tips/9/state",
  "fieldErrors": []
}
```

---

## 9. Security properties

| Property | How |
|---|---|
| **Identity is the token** | Every method takes `@AuthenticationPrincipal`. No endpoint accepts a user id, and no module method has a user-id parameter |
| **Ownership is structural, not checked** | Every query binds the token's `user_id` as a predicate. There is no code path that reads a tip and then verifies whose it is (BR-02) |
| **Role is enforced before the controller** | `/api/v1/tips/**` → `hasRole("STUDENT")` in `SecurityConfig`. An administrator's token is refused with `403` and never reaches the service — §9.1 |
| **A tip never names its owner** | `userId` is unmapped on the entity's DTO and the field set is pinned by a contract test |
| **A tip's prose is treated as sensitive** | A title and body name a category and an amount from one student's spending. That is why the read is student-only rather than authenticated |
| **Invalid input is refused, not coerced** | A malformed month or an unknown state is `400`, never read as another value |
| **Nothing internal is published** | No `rankScore`, `dedupeKey`, `generatedAt`, `tipTemplateId`, `pinnedAt` or `dismissedAt` (`OpenApiContractIT#tipSchemasMatchTheDocumentedContract`) |
| **Only one column is writable** | The request body has one field, `state`. There is no mass-assignment surface |

### 9.1 Why an administrator is refused here

A tip's title and body are readable prose — "Entertainment spending is up 127.3%", "You have 30.00
left of your 30.00 Food budget" — rendered from one student's own figures. Admitting the
administrator role would let it read a named student's spending through a route that was never meant
to name anyone. `/api/v1/dashboard/**` is guarded the same way for the same reason.

Administrator work on tip **templates** (UC-20) is a different table (`tip_templates`, not
`user_tips`) and lives under `/api/v1/admin/**` in module 11.

---

## 10. Angular integration notes

**The current frontend does not call this API.** There is no tips screen; the nearest component is
`InsightsComponent` at `frontend/src/app/features/insights/insights.component.ts`, and it is entirely
mock: `InsightService` holds `MOCK_INSIGHTS` in a signal and persists bookmarks to `localStorage`
under `campus_coin_bookmarked_insights`. Nothing in the frontend source is changed by this module.
This section records what a rewiring would have to reconcile.

### 10.1 The mock's model is not this contract

`frontend/src/app/core/models/insight.model.ts` describes `MonthlyInsight`: a long `narrativeSummary`,
a human `month` label (`"September 2026"`), a `savingTip` string, an optional
`{categoryName, percentChange, direction}` flag, an `isBookmarked` boolean, an `avatarUrl` and a
`createdAt`. That is **one paragraph per month addressed to the reader** — a monthly insight, which is
UC-17 and belongs to module 12. It is not a list of per-category tips.

The dashboard's own tips block is the closer relative: `DashboardResponse.tips` and this module's
`TipResponse` are both projections of `v_dashboard_tips`, and both publish the same six fields for the
same month. A tips screen and the home screen therefore show the same rows.

### 10.2 Which call to make

| Screen element | The endpoint to call |
|---|---|
| The month's tips list | `GET /api/v1/tips?month=yyyy-MM` |
| The month picker | `GET /api/v1/tips/months`, then `GET /api/v1/tips?month=` for the chosen one |
| A "refresh" or "give me new advice" button | `POST /api/v1/tips/generate` — and render the response, not a follow-up read |
| Pin / unpin / dismiss a tip | `POST /api/v1/tips/{id}/state` with `{"state": "PINNED"` \| `"NEW"` \| `"DISMISSED"}` |

### 10.3 Divergences to reconcile

| # | The mock does | The contract does | Consequence |
|---|---|---|---|
| 1 | One long `narrativeSummary` per month | `title` + `body` per tip, several tips per month | A "story" layout becomes a list of short cards |
| 2 | `month: "September 2026"` | `periodMonth: "2026-09"`, and per-row field names are `title`/`body` | The label must be derived client-side from `periodMonth` |
| 3 | `savingTip` is one free-text string | `body` is rendered from a template by the database, and `potentialSaving` is the figure | Amounts in the prose are the server's; do not recompute or re-format them |
| 4 | `isBookmarked` — a boolean persisted in `localStorage` | `state` is `NEW`/`PINNED`/`DISMISSED`, stored server-side | The bookmark becomes real state that survives a reload and a device change; `localStorage` must be dropped, or it will disagree with the server |
| 5 | The component's filter is "All" vs "Pinned Tips", counting from `isBookmarked` | `GET /api/v1/tips` already returns pinned tips first, and a dismissed tip is not returned at all | The "pinned" filter becomes a client-side filter of the same response, not a second call |
| 6 | `id: 'ins-2026-09'` — a string | Integer | Type change |
| 7 | `avatarUrl`, `categoryFlag`, `createdAt` | None of them. The advice is the database's prose, and no avatar is stored | Drop the avatar, or supply a static one client-side |
| 8 | A dismissal is not modelled at all | `DISMISSED` is terminal and removes the tip from both the list and the months list | A "delete/ignore" action must be one-way in the UI too, or the `400` will surprise the user |
| 9 | Nothing calls the generator | Advice appears only when `POST /generate` or the scheduler has run | After recording a transaction, the tips screen will not show new advice until one of the two runs |

### 10.4 Handling each status

| Response | What the client should do |
|---|---|
| `200` with an empty `tips` | A real answer. Show "no tips for this month", not an error, and do not call `/generate` automatically — that is an action the student takes |
| `401` | The token is gone or revoked — return to sign-in |
| `403` | The session is not a student — the sign-in screen, not a retry |
| `400` on `GET /tips` | `fieldErrors[0].field` is `month` — keep the picker's previous value and show the message |
| `400` on `POST /{id}/state` | Either the state is unknown or the tip was dismissed and cannot be restored. Read the message rather than assuming which, and re-read the list |
| `404` on `POST /{id}/state` | The tip is gone or was never the caller's. Remove it from the list and re-read |
| — | **Never re-sort the returned array.** It is already ranked — §3.2 |

---

## 11. The scheduled run

`TipGenerationScheduler` runs once a day for every `STUDENT` account whose `status` is `ACTIVE`,
calling the same `sp_generate_tips` through the same DAO that `/generate` uses. It is **not** a fifth
endpoint and not a second implementation.

| Property | How |
|---|---|
| Enabled by default | `campuscoin.tips.scheduler.enabled` (`TIPS_SCHEDULER_ENABLED`), default `true`. The test environment sets it `false` so a timer cannot write rows mid-assertion |
| Daily, `+07:00` | `campuscoin.tips.scheduler.cron` (`TIPS_SCHEDULER_CRON`), default `0 10 0 * * *` |
| **Ten minutes after the recurring run** | Deliberate: on the first of the month the recurring job posts that day's transactions first, so the tips generated afterwards describe the month *with* them. The gap is a configuration convenience, not a guarantee — a failed recurring run makes it a lag, not a correctness problem |
| The month is left to the database | Each call passes `null`, so `sp_generate_tips` uses `CURDATE()` as the database session sees it (§7). A JVM in another zone cannot disagree with the database about the current month |
| One transaction per student | The run calls `generateTips` once per student; the transaction boundary is the caller's, so one student's failure cannot roll back tips already generated for the others |
| A failure never escapes | The method catches `RuntimeException` and logs it. A scheduled method that throws is logged by the framework and not retried until the next tick; catching it here keeps that behaviour while letting the log line say what failed |
| No retry inside a run | A failure here is a fault rather than a transient collision — the dedupe key makes a collision impossible. Repeating it would only delay the students after it; the next tick is the retry |
| Safe on more than one instance | `INSERT IGNORE` against `uk_tip_dedupe`. There is no leader election, for the reason `RecurringScheduler` records (BR-16) |
| Who is included | Every active student, not just those with spending this month. Filtering by activity is wrong at both edges: a student's first tips would never be generated by the schedule, and a student who stopped spending would keep having tips re-ranked against figures that no longer exist. The procedure skips a student with nothing to say, so a call that produces no tip costs one indexed read |
| Why not a database `EVENT` | The schema is locked (§4 of the governing prompt) and already schedules nothing else; a DB event would also be invisible to the application's logs and configuration. The application-side schedule is the arrangement module 5 established |

`TipGenerationSchedulerTest` exercises the real class with a stubbed DAO: that the run covers every
student once in order, that a failed student does not stop or skip the rest, that a failure is not
retried within the run, that a failed student-list read does not propagate, and that an empty run is
not a failure.

---

## 12. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-18 | Show the tips generated from the student's own spending | `GET /tips` | `listTips` | `listTips` | `v_dashboard_tips` | `aTipCarriesItsRenderedTextAndNothingInternal` |
| UC-18 | Ranked by what following them saves, pinned first | same | — | `TipMapper` | the view's `ORDER BY (state='PINNED') DESC, rank_score DESC` | `pinnedTipsLeadTheList` |
| UC-18 | A month picker offers the months with tips | `GET /tips/months` | `listMonths` | `listMonths` | `v_dashboard_tips` (`DISTINCT period_month`) | `theMonthsListMatchesTheMonthsThatHaveTips` |
| UC-18 A1 | A student with too little data still gets advice | `POST /tips/generate` | `generateTips` | `generateTips` | rule 6 of `sp_generate_tips` | `aStudentWithNoDataGetsTheGenericTip` |
| UC-18 A2 | A month that is not a month is refused | `GET /tips` | — | `resolveMonth` | — | `anUnparseableMonthIsRefused` |
| UC-18 | Pin a tip so it keeps its place | `POST /{id}/state` | `changeState` | `changeState` | `user_tips.state` + `pinned_at` | `pinningStampsTheStateAndItsTimestamp` |
| UC-18 | Un-pin a tip | same | — | `setState(NEW, …)` | the same two columns | `clearingAPinReturnsTheTipToNew` |
| UC-18 | Dismiss a tip so it stops being shown | same | — | `setState(DISMISSED, …)` | `state` + `dismissed_at` | `aDismissedTipStaysDismissedAcrossAGeneration` |
| BR-14 | A dismissed tip never comes back | `GET /tips` | — | — | the view's `state <> 'DISMISSED'` **and** the procedure's `INSERT IGNORE` | `aDismissedTipStaysDismissedAcrossAGeneration`, `aMonthWhoseOnlyTipWasDismissedLeavesTheMonthsList` |
| BR-14 | Top N only, N from a setting | `POST /generate` | — | `generateTips(…, null)` | `tips.max_dashboard`, `ROW_NUMBER()` | `theStoredTipCountIsBoundedByTheSettingsTopN` — the same four candidate tips stored three ways and five ways, proving the bound is the parameter the endpoint leaves unset; module 7's `DashboardApiIT#theTipBlockIsBounded` covers the effect on the list the dashboard reads |
| BR-15 | A category rising abnormally against the student's own history | — | — | — | rule 3, `v_category_spend_trend`, `insight.spike_threshold_pct` | `aRiseAgainstASingleEarlierMonthProducesTheSpikeTip` (including the `baseline_months >= 1` gate — §4.6), `aCategoryWithNoEarlierSpendingProducesNoSpikeTip` (a first purchase is not a rise). M9-02 reads the seeded `Entertainment spending is up 127.3%` tip, rule 3's output |
| VĐ-04 | The savings goal at risk | — | — | — | rule 5, `users.monthly_savings_goal` | `aMonthWhoseNetFallsBelowTheGoalProducesTheSavingsGoalTip`, `aMonthWhoseNetMeetsTheGoalProducesNoSavingsGoalTip`, `theSavingsGoalTipAndAnUnbudgetedCategoryTipCoexist`. M9-02 reads the seeded `Your savings goal is at risk` tip, rule 5's output for the demo account's 100.00 goal against a 71.00 net |
| BR-12 | Over-budget and near-budget advice at the configured thresholds | — | — | — | rules 1 & 2, `budget.exceeded_threshold_pct` (100%), `budget.near_threshold_pct` (80%) | `spendingOnTheExceededThresholdProducesOnlyTheOverBudgetTip` (exactly on the exceeded edge), `spendingOnTheNearThresholdProducesTheNearBudgetTip` (exactly on the near edge), `approachingTheLimitNeverProducesBothBudgetTips` (inside the band → one tip) |
| VĐ-05 | A threshold comes from a setting, not from code | `POST /generate` | — | the DAO passes `NULL` for `p_max_tips` | `system_settings` | `theStoredTipCountIsBoundedByTheSettingsTopN` (the setting decides when no count is passed) — the DAO javadoc also records that overriding is structurally impossible |
| VĐ-10 | A month boundary is the database's | all | — | `currentMonth()` for the read paths; `null` for the scheduler | the session's `time_zone` | `generatingReturnsTheListItProduced` |
| BR-02 | Ownership | all | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `tipsAreScopedToTheirOwner` |
| BR-02 | No response names the owner | 38, 40, 41 | — | `TipMapper` | — | `aTipCarriesItsRenderedTextAndNothingInternal`, `OpenApiContractIT#tipSchemasMatchTheDocumentedContract` |
| BR-02 | Another student's tip is not discoverable | 41 | — | `findByIdAndUserId` | the `user_id` predicate | `anUnknownTipIsNotFound` |
| §7.5 | Student-only, administrator refused | all | — | — | — | `tipsRequireAToken` |
| §13 | No duplicate endpoint | all | — | — | — | `OpenApiContractIT#noEndpointIsDuplicated` (the tips alias `doesNotContain` block) |
| §26 | Every endpoint is in the inventory and the document | all | — | — | — | `OpenApiContractIT#documentMatchesTheInventory` |
| UC-18 | Reading writes nothing | 38, 39 | — | `readOnly = true` | — | `readingAMonthDoesNotGenerateTips` |
| UC-18 | Generating is idempotent | 40 | — | — | `uk_tip_dedupe` + `INSERT IGNORE` | `generatingTwiceChangesNothing` |
| `ck_tip_state` | A state carries the timestamp it pairs with | 41 | — | `UserTip.setState` | `ck_tip_state` | `TipsStateConsistencyIT` (6 tests, against the real constraint) |
| UC-18 | A state change is decided from a locked row | 41 | — | `findByIdAndUserId` (`PESSIMISTIC_WRITE`) | — | `twoPinsOfOneTipSettleOnOneState`, `generatingWhilePinningDoesNotDuplicateATip` |
| UC-18 | The scheduled run is per-student and cannot die | — | — | `TipGenerationScheduler` | `sp_generate_tips` | `TipGenerationSchedulerTest` (7 tests) |

---

## Related documentation

- [`docs/api/FRONTEND_API_GUIDE.md`](FRONTEND_API_GUIDE.md) — **start here.** The single entry point
  for the frontend: base URL, interceptors, the shared error contract, the enum reference and the
  master table of all 76 operations
- [`docs/api/API_INVENTORY.md`](API_INVENTORY.md) — endpoints 38–41, and the decisions recorded with
  them
- [`docs/modules/MODULE_09_TIPS.md`](../modules/MODULE_09_TIPS.md) — the module report: the tests, the
  two review passes, and the traceability
- [`docs/testing/manual/MODULE_09_MANUAL_TEST.md`](../testing/manual/MODULE_09_MANUAL_TEST.md) — the
  hand-run procedure
- [`docs/api/dashboard.md`](dashboard.md) — module 7, the other reader of `v_dashboard_tips`, and the
  module that has to correct for the view's missing time filter
- [`docs/api/budgets.md`](budgets.md) — where the `budgets` rows that rules 1, 2 and 4 read are written
- [`docs/api/transactions.md`](transactions.md) — where the spending every rule reads is recorded
- [`docs/CREDENTIALS.md`](../CREDENTIALS.md) — the seeded accounts, including the demo student's three
  months of tips
- [`docs/SECURITY.md`](../SECURITY.md) — the security decisions behind these endpoints
