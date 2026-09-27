# Campus Coin — Implementation / Change Summary

**Date:** 2026-09-27
**Pass:** corrective implementation + causal end-to-end QA
**Companion to:** `docs/testing/qa/CAUSAL_RELATIONSHIP_QA_REPORT.md`

This document lists **every source file this pass changed, and nothing else.** Nothing unrelated was
reformatted, no screen was restyled, and no file outside the change set was rewritten. Where a change
had to touch a file the concurrent UI work also touches, the edit was surgical and every unrelated line
was preserved.

---

## 1. Change set at a glance

| # | File | Kind | Lines | Why |
|---|---|---|---|---|
| 1 | `frontend/src/app/shared/components/announcement-banner/announcement-banner.component.ts` | **new** | 105 | REL-35 UI leg — the missing consumer for `dashboard.announcements` |
| 2 | `frontend/src/app/features/home-feed/home-feed.component.ts` | modified | +46 / −7 | Render the banners; rank the budget strip |
| 3 | `frontend/src/app/core/models/budget.model.ts` | modified | +45 / −1 | `rankBudgetsBySeverity`; correct the `consumptionStatus` vocabulary |
| 4 | `frontend/src/app/core/services/budget.service.ts` | modified | +19 / −3 | Read the server's `consumptionStatus` instead of re-deriving it |
| 5 | `backend/src/main/java/com/campuscoin/transaction/repository/TransactionDescriptionEncryptionDao.java` | **new** | 105 | OB-018 — re-encrypt imported descriptions |
| 6 | `backend/src/main/java/com/campuscoin/imports/service/ImportService.java` | modified | +92 / −1 | OB-018 — call the repair on the commit path |

**Totals: 6 files — 2 new, 4 modified; 307 lines added, 12 removed.**

**A third change set follows at §4-bis** — the chatbot rebuilt as a real conversational assistant (9 new
backend feature files, 5 new provider-adapter files, 3 new test files, 3 frontend files, the feature's own
API document). It is much larger than either set above and is driven by a separate brief, so it is given
its own section rather than folded into this table.

**Of these six files: no change was made in `db/`, `pom.xml`, `package.json`, or any test's
assertions.** No database object was hand-edited. No migration was written, because Causal QA
Correction 6 found no schema or procedure drift to migrate.

### Added later in the same session — the provider-reconnection work (D-21)

A separate, smaller change set, made after the six above, when the user supplied real provider
credentials and asked for the affected features to be connected and re-tested. It is listed
separately because it was driven by a different request and has a different risk profile.

| # | File | Kind | Change | Why |
|---|---|---|---|---|
| 7 | `backend/src/main/java/com/campuscoin/common/ai/AiProperties.java` | modified | `DEFAULT_MAX_TOKENS` `1024 → 8192`, with a comment explaining why | Gemini 3.x bills thought tokens against the cap; `1024` let thinking consume the whole budget intermittently, truncating the reply mid-JSON |
| 8 | `backend/src/main/resources/application.yml` | modified | `max-tokens: ${AI_MAX_TOKENS:1024} → ${AI_MAX_TOKENS:8192}`, with a comment | **This placeholder is what actually governs** — it resolves a number even when `AI_MAX_TOKENS` is unset, so the Java constant alone had no effect. Found by inspecting the request the app put on the wire, not by reading the code |
| 9 | `.gitignore` | modified | `.env` / `.env.local` / `.env.*.local` → `.env*` + `!.env.example`; added `.playwright-mcp/`, `.claude/settings.local.json` | A hand-made `.env.local.bak` was **not** ignored — a real secret-leak risk. `.env*` covers every backup spelling |
| 10 | `.env.example` | modified | `AI_MAX_TOKENS` `1024 → 8192` plus guidance | The committed template carried the same trap |
| 11 | `.env.local` | modified | Real provider values written; provider description corrected to Gemini | The template described the provider as Anthropic/`claude-opus-5`; the code is Gemini. `AI_MODEL` was left commented — a Claude name would be sent to the Gemini SDK and fail silently |

Changes 7–8 are the only **product-code** changes in this set, and both are the same one-line value
plus its explanation. Change 9 is a safety fix. Changes 10–11 are local/template configuration and
`.env.local` is gitignored.

### Documents changed (deliverables, not product code)

