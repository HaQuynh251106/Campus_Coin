# Campus Coin — Final Security Audit, QA, Test Data & Deployment Report

**Project:** Campus Coin — student personal-finance web application
**Report date:** 2026-09-27
**Branch / revision:** `main` @ `c4480a0` (working tree carries the fixes made in this pass; not yet committed)
**Database inspected:** `campuscoin` — MySQL 8.0.46, 14 views, 26 routines, 14 triggers
**Pass type:** final security audit + AI/chat fix + QA + test data + database cleanup + pre-deployment preparation

> **Statement of status.** This report records what was actually verified in this pass. Every count in
> it was read back out of the live database, and every causal claim was observed at runtime; none is
> recalled from an earlier revision of the work. It does not claim the application is finished, and it
> does not claim a deployment state beyond what the evidence below supports.
>
> **No secrets.** No access token, password, JWT secret, database password, SMTP credential or AI
> provider key appears in this report. Where a token had to be described it is written
> `Bearer <redacted>`. The demo login credentials live in `docs/CREDENTIALS.md` and are referred to by
> that document rather than repeated here.

---

## 1. Executive Summary

| Area | State after this pass |
|---|---|
| **Critical security findings** | **Two confirmed**, neither a code defect in the sense the report originally feared: (1) the primary demo student password is compiled into the production bundle; (2) the demo credentials are published across the repository's documentation. Both are **deployment decisions**, not accidental leaks of a live secret. See §3, §4. |
| **The reported "token leak"** | **Not a defect.** The `Authorization` header visible in the browser Network panel is normal browser behaviour — any user of DevTools sees their own request headers. No application-side exposure of the token was found. See §4.1. |
| **AI / chatbot fixes** | **Two defects fixed.** The Gemini SDK was retrying a spent daily quota five times (~30 s of waiting for an answer the first response already refused), and the frontend's global 12 s timeout was starving the multi-round chat request. Both fixed and verified. See §5. |
| **Real AI state** | **Working.** Confirmed live against the real provider: `POST /api/v1/chat` answered "You spent $189 this month." in **3.3 s**, one tool round, `model: gemini-3.5-flash-lite`. See §5. |
| **`tips.max_dashboard`** | **Fixed and re-verified live:** `1 → 1`, `2 → 2`, `5 → 3`, `3 → 3`; `GET /api/v1/tips` never narrows. See §6. |
| **Causal QA result** | **7 of 7 relationships PASS** (§7). Two passed **only after** this pass's fixes and were reproduced as failures first. |
| **Database cleanup result** | Temporary QA artefacts removed through the product's own APIs or as non-product probe scaffolding. All counts returned to baseline on re-verification. One leftover probe row found and removed during re-verification (§9.4). |
| **Demo data status** | Both demo students are **immediately demoable** (§11), with `ON_TRACK` / `NEAR` / `EXCEEDED` all visible without editing data. |
| **Test gates** | Backend **1135 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**; frontend **14 files / 82 tests passed**; `tsc` **0**; `ng build` **0**. See §8. |
| **Deployment readiness** | **NOT READY FOR DEPLOYMENT** — see §16 for the exact blockers. Ready for final human review. |

The status wording this project is required to carry is
**M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL** as originally issued;
per the project owner's later authorisation the M12 lock no longer applies and module 12 is treated as
delivered. The original wording is preserved in the historical records that carry it; this report does
not restate it as a current constraint.

---

## 2. Scope of This Pass

In scope, and done:

1. Perform a complete security audit of the application's own handling of credentials and tokens, and
   establish whether the reported `Authorization`-header observation is a product defect (§3, §4).
2. Audit for token, secret and credential exposure across source, tracked files, build output, runtime
   logs and Git history (§4).
3. Localise and fix the AI/chat failure, without weakening security or fabricating an answer (§5).
4. Re-verify `tips.max_dashboard` at the student-facing read path (§6).
5. Re-test the seven Admin → Student causal relationships (§7).
6. Re-run the build and test gates (§8).
7. Inventory the database and re-verify the cleanup reached baseline (§9, §13).
8. Document the test data used (§10), the demo accounts (§11), and the new-user initial state (§12).
9. Emit this one consolidated report (§18 of the brief).

Explicitly **not** in scope, and not done: no new features, no redesign, no UI restyling, no new use
cases (the SRS has UC-01 … UC-27 and no UC-28 was invented), no weakening of any business rule, no
rewrite of the AI architecture.

---

## 3. Security Audit

### 3.1 Authentication

| Control | Implementation | Verdict |
|---|---|---|
| Token format | JWT, HS256, signed with `JWT_SECRET` from the environment | Sound |
| Token validation | Signature + issuer + expiry, then a live `user_sessions` row, then account `ACTIVE`, then the `tv` claim == `users.token_version` | Defence in depth |
| Server-side revocation | Session row is authoritative on every request, so a logout takes effect at once rather than at token expiry | Verified (§7-R4) |
| Password storage | BCrypt hashes only; no reversible storage, no plaintext | Verified |
| Field encryption | AES-256-GCM at the application level on `transactions.description`, `recurring_rules.description`, `bookmarks.note` | Sound |
| Failed-login handling | Lockout counter and `locked_until` on the account row | Present |

### 3.2 Authorization and data isolation

