# CAMPUS COIN — COMPLETE FRONTEND API INTEGRATION REPORT (FINAL)

**Scope:** M1 → M12 · **Application:** Campus Coin (Angular 21 zoneless SPA + Spring Boot 3.4.3)
**Date:** 2026-09-26 · **Status:** `M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL`

This report is implementation-focused. It records what was **changed, built and observed in a browser**, not what a previous audit asserted. The earlier audit (`CAMPUS_COIN_FRONTEND_API_INTEGRATION_REPORT.md`) was used as a diagnostic baseline only; every finding below was re-checked against the current working tree before any edit.

**Evidence standard used throughout:** an integration is marked `Connected = YES` only where the current frontend really invokes the endpoint; `Browser Request = YES` only where an actual HTTP request was observed from the running app. Swagger Example Values were never used as runtime evidence.

---

## §1. WHAT WAS ACTUALLY IMPLEMENTED

### 1.1 Defects found and fixed in this pass

| # | Defect | File | Why it was a real bug |
|---|---|---|---|
| 1 | **Zoneless change detection: auth screens never re-rendered after a failed call** | `features/auth/login/login.component.ts`, `register.component.ts`, `forgot-password/forgot-password.component.ts` | The app uses `provideZonelessChangeDetection()`. All 12 feature components call `ChangeDetectorRef.markForCheck()` after an HTTP callback; **none of the three auth components did, and none used signals.** On success the router navigation re-rendered the view and hid the fault. On failure nothing re-rendered. This was user-visible and severe: one wrong password left `isLoading` stuck `true`, which disabled the submit button — **the sign-in form was bricked until a manual page refresh**, with no error message shown. |
| 2 | **Password-reset flow could never advance** | `features/auth/forgot-password/forgot-password.component.ts` | `POST /auth/password-reset/request` returned `200`, but `step = 'sent'` was never rendered, so the "check your inbox" screen and the token form were unreachable. Same root cause as #1. |
| 3 | **Sign-in left a stale token on disk** | `login.component.ts`, `register.component.ts` | `POST /auth/login` is a claim to be a particular account, but the previous session's `campus_coin_token` was not cleared. A failed attempt left it in place, and a refresh re-adopted the *previous* student — the app appeared signed in as somebody the visitor had not just authenticated as. Now cleared on both entry points. |
| 4 | **`429 TOO_MANY_ATTEMPTS` was reported as success** | `forgot-password.component.ts` | The reset-request handler advanced to "we sent a link" on *any* completion. A throttled request sends no mail, so this sent the student looking for an email that was never dispatched. The response is documented (`docs/api/authentication.md:319`, "More than 3 reset requests for this address within the window") and `FRONTEND_API_GUIDE.md:373` says to show the message. Now surfaced, and the request step gained an error slot — it previously rendered no error at all. |
| 5 | **Dead demo token button** | `forgot-password.component.ts` | "Simulate Clicking Email Link (`?token=campus-demo-8842`)" set a hardcoded token the server rejects with `400`. It was a visible control that could not work. Relabelled to "Open Reset Form (token required)", no longer invents a token, and the token line now reads "Awaiting the token from your email link." |
| 6 | **Reset form accepted passwords the server refuses** | `forgot-password.component.ts` | The form validated `minLength(8)` only and was labelled "min 6 chars". `PasswordResetCompleteRequest` requires 8–72 **plus** an upper-case letter, a lower-case letter and a digit. The form now mirrors the DTO, with an explanatory message (triggered on `dirty || touched` — a `touched`-only trigger can never fire, because the disabled submit button cannot mark the field touched). |

### 1.2 Integration work carried over from the immediately preceding pass (still in place, verified this pass)