| File | Purpose |
|---|---|
| `docs/testing/qa/CAUSAL_RELATIONSHIP_QA_REPORT.md` | Deliverable — the final causal report (rewritten), then updated with the D-21 results |
| `docs/testing/qa/MANUAL_USER_TEST_CHECKLIST.md` | Deliverable — §19 human tests + HUMAN TEST HANDOFF (new), then narrowed by D-21 |
| `docs/testing/qa/IMPLEMENTATION_CHANGE_SUMMARY.md` | Deliverable — this document (new) |
| `docs/testing/qa/DECISION_LOG.md` | Deliverable — extended with this pass's decisions (D-13…D-23) |
| `CAMPUS_COIN_M12_FRONTEND_IMPLEMENTATION_REPORT.md` | **Finding 3 retracted** after measurement |

---

## 2. Change 1 — the announcement banner (REL-35)

**Defect.** `GET /api/v1/dashboard` returned `announcements[]` correctly — audience-filtered, with
`severity` and the date window — but **no component consumed the field.** An administrator could publish
a notice and no student would ever see it. `FRONTEND_API_GUIDE.md:1554` requires the client to render
it, so this was a contract violation, not a missing nicety.

**Fix.** A new standalone, presentational component plus its wiring.

`frontend/src/app/shared/components/announcement-banner/announcement-banner.component.ts` (new, 105
lines):

- **Input only.** It takes a single `announcement` and renders it. It holds no service and no state, so
  it cannot become a second source of truth about which notices apply.
- **It never re-filters the audience.** The server has already decided what this caller may see; a
  second filter in the client is exactly how a notice the server published fails to appear. This is
  stated in the component's own header comment so a later edit does not "helpfully" add one.
- **Severity → appearance is a total mapping.** `INFO → sky`, `WARNING → amber`, `SUCCESS → emerald`,
  with a default for an unrecognised value, so a new server-side severity degrades to a readable banner
  rather than an unstyled or invisible one.
- **The severity is shown as a word, not only as a colour.** An `INFO` and a `WARNING` must remain
  distinguishable with colour ignored (§19.8).
- **The date window is stated honestly.** `endsAt ? 'From X until Y' : 'From X'` — an open-ended notice
  names no end date, because inventing one would be a lie about the data.

`frontend/src/app/features/home-feed/home-feed.component.ts` (modified, +46 / −7):

- Imports and registers the component; reads `this.announcements = dashboard.announcements || []`.
- Renders `@if (announcements.length > 0) { @for (notice of announcements; track notice.id) { … } }` —
  one banner per notice, and **nothing at all** when the list is empty. An empty `@for` that leaves an
  empty container behind would put a gap at the top of every student's home screen forever.
- The announcement failure is isolated from the dashboard load: a failed announcement fetch does not
  present as "the whole screen failed to load its figures".

**Verified** in a browser against the running API: 3 active student notices → 3 banners, three distinct
tones, body text and severity word rendered, the windowed notice names its window, the open-ended notice
names none, the `ADMINS`-audience notice is absent. **11/11 assertions PASS, zero console errors.**

---

## 3. Changes 2–4 — the budget strip and the `consumptionStatus` vocabulary

These three files move together; the third is the reason the first two exist.

**Defect A — the strip hid the worst budget.** `GET /api/v1/budgets` is ordered by **category name** so
the budgets screen is stable between calls. The home strip took that list's first four rows, so with all
six budgets over their limit the student saw whichever four sorted first alphabetically and could miss a
budget at 2144% of its limit entirely.

**Defect B — a dead comparison.** `budget.service.ts` compared the server's field against
`'WARNING'`, a value the endpoint never sends — it sends `ON_TRACK | NEAR | EXCEEDED`, which is
`v_budget_consumption`'s own `CASE`. The `DANGER` branch happened to work; the `WARNING` branch was
unreachable through the server's value and only ever fired via the local `consumedPct` fallback.

**Fix.**

- `budget.model.ts` — the type now publishes the three values the API actually sends
  (`ON_TRACK | NEAR | EXCEEDED`), with a comment recording that this is deliberately **not** the same
  vocabulary as the local `alertStatus` (`SAFE | WARNING | DANGER`), which remains a display
  classification.