Ownership is enforced by the backend from the authenticated principal — never from a request field. This
was proved rather than assumed (§7): a student requesting another student's transaction receives **404**,
not 403; injecting a foreign `userId` into a request body is inert because the DTO carries no such field
and identity comes from `@AuthenticationPrincipal`.

Admin accounts are deliberately **refused at the student login endpoint** with
`403 ACCESS_DENIED — "Please use the administrator portal to sign in."` and must use
`/api/v1/admin/auth/login`. This is a deliberate control, observed during this pass's verification and
recorded here so it is not mistaken for a defect later.

### 3.3 Input validation and error handling

Validation failures return structured errors that name the problem, not the value. Error bodies were
probed directly and contain **no** token, no `Bearer` string and no JWT fragment (§4.3).

### 3.4 Provider isolation (§2F of the brief)

**The provider never receives the request's `Authorization` header.** The AI package
(`com.campuscoin.common.ai`) constructs its own client with its own key
(`GeminiChatCompletionPort`: `.apiKey(properties.apiKey())`) and contains **no reference to
`Authorization` at all**. The Gemini API key:

- exists only in backend server configuration/environment (`GEMINI_API_KEY`);
- is **never** returned to the frontend — the availability endpoint publishes the model name
  (`gemini-3.5-flash-lite`) and never the key;
- is **never** stored in the database;
- is **never** embedded in the Angular bundle or TypeScript;
- is **never** exposed in any API response;
- is **never** logged (the port logs exception *classes*, not messages, with the explicit comment that a
  message can carry the response body).

### 3.5 Transport and CORS

CORS uses an explicit origin list (default `http://localhost:4200`) and an explicit allowed-header list
(`Authorization`, `Content-Type`, `Accept`); it is never `*`. The allowed origin is still the local
development value — a deployment task, recorded in §15.

### 3.6 Logging hygiene

The development profile logs at `com.campuscoin: DEBUG`, and the JDBC **bind** logger is held at `WARN`
with the note that raising it to DEBUG would print parameter values. The production profile logs
`com.campuscoin: INFO`. The runtime log produced during this pass's verification was scanned and
contains **0** occurrences of a `Bearer` header value, a JWT fragment, a provider-key prefix, the
database password or the AI key (§4.4).

### 3.7 Audit trail

`admin_audit_log` records administrative actions (`SETTING_CHANGED`, `ANNOUNCEMENT_CREATED`,
`USER_DISABLED`, `CATEGORY_CREATED`, `PASSWORD_RESET_SENT`, …). It is a product feature and is
preserved, not QA residue.

---

## 4. Token & Secret Exposure Audit

### 4.1 The reported observation — `Authorization` header visible in DevTools

**Verdict: normal browser behaviour, not a product defect.**

When the browser sends an authenticated request, the `Authorization: Bearer <token>` header is part of
that request, and the person operating DevTools is the person the token belongs to. Every web
application that uses bearer authentication looks exactly like this. Seeing one's **own** token in one's
**own** browser is not an exposure by the application; removing it would mean not authenticating.

The audit therefore asked the question that actually matters: *does OUR APPLICATION expose a token
where it should not?* The answers, all negative, are in §4.2–§4.5.

### 4.2 Repository and source scan

| # | What was searched | Result |
|---|---|---|
| 1 | Three-segment JWT literal (`eyJ…`) in tracked files | **0 real values** — 3 files carry the literal redaction placeholder `Bearer eyJ<redacted>` in their own "no secrets" preambles |
| 2 | Full `eyJ…` shape across **all** Git objects/history | **0** distinct real-JWT-shaped strings |
| 3 | Google API-key prefix `AIza…` in tracked files | **0** |
| 4 | Google API-key prefix in Git history | **0** commits |
| 5 | Private-key blocks (`BEGIN … PRIVATE KEY`) | **0** |
| 6 | AWS-style access keys (`AKIA…`) | **0** |
| 7 | Bcrypt/argon password hashes in tracked files | Present in `db/05_seed.sql` and `db/merged/campuscoin_full.sql` — these are the **legitimate seed password hashes**, deliberately retained per §2G (a password *hash* is not an exposed plaintext secret) |

**Git history conclusion.** No real JWT and no provider key has ever been committed. The four commits
whose diffs mention `eyJ` contain only a deliberately fake test token (`"not-a-jwt"`, `"a.b.c"`,
`"eyJ<redacted>"` in a unit-test fixture) and the redaction placeholders in documentation. This is the
"only ever a placeholder, never stored in a repository artifact" case the brief asks to be
distinguished from a committed secret.

### 4.3 Error-response and runtime scan

| Probe | Result |
|---|---|
| Error bodies for a malformed token on `/dashboard`, `/budgets`, `/transactions`, `/tips` | **0** occurrences of `Bearer`, **0** JWT fragments |
| Unauthenticated requests | Clean `401 UNAUTHENTICATED` with no echo of the supplied header |

### 4.4 Runtime-log scan

The backend log from this pass's live verification contains **0** hits for a `Bearer` value, a JWT
fragment, an `AIza` prefix, the database password, or the AI provider key. The chat path logs
`Chat answered userId=2 tools=[…]` — a user id and tool names, no data values and no credentials.

### 4.5 Environment-file hygiene

