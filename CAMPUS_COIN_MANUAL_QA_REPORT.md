# CAMPUS COIN — INDEPENDENT MANUAL QA / API ACCEPTANCE REPORT

**Project:** Campus Coin — student personal-finance web application
**Scope of this run:** M1–M11 (module 12 is locked and was **not** treated as a defect source)
**Report date:** 2026-09-26
**Test window:** 2026-09-26 05:35 → 05:53 (+07:00)
**Tester:** Independent QA (external to the implementation)
**Deliverable:** this file only — `CAMPUS_COIN_MANUAL_QA_REPORT.md`

> **Statement the report is required to carry:** **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL**
>
> This run does **not** claim the project is bug-free. Section 6 lists defects that were observed at
> runtime with reproduction evidence, and section 7 lists journeys that could not be completed.

---

## 1. Test environment

| Item | Observed value |
|---|---|
| Host | macOS (Darwin 27.0.0), host timezone `+07:00` |
| Backend | `java -jar target/campus-coin-backend-1.0.0-SNAPSHOT.jar`, PID 18366, started Fri Sep 25 23:10:38 2026 |
| Spring Boot | 3.4.3 (from `backend/pom.xml` parent) |
| Java | OpenJDK 21.0.11 LTS (Temurin) |
| Backend base URL | `http://localhost:8080` |
| OpenAPI document | `GET /api-docs` → 200 (OpenAPI 3.1). **61 operations across 43 paths** |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Database | MySQL 8.0.46 in container `campuscoin-mysql`, published on `:3306` |
| DB access used for verification | read-only `SELECT` / `SHOW` / `DESCRIBE` only. **No mutation, no config change, no restart during this run.** |
| Frontend | Angular dev server on `http://localhost:4200` (PID 39748) |
| Browser automation | Playwright (Chromium), viewport 1440×900 desktop and 390×844 mobile |
| API harness | `/tmp/qa_campuscoin/*.py` — raw HTTP calls; every request and response written to `evidence.jsonl` (231 records) |
| Testing technique | real user journeys (§5). Not a flat endpoint list |

**Deliberately not done, per the run's constraints:** no code was changed; no schema, seed or
configuration was modified; no database row was inserted, updated or deleted by hand; nothing was
"fixed" to allow a test to continue.

### 1.1 Previously suspected bug — instruction followed

The previous tester's note about **"Campus Cafe / category id 13"** was treated **only as a lead**,
never as a confirmed defect. See §8 (OBS-01) for the resolution.

---

## 2. Source documents inspected

| Document | Role in this run |
|---|---|
| `CampusCoin_DacTa_UseCase.docx` | Use cases UC-01 … UC-27, and the authoritative UAT matrix (UAT-01 … UAT-17) used in §12 |
| `CampusCoin End-to-End Web Solutions_SRS.pdf` | SRS; FR/NFR traceability referenced by the UAT matrix |
| `docs/api/API_INVENTORY.md` | Claimed endpoint inventory, cross-checked against the live OpenAPI document |
| `docs/api/FRONTEND_API_GUIDE.md` | Client integration contract (§2.4 sign-out, §2.5 password reset) |
| `docs/api/authentication.md`, `administration.md`, `categories.md`, `transactions.md`, `budgets.md`, `recurring.md`, `tips.md`, `bookmarks.md`, `notifications.md`, `reports.md`, `dashboard.md`, `profile.md` | Per-module API contracts |
| `docs/testing/manual/MODULE_01…MODULE_11_MANUAL_TEST.md` | The repository's own manual test procedures, including §3.4 of module 5 (scheduler) |
| `docs/SECURITY.md` | Encryption design, key management, residual exposure (§6 item 7, §12.6, §12.7) |
| `docs/HANDOFF_M1_M11.md` | Module status, known assumptions OB-001/OB-012/OB-013 |
| `docs/DB_DESIGN.md`, `docs/ERD.md`, `db/01_schema.sql` … `db/06_demo.sql` | Schema, views, procedures, triggers, seed |
| `docs/CREDENTIALS.md` | Seed accounts used to sign in |

Runtime was always preferred over documentation. **Swagger `Example Value` was never used as
evidence** (§9).

---

## 3. Runtime / API inventory verified

- Live OpenAPI document (`/api-docs`) reports **61 operations on 43 paths** — `GET` 24, `POST` 21,
  `PATCH` 11, `DELETE` 5. This matches `docs/api/API_INVENTORY.md`.
- **All 61 operations were exercised by real HTTP requests** during this run (see §13).
- `/v3/api-docs` returns **404 by design** (the document is served at `/api-docs`).
- Authorization discriminator established and used throughout:
  - a request with **no token** on `/api/v1/**` → **401** (the security filter runs before routing);
  - a request with a **valid STUDENT token** → **404 means the route does not exist**, **401 means an
    authentication problem**.
  - Control pair measured in this run: authenticated `GET /api/v1/profile/me` → **200**;
    authenticated `GET /api/v1/this-route-does-not-exist` → **404**.

**Running-build note (environment fact, not a defect).** The running backend process (PID 18366) was
started 2026-09-25 23:10:38 from `target/campus-coin-backend-1.0.0-SNAPSHOT.jar`, and that jar file
**no longer exists on disk** (the process still holds it open). Meanwhile `backend/target/classes`
contains newer package directories — `anomaly/ categorisation/ common/ai/ common/jdbc/ forecast/
imports/ insight/ recent/` — and `SecurityConfig.java` is modified in the working tree. The runtime
under test is therefore **an earlier artifact than the working tree**; every finding below describes
what the running build does.

---

## 4. Test data created

All identifiers below are **real ids returned by the running server**, captured during this run.

| Entity | Id | How it was created |
|---|---|---|
| USER_A | **5** | `POST /api/v1/auth/register` — `qa.journey.c1d8488c@example.com` |
| USER_B | **6** | `POST /api/v1/auth/register` — `qa.student.b.dd39f463@example.com` |
| USER_C (reset probe) | **7** | `POST /api/v1/auth/register` — `qa.reset.probe.bb51bae8@example.com` |
| Personal category "Campus Cafe QA" | **13** | `POST /api/v1/categories` (OWNER = USER_A) |
| Personal category "QA Retired Cat" | **15** | `POST /api/v1/categories` with `isActive:false` |
| Default category "QA Default Category" | **14** | `POST /api/v1/admin/categories` (ADMIN) |
| Transaction (Food) | **33** | `POST /api/v1/transactions`, `description` "First QA lunch" |
| Transaction (Campus Cafe QA) | **34** | `POST /api/v1/transactions` |
| Transactions (probe) | **39, 40** | `POST /api/v1/transactions` (BR-05 / BR-08 probes) |
| Transaction (Scholarship income) | **41** | `POST /api/v1/transactions`, OWNER = USER_B |
| Budget (USER_B, Food, 2026-09) | **6** | `POST /api/v1/budgets`, limit 30.00 |
| Budget (probe, deleted after use) | **7** | `POST /api/v1/budgets`; removed with `DELETE` |
| Recurring rule | **3** | `POST /api/v1/recurring-rules`, OWNER = USER_A |
| Bookmark | **1** | `POST /api/v1/bookmarks` (deleted by the same journey) |
| Notification | **3** | generated by the budget engine, OWNER = USER_B |
| Tip (pinned) / Tip (dismissed) | **14 / 16** | `POST /api/v1/tips/generate` |
| Announcement | **3** | `POST /api/v1/admin/announcements` |
| Tip template | **8** | `POST /api/v1/admin/tip-templates` |