- `budget.model.ts` — new `rankBudgetsBySeverity()`. It orders **by the server's own classification**,
  not by a threshold re-implemented in the client, so a student who changes
  `budget.near_threshold_pct` moves the label and this order together and there is no second definition
  to drift. `consumedPct` breaks ties inside a band; `categoryName` breaks the rest so the strip does
  not reshuffle between two renders of identical data. **It sorts a copy** — the service's own list
  keeps the API's order, so the budgets screen is unaffected.
- `budget.service.ts` — reads the server's `consumptionStatus` first and derives one only when the field
  is absent, then derives the local `alertStatus` **from that**, so the two can no longer disagree.

**Verified.** API returns 6 budgets in name order (Academics, Entertainment, Food, Hostel/Rent,
Subscriptions, Transport — all `EXCEEDED`). The strip renders **Food (2144.33%) → Academics (327.5%) →
Entertainment (190%) → Transport (183.36%)**; the old code would have shown the four alphabetical ones,
omitting Food. 3/3 assertions PASS.

---

## 4. Changes 5–6 — OB-018, imported descriptions stored as ciphertext

**Defect.** The import path produced an **inconsistent** result: some imported rows were stored as
encrypted envelopes and some as plaintext, in the *same* commit. The plaintext rows left student
financial descriptions readable at rest, contradicting §12.2 of `SECURITY.md`, which documents
`transactions.description` as **Encrypted**.

**Root cause.** `sp_apply_csv_batch` — the procedure the commit path calls — copies
`import_rows.description` verbatim into `transactions.description`. The encryption is an
**application-layer** concern (AES-256-GCM in `EncryptionService`), so a procedure copying a column
cannot apply it. Rows that reached the ledger by any route that did encrypt came out as envelopes; rows
copied by the procedure did not.

**Fix.** The commit path now repairs the rows it just created.

- `TransactionDescriptionEncryptionDao.java` (new, 105 lines) — **one** statement that re-writes the
  descriptions of exactly the transactions the batch created:
  ```sql
  UPDATE transactions SET description = CASE id WHEN … END
   WHERE user_id = :userId AND source = 'CSV' AND id IN (:ids)
  ```
  The `user_id` and `source = 'CSV'` predicates are not decoration: they bound the statement to the
  caller's own freshly-imported rows, so a stale session cannot re-encrypt — or touch — anyone else's
  data. The javadoc records why the procedure cannot do this itself and what OB-018's answer was.
- `ImportService.java` (+92 / −1) — calls `reencryptImportedDescriptions(userId, rows)` after a
  successful commit, which also writes the corresponding `transaction_history` audit rows.

**Verified.** A fresh 4-row CSV import (batch 14) produced transactions **117–120, all four stored as
encrypted envelopes** (`AQ…`, 72 characters = 12-byte nonce + 24-byte ciphertext + 16-byte tag), and all
four **read back decrypted correctly** on both `GET /transactions/{id}` and the list endpoint. Contrasted
against the pre-fix pattern (70 encrypted / 71 plaintext / 72 encrypted / 73 plaintext), which is what
made the defect visible in the first place.

> **Note on the 72-character figure.** An earlier draft of the analysis asserted envelopes were 64
> characters and consequently mislabelled the 72-character rows as plaintext. The arithmetic is
> 12 + 24 + 16 = 52 bytes → 72 base64 characters. The corrected measure is what the verification uses.

**Related residual, deliberately not fixed.** `recurring_rules.description` is documented **Encrypted**
but all four seed rules hold **plaintext**, written by `db/06_demo.sql`. The scheduler copies the stored
value verbatim, so a scheduler-posted transaction also carries a plaintext description. This is the same
class of finding as OB-018 on the recurring path. It was **not** fixed here for a concrete reason:
`PATCH`ing the seed rules would fire the update trigger and the budget-alert check, risking spurious
student notifications purely to re-encrypt a cosmetic string. `SECURITY.md` §12.7 already documents the
transitional state and names the remedy. Recorded in the causal report §L.

---

## 4-bis. Change set 3 — the chatbot rebuilt as a real conversational assistant

A third, much larger change set, driven by a separate brief: the existing `ChatbotService` was a
keyword-matching component and the requirement was a genuine multi-turn conversational assistant scoped
to the authenticated student's own records. **The keyword engine was not branched around — it was
replaced.** This section is a map of the change, not a duplicate of the design rationale, which is in
`docs/api/chat-assistant.md`.