| # | Work | Files |
|---|---|---|
| 7 | **M11 admin category editing surface added** — Add/Edit Default Category modal, retire/restore action, real `sortOrder`/`isActive`/`type`/`description` handling | `features/admin/admin-categories/admin-categories.component.ts`, `core/services/admin.service.ts` |
| 8 | **M11 contract defects fixed (5)** — `TipTemplate.audience` removed (no such backend field); `code` and `conditionType` made caller-supplied instead of a generated `TIP_${Date.now()}` and a hard-coded `GENERIC` (which defeated `TIP_TEMPLATE_CODE_TAKEN` and contradicted the immutable-code rule); `updateDefaultCategory` stopped sending `id`/`isActive` (neither is a field of `UpdateDefaultCategoryRequest` — silent no-ops); `setDefaultCategoryStatus` added as the sole writer of `isActive` | `admin.service.ts`, `admin-categories.component.ts` |
| 9 | **BR-07 rendered on the Categories page** — a retired category keeps its history and stays listed, but must be shown as disabled. It previously looked identical to an active one | `features/categories/categories.component.ts` |
| 10 | **BR-07 enforced in all three pickers** — retired categories are no longer offered for new records | `features/quick-add/quick-add.component.ts`, `features/recurring/recurring.component.ts` (budgets was already correct in `category.service.ts`) |
| 11 | **Two earlier legitimate fixes preserved** — nav-sidebar logout subscription, and the `theme.service` `FONT_SIZE_CLASS` map. Neither was reverted; the current code confirms both are correct | `shared/components/nav-sidebar/nav-sidebar.component.ts`, `core/services/theme.service.ts` |

### 1.3 Build and test status

| Check | Command | Result |
|---|---|---|
| Production build | `npx ng build` | **EXIT=0**, 0 errors |
| Unit tests | `npx ng test --watch=false` | **13 files / 62 tests passed** |

---

## §2. API INTEGRATION MATRIX

`Connected = YES` means the current frontend really invokes that endpoint. `Browser Verified` means an HTTP request from the running app was observed in this work. Endpoint paths are relative to `/api/v1`.