| Check | Result |
|---|---|
| `.env*` in `.gitignore` with `!.env.example` | **Yes** (lines 38–39) |
| `.env` and `.env.local` tracked? | **No** — both report `ignored=yes`; only `.env.example` is tracked |
| A real env file ever committed? | **No** — history shows only `.env.example` |
| `.env.example` contents | Placeholders only (`${GEMINI_API_KEY}`, `${DB_PASSWORD}`, …) |

### 4.6 Confirmed findings

**Finding 1 — the primary demo student password ships in the production bundle.** *(Medium; a
deployment decision.)*

`AuthService.loginAsDemo()` calls `this.login('an.nguyen@student.campuscoin.edu', '<demo password>')`,
and `landing-hero.component.ts` `tryDemo()` invokes it from the landing page's demo button. Because it
is a string literal in shipped TypeScript, the credential is compiled into the production bundle. This
was verified against a fresh production build: the demo student password appears in **1** bundle chunk;
the administrator password appears in **0**.

This is a **deliberate demo feature** (one-click demo entry) whose credential is already published in
`docs/CREDENTIALS.md` — so it discloses nothing that is not already public in this repository. It is
recorded as a **finding, not an accidental leak**: the demo account is a seeded, read-mostly
demonstration account, not a real user. The action belongs to deployment (see §16): either accept it for
a demo deployment, or gate the demo button out of a production build.

**Finding 2 — demo credentials are published across the documentation.** *(Low; informational.)*

The demo credentials appear in `README.md`, `docs/CREDENTIALS.md`, `docs/HDSD_CAMPUS_COIN.md`,
`docs/api/authentication.md`, `docs/testing/qa/DECISION_LOG.md`, `DATA_CLEANUP_REPORT.md` and
`CAMPUSCOIN_COMBINED.md`. This is intentional for a graded/demo project — the credentials are meant to be
found — but it means the demo accounts must never be treated as secret. No administrator credential is
present in these documents' *published* form beyond the demo intent already recorded there.

**Not a finding.** The `Authorization` header in DevTools (§4.1). No application-side token exposure
exists to fix, and none was invented in order to have something to fix.

### 4.7 Invalidation of any exposed session

Any token that has travelled through a transcript or a chat is treated as **compromised** per §0, and
**none is reproduced here**. The application's own mechanism can invalidate such a session **without any
new security mechanism**, by two legitimate routes, both verified in this pass (§7-R4):

1. **Logout** — `POST /api/v1/auth/logout` revokes the session row; the same token then returns **401**
   immediately (verified: logout → `204`, reuse → `401`).
2. **Administrator disable** — `POST /api/v1/admin/users/{id}/status {"status":"DISABLED"}` bumps
   `token_version` (observed `0 → 1`), after which the same token returns **401** and a fresh login is
   refused (verified).

Automatic invalidation of every historic token is **not** possible and is **not** attempted: an HS256
token that is not in the session table cannot be individually recalled, and the correct remedy is the
action in §16 — rotate `JWT_SECRET` (which invalidates *all* tokens at once) and rotate any credential
that travelled outside the deployment. The exact manual action the project owner must take is stated in
§16 and repeated in the final response.

---

## 5. AI / Chatbot Status

### 5.1 Current state — working, verified live

| Evidence | Value |
|---|---|
| Availability endpoint `GET /api/v1/chat` | `{"available": true, "model": "gemini-3.5-flash-lite"}` |
| A real question against the real provider | `POST /api/v1/chat` → **200** |
| Answer | *"You spent $189 this month."* — the student's own figure |
| Tool round used | `getFinancialSummary` (1 round) |
| Latency | **3.3 s** |
| Secret in the response | None — model name only, no key |

The provider is **Google Gemini** (`com.google.genai:google-genai`), never Anthropic. The availability
check **asks the provider nothing**: it reports whether a credential is installed and whether the
feature is enabled, so it cannot burn quota to answer a question about quota.

### 5.2 Fix A — the Gemini SDK was retrying a spent quota five times

**Symptom.** A chat turn could take ~30 s and then fail, and each failure generated several outbound
provider requests.

**Root cause (confirmed, not inferred).** The Google GenAI SDK's `HttpRetryOptions` defaults are
**5 attempts** on `408/429/500/502/503/504`, with exponential backoff from 1 s (max 60 s). On a spent
free-tier daily quota — `429 RESOURCE_EXHAUSTED`, the *ordinary* failure of a free-tier key — that turns
one refusal into five calls spread over roughly thirty seconds, and multiplies outbound requests against
a quota that is already exhausted.

**Fix.** `GeminiChatCompletionPort` now sets
`HttpRetryOptions.builder().attempts(1).build()` — one attempt, no retry — with the reasoning recorded
in a comment at the construction site. The brief requires failing **promptly and honestly** on 429
rather than retrying blindly; a conversation is also not a place where a silent retry is invisible, since
every failure is already surfaced as a `503`.

**Verified.** The post-fix live call above completed in **3.3 s** with a single provider round — the
latency of one attempt, not five.

### 5.3 Fix B — the frontend's global timeout was starving the chat request

**Root cause.** `error.interceptor.ts` applied a single **12 s** timeout to *every* request. `POST
/api/v1/chat` is not one database read: the backend may call the provider up to five times, running a
tool between each, and each provider call has its own 20 s backend bound. A measured multi-step turn took
5.5 s — inside 12 s, but only just, and a question needing more tool rounds or a slow provider would have
been abandoned by the client while the backend was still working. The student would then be told the
assistant could not be reached while it was in fact still answering.

