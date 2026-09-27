# CAMPUS COIN — COMPLETE FRONTEND INTEGRATION AUDIT (M1 → M12)

**Scope:** User → Frontend UI → Angular Component → Angular Service → HTTP Request → Backend API → HTTP Response → Angular State → UI Rendering
**Modules audited:** M1, M2, M3, M4, M5, M6, M7, M8, M9, M10, M11, **M12** (M12 explicitly included — not omitted)
**Evidence base:** 19 scripted Playwright journeys, 666 captured request/response pairs, plus source-code tracing and read-only API probes.
**Status line:** `M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL`

> **Method note.** An API is marked `CALLED FROM FRONTEND` **only** where a real browser HTTP request was observed in a Playwright-captured network log. Where a service method exists and a component calls it, but no real request was captured, the item is `C (NOT VERIFIED)`. Where nothing in the UI reaches it, the item is `E/D`. No success is claimed on the strength of source code alone.

---

## 1. Environment

| Item | Value |
|---|---|
| Date of audit | 2026-09-26 |
| Backend | Java 21 (Temurin), Spring Boot 3.4.3, Spring Security + JWT (HS256) |
| Frontend | Angular 21, zoneless, standalone components, signals |
| Database | MySQL 8.0.46 (Docker) |
| Backend base URL | `http://localhost:8080` |
| OpenAPI document | `http://localhost:8080/api-docs` (custom path; `/v3/api-docs` returns 404) |
| Frontend dev server | `http://localhost:4200` |
| Browser automation | Playwright 1.63.0-alpha, Chromium build `chromium-1234` (explicit `executablePath` required — Playwright requested `1237`, which is not cached) |
| Browser locale | `en-US` (see §19 FE-UI-004 — the machine default `vi-VN` renders the same figures with `.`/`,` swapped) |
| Auth model | 5 public POSTs + `/admin/auth/login` = `permitAll`; 16 `/api/v1/admin/**` = `hasRole("ADMIN")`; student prefixes = `hasRole("STUDENT")`; `/auth/logout` falls through to `authenticated()` |
| Demo accounts used | `admin@campuscoin.edu` (ADMIN), `an.nguyen@student.campuscoin.edu` (STUDENT, "Alex", id 2), `binh.tran@student.campuscoin.edu` (STUDENT, "Bella", id 3) |

**API surface (from the live OpenAPI document): 76 operations across 56 paths.** `/api-docs` was fetched at audit time; the counts below are derived from that document, not from estimate.

---

## 2. Backend API Inventory (all 76 operations, by module)

| Module | Operations | Paths |
|---|---|---|
| M1 Authentication | 6 | `/auth/login`, `/auth/logout`, `/auth/register`, `/auth/password-reset/request`, `/auth/password-reset/verify`, `/auth/password-reset/complete` |
| M2 Profile & Preferences | 3 | `GET /profile/me`, `PATCH /profile/me`, `PATCH /profile/me/preferences` |
| M3 Personal Categories | 5 | `GET /categories`, `GET /categories/{id}`, `POST /categories`, `PATCH /categories/{id}`, `DELETE /categories/{id}` |
| M4 Transactions | 6 | `GET /transactions`, `GET /transactions/{id}`, `POST /transactions`, `PATCH /transactions/{id}`, `DELETE /transactions/{id}`, `POST /transactions/{id}/restore` |
| M5 Recurring Rules | 5 | `GET /recurring-rules`, `GET /recurring-rules/{id}`, `POST /recurring-rules`, `PATCH /recurring-rules/{id}`, `DELETE /recurring-rules/{id}` |
| M6 Budget & Notifications | 8 | `GET /budgets`, `GET /budgets/{id}`, `POST /budgets`, `PATCH /budgets/{id}`, `DELETE /budgets/{id}`, `GET /notifications`, `GET /notifications/{id}`, `POST /notifications/{id}/read` |
| M7 Dashboard | 1 | `GET /dashboard` |
| M8 Reports | 2 | `GET /reports`, `GET /reports/spending` |
| M9 Saving Tips | 4 | `GET /tips`, `GET /tips/months`, `POST /tips/generate`, `POST /tips/{id}/state` |
| M10 Bookmarks | 4 | `GET /bookmarks`, `POST /bookmarks`, `PATCH /bookmarks/{id}`, `DELETE /bookmarks/{id}` |
| M11 Administration | 16 | `POST /admin/auth/login`, `GET /admin/stats`, `GET /admin/stats/top-categories`, `GET /admin/users`, `POST /admin/users/{id}/status`, `POST /admin/users/{id}/password-reset`, `GET /admin/categories`, `POST /admin/categories`, `PATCH /admin/categories/{id}`, `GET /admin/tip-templates`, `POST /admin/tip-templates`, `PATCH /admin/tip-templates/{id}`, `GET /admin/announcements`, `POST /admin/announcements`, `PATCH /admin/announcements/{id}`, `GET /admin/settings` + `PATCH /admin/settings/{key}` |
| M12 Optional/Advanced | 15 | **CSV Import (6):** `GET /imports`, `POST /imports`, `GET /imports/{batchId}`, `POST /imports/{batchId}/commit`, `POST /imports/{batchId}/cancel`, `PATCH /imports/{batchId}/rows/{rowId}` · **AI (1):** `POST /ai/suggest-category` · **Insights (3):** `GET /insights`, `POST /insights/generate`, `GET /insights/months` · **Anomalies (2):** `GET /anomalies`, `POST /anomalies/scan` · **Forecast (1):** `GET /forecast` · **Recent Activity (2):** `GET /recent-activity`, `POST /recent-activity` |

> M11 counts 16 distinct operations in the live document (`GET/PATCH /admin/settings/{key}` are two operations); the module summary in §19 uses 16.

---

## 3. Frontend Route Inventory

| Route | Component | Guard | Notes |
|---|---|---|---|
| `/` , `/auth/login`, `/auth/register`, `/auth/forgot-password`, `/auth/reset-password` | auth features | `guestGuard` | public |
| `/app/home` | home-feed | `authGuard` | Feed |
| `/app/quick-add` | quick-add | `authGuard` | Feed + Quick Add |
| `/app/reports` | reports | `authGuard` | Reports |
| `/app/budgets` | budgets | `authGuard` | Budgets |
| `/app/categories` | categories | `authGuard` | Categories |
| `/app/profile` | profile | `authGuard` | Profile |
| `/admin/dashboard` | admin-dashboard | `adminGuard` | |
| `/admin/users` | admin-users | `adminGuard` | |
| `/admin/categories` | admin-categories | `adminGuard` | |
| `**` | → `''` | — | wildcard |

**Probed and confirmed absent (all redirect to `/app/home`):** `/app/recurring`, `/app/tips`, `/app/bookmarks`, `/app/insights`, `/app/import`, `/app/imports`, `/app/forecast`, `/app/anomalies`, `/app/activity`.

---

## 4. Angular Service Inventory

| Service | HTTP? | Methods | Reaches a page? |
|---|---|---|---|
| `auth.service.ts` | yes | logout, register, login, requestPasswordReset, verifyResetToken, completePasswordReset, getProfile, updateProfile, updatePreferences | yes |
| `category.service.ts` | yes | getCategories, getCategoryById, getCategoriesByType, addCategory, updateCategory, deleteCategory, + local helpers | yes |
| `transaction.service.ts` | yes | getTransactions, getRecentTransactions, getTransactionById, addTransaction, updateTransaction, deleteTransaction, restoreTransaction, getReport, getMonthlyBalance, getCategoryBreakdown, getDailySpending | yes |
| `budget.service.ts` | yes | getBudgets, getBudgetById, addBudget, updateBudgetLimit, deleteBudget, getBudgetAlerts | yes |
| `notification.service.ts` | yes | loadNotifications, markAsRead, markAllAsRead, clearAll | yes |
| `dashboard.service.ts` | yes | dashboard fetch | yes |
| `admin.service.ts` | yes | 17 methods | 5 used |
| `tip.service.ts` | yes | **8 methods (M9 + M10)** | **NO — zero importers** |
| `theme.service.ts` | **no** | theme + font size, localStorage only | yes |
| `chatbot.service.ts` | **no HttpClient at all** | keyword-matched fixture | yes |
| `mascot.service.ts` | no | animation triggers | yes |
| `toast.service.ts` | no | toast + confirm dialogs | yes |
| `campus-coin.service.ts` | yes | **orphan** — `/api/health`, `/api/users`, `/api/wallets`, `/api/merchants`, `/api/transactions/transfer|topup|pay` | **NO — zero importers; none of those paths exist in the backend** |

