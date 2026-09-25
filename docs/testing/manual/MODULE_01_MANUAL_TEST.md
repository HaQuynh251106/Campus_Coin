# Module 1 — Authentication — Manual Test Procedure

A hand-executed test procedure for the authentication module of Campus Coin: **UC-01**
(registration), **UC-02** (sign-in and sign-out), **UC-03** (password reset) and **UC-05**
(administrator sign-in).

A QA engineer or the project owner follows this document against a **running stack**. No source
code needs to be read: every request, field name, status code and error code below is taken from
the live contract in [`docs/api/authentication.md`](../../api/authentication.md),
[`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 1–7),
[`docs/SECURITY.md`](../../SECURITY.md) and [`docs/CREDENTIALS.md`](../../CREDENTIALS.md).

The document tests the **HTTP API**. The current Angular application is mock-only (see
[M1-22](#m1-22-frontend-routing-rules-in-the-browser)), so the interface cannot be used to drive
most of these cases; Swagger UI or `curl` is used instead.

## Contents

- [1. Scope](#1-scope)
- [2. Prerequisites](#2-prerequisites)
- [3. How to fill in the token placeholders](#3-how-to-fill-in-the-token-placeholders)
- [4. Test accounts](#4-test-accounts)
- [5. The error contract, in one table](#5-the-error-contract-in-one-table)
- [6. Test cases](#6-test-cases)
- [7. Traceability](#7-traceability)
- [8. Not covered by this document](#8-not-covered-by-this-document)

---

## 1. Scope

| Use case | Covered by |
|----------|------------|
| UC-01 Register | M1-01 … M1-05 |
| UC-02 Sign in | M1-06 … M1-09, M1-18, M1-20 |
| UC-02 Sign out | M1-10 |
| UC-03 Password reset | M1-11 … M1-15, M1-21 |
| UC-05 Administrator sign-in | M1-16, M1-17, M1-19 |
| Frontend routing rules | M1-22 (partly blocked — see the case) |

Business rules referenced: **BR-01** (unique email, hashed password), **BR-03** (account status,
session and token-version invalidation), **BR-04** (reset token: one-time, 30-minute lifetime).
Rate limiting is **§7.10** of the requirements. **UAT-01** and **UAT-03** are the acceptance tests
for duplicate email and for a spent reset token.

There are exactly **seven** endpoints in this module (API_INVENTORY.md, endpoints 1–7). Anything
not in that list does not exist: there is no refresh endpoint, no change-password endpoint and no
session-listing endpoint.

## 2. Prerequisites

1. **MySQL 8 is running with the schema and seed data loaded.** From the repository root:

   ```bash
   docker compose up -d
   docker compose ps
   ```

2. **The backend is running** on `http://localhost:8080`, with the environment variables it
   requires already exported in your shell — `JWT_SECRET`, `DB_PASSWORD` and, if you have moved
   things, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `CORS_ALLOWED_ORIGINS`,
   `RESET_LINK_BASE_URL`. The values live in `.env` (gitignored); **never write a value from
   `.env` into this document or into any committed file.**

   ```bash
   set -a && . ./.env && set +a && cd backend && DB_PASSWORD="$MYSQL_PASSWORD" ./mvnw spring-boot:run
   ```

3. **The stack answers.** `curl -s http://localhost:8080/actuator/health` returns the summary
   health status.

4. **A way to send requests.** Either:
   - **Swagger UI** at `http://localhost:8080/swagger-ui.html` — use the **Authorize** button to
     attach a bearer token once you have one (the scheme is declared by `OpenApiConfig`); or
   - **curl**, using these two shapes throughout the document:

     ```bash
     # Public call (no token)
     curl -s -i -X POST http://localhost:8080/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"email":"...","password":"..."}'

     # Token-guarded call
     curl -s -i -X GET http://localhost:8080/api/v1/profile/me \
       -H "Authorization: Bearer ${USER_A_JWT}"
     ```

5. **The password-reset link sink.** There is no mail provider. In the `dev` profile (active by
   default) the reset link is appended to a file instead of being emailed:

   ```
   backend/target/password-reset-dev.log
   ```

   Each line is `<timestamp> | <email> | <reset link>`. Copy the `token=` value from the query
   string of the link. **The token is never logged and never returned in an HTTP response** —
   this file is the only way to obtain it, and it is gitignored. Do not copy a token into this
   document or into any committed file.

6. **Database access**, needed only for M1-09 (there is no API in this module that disables an
   account — that is UC-22, in module 11). Use Adminer at `http://localhost:8081`, or any MySQL
   client, signing in with the database account named in `.env` (`MYSQL_USER` /
   `MYSQL_PASSWORD`). Never use `root` for anything other than looking.

### Conventions used below

- Every request and response is `application/json`; the base path is `/api/v1`.
- A body shown as `{ ... }` is the **exact** JSON to send, apart from the text in angle brackets,
  which you supply.
- `<password from docs/CREDENTIALS.md>` means: read the value from
  [`docs/CREDENTIALS.md`](../../CREDENTIALS.md). **No password value appears in this document.**
- Every error response has the same shape (`timestamp`, `status`, `errorCode`, `message`, `path`,
  and `fieldErrors` only on a validation failure). Compare on `errorCode`, not on `message`:
  messages are text and may be reworded.
- Record each case's outcome in its **Result** line: `- [ ] Pass / Fail`.

### Notes that affect how you run the sequence

- Cases are written to be run **in order**. M1-02, M1-06 and the reset cases reuse the account
  registered in M1-01; M1-10 and M1-17 reuse the second seeded student.
- M1-20 locks a sign-in address out for **15 minutes** and M1-21 does the same for reset requests.
  Both are deliberately placed last. M1-20 uses a throwaway address so no real account is
  affected.
- The email used in M1-01 must be **unique**. If you re-run the whole document, change its local
  part, or you will hit M1-02's conflict.
- A **successful** sign-in clears that address's failure counter, and M1-06 signs in successfully,
  so the two failures recorded by M1-07 and M1-08 do not accumulate against the account.

## 3. How to fill in the token placeholders

Exactly four placeholders appear in this document, and they hold **tokens, never passwords**:

| Placeholder | Holds |
|-------------|-------|
| `${USER_A_JWT}` | The access token of the student registered in M1-01 (`qa.m1.student@student.campuscoin.edu`) |
| `${USER_B_JWT}` | The access token of the second seeded student, Bella Tran |
| `${ADMIN_JWT}` | The access token of the seeded administrator |
| `${JWT}` | The generic form used where a document needs one token and does not care whose it is; here it holds a **second** token of the same second student, used in M1-10 |

**How to obtain one:** call the sign-in endpoint and copy the `accessToken` value out of the
response body. Nothing else in the response is a token.

```
POST /api/v1/auth/login          → accessToken  →  ${USER_A_JWT} / ${USER_B_JWT} / ${JWT}
POST /api/v1/admin/auth/login    → accessToken  →  ${ADMIN_JWT}
```

Example:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<the student email>","password":"<the student password>"}'
# → {"accessToken":"<copy this value>","tokenType":"Bearer","expiresIn":7200,"user":{...}}
```

Rules for handling these values:

- Keep them in your shell (`export USER_A_JWT='...'`) or in the Swagger **Authorize** dialog.
- **Never paste a token into this document, into any other file in the repository, or into a bug
  report.** A token is a live credential for the account until it expires or is revoked.
- Replace a placeholder's value when a later case signs in again, and note that the previous value
  is then dead (revoked by sign-out or by a completed password reset — that is what M1-10 and
  M1-15 verify).
- A token is opaque: send it back verbatim. Do **not** decode it to read the role — the `user.role`
  field of the sign-in response is the reliable source.

## 4. Test accounts

Seeded by `db/05_seed.sql`; the passwords are recorded in
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md).

| Role | Email | Password |
|------|-------|----------|
| Administrator | `admin@campuscoin.edu` | see `docs/CREDENTIALS.md` |
| Student | `an.nguyen@student.campuscoin.edu` (Alex Nguyen) | see `docs/CREDENTIALS.md` |
| Student | `binh.tran@student.campuscoin.edu` (Bella Tran) | see `docs/CREDENTIALS.md` |

A fourth account is created by M1-01 and is used by M1-06 and M1-11 … M1-15:
`qa.m1.student@student.campuscoin.edu`, with a password **you choose**. Both are test artefacts and
may be left in the database; M1-09's throwaway account should be restored to `ACTIVE` at the end of
that case.

## 5. The error contract, in one table

| Status | `errorCode` | Meaning in this module |
|--------|-------------|------------------------|
| 201 | — | Register succeeded. **No body.** |
| 204 | — | Sign-out succeeded. **No body.** |
| 400 | `VALIDATION_ERROR` | A body field failed a rule; `fieldErrors[]` is present and names the field |
| 400 | `MALFORMED_REQUEST` | The body is missing, or is not JSON |
| 400 | `INVALID_RESET_TOKEN` | The reset token is unknown, already used, or expired (BR-04) |
| 401 | `INVALID_CREDENTIALS` | Wrong email **or** wrong password — deliberately indistinguishable |
| 401 | `ACCOUNT_DISABLED` | The account is `DISABLED`; returned **only** by the two sign-in endpoints |
| 401 | `UNAUTHENTICATED` | No token, or the token is malformed, tampered with, expired, revoked, or was invalidated by a password reset or an account disable |
| 403 | `ACCESS_DENIED` | Authenticated (or credentials correct) but this role may not use this endpoint |
| 409 | `EMAIL_ALREADY_REGISTERED` | The address is already registered |
| 429 | `TOO_MANY_ATTEMPTS` | Too many failed sign-ins, or too many reset requests |
| 500 | `INTERNAL_ERROR` | Unexpected failure; the detail is in the server log, not the response |

`fieldErrors[]` appears **only** on a `VALIDATION_ERROR`. Errors never contain a stack trace, a SQL
statement, a table or column name, or a driver message.

---

## 6. Test cases

### M1-01 Register a new student — happy path

**Covers:** UC-01 B1–B6, BR-01

**Preconditions:** Stack running. `qa.m1.student@student.campuscoin.edu` is **not** yet registered
(if you are re-running, change the local part).

**Steps:**

1. Send `POST /api/v1/auth/register` with this body, choosing a password that satisfies the policy
   (8–72 characters, at least one upper-case letter, one lower-case letter and one digit) and
   confirming it exactly:

   ```json
   {
     "fullName": "QA Module One Student",
     "email": "qa.m1.student@student.campuscoin.edu",
     "password": "<a policy-compliant password you choose>",
     "confirmPassword": "<the same value>"
   }
   ```

2. Send `POST /api/v1/auth/login` with the same email and password.

**Expected result:**

- Step 1: `201 Created` with an **empty body**. Specifically **no `accessToken`** — registration
  does not sign the student in (UC-01 B6).
- Step 2: `200 OK`, proving the account exists and the password was stored and verified correctly.
  The body has `accessToken`, `"tokenType": "Bearer"`, `expiresIn` (seconds; 7200 by default, from
  `auth.session_ttl_minutes`), and `user` with `id` (a number), `fullName`
  `"QA Module One Student"`, `email` and `"role": "STUDENT"`.
- The response contains no password, no password hash, no token version and no session id.

**Result:** - [ ] Pass / Fail

---

### M1-02 Register an email that is already registered

**Covers:** UC-01 A1, UAT-01, BR-01

**Preconditions:** M1-01 passed; `qa.m1.student@student.campuscoin.edu` exists.

**Steps:**

1. Send `POST /api/v1/auth/register` again with the **same email** as M1-01 (a different
   `fullName` is fine, and the passwords still have to match each other):

   ```json
   {
     "fullName": "Duplicate Attempt",
     "email": "qa.m1.student@student.campuscoin.edu",
     "password": "<a policy-compliant password you choose>",
     "confirmPassword": "<the same value>"
   }
   ```

2. Send the same request with the email in **upper case**, e.g.
   `QA.M1.STUDENT@STUDENT.CAMPUSCOIN.EDU`.

**Expected result:** both calls return `409 Conflict` with
`"errorCode": "EMAIL_ALREADY_REGISTERED"` and the message
"This email address is already registered. Please sign in or reset your password." No account is
created and no existing account is modified. The message must **not** name a database constraint,
a table or a column.

**Why step 2 matters:** the address is unique case-insensitively (BR-01), so a second account must
not be creatable by changing the case.

**Result:** - [ ] Pass / Fail

---

### M1-03 Register with a password that is too weak

**Covers:** UC-01 A2, BR-01

**Preconditions:** Stack running.

**Steps:** send each of these in turn as `POST /api/v1/auth/register`, with a fresh unique email
each time (for example `qa.m1.weak1@student.campuscoin.edu`, `…weak2…`, `…weak3…`, `…weak4…`), and
`confirmPassword` copied from `password` in each body:

1. `"password": "Abc1"` — shorter than 8 characters.
2. `"password": "abcdefgh1"` — no upper-case letter.
3. `"password": "ABCDEFGH1"` — no lower-case letter.
4. `"password": "Abcdefgh"` — no digit.

**Expected result:** all four return `400 Bad Request` with `"errorCode": "VALIDATION_ERROR"` and
`"message": "Request validation failed."`, and a `fieldErrors` array containing an entry whose
`field` is `"password"`. The entry's `message` names the rule that failed (length, upper-case,
lower-case, digit — in one of the forms documented in `docs/api/authentication.md` §3). **No
account is created** — verify by attempting to sign in with one of the rejected addresses: it must
return `401 INVALID_CREDENTIALS`.

**Result:** - [ ] Pass / Fail

---

### M1-04 Register with a mismatched password confirmation

**Covers:** UC-01 A2

**Preconditions:** Stack running.

**Steps:** send `POST /api/v1/auth/register` with a fresh unique email, a policy-compliant
`password`, and a **different** `confirmPassword`:

```json
{
  "fullName": "Mismatch Attempt",
  "email": "qa.m1.mismatch@student.campuscoin.edu",
  "password": "<a policy-compliant password you choose>",
  "confirmPassword": "<a different, also policy-compliant value>"
}
```

**Expected result:** `400 Bad Request`, `"errorCode": "VALIDATION_ERROR"`,
`"message": "Request validation failed."`, and `fieldErrors` containing exactly one entry with
`"field": "confirmPassword"` and a message stating that the passwords do not match. The error is
reported on `confirmPassword` (not on `password`) so the form can highlight the right input, and
**neither password value appears anywhere in the response**.

**Result:** - [ ] Pass / Fail

---

### M1-05 Register with a malformed email address

**Covers:** UC-01 A2

**Preconditions:** Stack running.

**Steps:** send `POST /api/v1/auth/register` twice, each with a valid `fullName` and matching
policy-compliant passwords:

1. `"email": "not-an-email"`.
2. `"email": ""` (empty string).

**Expected result:** both return `400 Bad Request` with `"errorCode": "VALIDATION_ERROR"` and a
`fieldErrors` entry whose `field` is `"email"`. No account is created.

**Result:** - [ ] Pass / Fail

---

### M1-06 Sign in as a student, and use the token

**Covers:** UC-02 B2, B3

**Preconditions:** M1-01 passed.

**Steps:**

1. Send `POST /api/v1/auth/login`:

   ```json
   {
     "email": "qa.m1.student@student.campuscoin.edu",
     "password": "<the password you chose in M1-01>"
   }
   ```

2. Copy `accessToken` from the response into `USER_A_JWT`, e.g.
   `export USER_A_JWT='<the value>'`.
3. Send a token-guarded call with it: `GET /api/v1/profile/me` with header
   `Authorization: Bearer ${USER_A_JWT}`.

**Expected result:**

- Step 1: `200 OK`. Body fields, exactly: `accessToken` (a non-empty string), `"tokenType":
  "Bearer"`, `expiresIn` (a positive number of **seconds**), and `user` containing `id` (number),
  `fullName`, `email`, `role` = `"STUDENT"`. Nothing else: no password, no hash, no token version,
  no refresh token.
- Step 3: `200 OK` with a profile body. This is what "the token is usable" means: the session row
  exists, is not revoked, is not expired, and the token's `tv` claim matches the account's current
  `token_version`.
- **Keep `${USER_A_JWT}`** — M1-15 uses it to show that a password reset kills it.

**Result:** - [ ] Pass / Fail

---

### M1-07 Sign in with the wrong password

**Covers:** UC-02 A1

**Preconditions:** `qa.m1.student@student.campuscoin.edu` exists.

**Steps:** send `POST /api/v1/auth/login` with the correct email and a **deliberately wrong**
password:

```json
{
  "email": "qa.m1.student@student.campuscoin.edu",
  "password": "<any value that is not the real password>"
}
```

**Expected result:** `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"`. Record the
`message` and the whole body — M1-08 compares against it. **No token is issued.** The response does
not say whether the address or the password was wrong.

**Result:** - [ ] Pass / Fail

---

### M1-08 Sign in with an unknown email address

**Covers:** UC-02 A1

**Preconditions:** `qa.m1.unknown@student.campuscoin.edu` has never been registered.

**Steps:**

1. Send `POST /api/v1/auth/login` with an address that does not exist and any password:

   ```json
   {
     "email": "qa.m1.unknown@student.campuscoin.edu",
     "password": "<any value>"
   }
   ```

2. Compare the response body with the one recorded in M1-07.

**Expected result:** `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"`, and a body
**indistinguishable** from M1-07's: same status, same `errorCode`, same `message`.
This is the point of the case — if the two responses differ in any way, the endpoint can be used
to discover which addresses are registered, which UC-02 A1 forbids.

**Result:** - [ ] Pass / Fail

---

### M1-09 Sign in on a disabled account

**Covers:** UC-02 A2, BR-03

**Preconditions:** M1-01-style access to the database (§2 item 6). There is no API in this module
that disables an account — that is UC-22, in module 11 — so the state is set directly.

**Steps:**

1. Register a throwaway student (`POST /api/v1/auth/register`):

   ```json
   {
     "fullName": "QA Disabled Account",
     "email": "qa.m1.disabled@student.campuscoin.edu",
     "password": "<a policy-compliant password you choose>",
     "confirmPassword": "<the same value>"
   }
   ```

2. Sign in as that student (`POST /api/v1/auth/login`) and keep the token in
   `${USER_A_JWT}` — replacing its value. It is used in step 5 and then discarded.
3. In the database, look up the two ids you need:

   ```sql
   SELECT id, email FROM users WHERE email IN
     ('qa.m1.disabled@student.campuscoin.edu', 'admin@campuscoin.edu');
   ```

4. Disable the throwaway account with the seeded administrator as the actor (UC-22 B3/B5):

   ```sql
   CALL sp_set_user_status(<throwaway id>, <admin id>, 'DISABLED', '127.0.0.1');
   ```

5. Send `POST /api/v1/auth/login` with the disabled account's correct email and password.
6. Send a token-guarded call with the token from step 2:
   `GET /api/v1/profile/me` with `Authorization: Bearer ${USER_A_JWT}`.
7. Restore the account:

   ```sql
   CALL sp_set_user_status(<throwaway id>, <admin id>, 'ACTIVE', '127.0.0.1');
   ```

**Expected result:**

- Step 4: the procedure succeeds. The account's sessions are revoked and its `token_version` is
  incremented, which is what makes step 6 fail rather than succeed.
- Step 5: `401 Unauthorized` with `"errorCode": "ACCOUNT_DISABLED"` and a message telling the
  student to contact the administrator. This code is returned by the two **sign-in** endpoints
  only. **No token is issued.**
- Step 6: `401 Unauthorized` with `"errorCode": "UNAUTHENTICATED"` — **not** `ACCOUNT_DISABLED`.
  On a token-guarded endpoint a token issued before the disable is rejected by the token filter,
  which reports a revoked session, a stale `token_version`, a bad signature and a missing token
  identically so that the holder of a token cannot learn the state of the account. Both are `401`
  and both mean "clear the session and sign in again".
- Step 7: the account returns to `ACTIVE`.

**Result:** - [ ] Pass / Fail

---

### M1-10 Sign out revokes the token — and only that token

**Covers:** UC-02 B5, A3

**Preconditions:** The second seeded student (Bella Tran) is active and her password is unchanged.
Email: `binh.tran@student.campuscoin.edu`.

**Steps:**

1. Sign in as Bella: `POST /api/v1/auth/login`. Copy `accessToken` into `USER_B_JWT`.
2. Sign in **again** as Bella, in a second call. Copy the (different) `accessToken` into `JWT`.
   Two sign-ins produce two distinct tokens because each carries a random `jti`.
3. Confirm both tokens work: `GET /api/v1/profile/me` with `Authorization: Bearer ${USER_B_JWT}`,
   then with `Bearer ${JWT}`.
4. Sign out the first session: `POST /api/v1/auth/logout` with header
   `Authorization: Bearer ${USER_B_JWT}` and **no body**.
5. Repeat step 4 with the same token.
6. Call `GET /api/v1/profile/me` with `Authorization: Bearer ${USER_B_JWT}`.
7. Call `GET /api/v1/profile/me` with `Authorization: Bearer ${JWT}`.
8. Call `POST /api/v1/auth/logout` with **no** `Authorization` header.

**Expected result:**

- Step 3: both `200 OK`.
- Step 4: `204 No Content` with an **empty body**.
- Step 5: `401 Unauthorized` with `"errorCode": "UNAUTHENTICATED"` — not `204`. The token is no
  longer valid, so a second sign-out is refused. Treat this as "already signed out" and clear the
  local session anyway.
- Step 6: `401 Unauthorized`, `UNAUTHENTICATED`. The token stops working immediately even though
  its own expiry has not passed.
- Step 7: `200 OK`. **This is the point of the case:** only the session that presented the token is
  revoked, so signing out on one device does not sign the student out on another (UC-02 B5).
- Step 8: `401 Unauthorized`, `UNAUTHENTICATED` — no token, so nothing to sign out.

**Result:** - [ ] Pass / Fail

---

### M1-11 Request a reset link for a known and for an unknown address

**Covers:** UC-03 B2, B3, A2

**Preconditions:** `qa.m1.student@student.campuscoin.edu` exists (M1-01). The sink file
`backend/target/password-reset-dev.log` is readable.

**Steps:**

1. Send `POST /api/v1/auth/password-reset/request` for the **known** address:

   ```json
   { "email": "qa.m1.student@student.campuscoin.edu" }
   ```

2. Send the same request for an address that has **never** been registered:

   ```json
   { "email": "qa.m1.no.such.student@student.campuscoin.edu" }
   ```

3. Compare the two response bodies byte for byte.
4. Open `backend/target/password-reset-dev.log` and find the most recent line for
   `qa.m1.student@student.campuscoin.edu`. Copy the value of the `token=` query parameter from its
   reset link — this is the **reset token** used by M1-12, M1-13 and M1-14. Do not paste it into
   this document.
5. Send `POST /api/v1/auth/password-reset/request` with `"email": "not-an-email"`.

**Expected result:**

- Steps 1 and 2: **both** `200 OK` with the identical body
  `{"message": "If the email exists, a reset link has been sent."}` — the wording is fixed by the
  requirement and must not branch. If the unknown address produces a different status or message,
  the endpoint has become a way of discovering which addresses are registered (UC-03 A2).
- Step 3: no difference at all.
- Step 4: a line exists for the known address and **none** exists for the unknown one. The token is
  never logged and never returned in a response — the sink file is the only place it appears.
- Step 5: `400 Bad Request` with `"errorCode": "VALIDATION_ERROR"` and a `fieldErrors` entry for
  `"email"`.
- The link is valid for **30 minutes** and is single-use; requesting a new link invalidates the
  account's previous unused links.

**Result:** - [ ] Pass / Fail

---

### M1-12 Verify a reset token

**Covers:** UC-03 B5, A1, BR-04

**Preconditions:** M1-11 passed; you hold the reset token for
`qa.m1.student@student.campuscoin.edu`.

**Steps:**

1. Send `POST /api/v1/auth/password-reset/verify` with the token in the **body**:

   ```json
   { "token": "<the reset token from M1-11 step 4>" }
   ```

2. Repeat the same call a second time, with the identical token.
3. Send the call with a token that does not exist:

   ```json
   { "token": "this-token-was-never-issued" }
   ```

4. Send the call with `{ "token": "" }`.

**Expected result:**

- Step 1: `200 OK` with exactly `{"valid": true}`. No account information is returned — no name, no
  email, no id. Identifying the account to whoever holds the link would tell an interceptor whose
  password they are about to change.
- Step 2: `200 OK`, `{"valid": true}` again. **Verifying does not consume the token**, so a student
  who opens the link and reloads the page can still use it.
- Step 3: `400 Bad Request` with `"errorCode": "INVALID_RESET_TOKEN"` and a message saying the
  link is invalid or has expired. `fieldErrors` is **absent** — this is not a field validation
  failure. The client should offer to send a new link.
- Step 4: `400 Bad Request` with `"errorCode": "VALIDATION_ERROR"` and a `fieldErrors` entry for
  `"token"` (a blank token is a field failure, not an invalid-token failure).
- The token is sent in the body, never as a query parameter.

**Result:** - [ ] Pass / Fail

---

### M1-13 Complete the reset

**Covers:** UC-03 B7, BR-04

**Preconditions:** M1-12 passed; the same reset token is still unused and unexpired.

**Steps:**

1. Send `POST /api/v1/auth/password-reset/complete`, choosing a new password that satisfies the
   policy and confirming it exactly:

   ```json
   {
     "token": "<the reset token from M1-11 step 4>",
     "newPassword": "<a new policy-compliant password you choose>",
     "confirmPassword": "<the same value>"
   }
   ```

2. Send `POST /api/v1/auth/login` with the **new** password.
3. Send `POST /api/v1/auth/login` with the **old** password.

**Expected result:**

- Step 1: `200 OK` with exactly
  `{"message": "Your password has been reset. Please sign in with your new password."}`
- Step 2: `200 OK` — the new password works.
- Step 3: `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"` — the old password no longer
  works.
- Confirming a rejected attempt: a mismatched confirmation returns
  `400 VALIDATION_ERROR` with a `fieldErrors` entry on `"confirmPassword"`, and does **not** consume
  the token, so the student can correct it and submit again.

**Result:** - [ ] Pass / Fail

---

### M1-14 Reusing a spent reset token is refused

**Covers:** UC-03 A1, UAT-03, BR-04

**Preconditions:** M1-13 passed; the token has been consumed.

**Steps:**

1. Send `POST /api/v1/auth/password-reset/verify` with the token that M1-13 consumed.
2. Send `POST /api/v1/auth/password-reset/complete` with the same token and a new
   policy-compliant password (with its confirmation).

**Expected result:**

- Step 1: `400 Bad Request` with `"errorCode": "INVALID_RESET_TOKEN"`. The token is single-use, so
  consuming it makes it unusable even though 30 minutes have not passed.
- Step 2: `400 Bad Request` with `"errorCode": "INVALID_RESET_TOKEN"`. The password is **not**
  changed a second time; the account keeps the password set in M1-13. Verify this by signing in
  with the password from M1-13 step 1 (it must still work).
- The remedy offered to the student is to request a new link (UC-03 A1).

**Result:** - [ ] Pass / Fail

---

### M1-15 A completed reset revokes every open session

**Covers:** UC-03 B7, BR-03

**Preconditions:** M1-06 passed, so `${USER_A_JWT}` holds a token issued **before** the reset in
M1-13, and that token has not been signed out. M1-13 passed.

**Steps:**

1. Send `GET /api/v1/profile/me` with `Authorization: Bearer ${USER_A_JWT}` — the token obtained in
   M1-06, before any reset.
2. Send `GET /api/v1/profile/me` again with the token obtained by signing in with the **new**
   password in M1-13 step 2 (put it in `${USER_A_JWT}`, replacing the old value).

**Expected result:**

- Step 1: `401 Unauthorized` with `"errorCode": "UNAUTHENTICATED"`. On success the reset procedure
  revokes every open session of the account and increments its `token_version`, so any access token
  held by the student — on any device, including the one that ran M1-06 — stops working
  immediately. This is deliberate: after a reset, nobody but the person who set the new password
  should stay signed in.
- Step 2: `200 OK`. The account is usable with the new session; only sessions that existed at the
  moment of the reset were killed.
- If step 1 returns `200 OK`, the reset did **not** revoke sessions — record a Fail, because BR-03
  requires it.

**Result:** - [ ] Pass / Fail

---

### M1-16 Administrator signs in on the administrator portal

**Covers:** UC-05 B1, B3

**Preconditions:** The seeded administrator account is active.

**Steps:**

1. Send `POST /api/v1/admin/auth/login`:

   ```json
   {
     "email": "admin@campuscoin.edu",
     "password": "<the administrator password from docs/CREDENTIALS.md>"
   }
   ```

2. Copy `accessToken` into `ADMIN_JWT`: `export ADMIN_JWT='<the value>'`.
3. Send `GET /api/v1/admin/ping` with `Authorization: Bearer ${ADMIN_JWT}`.

**Expected result:**

- Step 1: `200 OK`, identical in shape to the student sign-in response, with `user.role` =
  `"ADMIN"` and `user.email` = `admin@campuscoin.edu`. `tokenType` is `"Bearer"` and `expiresIn` is
  a positive number of seconds.
- Step 3: the request passes the role gate — so the status is **not** `401` and **not** `403`. The
  path does not exist, so a `404 NOT_FOUND` body is the expected outcome; what the case proves is
  that an administrator token is admitted to the `/api/v1/admin/**` prefix, which is the
  server-side half of UC-05 E1.
- Note the deliberate absence from the student UI: this endpoint is reached by direct URL only
  (UC-05 B1). The API itself cannot enforce that; it is a routing rule (see M1-22).

**Result:** - [ ] Pass / Fail

---

### M1-17 A student is refused on the administrator portal

**Covers:** UC-05 A1

**Preconditions:** The second seeded student (Bella Tran) is active and her password is unchanged.

**Steps:**

1. Send `POST /api/v1/admin/auth/login` with a **student's correct** credentials:

   ```json
   {
     "email": "binh.tran@student.campuscoin.edu",
     "password": "<Bella Tran's password from docs/CREDENTIALS.md>"
   }
   ```

2. Send the same request with Bella's email and a **wrong** password.

**Expected result:**

- Step 1: `403 Forbidden` with `"errorCode": "ACCESS_DENIED"`. The credentials were accepted and
  the role check refused the account: the message tells the student to use the student portal.
  **No token is issued.**
- Step 2: `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"` — the same answer as an
  unknown address.
- **Why the order matters:** the role check happens *after* the password check, so the endpoint
  cannot be used to discover which addresses are administrator accounts. A caller without the
  correct password gets `401`, never `403`.

**Result:** - [ ] Pass / Fail

---

### M1-18 An administrator is refused on the student sign-in

**Covers:** UC-02, UC-05 A1

**Preconditions:** The seeded administrator account is active.

**Steps:**

1. Send `POST /api/v1/auth/login` with the administrator's **correct** credentials:

   ```json
   {
     "email": "admin@campuscoin.edu",
     "password": "<the administrator password from docs/CREDENTIALS.md>"
   }
   ```

2. Send the same request with the administrator's email and a **wrong** password.

**Expected result:**

- Step 1: `403 Forbidden` with `"errorCode": "ACCESS_DENIED"` and a message directing the caller to
  the administrator portal. **No token is issued.** Students cannot fix this by retrying, so the
  client must route administrators to the administrator portal rather than showing "try again".
- Step 2: `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"`, indistinguishable from an
  unknown address.
- The administrator is not locked out of the administrator portal by this: M1-16 must still pass
  afterwards. (A correct password is not counted as a failed attempt, so this case does not consume
  the account's failure allowance.)

**Result:** - [ ] Pass / Fail

---

### M1-19 A student's token is refused on an administrator path

**Covers:** UC-05 E1

**Preconditions:** The second seeded student is active. `${USER_B_JWT}` and `${JWT}` were revoked in
M1-10, so sign in again to obtain a fresh student token.

**Steps:**

1. Sign in as Bella (`POST /api/v1/auth/login`) and copy `accessToken` into `USER_B_JWT`.
2. Send `GET /api/v1/admin/ping` with `Authorization: Bearer ${USER_B_JWT}`.
3. Send `GET /api/v1/admin/ping` with **no** `Authorization` header.
4. Send `GET /api/v1/admin/auth/login` (a `GET` to a `POST`-only path) with
   `Authorization: Bearer ${USER_B_JWT}`.

**Expected result:**

- Step 2: `403 Forbidden` with `"errorCode": "ACCESS_DENIED"`. A student token may not use the
  administrator area.
- Step 3: `401 Unauthorized` with `"errorCode": "UNAUTHENTICATED"`.
- Step 4: `403 Forbidden` with `"errorCode": "ACCESS_DENIED"` — the role rule is matched on the
  whole `/api/v1/admin/**` prefix and runs in the security filter chain **before** the controller,
  so it applies to paths that do not exist and to methods that are not mapped. That is what makes
  the rule independent of a controller being present.
- **The frontend guard is not the security boundary.** These answers come from the server, so a
  user who bypasses a browser guard still cannot reach administrator data.

**Result:** - [ ] Pass / Fail

---

### M1-20 Repeated failed sign-ins are throttled

**Covers:** UC-02, §7.10

**Preconditions:** Run this case **last** — it locks a sign-in address for 15 minutes. It uses an
address that does not exist (`qa.m1.throttle@student.campuscoin.edu`) so that no real account is
affected, and so that it also demonstrates that the counter is keyed on the submitted address
whether or not it exists.

**Steps:** send `POST /api/v1/auth/login` six times in a row, each within the same 15-minute
window, with this body unchanged:

```json
{
  "email": "qa.m1.throttle@student.campuscoin.edu",
  "password": "<any value>"
}
```

**Expected result:**

- Calls 1–5: `401 Unauthorized` with `"errorCode": "INVALID_CREDENTIALS"`.
- Call 6: `429 Too Many Requests` with `"errorCode": "TOO_MANY_ATTEMPTS"` and a message such as
  "Too many failed sign-in attempts. Please try again later."
- The limit is **5** consecutive failures (the seeded `auth.max_login_attempts`), the window is
  **15 minutes**, and the check runs **before** the password is compared.
- A **successful** sign-in clears the counter: if you now sign in successfully with a real account,
  the counter for *that* address is removed. The counter for the throttled address survives until
  its window closes.
- The counter lives in memory, per server instance, so restarting the backend clears it.

**Result:** - [ ] Pass / Fail

---

### M1-21 Repeated reset requests are throttled

**Covers:** UC-03, §7.10

**Preconditions:** Run this case last. Use an address that does not exist
(`qa.m1.reset.throttle@student.campuscoin.edu`) so the case is self-contained. Do not use an
address whose reset link you still need.

**Steps:** send `POST /api/v1/auth/password-reset/request` four times in a row, each within the
same 15-minute window, with this body unchanged:

```json
{ "email": "qa.m1.reset.throttle@student.campuscoin.edu" }
```

**Expected result:**

- Calls 1–3: `200 OK` with
  `{"message": "If the email exists, a reset link has been sent."}`
- Call 4: `429 Too Many Requests` with `"errorCode": "TOO_MANY_ATTEMPTS"` and a message such as
  "Too many password reset requests. Please try again later."
- The limit is **3** reset requests per address per 15-minute window, and it is counted for an
  unknown address exactly as for a known one — a throttle that behaved differently would be an
  account-enumeration oracle, which UC-03 A2 forbids.
- Note that calls 1–3 do **not** reveal that the address is unknown: they return the same `200` as
  a registered address would.

**Result:** - [ ] Pass / Fail

---

### M1-22 Frontend routing rules in the browser

**Covers:** UC-02 A3, UC-05 B1, UC-05 E1

**Preconditions:** The Angular application is running. From `frontend/`, start it with the
package's start script (the dev server defaults to `http://localhost:4200` and proxies `/api` to
`http://localhost:8080` through `frontend/proxy.conf.json`).

> **Read this before running the case.** The frontend in this repository is **mock-only**: the
> authentication service reads from an in-memory mock user list and never calls the API, no HTTP
> interceptor attaches a bearer token, there is no `401` handler and there is no forbidden page.
> `docs/api/authentication.md` §10.1 lists the rewiring that is **required** — it is a
> specification for a change, not a description of shipped code. Sub-checks A and B below are
> executable today; sub-checks C and D are **blocked** and must be recorded as **Fail** against
> the contract, not against the mock UI.

**Steps and expected results:**

**A. The administrator guard (executable).** With the browser at the application root, navigate
directly to `http://localhost:4200/admin/dashboard`.
*Expected:* the application redirects to its administrator sign-in screen rather than showing the
administrator dashboard, because the admin route requires the `ADMIN` role. Direct URL access is
the only way in (UC-05 B1) — there is no link to this screen from the student interface. Do **not**
expect the contract's `/admin/login` path: the implemented path is `/auth/admin-login`.

**B. The student area with no session (executable, expected to Fail against the contract).**
Navigate directly to `http://localhost:4200/app/home`.
*Expected per the contract:* redirect to the sign-in screen, because the student area requires a
token. *Observed today:* the page opens, because the mock service fabricates a default student
session on start-up, so the route guard sees a "signed in" user. Record **Fail** and cite
`docs/api/authentication.md` §10.1 and §10.5. The implemented sign-in path is `/auth/login`, not
the contract's `/login`.

**C. `401` → sign-in (blocked).** Expire or revoke a token and make an authenticated call from the
browser.
*Expected per the contract:* a `401` with `errorCode` `UNAUTHENTICATED` clears the stored token and
redirects to sign-in, because a stored token is never proof of a live session (a password reset on
another device revokes it). *Status today:* **not executable** — the frontend makes no
authenticated calls and has no `401` interceptor, so nothing can trigger the behaviour. Record
**Fail** with the same citation.

**D. `403` → forbidden page (blocked).** Have a signed-in student reach an administrator screen.
*Expected per the contract:* a `403` with `errorCode` `ACCESS_DENIED` navigates to a forbidden page
and does **not** retry. *Status today:* **not executable** — no forbidden route or component
exists in the application, and the frontend issues no calls that could return `403`. Record
**Fail** with the same citation.

**E. The reset link route (blocked).** Open the link produced in M1-11
(`http://localhost:4200/reset-password?token=…`).
*Expected per the contract:* the reset screen reads `token` from the query string, calls
`POST /api/v1/auth/password-reset/verify`, and opens the new-password form on `200`. *Status
today:* **not executable** — no route serves `/reset-password`. Record **Fail** with the same
citation.

**Service-side equivalence (executable now).** The rules C, D and E are all enforced server-side
and are verified by M1-09 step 6, M1-19 and M1-12 … M1-13 respectively. A failed guard is a user
experience defect, not a security hole.

**Result:** - [ ] Pass / Fail

---

## 7. Traceability

| Test ID | Covers | Endpoint exercised |
|---------|--------|--------------------|
| M1-01 | UC-01 B1–B6, BR-01 | `POST /api/v1/auth/register`, `POST /api/v1/auth/login` |
| M1-02 | UC-01 A1, UAT-01, BR-01 | `POST /api/v1/auth/register` |
| M1-03 | UC-01 A2, BR-01 | `POST /api/v1/auth/register` |
| M1-04 | UC-01 A2 | `POST /api/v1/auth/register` |
| M1-05 | UC-01 A2 | `POST /api/v1/auth/register` |
| M1-06 | UC-02 B2, B3 | `POST /api/v1/auth/login`, `GET /api/v1/profile/me` |
| M1-07 | UC-02 A1 | `POST /api/v1/auth/login` |
| M1-08 | UC-02 A1 | `POST /api/v1/auth/login` |
| M1-09 | UC-02 A2, BR-03 | `POST /api/v1/auth/login`, `GET /api/v1/profile/me`, `sp_set_user_status` |
| M1-10 | UC-02 B5, A3 | `POST /api/v1/auth/logout`, `POST /api/v1/auth/login` |
| M1-11 | UC-03 B2, B3, A2 | `POST /api/v1/auth/password-reset/request` |
| M1-12 | UC-03 B5, A1, BR-04 | `POST /api/v1/auth/password-reset/verify` |
| M1-13 | UC-03 B7, BR-04 | `POST /api/v1/auth/password-reset/complete` |
| M1-14 | UC-03 A1, UAT-03, BR-04 | `POST /api/v1/auth/password-reset/verify`, `POST /api/v1/auth/password-reset/complete` |
| M1-15 | UC-03 B7, BR-03 | `POST /api/v1/auth/password-reset/complete`, `GET /api/v1/profile/me` |
| M1-16 | UC-05 B1, B3 | `POST /api/v1/admin/auth/login` |
| M1-17 | UC-05 A1 | `POST /api/v1/admin/auth/login` |
| M1-18 | UC-02, UC-05 A1 | `POST /api/v1/auth/login` |
| M1-19 | UC-05 E1 | `GET /api/v1/admin/**`, `POST /api/v1/auth/login` |
| M1-20 | UC-02, §7.10 | `POST /api/v1/auth/login` |
| M1-21 | UC-03, §7.10 | `POST /api/v1/auth/password-reset/request` |
| M1-22 | UC-02 A3, UC-05 B1, UC-05 E1 | Angular routes and guards |

## 8. Not covered by this document

Requirements and behaviours this document **cannot** verify, because the implementation does not
expose them. Each is a real gap in the module's testability, not a mistake in the document.

| Item | Why it cannot be tested here |
|------|------------------------------|
| **UC-02 A2 — setting up a disabled account** | No endpoint in module 1 (or anywhere yet) changes an account's status; that is UC-22, in module 11. M1-09 therefore sets the state with a direct `CALL sp_set_user_status(...)` against the database, which needs database credentials and is not something a QA engineer running against a locked-down environment can do. The rule itself (`ACCOUNT_DISABLED` at sign-in, `UNAUTHENTICATED` on a token-guarded call) is verified. |
| **UC-03 B2 — actual delivery of the reset link** | There is no mail provider. In the `dev` profile the link is written to a file (`backend/target/password-reset-dev.log`); in production the default notifier discards it and logs a warning. So "the raw token is sent to the account's email address" cannot be verified end to end — only that a link was generated and that the token works. `docs/SECURITY.md` §5 records this as outstanding before production. |
| **UC-02 B5 — sign out everywhere** | Only "sign out the current session" exists. There is no endpoint that revokes all of an account's sessions from a session list, so M1-10 can verify only that other sessions survive. The "all sessions die at once" behaviour is reachable only through a password reset (M1-15) or an account disable (M1-09) — there is no explicit "sign out everywhere". |
| **Token refresh** | No use case defines a refresh flow, so no endpoint exists, even though `user_sessions.refresh_token_hash` is in the schema. Nothing to test. |
| **Change password while signed in** | Not a use case in this module and no endpoint exists. The only route to a new password is UC-03. |
| **UC-01 email verification** | Explicitly not required by UC-01. No endpoint exists. |
| **Frontend `401` → sign-in, `403` → forbidden, and the `/reset-password` route (M1-22 C, D, E)** | The Angular application is mock-only: it makes no API calls, stores no token, has no HTTP interceptor, and defines neither a forbidden page nor a reset-password route. `docs/api/authentication.md` §10.1 and §10.5 specify these as required changes; until that rewiring is done, only the server-side half (M1-09, M1-12, M1-13, M1-19) is verifiable. |
| **UC-05 B1 — the administrator portal being unlinked from the student UI** | M1-22 A checks one screen and the absence of a link cannot be exhaustively verified by hand. The API cannot enforce this at all: `/api/v1/admin/auth/login` is public by necessity. |
| **UC-22 / module 11 routes under `/api/v1/admin/**`** | No administrator route other than `POST /api/v1/admin/auth/login` exists yet. M1-19 verifies the role rule on the prefix using a deliberately unmapped path; the real endpoints arrive with module 11 and will need their own cases. |
| **The throttle as a multi-instance control** | The counters are held in memory per instance, so a restart clears them and N instances each allow the full quota. M1-20 and M1-21 verify the single-instance behaviour only; `docs/SECURITY.md` §11 records the trade-off. |