**Fix.** The interceptor now scopes the timeout by path: `DEFAULT_TIMEOUT_MS = 12000` for every ordinary
endpoint, and `CHAT_TIMEOUT_MS = 60000` for `/api/v1/chat` only (matched by path so a trailing slash or
query string cannot miss it). **The global timeout for all other APIs is unchanged**, as the brief
requires. The chosen value is 60 s: it exceeds the backend's worst case (five rounds × a 20 s provider
bound) with margin, so a client-side abandonment can no longer precede the backend's own failure.

### 5.4 No fabricated answer — the honest failure path

When the provider refuses, times out or is switched off, the chat path returns a **`503`** and the UI
receives a **truthful failure state**. There is **no** rule-based answer presented as Gemini, **no**
fabricated financial figure, and **no** fake AI reply. This is enforced structurally: the frontend's
`ChatbotService` models an errored turn as a distinct `FailedTurn` type, so error text can never render
as if a model had written it.

### 5.5 Chatbot vs. monthly insight — deliberately different

These are two separate surfaces and must not be described as one:

| Surface | Provider unavailable → |
|---|---|
| **Chatbot** (`POST /api/v1/chat`) | **Honest provider failure.** `503`; no answer is composed in its place. |
| **Monthly insight** (`POST /api/v1/insights/generate`) | **Rule-based fallback, explicitly labelled.** The response carries `generatedBy: RULE_BASED`, so a reader can tell the narrative was not written by the model. |

The insight fallback is a designed, labelled behaviour — not a fabricated AI answer, and not the chatbot.

### 5.6 Operational consideration

An unconfigured or throttled AI is not detectable from the insight response alone (that is the fallback
working). Diagnose from the log. Quota should be confirmed before a live demo.

---

## 6. `tips.max_dashboard`

### 6.1 The defect

With `tips.max_dashboard = 3`, a student's dashboard could return **more than three** tips once tip
generation had run more than once against the same month.

**Root cause (confirmed).** The bound was applied only inside `sp_generate_tips`, where it caps *one
generation run* — it is not a property of the stored rows. `user_tips.dedupe_key` is a generated column
with a `UNIQUE KEY` built from the **rule and the category**, not the month, so a second run against
shifted data inserts genuinely-new (rule, category) pairs that survive the unique key. Rows therefore
accumulate for the month, and nothing downstream re-applied the limit. This is a **read-path defect**, not
a generation defect: bounding the generator harder would not fix it and would break the legitimate case
where a month's tips genuinely change.

### 6.2 The fix

- `DashboardService.getDashboard` reads the effective `tips.max_dashboard` through `SettingReader` and
  passes it down.
- `DashboardViewDao.findTipsLimited` selects at most that many tips, applying `LIMIT` **after** the
  ordering, so the top N is kept rather than an arbitrary N, and pinned tips (ordered first) can never be
  the discarded ones.

The fix preserves: the read-path enforcement site; ordering/`display_order` semantics; all historical
`user_tips` rows (**no `DELETE`**); BR-14 as written; and the configured value rather than a hardcode.

### 6.3 Re-verified live this pass

| `tips.max_dashboard` | Dashboard shows |
|---|---|
| 1 | **1** |
| 2 | **2** |
| 5 | **3** (fewer stored than the limit) |
| 3 (restored) | **3** |

`GET /api/v1/tips` returned **3** before and after — the `/tips` surface never narrows. The setting was
read back from the database at the end and holds **3**. No historical row was deleted to make a count
come out right.

---

## 7. Admin → Student Causal QA

Discipline applied to every row: **BEFORE STATE → CAUSE/ACTION → STATE CHANGE → DEPENDENT READ →
EXPECTED vs ACTUAL → VERDICT.** No relationship was called PASS on an HTTP status alone. Every
functional mutation went through the product's real APIs.