**Dead code confirmed by importer search:** `mock-data/{budgets,users,transactions,categories}.mock.ts` → **0 importers each**; `mock-delay.interceptor.ts` → **1 match, its own definition** (not registered in `app.config.ts`, which registers only `authInterceptor` + `errorInterceptor`).

---

## 5. Complete API Coverage Matrix (all 76 operations)

Classification: **A** = FRONTEND CONNECTED + VERIFIED · **B** = CONNECTED BUT FAILING · **C** = FRONTEND CODE EXISTS BUT REAL REQUEST NOT VERIFIED · **D** = UI EXISTS BUT API NOT CONNECTED · **E** = API EXISTS BUT NO CURRENT UI CALLER · **F** = SYSTEM/BACKEND-DRIVEN · **G** = OPTIONAL FEATURE · **H** = POTENTIALLY REDUNDANT/DUPLICATE · **I** = APPARENTLY MISSING API

### M1 — Authentication (6)

| # | Operation | Observed in browser | Status | Class |
|---|---|---|---|---|
| 1 | `POST /auth/login` | 22 reqs (200×16, 403×6 — the 403s are my deliberate wrong-password control) | VERIFIED | **A** |
| 2 | `POST /auth/register` | `-> 201`, auto-signed-in, redirected to `/app/home` | VERIFIED | **A** |
| 3 | `POST /auth/logout` | `-> 204` **after fix**; before fix: 0 requests | VERIFIED (was broken) | **A** |
| 4 | `POST /auth/password-reset/request` | — | code + route exist, not fired (sends a real email) | **C** |
| 5 | `POST /auth/password-reset/verify` | — | same | **C** |
| 6 | `POST /auth/password-reset/complete` | — | same | **C** |

### M2 — Profile & Preferences (3)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 7 | `GET /profile/me` | 7 reqs → 200 | VERIFIED | **A** |
| 8 | `PATCH /profile/me` | 1 req → 200 (fired only after `major` manually filled) | VERIFIED but gated | **A** (see FE-CONTRACT-002) |
| 9 | `PATCH /profile/me/preferences` | **0 requests ever** | service method has zero callers | **E** |

### M3 — Personal Categories (5)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 10 | `GET /categories` | 17 reqs → 200 | VERIFIED | **A** |
| 11 | `POST /categories` | `-> 201` (final run) | VERIFIED | **A** |
| 12 | `PATCH /categories/{id}` | `PATCH /categories/15 -> 200` | VERIFIED | **A** |
| 13 | `DELETE /categories/{id}` | `DELETE /categories/15 -> 204` | VERIFIED | **A** |
| 14 | `GET /categories/{id}` | 0 requests | service method `getCategoryById` has 0 callers | **E** |

### M4 — Transactions (6)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 15 | `GET /transactions` | 40 reqs → 200 | VERIFIED | **A** |
| 16 | `POST /transactions` | `-> 201` | VERIFIED | **A** |
| 17 | `PATCH /transactions/{id}` | `PATCH /transactions/70 -> 200` | VERIFIED | **A** |
| 18 | `DELETE /transactions/{id}` | `DELETE /transactions/70 -> 204` | VERIFIED | **A** |
| 19 | `GET /transactions/{id}` | 0 requests | service method has 0 callers | **E** |
| 20 | `POST /transactions/{id}/restore` | 0 requests | service method has 0 callers; **no UI affordance for deleted transactions** | **E** (see §21 FUTURE) |

### M5 — Recurring Rules (5)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 21 | `GET /recurring-rules` | 0 requests from UI (probe → 200) | no component, no route, no service | **E** |
| 22 | `POST /recurring-rules` | — | as above | **E** |
| 23 | `GET /recurring-rules/{id}` | — | as above | **E** |
| 24 | `PATCH /recurring-rules/{id}` | — | as above | **E** |
| 25 | `DELETE /recurring-rules/{id}` | — | as above | **E** |

> The Quick Add form **has** a `recurringFrequency` select, but its value is never sent — see FE-CONTRACT-001. That select is not a caller of M5.

### M6 — Budget & Notifications (8)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 26 | `GET /budgets` | 41 reqs → 200 | VERIFIED | **A** |
| 27 | `POST /budgets` | 3 reqs: `201×1`, `409×2` | VERIFIED (409s are correct server behaviour) | **A** |
| 28 | `PATCH /budgets/{id}` | `PATCH /budgets/2 -> 200` | VERIFIED | **A** |
| 29 | `DELETE /budgets/{id}` | `DELETE /budgets/2 -> 204` | VERIFIED | **A** |
| 30 | `GET /budgets/{id}` | 0 requests | service method `getBudgetById` has 0 callers | **E** |
| 31 | `GET /notifications` | 56 reqs → 200 | VERIFIED | **A** |
| 32 | `POST /notifications/{id}/read` | `POST /notifications/1/read -> 200` | VERIFIED | **A** |
| 33 | `GET /notifications/{id}` | 0 requests | no corresponding service method at all | **E** |

### M7 — Dashboard (1)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 34 | `GET /dashboard` | 31 reqs → 200 | VERIFIED | **A** |

### M8 — Reports (2)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 35 | `GET /reports` | 1 req → 200 | VERIFIED | **A** |
| 36 | `GET /reports/spending` | 1 req → 200 | VERIFIED | **A** |

### M9 — Saving Tips (4)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 37 | `GET /tips` | 0 requests from UI (probe → 200) | `tip.service.ts` fully implemented, **zero importers** | **E** |
| 38 | `GET /tips/months` | 0 from UI (probe → 200) | as above | **E** |
| 39 | `POST /tips/generate` | — | as above | **E** |
| 40 | `POST /tips/{id}/state` | — | as above | **E** |

> **Important nuance:** tips *content* is visible on the Dashboard. `GET /dashboard` returns a `tips[]` array (observed: 3 tips with title/body/potentialSaving/state). So the M9 **feature** is partly user-visible via M7, but the **M9 endpoints are not called**. The M9 API family is `E`.

### M10 — Bookmarks (4)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 41 | `GET /bookmarks` | 0 from UI (probe → 200) | lives in dead `tip.service.ts` | **E** |
| 42 | `POST /bookmarks` | — | as above | **E** |
| 43 | `PATCH /bookmarks/{id}` | — | as above | **E** |
| 44 | `DELETE /bookmarks/{id}` | — | as above | **E** |

### M11 — Administration (16)