**No password, SMTP secret, AI API key, JWT secret or database password is reproduced in this
report.** Access tokens appear only in the masked form `Bearer eyJ...<redacted>`.

---

## 5. Journey results (Journey 1 – Journey 10)

Legend: **PASS** = the expected behaviour was observed at runtime with evidence · **FAIL** = a
documented expectation was not met · **BLOCKED** = could not be completed without violating this
run's constraints · **NOT IMPLEMENTED** = the surface is absent from the running build by design or
by module lock.

| # | Journey | Cases | Result |
|---|---|---|---|
| 1 | Onboarding: register → sign in → profile → first transaction → dashboard | TC-01 … TC-09 | **PASS** |
| 2 | Categories + transactions + soft delete + restore | TC-10 … TC-19c | **PASS** |
| 3 | Budget + alert thresholds (80 % / 100 %) | TC-20 … TC-40 | **PASS** |
| 4 | Recurring rules — API surface | TC-41 … TC-48 | **PASS** |
| 4 | Recurring rules — scheduler posting | M5-24 / M5-25 class | **BLOCKED** |
| 5 | Tips + bookmarks (pin / dismiss / generate again) | TC-47 … TC-61 | **PASS** |
| 6 | Reports + spending series + export | TC-56 … TC-61 | **PASS** (export satisfied by `window.print()`) |
| 7 | Session persistence across sign-out / sign-in | TC-61 … TC-70 | **PASS** |
| 8 | Forgot password + reset-link reuse + session revocation | TC-71 … TC-80, J8b | **PASS** |
| 9 | Administration portal + UAT-13 disable / UAT-14 default category | TC-81 … TC-101, J9c | **PASS** |
| 10 | Security / ownership isolation A vs B, anonymous, malformed | J10 (23 probes) | **PASS** |
| — | Browser legs: UAT-16 (display preferences), UAT-17 (mobile) | — | **UAT-16 FAIL · UAT-17 PASS** |

### Journey 1 — Onboarding
Register (201) → sign in (200) → `GET /profile/me` shows the untouched defaults → `PATCH /profile/me`
updates name/year/allowance/goal → `PATCH /profile/me/preferences` stores `DARK` / `LARGE` → the first
transaction posts (201, id 33) → dashboard reflects it immediately. **PASS.**

### Journey 2 — Categories and transactions
Create personal category (201, id 13) → it appears in the list → `GET /categories/13` → create a
transaction against it (201, id 34) → read → patch → `DELETE` returns **204** → `GET` the deleted row
returns **404** while the row survives in the database with `is_deleted = 1` → it is absent from the
default list and present with `includeDeleted=true` → `POST /transactions/34/restore` returns 200 →
the row is readable again. **PASS.**

### Journey 3 — Budget and alerts (UAT-07)
Budget 30.00 on Food for 2026-09, then spend in steps and watch the notifications:

| Step | Consumption | Notifications | Result |
|---|---|---|---|
| start | 0 / 30 | 0 | — |
| spend 24.00 | 24 / 30 (80 %) | **1** — `BUDGET_NEAR`, "You have used 80.00% of your Food budget (24.00 of 30.00)" | first "near" fires |
| spend 1.00 more | 25 / 30 (83 %) | **still 1** (id 2 only) | **no repeat** |
| (probe row removed) | 24 / 30 | — | consumption follows the row down |
| spend 7.00 more | 31 / 30 (103.33 %) | **2** — adds `BUDGET_EXCEEDED`, "You have spent 31.00 of 30.00 (103.33%) on Food" | "exceeded" fires once |
| spend 5.00 more | 36 / 30 | **still 2** (ids 3, 2) | **no repeat** |

Exactly the UAT-07 script: one "near", one "exceeded", neither repeated as spending continues.
Read-only DB confirms one row per `(budget_id, threshold_type)`: `(1,NEAR) 80.00`, `(6,NEAR) 80.00`,
`(6,EXCEEDED) 103.33`. **PASS.**

### Journey 4 — Recurring rules
**API surface — PASS.** Create with `nextRunDate` in the past (201, id 3) → read → list → pause (200)
→ resume (200) → a client-supplied `type` is ignored (200, the category still owns the type) → a rule
with `amount: 0` is refused (400).

**Scheduler posting — BLOCKED.** See §7.1.

### Journey 5 — Tips and bookmarks (UAT-12)
`POST /tips/generate` → three tips, ids 14/15/16 → pin id 14 → the list returns it **first** and only
it carries `state: "PINNED"` → dismiss id 16 → it disappears from the list → `POST /tips/generate`
again → **id 16 stays gone** and id 14 stays pinned first. Bookmark lifecycle in the same journey:
create (201, id 1) → list → patch the note (200) → delete (204) → list is empty. **PASS.**

### Journey 6 — Reports (UAT-09, UAT-10, UAT-11)
Month report for 2026-09 returns the totals, `expenseByCategory`, `incomeByCategory` and a
**six-point** `sixMonthTrend` — six points even for a month with no transactions (`2026-01`: all six
present, the five empty months `0.0`). Spending series returns both `DAILY` and `WEEKLY`. Income
filtering: after USER_B recorded a Scholarship income row (id 41), the report's `incomeByCategory`
contains **only** `Scholarship` (100 %) while `expenseByCategory` contains only `Food` — UAT-10's
intent is satisfied by the endpoint's own structure. **PASS.**

### Journey 7 — Session persistence
`POST /auth/logout` → **204**; the same token afterwards → **401**; a second logout on the dead token
→ **401**; sign in again → the profile, categories, transactions and dashboard are all intact. **PASS.**