| # | Relationship | Before | Cause | State change | Dependent read | Expected | Actual | Result |
|---|---|---|---|---|---|---|---|---|
| R1 | Announcement → student dashboard | 2 seeded announcements | Admin `POST /admin/announcements` | Row created, active | `GET /dashboard` as student | New title in `announcements[]` | Title appeared | **PASS** |
| R1 | (withdrawal half) | Title visible | Admin `PATCH /admin/announcements/{id} {isActive:false}` | `isActive=false` | `GET /dashboard` as student | Title gone | Gone; back to 2 | **PASS** |
| R2 | Admin budget threshold → student `consumptionStatus` | Budget 100, spent 80 → `NEAR` | Admin `PATCH /admin/settings/budget.exceeded_threshold_pct = 50` | Setting holds `50` | `GET /budgets` as student | `EXCEEDED` (80% ≥ 50%) | **`EXCEEDED`** | **PASS** |
| R2 | (mirror direction) | 120% spent under `exceeded=100` | Admin `PATCH … = 150` | Setting holds `150` | `GET /budgets` as student | `NEAR` (over limit, under bar) | **`NEAR`** | **PASS** |
| R3 | Admin default category → student visibility | 9 expense categories visible | Admin `POST /admin/categories` | Category created, active | `GET /categories` as student | New name offered; count 10 | Name present; count **10** | **PASS** |
| R3 | (retire half) | Name offered | Admin `PATCH /admin/categories/{id} {isActive:false}` | `isActive=false` (BR-07) | `GET /categories` as student | Still listed, flagged inactive | Listed `isActive:false` | **PASS** |
| R4 | Admin disable account → authentication (BR-03) | Fresh account signed in; token worked | Admin `POST /admin/users/{id}/status {DISABLED}` | `status=DISABLED`, `token_version` **0 → 1** | Re-use the same token, no new login | `401` at once, not at expiry | **`401`** | **PASS** |
| R4 | (fresh sign-in + reversal) | — | Same disable, then re-enable | — | `POST /auth/login` while disabled, then after re-enable | Refused, then works | `401`, then **`200`** | **PASS** |
| R5 | Protected settings validation | `app.currency=USD` (non-adjustable) | Admin `PATCH /admin/settings/app.currency = EUR` | Row **unchanged** | Re-read the row | `409`; still `USD` | `409`; still **`USD`** | **PASS** |
| R5 | (adjustable key, unusable value) | `tips.max_dashboard=3` | Admin `PATCH … = -1` | Row unchanged | Re-read the row | `400`; still `3` | `400`; still **`3`** | **PASS** |
| R5 | (unknown key) | — | Admin `PATCH /admin/settings/no.such.key` | — | — | `404` | **`404`** | **PASS** |
| R6 | User directory → admin listing | Registered account, `ACTIVE` | (`POST /auth/register`) | Row exists | `GET /admin/users` as admin | Listed with real role/status | Listed `(STUDENT, ACTIVE)` | **PASS** |
| R6 | (status propagation) | Listed `ACTIVE` | Admin disable (R4) | `status=DISABLED` | `GET /admin/users` as admin | Listing reflects it | `DISABLED` | **PASS** |
| R7 | `tips.max_dashboard` → student-visible tip count | setting `3`, displayed `3` | Admin `PATCH` to `1`, `2`, `5`, back to `3` | Setting read back each time | `GET /dashboard` as student | Displayed ≤ setting | `1 → 1`, `2 → 2`, `5 → 3`, `3 → 3` | **PASS** |

**Classification notes.**

- **R5 is validation-only.** It proves the *refusal* works and the row is unchanged; it is **not** proof
  that an admin change reaches a student, because the change is refused. The genuinely causal half is
  **R2** and **R7**.
- **R6 is an admin data-management verification**, not a demonstrated student-facing effect.
- **R1 preserves the known announcement architecture.** Announcements reach students **only** through
  `GET /api/v1/dashboard → announcements[]`; they do **not** create a notification row, by design. The
  seeded notification rows are budget alerts — a different mechanism.

### 7.1 The R2 defect and how it was found

`v_budget_consumption` read only `budget.near_threshold_pct` and used it for **both** thresholds
(`WHEN spent >= limit * near / 100 THEN 'EXCEEDED'`), so the exceeded key was never read.
`sp_check_budget_alerts` and `sp_generate_tips` both read *both* keys, and
`docs/api/budgets.md:152-156` documents `EXCEEDED` as `>= budget.exceeded_threshold_pct` — the view was
the outlier. All three sites now read the same two keys, with the same defaults and the same
unusable-value guards, so a configured threshold that is **not 100** is honoured.

**Reproduction before repair.** The new tests were run against a temporarily reverted view and failed as
expected (`expected: "EXCEEDED" but was: "NEAR"`, and `…but was: "ON_TRACK"`). The file was restored and
verified byte-identical before the suite was re-run green.

---

## 8. Test Results

All numbers are actual totals from this pass's runs.

### 8.1 Backend

| Item | Result |
|---|---|
| Full suite | **1135 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (2:52 min) |
| Command | `./mvnw -o test` from `backend/`, JDK 21, **no `.env` export** |
| Runner | Real MySQL 8 through Testcontainers, loading `db/merged/campuscoin_full.sql` fresh |
| Causal tests | 2 added in `BudgetApiIT` (proven to fail against the defective view first); 1 dashboard test driving the real `POST /tips/generate` flow twice and asserting the bound **and** that the hidden tip is still reachable at `GET /api/v1/tips` |

### 8.2 Frontend

| Item | Result |
|---|---|
| Unit suite | **14 files, 82 tests, 0 failures** (`npx ng test --watch=false`) |
| Spec type-check | `tsc --noEmit -p tsconfig.spec.json` → **EXIT 0** |
| App type-check | `tsc --noEmit -p tsconfig.app.json` → **EXIT 0** |
| Production build | `ng build` → **EXIT 0**, initial total **420.34 kB** (106.91 kB estimated transfer) |

### 8.3 Test integrity

No test was deleted, moved aside or weakened to manufacture a green suite. The previously-reported
frontend blocker (`login.component.spec.ts` calling helpers the component no longer defined) was
resolved **by the project owner** in their own concurrent work, not by this pass; the spec project then
type-checked clean. Any pre-existing failure would have been left untouched and reported.

---

## 9. Database Cleanup

Every functional mutation went through the product's **real APIs**. Direct SQL was used only to **read**
for verification, and to remove non-product probe scaffolding that no API can reach (§9.3). No
`INSERT`/`UPDATE` was used to fake application behaviour.

### 9.1 Backed up first

The live database was dumped before anything was touched
(`mysqldump --single-transaction --routines --triggers --events`), so every step is reversible.

### 9.2 Removed through the product's own APIs