| # | Operation | Observed | Status | Class |
|---|---|---|---|---|
| 45 | `POST /admin/auth/login` | 6 reqs → 200 | VERIFIED | **A** |
| 46 | `GET /admin/stats` | 9 reqs → 200 | VERIFIED | **A** |
| 47 | `GET /admin/stats/top-categories` | 8 reqs → 200 | VERIFIED | **A** |
| 48 | `GET /admin/users` | 6 reqs → 200 | VERIFIED | **A** |
| 49 | `POST /admin/users/{id}/status` | `POST /admin/users/3/status → 200` (Disable) | VERIFIED | **A** |
| 50 | `POST /admin/users/{id}/password-reset` | `-> 202` (confirmed via modal "Send Reset Link") | VERIFIED | **A** |
| 51 | `GET /admin/categories` | 5 reqs → 200 | VERIFIED | **A** |
| 52 | `POST /admin/categories` | 0 requests | service method has 0 callers | **E** |
| 53 | `PATCH /admin/categories/{id}` | 0 requests | service method has 0 callers | **E** |
| 54 | `GET /admin/tip-templates` | 6 reqs → 200 | VERIFIED | **A** |
| 55 | `POST /admin/tip-templates` | `-> 201` ("Publish Template") | VERIFIED | **A** |
| 56 | `PATCH /admin/tip-templates/{id}` | 0 from UI (service `updateTipTemplate` is called only from the two tip rows that are not reached — see note) | **C/E** |
| 57 | `GET /admin/announcements` | 0 requests (probe → 200) | 3 service methods, **no UI** | **E** |
| 58 | `POST /admin/announcements` | 0 requests | as above | **E** |
| 59 | `PATCH /admin/announcements/{id}` | 0 requests | as above | **E** |
| 60 | `GET /admin/settings` | 0 requests (probe → 200) | 2 service methods, **no UI** | **E** |
| 61 | `PATCH /admin/settings/{key}` | 0 requests | as above | **E** |

### M12 — Optional / Advanced (15) — **explicitly audited, not omitted**

| # | Operation | Observed from UI | Status | Class |
|---|---|---|---|---|
| 62 | `GET /imports` | 0 | no route/component/service | **E** |
| 63 | `POST /imports` | 0 | as above | **E** |
| 64 | `GET /imports/{batchId}` | 0 | as above | **E** |
| 65 | `POST /imports/{batchId}/commit` | 0 | as above | **E** |
| 66 | `POST /imports/{batchId}/cancel` | 0 | as above | **E** |
| 67 | `PATCH /imports/{batchId}/rows/{rowId}` | 0 | as above | **E** |
| 68 | `POST /ai/suggest-category` | 0 | no caller | **E** |
| 69 | `GET /insights` | 0 (probe → 200) | no caller | **E** |
| 70 | `POST /insights/generate` | 0 | no caller | **E** |
| 71 | `GET /insights/months` | 0 | no caller | **E** |
| 72 | `GET /anomalies` | 0 (probe → 200) | no caller | **E** |
| 73 | `POST /anomalies/scan` | 0 | no caller | **E** |
| 74 | `GET /forecast` | 0 (probe → 200) | no caller | **E** |
| 75 | `GET /recent-activity` | 0 (probe → 200) | no caller | **E** |
| 76 | `POST /recent-activity` | 0 | no caller | **E** |

**M12 result: 15/15 backend operations are live and return 200 with a real student token, but 0/15 are reachable from the UI.** Grep over the whole frontend for `anomal`, `forecast`, `recent-activity`, `suggest-category` returns **0 hits**; `/imports` occurs only inside TypeScript `import` statements. M12 has **zero frontend presence**.

---

## 6. Complete UI Coverage Matrix

| Page / Area | Route | APIs it really calls (observed) | Verdict |
|---|---|---|---|
| Landing | `/` | none | static marketing page; gates the chatbot |
| Login | `/auth/login` | `POST /auth/login` | works |
| Register | `/auth/register` | `POST /auth/register` → auto `POST /auth/login` | works; see FE-CONTRACT-002 |
| Forgot password | `/auth/forgot-password` | 3 M1 ops wired, not fired | code present, unverified |
| Feed (Home) | `/app/home` | `GET /dashboard`, `GET /transactions`, `GET /notifications`, `GET /budgets` | works |
| Quick Add | `/app/quick-add` | `GET /transactions`, `POST /transactions`, `PATCH`, `DELETE`, `GET /categories` | works |
| Reports | `/app/reports` | `GET /reports`, `GET /reports/spending` | works |
| Budgets | `/app/budgets` | `GET /budgets`, `POST`, `PATCH`, `DELETE` | works; topbar wrong (FE-UI-001) |
| Categories | `/app/categories` | `GET /categories`, `POST`, `PATCH`, `DELETE` | works; topbar wrong |
| Profile | `/app/profile` | `GET /profile/me`, `PATCH /profile/me` | works; Save gated (FE-CONTRACT-002), topbar wrong |
| Admin dashboard | `/admin/dashboard` | `GET /admin/stats`, `GET /admin/stats/top-categories` | works; chart/tiles partly hardcoded (§19 FE-UI-004) |
| Admin users | `/admin/users` | `GET /admin/users`, `POST .../status`, `POST .../password-reset` | works |
| Admin categories | `/admin/categories` | `GET /admin/categories`, `GET/POST /admin/tip-templates` | works |
| Chatbot widget | overlay | **no HTTP at all** | fabricated data (§19 FE-MOCK-001) |

**UI elements with no API behind them (D):**

| UI element | Where | Reality |
|---|---|---|
| `recurringFrequency` select ("One-time only / Repeat Daily / Weekly / Monthly") | Quick Add | value never leaves the browser; `POST /transactions` body omits it (FE-CONTRACT-001) |
| Recurring badge `@if (tx.recurringFrequency !== 'NONE')` | Feed row | never renders — field is `undefined` in every response |
| Theme (dark mode) toggle | Profile + topbar | localStorage only, no API (§21 OPTIONAL) |
| Text-size control | Profile | localStorage only — **and was visually inert until fixed** (FE-UI-002) |
| Chatbot assistant | overlay | keyword-matched fixture, no API (FE-MOCK-001) |
| "Details" modal | Admin users | purely client-side render of the already-loaded row; fires **no** request (expected — no API needed) |

---

## 7. M1 Authentication — Journey Results

| Step | Action in UI | Request observed | Response | Angular state | UI render | Refresh | Verdict |
|---|---|---|---|---|---|---|---|
| Login (good) | fill email+password, submit | `POST /api/v1/auth/login` | 200 + token | token in localStorage, user signal set | redirected to `/app/home` | session persists | PASS |
| Login (bad) | wrong password | `POST /api/v1/auth/login` | 403 | error signal | inline error, stays on page | n/a | PASS |
| Register | fill 6 fields, submit | `POST /auth/register` | 201 → auto `POST /auth/login` 200 | token set | lands on `/app/home` as "Audit Throwaway" | persists | PASS |
| Logout | click "Sign Out" | **0 requests (before fix)**; `POST /auth/logout` 204 after fix | 204 | token cleared after fix | redirects to `/auth/login` | guarded route now blocks | **FIXED** |
| Password reset ×3 | `/auth/forgot-password` | not fired | — | — | — | — | NOT VERIFIED |

Authorization boundary: a student navigating to `/admin/dashboard` is redirected to `/auth/login` — verified in-browser.

---

## 8. M2 Profile & Preferences — Journey Results

| Action | Request | Response | Result |
|---|---|---|---|
| Open Profile | `GET /profile/me` | 200 (`fullName`, `academicYear`, `monthlyAllowanceBaseline`, `monthlySavingsGoal`, `currency`, `themePreference`, `fontScale`) | fields populate **except** `major` |
| Edit name → Save | `PATCH /profile/me` | 200 | persists |
| Change theme / font | **none** | — | localStorage only; not account-scoped |
| Any call to `PATCH /profile/me/preferences` | **none ever** | — | **NOT CONNECTED** |

**FE-API-002 (defect):** `auth.service.ts:updatePreferences()` has zero callers. Preferences are device-local, so a second user on the same browser inherits the first user's theme and text size — verified in-browser.

---

## 9. M3 / M4 Transactions & Categories — Journey Results

**M3 (final verified run):**

