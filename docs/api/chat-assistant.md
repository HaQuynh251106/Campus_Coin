# Chat assistant — endpoints 77–78

A student asks a question about their own Campus Coin records in their own words, and a language model
answers it from figures this backend read for them. It is the only surface in the application where the
reply is free-form prose rather than a resource a client can validate field by field.

Base path `/api/v1`. Both endpoints need a bearer token with the `STUDENT` role and touch only the
caller's own data.

> **These endpoints implement no numbered use case.** The use case and business-flow specification runs
> UC-01…UC-27, and its twenty-seventh is the display preferences on `/api/v1/profile/me`; there is no
> conversational assistant anywhere in it. The feature was requested by the project owner after module
> 12, is **not** part of that (locked) module, and is recorded in `API_INVENTORY.md` as a post-module
> addition with `—` in the UC column. A fabricated `UC-28` would read as an approved requirement a
> reviewer could go and check, and there would be nothing there to find.

## Table of contents

1. [Scope](#1-scope)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [Availability — 77](#4-availability--77)
5. [Ask — 78](#5-ask--78)
6. [The nine tools](#6-the-nine-tools)
7. [Multi-turn conversation](#7-multi-turn-conversation)
8. [Ownership](#8-ownership)
9. [Provider failure](#9-provider-failure)
10. [Status codes](#10-status-codes)
11. [Security properties](#11-security-properties)
12. [Angular integration notes](#12-angular-integration-notes)
13. [Traceability](#13-traceability)

## 1. Scope

Two operations on one path. The `POST` is the conversation; the `GET` reports whether one is possible.

The two share a URL because they are one feature seen two ways — the arrangement `/profile/me` uses for
its `GET` and `PATCH`. They are not a list-and-create pair: neither creates anything. The `GET` exists
so a chat panel can say the assistant is unavailable when it opens, rather than accepting a question it
cannot answer.

**This is read-only.** The assistant can describe the student's month; it cannot change it. The nine
tools are all reads, and there is no endpoint here that writes. That is a deliberate limit rather than
a stage of development: a conversational assistant that could create a transaction or move a budget
limit would be a route for a misread sentence to change a student's records, and the application has
screens for those operations where the student sees what they are about to save.

## 2. Endpoints at a glance

| # | Method | Path | UC | Success |
|---|--------|------|----|---------|
| 77 | GET | `/api/v1/chat` | — | `200` whether the assistant is available |
| 78 | POST | `/api/v1/chat` | — | `200` the reply, its model and the tools it read |

## 3. Field reference

### Request — 78

| Field | Type | Required | Constraints | Notes |
|---|---|---|---|---|
| `message` | string | yes | non-blank, ≤ 2000 chars | The question. |
| `history` | array | no | ≤ 30 turns | The conversation so far, **oldest first**. The new question goes in `message`, not here. |

Each entry of `history`:

| Field | Type | Required | Values |
|---|---|---|---|
| `role` | string | yes | `USER` or `ASSISTANT` |
| `text` | string | yes | non-blank, ≤ 2000 chars |

There is **no `USER`-settable system role**. The instruction the model runs under is a server-side
constant; a `role` of `SYSTEM` is a `400`, so a client cannot rewrite the assistant's own rules. That
is the reason the enum has two members and not three.

### Response — 78

| Field | Type | Notes |
|---|---|---|
| `reply` | string | The assistant's answer. Never blank — a blank provider response is a `503`, not an empty reply. |
| `model` | string | The model that wrote it, e.g. `gemini-3.5-flash`. The same provenance disclosure `MonthlyInsightResponse.model` makes. |
| `toolsUsed` | string[] | The reads that grounded the answer, in the order the model asked for them. **Empty is a meaningful value**: it means the answer needed no data, which is the shape a refusal takes. |

### Response — 77

| Field | Type | Notes |
|---|---|---|
| `available` | boolean | `true` when a question would reach a provider. |
| `model` | string | Omitted when `available` is `false`. |
| `reason` | string | Omitted when `available` is `true`. A sentence for the student — it names no provider, credential, setting key or endpoint. |

## 4. Availability — 77

`GET /api/v1/chat` → `200`

```json
{ "available": true, "model": "gemini-3.5-flash" }
```

```json
{ "available": false, "reason": "The assistant isn't available on this installation right now." }
```

**It asks the provider nothing.** The answer comes from two facts the backend already holds: whether a
credential is configured, and whether an administrator has switched the feature on (`ai.enabled` in
`system_settings`, read at the point of use). A probe that actually called the provider would spend the
deployment's daily quota answering a question about the quota, and would turn a read-only status check
into an outbound request carrying the deployment's credential.

Call it when the chat panel opens. A panel that knows the assistant is off can render a notice instead
of an input box that will fail.

**`available: false` is not an error and not a `503`.** The request succeeded; the answer is that the
feature is off. `503` is reserved for the `POST`, where a question was actually asked.

## 5. Ask — 78

`POST /api/v1/chat`

```json
{
  "message": "How much did I spend on Food this month?",
  "history": [
    { "role": "USER", "text": "What is my balance?" },
    { "role": "ASSISTANT", "text": "Your net for September is -120.50 ..." }
  ]
}
```

→ `200`

```json
{
  "reply": "You spent 84.20 on Food in September. That is 31% of your 271.50 total spending.",
  "model": "gemini-3.5-flash",
  "toolsUsed": ["getCategorySpending"]
}
```

```json
{
  "reply": "I can help with your Campus Coin finances, transactions, budgets, reports, categories and related features, but I can't help with that topic.",
  "model": "gemini-3.5-flash",
  "toolsUsed": []
}
```

The second example is the out-of-scope refusal, and `toolsUsed: []` is what distinguishes it from a
grounded answer: the model read nothing, because the question was not about the student's records.

## 6. The nine tools

The model does not receive the student's data in its prompt. It receives **declarations** — names,
descriptions and parameter schemas — and when it needs a figure it names one. `ChatService` runs that
named read against the authenticated student's own rows and hands the result back. The model then writes
its answer from the figures it was given and nothing else.

The nine are one per existing service capability, not one per phrasing a student might use. Where the
project brief's illustrative list named two tools that would each be a second route to the same figure
(`getBudgets` and `getBudgetStatus`, `getMonthlySummary` and `getReport`, `getInsight`), the layer
exposes the one the application already owns the calculation for rather than a duplicate.

| Tool | Reads | Backing service |
|---|---|---|
| `getFinancialSummary` | This month's income, expense, net, top expense category, allowance baseline and savings goal | `DashboardService.getDashboard` |
| `getMonthlySummary` | One named month's totals and per-category breakdown | `ReportService.getReport` |
| `getCategorySpending` | One category's spending in one month, and its share of that month | `ReportService.getReport` |
| `getBudgetStatus` | Each limit with its spend, remainder, percentage and status | `BudgetService.listBudgets` |
| `getTransactions` | Individual records between two dates, optionally by category and kind | `TransactionService.listTransactions` |
| `getSavingTips` | The tips that already exist for one month | `TipService.listTips` |
| `getForecast` | Next month's projection and the months it is averaged from | `ForecastService.forecast` |
| `getAnomalies` | Records the detector already marked, with its explanation | `AnomalyService.list` |
| `getRecentActivity` | What the student recently opened or changed | `RecentActivityService.list` |

**Four capabilities the brief lists are deliberately absent**, because each is a second route to a
figure above: there is no `getBudgets` beside `getBudgetStatus`, no `getReport` beside
`getMonthlySummary`, no `getInsight` (the same month's figures read twice, already covered by
`getMonthlySummary`), and no `getCurrentFinancialSummary` (the dashboard read `getFinancialSummary`
already is).

**Nothing that writes is a tool.** There is no scan, no tip generation, no insight generation and no
mark-notification-read, so the assistant cannot spend a provider call or write a row on the student's
behalf.

### What a tool returns

A small JSON-compatible object, never the raw entity. Internal identifiers are dropped where a sentence
does not need them: `getRecentActivity` returns the category's **name** rather than its `categoryId`, and
`getTransactions` returns no transaction id. The reason is the data boundary, not tidiness — an
identifier the model cannot put in a sentence is disclosure for nothing, so it is not sent.

A tool that cannot answer — a malformed month, an unknown category, a month with no records — returns
an object carrying the reason rather than throwing. The model reads it and corrects itself, or tells the
student. A refused tool is an ordinary result and the turn continues.

## 7. Multi-turn conversation

**There is no session and no stored transcript.** The client sends the conversation with each request;
the backend keeps nothing. Three consequences are worth stating plainly:

- **Follow-ups resolve against the actual conversation.** "What about last month?", "Was that above my
  budget?" and "Why?" are answered by the model reading the turns it was given, not by the application
  matching a phrase. There is no keyword or regular-expression parser in this path.
- **Nothing can leak between accounts.** There is no server-side history to be attributed to the wrong
  student, because there is no server-side history.
- **The client owns the transcript.** It sends the last 30 turns; the backend drops earlier ones from
  the front, and drops blank turns. A client that sends none gets a one-question conversation, which
  works and simply cannot resolve a pronoun.

## 8. Ownership

**Identity is the bearer token, and the request has no field that could say otherwise.**

- There is no `userId` in the request. Jackson ignores an unknown property, so a body carrying one is
  answered normally and the id is simply discarded — an attempt to reach another student's records is
  not refused, it is inert: the read follows the token regardless. That is the stronger property, and
  it is what the integration suite asserts by sending one student's id in another's request.
- No tool takes an identifier. The `AuthenticatedUser` is bound into the tool callback by `ChatService`
  from the security context, so the model has no parameter through which to name a student.
- Every tool call delegates to a service whose query narrows on `user_id` first — the same services the
  student's own screens call, so a figure the assistant quotes is the figure the screen shows.

## 9. Provider failure

**A provider failure is reported, never disguised.** This is the one place where the AI policy of the
rest of the application is deliberately inverted:

| | UC-08, UC-17 | This route |
|---|---|---|
| Provider unavailable | Returns empty; the application gives its deterministic answer | Throws; `503` |
| Why | A keyword match and a rule-based summary are valid answers that no provider is needed for | A conversation has no non-model answer |

Text composed by the application would be read as the assistant's while no assistant wrote it. So the
following all produce `503 AI_UNAVAILABLE` with a student-readable message, and **no reply body**:

- No credential configured on the deployment.
- `ai.enabled` switched off.
- A rate limit or an exceeded daily quota (`429` from the provider).
- A timeout or an unreachable provider.
- A blank, malformed or tool-loop-exhausted response.

**A client must render this as an error and offer a retry.** It must never fall back to a locally
composed sentence, and the frontend wiring must not contain one — see §12.

## 10. Status codes

### 77

| Code | When | Body |
|---|---|---|
| `200` | Always, when the token is valid | `{available, model?, reason?}` |
| `401` | No token, or it is invalid, expired or revoked | `ApiError` |

### 78

| Code | When | Body |
|---|---|---|
| `200` | The assistant answered | `ChatResponse` |
| `400` | `message` missing/blank/over 2000 chars; a `history` turn malformed; a `role` other than `USER`/`ASSISTANT` | `ApiError`, field-level `errors[]` where applicable |
| `401` | No token, or it is invalid, expired or revoked | `ApiError` |
| `403` | A valid token whose role is not `STUDENT` | `ApiError` |
| `503` | `AI_UNAVAILABLE` — see §9. **No reply is returned.** | `ApiError` |

## 11. Security properties

- **The provider never sees the database.** `ChatCompletionPort` has no repository, no `EntityManager`
  and no user id — the same discipline `AiSuggestionPort` states. It has a callback; the service that
  owns the authenticated principal supplies it. The model can only receive what that service chose to
  read for it.
- **The key never leaves the server.** `GEMINI_API_KEY` is read from the environment, is never written
  to `application.yml`, never stored in MySQL, never put in a JWT and never sent to Angular. It is not
  logged and no endpoint can return it. `GET /api/v1/chat` publishes the **model**, not the credential.
- **No credential is a supported deployment.** With `GEMINI_API_KEY` unset the application starts and
  every other feature works; `/api/v1/chat` answers `503` and 77 reports `available: false`.
- **What is never sent.** Password hashes, tokens, reset tokens, API keys, SMTP and database
  credentials, internal secrets, administrator-only information and any other student's data. A tool
  returns figures from the caller's own rows; there is no tool that reads another student's, and none
  that reads a credential.
- **No raw database access.** The model cannot write SQL, name a table or reach a repository. The
  tools are the complete list of what it can read.
- **Scope is enforced by the instruction, and still by the code where it matters.** The system
  instruction tells the model to decline anything outside the student's Campus Coin finances with a
  fixed sentence. The properties that must not depend on a model obeying an instruction do not: the
  read-only limit is structural (there is no writing tool), and ownership is structural (there is no
  identifier to abuse).

## 12. Angular integration notes

**Preserve the existing chat panel.** The layout, the mascot and the visual language are the project
owner's and are not part of this contract.

### Service shape

```ts
ask(message: string, history: ChatTurn[]): Observable<ChatResponse>
availability(): Observable<{ available: boolean; model?: string; reason?: string }>
```

Send the transcript on every call — there is no session to rely on.

### The three states the panel needs

| State | Trigger | What to render |
|---|---|---|
| Unavailable | `available: false` at open | A notice with `reason`. No input, or a disabled one — do not accept a question that will fail. |
| Error | `503` from the `POST` | The `ApiError.message`, styled as a retryable failure, with a retry affordance. |
| Distinct error | `400` | A field error — the message was empty or too long. Usually preventable client-side. |

### Latency, and the client timeout that sits under it

A turn is **two** provider calls when the model needs data — it asks for a tool, then writes the reply
from what the tool returned — so a grounded answer takes about **6–12 seconds**, and a heavier question
that reads several tools takes longer still. Measured against a live provider: 5.7 s and 12.1 s for the
same summary question, 8.4 s and 8.8 s for a category follow-up.

**`errorInterceptor` applies `timeout(12000)` to every request**, so a slow *but successful* turn can
have its connection aborted client-side and be rendered as `Request timed out` while the backend goes on
to answer. That is a real, observed failure mode, not a hypothetical: the server logged
`Chat answered userId=2` at 16:28:57.873 for a request the browser aborted at `ERR_ABORTED`. The panel
handles it correctly — it shows a retryable failure and re-sends the same question — so the honest
reading is "the turn was slow", never "the assistant is broken". Treat a timeout as retryable, and do
not lower this budget by composing a local answer.

### When the provider refuses because of quota

`503 AI_UNAVAILABLE` covers a rate limit as well as an outage. On the free tier Gemini allows **20
requests per model per day**, and exhausting it answers `429 RESOURCE_EXHAUSTED` — which this route
reports as the same `503`, because a client can do nothing different about either. Confirmed by calling
the provider's REST endpoint directly with the configured key, bypassing this application entirely. The
practical consequence for a frontend: a `503` late in a testing session is often quota, not a bug, and
the error is genuinely the honest answer.

### What the client must not do

- **Do not compose a reply when the request fails.** The previous implementation of this panel answered
  from keyword matching and rendered paragraphs of invented figures — `$423.50 net for September`,
  `85% of your $35 coffee cap` — as the student's own account. Section 15 of the rebuild brief forbids
  presenting application-composed text as the assistant's, and a `503` is the honest answer.
- **Do not send a user id.** There is no field for one.
- **Do not let one student's transcript outlive their session.** Ownership on the server is the bearer
  token's job, but the transcript is the client's, and a root-scoped Angular service outlives the panel
  that shows it. If the transcript is still in memory when a different student signs in on the same tab,
  the panel opens onto the previous student's replies — and, because `history` is sent with each call,
  their questions are replayed as the new student's conversation. The server is not fooled (every read
  still follows the token), but the wrong person is shown the wrong figures. Clear the transcript when
  the signed-in account changes.
- **Do not label a reply the backend did not produce as AI.** `reply` is always model-written; if the
  request failed, there is no reply.

## 13. Traceability

| Behaviour | Endpoint | Controller | Service | Test |
|---|---|---|---|---|
| Report availability | 77 | `ChatController.availability` | `ChatService.availability`, `ChatCompletionPort.isExternalProvider` | `ChatApiIT` |
| Answer a grounded question | 78 | `ChatController.ask` | `ChatService.ask` → `ChatToolExecutor.execute` | `ChatApiIT` |
| Refuse another student's data | 78 | `ChatController.ask` | `ChatRequest` has no user-id field; principal bound in `ChatService` | `ChatApiIT` |
| Reject an unauthenticated caller | 78 | — | `SecurityConfig` `/api/v1/chat/**` → `hasRole("STUDENT")` | `ChatApiIT` |
| Report a provider failure without inventing a reply | 78 | `ChatController.ask` | `AiProviderUnavailableException` → `ErrorCode.AI_UNAVAILABLE` | `ChatApiIT` |
| Read no more than the declared tools | 78 | — | `ChatTool` (nine constants), `ChatToolExecutor` | `ChatToolTest` |

---

**Related documentation**

- [API_INVENTORY.md](API_INVENTORY.md) — endpoints 77–78 and the post-module note at the top
- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — §7's per-endpoint reference and the master table
- [ai-and-insights.md](ai-and-insights.md) — the AI boundary endpoints 68–71 obey, and why this route
  does not share their silent-fallback policy
- [../SECURITY.md](../SECURITY.md) — the key-handling and data-boundary rules