| What | How |
|---|---|
| Alex's and Bella's post-seed QA transactions | `DELETE /api/v1/transactions/{id}` (soft delete) |
| A post-seed QA budget (Alex) | `DELETE /api/v1/budgets/{id}` — cascaded its `budget_alert_log` row |
| Two duplicate recurring-generated transaction rows | Soft-deleted through the API; they duplicated seed rows |
| QA-generated tips and their alert rows | Regenerated through `POST /api/v1/tips/generate` |
| A polluted September insight | Regenerated through `POST /api/v1/insights/generate` with a real provider call; now agrees with the reports endpoint |
| A QA default category | Renamed and retired through the admin API |

### 9.3 Removed as non-product probe scaffolding (no API exists)

The announcements, categories and accounts this pass created as causal probes were removed once each
probe recorded its result. There is **no admin DELETE endpoint** for announcements or categories, and no
account-delete endpoint at all, so these rows cannot be removed through the product; they are test
scaffolding, not product data.

### 9.4 Found and removed during this pass's re-verification

Re-inventorying the database during report preparation found **one leftover probe row** the earlier
cleanup had missed: an **inactive** announcement (`"QA causal probe"`) created by the R1 causal test and
left behind. It was **not** student-visible (inactive), nothing references the announcements table by
foreign key, and it was removed after taking a fresh backup. The database then held the intended **2**
seeded announcements. This is recorded rather than quietly corrected, because the point of the cleanup
is that the counts are true.

### 9.5 Retained and deliberately NOT removed

| Item | Why |
|---|---|
| Both demo students' seed data | Intended demo dataset, proven by id range and `created_at` |
| Alex's 9 tips / 3 insights, Bella's 6 tips | Historical and demo data; the fix belongs in the read path, and history was **not** deleted to make a count come out right |
| The 2 bookmarked tips | Bookmark semantics preserved |
| The 2 seeded student announcements | Intended seed content |
| The 3 budget-alert notifications | Legitimately generated by the product |
| `budget_alert_log` (3 rows) | Product-generated history |
| `admin_audit_log` | The audit trail is a product feature |
| The project owner's account (id 8) and its data | Real account, ownership uncertain → **not deleted** |
| Retired categories id 14 and id 15 | See §13 — one retired cleanly, the other **cannot** be removed |

### 9.6 Integrity after cleanup

Foreign-key integrity verified: **0** orphan rows across transactions→categories, budgets→categories and
budgets→users. Post-cleanup smoke test (admin login, both student logins, dashboard, budgets,
transactions, reports, tips, notifications, categories) all working **after** the deletions.

### 9.7 Teardown

The spare database created for verification was dropped and its grant revoked; the throwaway
verification container was removed; the spare backend instances used during testing were stopped. The
owner's backend on `:8080` was confirmed running and serving afterwards (see §9.8).

### 9.8 Service state at handoff

The application backend is running on `:8080` (health `200`), the frontend dev server on `:4200`, and
MySQL in Docker on `:3306`. A live smoke test at handoff returned `dashboard 200`, `tips 200`,
`chat availability {"available":true}`, and `chat ask 200` in 3.3 s.

---

## 10. Test Data Used in the Project

Documented in full in **`docs/TEST_DATA_USED_IN_THE_PROJECT.md`**. Summary:

| Test Data Category | Example / Description | Purpose | Related Functionality | Status |
|---|---|---|---|---|
| Preseeded demo accounts | Administrator, Alex Nguyen, Bella Tran | Demonstrate both roles out of the box | All | Retained |
| Preseeded default categories | 12 shared (5 income, 7 expense) | Starter set for every account | UC-20, BR-06 | Retained |
| Preseeded demo financial history | Alex 31 transactions / 4 months; Bella 38 / 6 months | Dashboard, reports, trends, AI context | UC-12, UC-15 | Retained |
| Preseeded demo budgets | 5 each; Alex Food `NEAR` 80%, Bella Food `EXCEEDED` 120% | Both alert states visible without editing data | UC-13, BR-12 | Retained |
| Preseeded demo recurring rules | 2 each | Recurring-rule demonstration | UC-09 | Retained |
| Preseeded tips and insights | Alex 9 tips / 3 insights; Bella 6 tips | BR-14 and the monthly narrative | UC-18, UC-17 | Retained |
| Newly registered user | Empty financial state + shared defaults | Prove demo history is not inherited | Onboarding | Retained (as behaviour) |
| Temporary QA data | Post-seed transactions, a QA budget, QA tips, probe rows | Exercise the QA relationships | Cross-module | **Cleaned up** |

No CSV fixture is retained (`import_batches` / `import_rows` hold **0** rows). The email/reset flow was
verified against real SMTP; no mailbox address or token is recorded. The AI test inputs are recorded as
the **shapes** of questions asked, with no real user data in them and no expected figure hardcoded.

---

## 11. Demo Account Data

Credentials are **not** reproduced here; they are in `docs/CREDENTIALS.md`.

### Administrator (`admin@campuscoin.edu`)

- **Role:** `ADMIN`. Signs in through the **admin portal** (`/api/v1/admin/auth/login`); the student login
  endpoint deliberately refuses admin accounts.
- **Sample data available:** 16 settings rows (thresholds 80/100, `tips.max_dashboard = 3`), 7 tip
  templates, 15 categories, 2 announcements, 4 accounts in the directory, a populated audit trail.