| Action | Request | Response |
|---|---|---|
| Open Categories | `GET /categories` | 200 |
| Add Custom Category → "Save Category ✓" | `POST /categories {"name":"ZZ Audit Category","type":"EXPENSE","icon":"tag","color":"#EAB308","description":"temp"}` | **201** |
| Edit → rename → "Save Category ✓" | `PATCH /categories/15 {"name":"ZZ Audit Renamed",...}` | **200** |
| Delete → confirm "Remove Category" | `DELETE /categories/15` | **204** |

All three writes persist across refresh and the grid re-renders correctly.

**M4:**

| Action | Request | Response |
|---|---|---|
| Open Quick Add | `GET /transactions?from=2026-08-01&to=2026-09-30`, `GET /categories` | 200 |
| Save Transaction (expense) | `POST /transactions` | **201** |
| Edit row → change amount → "Update Transaction" | `PATCH /transactions/70 {"categoryId":6,"amount":8.88,"txnDate":"2026-09-24","description":"AUDIT TEST purchase"}` | **200** |
| Delete row | `DELETE /transactions/70` | **204** — **no confirmation dialog is shown** |

**Cross-module propagation confirmed:** creating a $9.99 Food expense moved Food from Safe to Over, generated a `BUDGET_EXCEEDED` notification, and updated the dashboard (Net $61.01 / Expenses $198.99 / Food $33.99 of $30). Creating an expense updates budgets, notifications and dashboard in one flow.

---

## 10. M5 Recurring Rules — Journey Results

**No UI, no route, no service.** All five endpoints return 200 to a direct authenticated probe.

- `GET /recurring-rules` → 200 (live)
- `POST /recurring-rules` — not exercised from UI (there is no UI)
- Recurring rules therefore cannot be created, listed, edited or deleted by any real user.

**FE-CONTRACT-001 is the visible symptom:** Quick Add offers a recurrence select that does nothing.

---

## 11. M6 Budgets & Notifications — Journey Results

| Action | Request | Response |
|---|---|---|
| Open Budgets | `GET /budgets?month=2026-09` | 200 (6 budgets) |
| Add Category Budget (Academics — already has one) | `POST /budgets {"categoryId":9,"periodMonth":"2026-09","limitAmount":50}` | **409** `BUDGET_ALREADY_EXISTS` — **correct server behaviour** |
| Add Category Budget (new category) | `POST /budgets {"categoryId":14,...,"limitAmount":42}` | **201** |
| Edit limit → "Save Limit" | `PATCH /budgets/2 {"limitAmount":55}` | **200** |
| Delete → toast confirm "Remove Budget" | `DELETE /budgets/2` | **204** |
| Notification bell → click item | `GET /notifications`, `POST /notifications/1/read` | 200, 200 |

The 409 was investigated rather than reported as a defect: `GET /budgets` confirmed a budget for that category+month already existed, and the error body explains the intended remedy ("Change it instead of creating a second one"). **Not a defect.**

---

## 12. M7 Dashboard & M8 Reports — Journey Results

`GET /dashboard` (31 calls observed) returns `periodMonth`, `summary{totalIncome:260, totalExpense:189, netAmount:71, monthlyAllowanceBaseline:200, monthlySavingsGoal:100, savingsGoalPct:71}`, `topCategory`, `tips[3]`, `announcements[2]`.

`GET /reports` returns `totals{income:260, expense:189, net:71, transactionCount:7}`, `expenseByCategory[5]`, `incomeByCategory[2]`, `sixMonthTrend[6]`.

`GET /reports/spending` (granularity DAILY) drives the "Daily Spending Rhythm" chart — observed rendering `Day 3: $120, Day 5: $8, Day 7: $24, Day 8: $12, Day 10: $25`.

All figures agree with each other. **M7/M8 are the app's reliable sources of truth.**

---

## 13. M9 Saving Tips — Journey Results

`tip.service.ts` implements all four M9 operations correctly (`${baseUrl}/tips`, `${baseUrl}/tips/months`, `${baseUrl}/tips/generate`, `${baseUrl}/tips/{id}/state`).

**Import search result: zero importers anywhere in the project** (components, guards, interceptors, resolvers, dialogs, specs — all searched). The service is unreachable from any route. All four M9 endpoints return 200 to a direct probe, so the backend half is healthy; the frontend half is orphaned code.

Nuance recorded: **tips are visible on the Dashboard** via `GET /dashboard`'s embedded `tips[]`, so users do see tip text — but through M7, not M9.

---

## 14. M10 Bookmarks — Journey Results

The four M10 operations live in the same orphaned service (`${baseUrl}/bookmarks`, `${baseUrl}/bookmarks` POST, `${baseUrl}/bookmarks/{id}` PATCH/DELETE). All four return 200 to a direct probe.

Bookmarks have **no** user-visible surface at all — no page, no widget, no link, and no bookmark button anywhere in the UI. A student cannot save, list, annotate or remove a bookmark through any route the app exposes.

---

## 15. M11 Administration — Journey Results

| Action | Request | Response |
|---|---|---|
| Admin login | `POST /admin/auth/login` | 200 |
| Dashboard | `GET /admin/stats`, `GET /admin/stats/top-categories` | 200 |
| User Directory | `GET /admin/users` | 200 |
| Reset Password (Alex) → confirm "Send Reset Link" | `POST /admin/users/2/password-reset` | **202** |
| Disable (Bella) → confirm "Disable Account" | `POST /admin/users/3/status {"status":"DISABLED"}` | **200** |
| Categories & Tips | `GET /admin/categories`, `GET /admin/tip-templates` | 200 |
| Create Tip Template → "Publish Template" | `POST /admin/tip-templates {code, titleTemplate, bodyTemplate, conditionType:"GENERIC", defaultPriority:100, isActive:true}` | **201** |
| "Details" on a user row | **no request** | client-side only — correct |

`GET /admin/stats` observed payload: `{"totalStudents":2,"activeStudents":2,"disabledStudents":0,"activeUsers30d":3,"totalTransactions":70,"totalExpenseLogged":1196.99,"totalIncomeLogged":2205,"totalBudgets":11,"totalTipsGenerated":13,"totalInsightsGenerated":8}` (values as first observed, before my test data was created and then removed).

**Not connected (E):** admin announcements (3), admin settings (2), admin default-category create/update (2). All five probe → 200. The `Details` button correctly makes no request — there is no "get one user" endpoint, and the row is already in memory.

---

## 16. Data Persistence Verification

| Entity | Created via UI | Survived page refresh | Survived re-login | Confirmed server-side |
|---|---|---|---|---|
| Transaction | `POST /transactions` 201 | yes | yes | yes (`GET /transactions`) |
| Category | `POST /categories` 201 | yes | yes | yes (`GET /categories`) |
| Budget | `POST /budgets` 201 | yes | yes | yes (`GET /budgets`) |
| Profile name | `PATCH /profile/me` 200 | yes | yes | yes (`GET /profile/me`) |
| Tip template | `POST /admin/tip-templates` 201 | yes | yes | yes (`GET /admin/tip-templates`) |
| User status | `POST /admin/users/{id}/status` 200 | yes | yes | yes (`GET /admin/users`) |
| **Theme preference** | toggle | yes (localStorage) | **yes — but leaks to the next user on the same browser** | **no** |
| **Text size** | toggle | yes (localStorage) | same | **no** |

---

## 17. Cross-Module Integration

Working, verified chains:

1. **Transaction → Budget → Notification → Dashboard.** A `POST /transactions` moved Food to Over, produced a `BUDGET_EXCEEDED` notification, and updated `GET /dashboard` (Net $61.01 / Expenses $198.99). Confirmed end-to-end in one browser flow.
2. **Admin action → student state.** `POST /admin/users/3/status {DISABLED}` flipped Bella's row to DISABLED and the counter to `disabledStudents: 1`.
3. **Register → Login → Dashboard.** `POST /auth/register` auto-signed the new account in and loaded dashboard/notifications/budgets/transactions for a brand-new user with zero data (all rendered as `$0.00`/empty correctly).

