# AI categorisation and monthly insights — endpoints 68–71

UC-08 proposes a category for one of the signed-in student's records and learns from where they filed
it. UC-17 writes a narrative for a month from the student's own figures. Both call an external AI
provider when one is configured, and both work fully without one.

Base path `/api/v1`. Every endpoint needs a bearer token with the `STUDENT` role and touches only the
caller's own data.

## Table of contents

1. [Scope](#1-scope)
2. [The AI boundary — the rule every endpoint here obeys](#2-the-ai-boundary--the-rule-every-endpoint-here-obeys)
3. [Endpoints at a glance](#3-endpoints-at-a-glance)
4. [Field reference](#4-field-reference)
5. [Categorisation — 68](#5-categorisation--68)
6. [Insights — 69–71](#6-insights--6971)
7. [Status codes](#7-status-codes)
8. [Angular integration notes](#8-angular-integration-notes)
9. [Traceability](#9-traceability)

## 1. Scope

Four operations on three paths. Two features that share one capability — an optional call to an
external model — and one architecture.

- **UC-08** (`POST /ai/suggest-category`): proposes a category for a transaction, stores the proposal
  beside it as advice, and learns a per-student keyword-to-category mapping from the category the
  record is actually in.
- **UC-17** (`GET /insights`, `GET /insights/months`, `POST /insights/generate`): the month's figures,
  the categories that ran above their own usual level, and a summary and advice written about them.

## 2. The AI boundary — the rule every endpoint here obeys

The provider is **never** given database access. The flow is fixed, and it is enforced by the shape of
the code rather than by convention:

```
Angular ─► Spring Boot ─► this backend reads and filters the student's own rows
                        ─► a prepared context object ─► the AI provider
          ◄─ this backend validates the answer ◄─
```

- **The provider cannot fetch anything.** `AiSuggestionPort` — the port the services call — has no
  repository. An implementation can send only what a service deliberately handed it, so "the provider
  never sees the database" is a property of the type, not a promise in a comment.
- **Only aggregates and the minimum leave the server.** UC-08 sends the description and the names of
  the categories the student may file under — **no amount, no date, no identifier**. UC-17 sends the
  month's totals and category names, never a transaction. `MonthlyNarrativeRequest` is the type that
  states this, and `MonthlyCategoryTotal` is the shape `ai.send_aggregates_only` requires.
- **The answer is validated before it means anything.** Whatever the provider returns is matched back
  against the student's own categories; a name the student does not have is discarded rather than
  trusted. A category id is never taken from the provider's response.
- **The key never leaves the server.** `GEMINI_API_KEY` is read from the environment. It is never
  written to `application.yml`, never stored in MySQL, never put in a JWT, never sent to Angular, and
  never logged. The model and endpoint are deployment configuration (`AI_MODEL`, `AI_BASE_URL`), not
  administrator-tunable settings, because changing them changes where student data is sent.
- **No credential is a supported deployment.** With the key unset the application starts normally and
  installs `NoopAiSuggestionPort`. UC-08 falls back to the student's own learned rules; UC-17 keeps the
  `RULE_BASED` summary the database wrote. **Nothing is faked** — `generatedBy` records `RULE_BASED`
  rather than `AI`.
- **Two settings gate every call, read at the point of use (VĐ-05).**
  - `ai.enabled` — an administrator's master switch. Off, the provider is never called.
  - `ai.send_aggregates_only` — when off, UC-17's narrative is **skipped** rather than sent as
    per-row data. This build has no per-row disclosure path; refusing to call is the strict reading,
    and it is the deliberate one.
- **The answer is advice, and the API says so.** A suggestion never files a record on its own (BR-13),
  and an insight is a suggestion rather than financial advice.

The same boundary stated for the deployment as a whole, with the configuration side of it, is
[`../SECURITY.md`](../SECURITY.md) §13.

## 3. Endpoints at a glance

| # | Method | Path | UC | Success |
|---|--------|------|----|---------|
| 68 | POST | `/api/v1/ai/suggest-category` | UC-08 | `200` a proposal, or `source: NONE` |
| 69 | GET | `/api/v1/insights` | UC-17 | `200` the month's insight |
| 70 | GET | `/api/v1/insights/months` | UC-17 | `200` the months that have one |
| 71 | POST | `/api/v1/insights/generate` | UC-17 | `200` the insight after generating |

### Parameters

| Endpoint | Parameter | In | Default | Notes |
|---|---|---|---|---|
| 69, 71 | `month` | query | the current month | `yyyy-MM`. A malformed value is `400` |

### Authentication

Bearer token, role `STUDENT`. No token or an invalid one → `401 UNAUTHENTICATED`.

## 4. Field reference

### `SuggestCategoryRequest` (68)

| Field | Type | Required | Notes |
|---|---|---|---|
| `transactionId` | number | yes | Must be one of the caller's own and not in the trash; otherwise `404` |

### `CategorySuggestionResponse` (68)

| Field | Type | Notes |
|---|---|---|
| `transactionId` | number | The record this answer is about |
| `source` | `SuggestionSource` | `NONE` \| `RULE` \| `AI` |
| `categoryId` | number | The proposed category. Omitted when `source` is `NONE` |
| `categoryName` | string | Omitted when `source` is `NONE` |
| `type` | `CategoryType` | Omitted when `source` is `NONE` |
| `confidence` | number | 0–1. Advisory only — stored and shown, never decides anything. Omitted when `source` is `NONE` |
| `reason` | string | Why this proposal, in prose. Omitted when `source` is `NONE` |

### `LearnedCategoryRuleResponse` (68)

The mapping this call left stored — the "learning" half of UC-08.

| Field | Type | Notes |
|---|---|---|
| `keyword` | string | The description as stored: trimmed and lower-cased — the form the next match compares against |
| `categoryId` | number | The category the mapping now points at |
| `categoryName` | string | That category's name |
| `source` | `RuleSource` | `ACCEPTED` \| `OVERRIDE` \| `IMPORT` |

### `MonthlyInsightResponse` (69, 71)

| Field | Type | Notes |
|---|---|---|
| `periodMonth` | string | The month, `yyyy-MM` |
| `totalIncome` | number | The month's income, as the database computed it |
| `totalExpense` | number | The month's spending |
| `netAmount` | number | Income minus spending. Negative means the student spent more than they received |
| `summary` | string | What the month looked like, in prose |
| `advice` | string | What the student might consider next month, framed as a suggestion |
| `generatedBy` | `InsightGeneratedBy` | `AI` \| `RULE_BASED` \| `MANUAL` — **the field BR-13 turns on** |
| `model` | string | Which model wrote the prose. Null when rule-based |
| `flaggedCategories` | array | The categories that ran above their own usual level |
| `generatedAt` | datetime | |

`FlaggedCategoryResponse`: `categoryId`, `categoryName`, `currentTotal`, `baselineAvg`,
`pctChange`.

### `InsightMonthsResponse` (70)

| Field | Type | Notes |
|---|---|---|
| `months` | array | `yyyy-MM` strings, newest first. Empty when nothing has been generated |

## 5. Categorisation — 68

### 68 — `POST /api/v1/ai/suggest-category`

Proposes a category for one of the caller's own records, records the proposal beside it, and learns the
mapping implied by the category the record is in.

**It proposes; it does not file.** The record keeps the category the student chose. The proposal is
stored beside it as a suggestion the student may review and override, which is what BR-13 requires, and
**no call to this endpoint moves a transaction from one category to another.**

**How the proposal is reached.** First the student's own learned mappings are consulted, and an exact
match on the description wins. Only when there is no mapping is the configured AI service asked, and
only with the description and the names of the categories the student may file under — no amount, no
date, no identifier. Whatever it answers is matched back against those categories before it becomes
anything, so a name the student does not have is discarded rather than trusted. If neither step
proposes anything the answer says so with `source: NONE` — **that is a result, not an error.**

**The preserved example (UC-08).** A student records "Campus Cafe" and files it under **Food**. The
mapping `"campus cafe" → Food` is stored. The next record whose description reads "Campus Cafe" is
proposed `Food` with `source: RULE`. If the student instead files it under **Entertainment**, the
mapping is stored with `source: OVERRIDE` — the filing corrected the system, and the next match uses
the corrected value.

**It learns as a side effect.** UC-08's "learn from corrections" is this per-student mapping. Repeating
a call over an unchanged record writes nothing the second time, so opening a screen twice cannot
accumulate history.

**What it costs to run without an AI service.** Nothing about the feature stops working. A deployment
with `ai.enabled` off answers every record from the student's own mappings and reports `source: RULE`;
a new description gets `source: NONE` until the student files something similar enough to have taught
one.

```json
POST /api/v1/ai/suggest-category
{ "transactionId": 31 }
```

```json
{
  "transactionId": 31,
  "source": "RULE",
  "categoryId": 4,
  "categoryName": "Food",
  "type": "EXPENSE",
  "confidence": "1.0000",
  "reason": "A mapping you taught the system matches this description."
}
```

## 6. Insights — 69–71

### 69 — `GET /api/v1/insights?month=2026-09`

The caller's insight for one month: the totals, the categories that ran above their own usual level,
and the summary and advice written about them.

`month` is optional; left out it means the current month as the server judges it.

**Reading does not generate and does not call any AI provider.** A month whose insight was never
produced answers `404` — an insight is one object rather than a list, so there is nothing to return an
empty version of. Generate one first with 71.

### 70 — `GET /api/v1/insights/months`

The months the caller has an insight for, newest first.

This exists so a month picker **offers only months that would return something**. Building the list
from the student's transactions instead would offer months whose insight was never generated, and
opening one would answer `404` behind a menu entry. The list can be empty, and the current month is not
special-cased into it.

### 71 — `POST /api/v1/insights/generate?month=2026-09`

Recomputes the caller's insight for the named month — or the current month when none is named — and
returns it.

**The figures come first, from the database.** `sp_generate_monthly_insight` computes the month's
totals and its unusual categories and stores them. **Only then**, if an AI provider is configured and
`ai.enabled` is on, is the provider given the month's aggregates and asked to write the summary and
advice; what it writes is stored over the rule-based text. **The database never sends anything to a
provider by itself** — the application reads, filters to the caller's own month and sends aggregates
only.

**Works with no AI provider.** When there is no credential, when `ai.enabled` is off, when
`ai.send_aggregates_only` is off, or when the provider is unreachable, **the rule-based summary stands
and the request still succeeds.** A response therefore always has `generatedBy` set, and `model` only
when a provider wrote the text.

**Safe to call repeatedly.** The month is stored once and refreshed in place, so repeating the call
cannot produce a second insight, and a summary a provider already wrote is preserved rather than
overwritten by a later run.

Generating costs nothing to observe: reading the month afterwards does not generate again.

## 7. Status codes

| Code | Error code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | 68: the body is missing `transactionId`. 69, 71: `month` is not a valid month |
| `401` | `UNAUTHENTICATED` | No token, or an invalid, expired or revoked one |
| `404` | `NOT_FOUND` | 68: no such transaction of the caller's, or it is in the trash. 69: no insight exists for that month |
| `409` | `DATA_CONFLICT` | 68: the record changed while the proposal was being written. Reload and try again |

## 8. Angular integration notes

- **Treat `source: NONE` as a normal answer.** It means "no proposal", not "something failed". Render
  it as the absence of a suggestion, not an error toast.
- **`confidence` is display only.** Do not sort, filter or auto-select by it. A `RULE` proposal carries
  the certain value because a mapping was taught, not because it was scored.
- **Never auto-apply a suggestion.** BR-13 requires the student to be able to review and override it;
  a screen that files the record on the suggestion alone changes the student's data without their
  having said so.
- **Drive the month picker from 70**, not from the transaction history — otherwise every month with no
  insight is a menu entry that opens onto a `404`.
- **Show `generatedBy` beside the prose.** A screen that presents an AI sentence as if it were the
  database's own figure is the mistake BR-13 exists to prevent, and `generatedBy` is the field that
  lets a client avoid it. `AI` and `RULE_BASED` should read differently.
- **Do not offer a "regenerate" as if it were free.** It is safe to call repeatedly but it may call the
  provider again. The figures are identical either way.
- **A `409` on 68 means reload and retry**, not that the record is unprocessable.

## 9. Traceability

| UC | Endpoint | Database / service |
|---|---|---|
| UC-08 propose a category | 68 | `category_rules` (per-student keyword mapping), `transactions.ai_suggested_category_id`; `CategorisationService`, `AiSuggestionPort` |
| UC-08 learn from a correction | 68, 66 | `category_rules`, written by the suggestion and by each imported row |
| UC-17 generate a month | 71 | `sp_generate_monthly_insight` writes the figures, then `InsightService` layers the narrative |
| UC-17 read a month | 69 | `insights` |
| UC-17 month picker | 70 | `insights.period_month` |

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list, and the AI-boundary section.
- [imports.md](imports.md) — where `aiSuggestedCategoryId` appears in a preview row.
- [../SECURITY.md](../SECURITY.md) — the security decisions behind the AI boundary.
- [../modules/MODULE_12_ADVANCED.md](../modules/MODULE_12_ADVANCED.md) — the module report.
