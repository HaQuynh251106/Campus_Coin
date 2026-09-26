# Authentication API

Contract for the seven authentication endpoints: UC-01 (registration), UC-02 (sign-in and
sign-out), UC-03 (password reset) and UC-05 (administrator sign-in).

This document is written so the frontend can be built against it **without reading the Java
source**. Everything the client needs — field names, types, validation rules, status codes, error
codes and the exact response bodies — is here.

- Base URL (development): `http://localhost:8080`
- Base path: `/api/v1`
- Content type: `application/json` for every request and response
- Live specification: `/api-docs`, browsable at `/swagger-ui.html`

## Contents

- [1. Conventions](#1-conventions)
- [2. The error contract](#2-the-error-contract)
- [3. UC-01 Register](#3-uc-01-register--post-apiv1authregister)
- [4. UC-02 Sign in](#4-uc-02-sign-in--post-apiv1authlogin)
- [5. UC-02 Sign out](#5-uc-02-sign-out--post-apiv1authlogout)
- [6. UC-03 Request a reset link](#6-uc-03-request-a-reset-link--post-apiv1authpassword-resetrequest)
- [7. UC-03 Verify a reset token](#7-uc-03-verify-a-reset-token--post-apiv1authpassword-resetverify)
- [8. UC-03 Complete the reset](#8-uc-03-complete-the-reset--post-apiv1authpassword-resetcomplete)
- [9. UC-05 Administrator sign in](#9-uc-05-administrator-sign-in--post-apiv1adminauthlogin)
- [10. Angular integration notes](#10-angular-integration-notes)
- [11. Traceability](#11-traceability)

---

## 1. Conventions

**Field naming.** Request and response fields are `lowerCamelCase`: `fullName`, `accessToken`,
`expiresIn`. This is what Angular sends and receives without translation.

**Authentication.** Protected endpoints take a bearer token:

```
Authorization: Bearer <accessToken>
```

The token is a signed JWT. The client never needs to read or parse it; it is an opaque string that
must be sent back verbatim. Do not decode it to find the role — the role is in the sign-in
response and is the reliable source.

**Identity.** No endpoint accepts a `userId`. The server takes the caller's identity from the
verified token. Sending a `userId` in a body has no effect; it is discarded, not honoured.

**Timestamps.** Errors carry an ISO-8601 UTC instant, e.g. `2026-09-25T10:15:30.123Z`.

**Status codes used in this module**

| Status | Meaning | Client action |
|--------|---------|---------------|
| `200` | Success with a body | Read the body |
| `201` | Created, no body (register) | Navigate to sign-in |
| `204` | Success, no body (logout) | Clear local session, navigate to sign-in |
| `400` | Validation failed, or the reset token is unusable | Show `fieldErrors` on the form if present; otherwise show `message` |
| `401` | Credentials wrong, account disabled, or token missing/invalid/expired | Sign-in: show `message` on the form. Other calls: clear session and return to sign-in |
| `403` | Authenticated but not permitted (a student using the administrator portal or an admin route) | Show a forbidden page; do not silently retry |
| `409` | Email already registered | Show `message` and offer sign-in or password reset |
| `429` | Too many attempts | Show `message`; disable the submit button for a while |
| `500` | Unexpected server failure | Show a generic error; the detail is in the server log, not the response |

---

## 2. The error contract

Every non-2xx response has this body, from every endpoint:

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
|-------|------|-------|
| `timestamp` | string | ISO-8601 UTC |
| `status` | number | Same as the HTTP status |
| `errorCode` | string | **Switch on this.** A stable machine-readable code |
| `message` | string | Human-readable. Safe to display. May be reworded |
| `path` | string | The request path |
| `fieldErrors` | array | Present **only** on validation failures. Absent otherwise |

`fieldErrors[]` has `field` (the request field name) and `message` (safe to display next to that
input). Use `errorCode`, not `message`, for control flow: messages are text and may change.

### Error codes

| `errorCode` | Status | Meaning |
|-------------|--------|---------|
| `VALIDATION_ERROR` | 400 | One or more body fields failed validation. `fieldErrors` is present |
| `MALFORMED_REQUEST` | 400 | Body missing, not JSON, or a field has the wrong type |
| `INVALID_REQUEST` | 400 | A parameter or HTTP method is not supported by the endpoint |
| `INVALID_RESET_TOKEN` | 400 | The reset token is unknown, already used, or expired (BR-04, UC-03 A1) |
| `INVALID_CREDENTIALS` | 401 | Wrong email or password (UC-02 A1) |
| `UNAUTHENTICATED` | 401 | No token, or the token is malformed, tampered with, or expired (UC-02 A3, E1). On a token-guarded endpoint this also covers a revoked session, a stale `token_version` and a disabled account — see the note below |
| `ACCOUNT_DISABLED` | 401 | The account is DISABLED at **sign-in** (UC-02 A2, BR-03). Only the sign-in endpoints return this code |
| `ACCESS_DENIED` | 403 | Authenticated, but this role may not use this endpoint (UC-05 A1, E1) |
| `EMAIL_ALREADY_REGISTERED` | 409 | The address is already registered (UC-01 A1, UAT-01) |
| `DATA_CONFLICT` | 409 | A database rule rejected the write |
| `NOT_FOUND` | 404 | The resource does not exist |
| `TOO_MANY_ATTEMPTS` | 429 | Too many failed sign-ins or reset requests (§7.10) |
| `INTERNAL_ERROR` | 500 | Unexpected failure |

Errors never contain a stack trace, a SQL statement, a database credential or a driver message.

**Where `ACCOUNT_DISABLED` is and is not sent.** The code is returned by the two sign-in endpoints
only, because that is the point at which the student is actively trying to get in and needs to be
told why they cannot. On a **token-guarded** endpoint an account that has been disabled since the
token was issued is rejected by the token filter with `401 UNAUTHENTICATED` instead. The filter
deliberately reports no-token, bad-signature, expired, revoked and disabled identically, so that a
caller holding a token cannot use the difference to learn the state of an account. Both are `401`
and both mean the same thing to the client: clear the session and return to sign-in. Each module
document states which of the two its endpoints actually return.

**Vocabulary note.** Use "sign in" and "sign out" in the UI. The API uses `login`/`logout` in paths
and `INVALID_CREDENTIALS` in codes; those are identifiers, not user-facing text.

---

## 3. UC-01 Register — `POST /api/v1/auth/register`

Creates a STUDENT account with status ACTIVE. Public.

### Request

```json
{
  "fullName": "Alex Nguyen",
  "email": "alex.nguyen@student.campuscoin.edu",
  "password": "Student@123",
  "confirmPassword": "Student@123"
}
```

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `fullName` | string | yes | 2–120 characters, not blank |
| `email` | string | yes | Well-formed email, at most 190 characters |
| `password` | string | yes | 8–72 characters, with at least one upper-case letter, one lower-case letter and one digit |
| `confirmPassword` | string | yes | Must equal `password` |

Exactly these four fields. `role`, `status`, `currency` and `id` are **not** accepted: the account
is always STUDENT/ACTIVE with the system default currency, and a client cannot choose them.

The 72-character limit is not arbitrary — bcrypt ignores everything past 72 bytes, so longer input
would be silently truncated.

### Response `201 Created`

No body. Registration does **not** sign the student in (UC-01 B6); call
[sign in](#4-uc-02-sign-in--post-apiv1authlogin) afterwards.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `VALIDATION_ERROR` | A field rule failed, or the two passwords differ. `fieldErrors` names each offending field |
| 409 | `EMAIL_ALREADY_REGISTERED` | The address is taken (UC-01 A1, UAT-01) |

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "EMAIL_ALREADY_REGISTERED",
  "message": "This email address is already registered. Please sign in or reset your password.",
  "path": "/api/v1/auth/register"
}
```

A mismatched confirmation is reported as a field error on `confirmPassword`, so the form can
highlight that input:

```json
{
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "fieldErrors": [{ "field": "confirmPassword", "message": "Passwords do not match." }]
}
```

---

## 4. UC-02 Sign in — `POST /api/v1/auth/login`

Authenticates a **student** and opens a security session. Public.

### Request

```json
{ "email": "alex.nguyen@student.campuscoin.edu", "password": "Student@123" }
```

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `email` | string | yes | Not blank, well-formed email, at most 190 characters |
| `password` | string | yes | Not blank, at most 72 characters |

The password policy is **not** enforced here — only on registration and reset. An existing account
whose password predates a policy change must still be able to sign in.

### Response `200 OK`

```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9.eyJpc3MiOiJjYW1wdXMtY29pbiIs...",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": {
    "id": 2,
    "fullName": "Alex Nguyen",
    "email": "alex.nguyen@student.campuscoin.edu",
    "role": "STUDENT"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| `accessToken` | string | Send as `Authorization: Bearer <value>`. Stored by the server only as a hash |
| `tokenType` | string | Always `"Bearer"` |
| `expiresIn` | number | Lifetime in **seconds** from now (7200 = 2 hours by default) |
| `user.id` | number | Account id |
| `user.fullName` | string | Display name |
| `user.email` | string | Account email |
| `user.role` | string | `"STUDENT"` or `"ADMIN"` |

The response contains no password hash, no token version, no session token and no internal
security field. There is no refresh token: no use case defines a refresh flow, so the client signs
in again when the token expires.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `VALIDATION_ERROR` | A field rule failed |
| 401 | `INVALID_CREDENTIALS` | The email is unknown **or** the password is wrong — deliberately indistinguishable (UC-02 A1) |
| 401 | `ACCOUNT_DISABLED` | The account is DISABLED; the message tells the student to contact the administrator (UC-02 A2) |
| 403 | `ACCESS_DENIED` | The account is an ADMIN account; it must use the administrator portal (UC-05) |
| 429 | `TOO_MANY_ATTEMPTS` | More than 5 consecutive failures for this address (§7.10) |

Wrong password and unknown email return the **same** status and the same message. Do not try to
distinguish them in the UI.

An `ADMIN` account signing in here is refused with 403 — this is not an error the student can fix
by retrying, so route administrators to the administrator portal.

---

## 5. UC-02 Sign out — `POST /api/v1/auth/logout`

Revokes the session that presented the token. **Requires a bearer token.**

### Request

No body.

### Response `204 No Content`

No body. The token stops working immediately, even though its own expiry has not passed.

Only the current session is revoked. Signing out on one device does not sign the student out on
another.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `UNAUTHENTICATED` | No token, or the token is invalid, expired, or already revoked |

A second sign-out with the same token returns 401 rather than 204, because the token is no longer
valid. Treat 401 here as "already signed out" and clear the local session anyway.

---

## 6. UC-03 Request a reset link — `POST /api/v1/auth/password-reset/request`

Sends a reset link if the address belongs to an account. Public.

### Request

```json
{ "email": "alex.nguyen@student.campuscoin.edu" }
```

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `email` | string | yes | Not blank, well-formed email, at most 190 characters |

### Response `200 OK` — always this body

```json
{ "message": "If the email exists, a reset link has been sent." }
```

This is the **only** success body, for a registered address and an unregistered one alike
(UC-03 B3). The wording is fixed by the requirement. Do not add a branch that says "we couldn't
find that email": that would turn the endpoint into a way of discovering which addresses are
registered (UC-03 A2).

The link is valid for 30 minutes and is single-use. Requesting a new link invalidates the
account's previous unused links.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `VALIDATION_ERROR` | The email field failed validation |
| 429 | `TOO_MANY_ATTEMPTS` | More than 3 reset requests for this address within the window |

---

## 7. UC-03 Verify a reset token — `POST /api/v1/auth/password-reset/verify`

Checks that the token from the reset link is still usable, so the new-password screen may open.
Public.

### Request

```json
{ "token": "xRZF_8OZNfFhQWMqZxvVbAdC-tq24rkmDzSCa0h2fWc" }
```

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `token` | string | yes | Not blank, at most 200 characters |

**The token travels in the body, never in the URL.** A query parameter would be written to access
logs, proxy logs and browser history by components this application does not control.

The token is **not** consumed by verifying, so a student who opens the link and then reloads the
page can still use it until it expires. Verify as often as you like.

### Response `200 OK`

```json
{ "valid": true }
```

Always `true` on a 200. No account information is returned: identifying the account to whoever
holds the link would tell an interceptor whose password they are about to change.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `INVALID_RESET_TOKEN` | The token is unknown, already used, or expired (BR-04, UC-03 A1) |
| 400 | `VALIDATION_ERROR` | The token field is blank or too long |

On `INVALID_RESET_TOKEN`, offer to send a new link (UC-03 A1).

---

## 8. UC-03 Complete the reset — `POST /api/v1/auth/password-reset/complete`

Consumes the token and sets the new password. Public.

### Request

```json
{
  "token": "xRZF_8OZNfFhQWMqZxvVbAdC-tq24rkmDzSCa0h2fWc",
  "newPassword": "Student@456",
  "confirmPassword": "Student@456"
}
```

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `token` | string | yes | Not blank, at most 200 characters |
| `newPassword` | string | yes | Same policy as registration: 8–72 characters, upper, lower and a digit |
| `confirmPassword` | string | yes | Must equal `newPassword` |

### Response `200 OK`

```json
{ "message": "Your password has been reset. Please sign in with your new password." }
```

On success **every open session of the account is revoked and its token version is incremented**
(BR-03): any access token the student holds, on any device, stops working immediately. This is
deliberate — after a reset, nobody but the person who set the new password should stay signed in.
Send the student to sign-in, and clear any cached token.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `VALIDATION_ERROR` | A field rule failed, or the two passwords differ (field error on `confirmPassword`) |
| 400 | `INVALID_RESET_TOKEN` | The token is unknown, already used, or expired (UAT-03) |

A rejected attempt does **not** consume the token, so a student who mistypes the confirmation can
correct it and submit again.

---

## 9. UC-05 Administrator sign in — `POST /api/v1/admin/auth/login`

Authenticates an **administrator**. Public. Direct access only — there is no link to it from the
student UI (UC-05 B1).

### Request

Identical to [sign in](#4-uc-02-sign-in--post-apiv1authlogin):

```json
{ "email": "admin@campuscoin.edu", "password": "Admin@123" }
```

### Response `200 OK`

Identical in shape to the student sign-in response, with `"role": "ADMIN"`.

### Failures

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `VALIDATION_ERROR` | A field rule failed |
| 401 | `INVALID_CREDENTIALS` | Unknown email or wrong password |
| 401 | `ACCOUNT_DISABLED` | The account is DISABLED |
| 403 | `ACCESS_DENIED` | The credentials are correct but the account is not an ADMIN (UC-05 A1) |
| 429 | `TOO_MANY_ATTEMPTS` | Too many consecutive failures |

The role check happens **after** the password check, so this endpoint cannot be used to discover
which addresses are administrator accounts.

### Every other `/api/v1/admin/**` route

The whole `/api/v1/admin/**` prefix requires the ADMIN role, enforced server-side (UC-05 E1).
A student token gets `403 ACCESS_DENIED`; no token gets `401 UNAUTHENTICATED`. The administrator UI
is not protected by hiding it — the server refuses the call regardless of what the client shows.

---

## 10. Angular integration notes

### 10.1 The current frontend must be rewired

`frontend/src/app/core/services/auth.service.ts` is **mock-only**: it reads from
`MOCK_USERS`, fakes a delay, and never calls the API. Several of its field names do not match the
database, so it cannot simply be pointed at the backend. It needs the following changes. **No
Angular source was changed in this module** — this is the specification for the change.

| Current code | Problem | Required change |
|--------------|---------|-----------------|
| `login()` returns `Observable<User>`, ignores the password | Always succeeds | Call `POST /api/v1/auth/login`; store `accessToken` and `expiresIn` alongside the user |
| `login()` auto-creates a demo session for an unknown email | Silent fake success | Remove. A 401 `INVALID_CREDENTIALS` is a real failure |
| `adminLogin()` returns the first mock ADMIN | Always succeeds | Call `POST /api/v1/admin/auth/login`; handle 403 for a student account |
| `register()` returns `Observable<User>` and signs in | Backend returns no body and does not sign in | Call `POST /api/v1/auth/register`, expect `201` with no body, then navigate to sign-in |
| `requestPasswordReset()` returns `of(true)` | No call made | Call `POST .../password-reset/request`; always show the returned generic message |
| `resetPassword()` returns `of(true)` | No call made | Call `.../password-reset/verify` then `.../password-reset/complete` |
| `logout()` only clears local state | The server session stays open | Call `POST /api/v1/auth/logout` with the bearer token, **then** clear local state |
| `localStorage['campus_coin_user']` holds a whole user object | No token is stored, so no authenticated call is possible | Store `accessToken` (and optionally the summary); never store a password |
| `User.name` | Nothing in the API returns `name` | The API field is `fullName` |
| `User.studentId` | Not returned by any authentication endpoint | Remove from the auth flow; it is not part of this module |
| `User.avatar` | Not returned; a hard-coded Unsplash URL | Use a local placeholder or initials |
| `User.monthlyAllowance`, `User.savingsGoal` | Not returned by any authentication endpoint | These belong to later modules (profile, budgets) |
| `User.university`, `User.major`, `User.academicYear` | Not returned by any authentication endpoint | Profile data (UC-04, module 2) |
| `User.id: string` (`'user-001'`) | The API returns a **number** | Change the type to `number` |
| `User.status === 'DISABLED'` checked client-side | The server enforces this | Rely on the error code: `ACCOUNT_DISABLED` at sign-in, `UNAUTHENTICATED` on a token-guarded call |
| `restoreSession()` trusts `localStorage` and defaults to a student | A tampered value grants access in the UI | Treat the stored token as untrusted: a `401` on the next call signs the user out |

The user summary actually returned by sign-in is exactly:

```ts
interface AuthUser {
  id: number;
  fullName: string;
  email: string;
  role: 'STUDENT' | 'ADMIN';
}

interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: AuthUser;
}
```

### 10.2 Sending the token

Attach the token to every protected call through an HTTP interceptor:

```
Authorization: Bearer <accessToken>
```

Do not attach it to `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/admin/auth/login` or
the three password-reset endpoints — they are public and do not require it.

### 10.3 Base URL

- Development: `environment.development.ts` already points at `http://localhost:8080/api`, and
  `frontend/proxy.conf.json` proxies `/api` to `http://localhost:8080`. Both are correct and
  already match the backend's default port.
- Production: same-origin (`/api`), terminated by TLS at the reverse proxy.

Note that the API's own base path is `/api/v1`. The proxy passes `/api` through unchanged, so the
frontend must request `/api/v1/...`.

### 10.4 Handling each status

| Status | `errorCode` | Angular behaviour |
|--------|-------------|-------------------|
| 400 | `VALIDATION_ERROR` | Map `fieldErrors[]` onto the reactive form controls (`field` is the control name). If `fieldErrors` is absent, show `message` as a form-level error |
| 400 | `INVALID_RESET_TOKEN` | "This link is no longer valid." Offer a button that calls the request endpoint again |
| 401 | `INVALID_CREDENTIALS` | Show `message` on the sign-in form. Do not say whether the email or the password was wrong |
| 401 | `ACCOUNT_DISABLED` | Show `message`, which names the reason; do not offer "try again" |
| 401 | `UNAUTHENTICATED` | Clear the stored token and redirect to sign-in. The guard cannot detect an expired token on its own — an interceptor must handle this |
| 403 | `ACCESS_DENIED` | Navigate to a forbidden page. Do not retry |
| 409 | `EMAIL_ALREADY_REGISTERED` | Show `message` and offer links to sign-in and to password reset |
| 429 | `TOO_MANY_ATTEMPTS` | Show `message`, disable the submit button, and let the user try again later |
| 500 | `INTERNAL_ERROR` | Generic error page. The distinction between codes is not useful to the user |

Because the token can expire or be revoked server-side at any moment (a password reset on another
device revokes it), the only reliable way to know a session is still valid is to make a call and
handle 401. Never treat a stored token as proof of a live session.

### 10.5 Routing

| Route | Access | Guard behaviour |
|-------|--------|-----------------|
| `/login`, `/register`, `/forgot-password`, `/reset-password` | Anonymous | If already signed in, redirect to the dashboard |
| `/admin/login` | Anonymous | Direct URL only; if signed in as ADMIN, redirect to the admin dashboard |
| Student area | `role === 'STUDENT'` | Redirect to `/login` if no token |
| `/admin/**` | `role === 'ADMIN'` | Redirect to `/admin/login` if not an ADMIN |

The guards are a convenience for the user, **not** the security boundary. Every `/api/v1/admin/**`
call is refused server-side for a non-admin token regardless of what the client allows, so a user
who bypasses a guard in the browser still cannot read or change administrator data.

### 10.6 Token storage

`localStorage` is the straightforward choice and matches the existing code's approach. It is
vulnerable to XSS, so the Angular app must not render untrusted HTML without sanitising it. An
httpOnly cookie would remove that exposure but would require CSRF protection and a change to the
API contract, which is out of scope here. If you choose `localStorage`:

- store the token, never the password;
- clear it on sign-out, on `401`, and after a completed password reset;
- do not keep it in a place that survives a "sign out everywhere" the user cannot see.

`expiresIn` (seconds) is returned so you can proactively sign the user out just before the token
expires rather than waiting for a failed call.

### 10.7 Driving the reset flow

The reset link the backend produces is:

```
http://localhost:4200/reset-password?token=<raw-token>
```

1. The reset-password route reads `token` from the query string.
2. Call `POST /api/v1/auth/password-reset/verify` with that token.
   - `200` → show the new-password form.
   - `400 INVALID_RESET_TOKEN` → show "this link is no longer valid" and offer to request a new one.
3. On submit, call `POST /api/v1/auth/password-reset/complete` with the token and the new password.
4. On `200`, show the confirmation and navigate to sign-in. Any cached token is now invalid, so
   clear it.

`RESET_LINK_BASE_URL` on the server controls that base URL; it defaults to
`http://localhost:4200/reset-password`, which is why the frontend route must exist at that path.

---

## 11. Traceability

Each endpoint traced from the requirement through to the database objects it touches.

| Endpoint | UC | Business rules | Database objects |
|----------|----|----------------|------------------|
| `POST /auth/register` | UC-01 B1–B6, A1, A2 | BR-01 (unique email, hashed password) | `users` (`uk_users_email`, `fk`-free insert); `system_settings.app.currency`; shared default `categories` (`user_id IS NULL`, UC-01 B5 — read, not created) |
| `POST /auth/login` | UC-02 B2, B3, A1, A2 | BR-01, BR-03 (ACTIVE status) | `users` (read `password_hash`, `status`, `token_version`; write `last_login_at`); `user_sessions` (insert, `uk_sessions_token`); `system_settings.auth.session_ttl_minutes` |
| `POST /auth/logout` | UC-02 B5, A3 | BR-03 | `user_sessions` (update `revoked_at`, `revoked_reason='LOGOUT'`) |
| `POST /auth/password-reset/request` | UC-03 B2, B3, A2 | BR-04 (one-time, 30 min), §7.10 | `sp_create_password_reset_token`; `password_reset_tokens`; `system_settings.auth.reset_token_ttl_minutes` |
| `POST /auth/password-reset/verify` | UC-03 B5, A1 | BR-04 | `sp_verify_password_reset_token` (read-only); `password_reset_tokens` |
| `POST /auth/password-reset/complete` | UC-03 B7, A1 | BR-03, BR-04 | `sp_complete_password_reset` (consumes token, updates `users.password_hash`, bumps `users.token_version`, revokes `user_sessions`); `password_reset_tokens` |
| `POST /admin/auth/login` | UC-05 B1, B3, A1, E1 | BR-03 | Same objects as `POST /auth/login`, plus the `users.role` check against `ADMIN` |

Business rules the **database** owns, and which the Java layer therefore does not re-implement:
the uniqueness of `users.email`, the reset token's one-time use and expiry, the atomic
consume-and-validate step, the `token_version` bump, session revocation after a reset, and account
status. Java owns what the schema provides no mechanism for: bcrypt hashing, JWT issue and verify,
inserting `user_sessions` rows, and updating `users.last_login_at`.

Requirements with no endpoint here, and why:

| Requirement | Status |
|-------------|--------|
| UC-04 Profile, UC-27 Preferences | Built — module 2, see [profile.md](profile.md) |
| UC-06 … UC-23 | Built — modules 3–11, see [API_INVENTORY.md](API_INVENTORY.md) |
| UC-11 CSV import, UC-08 AI categorisation, UC-17 insights, UC-24/UC-25 anomalies, UC-26 recent activity | **Implemented but locked** — module 12; endpoints 62–76 in [API_INVENTORY.md](API_INVENTORY.md), built and tested, awaiting the project owner's approval to ship |
| Email verification (UC-01 BA note) | Explicitly not required by UC-01 |
| Token refresh | No use case defines it, although `user_sessions.refresh_token_hash` exists |
| Change password while signed in | Not a use case in this module |
| Session listing / revocation by id | Not a use case in this module |

---

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, the auth and error interceptors to install, the shared error contract, the
  enum reference and the master table of all 76 operations
- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [profile.md](profile.md) — module 2, the profile these tokens give access to
- [../testing/manual/MODULE_01_MANUAL_TEST.md](../testing/manual/MODULE_01_MANUAL_TEST.md) — the
  hand-run procedure
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