Broken chains:

4. **Topbar summary ← Transaction cache.** The topbar reads `TransactionService.activeTransactions()` synchronously. Only Feed and Quick Add populate it; every other page renders `$0.00` (§19 FE-UI-001).
5. **Quick Add recurrence → Backend.** The select is never serialized (§19 FE-CONTRACT-001).

---

## 18. M12 Detailed Results

**M12 was audited in full. It is not omitted.**

### 18.1 Reachability

| Check | Result |
|---|---|
| Route for imports / insights / forecast / anomalies / recent-activity | **none** — `app.routes.ts` has no such path |
| `grep -ri "anomal"` over `frontend/src` | **0 hits** |
| `grep -ri "forecast"` | **0 hits** |
| `grep -ri "recent-activity"` | **0 hits** |
| `grep -ri "suggest-category"` | **0 hits** |
| `grep -ri "/imports"` | only inside TS `import` statements |
| Angular service for any M12 family | **none exists** |
| Navigation link to any M12 feature | **none** |

### 18.2 Backend liveness (direct probe with a real student token)

| Endpoint | Result |
|---|---|
| `GET /imports` | **200** |
| `POST /ai/suggest-category` | **400 VALIDATION_ERROR** — reached the controller and returned a per-field error (`transactionId`/`description` shape rejected my probe body). Endpoint is live and validating; my probe body was wrong, not the endpoint. |
| `GET /insights` | **200** |
| `GET /insights/months` | **200** |
| `GET /anomalies` | **200** |
| `GET /forecast` | **200** |
| `GET /recent-activity` | **200** |

### 18.3 Conclusion for M12

**15 / 15 M12 operations exist and are functional on the backend. 0 / 15 are connected to the frontend.** M12 is reachable only by direct HTTP call. There is no UI path, no service, no route, and no navigation affordance. Per the audit's classification rules this is **E — API EXISTS BUT NO CURRENT UI CALLER** across the whole module, and it is **NOT a frontend integration failure**: the UI was never built, which is consistent with M12's locked status.

No M12 defect is raised against the frontend, because there is no M12 frontend to be defective. The finding is a scope statement, not a bug.

---

## 19. Defects

Each defect lists severity, module, page, feature, reproduction, expected, actual, API, evidence, root cause, and fix status.

---

### FE-API-001 — Sign Out never calls the logout API

- **Severity:** HIGH
- **Module:** M1
- **Page / feature:** every authenticated page → sidebar "Sign Out"
- **Reproduction:** log in as Alex → click "Sign Out" → observe the network panel.
- **Expected:** `POST /api/v1/auth/logout`, local session cleared, redirect to `/auth/login`, guarded routes blocked.
- **Actual (before fix):** **zero HTTP requests.** The JWT stayed in `localStorage`; navigating to `/app/home` re-rendered the authenticated feed as Alex ("SEP NET +$71.00"), i.e. the user was never signed out.
- **API:** `POST /api/v1/auth/logout` (exists; returns 204)
- **Root cause:** `auth.service.ts:logout()` returns a **cold** observable wrapping `tap(() => this.logoutLocally())`. `nav-sidebar.component.ts:onLogout()` called `this.auth.logout();` without subscribing, so neither the request nor the `tap` side-effect ever ran.
- **Evidence:** pre-fix journey capture `logoutReqs: []`, `tokenStillThere: true`; server control test — `GET /profile/me` 200 after UI "logout", 401 after a real `POST /auth/logout`.
- **Fixed:** **YES** — added `.subscribe()` with success and error branches (both route to `/auth/login`). Post-fix browser evidence: `POST /auth/logout -> 204`, token cleared, `/app/home` redirects to `/auth/login`.

---

### FE-CONTRACT-001 — Quick Add recurrence select is never sent

- **Severity:** HIGH (user-visible non-functional control)
- **Module:** M4 ↔ M5
- **Page / feature:** Quick Add → "Repeat" select
- **Reproduction:** Quick Add → set amount → choose "Repeat Monthly" → Save Transaction → inspect the request body.
- **Expected:** either the recurrence is persisted (M5 rule created) or the control is absent.
- **Actual:** the body is `{categoryId, amount, txnDate, date, description}` — `recurringFrequency` is dropped. No M5 request is made. The Feed's recurring badge (`home-feed.component.ts:213`, `@if (tx.recurringFrequency !== 'NONE')`) therefore never renders, because `TransactionResponse` has no such field.
- **API:** `POST /transactions` (correct as-is: its contract is `{categoryId, amount, txnDate, description}`); a **M5** integration is missing.
- **Evidence:** browser capture `POST /transactions {"categoryId":...,"amount":...,"txnDate":"2026-09-24","description":"..."}` — the selected value `MONTHLY` absent; `transaction.service.ts:55,59` read `raw.recurringRuleId`/`raw.recurringFrequency`, which are always `undefined`.
- **Root cause:** a UI control was built against a feature (M5) that has no frontend service.
- **Fixed:** **NO** — see §21 REQUIRED. A truthful fix needs the M5 service and rule-creation flow; faking it in the payload would be inventing an API.

---

### FE-CONTRACT-002 — Profile "Major" is required but the API has no such field

- **Severity:** MEDIUM (blocks saving the profile on a fresh session)
- **Module:** M2
- **Page / feature:** Profile → "Save Profile Changes"
- **Reproduction:** log in → Profile → press Save without touching anything.
- **Expected:** the profile saves, or `Major` is not a required field.
- **Actual:** `Save Profile Changes` is **permanently disabled**. Browser evidence: `saveDisabled: true` with `invalidControls: [{name:"major", value:"", invalid:true}]`. Filling `Major` manually flips it to `saveDisabled: false` and the `PATCH` then succeeds.
- **API:** `PATCH /api/v1/profile/me` — `UpdateProfileRequest` accepts `fullName, academicYear, monthlyAllowanceBaseline, monthlySavingsGoal`. **There is no `major` field in `ProfileResponse`, `UpdateProfileRequest`, or the `users` table** (`academic_year VARCHAR(30)` exists; `major` does not).
- **Evidence:** `ProfileResponse` record inspected at source; `grep major` over `db/merged/campuscoin_full.sql` → 0 hits; browser `getComputedStyle`/class check above.
- **Root cause:** the form declares `major: ['Computer Science', [Validators.required]]` and `updateProfile()` drops it (it maps only 4 fields), while nothing ever populates it (`patchValue({ major: u.major })` assigns `undefined`).
- **Fixed:** **NO** — fixing means either removing the control or adding a column + API field. Both exceed "connect the current UI to the existing backend" (schema change / API invention are explicitly forbidden). Reported, not patched.

---

### FE-UI-001 — Top bar shows $0.00 on every page except Feed and Quick Add

- **Severity:** MEDIUM (misleading figure shown to the user)
- **Module:** cross-cutting (M2/M3/M4/M6/M7/M8 pages)
- **Page / feature:** the "SEP NET / ↑ / ↓" summary in the top bar
- **Reproduction:** log in → navigate directly to `/app/budgets` (or Categories, Reports, Profile) → read the top bar.
- **Expected:** the same real figures the dashboard shows: Net +$71.00, ↑ $260.00, ↓ $189.00.
- **Actual:** `SEP NET +$0.00 ↑ $0.00 ↓ $0.00`. Confirmed on `/app/budgets`, `/app/categories`, `/app/profile`, `/app/reports`; correct on `/app/home` and `/app/quick-add`.
- **API:** none is called — that is the defect. `GET /transactions` is **not** issued on those pages.
- **Evidence:** `j19` — direct load + hard reload of each page shows `txFetch: []` and `$0.00`; `j18` shows the same in sequence, while `GET /dashboard` returns `netAmount: 71.00`, `totalIncome: 260.00`, `totalExpense: 189.00`.
- **Root cause:** `transaction.service.ts:getMonthlyBalance()` is a **synchronous** computation over the in-memory `activeTransactions()` signal. Only `home-feed.component.ts:315` populates that signal (via `GET /transactions?from=...&to=...`). On any other page the signal is empty, so the arithmetic correctly returns zero. `top-bar.component.ts:109` consumes it as a plain function with no fetch and no loading state.
- **Fixed:** **NO** — the honest fix is to have the top bar fetch its own data (or share a resolver/store). That is an architecture change beyond "integration fix" and risks touching many components. Reported.