### Journey 8 — Forgot password
Request a link for a real address and for an unknown address → **byte-identical** 200 response
(`"If the email exists, a reset link has been sent."`) — anti-enumeration holds. Read the token from
the development sink → `verify` (200) → `complete` (200) → the old password is refused (401) → the new
password works (200) → re-verify the used token → **400 `INVALID_RESET_TOKEN`** (UAT-03) → re-complete
with it → **400** → request a fresh link → 200. On a dedicated probe account, **two** live sessions
were both invalidated (**401**) by a completed reset. **PASS.**

### Journey 9 — Administration (UAT-13, UAT-14)
Admin signs in through `/admin/auth/login` (200). A student at the admin portal → **403**. An admin at
the student portal → **403**. A STUDENT is refused (**403**) on all seven admin GETs; an ADMIN is
refused (**403**) on all nine student endpoints; ADMIN gets 200 on every admin surface. Admin creates
a default category (201, id 14) and the student immediately sees it with `isDefault: true`; the
student's `PATCH` and `DELETE` on it are both **404** (UAT-14 **PASS**). Announcement created (201),
visible on the student dashboard, then withdrawn. All six remaining admin operations exercised
(§13). UAT-13: admin disables a signed-in student (200) → the student's **live token** is refused
(**401**) → the student cannot sign in (**401 `ACCOUNT_DISABLED`**) → the admin cannot disable itself
(**409**) → re-enable → the student can sign in again. **PASS.**

### Journey 10 — Security and ownership (UAT-02)
23 probes, all as expected: USER_B reading, patching or deleting USER_A's category, transaction and
recurring rule → **404** on every one (ten probes); USER_A doing the same to USER_B's budget → **404**
(three probes); USER_B creating a transaction or a budget against USER_A's `categoryId` → **404**;
USER_B's own lists never contain USER_A's rows; anonymous requests → **401**; a malformed token, an
empty bearer and a tampered signature → **401**. **PASS.**

### Browser legs
- **UAT-17 — mobile dashboard: PASS.** At 390×844 the document scroll width equals the viewport,
  horizontal overflow is `false`, zero elements are wider than the viewport, the sidebar is collapsed
  into the bottom navigation, and the dashboard actions are all reachable. Screenshot
  `uat17-mobile-390.png`.
- **UAT-16 — display preferences: FAIL.** See ISSUE-002 and ISSUE-003 in §15.

---

## 6. FAILURES

| ID | Severity | Journey | UC / UAT | Endpoint / Surface | Problem | Evidence |
|---|---|---|---|---|---|---|
| ISSUE-001 | **HIGH** | 7 (browser leg) | UC-05 / UAT-13 (session revocation) | UI "Sign Out" → `POST /api/v1/auth/logout` | Clicking **Sign Out** in the sidebar performs **no HTTP request at all**. The token and user record stay in `localStorage`, the server still accepts the token, and the protected dashboard still renders. The session is not ended. | §15 ISSUE-001 |
| ISSUE-002 | **MEDIUM** | UAT-16 | UC-27 B2/B3 · NFR Accessibility | `ThemeService.applyFontSize()` ↔ `styles/_tokens.scss` | The font-size class the service writes (`text-size-large`) has **no CSS rule**; the stylesheet defines `text-size-lg`. The chosen font size therefore never applies. The `classList.remove()` call also lists the wrong names, so size classes accumulate. | §15 ISSUE-002 |
| ISSUE-003 | **MEDIUM** | UAT-16 | UC-27 B4 | `PATCH /api/v1/profile/me/preferences` | The display preference is **device-local, not account-scoped**. UC-27 B4 requires "Hệ thống lưu lựa chọn theo tài khoản" (the system saves the choice per account). The only method that calls the preferences endpoint, `AuthService.updatePreferences()`, has **zero call sites**. | §15 ISSUE-003 |

### Severity justification

- **ISSUE-001 — HIGH.** Severity is assigned on impact, not on how hard it is to fix.
  1. *Security.* The user's intent ("end my session") is not carried out. On a shared or public
     machine — the normal case for a Campus Coin user — the next person to open the browser is still
     authenticated as the previous user, and the server will serve that user's financial data. This is
     the same threat UAT-13 exists to defend against, reached by a route the admin cannot revoke except
     by disabling the account.
  2. *Correctness of the documented contract.* `docs/api/FRONTEND_API_GUIDE.md` §2.4 states the
     client must "Call the server **first**, then clear local state; the reverse leaves an open session
     behind." The implementation does the reverse *and* never calls the server.
  3. *Why not CRITICAL.* No data corruption, no privilege escalation, and no remote exploit: the flaw
     requires physical access to an already-authenticated browser. The account is not compromised from
     outside.
  4. *Why not MEDIUM.* MEDIUM would fit a display or convenience defect. This one leaves an
     authenticated session alive against the user's explicit instruction.

- **ISSUE-002 — MEDIUM.** The feature is user-visible and part of an NFR (accessibility), but it
  degrades rather than breaks: dark mode works, and text still renders at the default size. No data is
  lost and nothing becomes inaccessible (the default is the middle size, so no user is locked out of
  content). It fails the accessibility promise, which is why it is not LOW.

- **ISSUE-003 — MEDIUM.** The stated requirement is a per-account stored preference; the build stores
  it per browser. Consequences: the preference is lost on cache clear, does not follow the student to
  a lab machine, and — the part that is visible to a tester — **leaks between accounts**: signing in as
  a second student on the same browser renders the *first* student's theme and font size (proven in
  §15 ISSUE-003). Same weight as ISSUE-002.

---

## 7. BLOCKED / NOT IMPLEMENTED

### 7.1 BLOCKED — Journey 4, the recurring scheduler's posting half
The API surface of UC-09 is complete and passes. What could not be observed is the **job that turns a
due rule into a transaction**, and the reason is environmental, not a defect:

- `recurring_occurrences` is empty and `transactions.source` is `MANUAL` for **all 40** rows — nothing
  has ever been posted by the scheduler in this database.
- The daily job runs at `0 5 0 * * *` in `Asia/Ho_Chi_Minh` (00:05). Rule **3**, created during this
  run at 05:38 with `next_run_date = 2026-09-01`, became due **after** that day's 00:05 run had
  already passed, so no run could have picked it up yet. Its `next_run_date` is still `2026-09-01`,
  confirming it has not been posted.
- `docs/testing/manual/MODULE_05_MANUAL_TEST.md` §3.4 documents exactly two supported ways to make a
  rule post without waiting for 00:05: (1) `PATCH` the rule's `nextRunDate` backwards and wait for the
  next scheduled run, or (2) re-time `RECURRING_SCHEDULER_CRON` and **restart the backend**. Option 2
  is not available: the jar the running process was started from has been deleted from disk, so the
  build under test cannot be restarted without changing what is being tested. Option 1 still requires
  waiting for the next 00:05 run.