### Backend — new, 9 files (the feature)

| File | Lines | Why |
|---|---|---|
| `chat/controller/ChatController.java` | 160 | `POST /api/v1/chat` and `GET /api/v1/chat`; both take `@AuthenticationPrincipal`, so identity is the token's and nothing else |
| `chat/service/ChatService.java` | 246 | Turn-taking: history trimming (`MAX_HISTORY_TURNS = 30`), the tool-round bound (`MAX_TOOL_ROUNDS = 5`), the system instruction, and the **binding of the principal into the only callback the provider can call** |
| `chat/service/ChatRole.java` | 24 | The two roles a client may send. No `SYSTEM`: a client cannot write the assistant's instruction |
| `chat/tool/ChatTool.java` | 262 | The nine declared tools, each a read, with the declaration the model sees |
| `chat/tool/ChatToolExecutor.java` | 656 | Resolves a declaration to the service that already owns the calculation, so no business logic is duplicated |
| `chat/dto/ChatRequest.java` | 48 | `message` + `history`. **No user-id field** |
| `chat/dto/ChatTurnRequest.java` | 43 | One prior turn |
| `chat/dto/ChatResponse.java` | 44 | `reply`, `model`, `toolsUsed` |
| `chat/dto/ChatAvailabilityResponse.java` | 43 | `available` + `model`/`reason` (`NON_NULL` when absent) |

### Backend — new, 5 files (the provider adapter)

| File | Lines | Why |
|---|---|---|
| `common/ai/ChatCompletionPort.java` | 155 | The port. `ToolInvoker` is `(String, Map) -> Map` — **no identity parameter**, which is the ownership guarantee, asserted reflectively |
| `common/ai/GeminiChatCompletionPort.java` | 380 | The Gemini adapter and its tool loop. **Every provider fault throws** — see D-26 |
| `common/ai/NoopChatCompletionPort.java` | 39 | Installed with no credential; refuses visibly rather than answering |
| `common/ai/ChatConfig.java` | 40 | Wires the real or no-op adapter from the credential's presence |
| `common/ai/AiProviderUnavailableException.java` | 33 | The failure type, distinct from `AiSuggestionPort`'s `Optional` discipline |

### Backend — new, 3 files (tests) and 1 modified

| File | Lines | What it establishes |
|---|---|---|
| `chat/ChatApiIT.java` | 679 | **19 tests, the full production path** against a wire-level provider stub: the section-13 chain, the figure read from the student's own rows, ownership for two students, an inert `userId`, no token → no data → no provider call, an admin refused, a spent quota → honest `503`, input bounds |
| `chat/StubGeminiProvider.java` | 334 | The stub. Not a mock of this application: a real HTTP server speaking the provider's protocol |
| `chat/service/ChatServiceTest.java` | 494 | **21 tests**: the nine declarations, the invoker's signature, conversation order, history trimming, no identity in the request, provider failure → `AI_UNAVAILABLE` and never a reply |
| `common/ai/AiProperties.java` | modified | D-21's token-cap value (`1024 → 8192`); no change for the chat work |

### Frontend — 3 files

| File | Lines | Why |
|---|---|---|
| `core/models/chat.model.ts` | 52 | `ChatResponse`, `ChatTurn`, `ChatAvailability` |
| `core/services/chatbot.service.ts` | 254 | The client. **Replaced wholesale** — the keyword engine is gone. Sends no user id; a failure becomes a **type-distinct** failed turn, not a reply (D-31) |
| `core/services/chatbot.service.spec.ts` | 187 | **11 tests**: the reply rendered unchanged, a `503` flagged with a retry, a blank reply treated as a failure, the transcript's shape, no user id, availability that costs no provider call |

`shared/components/chatbot-widget/chatbot-widget.component.ts` was **modified surgically**, not
replaced: the brief requires the existing UI layout and visual language to be preserved. The message
bubble gained a retry affordance for a failed turn, the composer became a `textarea` (Enter sends,
Shift+Enter adds a line), and the typing indicator and availability notice were wired to the service.
No unrelated styling was touched.

### Documents