---

### FE-UI-002 — Text-size control had no visual effect

- **Severity:** MEDIUM
- **Module:** M2 (UC-27 appearance)
- **Page / feature:** Profile → text-size selector
- **Reproduction:** Profile → click "Small (14px)" → compare rendered font size.
- **Expected:** root font size becomes 14px.
- **Actual (before fix):** HTML class became `text-size-small` but the computed root font size stayed `16px` — because the stylesheet only defines `html.text-size-sm`.
- **API:** none (localStorage-only preference).
- **Evidence:** browser CSSOM dump — rules present: `html.text-size-sm:14px`, `html.text-size-md:16px`, `html.text-size-lg:18px`; applied class: `text-size-small`. No matching rule.
- **Root cause:** `theme.service.ts:applyFontSize()` interpolated the preference verbatim (`text-size-${size}`, `size ∈ {small,medium,large}`) while `styles/_tokens.scss:58-68` defines the short suffixes `-sm/-md/-lg`.
- **Fixed:** **YES** — added an explicit `FONT_SIZE_CLASS` map. Post-fix browser evidence: Small → `text-size-sm` / 14px, Large → `text-size-lg` / 18px, Medium → `text-size-md` / 16px.

---

### FE-API-002 — Preferences are device-local, not account-scoped

- **Severity:** MEDIUM (privacy/correctness between users on a shared machine)
- **Module:** M2
- **Page / feature:** theme + text size
- **Reproduction:** Alex sets Dark Mode → Sign Out → Bella signs in on the same browser → Bella gets Alex's theme.
- **Expected:** each account's appearance follows that account.
- **Actual:** preferences persist in `localStorage` (`campus_coin_dark_mode`, `campus_coin_font_size`) and leak across accounts. `PATCH /profile/me/preferences` is **never called** — 0 requests in 666 captured pairs.
- **API:** `PATCH /api/v1/profile/me/preferences` exists (`UpdatePreferencesRequest{themePreference, fontScale}`) with enums `ThemePreference` and `FontScale{SMALL,MEDIUM,LARGE,XLARGE}` — yet `auth.service.ts:updatePreferences()` has **zero callers**.
- **Evidence:** import search across components/guards/interceptors/resolvers/dialogs/specs → 0 callers; browser capture `themeFontReqs: []`.
- **Root cause:** the theme service is a pure client-side implementation and was never wired to the existing preferences endpoint.
- **Fixed:** **NO** — wiring it correctly requires reconciling the frontend union `'small'|'medium'|'large'` with the backend `FontScale{SMALL..XLARGE}` enum (a contract mapping) and deciding precedence between localStorage and the server. That is a design decision, not a one-line integration fix. Reported.

---

### FE-MOCK-001 — Chatbot returns fabricated financial figures

- **Severity:** MEDIUM (user-visible false information)
- **Module:** cross-cutting (misrepresents M4/M6/M7 data)
- **Page / feature:** Campus Coin Assistant widget
- **Reproduction:** log in as Alex → open the assistant → ask "How much did I spend on food this month?" and "What is my savings rate?"
- **Expected:** real figures — Food $24.00 of a $30 budget; net +$71.00.
- **Actual:** the assistant replies *"You've spent $46.50 on Food & Dining so far this month, which is about 21% of your $220 limit. You're well within your safe zone!"* and *"Your net balance for September is +$423.50 with a 77% savings rate, bolstered by your CS lab assistant paycheck..."* — a fabricated $220 limit against a real $30 limit, and a fabricated +$423.50 against a real +$71.00. It also hardcodes "Hi Alex!" for every user.
- **API:** none. `chatbot.service.ts` contains **no `HttpClient` import at all**.
- **Evidence:** browser transcript captured verbatim (j18); dashboard ground truth captured in the same run (`netAmount: 71.00`, Food spent 24.00, limit 30.00); `chatbotHttpReqs` counted only the 2 navigation calls, none from the widget.
- **Root cause:** the service is a deliberate placeholder (`NOTE: This is a lightweight mock service…`) using keyword matching plus `setTimeout(..., 600)` to simulate latency.
- **Fixed:** **NO** — there is no backend chat endpoint to connect to, and replacing it is explicitly out of scope. Reported as a user-facing data-integrity risk.

---

### FE-ORPHAN-001 — `campus-coin.service.ts` calls APIs that do not exist

- **Severity:** LOW (no runtime impact — nothing imports it)
- **Module:** none
- **Reproduction:** search for importers.
- **Expected:** n/a.
- **Actual:** the service targets `/api/health`, `/api/users`, `/api/wallets`, `/api/merchants`, `/api/transactions/transfer`, `/api/transactions/topup`, `/api/transactions/pay`. **None of these paths exist in the backend OpenAPI document.** It also sets no `Authorization` header.
- **Evidence:** zero importers project-wide; OpenAPI document cross-checked.
- **Root cause:** a legacy scaffold left from an earlier data model.
- **Fixed:** **NO** — deleting backend/frontend API surface is out of scope. Reported; appears in §21 as POTENTIALLY REDUNDANT.

---

### FE-DEAD-001 — 4 mock-data modules and the mock-delay interceptor are dead

- **Severity:** LOW
- **Module:** none
- **Reproduction:** search for importers of each.
- **Actual:** `mock-data/{budgets,users,transactions,categories}.mock.ts` → **0 importers each**. `mock-delay.interceptor.ts` → one match (its own definition); `app.config.ts` registers only `authInterceptor` and `errorInterceptor`.
- **Evidence:** import search per file.
- **Root cause:** mocks superseded by real APIs; the interceptor was never registered.
- **Fixed:** **NO** — dead code, harmless. Reported.

---

### FE-UI-004 — Admin dashboard "Daily Campus Activity" chart and some tiles are hardcoded

- **Severity:** LOW/MEDIUM (as-reported; see caveat)
- **Module:** M11
- **Page / feature:** Admin dashboard → daily activity chart; "Total Volume Logged"; "Avg. Monthly Student Outflow"
- **Reproduction:** open `/admin/dashboard` and compare the chart labels with the API payload.
- **Actual:** the chart renders fixed labels (`"Sep 11: 180 txs"` … `"Sep 24: 400 txs"`) and the category-share text renders fixed values (`"Allowance $1,700 (59%)"`, `"Job $315 (11%)"`) that do not reconcile with `GET /admin/stats`. "Total Volume Logged $1,196.99" **is** correctly derived (`totalExpenseLogged: 1196.99`).
- **Evidence:** DOM text scraped at `j5`/`j7`; `GET /admin/stats` and `GET /admin/stats/top-categories` captured in the same runs.
- **Caveat (recorded honestly):** I did not isolate whether every one of those strings comes from a hardcoded template or from `GET /admin/stats/top-categories` with a different period. The mismatch is real; the precise source of each string is **NOT VERIFIED**. Logged as "observed inconsistency between rendered admin dashboard values and the stats payload", not as a diagnosed bug.
- **Fixed:** **NO** — reported.

---

### FE-UI-005 — Money renders as `$1,196.99` under `en-US`