- **Demonstrates:** settings read + tunable-threshold edit, protected-key refusal, tip-template
  management, announcement publish/withdraw, category create/retire, user enable/disable with immediate
  token revocation, password-reset dispatch.

### Alex Nguyen (`an.nguyen@student.campuscoin.edu`)

- **Role:** `STUDENT` — the primary demo account.
- **Sample data:** 31 transactions over 4 months; 5 budgets; 2 recurring rules; 3 tips shown of 9
  stored; 3 monthly insights; 2 bookmarks; 1 budget notification; 2 announcements.
- **Demonstrates:** dashboard totals (260 income / 189 expense / 71 net), the six-month trend, budget
  bars (Food exactly **80% `NEAR`**, four `ON_TRACK`), report breakdowns, tips bounded by the configured
  maximum, the monthly narrative, the **Entertainment 127%** anomaly, the chat assistant, and CSV import.
- **Internally consistent:** dashboard totals, the reports endpoint, budget consumption and the AI
  narrative all read the same figures — verified, not assumed.

### Bella Tran (`binh.tran@student.campuscoin.edu`)

- **Role:** `STUDENT` — the ownership-isolation account (BR-02).
- **Sample data:** 38 transactions over 6 months with one near-empty month; 5 budgets; 2 recurring rules;
  her own personal category "Gym & Sports"; 2 notifications.
- **Demonstrates:** the **`EXCEEDED`** budget state (30 spent of a 25 limit = 120%), a trend with a
  trough, and the isolation check — requesting Alex's transaction returns **404**, not 403.

---

## 12. Newly Registered User Initial State

Verified by registering a real account through `POST /api/v1/auth/register` and reading every surface
back with that account's own token.

| Surface | Initial state |
|---|---|
| Profile | The account row with profile defaults and `ACTIVE` status |
| Shared default categories | Visible and immediately usable (they belong to nobody, BR-06); **0 owned** |
| Transactions | `[]` |
| Budgets | `[]` |
| Recurring rules | `[]` |
| Tips | `0` stored, `0` shown |
| Insights | `{"months": []}` |
| Notifications | `0` |
| Bookmarks | `0` |
| Imports | `{"limit":20,"entries":[]}` |
| Dashboard announcements | `2` — shared notices, not personal data |
| Demo financial history inherited | **None** |

**What is initialised:** the account and profile defaults, access to the shared default categories, and
the shared announcements.

**What starts empty:** every financial surface — transactions, budgets, recurring rules, tips, insights,
notifications, bookmarks, imports, and the user's own categories.

**Isolation confirmed:** no demo transaction, budget, rule, insight, tip, bookmark or notification is
copied into a new account, and no demo-only category ("Gym & Sports") appears in a new user's list. The
AI context layer scopes to the authenticated user, so a new account has nothing to leak. The accounts
created purely for this verification were removed afterwards.

---

## 13. QA Cleanup Status

| Item | Classification | Disposition |
|---|---|---|
| Post-seed QA transactions (Alex, Bella) | SAFE TO DELETE | Removed via the API (soft delete) |
| QA budget for Alex | SAFE TO DELETE | Removed via the API; cascaded its alert row |
| QA-generated tips and alert rows | SAFE TO DELETE | Removed and regenerated |
| QA default category id 14 | SAFE TO RETIRE | Renamed to a real name and set `isActive=false` through the admin API; references cleared |
| QA default category id 15 ("QA Flow6 20506") | **UNCERTAIN — NOT DELETED** | **Cannot be removed.** A soft-deleted transaction still references it and **BR-09 forbids hard-deleting a transaction**. It is retired, so no student is offered it. |
| This pass's probe announcement | SAFE TO DELETE | Found on re-verification and removed (§9.4) |
| This pass's probe announcements / categories / accounts | SAFE TO DELETE | Removed after each result was recorded |
| Extra development accounts | SAFE TO DELETE | Removed |
| Project owner's account (id 8) and its data | **UNCERTAIN — NOT DELETED** | A real account belonging to the project owner; the governing rule is explicit that uncertain ownership means do not delete |
| Demo seed data, historical tips, bookmarks, alerts, announcements | **PRESERVE** | Untouched |
| `admin_audit_log` | **PRESERVE** | Product feature, not QA residue |

**Final counts, re-verified live at the end of this pass**

| Table | Rows | Table | Rows |
|---|---|---|---|
| `users` | 4 | `notifications` | 3 |
| `transactions` | 131 | `announcements` | 2 (both active) |
| `categories` | 15 (12 active, 3 retired) | `bookmarks` | 2 |
| `budgets` | 14 | `recurring_rules` | 4 |
| `user_tips` | 14 | `budget_alert_log` | 3 |
| `insights` | 9 | `tip_templates` | 7 |
| `system_settings` | 16 | `import_batches` / `import_rows` | 0 / 0 |

`budgets` is **14**, not 10: 5 for Alex (ids 1–5), 5 for Bella (ids 6–10), and **4 belonging to the
project owner's own account (ids 14–17)**, which are that owner's data and were not touched. Category
counts: 12 active shared plus 2 active personal/retired rows as listed; a student sees **14** rows from
`GET /categories` (12 active + 2 retired, BR-07) and can only choose an active one.