- Making the rule post by hand would additionally violate the run's constraint "Đừng sửa database bằng
  tay để 'ép' scheduler chạy trừ khi tài liệu chính thức yêu cầu."

**To be verified when the scheduler can be observed:** M5-24 (catch-up over missed periods) and M5-25
(idempotency — one transaction per period, `recurring_occurrences` unique on `(rule_id, period_key)`).
The schema for both is present (the table exists and is empty; `next_run_date` is in the past for rule
3), and the API-side validation of `status`, `frequency` and `nextRunDate` all behaved.

### 7.2 NOT IMPLEMENTED (by design or by module lock)

Every path below was probed with a **valid STUDENT token** and returned **404**, against a control
pair that returned 200 (`/api/v1/profile/me`) and 404 (`/api/v1/this-route-does-not-exist`). The same
paths return **401** with no token, confirming the security filter ran first (so the 404 is not an
authentication artefact).

| Surface | Probed path | Authenticated | Anonymous | UAT affected |
|---|---|---|---|---|
| CSV import (UC-11) | `GET /api/v1/imports` | **404** | 401 | **UAT-15** |
| CSV import preview (UC-11) | `GET /api/v1/imports/preview` | **404** | 401 | UAT-15 |
| AI category suggestion (UC-08) | `GET /api/v1/categorisation/suggest` | **404** | 401 | **UAT-05** |
| Learned overrides (UC-08) | `GET /api/v1/categorisation/rules` | **404** | 401 | UAT-05 |
| Insights (UC-17) | `GET /api/v1/insights` | **404** | 401 | — |
| Anomaly detection (UC-24) | `GET /api/v1/anomalies` | **404** | 401 | — |
| Forecast (UC-25) | `GET /api/v1/forecast` | **404** | 401 | — |
| Recent activity (UC-26) | `GET /api/v1/recent-activity` | **404** | 401 | — |
| AI chat surface | `GET /api/v1/ai/chat` | **404** | 401 | — |
| Export endpoint (UC-16) | `GET /api/v1/export` | **404** | 401 | UAT-11 (see below) |

So **UAT-05** and **UAT-15** are recorded as **BLOCKED / NOT IMPLEMENTED** — they belong to module 12,
which is locked, and the routes do not exist in the running build.

**UAT-11 (export) is different and is recorded as PASS-by-other-means.** `docs/api/reports.md` §12
states explicitly that UC-16 has no route of its own and that the file is produced in the browser.
This was verified at runtime rather than taken on trust: on `/app/reports`, an "Export report" button
exists, and clicking it produced **exactly one** `window.print()` invocation with **no network
request** to any export/download/PDF/CSV path and no `<a download>` element added. The documented
design and the observed behaviour agree.

---

## 8. OBSERVATIONS / DOCUMENTATION DISCREPANCIES

> Per the run's rules, a documentation mismatch is **not** reported as an API failure, and Swagger
> `Example Value` is never used as runtime evidence.

