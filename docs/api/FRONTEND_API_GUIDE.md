# Campus Coin — Frontend API Guide

**This is the first document to read before wiring the Angular client to the backend.**

It is the single entry point for the M1–M11 HTTP contract: base URL, authentication, the interceptor
to install, the error contract every endpoint shares, the ownership rule, the enum values, a
complete per-endpoint reference, the integration flows screen by screen, and one master table of all
61 operations.

Everything here was verified against the **current working tree** — the Java controllers and DTOs,
`SecurityConfig`, `ErrorCode`, and the live OpenAPI document at `/api-docs` — not against an earlier
draft. Where the OpenAPI annotations and the running server disagree, the section says so and
follows the server.

| | |
|---|---|
| **Contract scope** | Modules 1–11 |
| **Endpoints** | **61 operations on 43 paths** |
| **OpenAPI** | `/api-docs` (OpenAPI 3.1.0) · Swagger UI at `/swagger-ui.html` |
| **API base path** | `/api/v1` |
| **Status** | M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL |

**Module 12 is locked.** Several enums in this guide carry members that belong to module 12
(`INSIGHT`, `INSIGHT_READY`, `LOW_SAVINGS_RATE`). Those members exist in the database `ENUM` and
appear in the OpenAPI schema, but **no endpoint serves them**. Where that matters the section says
so. There are no insight, chatbot or AI endpoints, and none should be built against this contract.

---

## Contents