| File | Change |
|---|---|
| `docs/api/chat-assistant.md` | **new** — the feature's full contract: endpoints, data boundary, tools, multi-turn, provider failure, ownership, security properties |
| `docs/api/API_INVENTORY.md`, `docs/api/FRONTEND_API_GUIDE.md` | the two chat operations added; the false "`userId` → `400`" claim corrected (D-29) |

**No database change.** No table, column, procedure or migration was added: the conversation is not
stored, and every figure the assistant quotes is read through a service the student's screens already
call. **No `pom.xml` change** — the Gemini SDK was already a dependency for module 12's suggestion port.

### Regression evidence for this change set

| Check | Result |
|---|---|
| `mvn -o test -Dtest=ChatApiIT` | **19/19 PASS** (full chain, stub provider, real MySQL) |
| `mvn -o test -Dtest=ChatServiceTest` | **21/21 PASS** |
| `mvn -o test -Dtest=OpenApiContractIT` | **19/19 PASS** — the docs/contract lockstep holds at 78 operations on 57 paths |
| `npx ng test --watch=false` | **12 files, 60 tests, 0 failures** — run this pass; see the note on the project-level build error below |
| `npx tsc --noEmit -p tsconfig.app.json` | `EXIT=0` |

**The frontend suite was run despite the pre-existing build error.** `login.component.spec.ts` (the
concurrent UI work's file, unmodified by this pass) does not compile against the working-tree
`login.component.ts`. To obtain a real result rather than a claimed one, that single spec was moved aside
for the duration of the run — **restored byte-identical afterwards, verified by SHA-256** — the suite was
executed, and the file was put back. The project-level `TS2339` errors are unchanged and are still
recorded as Correction 7 / MANUAL-BUILD-01.

**One defect found and fixed while testing**, and it is recorded because it is the kind that hides: the
frontend `failure()` path funnelled the error text through the `ChatResponse` shape, where non-blank text
was indistinguishable from a model reply — so a failed turn rendered as an ordinary assistant bubble with
no retry. It is now a distinct `FailedTurn` type, so the panel cannot mistake one for the other. This is
exactly the confusion the brief's section 15 forbids, and it was caught by the Vitest assertion that the
failure carries `failed: true` (D-31).

---

## 5. Regression evidence

| Check | Command | Result |
|---|---|---|
| Backend build + tests | `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home mvn -B test` | **BUILD SUCCESS — 1091 tests, 0 failures, 0 errors, 0 skipped** (56 report files) |
| Backend build + tests, re-run after the D-21 provider changes | `cd backend && ./mvnw -o clean test` | **BUILD SUCCESS — 1091 tests, 0 failures, 0 errors, 0 skipped** — identical to the line above, so the token-cap change altered nothing the suite covers |
| Backend build + tests, re-run after the chatbot rebuild | `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home mvn -B test` | **BUILD SUCCESS — 1131 tests, 0 failures, 0 errors, 0 skipped** in 4:42, across **58** surefire report files (the baseline's 56 + the two new chat classes). The +40 is exactly the chat work (19 `ChatApiIT` + 21 `ChatServiceTest`); every pre-existing test still passes |
| Frontend unit tests | `cd frontend && npx ng test --watch=false` | **12 files, 60 tests, 0 failures** — run this pass. The count changed from the earlier 13 / 62 because the chatbot spec was rewritten (`chatbot.service.spec.ts` now holds **11** chat tests, replacing the keyword-engine tests). Re-running without intervention fails to compile: the concurrent, uncommitted `login.component.ts` edit removed `fillDemoStudent()` / `fillDemoAdmin()`, which the untouched `login.component.spec.ts` still calls. That one spec was set aside for the run and **restored byte-identical** (SHA-256 verified) — not caused by, and not fixed by, this pass — see causal report Correction 7 / MANUAL-BUILD-01 |
| Frontend production build | `cd frontend && npx ng build` | `Application bundle generation complete.` — `BUILD_EXIT=0` |
| Frontend type-check | `cd frontend && npx tsc --noEmit -p tsconfig.app.json` | No output — `EXIT=0` |
| Database routine drift | Repository SQL vs live `information_schema` | **Zero drift** (Causal QA Correction 6) |
| Browser states | load / empty / error / authorisation / mobile | All distinct; an error is not rendered as an empty state |

**The frontend count changed from 13 / 62 to 12 / 60, and the chatbot work is the only cause.** Thirteen
spec files exist on disk; twelve ran, the difference being the `login.component.spec.ts` set aside for the
run. The two-test fall is the chatbot spec, which was rewritten for real conversations (11 tests) in place
of the keyword-engine tests — the banner component and the budget-ranking rewrite added no test failures
and removed none. There is **no lint step** in this project; `tsc --noEmit` is the static gate that
substitutes for it.

**What these numbers do not establish.** The backend suite drives the chat chain against a wire-level
provider stub, which proves the application's own half of it — identity, the tool read, the request the
provider is sent, the reply rendered — but by construction it cannot prove that Gemini wrote the reply.
The feature **may not be called an AI chatbot** on this evidence alone (brief §15). That is
MANUAL-AI-CHAT-01, and it is PENDING HUMAN TEST.

**A note on how the 1131 was arrived at, because the first count was wrong.** Summing test counts straight
out of `backend/target/surefire-reports/` gave **1133**, not 1131. The difference was two surefire reports
belonging to throwaway probe classes this session created and deleted (`ZProbeSdkTest`, `ZProbePathTest`).
The probes were gone from `src/test/java`; their **reports were not**, and a report directory is exactly
the kind of persisted artefact that outlives the code (as with the stale `ChatService` class files earlier
in this pass). Maven's own printed summary — computed from that run's actual executions, not from what
happened to be lying in the folder — said 1131, and it was right. The four stale files were deleted and the
tally recomputed over the remaining reports: **1131, 0 failures, across 58 files**, now matching Maven
exactly. The figure quoted everywhere in the deliverables is **1131**; no count was taken from a directory
listing until the listing had been shown to contain only the run's own reports.

The two Sass `@import` deprecation warnings come from `src/styles.scss` / `src/styles/styles.scss` and
are **pre-existing**; they were not introduced by this work and were left alone.

---

## 6. What was deliberately NOT changed

Each of these was found, judged, and left — with the reason recorded rather than left implicit.

| Not changed | Reason |
|---|---|
| **UI design / layout / styling** | The user is editing the frontend UI concurrently. No global restyle, no reformatting of unrelated frontend files, no broad auto-formatting. Both changed frontend files are surgical edits. |
| **`docs/testing/qa/` figures carried from earlier reports** | Every number in the new report was re-measured. Where an old figure disagreed with the system, the system won and the correction is recorded (§G of the causal report). |
| **The conservative duplicate detector** | A blank-category CSV row escapes duplicate detection. `ImportDuplicateDetector`'s javadoc says this is deliberate — it "errs toward not flagging" so no student data is lost. Changing it is a **product decision**, not a QA fix. |
| **`recurring_rules.description` plaintext** | Would require firing the update trigger and risking spurious notifications. Documented, not fixed (§4 above). |
| **Recent-activity UX** | The backend contract is correct and tested. The screen ships a "log an action" form where the guide describes a client-fired call on view/edit — a client usability gap in a screen being concurrently edited. Recorded as `REMAINING MANUAL REVIEW`. |
| **Hardcoded `'2026-09'` month in `home-feed`** | Recorded with its exact location. Out of scope for a causal-QA pass and in a concurrently edited file. |
| **The `db/` files** | No drift exists to fix (Correction 6). Nothing was hand-edited in the database, and no migration was invented. |
| **Test assertions** | None was weakened. No test was modified to make a relationship pass. |

---

## 7. Test data side effects

Every functional mutation went through the product's own APIs. **No `INSERT`, `UPDATE` or `DELETE` SQL
was executed against the live database for QA.** All live SQL was read-only (`SELECT`, `SHOW COLUMNS`,
`information_schema`).

**Created and reversed during this pass:** probe transactions 122–127 (deleted via
`DELETE /transactions/{id}` → 204); announcements 31–36 (withdrawn via
`PATCH /admin/announcements/{id}` → 200).

**Left behind (no delete route exists, or the row is evidence):** announcement rows 3–36 (all withdrawn,
inert, but with no DELETE route); transactions 117–120 (the OB-018 evidence) and 99/121 (the REL-21/22
scheduler occurrences); import batches 3–14; QA-created accounts, `category_rules` rows, bookmarks and
recent-activity rows; `transaction_history` audit rows; default category 15 (retired only — default
categories have no delete route, verified: `400` on the admin route, `403`/`404` on the student one).

**The token-reuse QA account** (`qa.tokenreuse.<epoch>@student.campuscoin.edu`) was registered for the
Section H reset-token test. Its password was changed by that test and is therefore **not** the seed
value — it is a QA account and must not be mistaken for a demo account. (The value is not recorded here;
the demo values are in `docs/CREDENTIALS.md`.) It is distinct from all three demo accounts, which are
unaffected.

**Alex's password is at the documented seed value** (documented password → `200`, alternatives →
`401`). `docs/CREDENTIALS.md` is accurate and needed no edit.

**Cleanup options** are in §J of the causal report. The destructive option (`docker compose down -v`,
then reload) is **not** taken without the user's explicit go-ahead; the recommended option is to leave
the inert footprint and label it.

### Added by the provider-reconnection pass (D-21) — and the one row that was NOT inert

The rest of the footprint is invisible to a student. **`insights` row 3 was not**, and it is worth
recording precisely because the pass's own evidence tooling created it.

Holding `STUB narrative: …` for user 2 / `2026-09` marked `generated_by='AI'`, it was written while
the AI adapter was pointed at a local stub to verify the application's wiring after the real
provider's quota was exhausted. `GET /insights` served that text to Alex, so the word `STUB` appeared
on screen. **Regenerating alone could not fix it:** `sp_generate_monthly_insight`'s guard is
`summary_text = IF(insights.generated_by = 'AI', insights.summary_text, v_summary)` — once marked
`AI`, the text survives every later run.

**Resolved.** Row 3 was deleted (after confirming no `bookmarks` row referenced it) and the insight
rebuilt through `POST /insights/generate`, so the current row 28 holds the product's own rule-based
text with figures matching `GET /reports`. Only the removal of our own residue was SQL; every visible
value was produced by the application. A database-wide check confirms zero `STUB` rows and zero
insights carrying a `model_name`. Full before/after evidence in §I of the causal report.

Also added, both inert (no delete route exists; both only affect suggestions for one exact phrase):
`category_rules` row 38 (`provider probe coffee beans`, auto-written by the categorisation learning
flow) and row 39 (`qa rel-18 delete-propagation probe`, written by the UC-08 override path). Probe
transaction 128 was created and deleted through the API (`204`, read-back `404`).

---

## 8. Operational notes for whoever runs this next

1. **Restart the dev server if the announcement banner is missing.** The `ng serve` on `:4200` was
   serving a **stale build cache** — the `home-feed` module it served contained zero references to the
   banner. The source is correct and the production build contains it. `cd frontend && npx ng serve
   --port 4200`.
2. **The frontend must be served on `:4200`.** Backend CORS allowlists only `http://localhost:4200`;
   any other port makes login fail with **403** while the API itself is healthy. This cost time during
   this pass and is not a product defect.
3. **Start the backend before the frontend.** The frontend proxy targets `http://localhost:8080`.
4. **Build and run the backend with JDK 21** (`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`).
   JDK 21 is not this machine's default.
5. **`@Scheduled` requires a 6-field cron** (with seconds). A 5-field value fails at startup.
6. **Angular 21 uses Vitest:** `npx ng test --watch=false`. The `--browsers=ChromeHeadless` flag fails.
   As of this writing that command **fails to compile** — see note 8 — so treat `npx ng build` plus
   `npx tsc --noEmit -p tsconfig.app.json` as the working gates until it is repaired.
8. **The frontend unit-test project is currently broken by an uncommitted edit, not by this pass.** The
   concurrent `login.component.ts` change removed `fillDemoStudent()` / `fillDemoAdmin()`, which the
   untouched `login.component.spec.ts` still calls at lines 54 and 60, so `npx ng test` stops on two
   `TS2339` errors. Restore the helpers or delete the two cases. The application compiles and builds
   cleanly; only `tsconfig.spec.json` fails. Recorded as Correction 7 / D-23 and deliberately **not**
   patched here, because both files belong to UI work in flight.
7. **The password-reset development sink** is `backend/target/password-reset-dev.log` and is **on** in the
   `dev` profile (`RESET_SINK_ENABLED: true`). It contains live reset links — it is a development
   convenience, not something to ship, and it is the reason the token's single-use behaviour could be
   verified automatically.