**OBS-01 (closes the previous tester's lead, §11 of the brief) — reported as a documentation artefact,
not a bug.**
*Statement under test:* "Campus Cafe / category id 13" misbehaves.
*Finding:* `13` and `"Campus Cafe"` are the **`example` values declared on `CategoryResponse`** in the
OpenAPI document (`id.example = 13`, `name.example = "Campus Cafe"`, `description.example =
"Boba and study snacks"`). They are Swagger **Example Value** content, not a runtime response.
*Independent reproduction:* `POST /api/v1/categories` returned 201 with the real body
`{"id": 13, "name": "Campus Cafe QA", ...}`; `GET /categories` contained id 13; `GET /categories/13`
returned 200 with **zero** field differences from the create response; `POST /transactions` with
`categoryId: 13` returned 201 (id 34) with `categoryName: "Campus Cafe QA"`. **No 404 occurred
anywhere.**
*Conclusion:* **"Swagger Example Value differs from runtime response."** No API defect. The original
lead is closed and was never treated as a confirmed bug.

**OBS-02 — `docs/api/FRONTEND_API_GUIDE.md` §2.4 contradicts `docs/api/authentication.md` §5.**
The frontend guide says "A second logout is a `204` again, not an error." The authentication contract
says a second sign-out with the same token "returns `401` rather than `204`, because the token is no
longer valid." **Runtime returns 401** (TC-63). The frontend guide is the incorrect document.

**OBS-03 — `GET /api/v1/admin/stats/top-categories` publishes a per-student amount.**
Observed response (read-only, ADMIN token):
`{"categoryId": 13, "categoryName": "Campus Cafe QA", "scope": "PERSONAL", "txnCount": 1,
"totalAmount": 9.5, "distinctUsers": 1}`.
With `scope: "PERSONAL"` and `distinctUsers: 1`, `totalAmount` is **one named student's spending on
one category**, and the category name is a personal category — a name the student chose. This sits
uneasily beside `docs/api/administration.md`'s framing of the admin statistics surface. Recorded as an
observation for the project owner to confirm against the intended privacy posture; **not** classified
as a defect, because the response matches its own field contract (`categoryId`, `categoryName`,
`type`, `scope`, `txnCount`, `totalAmount`, `distinctUsers`).

**OBS-04 — plaintext descriptions in rows that predate encryption (documented, still true at runtime).**
`docs/SECURITY.md` §6 item 7 already requires re-encrypting rows that predate encryption, and §12.7
records the transitional warning. Observed at rest: transaction 33 and 34 and recurring rules 1 and 2
hold **plaintext** descriptions, while recurring rule 3 (created during this run) holds a proper
envelope — Base64 decoding gives `format = 1`, `keyVersion = 1`, 12-byte IV, and a ciphertext whose
length implies a 19-character plaintext. So encryption is working for new writes and the pre-existing
rows are simply awaiting the re-encryption pass the documentation already asks for. Reported so the
gap is not mistaken for a fresh defect.

**OBS-05 — the frontend stores a user object whose `status` is hardcoded.**
`AuthService.restoreSession()` reads `campus_coin_user` from `localStorage` verbatim, and
`persistSession()` writes `status: 'ACTIVE'` unconditionally. A stale local record therefore claims
`ACTIVE` even for an account the administrator has disabled. This is cosmetic only: the server refuses
that token (**401**, proven in UAT-13/TC-97), so no access is granted. Recorded because it can mislead
a client reading its own stored state.

---

## 9. Cross-endpoint consistency

Multiple independent reads of the same underlying facts were compared. No contradiction was found.

| Fact | Read through | Result |
|---|---|---|
| Month spending for USER_A, 2026-09 | `GET /dashboard` · `GET /reports?month=2026-09` · `GET /reports/spending?granularity=DAILY` · `GET /reports/spending?granularity=WEEKLY` | **22.00** in all four |
| USER_B, 2026-09, after adding income | `GET /reports` totals vs. its own category blocks | `income 250.00`, `expense 36.00`, `net 214.00`, `transactionCount 4`; category blocks sum to the same figures |
| Budget consumption | `GET /budgets/6` vs. the notification text vs. `budget_alert_log` (read-only DB) | 24.00 → 80 %; 31.00 → 103.33 % in all three |
| Soft-deleted row 34 | `GET /transactions` (absent) · `GET /transactions?includeDeleted=true` (present) · `DELETE` history in `transaction_history` (CREATE, UPDATE, DELETE, RESTORE) | consistent |
| Referential integrity | `transaction_history` LEFT JOIN `transactions` | **0 orphans** |

---

## 10. Security / ownership

| Control | Probe | Observed |
|---|---|---|
| Cross-user read/write/delete (UAT-02) | USER_B on USER_A's category, transaction, recurring rule (10 probes) | **404** on every one |
| Cross-user read/write/delete | USER_A on USER_B's budget (3 probes) | **404** on every one |
| Cross-user indirect use | USER_B posting a transaction / budget against USER_A's `categoryId` | **404** |
| Anonymous | 5 endpoints with no token | **401** |
| Malformed token | `not.a.jwt.at.all` | **401** |
| Empty bearer value | `""` | **401** |
| Tampered signature | last 6 characters of a live token replaced | **401** |
| Role separation | STUDENT on 7 admin GETs | **403** each |
| Role separation | ADMIN on 9 student endpoints | **403** each |
| Portal separation | STUDENT at `/admin/auth/login` · ADMIN at `/auth/login` | **403** each |
| Account disable (UAT-13) | live token after admin disables the account | **401** |
| Password reset (UAT-03) | reuse of a consumed reset token | **400 `INVALID_RESET_TOKEN`** |
| Password reset | invalidation of *other* live sessions | both pre-existing sessions **401** |
| Anti-enumeration (BR-04) | reset request for a known vs. an unknown address | responses **identical** |
| Encryption at rest | `transactions.description`, `recurring_rules.description`, `bookmarks.note` | new writes carry the documented envelope (format 1, keyVersion 1, 12-byte IV) |

The ownership policy is consistent and strict: **"not yours" and "does not exist" are indistinguishable
(404)** on student-reachable resources, which is the documented intent, while the admin-only routes
answer 404 for an unknown id where the caller is entitled to know.

**Login throttling — measured, not assumed.** Eight failed sign-in attempts were sent for a
**non-existent** address (so that no real account was locked out), and the status sequence was
`401, 401, 401, 401, 401, 429, 429, 429` with the final error code `TOO_MANY_ATTEMPTS`. The cut-off
falls exactly at the configured `auth.max_login_attempts = 5` read earlier from
`GET /api/v1/admin/settings`, and a wrong email is throttled the same way a real one would be.

---

## 11. Data integrity

Read-only database checks after the journeys:

| Check | Query | Result |
|---|---|---|
| Soft delete keeps the row | `transactions WHERE is_deleted = 1` | ids **39**, **36** — present, not removed |
| History is written | `transaction_history WHERE transaction_id IN (33,34)` | 33 `CREATE`; 34 `CREATE`, `UPDATE`, `DELETE`, `RESTORE` — **UAT-06's "2 history rows" for edit+delete holds** |
| No orphan history | `transaction_history LEFT JOIN transactions` | **0 orphans** |
| Alert de-duplication | `budget_alert_log GROUP BY budget_id, threshold_type HAVING COUNT(*) > 1` | **no rows** — one alert per threshold |
| Trend completeness | `GET /reports` `sixMonthTrend` length | **6** for a populated month and for an empty one |
| Recurring idempotency table | `recurring_occurrences` | **0 rows** (consistent with no scheduler run — §7.1) |
| Scheduler has not posted | `transactions.source` | all **MANUAL** |

---

## 12. UAT traceability (UAT-01 … UAT-17)

The codes below come from the repository's own UAT matrix. No UAT code was invented.

| UAT | Scenario (abbreviated) | UC | Result | Evidence |
|---|---|---|---|---|
| UAT-01 | Duplicate email at registration | UC-01 | **PASS** | 409 `EMAIL_ALREADY_REGISTERED` |
| UAT-02 | SV A opens SV B's transaction URL → refused | UC-10 | **PASS** | 404 on all cross-user probes (§10) |
| UAT-03 | Reuse a reset link → refused, offer a new link | UC-03 | **PASS** | 400 `INVALID_RESET_TOKEN`; a fresh request then returns 200 |
| UAT-04 | New student records a first transaction from the dashboard; balance updates at once | UC-07, UC-12 | **PASS** | txn 33 → dashboard totals change immediately |
| UAT-05 | Type "Campus Cafe", override the suggestion, retype → the override is followed | UC-08 | **BLOCKED / NOT IMPLEMENTED** | routes absent (§7.2) |
| UAT-06 | Edit then delete a transaction → 2 history rows; gone from balance and reports | UC-10 | **PASS** | `transaction_history` for id 34 holds CREATE/UPDATE/DELETE (+RESTORE after the later restore) |
| UAT-07 | Budget 30, spend 24 then 7 → one "near", one "exceeded", no repeats | UC-13, UC-14 | **PASS** | notifications 2 and 3, one each; DB shows one row per threshold |
| UAT-08 | Monthly allowance template, simulate a period → exactly one transaction per period | UC-09 | **BLOCKED** | §7.1 — the posting job could not be observed |
| UAT-09 | 6-month report with empty months → all 6 columns, empty ones 0 | UC-15 | **PASS** | 6 trend points for both a populated and an empty month |
| UAT-10 | Filter the report by income source "Scholarship" → only Scholarship | UC-15 | **PASS** | `incomeByCategory` contains only Scholarship (100 %) |
| UAT-11 | Export a month report to PDF → file only on request, matches the screen | UC-16 | **PASS (by documented design)** | `docs/api/reports.md` §12; runtime: one `window.print()`, zero export requests |
| UAT-12 | Dismiss a tip and pin a tip → dismissed stays gone, pinned is at the top | UC-18 | **PASS** | pinned id 14 first with `state PINNED`; dismissed id 16 absent, and still absent after regenerating |
| UAT-13 | Admin disables a signed-in account → session revoked, cannot log in again | UC-22 | **PASS** | live token 401, login 401 `ACCOUNT_DISABLED`, self-disable 409 |
| UAT-14 | Admin adds a default category → appears for every student | UC-20 | **PASS** | id 14 visible to the student; student PATCH/DELETE → 404 |
| UAT-15 | CSV import with 2 bad rows → exactly 2 flagged, the rest imported | UC-11 | **BLOCKED / NOT IMPLEMENTED** | routes absent (§7.2) |
| UAT-16 | Dark mode + larger font, sign out, sign in → preferences retained | **UC-27** | **FAIL** | §15 ISSUE-001, ISSUE-002, ISSUE-003 |
| UAT-17 | Use the dashboard on a mobile phone → responsive, fully operable | UC-12 · NFR | **PASS** | 390×844: no horizontal overflow, 0 over-wide elements, bottom navigation |

> **On the UAT matrix itself:** UC-27 and UAT-16 are traced in the matrix to *FR Accessibility and UI
> Enhancements · NFR Accessibility*, and the USER entity row in the use-case document lists "tùy chọn
> hiển thị" (display preferences) among the fields for UC-01–05, 22 **and 27**. UAT-16's wording is
> "Tùy chọn được giữ" (the preferences are retained) — which is why ISSUE-001, ISSUE-002 and ISSUE-003
> all bear on it: the literal script appears to pass only because `localStorage` survives a sign-out
> that never happened, while the font size never takes effect and the storage is not account-scoped as
> UC-27 B4 requires.

---

## 13. Endpoint traceability

**61 / 61 operations in the live OpenAPI document were exercised by real HTTP requests during this
run.** Coverage was computed by matching the recorded request log against the OpenAPI paths with ids
normalised.

| Group | Operations | Exercised |
|---|---|---|
| `/api/v1/auth/*` (register, login, logout, reset request/verify/complete) | 6 | 6 |
| `/api/v1/profile/me`, `/api/v1/profile/me/preferences` | 3 | 3 |
| `/api/v1/categories` + `/{id}` | 5 | 5 |
| `/api/v1/transactions` + `/{id}` + `/{id}/restore` | 6 | 6 |
| `/api/v1/budgets` + `/{id}` | 5 | 5 |
| `/api/v1/recurring-rules` + `/{id}` | 5 | 5 |
| `/api/v1/dashboard`, `/api/v1/reports`, `/api/v1/reports/spending` | 3 | 3 |
| `/api/v1/tips`, `/api/v1/tips/generate`, `/api/v1/tips/months`, `/api/v1/tips/{id}/state` | 4 | 4 |
| `/api/v1/bookmarks` + `/{id}` | 4 | 4 |
| `/api/v1/notifications` + `/{id}` + `/{id}/read` | 3 | 3 |
| `/api/v1/admin/auth/login` | 1 | 1 |
| `/api/v1/admin/categories` + `/{id}` | 3 | 3 |
| `/api/v1/admin/announcements` + `/{id}` | 3 | 3 |
| `/api/v1/admin/tip-templates` + `/{id}` | 3 | 3 |
| `/api/v1/admin/settings` + `/{key}` | 2 | 2 |
| `/api/v1/admin/users` , `/{id}/status`, `/{id}/password-reset` | 3 | 3 |
| `/api/v1/admin/stats`, `/api/v1/admin/stats/top-categories` | 2 | 2 |
| **Total** | **61** | **61** |

Notable results from the last group: admin `PATCH` on a default category → 200 (id 14 renamed);
admin `PATCH` on an announcement → 200 `isActive: false`; tip-template create → 201 (id 8) and patch
→ 200; `PATCH /admin/settings/budget.exceeded_threshold_pct` with its own current value → 200 (sent
with the value it already held so that no configuration was actually altered);
`POST /admin/users/6/password-reset` → **202** with the message
`"A password reset link has been sent to the account's email address."` and **no token in the
response**, exactly as `docs/api/administration.md` describes; the same call for id `999999` → **404**.

---

## 14. Regression summary

| Area | Verdict |
|---|---|
| Authentication (register, login, logout, reset) | **No regression observed.** Server-side logout, reset-link single-use, session revocation on reset all behave as documented |
| Authorization (roles, portals, ownership) | **No regression observed.** 23 ownership/authorization probes all produced the documented status |
| Categories & transactions (CRUD, retire, soft delete, restore, history) | **No regression observed** |
| Budgets & alerts | **No regression observed**, including de-duplication after delete |
| Recurring rules (API) | **No regression observed**; scheduler posting **not verified** (§7.1) |
| Tips & bookmarks | **No regression observed** |
| Reports | **No regression observed** |
| Dashboard, notifications | **No regression observed** |
| Admin portal | **No regression observed** across all 16 admin operations |
| Encryption at rest (M11) | **Working for new writes.** Pre-existing rows remain plaintext pending the re-encryption pass the documentation already requires (OBS-04) |
| **Frontend session handling** | **REGRESSION / DEFECT — see ISSUE-001.** Sign-out does not end the session |
| **Frontend display preferences** | **REGRESSION / DEFECT — see ISSUE-002, ISSUE-003.** Font scale does not apply; the preference is not account-scoped |

---

## 15. Issues requiring developer review

> Every entry below carries **"Not fixed during this QA run: YES"**.
> No code was modified, and no bug was worked around in order to continue testing.

---

### ISSUE-001 — Sign-out does not end the session

- **Severity:** **HIGH**
- **First observed:** 2026-09-26, browser leg of Journey 7 / UAT-13, `http://localhost:4200`
- **Journey:** 7 (session persistence) — browser leg
- **UC / UAT:** UC-05 (sign out) · bears on **UAT-13** (a session the administrator must revoke) and on **UAT-16**
- **Endpoint / surface:** UI control "Sign Out" in the sidebar → expected `POST /api/v1/auth/logout`
- **Preconditions:** signed in as a STUDENT in the Angular app; a protected route such as `/app/home` reachable
- **Steps to reproduce:**
  1. Sign in as `qa.journey.c1d8488c@example.com` through the UI.
  2. Click **Sign Out** in the sidebar. The app navigates to `/auth/login`.
  3. Observe the network log, filtered to `logout|profile`.
  4. In the browser console: `localStorage.getItem('campus_coin_token')`.
  5. Re-issue a request with the retained token:
     `fetch('/api/v1/profile/me', { headers: { Authorization: 'Bearer ' + localStorage.getItem('campus_coin_token') } })`
  6. Navigate to `http://localhost:4200/app/home`.
- **Expected:** `POST /api/v1/auth/logout` → **204**; the server-side session revoked; local token and
  user cleared; a request with the old token → **401**; `/app/home` redirects to the sign-in page.
  (`docs/api/FRONTEND_API_GUIDE.md` §2.4: "Call the server **first**, then clear local state; the
  reverse leaves an open session behind.")
- **Actual:** the filtered network log is **empty** — **no logout request is ever made**. `localStorage`
  still holds `campus_coin_token` and `campus_coin_user`. The retained token returns **200** from
  `/api/v1/profile/me`, and `/app/home` renders the protected dashboard (heading "Welcome back, QA").
- **Evidence:** empty network log for `logout|profile`; the localStorage read; the **200** response
  carrying `qa.journey.c1d8488c@example.com`; the rendered protected dashboard.
- **Cross-checks:** the same action performed over the API works correctly — `POST /api/v1/auth/logout`
  → **204**, the token then → **401**, a second logout → **401** (Journey 7, TC-61…TC-63). The defect is
  therefore confined to the client, and does not contradict the documented server behaviour.
- **Suspected layer:** **frontend**. `frontend/src/app/shared/components/nav-sidebar/nav-sidebar.component.ts:152`

  ```ts
  onLogout(): void {
    this.auth.logout();                                   // <-- no .subscribe()
    this.router.navigate([this.isAdmin ? '/auth/admin-login' : '/auth/login']);
  }
  ```

  `AuthService.logout()` returns a **cold** `Observable`:

  ```ts
  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/logout`, {}).pipe(
      tap(() => this.logoutLocally())                     // runs only when subscribed
    );
  }
  ```

  Because the Observable is never subscribed, `HttpClient` never issues the request, so neither the
  server call nor `logoutLocally()` (which lives inside `tap`) executes. The navigation away from the
  page then makes the failure look like a successful sign-out.

  This is the **only** call site of `AuthService.logout()` in the application — `nav-sidebar.component.ts`
  is the sole non-spec file that references it, and the component contains no `subscribe` at all. (The
  one other place that clears session state is `frontend/src/app/core/interceptors/error.interceptor.ts:16`,
  which calls `logoutLocally()` when a response is already a 401. That is correct and is *not* a second
  instance of this defect: on a 401 the server has already rejected the token, so there is nothing to
  tell it. It does mean the only working route to a locally-cleared session today is an expired or
  revoked token.)
- **Confidence:** **HIGH** — the behaviour was produced in a real browser and the code path explains it
  exactly; the API-side control proves the server is not at fault.
- **Recommended next investigation:** grep the frontend for other `Observable`-returning service calls
  whose result is discarded (`.logout()`, `.updatePreferences()`, any `this.x.y()` without `subscribe`,
  `await` or a template `| async`); decide the intended contract (subscribe, or return a `Promise`).
  Confirm the removal is not deliberate.
- **Not fixed during this QA run:** **YES**

---

### ISSUE-002 — The font-size preference never applies; class names accumulate

- **Severity:** **MEDIUM**
- **First observed:** 2026-09-26, UAT-16, `/app/profile` on `http://localhost:4200`
- **Journey:** UAT-16 (display preferences) — Journey 1 / Journey 7 touch the same surface
- **UC / UAT:** **UC-27 B2/B3** (choose a font size; it applies immediately on every page) · NFR Accessibility
- **Endpoint / surface:** `ThemeService.applyFontSize()` ↔ `frontend/src/styles/_tokens.scss`
- **Preconditions:** signed in; the profile page shows the "Appearance & Accessibility" panel
- **Steps to reproduce:**
  1. Go to `/app/profile`.
  2. Under "Appearance & Accessibility", choose **Large (18px)**.
  3. Inspect `document.documentElement.className`.
  4. With the same element and everything else unchanged, compare a class that exists in the stylesheet
     with the class the service actually applies:
     ```js
     const el = document.documentElement;
     el.className = 'text-size-lg';    getComputedStyle(el).fontSize   // 18px
     el.className = 'text-size-large'; getComputedStyle(el).fontSize   // 16px
     ```