- [1. Base URL and environments](#1-base-url-and-environments)
- [2. Authentication](#2-authentication)
- [3. Angular HTTP interceptor](#3-angular-http-interceptor)
- [4. Common error contract](#4-common-error-contract)
- [5. Data ownership rule](#5-data-ownership-rule)
- [6. Enum reference](#6-enum-reference)
- [7. Endpoint reference by module](#7-endpoint-reference-by-module)
- [8. Frontend integration flows](#8-frontend-integration-flows)
- [9. Master quick-reference table](#9-master-quick-reference-table)

---

## 1. Base URL and environments

| Environment | What the Angular app calls | Backend it reaches |
|---|---|---|
| Development (`npm start`) | `/api/v1/...` | `http://localhost:8080` — via `frontend/proxy.conf.json`, which proxies the `/api` prefix |
| Development (direct) | `http://localhost:8080/api/v1/...` | Same server, no proxy |
| Production | `/api/v1/...` | Same origin; TLS terminates at the reverse proxy |

`frontend/src/environments/environment.development.ts` sets `apiUrl: 'http://localhost:8080/api'`
and `environment.ts` sets `apiUrl: '/api'`. **Both already end in `/api`, so a service must append
`/v1/...`**, not `/api/v1/...` again. The server's own base path is `/api/v1`; the proxy passes
`/api` through unchanged.

```ts
// environment.development.ts
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api',   // append '/v1/...'
  useMockData: true,                     // currently true — the app is mock-only
};
```

**Non-API paths** (not part of the client contract, useful while developing):

| Path | Purpose | Auth |
|---|---|---|
| `/api-docs` | OpenAPI 3.1 document | Public |
| `/swagger-ui.html` | Swagger UI (redirects to `/swagger-ui/index.html`) | Public |
| `/actuator/health`, `/actuator/info` | Readiness and build info | Public |
| `/actuator/**` (anything else) | Closed | — |

> **Note:** `/v3/api-docs` returns `404 NOT_FOUND`. The document is served at **`/api-docs`** —
> deliberate, and configured in `application.yml` (`springdoc.api-docs.path`). Do not point a code
> generator at `/v3/api-docs`.

### CORS

The API allows an explicit origin list, never a wildcard. The development default is
`http://localhost:4200` (`CORS_ALLOWED_ORIGINS` overrides it). Permitted methods are
`GET, POST, PUT, PATCH, DELETE, OPTIONS`; permitted request headers are `Authorization`,
`Content-Type`, `Accept`; `Location` is exposed. A browser on any other origin is refused before
the request reaches the API — if a dev server runs on a non-4200 port, either change the port or set
`CORS_ALLOWED_ORIGINS`.

### Content type

Every request body and every response body is `application/json`. Three responses carry **no body**
and a client must not try to parse them:

| Response | Endpoints |
|---|---|
| `201` no body | `POST /api/v1/auth/register` |
| `204` no body | `POST /api/v1/auth/logout`, and every `DELETE` |

---

## 2. Authentication

### 2.1 Sign-in returns the token

Two public endpoints issue a token — one for students, one for administrators. Both return the same
shape.

| Who | Endpoint |
|---|---|
| Student | `POST /api/v1/auth/login` |
| Administrator | `POST /api/v1/admin/auth/login` |

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": { "id": 2, "fullName": "An Nguyen", "email": "an.nguyen@student.campuscoin.edu", "role": "STUDENT" }
}
```

| Field | Type | Notes |
|---|---|---|
| `accessToken` | string | The JWT. Opaque to the client — send it back verbatim |
| `tokenType` | string | Always the literal `"Bearer"` |
| `expiresIn` | number | **Seconds** of remaining lifetime, for proactively expiring the session |
| `user.id` | number | Integer, not a string |
| `user.fullName` | string | Display name. The API has **no** `name` field |
| `user.email` | string | Sign-in address |
| `user.role` | `"STUDENT"` \| `"ADMIN"` | The reliable source of the role for routing |

**Do not decode the JWT to find the role.** The role is in this response. `expiresIn` is derived
from the same `auth.session_ttl_minutes` setting the server session row uses, so the two cannot
disagree. The default TTL is 120 minutes; there is **no refresh endpoint** — when the token expires,
the user signs in again.

### 2.2 Sending the token

Every endpoint that is not public takes:

```
Authorization: Bearer <accessToken>
```

Public endpoints — send **no** `Authorization` header:

`POST /api/v1/auth/register` · `POST /api/v1/auth/login` · `POST /api/v1/admin/auth/login` ·
`POST /api/v1/auth/password-reset/request` · `POST /api/v1/auth/password-reset/verify` ·
`POST /api/v1/auth/password-reset/complete`

Everything else is Bearer, and everything under `/api/v1/admin/**` additionally requires the
`ADMIN` role.

### 2.3 Token lifetime and revocation

A token can stop working before `expiresIn` says so. Any of these ends a session immediately, and
the next call answers `401 UNAUTHENTICATED`:

- an administrator disables the account (`POST /api/v1/admin/users/{id}/status`);
- the password is reset, on any device, through **any** reset path;
- the user signs out (`POST /api/v1/auth/logout`), or signs out everywhere.

**Never treat a stored token as proof of a live session.** The only reliable check is to make a call
and handle `401`. The interceptor in §3 is where that happens, once.

### 2.4 Sign-out

`POST /api/v1/auth/logout` with the bearer token → `204` no body, and the server session is revoked.
Call the server **first**, then clear local state; the reverse leaves an open session behind. A
second logout is a `204` again, not an error.

### 2.5 Password reset (3 calls)

The link the backend emails is:

```
{RESET_LINK_BASE_URL}?token=<raw-token>
```

which defaults to `http://localhost:4200/reset-password?token=...`. **The Angular route must exist at
`/reset-password`** and read `token` from the query string.

1. `POST /api/v1/auth/password-reset/request` `{ "email": "..." }`
   → `200` with a **generic message that is returned whether or not the address exists** (BR-04
   anti-enumeration). Show the message as-is; never report "no such account".
2. `POST /api/v1/auth/password-reset/verify` `{ "token": "..." }`
   → `200 { "valid": true }` to open the new-password form, or `400 INVALID_RESET_TOKEN` for
   unknown / already-used / expired.
3. `POST /api/v1/auth/password-reset/complete` `{ "token", "newPassword", "confirmPassword" }`
   → `200` with a confirmation message. **Every cached token for that account is now invalid** —
   clear local state and navigate to sign-in.

### 2.6 Token storage

`localStorage` is the straightforward choice and matches the existing code. It is XSS-exposed, so
never render untrusted HTML without sanitising it. Rules: store the token and never the password;
clear it on sign-out, on any `401`, and after a completed password reset. An httpOnly cookie would
remove the XSS exposure but needs CSRF protection and an API change — out of scope here.

---

## 3. Angular HTTP interceptor

### 3.1 Current state of the app

`frontend/src/app/app.config.ts` today registers `provideHttpClient()` **with no interceptors**:

```ts
providers: [
  provideBrowserGlobalErrorListeners(),
  provideZonelessChangeDetection(),
  provideRouter(routes),
  provideHttpClient()
]
```

`frontend/src/app/core/interceptors/mock-delay.interceptor.ts` exists and is **not registered** —
it is dead code today. The services are mock-only (`environment.useMockData` is `true`). The two
interceptors below are what a real wiring needs.

### 3.2 Auth interceptor — attach the token

```ts
// frontend/src/app/core/interceptors/auth.interceptor.ts
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

const PUBLIC = [
  '/api/v1/auth/register',
  '/api/v1/auth/login',
  '/api/v1/admin/auth/login',
  '/api/v1/auth/password-reset/request',
  '/api/v1/auth/password-reset/verify',
  '/api/v1/auth/password-reset/complete',
];

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).accessToken();

  // Never call the API without the prefix that routes through the proxy.
  const isPublic = PUBLIC.some(p => req.url.includes(p));
  if (!token || isPublic) {
    return next(req);
  }

  return next(req.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  }));
};
```

### 3.3 Error interceptor — one place that reacts to `401`

```ts
// frontend/src/app/core/interceptors/error.interceptor.ts
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      // Sign-in endpoints report a bad password with 401 — that is a form
      // error, not an expired session. Let those through.
      const isSignIn = req.url.includes('/auth/login') || req.url.includes('/admin/auth/login');

      if (err.status === 401 && !isSignIn) {
        auth.clearSession();
        router.navigate(['/login']);
      }
      if (err.status === 403) {
        router.navigate(['/forbidden']);   // or the admin sign-in for admin routes
      }
      return throwError(() => err);
    }),
  );
};
```

Register both, **in this order** (`auth` first so the token is on the request the error interceptor
sees):

```ts
provideHttpClient(withInterceptors([authInterceptor, errorInterceptor]))
```

### 3.4 Reading the error body

Every non-2xx response — from every endpoint, including `403` and `500` — has the `ApiError` body in
§4. Read `errorCode`, never `message`, for control flow:

```ts
this.http.patch<TransactionResponse>(`${environment.apiUrl}/v1/transactions/${id}`, body).pipe(
  catchError((err: HttpErrorResponse) => {
    const code = err.error?.errorCode;
    if (code === 'VALIDATION_ERROR') {
      for (const fe of err.error.fieldErrors ?? []) {
        this.form.get(fe.field)?.setErrors({ server: fe.message });
      }
    }
    return throwError(() => err);
  }),
);
```

### 3.5 Guards are convenience, not security

`auth.guard.ts` and `admin.guard.ts` decide what the **UI** shows. The server refuses independently:
every `/api/v1/admin/**` call is rejected for a non-admin token regardless of what the client allows,
and every student endpoint is rejected for an administrator token. A user who bypasses a guard in the
browser still cannot read or change data.

---

## 4. Common error contract

### 4.1 The body

Every non-2xx response from every endpoint has this shape:

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/auth/register",
  "fieldErrors": [
    { "field": "email", "message": "Email must be a well-formed email address." }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `timestamp` | string | ISO-8601 UTC |
| `status` | number | Same as the HTTP status |
| `errorCode` | string | **Switch on this.** Stable and machine-readable |
| `message` | string | Human-readable, safe to display, **may be reworded** — never switch on it |
| `path` | string | The request path |
| `fieldErrors` | array | Present **only** when there is at least one field error; absent otherwise |
| `fieldErrors[].field` | string | The **request field name** — maps directly to a form control |
| `fieldErrors[].message` | string | Safe to display beside that input |

`fieldErrors` is omitted, not `[]`, when empty (`@JsonInclude(NON_EMPTY)`). Always use
`err.error.fieldErrors ?? []`.

Errors never contain a stack trace, a SQL statement, a database credential or a driver message.

### 4.2 The complete error-code list

These 26 codes are the entire set (`ErrorCode.java`). Nothing else is ever returned.

| `errorCode` | HTTP | Meaning | Typical client action |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | A body field failed validation. `fieldErrors` is present | Map `fieldErrors[]` onto the form controls |
| `MALFORMED_REQUEST` | 400 | Body missing, not JSON, or a field has the wrong type | Fix the request; a bug, not a user error |
| `INVALID_REQUEST` | 400 | A parameter or the HTTP method is not supported here | Fix the request |
| `INVALID_RESET_TOKEN` | 400 | Reset token unknown, already used, or expired (BR-04) | "This link is no longer valid" + offer a new one |
| `INVALID_CREDENTIALS` | 401 | Wrong email or password (UC-02 A1) | Show `message`; never say *which* was wrong |
| `UNAUTHENTICATED` | 401 | No token, or malformed / tampered / expired / revoked | Clear the session, return to sign-in |
| `ACCOUNT_DISABLED` | 401 | Account is DISABLED **at sign-in** only (BR-03) | Show `message`; do not offer "try again" |
| `TOO_MANY_ATTEMPTS` | 429 | Too many failed sign-ins or reset requests | Show `message`; disable submit for a while |
| `ACCESS_DENIED` | 403 | Authenticated, but this role may not use the endpoint | Forbidden page; do **not** retry |
| `EMAIL_ALREADY_REGISTERED` | 409 | The address is taken (UC-01 A1) | Offer sign-in or password reset |
| `CATEGORY_NAME_TAKEN` | 409 | Name repeats one of your categories of that type, or a default one | Ask for a different name |
| `CATEGORY_IN_USE` | 409 | A transaction, budget or rule uses it — the change would rewrite history | Offer retirement (`isActive:false`) instead |
| `TRANSACTION_ALREADY_DELETED` | 409 | Already in the trash | Refresh the list |
| `TRANSACTION_NOT_DELETED` | 409 | Not in the trash, so nothing to restore | Refresh the list |
| `RECURRING_RULE_IN_USE` | 409 | The rule has already posted transactions; end it instead of deleting | Offer `PATCH {"status":"ENDED"}` |
| `CATEGORY_RETIRED` | 409 | The rule's category is retired, which freezes the rule | Restore the category, or delete the rule |
| `RECURRING_RULE_ENDED` | 409 | `ENDED` is final; the rule cannot be moved out of it | Create a new rule |
| `BUDGET_ALREADY_EXISTS` | 409 | One limit per category per month (BR-11) | Send the client to the update endpoint |
| `BOOKMARK_ALREADY_EXISTS` | 409 | The item is already saved (UC-19 B1) | Not an error to the user — navigate to the entry, or `PATCH` the note |
| `SELF_DISABLE_FORBIDDEN` | 409 | An administrator tried to disable their own account | Another administrator must do it |
| `THRESHOLD_NOT_ADJUSTABLE` | 409 | The setting exists but this API may not change it | Disable the control; `adjustable` was `false` |
| `TIP_TEMPLATE_CODE_TAKEN` | 409 | Another template uses this code | Choose another code |
| `TIP_TEMPLATE_CODE_IMMUTABLE` | 409 | A template's `code` cannot be changed | Send the stored code unchanged |
| `NOT_FOUND` | 404 | The resource does not exist (or, for a student route, is not the caller's) | Refresh the list |
| `DATA_CONFLICT` | 409 | An unrecognised database rule rejected the write | Generic conflict message |
| `INTERNAL_ERROR` | 500 | Unexpected failure; the detail is in the server log, not the response | Generic error page |

### 4.3 Which statuses a given endpoint can return

The OpenAPI document declares a status set per operation, and the tables in §7 reproduce it. Two
universal rules apply on top of it:

- **Every Bearer endpoint can answer `401 UNAUTHENTICATED`**, including any operation whose
  OpenAPI entry omits it.
- **Every student-only endpoint can answer `403 ACCESS_DENIED`** for an administrator token, and
  every `/api/v1/admin/**` endpoint can answer `403` for a student token — because the rules in
  `SecurityConfig` require the role. The OpenAPI document under-declares `403` on most student
  endpoints (only `GET /dashboard`, `GET /reports` and `GET /reports/spending` declare it); the
  server returns it anyway. Build against the server. See §10 for the recorded discrepancy.

The full status-code → behaviour mapping:

| Status | Client action |
|---|---|
| `200` / `201` / `202` / `204` | Success. `201`/`204` may have no body — do not parse one |
| `400` | Show `fieldErrors` on the form if present; otherwise show `message` |
| `401` | Clear the session and return to sign-in — except on the sign-in endpoints, where it is a form error |
| `403` | Forbidden page / admin sign-in. Never silently retry |
| `404` | The resource is gone or was never the caller's. Refresh |
| `409` | A state conflict. Show `message`; the tables above name the remedy per code |
| `429` | Show `message`, disable the submit button, allow a later retry |
| `500` | Generic error. The distinction between codes is not useful to the user |

### 4.4 Enums are member names, never numbers

`fail-on-numbers-for-enums: true` is set on the server. `{"fontScale": 2}` is **rejected**, not read
as `LARGE`. Always send and expect the member name (`"LARGE"`). This is why every enum in §6 is
listed as strings.

---

## 5. Data ownership rule

**No endpoint accepts a `userId`.** The caller's identity comes from the verified bearer token, and
every query is bound with it. A `userId` in a body or a query string is discarded, not honoured.

What this means for the client, module by module:

| Rule | Consequence |
|---|---|
| The API never returns another student's data | There is no `GET /users/{id}`, no `?userId=` and no `/profile/me/...` alias anywhere |
| "Not yours" and "does not exist" look identical on student routes | A tip that is another student's answers the same `404` as a missing one. Do not build an id-probing UI on the difference |
| Admin routes are the one place ids are disclosed | `404` on `POST /admin/users/{id}/status` does tell an administrator which ids are real — intended, and safe only while the `hasRole("ADMIN")` rule holds |
| The role cannot be chosen by the client | Registration always creates `STUDENT`/`ACTIVE` with the system currency. `role`, `status`, `currency` and `id` are not accepted on any write |
| A record's type comes from its category | `transactions` and `recurring_rules` have no writable `type` — it is derived from the chosen category (BR-05). Never send `type` on a transaction |

The UI consequence: **never render a user-id input, and never trust a client-side filter for
ownership.** Filter for display; let the server be the boundary.

---

## 6. Enum reference

Every enum value the API can send or accept, verified from the Java enums and the live schema. Send
and expect these **strings**.

### 6.1 Transactions, categories and rules

| Enum | Values | Where |
|---|---|---|
| `CategoryType` | `INCOME`, `EXPENSE` | `CategoryResponse.type`, `TransactionResponse.type`, `RecurringRuleResponse.type`, admin category requests |
| `TransactionSource` | `MANUAL`, `CSV`, `RECURRING` | `TransactionResponse.source` — read-only; a row created through this API is always `MANUAL` |
| `RecurringFrequency` | `DAILY`, `WEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY` | Recurring rule create/update and response |
| `RecurringStatus` | `ACTIVE`, `PAUSED`, `ENDED` | `RecurringRuleResponse.status`; writable on update. `ENDED` is final |
| `ConsumptionStatus` | `ON_TRACK`, `NEAR`, `EXCEEDED` | `BudgetResponse.consumptionStatus` — read-only, derived from the thresholds |

### 6.2 Profile and account

| Enum | Values | Where |
|---|---|---|
| `UserRole` | `STUDENT`, `ADMIN` | `AuthResponse.user.role`, `AdminUserResponse.role` |
| `AccountStatus` | `ACTIVE`, `DISABLED` | `AdminUserResponse.status`; writable through `SetUserStatusRequest` |
| `ThemePreference` | `LIGHT`, `DARK`, `SYSTEM` | Profile preferences. `SYSTEM` follows the OS |
| `FontScale` | `SMALL`, `MEDIUM`, `LARGE`, `XLARGE` | Profile preferences |

### 6.3 Tips, notifications, announcements and bookmarks

| Enum | Values | Where |
|---|---|---|
| `TipState` | `NEW`, `PINNED`, `DISMISSED` | `TipResponse.state`, `BookmarkResponse.tipState`, and the writable `UpdateTipStateRequest.state` |
| `TipConditionType` | `OVER_BUDGET`, `NEAR_BUDGET`, `CATEGORY_SPIKE`, `NO_BUDGET_SET`, `SAVINGS_GOAL_AT_RISK`, `LOW_SAVINGS_RATE`, `GENERIC` | Admin tip templates. `LOW_SAVINGS_RATE` is seeded but produces no tips in this build |
| `NotificationType` | `BUDGET_NEAR`, `BUDGET_EXCEEDED`, `ANNOUNCEMENT`, `SYSTEM`, `INSIGHT_READY`, `TIP`, `RECURRING_POSTED` | `NotificationResponse.type` — read-only. Only `BUDGET_NEAR` and `BUDGET_EXCEEDED` are written in this build; `INSIGHT_READY` belongs to module 12 |
| `AnnouncementSeverity` | `INFO`, `WARNING`, `SUCCESS` | Announcements |
| `AnnouncementAudience` | `ALL`, `STUDENTS`, `ADMINS` | Announcements. A student's dashboard never receives an `ADMINS` notice |
| `BookmarkItemType` | `TIP`, `INSIGHT` | Bookmark create and response |
| `ReportGranularity` | `DAILY`, `WEEKLY` | `GET /api/v1/reports/spending` |

### 6.4 The two enums that are not the same

Two fields look like the same enum and are not. This is deliberate and **load-bearing for the UI**:

| Field | Values | Why |
|---|---|---|
| `TipResponse.state` (tips screen, bookmarks) | `NEW`, `PINNED`, `DISMISSED` | The full row state |
| `DashboardTipResponse.state` (dashboard) | **`NEW`, `PINNED` only** | The dashboard view excludes dismissed tips entirely, so `DISMISSED` never appears here |

A TypeScript type for the dashboard's tips must not be the same type as the tips screen's. Widening
it invites a `case 'DISMISSED'` branch that can never run on the dashboard.

### 6.5 The only writable enum that has a restriction

`UpdateTipStateRequest.state` accepts all three values, but **dismissal is one-way**: a tip that is
`DISMISSED` cannot be moved back to `NEW` or `PINNED` — that request answers `400`. `NEW` is how a
tip is *unpinned*. The UI must therefore not offer "restore" on a dismissed tip.

---

## 7. Endpoint reference by module

One entry per operation. Every request field that carries a constraint shows it; every response
field that may be **absent** is marked *(optional)*, which is what `@JsonInclude(NON_NULL)` does.
Fields not so marked are always present.

Reading the tables:

- **Auth** — `Public` (no header) or `Bearer`.
- **Role** — the role the endpoint requires; `—` for public.
- **Body** — required request fields are plain; optional ones are marked *opt*.
- **Statuses** — the set the OpenAPI document declares. Add `401` on any Bearer endpoint and `403`
  for the wrong role, per §4.3.

---

### 7.1 Authentication — endpoints 1–7

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 1 | `POST` | `/api/v1/auth/register` | Public | — | `201` no body |
| 2 | `POST` | `/api/v1/auth/login` | Public | — | `200` token + user |
| 3 | `POST` | `/api/v1/auth/logout` | Bearer | `STUDENT` | `204` no body |
| 4 | `POST` | `/api/v1/auth/password-reset/request` | Public | — | `200` message |
| 5 | `POST` | `/api/v1/auth/password-reset/verify` | Public | — | `200` `{valid}` |
| 6 | `POST` | `/api/v1/auth/password-reset/complete` | Public | — | `200` message |
| 7 | `POST` | `/api/v1/admin/auth/login` | Public | — | `200` token + user |

#### 1. Register — `POST /api/v1/auth/register`

| Field | Type | Req | Rules |
|---|---|---|---|
| `fullName` | string | ✔ | 2–120 characters, not blank |
| `email` | string | ✔ | Well-formed email, ≤ 190 characters |
| `password` | string | ✔ | 8–72 characters, ≥ 1 upper-case, ≥ 1 lower-case, ≥ 1 digit |
| `confirmPassword` | string | ✔ | Must equal `password` (mismatch → field error on `confirmPassword`) |

`201` with **no body**. Registration does **not** sign the user in — call sign-in afterwards.
Address always becomes a `STUDENT`/`ACTIVE` account with the system currency; `role`, `status` and
`currency` are not accepted.

Statuses: `201` · `400` `VALIDATION_ERROR` · `409` `EMAIL_ALREADY_REGISTERED`.

The 72-character cap is bcrypt's own limit — longer input would be silently truncated.

#### 2. Sign in (student) — `POST /api/v1/auth/login`

| Field | Type | Req | Rules |
|---|---|---|---|
| `email` | string | ✔ | Not blank, ≤ 190 characters |
| `password` | string | ✔ | Not blank, ≤ 72 characters |

No password-shape rule is applied here deliberately: a validation error on the password would let an
attacker distinguish "this could never be a password" from "wrong password".

`200` → `AuthResponse` (§2.1). Statuses: `200` · `400` · `401` `INVALID_CREDENTIALS` or
`ACCOUNT_DISABLED` · `403` · `429` `TOO_MANY_ATTEMPTS`.

#### 3. Sign out — `POST /api/v1/auth/logout`

No body. `204`, session revoked. Idempotent. Statuses: `204` · `401`.

#### 4. Request a reset link — `POST /api/v1/auth/password-reset/request`

| Field | Type | Req | Rules |
|---|---|---|---|
| `email` | string | ✔ | ≤ 190 characters |

`200` `{ "message": "..." }` — **the same message whether or not the address exists.** Statuses:
`200` · `400` · `429`.

#### 5. Verify a reset token — `POST /api/v1/auth/password-reset/verify`

| Field | Type | Req | Rules |
|---|---|---|---|
| `token` | string | ✔ | ≤ 200 characters |

`200` `{ "valid": true }`. A bad token is `400 INVALID_RESET_TOKEN`. Statuses: `200` · `400`.

#### 6. Complete the reset — `POST /api/v1/auth/password-reset/complete`

| Field | Type | Req | Rules |
|---|---|---|---|
| `token` | string | ✔ | ≤ 200 characters |
| `newPassword` | string | ✔ | 8–72 characters |
| `confirmPassword` | string | ✔ | Must equal `newPassword` |

`200` `{ "message": "..." }`. This consumes the token, changes the password and revokes **every**
existing session for the account. Statuses: `200` · `400` (`INVALID_RESET_TOKEN` or a field error).

#### 7. Administrator sign in — `POST /api/v1/admin/auth/login`

Body and response identical to endpoint 2 (`LoginRequest` → `AuthResponse`). The difference is the
policy: a token issued to a `STUDENT` account is refused with `403 ACCESS_DENIED`.

Statuses: `200` · `400` · `401` · `403` · `429`.

---

### 7.2 Profile & Preferences — endpoints 8–10

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 8 | `GET` | `/api/v1/profile/me` | Bearer | `STUDENT` | `200` profile |
| 9 | `PATCH` | `/api/v1/profile/me` | Bearer | `STUDENT` | `200` profile |
| 10 | `PATCH` | `/api/v1/profile/me/preferences` | Bearer | `STUDENT` | `200` profile |

All three return the **same** `ProfileResponse` — every field always present, including a `null`
`academicYear`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Account id |
| `fullName` | string | Display name |
| `email` | string | Read-only here — changing it is not part of UC-04 |
| `academicYear` | string \| **null** | `null` when not set |
| `monthlyAllowanceBaseline` | number | Never negative |
| `monthlySavingsGoal` | number | Never negative |
| `currency` | string | ISO 4217, e.g. `USD`. Read-only |
| `themePreference` | `LIGHT` \| `DARK` \| `SYSTEM` | |
| `fontScale` | `SMALL` \| `MEDIUM` \| `LARGE` \| `XLARGE` | |

#### 9. Update profile — `PATCH /api/v1/profile/me`

| Field | Type | Req | Rules |
|---|---|---|---|
| `fullName` | string | opt | Not blank; 1–120 characters (`^\s*\S.{0,118}\S\s*$`) |
| `academicYear` | string | opt | ≤ 30 characters. **Send `""` to clear it** |
| `monthlyAllowanceBaseline` | number | opt | ≥ 0 |
| `monthlySavingsGoal` | number | opt | ≥ 0 |

Statuses: `200` · `400` · `401`.

#### 10. Update preferences — `PATCH /api/v1/profile/me/preferences`

| Field | Type | Req | Rules |
|---|---|---|---|
| `themePreference` | `LIGHT` \| `DARK` \| `SYSTEM` | opt | |
| `fontScale` | `SMALL` \| `MEDIUM` \| `LARGE` \| `XLARGE` | opt | |

Statuses: `200` · `400` · `401`.

**Partial-update semantics, and they apply to every `PATCH` in this API.** A field that is **absent**
or **`null`** is left unchanged. For the free-text fields that can also be cleared, an **empty
string clears it**. Everything else is set to what you send. This is why a `PATCH` body should carry
only the fields the user actually edited.

---

### 7.3 Personal Categories — endpoints 11–15

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 11 | `GET` | `/api/v1/categories` | Bearer | `STUDENT` | `200` list |
| 12 | `GET` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | `200` category |
| 13 | `POST` | `/api/v1/categories` | Bearer | `STUDENT` | `201` category |
| 14 | `PATCH` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | `200` category |
| 15 | `DELETE` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | `204` no body |

`CategoryResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `name` | string | Unique among the caller's categories of the same type, and never a default's name |
| `type` | `INCOME` \| `EXPENSE` | **The single source of truth for a transaction's type** (BR-05) |
| `icon` | string *(optional)* | Omitted when not set |
| `color` | string *(optional)* | `#RRGGBB`. Omitted when not set |
| `isDefault` | boolean | `true` for a shared default category — the student cannot edit or delete it |
| `isActive` | boolean | `false` once retired (BR-07) |
| `sortOrder` | number | Display order |
| `description` | string *(optional)* | Omitted when not set |

**The list is not filtered by type.** It returns the shared default categories plus the caller's own
in one array; split them in the UI with `isDefault` and `type`. There is no `?type=` parameter —
filter client-side.

#### 13. Create — `POST /api/v1/categories`

| Field | Type | Req | Rules |
|---|---|---|---|
| `name` | string | ✔ | Not blank, 1–80 characters (`^\s*\S.{0,79}\s*$`) |
| `type` | `INCOME` \| `EXPENSE` | ✔ | Fixed at creation in practice — see below |
| `icon` | string | opt | ≤ 50 characters |
| `color` | string | opt | `#RRGGBB`, or `""` for none |
| `description` | string | opt | ≤ 255 characters |
| `sortOrder` | number | opt | 0–32767, default `0` |
| `isActive` | boolean | opt | default `true`; `false` creates it already retired |

Statuses: `201` · `400` · `409` `CATEGORY_NAME_TAKEN` · `401`.

#### 14. Update — `PATCH /api/v1/categories/{id}`

Same fields, all optional; `""` clears `icon`, `color` and `description`. **`type` is refused with
`409 CATEGORY_IN_USE`** once any transaction, budget or recurring rule uses the category, because it
would rewrite the meaning of that history. `isActive: false` retires the category without deleting
it — offer that instead of a delete when the delete is refused.

Statuses: `200` · `400` · `404` · `409` (`CATEGORY_NAME_TAKEN`, `CATEGORY_IN_USE`) · `401`.

#### 15. Delete — `DELETE /api/v1/categories/{id}`

`204`. Statuses: `204` · `404` · `409` `CATEGORY_IN_USE` · `401`.

---

### 7.4 Transactions — endpoints 16–21

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 16 | `GET` | `/api/v1/transactions` | Bearer | `STUDENT` | `200` list |
| 17 | `GET` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | `200` transaction |
| 18 | `POST` | `/api/v1/transactions` | Bearer | `STUDENT` | `201` transaction |
| 19 | `PATCH` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | `200` transaction |
| 20 | `DELETE` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | `204` no body |
| 21 | `POST` | `/api/v1/transactions/{id}/restore` | Bearer | `STUDENT` | `200` transaction |

`TransactionResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `categoryId` | number | |
| `categoryName` | string | |
| `categoryIcon` | string *(optional)* | Omitted when the category has none |
| `categoryColor` | string *(optional)* | Omitted when the category has none |
| `type` | `INCOME` \| `EXPENSE` | **Read-only**, derived from the category (BR-05). Never send it |
| `amount` | number | Always positive; `type` says which direction |
| `txnDate` | string | `yyyy-MM-dd` (a date, not a timestamp) |
| `description` | string *(optional)* | Omitted when not set |
| `source` | `MANUAL` \| `CSV` \| `RECURRING` | **Read-only.** Always `MANUAL` for a row created here |
| `isDeleted` | boolean | `true` = in the trash; excluded from every balance and report (BR-09) |
| `deletedAt` | string *(optional)* | Present only when `isDeleted` is `true` |

#### 16. List — `GET /api/v1/transactions`

| Query | Type | Default | Notes |
|---|---|---|---|
| `from` | `yyyy-MM-dd` | none | Earliest date to include, inclusive |
| `to` | `yyyy-MM-dd` | today | Latest date to include, inclusive |
| `includeDeleted` | boolean | `false` | `true` adds the trash to the result |

Statuses: `200` · `400` · `401`. **There is no pagination** — the list is bounded by the date range.

#### 18. Record — `POST /api/v1/transactions`

| Field | Type | Req | Rules |
|---|---|---|---|
| `categoryId` | number | ✔ | The caller's own category, or a default one in use |
| `amount` | number | ✔ | **> 0**, at most two decimal places |
| `txnDate` | `yyyy-MM-dd` | ✔ | **Cannot be in the future** (BR-08) |
| `description` | string | opt | ≤ 255 characters |

Statuses: `201` · `400` · `404` · `409` · `401`.

#### 19. Update — `PATCH /api/v1/transactions/{id}`

All four fields optional; same rules; `description: ""` clears the note. Moving `categoryId` changes
whether the record counts as income or expense.

Statuses: `200` · `400` · `404` · `409` · `401`.

#### 20. Delete — `DELETE /api/v1/transactions/{id}`

Soft delete: `204`, `isDeleted` becomes `true`. Statuses: `204` · `404` · `409`
`TRANSACTION_ALREADY_DELETED` · `401`.

#### 21. Restore — `POST /api/v1/transactions/{id}/restore`

No body; `200` on success. Statuses: `200` · `404` · `409` `TRANSACTION_NOT_DELETED` · `401`.

**Delete and restore are separate operations, not a `PATCH` of `isDeleted`.** `isDeleted` is not a
writable field anywhere — BR-09 requires the history log entry each transition writes.

---

### 7.5 Recurring — endpoints 22–26

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 22 | `GET` | `/api/v1/recurring-rules` | Bearer | `STUDENT` | `200` list |
| 23 | `GET` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | `200` rule |
| 24 | `POST` | `/api/v1/recurring-rules` | Bearer | `STUDENT` | `201` rule |
| 25 | `PATCH` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | `200` rule |
| 26 | `DELETE` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | `204` no body |

`RecurringRuleResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `categoryId` | number | |
| `categoryName` | string | |
| `categoryIcon` / `categoryColor` | string *(optional)* | Omitted when the category has none |
| `type` | `INCOME` \| `EXPENSE` | **Read-only**, derived from the category |
| `amount` | number | Always positive |
| `description` | string *(optional)* | Omitted when not set |
| `frequency` | `DAILY` \| `WEEKLY` \| `MONTHLY` \| `QUARTERLY` \| `YEARLY` | |
| `intervalCount` | number | Periods between occurrences (1 = every period) |
| `startDate` | `yyyy-MM-dd` | **Not editable** |
| `endDate` | `yyyy-MM-dd` *(optional)* | Omitted when the rule is open-ended |
| `nextRunDate` | `yyyy-MM-dd` | What the scheduler reads; how a rule is moved to another day |
| `lastRunDate` | `yyyy-MM-dd` *(optional)* | Omitted until the rule has run once — **that is how the UI tells a never-run rule** |
| `status` | `ACTIVE` \| `PAUSED` \| `ENDED` | Only `ACTIVE` rules post |

#### 24. Create — `POST /api/v1/recurring-rules`

| Field | Type | Req | Rules |
|---|---|---|---|
| `categoryId` | number | ✔ | Decides income vs expense |
| `amount` | number | ✔ | **> 0**, at most two decimal places |
| `frequency` | `DAILY`…`YEARLY` | ✔ | |
| `startDate` | `yyyy-MM-dd` | ✔ | Cannot be changed afterwards |
| `description` | string | opt | ≤ 255 characters |
| `intervalCount` | number | opt | 1–999, default `1` |
| `endDate` | `yyyy-MM-dd` | opt | Inclusive; omit or `""` for open-ended |
| `nextRunDate` | `yyyy-MM-dd` | opt | First occurrence; omit to start on `startDate` |

Statuses: `201` · `400` · `404` · `409` · `401`.

#### 25. Update — `PATCH /api/v1/recurring-rules/{id}`

All optional; adds `status`. `description: ""` clears the note; `endDate: ""` makes the rule
open-ended.

| Field | Type | Notes |
|---|---|---|
| `categoryId` | number | Moving it also changes the income/expense direction |
| `amount` | number | > 0 |
| `description` | string | `""` clears |
| `frequency` | enum | Changing it does not re-post covered periods |
| `intervalCount` | number | 1–999 |
| `endDate` | `yyyy-MM-dd` | `""` removes it |
| `nextRunDate` | `yyyy-MM-dd` | Moves the rule onto a different day |
| `status` | `ACTIVE` \| `PAUSED` \| `ENDED` | `ENDED` is **final** |

`startDate` and `lastRunDate` are **not** accepted — `startDate` is the rule's origin and
`lastRunDate` is the scheduler's cursor.

Statuses: `200` · `400` · `404` · `409` (`CATEGORY_RETIRED`, `RECURRING_RULE_ENDED`) · `401`.

#### 26. Delete — `DELETE /api/v1/recurring-rules/{id}`

`204`, **unless the rule has already generated transactions** — then `409 RECURRING_RULE_IN_USE`,
and the remedy the UI must offer is `PATCH {"status":"ENDED"}`. Statuses: `204` · `404` · `409` ·
`401`.

**A pause defers periods rather than skipping them.** Nothing selects a `PAUSED` rule, so its cursor
does not advance; resuming it posts every period that came due during the pause, dated on their
original dates. Recorded as OB-010 — tell the user in the UI rather than silently surprising them.

---

### 7.6 Budget & Notifications — endpoints 27–34

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 27 | `GET` | `/api/v1/budgets` | Bearer | `STUDENT` | `200` list |
| 28 | `GET` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | `200` budget |
| 29 | `POST` | `/api/v1/budgets` | Bearer | `STUDENT` | `201` budget |
| 30 | `PATCH` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | `200` budget |
| 31 | `DELETE` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | `204` no body |
| 32 | `GET` | `/api/v1/notifications` | Bearer | `STUDENT` | `200` list |
| 33 | `GET` | `/api/v1/notifications/{id}` | Bearer | `STUDENT` | `200` notification |
| 34 | `POST` | `/api/v1/notifications/{id}/read` | Bearer | `STUDENT` | `200` notification |

`BudgetResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `categoryId` | number | |
| `categoryName` | string | |
| `categoryIcon` / `categoryColor` | string *(optional)* | Omitted when the category has none |
| `periodMonth` | string | `yyyy-MM` |
| `limitAmount` | number | The limit |
| `spentAmount` | number | **Read-only.** Live spending, trash excluded |
| `remainingAmount` | number | **Read-only.** Negative once the limit is passed |
| `consumedPct` | number | **Read-only.** Percent, two decimals; `0` for no spending |
| `consumptionStatus` | `ON_TRACK` \| `NEAR` \| `EXCEEDED` | **Read-only.** `NEAR` at ≥ 80% by default |

#### 27. List — `GET /api/v1/budgets`

| Query | Type | Default | Notes |
|---|---|---|---|
| `month` | `yyyy-MM` | current month | |

Statuses: `200` · `400` · `401`.

#### 29. Set a limit — `POST /api/v1/budgets`

| Field | Type | Req | Rules |
|---|---|---|---|
| `categoryId` | number | ✔ | An expense category |
| `limitAmount` | number | ✔ | **> 0**, at most two decimal places |
| `periodMonth` | `yyyy-MM` | opt | Defaults to the current month |

Statuses: `201` · `400` · `404` · `409` `BUDGET_ALREADY_EXISTS` · `401`.

**Setting, changing or removing a limit raises no alert, and the UI must not imply one.** An alert
belongs to the *transaction* that crossed a threshold. BR-12 bounds it to at most one message per
threshold, per category, per month — so "my budget reads `EXCEEDED` and I have one notification" is
a correct state, not a bug. Never synthesise alerts from current budget state.

#### 30. Change a limit — `PATCH /api/v1/budgets/{id}`

| Field | Type | Req | Rules |
|---|---|---|---|
| `limitAmount` | number | opt | > 0, at most two decimals |

`categoryId` and `periodMonth` are **not editable** — together with the owner they are the row's
identity, so moving a limit is a `DELETE` then `POST`. Statuses: `200` · `400` · `404` · `409`
`CATEGORY_RETIRED` · `401`.

A budget whose category has been retired cannot be changed at all (`409 CATEGORY_RETIRED`); the
remedies are to restore the category or delete the budget. Deleting is **not** frozen.

#### 31. Remove a limit — `DELETE /api/v1/budgets/{id}`

`204`. Statuses: `204` · `404` · `401`.

#### 32. List notifications — `GET /api/v1/notifications`

No parameters, newest first. Statuses: `200` · `401`.

#### 33. One notification — `GET /api/v1/notifications/{id}`

`NotificationResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `type` | see §6.3 | Read-only |
| `title` | string | |
| `body` | string *(optional)* | Omitted when empty |
| `linkUrl` | string *(optional)* | e.g. `/budgets` — a client-side route |
| `refEntityType` | string *(optional)* | e.g. `BUDGET` |
| `refEntityId` | number *(optional)* | |
| `isRead` | boolean | |
| `readAt` | string *(optional)* | Present **if and only if** `isRead` is `true` |
| `createdAt` | string | Newest first |

Reading one **does not mark it read** — opening and acknowledging are different acts. Statuses:
`200` · `404` · `401`.

#### 34. Mark read — `POST /api/v1/notifications/{id}/read`

No body; `200` with the updated notification. **One-way** — there is no unread route, and a second
call is a successful no-op. Statuses: `200` · `404` · `401`.

---

### 7.7 Dashboard — endpoint 35

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 35 | `GET` | `/api/v1/dashboard` | Bearer | `STUDENT` | `200` dashboard |

No parameters, no body, and **nothing on this path writes**.

`DashboardResponse`:

| Field | Type | Notes |
|---|---|---|
| `periodMonth` | string | `yyyy-MM` — the month **every** figure and list describes. Always the current month, from the database clock |
| `summary` | object | The month's totals and goal progress |
| `topCategory` | object *(optional)* | Omitted when the student recorded no spending this month |
| `tips` | array | Pinned first, then by potential saving (BR-14). Empty when none |
| `announcements` | array | Live notices for students, newest first. Empty when none |

`summary` — `DashboardSummaryResponse`:

| Field | Type | Notes |
|---|---|---|
| `currency` | string | |
| `totalIncome` | number | Trash excluded |
| `totalExpense` | number | Trash excluded |
| `netAmount` | number | Income − spending; may be negative |
| `monthlyAllowanceBaseline` | number | `0.00` when not set |
| `monthlySavingsGoal` | number | `0.00` when not set |
| `savingsGoalPct` | number *(optional)* | **Absent when no goal is set**; may be negative |

`topCategory` — `DashboardTopCategoryResponse`: `categoryId`, `categoryName`,
`categoryIcon` *(optional)*, `categoryColor` *(optional)*, `totalAmount`.

`tips[]` — `DashboardTipResponse`: `id`, `categoryId` *(optional — absent for the savings-goal tip)*,
`title`, `body`, `potentialSaving`, and `state` limited to **`NEW` | `PINNED`** (§6.4).

`announcements[]` — `DashboardAnnouncementResponse`: `id`, `title`, `body`, `severity`,
`startsAt`, `endsAt` *(optional — absent for an open-ended notice)*.

Statuses: `200` · `401` · `403`.

**The month is not selectable.** `periodMonth` is always the current month, and there is no
`?month=`. Keep it in the response so the header can name the month it is showing. Budget progress
and the notification list are **not** in this payload — fetch `GET /api/v1/budgets` and
`GET /api/v1/notifications` for those.

---

### 7.8 Reports & Export — endpoints 36–37

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 36 | `GET` | `/api/v1/reports` | Bearer | `STUDENT` | `200` report |
| 37 | `GET` | `/api/v1/reports/spending` | Bearer | `STUDENT` | `200` spending series |

These are **two endpoints because they are not equally month-selectable** — that is a correctness
constraint, not a design preference. Endpoint 36 can answer about any month; endpoint 37 reads views
that derive their range from the database clock and can only answer about the current month.

#### 36. Monthly report — `GET /api/v1/reports`

| Query | Type | Default | Notes |
|---|---|---|---|
| `month` | `yyyy-MM` | current month | Any month the caller has records for, including an empty one |

`ReportResponse`:

| Field | Type | Notes |
|---|---|---|
| `periodMonth` | string | The month the totals and breakdowns describe |
| `currency` | string | |
| `totals` | object | **Always present**, but its figures may be absent (§ below) |
| `expenseByCategory` | array | Largest first. **Always present**, empty when nothing spent |
| `incomeByCategory` | array | Largest first. **Always present**, empty when nothing earned |
| `sixMonthTrend` | array | **Always exactly 6 points**, oldest first |

`totals` — `ReportTotalsResponse`. **All four fields are absent when the month holds no records:**
`income`, `expense`, `net`, `transactionCount`. "Recorded nothing" and "netted to nothing" are
different statements, and the schema distinguishes them. Render "no activity in July", **not**
`$0.00`, when the block is absent.

`expenseByCategory[]` / `incomeByCategory[]` — `ReportCategoryResponse`: `categoryId`,
`categoryName`, `categoryIcon` *(optional)*, `categoryColor` *(optional)*, `type`, `total`,
`percentage`, `transactionCount`.

> **`percentage` does not sum to 100.** Each share is rounded independently to two decimals, so three
> equal thirds read `33.33` each and total `99.99`. Never adjust a slice to absorb a remainder, and
> never display a "total percentage". Do not derive a slice's share from another — read the field.

`sixMonthTrend[]` — `ReportTrendPointResponse`: `periodMonth`, `income`, `expense`, `net`. A quiet
month is present as `0.00` here, contrary to the totals block above — BR-17 requires all six points.

**The trend ignores `month`.** It is always the last six months ending at the **current** one, so
asking for July's report returns July's totals and July's slices beside the same six trend points.
Draw the chart from each point's own `periodMonth`, never from `periodMonth`.

Statuses: `200` · `400` · `401` · `403`.

#### 37. Spending by day or week — `GET /api/v1/reports/spending`

| Query | Type | Default | Notes |
|---|---|---|---|
| `month` | `yyyy-MM` | current month | **Only the current month is accepted**; any other value is `400` |
| `from` | `yyyy-MM-dd` | first of month | Must fall **within** the current month |
| `to` | `yyyy-MM-dd` | last of month | Must fall **within** the current month |
| `granularity` | `DAILY` \| `WEEKLY` | `DAILY` | `WEEKLY` = one point per ISO week |

`SpendingSeriesResponse`: `granularity`, `from`, `to`, `currency`, `totalExpense`, `points[]`.
`SpendingPointResponse`: `intervalStart`, `intervalEnd`, `totalExpense`, `transactionCount`.

**Intervals with no spending are absent, not zero** — draw the gap from `from`/`to`, do not insert a
zero bar. **Weekly bars are not clipped to the month**: an ISO week is not split, so the first and
last bars may reach into the neighbouring months and `totalExpense` may exceed the month's expense.
That is correct; label the bars by their own dates.

Statuses: `200` · `400` · `401` · `403`. `400` covers a non-current `month` and a window outside it
(`fieldErrors` names `month`, `from` or `to`).

**There is no export endpoint.** BR-18 requires the file to be generated when the user asks, which is
what these two reads already do. A client exports what it fetched.

---

### 7.9 Saving Tips — endpoints 38–41

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 38 | `GET` | `/api/v1/tips` | Bearer | `STUDENT` | `200` one month's tips |
| 39 | `GET` | `/api/v1/tips/months` | Bearer | `STUDENT` | `200` `{months:[...]}` |
| 40 | `POST` | `/api/v1/tips/generate` | Bearer | `STUDENT` | `200` the month's tips |
| 41 | `POST` | `/api/v1/tips/{id}/state` | Bearer | `STUDENT` | `200` the tip |

`TipResponse`: `id`, `categoryId` *(optional — absent for the savings-goal tip)*, `title`, `body`,
`potentialSaving`, `state` (`NEW` | `PINNED` | `DISMISSED`).

#### 38. One month's tips — `GET /api/v1/tips`

| Query | Type | Default | Notes |
|---|---|---|---|
| `month` | `yyyy-MM` | current month | The response names the month it answered |

`TipListResponse`: `periodMonth`, `tips[]` — already ranked (pinned first, then by `potentialSaving`).
**A month with no tips is `200` with an empty array, never `404`** — the month exists, the student
simply has no advice for it. Statuses: `200` · `400` · `401`.

**Reading does not generate.** `GET` never changes what is on the screen.

#### 39. Months with tips — `GET /api/v1/tips/months`

`200` `{ "months": ["2026-09","2026-08"] }`, newest first. This is what a month picker should offer,
because it lists exactly the months that would return something. A month whose tips were **all**
dismissed drops out of this list — it would show an empty screen. Statuses: `200` · `401`.

#### 40. Generate — `POST /api/v1/tips/generate`

No body, no `month` parameter — it always runs for the current month, so a client cannot pull advice
about an arbitrary past month. Returns `200` with the month's tips.

**Generating is idempotent and preserves the student's choices.** A second call in the same month
adds nothing and **cannot resurrect a dismissed tip or move a pinned one**. A refresh button is
therefore safe. Statuses: `200` · `401`.

#### 41. Pin, dismiss or clear — `POST /api/v1/tips/{id}/state`

| Field | Type | Req | Rules |
|---|---|---|---|
| `state` | `NEW` \| `PINNED` \| `DISMISSED` | ✔ | |

`200` with the tip in its new state. `NEW` is how a tip is **unpinned**. Dismissal is **one-way**:
asking to move a `DISMISSED` tip to `NEW` or `PINNED` answers `400`. Asking for the state a tip
already holds is not an error — it returns the tip and its original timestamp.

Statuses: `200` · `400` · `404` · `401`.

There is **no** `/tips/{id}` read, no create, no edit, no delete, and no `/pin`, `/unpin` or
`/dismiss` — three names for one write of one column.

---

### 7.10 Bookmarks / Notes — endpoints 42–45

| # | Method | Endpoint | Auth | Role | Success |
|---|---|---|---|---|---|
| 42 | `GET` | `/api/v1/bookmarks` | Bearer | `STUDENT` | `200` list, newest first |
| 43 | `POST` | `/api/v1/bookmarks` | Bearer | `STUDENT` | `201` the saved item |
| 44 | `PATCH` | `/api/v1/bookmarks/{id}` | Bearer | `STUDENT` | `200` the saved item |
| 45 | `DELETE` | `/api/v1/bookmarks/{id}` | Bearer | `STUDENT` | `204` no body |

`BookmarkResponse` — **the tip's own words are embedded**, so a list row needs no second call:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Use for the note and un-mark actions |
| `itemType` | `TIP` \| `INSIGHT` | Holds only `TIP` in practice (§ below) |
| `tipId` | number | The tips endpoint's `id` |
| `tipTitle` | string | The saved tip's headline |
| `tipBody` | string | The saved advice |
| `tipPotentialSaving` | number | `0.00` when no figure is attached |
| `tipState` | `NEW` \| `PINNED` \| `DISMISSED` | A tip dismissed on the tips screen **stays here** |
| `tipMonth` | string | `yyyy-MM` |
| `note` | string *(optional)* | The caller's own note. Omitted when there is none |
| `createdAt` | string | The list is ordered by this, newest first |

**Never resolve the tip per row.** Every displayed field is already in the response.

#### 43. Save — `POST /api/v1/bookmarks`

| Field | Type | Req | Rules |
|---|---|---|---|
| `itemType` | `TIP` \| `INSIGHT` | ✔ | **Send only `TIP`** — see below |
| `itemId` | number | ✔ | The tip's `id` from `GET /api/v1/tips` |
| `note` | string | opt | ≤ 255 characters |

Statuses: `201` · `400` · `404` · `409` `BOOKMARK_ALREADY_EXISTS` · `401`.

- **`INSIGHT` is refused with a `400` field error naming UC-17.** The value exists in the database
  `ENUM`, so the OpenAPI schema accepts it, but insights belong to module 12 and no read path exists.
  Do not offer it in the UI.
- **A repeat save is `409`, not an idempotent success.** `uk_bookmark_dedupe` makes it impossible;
  answering `201` would claim a bookmark was made when none was. Recover by treating the item as
  already saved — navigate to the existing entry, or `PATCH` the note the user just typed.

#### 44. Set or clear the note — `PATCH /api/v1/bookmarks/{id}`

| Field | Type | Req | Rules |
|---|---|---|---|
| `note` | string | opt | ≤ 255 characters. **Omit or send `null` → unchanged. Send `""` → cleared** |

The target of a bookmark cannot be changed; this body has exactly one field. Statuses: `200` · `400`
· `404` · `401`.

A dismissed tip **stays in the saved list** carrying `tipState: "DISMISSED"`. Do not filter it out —
the student chose to keep it, and filtering removes something they did not remove.

#### 45. Un-mark — `DELETE /api/v1/bookmarks/{id}`

`204`, **twice** — un-marking is idempotent, so a retry or two devices settle on the end state rather
than reporting a failure. There is no `404` on this route. Statuses: `204` · `401`.

**Pinning is not bookmarking** (VĐ-03). Pinning is `POST /api/v1/tips/{id}/state`; a "Pinned tips"
tab and a "Saved items" list are two features, and merging them makes the students' pins and their
saved items drift apart.

---

### 7.11 Administration — endpoints 46–61

Everything under `/api/v1/admin/**` requires the `ADMIN` role; a student token is `403 ACCESS_DENIED`
and no token is `401 UNAUTHENTICATED`, on all 16 operations. `POST /api/v1/admin/auth/login` is the
one public exception (endpoint 7).

| # | Method | Endpoint | Success |
|---|---|---|---|
| 46 | `GET` | `/api/v1/admin/users` | `200` every account, oldest first |
| 47 | `POST` | `/api/v1/admin/users/{id}/status` | `200` the account, in its new state |
| 48 | `POST` | `/api/v1/admin/users/{id}/password-reset` | `202` a message, **never a token** |
| 49 | `GET` | `/api/v1/admin/categories` | `200` the default categories |
| 50 | `POST` | `/api/v1/admin/categories` | `201` the category |
| 51 | `PATCH` | `/api/v1/admin/categories/{id}` | `200` the category |
| 52 | `GET` | `/api/v1/admin/announcements` | `200` every announcement |
| 53 | `POST` | `/api/v1/admin/announcements` | `201` the announcement |
| 54 | `PATCH` | `/api/v1/admin/announcements/{id}` | `200` the announcement |
| 55 | `GET` | `/api/v1/admin/tip-templates` | `200` the templates |
| 56 | `POST` | `/api/v1/admin/tip-templates` | `201` the template |
| 57 | `PATCH` | `/api/v1/admin/tip-templates/{id}` | `200` the template |
| 58 | `GET` | `/api/v1/admin/settings` | `200` every setting |
| 59 | `PATCH` | `/api/v1/admin/settings/{key}` | `200` the setting |
| 60 | `GET` | `/api/v1/admin/stats` | `200` one aggregate row |
| 61 | `GET` | `/api/v1/admin/stats/top-categories` | `200` the ranked categories |

#### 46. List accounts — `GET /api/v1/admin/users`

`AdminUserResponse` — **exactly eight fields**, and the list is a security boundary:

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `email` | string | Published so the admin screens can list and search by it |
| `fullName` | string | |
| `role` | `STUDENT` \| `ADMIN` | |
| `status` | `ACTIVE` \| `DISABLED` | |
| `academicYear` | string *(optional)* | Omitted when not set |
| `lastLoginAt` | string *(optional)* | Omitted when the account has never signed in |
| `createdAt` | string | |

No password hash and no `token_version` — the projection has no component for either. No search or
filter parameters: the client filters.

Statuses: `200` · `401` · `403`.

#### 47. Enable or disable — `POST /api/v1/admin/users/{id}/status`

| Field | Type | Req | Rules |
|---|---|---|---|
| `status` | `ACTIVE` \| `DISABLED` | ✔ | |

`200` with the account in its new state — which is why there is **no** `GET /admin/users/{id}`.

**`DISABLED` takes effect immediately**: every open session is revoked and the account's existing
tokens stop working (BR-06). That is why this is a `POST .../status` transition and not a `PATCH` of
a field. An administrator **cannot disable their own account** — `409 SELF_DISABLE_FORBIDDEN`, and
the remedy is another administrator. Re-enabling your own account is permitted.

Statuses: `200` · `400` · `404` · `409` · `401` · `403`.

#### 48. Send a reset link — `POST /api/v1/admin/users/{id}/password-reset`

No body. `202` `{ "message": "..." }` — **the response never carries the token**; the raw link goes
only to the notifier, and the row stores a hash.

Not a duplicate of the public request endpoint: this one is ADMIN-only, addressed by **id**, answers
`404` for a missing target (the caller is entitled to know), and leaves an audit row. Statuses:
`202` · `404` · `409` · `401` · `403`.

#### 49–51. Default categories

`GET` returns every shared default category (`user_id IS NULL`), retired ones included — a different
question from `GET /api/v1/categories`, which merges the caller's own rows. `POST` and `PATCH` take
`CategoryResponse` back.

`POST` body — `UpsertDefaultCategoryRequest`:

| Field | Type | Req | Rules |
|---|---|---|---|
| `name` | string | ✔ | Not blank, 1–80 characters; unique among defaults of that type |
| `type` | `INCOME` \| `EXPENSE` | ✔ | |
| `icon` | string | opt | ≤ 50 characters |
| `color` | string | opt | `#RRGGBB` |
| `description` | string | opt | ≤ 255 characters |
| `sortOrder` | number | opt | 0–32767 |
| `isActive` | boolean | opt | `false` creates it already retired |

`PATCH` body — `UpdateDefaultCategoryRequest`: the same fields, all optional, but with **stricter
clearing rules than the student route** — `icon`, `color` and `description` are *not* clearable
here; omit them to leave them unchanged.

Statuses: `200`/`201` · `400` · `404` · `409` · `401` · `403`. **No `DELETE`** — retirement is
`PATCH {"isActive": false}`.

#### 52–54. Announcements

`AnnouncementResponse`: `id`, `title`, `body`, `severity` (`INFO`|`WARNING`|`SUCCESS`), `audience`
(`ALL`|`STUDENTS`|`ADMINS`), `startsAt`, `endsAt` *(optional — absent for an open-ended notice)*,
`isActive`, `createdAt`.

`GET` returns **every** announcement — inactive ones and out-of-window ones included, and including
`ADMINS` rows. This is deliberately not the dashboard's slice.

`POST` body — `CreateAnnouncementRequest`:

| Field | Type | Req | Rules |
|---|---|---|---|
| `title` | string | ✔ | Not blank, ≤ 150 characters |
| `body` | string | ✔ | Not blank |
| `severity` | enum | opt | Defaults to `INFO` |
| `audience` | enum | opt | Defaults to `STUDENTS` — `ALL` must be asked for explicitly |
| `startsAt` | date-time | opt | Defaults to now |
| `endsAt` | date-time | opt | Omit for open-ended. **Must be after `startsAt`** |

`PATCH` body — `UpdateAnnouncementRequest`: **`isActive` only, and it is required.** Content is
create-once; a typo is fixed by posting a corrected notice and withdrawing the old one. There is no
content-update route.

Statuses: `200`/`201` · `400` · `404` · `409` · `401` · `403`. **No `DELETE`.**

#### 55–57. Tip templates

`TipTemplateResponse`: `id`, `code`, `conditionType`, `titleTemplate`, `bodyTemplate`,
`defaultPriority`, `isActive`. `condition_params` is deliberately not published.

`POST` body — `CreateTipTemplateRequest`:

| Field | Type | Req | Rules |
|---|---|---|---|
| `code` | string | ✔ | `^[A-Za-z0-9_]{1,50}$`, stored upper case, unique |
| `titleTemplate` | string | ✔ | ≤ 200 characters |
| `bodyTemplate` | string | ✔ | ≤ 65535 characters |
| `defaultPriority` | number | ✔ | ≥ 0, default `100` |
| `conditionType` | enum | opt | Defaults to `GENERIC` |
| `isActive` | boolean | opt | Defaults to `true` |

`PATCH` body — `UpdateTipTemplateRequest`: all optional.

**`code` is immutable, and the failure mode is silent.** Must equal the stored code when sent — omit
it, or send the same value. A different value is `409 TIP_TEMPLATE_CODE_IMMUTABLE`; sending the
stored code unchanged is accepted so a client can round-trip a full representation. Disable the code
input in the edit form rather than letting a user "change" it.

Statuses: `200`/`201` · `400` · `404` · `409` (`TIP_TEMPLATE_CODE_TAKEN`,
`TIP_TEMPLATE_CODE_IMMUTABLE`) · `401` · `403`. **No `DELETE`.**

#### 58. List settings — `GET /api/v1/admin/settings`

`SystemSettingResponse`: `key`, `value` (always **text**), `valueType`
(`STRING`|`INT`|`DECIMAL`|`BOOLEAN`|`JSON`), `description` *(optional)*, `adjustable`.

**Read `adjustable` to decide whether to enable the control.** Six keys can be changed through the
API; the rest are refused with `409 THRESHOLD_NOT_ADJUSTABLE`. Statuses: `200` · `401` · `403`.

#### 59. Change a threshold — `PATCH /api/v1/admin/settings/{key}`

The key is in the **path**, not the body.

| Field | Type | Req | Rules |
|---|---|---|---|
| `value` | string | ✔ | ≤ 255 characters. Read according to the key: percentages and counts take positive numbers; the baseline-months key takes a whole number 1–12 |

Statuses: `200` · `400` · `404` · `409` `THRESHOLD_NOT_ADJUSTABLE` · `401` · `403`.

#### 60. Usage statistics — `GET /api/v1/admin/stats`

`AdminUsageStatsResponse`: `totalStudents`, `activeStudents`, `disabledStudents`, `activeUsers30d`,
`totalTransactions`, `totalExpenseLogged`, `totalIncomeLogged`, `totalBudgets`, `totalTipsGenerated`,
`totalInsightsGenerated`. All `number`; the money figures are `SUM`s **over many students** — no
per-student money read exists anywhere in this module. Statuses: `200` · `401` · `403`.

#### 61. Top categories — `GET /api/v1/admin/stats/top-categories`

`AdminTopCategoryResponse`: `categoryId`, `categoryName`, `type`, `scope` (`DEFAULT` | `PERSONAL`),
`txnCount`, `totalAmount`, `distinctUsers`. Returns **every** category, most-used first — unused
categories appear with zeros. Statuses: `200` · `401` · `403`.

#### Absent from this module, deliberately

| Not built | Why |
|---|---|
| `GET /api/v1/admin/audit-log` | UC-22 B5 requires the administrator to *log*, not to view. There is no view over the table and no endpoint |
| `DELETE` anywhere | Retirement (`isActive: false`) is BR-07's answer, and there is no delete procedure for announcements or templates |
| `PUT` anywhere | Every writable resource has a `PATCH` |
| `/admin/insights/**`, `/admin/anomalies/**`, `/admin/ai/**` | Module 12, locked. No route, no settings key |
| `GET /admin/users/{id}` | 47 and 48 return the account |

---

## 8. Frontend integration flows

Screen-by-screen, the calls a wiring needs, in order, with the states the list above implies.

### 8.1 Student sign-in and session start

1. `POST /api/v1/auth/login` with `{email, password}`.
2. On `200`: store `accessToken`, `expiresIn` and `user`. Route by `user.role`.
3. On `401 INVALID_CREDENTIALS`: show `message` on the form — **never say which field was wrong**.
4. On `401 ACCOUNT_DISABLED`: show `message`; do not offer "try again".
5. On `429 TOO_MANY_ATTEMPTS`: show `message`, disable submit for a while.
6. On `403`: this is the admin portal and the account is a student — a configuration mistake in the
   UI, not a user error.
7. Schedule a sign-out at `expiresIn` seconds (minus a small margin). There is no refresh.

**Register → sign in is two steps.** `POST /api/v1/auth/register` returns `201` with no body and does
not sign the user in.

### 8.2 Administrator sign-in

Identical, against `POST /api/v1/admin/auth/login`. A `STUDENT` token is refused with `403`. Route
the administrator into `/admin/**`; the guards are UI convenience only.

### 8.3 Home / dashboard

One call: `GET /api/v1/dashboard`.

- Render `periodMonth` in the header — it may not be the calendar month the user expects, because it
  is the database's month.
- `topCategory` **absent** → show "no spending yet this month", not an empty chart.
- `savingsGoalPct` **absent** → no goal is set; hide the progress ring rather than showing 0%.
- `tips[].state` is `NEW` or `PINNED` only — a pin action goes to `POST /api/v1/tips/{id}/state`.
- `announcements` — render `severity` with distinct styling; `endsAt` absent means open-ended.
- Budget bars and unread counts are **not** here: `GET /api/v1/budgets` and
  `GET /api/v1/notifications` respectively.

### 8.4 Record a transaction

1. `GET /api/v1/categories` once, cache, and split into income / expense pickers using `type`
   (filter out `isActive === false`).
2. `POST /api/v1/transactions` with `categoryId`, `amount`, `txnDate`, optional `description`.
3. **Do not send `type` or `source`** — both are read-only and derived.
4. `400` → map `fieldErrors` onto the form. The date cannot be in the future (BR-08).
5. On `201`, use the returned `id`; do not assume it.
6. After a successful write, refetch the dashboard and the current month's budgets — a transaction
   can raise a budget alert, and only a transaction can.

### 8.5 Transaction history and the trash

1. `GET /api/v1/transactions?from=…&to=…` for the live list.
2. Add `includeDeleted=true` to show the trash; distinguish rows by `isDeleted` / `deletedAt`.
3. Delete → `DELETE /api/v1/transactions/{id}` (`204`); restore → `POST …/{id}/restore`.
4. `409 TRANSACTION_ALREADY_DELETED` / `TRANSACTION_NOT_DELETED` mean another tab acted first —
   refetch rather than showing an error.

### 8.6 Budgets

1. `GET /api/v1/budgets?month=yyyy-MM` (omit for the current month).
2. Render `consumedPct` and `consumptionStatus` — never recompute them. `remainingAmount` may be
   negative.
3. Set a limit → `POST /api/v1/budgets`. A second limit for the same category and month is
   `409 BUDGET_ALREADY_EXISTS` — send the user to the **edit** path (`PATCH`).
4. The month and category are fixed after creation; "move it" is delete + create.
5. **A retired category freezes its budget** (`409 CATEGORY_RETIRED` on `PATCH`). Offer "restore the
   category" or "remove the budget".
6. Setting or changing a limit raises **no** notification. Do not imply one.

### 8.7 Notifications

1. `GET /api/v1/notifications` — newest first, `readAt` present iff `isRead`.
2. `POST /api/v1/notifications/{id}/read` to acknowledge; **one-way**, a second call is a no-op.
3. `GET /api/v1/notifications/{id}` does **not** mark read — opening a message and acknowledging it
   are separate acts. If the UI auto-marks on open, that is a deliberate product decision, not
   something the API implies.
4. `linkUrl` is a client-side route — navigate to it directly.

### 8.8 Reports

1. `GET /api/v1/reports?month=yyyy-MM` — any month.
2. Render the **absent** totals block as "no activity in this month", not `$0.00`.
3. Draw `sixMonthTrend` from each point's own `periodMonth`; it does not follow `month`.
4. Show `percentage` as a per-slice figure only — it does not sum to 100.
5. For the day/week chart, call `GET /api/v1/reports/spending` separately, with `granularity`.
   Remember it only answers about the **current** month, and that quiet intervals are **absent**.
6. **Export is client-side.** There is no export endpoint — serialise what you fetched.
7. There is no print/download route; a `?month=` on `/spending` for anything but the current month is
   `400`.

### 8.9 Saving tips

1. Populate the month picker from `GET /api/v1/tips/months` — never from the transaction history.
2. `GET /api/v1/tips?month=yyyy-MM` for the ranked list. An empty array is a real answer.
3. Refresh button → `POST /api/v1/tips/generate`. Safe to press repeatedly; it cannot undo a pin or
   resurrect a dismissal.
4. Pin / dismiss / unpin → `POST /api/v1/tips/{id}/state`. **Do not offer "restore" on a dismissed
   tip** — it is a one-way state.
5. Reading never generates: the screen cannot change itself on load.

### 8.10 Bookmarks and notes

1. `GET /api/v1/bookmarks` — every displayed field, including the tip's words, is in the response.
   No per-row lookup.
2. Save from a tip card → `POST /api/v1/bookmarks` with `{"itemType":"TIP","itemId":tip.id}`. Never
   offer `INSIGHT`.
3. `409 BOOKMARK_ALREADY_EXISTS` is a **recovery**, not an error: the item is already saved. Navigate
   to the existing entry, or `PATCH` the note the user just typed.
4. Note editor → `PATCH /api/v1/bookmarks/{id}` with `{"note":"…"}`; `{"note":""}` clears it. Omit
   the field to leave it unchanged.
5. Un-mark → `DELETE /api/v1/bookmarks/{id}`; idempotent, `204` twice.
6. A dismissed tip **stays** in the saved list — show it, with its `DISMISSED` state.
7. Pinning is not bookmarking: "keep it on my dashboard" is `POST /api/v1/tips/{id}/state`.

### 8.11 Recurring rules

1. `GET /api/v1/recurring-rules` — all statuses, ordered by `nextRunDate`.
2. `lastRunDate` **absent** means the rule has never posted — that is how the UI tells the states
   apart, not by comparing dates.
3. Create with `startDate` and an optional `nextRunDate`; `startDate` becomes read-only afterwards.
4. Pause/resume/end → `PATCH {"status":"…"}`. **`ENDED` is final.**
5. A rule that has posted cannot be deleted (`409 RECURRING_RULE_IN_USE`) — offer `ENDED` instead.
6. A retired category freezes the rule (`409 CATEGORY_RETIRED`), including pause and end.
7. Warn the user that resuming a paused rule posts every period that came due while it was paused,
   dated on their original dates (OB-010).

### 8.12 Profile and preferences

1. `GET /api/v1/profile/me` once on entry; the same shape comes back from every write. Note
   `academicYear` may be `null`.
2. Profile form → `PATCH /api/v1/profile/me`. Send `""` to clear `academicYear`; omit untouched
   fields.
3. Theme / text size → `PATCH /api/v1/profile/me/preferences`; apply immediately and persist. A
   `SYSTEM` theme must follow the OS.
4. `email` and `currency` are read-only here.

### 8.13 Administration

1. Accounts: `GET /api/v1/admin/users`; disable/enable via `POST …/{id}/status`; send a reset link
   via `POST …/{id}/password-reset` (`202`, never a token). **Do not build a self-disable control** —
   it answers `409 SELF_DISABLE_FORBIDDEN`.
2. Default categories: list `GET /admin/categories`; create `POST`; edit `PATCH`; retire with
   `{"isActive": false}`. There is no delete.
3. Announcements: `POST` to publish; `PATCH {"isActive": …}` to show or withdraw. Content cannot be
   edited — post a corrected notice instead.
4. Tip templates: `POST` / `PATCH`. **Disable the `code` field on edit** — it is immutable.
5. Settings: read the list, **honour `adjustable`**, and `PATCH /admin/settings/{key}` with
   `{"value":"…"}` as text. A non-adjustable key is `409`.
6. Statistics: `GET /admin/stats` and `GET /admin/stats/top-categories`. Both are aggregates; there is
   no per-student drill-down and no audit-log route.

---

## 9. Master quick-reference table

All 61 operations in inventory order. This table matches `API_INVENTORY.md` and the live OpenAPI
document exactly: 61 rows, numbering 1–61 contiguous, 43 distinct paths.

| Module | Method | Endpoint | Auth | Role | Purpose |
|--------|--------|----------|------|------|---------|
| M1 — Authentication | `POST` | `/api/v1/auth/register` | Public | — | Register a student account |
| M1 — Authentication | `POST` | `/api/v1/auth/login` | Public | — | Sign in as a student |
| M1 — Authentication | `POST` | `/api/v1/auth/logout` | Bearer | `STUDENT` | Sign out of the current session |
| M1 — Authentication | `POST` | `/api/v1/auth/password-reset/request` | Public | — | Request a password reset link |
| M1 — Authentication | `POST` | `/api/v1/auth/password-reset/verify` | Public | — | Check a password reset token |
| M1 — Authentication | `POST` | `/api/v1/auth/password-reset/complete` | Public | — | Set a new password using a reset token |
| M1 — Authentication | `POST` | `/api/v1/admin/auth/login` | Public | — | Sign in as an administrator |
| M2 — Profile & Preferences | `GET` | `/api/v1/profile/me` | Bearer | `STUDENT` | Get my profile and preferences |
| M2 — Profile & Preferences | `PATCH` | `/api/v1/profile/me` | Bearer | `STUDENT` | Update my profile |
| M2 — Profile & Preferences | `PATCH` | `/api/v1/profile/me/preferences` | Bearer | `STUDENT` | Update my display preferences |
| M3 — Personal Categories | `GET` | `/api/v1/categories` | Bearer | `STUDENT` | List the categories I can use |
| M3 — Personal Categories | `GET` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | Get one of my categories |
| M3 — Personal Categories | `POST` | `/api/v1/categories` | Bearer | `STUDENT` | Create a personal category |
| M3 — Personal Categories | `PATCH` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | Update one of my categories |
| M3 — Personal Categories | `DELETE` | `/api/v1/categories/{id}` | Bearer | `STUDENT` | Delete one of my categories |
| M4 — Transactions | `GET` | `/api/v1/transactions` | Bearer | `STUDENT` | List my transactions |
| M4 — Transactions | `GET` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | Get one of my transactions |
| M4 — Transactions | `POST` | `/api/v1/transactions` | Bearer | `STUDENT` | Record a transaction |
| M4 — Transactions | `PATCH` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | Update one of my transactions |
| M4 — Transactions | `DELETE` | `/api/v1/transactions/{id}` | Bearer | `STUDENT` | Delete one of my transactions |
| M4 — Transactions | `POST` | `/api/v1/transactions/{id}/restore` | Bearer | `STUDENT` | Restore a deleted transaction |
| M5 — Recurring | `GET` | `/api/v1/recurring-rules` | Bearer | `STUDENT` | List my recurring rules |
| M5 — Recurring | `GET` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | Get one of my recurring rules |
| M5 — Recurring | `POST` | `/api/v1/recurring-rules` | Bearer | `STUDENT` | Create a recurring rule |
| M5 — Recurring | `PATCH` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | Update one of my recurring rules |
| M5 — Recurring | `DELETE` | `/api/v1/recurring-rules/{id}` | Bearer | `STUDENT` | Delete one of my recurring rules |
| M6 — Budget & Notifications | `GET` | `/api/v1/budgets` | Bearer | `STUDENT` | List my spending limits |
| M6 — Budget & Notifications | `GET` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | Get one of my spending limits |
| M6 — Budget & Notifications | `POST` | `/api/v1/budgets` | Bearer | `STUDENT` | Set a spending limit |
| M6 — Budget & Notifications | `PATCH` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | Change one of my spending limits |
| M6 — Budget & Notifications | `DELETE` | `/api/v1/budgets/{id}` | Bearer | `STUDENT` | Remove one of my spending limits |
| M6 — Budget & Notifications | `GET` | `/api/v1/notifications` | Bearer | `STUDENT` | List my notifications |
| M6 — Budget & Notifications | `GET` | `/api/v1/notifications/{id}` | Bearer | `STUDENT` | Get one of my notifications |
| M6 — Budget & Notifications | `POST` | `/api/v1/notifications/{id}/read` | Bearer | `STUDENT` | Mark one of my notifications as read |
| M7 — Dashboard | `GET` | `/api/v1/dashboard` | Bearer | `STUDENT` | Get my dashboard |
| M8 — Reports & Export | `GET` | `/api/v1/reports` | Bearer | `STUDENT` | Get my report for a month |
| M8 — Reports & Export | `GET` | `/api/v1/reports/spending` | Bearer | `STUDENT` | Get my spending by day or by week |
| M9 — Saving Tips | `GET` | `/api/v1/tips` | Bearer | `STUDENT` | List my saving tips for a month |
| M9 — Saving Tips | `GET` | `/api/v1/tips/months` | Bearer | `STUDENT` | List the months that have tips |
| M9 — Saving Tips | `POST` | `/api/v1/tips/generate` | Bearer | `STUDENT` | Generate my tips for this month |
| M9 — Saving Tips | `POST` | `/api/v1/tips/{id}/state` | Bearer | `STUDENT` | Pin, dismiss or clear one of my tips |
| M10 — Bookmarks / Notes | `GET` | `/api/v1/bookmarks` | Bearer | `STUDENT` | List my saved items |
| M10 — Bookmarks / Notes | `POST` | `/api/v1/bookmarks` | Bearer | `STUDENT` | Save a tip to look at again |
| M10 — Bookmarks / Notes | `PATCH` | `/api/v1/bookmarks/{id}` | Bearer | `STUDENT` | Set or clear the note on a saved item |
| M10 — Bookmarks / Notes | `DELETE` | `/api/v1/bookmarks/{id}` | Bearer | `STUDENT` | Un-mark a saved item |
| M11 — Administration | `GET` | `/api/v1/admin/users` | Bearer | `ADMIN` | List every account |
| M11 — Administration | `POST` | `/api/v1/admin/users/{id}/status` | Bearer | `ADMIN` | Enable or disable an account |
| M11 — Administration | `POST` | `/api/v1/admin/users/{id}/password-reset` | Bearer | `ADMIN` | Send an account a password reset link |
| M11 — Administration | `GET` | `/api/v1/admin/categories` | Bearer | `ADMIN` | List the shared default categories |
| M11 — Administration | `POST` | `/api/v1/admin/categories` | Bearer | `ADMIN` | Create a shared default category |
| M11 — Administration | `PATCH` | `/api/v1/admin/categories/{id}` | Bearer | `ADMIN` | Change a shared default category |
| M11 — Administration | `GET` | `/api/v1/admin/announcements` | Bearer | `ADMIN` | List every announcement |
| M11 — Administration | `POST` | `/api/v1/admin/announcements` | Bearer | `ADMIN` | Publish an announcement |
| M11 — Administration | `PATCH` | `/api/v1/admin/announcements/{id}` | Bearer | `ADMIN` | Show or withdraw an announcement |
| M11 — Administration | `GET` | `/api/v1/admin/tip-templates` | Bearer | `ADMIN` | List the tip templates |
| M11 — Administration | `POST` | `/api/v1/admin/tip-templates` | Bearer | `ADMIN` | Create a tip template |
| M11 — Administration | `PATCH` | `/api/v1/admin/tip-templates/{id}` | Bearer | `ADMIN` | Change a tip template |
| M11 — Administration | `GET` | `/api/v1/admin/settings` | Bearer | `ADMIN` | List every setting |
| M11 — Administration | `PATCH` | `/api/v1/admin/settings/{key}` | Bearer | `ADMIN` | Change a business threshold |
| M11 — Administration | `GET` | `/api/v1/admin/stats` | Bearer | `ADMIN` | System-wide usage figures |
| M11 — Administration | `GET` | `/api/v1/admin/stats/top-categories` | Bearer | `ADMIN` | Categories ranked by usage |

**Total: 61 operations on 43 paths.**

---

## 10. Noted discrepancies — recorded, not fixed

Per this task's rule 15, an implementation or contract discrepancy found while documenting is
**recorded here rather than silently changed**. None of these blocks the frontend.

| # | Where | The discrepancy | Status |
|---|---|---|---|
| 1 | `OpenApiContractIT` / live document | `/v3/api-docs` is the Spring Boot default but this app serves the document at **`/api-docs`** (configured). A tool assuming the default 404s | Configuration, not a defect. Documented in §1 |
| 2 | Response `@ApiResponses` on most student endpoints | Only `GET /dashboard`, `GET /reports` and `GET /reports/spending` declare `403`; in practice **every** `/api/v1/{profile,categories,transactions,recurring-rules,budgets,notifications,tips,bookmarks}/**` operation returns `403 ACCESS_DENIED` for an administrator token, because `SecurityConfig` requires `hasRole("STUDENT")` | **Discrepancy.** The guide follows the server (§4.3). The OpenAPI annotations under-declare the status |
| 3 | `POST /api/v1/auth/login` | Declares `403`; a student sign-in cannot produce a `403` (it is reached before the role rules). `POST /admin/auth/login` legitimately returns it | **Over-declaration.** Harmless |
| 4 | `CreateBookmarkRequest.itemType` | The schema accepts `INSIGHT`; the service refuses it at runtime with a `400` field error naming UC-17 | **Deliberate** — the value exists in the database `ENUM`. Documented in §7.10 |
| 5 | `frontend/src/app/core/interceptors/mock-delay.interceptor.ts` | The file exists but is **not registered** in `app.config.ts` (`provideHttpClient()` takes no interceptors) | Dead code today. Not changed — out of this task's scope |
| 6 | `SecurityConfig` javadoc vs `application.yml` | The javadoc says the exposure list "also lists `metrics`"; `application.yml` now exposes only `health,info` and says `metrics` is deliberately not exposed | **Stale comment.** The security behaviour is correct either way; the comment is out of date |
| 7 | Response schemas in the OpenAPI document | Almost no response schema declares a `required` list, so a generator will emit every response field as optional. The real rule is the `@JsonInclude(NON_NULL)` set listed per response in §7 | Documented explicitly there, so a client can narrow the types |

---

## Related documentation

- [`API_INVENTORY.md`](API_INVENTORY.md) — the authoritative endpoint list, with the reasoning
  behind each route's shape and the deliberately-absent capabilities
- [`authentication.md`](authentication.md) · [`profile.md`](profile.md) ·
  [`categories.md`](categories.md) · [`transactions.md`](transactions.md) ·
  [`recurring.md`](recurring.md) · [`budgets.md`](budgets.md) ·
  [`notifications.md`](notifications.md) · [`dashboard.md`](dashboard.md) ·
  [`reports.md`](reports.md) · [`tips.md`](tips.md) · [`bookmarks.md`](bookmarks.md) ·
  [`administration.md`](administration.md) — the per-module contracts, with the full reasoning and
  the frontend divergences for each
- [`../SECURITY.md`](../SECURITY.md) — the security decisions behind the contract
- [`../OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — open items, including OB-010 (paused
  rules defer their periods) and OB-013 (amount aggregation)
- [`../modules/`](../modules/) — the per-module engineering reports