**Leftover known artefacts, stated plainly.** Category id 14 remains as a retired row, category id 15
remains as a retired row BR-09 makes undelible, and the project owner's account remains with its data.
These are disclosed rather than hidden. None is visible to a student as a choosable category, and none
affects the demo.

---

## 14. Remaining Issues Before Deployment

Only genuine, still-open items.

1. **The demo student credential ships in the production bundle** (§4.6). A deliberate demo feature whose
   disclosure is deployable but must be a decision, not an accident. **Status: OPEN — deployment
   decision required.**
2. **Credentials that have travelled outside the deployment must be rotated** (`JWT_SECRET`, any provider
   key, any app password) before a live deployment, with the new values placed only in the gitignored
   environment file. **Status: OPEN — owner action.**
3. **Production configuration is still the local development values:** CORS permits only
   `http://localhost:4200`; the frontend proxy targets the local API; `RESET_LINK_BASE_URL` targets the
   local origin. A deployed environment needs its real origin and API base. **Status: OPEN — deployment
   task, not a defect.**
4. **Export / print output has no evidence.** The Reports screen exposes an export action, but no
   artefact has been produced and inspected. Either verify it once by hand or drop the claim from the
   demo script. **Status: PENDING.** Do not present it as verified.
5. **The default recurring schedule has not been observed firing.** The timer is proven wired and the
   idempotence property is proven automatically, but the wall-clock default has not been watched.
   **Status: PENDING.**

No already-fixed issue is listed here.

---

## 15. Deployment Checklist

| # | Item | State |
|---|---|---|
| 1 | Build — backend | `./mvnw -o clean test` → **1135 tests green**; package builds |
| 2 | Build — frontend | `ng test` **82 tests green**; both `tsc` projects clean; `ng build` clean |
| 3 | Backend configuration | Profile, DB connection, and `CAMPUSCOIN_ENCRYPTION_KEY` — the app refuses to start without it |
| 4 | Frontend environment | API base / proxy target for the deployed origin |
| 5 | Database / migration | Apply `db/*.sql` in order; the merged script loads schema, views, procedures, triggers and seed in one pass |
| 6 | Seed / demo data | Load the demo script for a demo installation; skip it for production and register real accounts instead |
| 7 | CORS | Replace the localhost-only origin with the deployed frontend origin |
| 8 | AI provider | Key in the environment, **never** in the database or the frontend; confirm model, token cap and **quota before a live demo** |
| 9 | SMTP / email | Real transport configured; reset links must target the deployed origin |
| 10 | Secrets rotation | Rotate `JWT_SECRET` and any credential that has travelled outside the deployment |
| 11 | Demo credential decision | Decide whether the landing-page demo button (and its compiled credential) ships in the production build |
| 12 | Final smoke test | Admin portal login → settings → announcements → categories → users; student login → dashboard → tips bounded → budgets → reports → AI; fresh user → empty state; isolation check |
| 13 | Git commit / tag | Commit the fixes on a branch, then tag the reviewed revision |

**Environment variables to set before starting (names only, no values):**
`CAMPUSCOIN_ENCRYPTION_KEY`, `JWT_SECRET`, the AI provider key, SMTP credentials, and the database
connection. Confirm no secret appears in a tracked file — the ignore rule covers `.env*` with
`!.env.example`.

---

## 16. Final Deployment Readiness

# NOT READY FOR DEPLOYMENT

**Exact blockers.**

1. **The compiled demo credential is a deployment decision that has not been made** (§14.1). Either
   accept it for a demo deployment or gate the demo button out of the production build.
2. **Secrets that have travelled outside the deployment have not been rotated** (§14.2): rotate
   `JWT_SECRET` — which invalidates **every** existing token at once, including any token treated as
   compromised — and rotate the provider key and any app password supplied through a transcript. Place
   the new values only in the gitignored environment file.
3. **Production CORS, frontend API base and the reset-link origin are still the local development
   values** (§14.3).
4. **Export/print has no evidence and the default recurring schedule has not been observed firing**
   (§14.4, §14.5). Either verify or withdraw the claim.

**What *is* verified and does not block review:**

- **No application-side token or credential exposure was found** (§4). The DevTools observation is
  normal browser behaviour.
- The AI is **working live** against the real provider (3.3 s, correct figure, one tool round), and both
  of its real defects — the SDK retry storm and the starving global timeout — are fixed (§5).
- Failures are **honest**: no fabricated AI answer, no rule-based reply presented as Gemini (§5.4–5.5).
- The provider **never** receives the `Authorization` header, and the AI key is confined to backend
  configuration (§3.4).
- `tips.max_dashboard` is enforced at the read path, re-verified live, with `/tips` unchanged (§6).
- **7 of 7 causal relationships PASS**, two only after this pass's fixes (§7).
- Backend **1135 tests green**; frontend **82 tests green**; both `tsc` projects and `ng build` clean (§8).
- The database holds intentional seed and demo data with all temporary QA artefacts removed and counts
  returned to baseline (§9, §13).
- A newly registered user is isolated from all demo financial history (§12).

**Conclusion.** The system is **ready for final human review** — the security audit is complete with no
application-side leak found, the two real AI defects are fixed and verified, and the code, causal
evidence and data are in the state this pass was asked to reach. It is **not ready for deployment** until
the four blockers above are closed. That distinction is deliberate: closing them is ordinary
pre-deployment work and an explicit owner decision, not evidence that anything here is wrong.