- **Expected:** choosing Large renders body text at **18px**, and the previous size class is removed so
  only one size class is present at a time.
- **Actual:** the service writes `text-size-large`, for which **no CSS rule exists** — the computed
  font size is the 16px default, i.e. visually unchanged. The removal step names the *other* set of
  classes, so classes accumulate: after choosing Large the DOM was observed carrying
  `html class="text-size-medium dark text-size-large"` — two size classes at once.
- **Evidence:** the same-element class swap above (**18px** for `text-size-lg` vs **16px** for
  `text-size-large`); the observed accumulated class list.
- **Cross-checks:** the panel itself reports the selection (so the state is stored), and dark mode — the
  other half of the same preference object — does work. The defect is limited to the font-size path.
- **Suspected layer:** **frontend**. `frontend/src/app/core/services/theme.service.ts:74`

  ```ts
  private applyFontSize(size: FontSizePreference): void {
    const root = document.documentElement;
    root.classList.remove('text-size-sm', 'text-size-md', 'text-size-lg');  // wrong names
    root.classList.add(`text-size-${size}`);                                 // -> text-size-large
  }
  ```

  and the only rules that exist (`frontend/src/styles/_tokens.scss:58-68`):

  ```scss
  html.text-size-sm { font-size: 14px; }
  html.text-size-md { font-size: 16px; }
  html.text-size-lg { font-size: 18px; }
  ```

  The service's vocabulary (`small` / `medium` / `large`, and the bound `FontSizePreference` type) and
  the stylesheet's vocabulary (`sm` / `md` / `lg`) disagree, and neither side was reconciled.