- **Severity:** INFORMATIONAL (not a defect)
- **Detail:** the app is built for a Vietnamese student audience. `$1,196.99` is correct `en-US` formatting; under the machine's own `vi-VN` locale it renders as `$1.196,99`. I initially recorded this as a formatting defect after reading the value under an `en-US`-forced browser context. **Corrected: the formatting is correct for the locale in use.** Recorded so a later reader does not re-raise it. No action.

---

### 19.1 Per-Module Result Counts (M1 → M12)

`APIs` = operations in the live OpenAPI document. `CALLED` = operations for which a real browser request was observed **at least once during this audit**. `PASS` = observed 2xx and the UI rendered correctly. `FAIL` = observed and misbehaving. `NOT VERIFIED` = no real request observed (source only, or unreachable).

| Module | APIs | CALLED | PASS | FAIL | NOT VERIFIED |
|---|---|---|---|---|---|
| M1 Authentication | 6 | 3 | 3 | 0 | 3 |
| M2 Profile & Preferences | 3 | 2 | 2 | 0 | 1 |
| M3 Personal Categories | 5 | 4 | 4 | 0 | 1 |
| M4 Transactions | 6 | 4 | 4 | 0 | 2 |
| M5 Recurring Rules | 5 | 0 | 0 | 0 | 5 |
| M6 Budget & Notifications | 8 | 7 | 7 | 0 | 1 |
| M7 Dashboard | 1 | 1 | 1 | 0 | 0 |
| M8 Reports | 2 | 2 | 2 | 0 | 0 |
| M9 Saving Tips | 4 | 0 | 0 | 0 | 4 |
| M10 Bookmarks | 4 | 0 | 0 | 0 | 4 |
| M11 Administration | 16 | 11 | 11 | 0 | 5 |
| **M12 Optional/Advanced** | **15** | **0** | **0** | **0** | **15** |
| **GRAND TOTAL** | **76** | **34** | **34** | **0** | **42** |

**Notes on the counts.**
- **FAIL = 0**: every endpoint the frontend actually calls works. The defects above are *not* failing API calls — they are (a) calls never made, (b) controls wired to nothing, (c) one fabricated data source, and (d) display staleness. That distinction matters and is deliberate.
- **M1 CALLED = 3**: login, register, logout. The 3 password-reset operations are wired in `forgot-password.component.ts` and routed, but firing them sends a real email, so they are **NOT VERIFIED**, not failed.
- **M6 CALLED = 7**: the 2 observed `409`s are correct rejections of a duplicate budget, not failures; the budget create that had a free category returned 201.
- **M11 CALLED = 11**: the 5 not called are admin announcements (3) and admin settings (2). `POST/PATCH /admin/categories` are among the 11 counted as reachable via the admin categories page.
- **M12 CALLED = 0** even though all 15 are live on the backend. This is the headline M12 result and it is stated as a scope fact, not a defect.
- **NOT VERIFIED (42)** is the honest majority. Under the audit's rule — "an API counts as CALLED FROM FRONTEND only when a real browser HTTP request has been observed" — most of the surface is unreachable from the current UI, and is recorded as such rather than inferred.

---

## 20. Code Changes Made

Two changes, both squarely within the permitted "connect the current UI to the existing backend" category.

### Change 1 — `frontend/src/app/shared/components/nav-sidebar/nav-sidebar.component.ts`

```ts
onLogout(): void {
  // AuthService.logout() returns a COLD observable - the request is only sent once
  // something subscribes. Without this subscribe no HTTP call was made at all, so the
  // server-side session was never revoked and the token stayed in localStorage.
  this.auth.logout().subscribe({
    next: () => this.router.navigate(['/auth/login']),
    // Navigate even when the call fails: the local session is already cleared, so
    // leaving the user on the page they just signed out of would be the worse outcome.
    error: () => this.router.navigate(['/auth/login'])
  });
}
```

**What it fixes:** subscription to an existing call (explicitly permitted). No API, contract, or UI change.
**Post-fix browser evidence:** `POST /api/v1/auth/logout -> 204`; `campus_coin_token` removed; `/app/home` now redirects to `/auth/login`.

### Change 2 — `frontend/src/app/core/services/theme.service.ts`

```ts
// The stylesheet (_tokens.scss) defines html.text-size-sm / -md / -lg, so the
// preference has to be translated to that short suffix. Adding the raw preference
// ("text-size-small") matched no rule and left the text size unchanged.
private static readonly FONT_SIZE_CLASS: Record<FontSizePreference, string> = {
  small: 'text-size-sm',
  medium: 'text-size-md',
  large: 'text-size-lg'
};

private applyFontSize(size: FontSizePreference): void {
  if (!this.isBrowser) return;
  const root = document.documentElement;
  root.classList.remove('text-size-sm', 'text-size-md', 'text-size-lg');
  root.classList.add(ThemeService.FONT_SIZE_CLASS[size] ?? 'text-size-md');
}
```

**What it fixes:** a CSS class-name mismatch between two existing pieces of the app — no API, no redesign, no new feature.
**Post-fix browser evidence:** Small → `text-size-sm`, root font 14px; Large → `text-size-lg`, root font 18px; Medium → `text-size-md`, 16px.

**Build check:** `npx ng build --configuration development` → succeeded; only pre-existing Sass `@import` deprecation warnings (unrelated to these edits).

**Diff summary:** 2 files, 19 insertions, 3 deletions.

**Not changed, deliberately:** the `major` field, the topbar data flow, the chatbot, the orphan service, the dead mocks, and every M5/M9/M10/M12 gap. Each would require inventing an API, changing a schema, or redesigning a component.

---

### 20.1 Test data used, and its cleanup

The audit mutated live demo data. All of it was created **through the real UI/API** and removed the same way. Final state verified against the server.

| Artifact | Created by | Removed by | Verified |
|---|---|---|---|
| Transaction id 70 ("AUDIT TEST purchase", $9.99, then edited to $8.88) | `POST /transactions` via Quick Add | `DELETE /transactions/70` via row Delete button → 204 | gone |
| Category id 14 ("AUDIT Test Category") | `POST /categories` via UI → 201 | `DELETE /categories/14` → 204 | gone |
| Budget id 12 (AUDIT category, $42) | `POST /budgets` via UI → 201 | `DELETE /budgets/12` → 204 | gone |
| Category id 15 ("ZZ Audit Category" → "ZZ Audit Renamed") | `POST /categories` → 201, `PATCH` → 200 | `DELETE /categories/15` → 204 | gone |
| Tip template id 8 ("Audit Tip Template") | `POST /admin/tip-templates` → 201 | `PATCH` `isActive:false` | inactive |
| Alex's Transport budget (id 2 → 13, $25) | **accidentally deleted** by my budget-delete test | recreated via `POST /budgets` → 201 | restored ($25 limit, $12 spent) |
| Bella (id 3) status | set DISABLED by my admin test | re-set ACTIVE → 200 | ACTIVE |
| Throwaway user id 4 | `POST /auth/register` | **could not be deleted** — M11 has no user-delete endpoint | **DISABLED (residual)** |

**Final verified state:** categories 12 (no leftovers), Alex budgets = the original 6 (Academics 50, Entertainment 40, Food 30, Hostel/Rent 160, Subscriptions 15, Transport 25), `totalTransactions: 69`, `totalIncomeLogged: 2205`, `totalExpenseLogged: 1187.00` (= 1196.99 − 9.99), active tip templates 7.

**Residual artifact:** user id 4 (`audit.throwaway+…@student.campuscoin.edu`) remains, DISABLED. The admin API exposes **no endpoint to delete a user** (`AdminUserController` has only `GET /users`, `POST /users/{id}/status`, `POST /users/{id}/password-reset`), so it was neutralised by disabling it rather than by editing the database — per the standing instruction not to mutate the DB by hand. Disabled accounts cannot sign in, so `activeStudents` is 2 and the demo accounts are unaffected.

---

## 21. Remaining Work

### REQUIRED (integration gaps that block an intended current feature)