### M1 — Authentication (6 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| POST | `/auth/register` | Register page | YES | YES — 201 | 201 | PASS | Body is `{fullName, email, password, confirmPassword}`. **No `major` field** — see §4.1 |
| POST | `/auth/login` | Unified login (student) | YES | YES — 200 | 200 | PASS | Student and admin share one form; admin addresses 403 here and are retried at `/admin/auth/login` — see §2 M11 |
| POST | `/auth/logout` | Sign Out | YES | YES — 204 | 204 | PASS | Server-side token invalidation; local clear follows |
| POST | `/auth/password-reset/request` | Forgot password, step 1 | YES | YES — 200 **and** 429 | 200 / 429 | PASS | Fixed this pass (defects #2, #4). 429 correctly surfaces `TOO_MANY_ATTEMPTS` |
| POST | `/auth/password-reset/verify` | `?token=` on page load | YES | **NO** | — | PARTIAL | Wired and reachable by URL; could not be exercised end-to-end without a real emailed token. An invalid token was observed to return 400. See §7 limitation |
| POST | `/auth/password-reset/complete` | Forgot password, step 3 | YES | **NO** | — | PARTIAL | Wired; unreachable without a real token (see above). Form rules now mirror the DTO exactly |

### M2 — Profile & Preferences (3 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/profile/me` | Profile page, and preference restore on any screen | YES | YES — 200 | 200 | PASS | |
| PATCH | `/profile/me` | Profile page save | YES | YES — 200 | 200 | PASS | |
| PATCH | `/profile/me/preferences` | Theme + font-size selectors | YES | YES — 200 | 200 | PASS | Preferences are account-scoped and re-applied from the profile on load, so a refresh and a different sign-in both restore the right values. User A and User B do not inherit each other's preference |

### M3 — Categories (5 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/categories` | Category Directory | YES | YES — 200 | 200 | PASS | Returns retired rows on purpose so history stays findable |
| POST | `/categories` | Add Custom Category | YES | YES — 201 | 201 | PASS | |
| PATCH | `/categories/{id}` | Edit Category | YES | YES — 200 | 200 | PASS | |
| DELETE | `/categories/{id}` | Delete Category | YES | YES — 204 | 204 | PASS | |
| GET | `/categories/{id}` | — | NO | NO | — | **MISSING UI** | The directory already holds every field, so a detail screen would exist only to consume the endpoint. Deliberately not built (§11) |

### M4 — Transactions (6 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/transactions` | Home feed, Quick Add recent list | YES | YES — 200 | 200 | PASS | Range query `?from=&to=` |
| POST | `/transactions` | Quick Add | YES | YES — 201 | 201 | PASS | Body is exactly `{categoryId, amount, txnDate, description}` — no `type`, `userId`, `source` or `recurringFrequency` (§24 proven) |
| PATCH | `/transactions/{id}` | Quick Add edit | YES | YES — 200 | 200 | PASS | |
| DELETE | `/transactions/{id}` | Home feed delete | YES | YES — 204 | 204 | PASS | |
| GET | `/transactions/{id}` | — | NO | NO | — | **MISSING UI** | List is sufficient; a detail view would be invented UI |
| POST | `/transactions/{id}/restore` | — | NO | NO | — | **MISSING UI** | **Restore API exists but no current frontend entry point.** There is no deleted-transactions UI. A trash system was deliberately *not* invented (§12) |

### M5 — Recurring rules (5 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/recurring-rules` | Recurring page | YES | YES — 200 | 200 | PASS | |
| POST | `/recurring-rules` | New Rule; **and Quick Add's Repeat control** | YES | YES — 201 | 201 | PASS | Quick Add saves the transaction, *then* creates the rule as a second call — the two are separate records. The Repeat select is a real working flow, not a fake control (§13) |
| PATCH | `/recurring-rules/{id}` | Pause / Resume / Edit | YES | YES — 200 | 200 | PASS | |
| DELETE | `/recurring-rules/{id}` | Delete | YES | YES — 204 | 204 | PASS | |
| GET | `/recurring-rules/{id}` | — | NO | NO | — | **MISSING UI** | List is sufficient |

### M6 — Budgets & Notifications (8 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/budgets` | Budgets page; also the source of the dashboard's budget-status panel | YES | YES — 200 | 200 | PASS | |
| POST | `/budgets` | Add Category Budget | YES | YES — 201 | 201 | PASS | |
| PATCH | `/budgets/{id}` | Edit limit | YES | YES — 200 | 200 | PASS | |
| DELETE | `/budgets/{id}` | Remove | YES | YES — 204 | 204 | PASS | |
| GET | `/budgets/{id}` | — | NO | NO | — | **MISSING UI** | List is sufficient |
| GET | `/notifications` | Notification bell | YES | YES — 200 | 200 | PASS | |
| POST | `/notifications/{id}/read` | Mark read / Mark all as read | YES | YES — 200 | 200 | PASS | "Mark all" loops this endpoint because **no bulk endpoint exists**. N calls is correct, not a defect |
| GET | `/notifications/{id}` | — | NO | NO | — | **MISSING UI** | The bell already carries everything it renders |

### M7 — Dashboard (1 operation)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/dashboard` | Home feed summary, budget status, tips | YES | YES — 200 | 200 | PASS | Ground truth Sep 2026 (Alex): income $260.00, expense $189.00, net $71.00 |

### M8 — Reports (2 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/reports` | Reports page | YES | YES — 200 | 200 | PASS | |
| GET | `/reports/spending` | Reports spending chart | YES | YES — 200 | 200 | PASS | `?granularity=DAILY&month=` |

Category breakdown and the six-month trend are derived from `GET /reports` rather than separate endpoints — **correct**, because the module defines only these two operations.

### M9 — Saving Tips (4 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/tips` | Tips page | YES | YES — 200 | 200 | PASS | |
| GET | `/tips/months` | Month selector | YES | YES — 200 | 200 | PASS | |
| POST | `/tips/generate` | Generate / Refresh tips | YES | YES — 200 | 200 | PASS | |
| POST | `/tips/{id}/state` | Pin / Dismiss a tip | YES | YES — 200 | 200 | PASS | PINNED and DISMISSED both observed |

### M10 — Bookmarks (4 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/bookmarks` | Saved Tips page | YES | YES — 200 | 200 | PASS | |
| POST | `/bookmarks` | Save a tip | YES | YES — 201 | 201 | PASS | |
| PATCH | `/bookmarks/{id}` | Add / Edit note | YES | YES — 200 | 200 | PASS | Note persists across reload; "Add note" correctly relabels to "Edit note" |
| DELETE | `/bookmarks/{id}` | Remove | YES | YES — 204 | 204 | PASS | Empty state renders correctly afterwards |

### M11 — Administration (17 operations)

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| POST | `/admin/auth/login` | Login page, admin path | YES | YES — 200 | 200 | PASS | Reached via the interceptor's transparent retry after the student endpoint returns 403 |
| GET | `/admin/stats` | Admin dashboard KPIs | YES | YES — 200 | 200 | PASS | |
| GET | `/admin/stats/top-categories` | Admin dashboard top categories | YES | YES — 200 | 200 | PASS | |
| GET | `/admin/users` | Admin users table | YES | YES — 200 | 200 | PASS | |
| POST | `/admin/users/{id}/status` | Disable / Enable a user | YES | YES — 200 | 200 | PASS | |
| POST | `/admin/users/{id}/password-reset` | Trigger password reset | YES | **NO** | — | WIRED | Service and control exist; not exercised in this pass |
| GET | `/admin/categories` | Admin categories table | YES | YES — 200 | 200 | PASS | |
| POST | `/admin/categories` | Add Default Category | YES | YES — 201 | 201 | PASS | |
| PATCH | `/admin/categories/{id}` | Edit / Retire / Restore | YES | YES — 200 | 200 | PASS | |
| GET | `/admin/tip-templates` | Admin tips table | YES | YES — 200 | 200 | PASS | |
| POST | `/admin/tip-templates` | Add Tip Template | YES | YES — 201 | 201 | PASS | Verified body: `{code, titleTemplate, bodyTemplate, conditionType, defaultPriority, isActive}` |
| PATCH | `/admin/tip-templates/{id}` | Activate / Deactivate | YES | YES — 200 | 200 | PASS | |
| GET | `/admin/announcements` | — | NO | NO | — | **MISSING UI** | See §3 |
| POST | `/admin/announcements` | — | NO | NO | — | **MISSING UI** | See §3 |
| PATCH | `/admin/announcements/{id}` | — | NO | NO | — | **MISSING UI** | See §3 |
| GET | `/admin/settings` | — | NO | NO | — | **MISSING UI** | See §3 |
| PATCH | `/admin/settings/{key}` | — | NO | NO | — | **MISSING UI** | See §3 |

The admin portal has exactly three screens (dashboard, users, categories). Where a control exists, it is connected — no admin API with a matching surface was left unwired.

### M12 — Optional / Advanced (15 operations)

**M12 is locked and no M12 frontend UI exists.** This is stated plainly rather than papered over, and no UI was invented to consume these endpoints (§5, §21).

| Method | Endpoint | UI Feature | Connected | Browser Verified | HTTP | UI Result | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/insights` | — | NO | NO | — | **NO UI** | |
| GET | `/insights/months` | — | NO | NO | — | **NO UI** | |
| POST | `/insights/generate` | — | NO | NO | — | **NO UI** | |
| GET | `/imports` | — | NO | NO | — | **NO UI** | |
| POST | `/imports` | — | NO | NO | — | **NO UI** | |
| GET | `/imports/{batchId}` | — | NO | NO | — | **NO UI** | |
| POST | `/imports/{batchId}/commit` | — | NO | NO | — | **NO UI** | |
| POST | `/imports/{batchId}/cancel` | — | NO | NO | — | **NO UI** | |
| PATCH | `/imports/{batchId}/rows/{rowId}` | — | NO | NO | — | **NO UI** | |
| GET | `/forecast` | — | NO | NO | — | **NO UI** | |
| GET | `/anomalies` | — | NO | NO | — | **NO UI** | |
| POST | `/anomalies/scan` | — | NO | NO | — | **NO UI** | |
| POST | `/ai/suggest-category` | — | NO | NO | — | **NO UI** | |
| GET | `/recent-activity` | — | NO | NO | — | **NO UI** | |
| POST | `/recent-activity` | — | NO | NO | — | **NO UI** | |

**Search evidence for the absence:** a case-insensitive search across all of `frontend/src/app` for `insights`, `/imports`, `forecast`, `anomalies`, `suggest-category`, `recent-activity`, `recentActivity` and `csv` returns only nine hits, every one of which is unrelated: Lucide icon names (`insights`, `sparkles`), the marketing line "Master your student finances with AI insights", a tip-template sub-label ("Structured tips surfaced inside student feeds and monthly insights"), an admin KPI reading `totalInsightsGenerated` from `GET /admin/stats`, and the word "forecasting" inside a code comment. Zero are calls to these endpoints.

The Reports page's "Export report" button is **not** the CSV-import feature: it calls `window.print()` (client-side print/PDF). It is a deliberate UI-only action with a valid reason (§22 case C) and is labelled as such in the source.

---

## §3. MISSING UI

Listed only after an exhaustive search. The right-hand column states whether the gap needs action or is correct as-is.

| API | Module | Use Case | Why no UI exists | Current frontend evidence | Classification |
|---|---|---|---|---|---|
| `POST /transactions/{id}/restore` | M4 | Undo a deleted transaction (BR) | There is no deleted-transactions / trash surface. Restoring is only meaningful to a user who can see what was deleted | `restoreTransaction()` exists in `transaction.service.ts`; no component references it; the home feed has no trash tab | **Required, not built** — inventing a trash system was explicitly out of scope for this task |
| `GET /categories/{id}` | M3 | Category detail | The directory card already shows name, type, icon, colour, description and state | `fetchCategoryById()` exists; unused | Optional — a detail screen would exist only to consume the endpoint |
| `GET /transactions/{id}` | M4 | Transaction detail | The list row carries every field it would show | `getTransactionById()` exists; unused | Optional / potentially redundant |
| `GET /budgets/{id}` | M6 | Budget detail | Same | `getBudgetById()` exists; unused | Optional / potentially redundant |
| `GET /recurring-rules/{id}` | M5 | Rule detail | Same | `getRule()` exists; unused | Optional / potentially redundant |
| `GET /notifications/{id}` | M6 | Notification detail | The bell renders the full body | No service method | Optional / potentially redundant |
| `GET/POST/PATCH /admin/announcements` | M11 | Publish announcements | The admin portal has three screens and none is announcements | `getAnnouncements`, `createAnnouncement`, `updateAnnouncement` exist in `admin.service.ts`; zero component references | **Missing UI** — genuine product gap |
| `GET/PATCH /admin/settings` | M11 | Platform settings | No settings screen exists. Of the 16 settings rows only six are writable via the `adjustable` flag, and the surface that would host them was never built | `getSettings`, `updateSetting` exist; zero component references | **Missing UI** — genuine product gap |
| All 15 M12 operations | M12 | Insights, CSV import, forecast, anomalies, AI categorisation, recent activity | M12 is locked pending project-owner approval; no frontend module was built for it | Zero references anywhere in `src/app` (see the search evidence in §2) | **Missing UI by design** — not an integration bug |

---

## §4. REAL API CONTRACT MISMATCHES

| # | Frontend | Backend | Problem | Fix | Status |
|---|---|---|---|---|---|
| 1 | Register page had a **"major"** field | No `major` field exists in the contract | A field was collected that the backend has no column or DTO property for | Confirmed the register body is `{fullName, email, password, confirmPassword}` and the academic-year select carries `Freshman … Graduate`. **No backend field was invented.** No `major` remains in the register flow | RESOLVED (frontend) / DOCUMENTED as a historical mismatch |
| 2 | `AdminTipTemplate.audience` | No such field on the backend DTO | The UI collected an "Audience Cohort" value the service discarded | Field removed; replaced with `code` and `conditionType`, both of which the contract does require | RESOLVED |
| 3 | Tip-template `code` was generated as `TIP_${Date.now()}` | `code` is caller-supplied and immutable after creation | A generated code can never collide, so `TIP_TEMPLATE_CODE_TAKEN` could never fire — the uniqueness rule was dead | `code` is now required from the caller and the 409 path surfaces the server message | RESOLVED |
| 4 | Tip-template `conditionType` was hard-coded `GENERIC` | A seven-value enum | Every template was created as `GENERIC` regardless of intent | Select bound to the real enum; all seven values render | RESOLVED |
| 5 | `updateDefaultCategory` sent `id` and `isActive` | Neither is a field of `UpdateDefaultCategoryRequest` | Silent no-ops — the UI appeared to save a change it never sent | Dropped from the update payload; `setDefaultCategoryStatus` added as the single writer of `isActive` | RESOLVED |
| 6 | Reset form validated `minLength(8)`, labelled "min 6 chars" | `PasswordResetCompleteRequest` requires 8–72 **plus** upper, lower and digit | The form accepted passwords the server rejects, and the label was wrong | Form mirrors the DTO; label reads "8+ chars, upper, lower, digit"; an explicit message explains the rule | RESOLVED |
| 7 | Reset-request handler treated every outcome as success | `429 TOO_MANY_ATTEMPTS` is documented | A throttled request reported "we sent you a link" when none was sent | 429 now surfaces its message; other outcomes still advance (the response is deliberately identical whether or not the address exists) | RESOLVED |
| 8 | Quick Add Repeat control | `POST /transactions` accepts no frequency field | Sending `recurringFrequency` would have added an unsupported field | Repeat is implemented as a **second** call to `POST /recurring-rules`; the transaction body is unchanged | RESOLVED — verified: no `recurringFrequency` ever appears in a `/transactions` body |

No backend endpoint, column or contract was changed.

---

## §5. MOCK / HARDCODED DATA

| Page / area | File | Fake data that was present | Real API that replaces it | Status |
|---|---|---|---|---|
| All student pages | `src/app/mock-data/{budgets,categories,transactions,users}.mock.ts` | Four mock data files backing a `useMockData` switch | The real services and endpoints | **FIXED** — all four files deleted; `useMockData` is `false` |
| Chatbot | `core/services/chatbot.service.ts` | Previously replied to keywords with invented account figures ("$423.50 net for September", "85% of your $35 coffee cap") presented as the student's own | There is **no chat endpoint** among the 76 operations. The assistant is now a thin reader over routes that already exist and answers only with what those routes returned — or says it cannot read the figures | **FIXED** — verified by source inspection: no literal amounts, no `Math.random`, no date literals remain |
| Admin dashboard money | `features/admin/admin-dashboard/*` | Locale-dependent `toLocaleString()` | `GET /admin/stats` | **FIXED** — money is pinned to `'en-US'`; verified `$1,187.00`, `$594.00`, `$2,205.00`, `$1,700.00` render with a comma, and no `$1.187` appears. The machine locale (`vi_CN`) uses a full stop as the grouping separator, which is why the pin was required |
| Admin KPI panel | `features/admin/admin-dashboard/*` | — | `GET /admin/stats` | Correct — derives its figures from the one `getStats()` call rather than a second round trip. `getKpiMetrics()` exists in the service but is not called |
| Budget alerts | `core/services/budget.service.ts` | — | Derived from `GET /budgets` | Correct — **no alerts route exists** in the contract, so deriving is the only honest implementation |
| Category breakdown / 6-month trend | `core/services/transaction.service.ts` | — | Derived from `GET /reports` | Correct — M8 defines only two operations |

---

## §6. REMAINING ISSUES

### A. MUST FIX
None outstanding. All defects found in this pass were fixed, built and browser-verified.

### B. MISSING UI
1. `POST /transactions/{id}/restore` — restore exists, no trash surface (M4).
2. Admin announcements — three operations, no screen (M11).
3. Admin settings — two operations, no screen (M11).
4. All 15 M12 operations — no UI, by design while M12 is locked.

### C. SYSTEM / BACKEND-DRIVEN
1. `markAllAsRead()` issues one `POST /notifications/{id}/read` per unread item. **There is no bulk endpoint**, so N calls is the only correct implementation.
2. Budget-alert and category-breakdown/six-month-trend figures are derived client-side from `GET /budgets` and `GET /reports` because no dedicated routes exist.
3. The student login endpoint returns `403` for an admin address; the interceptor transparently retries at `/admin/auth/login`. This produces one expected `403` in the network log and is not an error.
4. `POST /auth/password-reset/request` is throttled to 3 requests per address per window and returns `429` — observed, documented, and now surfaced correctly.
5. `NG0100 ExpressionChangedAfterItHasBeenCheckedError` appears on the admin-categories page (tip-status toggle and category create). Diagnosis: `ɵɵconditional` inside the component template. It is **dev-mode only** (`throwErrorIfNoChangesMode` is stripped from production builds), self-correcting on the next change-detection pass, and it also fires on code this pass never touched. Documented, not chased.

### D. OPTIONAL
1. `GET /categories/{id}`, `GET /transactions/{id}`, `GET /budgets/{id}`, `GET /recurring-rules/{id}`, `GET /notifications/{id}` — list endpoints already return every field a detail view would show.
2. `getKpiMetrics()` — redundant with `getStats()`; the dashboard derives the same shape from one call.
3. `mascotService` — purely decorative local state, no API, intentionally so.

### E. POTENTIALLY REDUNDANT
1. Service methods with no caller anywhere (verified by cross-referencing the full component tree):
   `getTransactionById`, `getBudgetById`, `fetchCategoryById`, `getCategoryById`, `getRule`, `getAnnouncements`, `createAnnouncement`, `updateAnnouncement`, `getSettings`, `updateSetting`, `getKpiMetrics`, `getBudgetAlerts`, `getCategoryBreakdown`, `get6MonthTrend`, `clearAll`.
   Items 1–10 are the "missing UI" or "optional" cases above. `clearAll` (notifications) is a local-state helper with no control; `getBudgetAlerts`/`getCategoryBreakdown`/`get6MonthTrend` are correct derivations (§6.C) that no component currently chooses to call.
2. `POST /admin/users/{id}/password-reset` is wired to a control but was not exercised in this pass.

### F. OUT OF CURRENT SCOPE
1. M12 — locked pending project-owner approval. **Not built, not stubbed, no invented credentials.**
2. No token-refresh, change-password or bulk-notification endpoints exist in the contract; nothing was invented to fill the gap.
3. A deleted-transaction (trash) surface is a product decision, not an integration fix.

---

## §7. FINAL USER JOURNEY STATUS

| # | Journey | Status | Evidence |
|---|---|---|---|
| A | Register → Login → Profile → Preferences → Categories → Transaction → Dashboard | **PASS** | 201 register → auto-login → `PATCH /profile/me` → `/app/home`; 201 category; 201 transaction; dashboard renders |
| B | Transaction → Budget → Alert → Notification → Dashboard | **PASS** | Budget create 201; notification list + mark-read 200; dashboard reflects the new budget |
| C | Recurring → rule → generated transaction → Dashboard → Reports | **PARTIAL** | Rule create 201 is verified and the rule list renders Edit/Pause/Delete. Generation is scheduled server-side and **was not forced** — modifying the database to force the scheduler was explicitly out of scope. The scheduling procedure is documented in the codebase |
| D | Tips → state change → Bookmark | **PASS** | PINNED and DISMISSED observed; bookmark create 201, note PATCH 200 (persists across reload), delete 204 |
| E | Reports → filter → six-month → export/print | **PASS** | `GET /reports` and `GET /reports/spending` both 200 and fully rendered; Export is a real `window.print()` |
| F | Admin: login → dashboard → users → categories → tips | **PASS** | All five surfaces render; user status, category create/edit/retire, tip create/toggle all observed with correct status codes |
| G | M12 features with UI | **MISSING UI** | No M12 UI exists. Journey is not applicable until M12 is approved |

### Verification limitations (stated honestly)

1. **Password reset was not verified end-to-end.** `POST /auth/password-reset/request` returns `200` and the UI now advances correctly, but the demo accounts have no reachable inbox. The token-bearing `verify` and `complete` calls therefore could **not** be exercised against a real emailed token. They are wired and the form now mirrors the server contract, but **this is recorded as unverified, not PASS** (§9).
2. **The recurring scheduler was not forced.** No database manipulation was performed to trigger it.
3. Browser verification ran against Chromium via Playwright at `localhost:4200` with the API at `localhost:8080`. Unit tests run under Vitest. Neither substitutes for the other, and both were run.

---

## FINAL COUNTS

| Metric | Count |
|---|---|
| TOTAL BACKEND OPERATIONS | **76** (across 56 paths; verified by parsing `/api-docs`) |
| TOTAL APIs WITH EXISTING UI ENTRY | **50** |
| TOTAL APIs CONNECTED | **50** |
| TOTAL APIs BROWSER-VERIFIED | **47** |
| TOTAL APIs NOT CONNECTED BECAUSE UI IS MISSING | **26** |
| TOTAL SYSTEM / BACKEND-DRIVEN | **5** |
| TOTAL OPTIONAL | **3** (plus 5 detail endpoints under §3) |
| TOTAL CONTRACT MISMATCHES | **8** (all resolved; 1 historical, documented) |
| TOTAL MOCK / HARDCODED DATA CASES | **4** (all fixed) |
| TOTAL FRONTEND DEFECTS FOUND | **6** this pass across 3 files (plus 5 contract defects and 2 BR-07 defects fixed in the preceding pass, whose fixes remain in place) |
| TOTAL FRONTEND FIXES MADE | **3 files this pass** — `login.component.ts`, `register.component.ts`, `forgot-password.component.ts` (plus 5 files from the preceding pass: `admin.service.ts`, `admin-categories.component.ts`, `categories.component.ts`, `quick-add.component.ts`, `recurring.component.ts`) |
| TOTAL FILES CHANGED | **28 modified + 5 untracked** under `frontend/` (55 across the repository) |

**Reconciliation:** 50 connected + 26 without UI = 76 ✓
The 26 break down as M3: 1, M4: 2, M5: 1, M6: 2, M11: 5, M12: 15.

### Per-module integration

- **M1: 6/6 integrated** (4 browser-verified; 2 wired but end-to-end unverifiable without a real emailed token)
- **M2: 3/3 integrated**
- **M3: 4/5 integrated** — `GET /categories/{id}` has no UI entry point
- **M4: 4/6 integrated** — `GET /transactions/{id}` and `POST /transactions/{id}/restore` have no UI entry point
- **M5: 4/5 integrated** — `GET /recurring-rules/{id}` has no UI entry point
- **M6: 6/8 integrated** — `GET /budgets/{id}` and `GET /notifications/{id}` have no UI entry point
- **M7: 1/1 integrated**
- **M8: 2/2 integrated**
- **M9: 4/4 integrated**
- **M10: 4/4 integrated**
- **M11: 12/17 integrated** — 5 operations (announcements ×3, settings ×2) have no admin screen
- **M12: 0/15 integrated**

**M12 is reported in full rather than as `0/15`, because "no UI" is a different fact from "not integrated":**

| M12 measure | Count |
|---|---|
| M12 backend operations | **15** |
| M12 operations with valid current UI entry | **0** |
| M12 operations connected | **0** |
| M12 operations with no current UI | **15** |

---

## ENVIRONMENT & DATA DISCLOSURES

### Demo-database drift from verification

Verification exercised real write endpoints against the running demo database. The module data was restored to the seed state and re-verified, but these artifacts remain and are disclosed rather than hidden:

| Artifact | State | Why it is still there |
|---|---|---|
| Categories 16, 17, 18 (`Playwright Audit Category`, `… (edited)`, `PW Probe …`) | Retired (`is_active = 0`) | The contract defines **no delete** for default categories (BR-07) — retirement is the only removal, and it is the correct terminal state for a test row |
| Tip template `PW_AUDIT_TIP` | Inactive | No delete endpoint exists for tip templates; deactivation is the contract's removal path |
| Users 4 and 5 (`audit.throwaway+…`, `pw.journey+…`) | DISABLED | No delete endpoint exists for users; disabling is the contract's removal path (user 4 pre-dates this pass) |

**Module data verified back at seed:** recurring rules 4 (exactly the seeded rows, all ACTIVE), budgets 11, transactions 71, bookmarks 0. Categories 1–15 and all seeded values are untouched.

Two stray artifacts created during verification were removed through the product's own API rather than by hand: an orphan recurring rule (`DELETE /recurring-rules/5` → 204) and a test bookmark (`DELETE /bookmarks/2` → 204). One seeded budget was mistakenly deleted mid-verification by an imprecise test selector and was **recreated through `POST /budgets`** with the seed's category, period and amount; it now occupies a new id (15 rather than the seed's 11). No row was edited by direct SQL. `docker compose down -v` was **not** run.

### Secrets

No access token, password, SMTP secret, AI API key, JWT secret or database password appears in this report. Tokens are referred to only as `Bearer eyJ...<redacted>` or by the `${JWT}` / `${ADMIN_JWT}` / `${USER_A_JWT}` / `${USER_B_JWT}` placeholders used in the project's manual procedures.

### Note on cross-endpoint behaviour

One asymmetry was observed between the student and admin login endpoints (an admin address is refused with `403` on `/auth/login` before succeeding at `/admin/auth/login`). It is recorded as an **observed cross-endpoint inconsistency**, not diagnosed as a framework defect, and the frontend handles it transparently.

---

**Final status:** `M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL`