- **Confidence:** **HIGH** — the mismatch was measured on the live DOM, not inferred.
- **Recommended next investigation:** pick one vocabulary and align the service, the bound type
  (`frontend/src/app/core/models/user.model.ts`), the CSS and the stored value; then confirm that only
  one size class is ever present. Note the enum on the API side uses `SMALL|MEDIUM|LARGE|XLARGE` — a
  third vocabulary — so check that too before choosing.
- **Not fixed during this QA run:** **YES**

---

### ISSUE-003 — The display preference is device-local, not account-scoped

- **Severity:** **MEDIUM**
- **First observed:** 2026-09-26, UAT-16, in one browser at `http://localhost:4200`
- **Journey:** UAT-16
- **UC / UAT:** **UC-27 B4** — "Hệ thống lưu lựa chọn theo tài khoản" (the system saves the choice per
  account); postcondition "Lựa chọn hiển thị được giữ qua các phiên"
- **Endpoint / surface:** `PATCH /api/v1/profile/me/preferences` (exists and works over the API — the
  client simply never calls it)
- **Preconditions:** two STUDENT accounts available; a browser where the first one has changed its
  display settings
- **Steps to reproduce:**
  1. Sign in as USER_A. In `/app/profile`, enable **Dark Mode** and choose **Large**.
  2. Confirm the server-side profile of USER_A is `themePreference: "DARK"`, `fontScale: "LARGE"`
     (it was set over the API earlier in this run).
  3. Without clearing the browser, sign in as **USER_B** (`qa.student.b.dd39f463@example.com`), whose
     server profile is `themePreference: "SYSTEM"`, `fontScale: "MEDIUM"`.
  4. Inspect the DOM and the localStorage keys.