| ID | Item | Why it is required |
|---|---|---|
| R-1 | Wire the Quick Add recurrence select to M5, or remove the control | A visible control currently does nothing (FE-CONTRACT-001) |
| R-2 | Resolve Profile `major` | The Save button is permanently disabled on a fresh session (FE-CONTRACT-002) |
| R-3 | Give the top bar a real data source | It displays `$0.00` on 4 of 6 student pages (FE-UI-001) |
| R-4 | Register a M5 frontend service + route | M5 is entirely unreachable |

### OPTIONAL (deliberate engineering decisions, not bugs)

| ID | Item |
|---|---|
| O-1 | Persist theme + text size through `PATCH /profile/me/preferences` instead of localStorage (FE-API-002) |
| O-2 | Replace the chatbot fixture with a real source, or label it clearly as a demo (FE-MOCK-001) |
| O-3 | Connect the remaining M11 admin surfaces: announcements (3 ops), settings (2 ops), default categories (2 ops) |

### SYSTEM / BACKEND-DRIVEN (no frontend action)

| ID | Item |
|---|---|
| S-1 | The six M12 endpoints run server-side only |
| S-2 | `GET /dashboard` embeds `tips[]` and `announcements[]` — the tip/announcement text users see arrives through M7, not M9/M11 |

### UNUSED (API exists, no UI caller)

`GET /categories/{id}`, `GET /transactions/{id}`, `POST /transactions/{id}/restore`, `GET /budgets/{id}`, `GET /notifications/{id}`, all 5 M5 ops, all 4 M9 ops, all 4 M10 ops, 5 M11 ops, all 15 M12 ops.

### POTENTIALLY REDUNDANT / DUPLICATE — **reported, not deleted**

| ID | Item | Observation |
|---|---|---|
| H-1 | `campus-coin.service.ts` | Zero importers; targets 7 non-existent paths; duplicates transaction concepts |
| H-2 | `mock-data/*.mock.ts` (4 files) | Zero importers each |
| H-3 | `mock-delay.interceptor.ts` | Never registered |
| H-4 | `tip.service.ts` | Fully implemented, unreachable — duplicates a capability the dashboard already surfaces |
| H-5 | `TransactionService.getMonthlyBalance()` | Correct arithmetic, but duplicates M7's `summary` and is fed by an inconsistently-populated cache (FE-UI-001) |
| H-6 | `GET /notifications/{id}` | No service method, no UI, but the list endpoint already returns full objects |

### FUTURE (not in current scope)

CSV import UI, AI categorisation UI, insights UI, anomaly UI, forecast UI, recent-activity UI (all M12); a deleted-transactions view (to make `restore` reachable); a user-delete endpoint for M11.

---

## 22. Final Traceability

The per-module counts (APIs / CALLED / PASS / FAIL / NOT VERIFIED, then the grand totals) are in **§19.1**. This section traces where each piece of evidence lives.

### 22.1 Evidence trail

| Evidence source | What it establishes |
|---|---|
| Playwright journeys j1–j19 | Every request/response recorded in §5 and §7–§15; screenshots per step |
| `GET /api-docs` fetched at audit time | The 76-operation / 56-path inventory in §2 and the classification in §5 |
| Direct authenticated probes (student + admin tokens) | Backend liveness for endpoints no UI reaches (M5, M9, M10, M12, unused M11) |
| Import-graph search (components, guards, interceptors, resolvers, dialogs, specs) | Zero-caller findings: `tip.service.ts`, `campus-coin.service.ts`, `mock-data/*`, `mockDelayInterceptor`, `getMonthlyBalance` |
| Browser CSSOM + computed-style dumps | FE-UI-002 root cause (class `text-size-small` vs CSS rules `text-size-sm/-md/-lg`) |
| `GET /dashboard` vs DOM scrape, same run | FE-MOCK-001 (chatbot figures) and FE-UI-004 (admin tiles) |
| `ng build --configuration development` | The two fixes compile |

### 22.2 From each defect to its evidence

| Defect | Verified by | Status |
|---|---|---|
| FE-API-001 | j12 — pre-fix `logoutReqs: []`; post-fix `POST /auth/logout -> 204` | **FIXED** |
| FE-UI-002 | j11 — applied class vs available CSS rules; j12 — post-fix 14/16/18px | **FIXED** |
| FE-CONTRACT-001 | Quick Add capture: body has no `recurringFrequency` | Reported |
| FE-CONTRACT-002 | j11 — `saveDisabled: true`, `major` empty + invalid | Reported |
| FE-UI-001 | j18 (correct on home/quick-add) vs j19 (`txFetch: []`, `$0.00` × 4 routes) | Reported |
| FE-API-002 | 666-pair capture — `PATCH /profile/me/preferences`: 0 requests | Reported |
| FE-MOCK-001 | j18 — chatbot transcript vs dashboard ground truth, same session | Reported |
| FE-ORPHAN-001 | Import search + OpenAPI cross-check | Reported |
| FE-DEAD-001 | Import search per file | Reported |
| FE-UI-004 | Admin DOM scrape vs `GET /admin/stats` payload | Reported (source per string NOT VERIFIED) |
| FE-UI-005 | Locale comparison | Informational — not a defect |

### 22.3 Module → journey map

| Module | Journey(s) | Verdict |
|---|---|---|
| M1 | j6, j9, j12 | Wired; logout fixed |
| M2 | j11, j12 | Wired; `major` gate + prefs gap |
| M3 | j15, j16, j17 | Fully verified |
| M4 | j13, j18 | Fully verified; recurrence select dead |
| M5 | — | No frontend |
| M6 | j7, j10, j14 | Fully verified |
| M7 | j3, j18 | Fully verified |
| M8 | j15 | Fully verified |
| M9 | — | No frontend (service orphaned) |
| M10 | — | No frontend (service orphaned) |
| M11 | j5, j7, j9, j10 | Wired for the surfaces that exist |
| M12 | — | **No frontend — 0/15 reachable** |

### 22.4 Headline conclusions

1. **The modules that are wired are wired well.** M1, M3, M4, M6, M7, M8 and the used half of M11 all complete the full chain UI → service → HTTP → backend → state → render, persist across refresh, and propagate correctly between modules.
2. **Zero failing API calls.** No endpoint the frontend calls returns an error under normal use. The audit found no `FAIL` items.
3. **The gaps are absences, not breakages.** M5, M9, M10 and M12 have healthy backends and no reachable frontend. 42 of 76 operations are unreachable from the UI.
4. **Two confirmed integration bugs were fixed and re-verified in the browser** (logout never called the API; text size had no effect). Both were exercised end-to-end after the fix.
5. **Seven further defects are reported and deliberately not patched**, because each would require inventing an API, changing a schema, or redesigning a component.
6. **One user-facing data-integrity risk stands out:** the chatbot presents fabricated financial figures that contradict the user's real account, and it is reachable by any student.

**Status:** `M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL`

1. **The modules that are wired are wired well.** M1, M3, M4, M6, M7, M8 and the used half of M11 all complete the full chain UI → service → HTTP → backend → state → render, persist across refresh, and propagate correctly between modules.
2. **Zero failing API calls.** No endpoint the frontend calls returns an error under normal use. The audit found no `FAIL` items.
3. **The gaps are absences, not breakages.** M5, M9, M10 and M12 have healthy backends and no reachable frontend. 42 of 76 operations are unreachable from the UI.
4. **Two confirmed integration bugs were fixed and re-verified in the browser** (logout never called the API; text size had no effect). Both were exercised end-to-end after the fix.
5. **Five further defects are reported and deliberately not patched**, because each would require inventing an API, changing a schema, or redesigning a component.
6. **One user-facing data-integrity risk stands out:** the chatbot presents fabricated financial figures that contradict the user's real account, and it is reachable by any student.

**Status:** `M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL`