- **Expected:** USER_B sees the appearance their own account specifies (`SYSTEM` / `MEDIUM`), because
  UC-27 B4 stores the preference **per account**.
- **Actual:** the browser still renders `dark` on the root element and `text-size-large`, i.e. **USER_A's**
  settings, for USER_B — proven by reading the DOM class list after the second sign-in. The service
  never reads the account's stored preference, and the client never sends the user's choice back.
- **Evidence:** the DOM class list measured while signed in as USER_B (whose server profile is
  `SYSTEM` / `MEDIUM`) still showing the dark class and the large size class; the server profile values
  read back from `GET /api/v1/profile/me` for both accounts; no `/api/v1/profile/me` request on sign-in
  and no `PATCH /api/v1/profile/me/preferences` request at any point in the UI session.
- **Cross-checks:** over the API the storage itself works — `PATCH /profile/me/preferences` → 200 and
  a subsequent `GET /profile/me` reflects `DARK` / `LARGE` (Journey 1 TC-05, Journey 7 TC-66/TC-67). The
  API is not at fault; the client is.
- **Suspected layer:** **frontend**. `ThemeService` reads and writes **only** `localStorage`
  (`frontend/src/app/core/services/theme.service.ts:19, 28, 43, 51, 59`), and never asks the API.
  `AuthService.updatePreferences()` — the only code that calls the preferences endpoint
  (`frontend/src/app/core/services/auth.service.ts:176`) — has **zero call sites** in the application.
  `ProfileComponent` sends `name`, `academicYear`, `major`, `monthlyAllowance`, `savingsGoal` on save,
  but never `themePreference` or `fontScale`.
- **Confidence:** **HIGH** — demonstrated with a second account in the same browser, and corroborated
  by the absence of the request in the network log plus the absence of any call site in the source.
- **Recommended next investigation:** decide the intended source of truth for UC-27 B4. If it is the
  account, wire the profile page to `updatePreferences()` on change (and load it in `restoreSession`),
  then decide what should happen to the existing `localStorage` keys — leaving both as sources of truth
  will reproduce this defect.
- **Not fixed during this QA run:** **YES**

---

### Observation entries (no defect declared)

| ID | Surface | Why it is not a defect |
|---|---|---|
| OBS-01 | "Campus Cafe / id 13" lead from the previous tester | Swagger `Example Value`, not runtime. No 404 exists. §8 |
| OBS-02 | `FRONTEND_API_GUIDE.md` §2.4 vs `authentication.md` §5 on a second logout | A documentation disagreement; runtime matches `authentication.md` |
| OBS-03 | `admin/stats/top-categories` with `scope: PERSONAL`, `distinctUsers: 1` | Response matches its own field contract; privacy posture is the owner's call. §8 |
| OBS-04 | Plaintext descriptions in rows predating encryption | Already tracked by `docs/SECURITY.md` §6 item 7 / OB-013 |
| OBS-05 | Client-side `status: 'ACTIVE'` hardcoded in `persistSession()` | Cosmetic; the server still refuses a disabled account's token |

---

## 16. Final QA conclusion

**Scope of the claim.** This run tested M1–M11 by journey, over real HTTP against the running build and,
for the frontend, in a real browser. All **61** operations of the live OpenAPI document were exercised.
It did **not** test module 12, which is locked, and it could **not** observe the recurring scheduler's
posting job (§7.1) or run the database-level and container-level suites, which are not part of a manual
acceptance pass.

**What holds.** The M1–M11 backend is in good condition. Authentication, authorization, ownership
isolation, the soft-delete/history model, budget-alert de-duplication, recurrence rules at the API
level, tips, bookmarks, reports and the administration portal all behaved as their contracts describe,
with consistent figures across the endpoints that read the same facts. The security posture is
notably disciplined: 23 ownership and authentication probes produced the documented status on every
one, "not yours" is indistinguishable from "does not exist" on student-reachable resources, reset
tokens are single-use, and completing a reset revokes every other live session. **Application-level
field encryption is working for new writes.**

**What does not.** Three defects were confirmed, all in the **frontend**, none in the API:

1. **ISSUE-001 (HIGH)** — **Sign Out does not end the session.** No logout request is made, the token
   survives in the browser, and the server keeps serving the account. This is the most serious finding
   of the run: a signed-in session outlives the user's explicit instruction to end it, on a machine
   they may not control.
2. **ISSUE-002 (MEDIUM)** — the font-size preference **never applies**, because the class the service
   writes has no CSS rule, and size classes accumulate on the root element.
3. **ISSUE-003 (MEDIUM)** — the display preference is **device-local, not account-scoped**, which
   contradicts UC-27 B4 and leaks one student's appearance settings into another's session.

Together these make **UAT-16 FAIL**. Its literal script appears to pass, and that is precisely the trap:
the appearance does survive a "sign-out", because the sign-out never happens. The retention it
demonstrates is a browser's, not the account's.

**Two UAT items are honestly unfinished rather than passing:** **UAT-05** (AI category suggestion,
UC-08) and **UAT-15** (CSV import, UC-11) belong to the locked module 12 and their routes do not exist
in the running build — recorded as BLOCKED / NOT IMPLEMENTED, not as failures. **UAT-08** (recurring
posting) is BLOCKED for an environmental reason documented in §7.1, with the two supported ways to
observe it both unavailable without restarting or re-configuring the build under test.

**One environmental caveat the next tester must know.** The running backend was started from a jar that
has since been deleted from disk, while `backend/target/classes` and the working tree contain newer
packages (`anomaly/`, `categorisation/`, `forecast/`, `imports/`, `insight/`, `recent/`, `common/ai/`,
`common/jdbc/`) and a modified `SecurityConfig.java`. Everything in this report describes the **running
61-operation build**, which is an earlier artifact than the working tree. Any of the module-12 findings
above may already be addressed in a build that has not been started.

**Status.**

> **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL**

**Recommended order of work for the developer:** ISSUE-001 first — it is small, its cause is a missing
subscription, and it is the only finding here with a security consequence. Then ISSUE-002 and
ISSUE-003 together, as one decision about where UC-27's preference lives.

---

*End of report. No source file, schema, seed or configuration value was modified during this QA run.
Test data created during the run remains in the development database (users 5, 6, 7; categories 13, 14,
15; transactions 33, 34, 39, 40, 41; budgets 6; recurring rule 3; announcement 3; tip template 8).*
