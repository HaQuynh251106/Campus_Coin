# Module 2 — Profile & Preferences — Manual Test Procedure

Manual acceptance tests for the profile and display-preference endpoints (UC-04, UC-27), for a QA
engineer or the project owner following the steps by hand against a running stack.

- **Source of truth:** `docs/api/profile.md` (contract) and `docs/modules/MODULE_02_PROFILE.md`
  (module record). Endpoint numbers 8–10 in `docs/api/API_INVENTORY.md`.
- **Endpoints under test:** `GET /api/v1/profile/me`, `PATCH /api/v1/profile/me`,
  `PATCH /api/v1/profile/me/preferences`. Base path `/api/v1`, content type `application/json`,
  bearer token required on all three.
- **Required role:** `STUDENT` only. `/api/v1/profile/**` requires `hasRole("STUDENT")`, so an
  administrator token is refused with `403`.
- **No identifier in any path or body.** Every endpoint acts on the account in the verified bearer
  token. No request type accepts a user id.

---

## 1. Environment and test accounts

### 1.1 The running stack

| Item | Value |
|---|---|
| Base URL (development) | `http://localhost:8080` |
| Base path | `/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/api-docs` |

Start the application with the development profile so the seeded accounts and the development
database are present. Confirm it is up before starting: `GET /actuator/health` answers `200`.

### 1.2 Where the tokens come from — do not paste a token into this document

This procedure never contains a token or a password value. Use these four placeholders in every
request:

| Placeholder | Account | How to obtain it |
|---|---|---|
| `${JWT}` | Student A (the primary student under test) | Sign in as student A and copy the `accessToken` value from the response |
| `${USER_A_JWT}` | Student A — a second name for the same token, used where two students must not be confused | Same sign-in as `${JWT}` |
| `${USER_B_JWT}` | Student B — the second student, used only to prove isolation | Sign in as student B separately and copy its `accessToken` |
| `${ADMIN_JWT}` | The administrator, used only to prove the role boundary | Sign in through the administrator endpoint and copy its `accessToken` |

To fill a placeholder: call the appropriate sign-in endpoint, read the `accessToken` field from the
JSON response, and use that value where the placeholder appears. Do not commit a token to a file,
and never place one in source code or in a shared document.

Sign-in endpoints (from `docs/api/API_INVENTORY.md`, endpoints 2 and 7):

- **Student sign-in:** `POST /api/v1/auth/login` with a JSON body
  `{ "email": "<student email>", "password": "<student password>" }`.
- **Administrator sign-in:** `POST /api/v1/admin/auth/login` with the same body shape and the
  administrator's credentials.

Both answer `200` with:

```json
{
  "accessToken": "<copy this value into the placeholder>",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": { "id": 2, "fullName": "Alex Nguyen", "email": "…", "role": "STUDENT" }
}
```

The account emails and passwords are in `docs/CREDENTIALS.md`; read them there rather than
transcribing them into any test artifact. The token lives for `expiresIn` seconds (7200 by default
= 2 hours); if a later call unexpectedly returns `401 UNAUTHENTICATED`, sign in again and re-copy
the token before assuming a defect.

### 1.3 A note on two students

Most tests need only `${JWT}` (student A). The ownership test (M2-12) needs student B's token
(`${USER_B_JWT}`) as well, and the role test (M2-13) needs `${ADMIN_JWT}`. Sign in as all three
accounts once at the start so the tokens are ready.

### 1.4 How to read a result

Every non-2xx response has the same body shape (`docs/api/authentication.md` §2):

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/profile/me",
  "fieldErrors": [ { "field": "fullName", "message": "…" } ]
}
```

`fieldErrors` is present **only** on validation failures. Assert on `errorCode`, not on `message`
— the message is human-readable text and may be reworded. The field name in `fieldErrors[]` is
always the key `field`, never `path`.

Record the outcome of every test in the **Result** box as `Pass` or `Fail`.

---

## 2. Test cases

### M2-01 — Read my profile and preferences

**Covers:** UC-04 (read), UC-27 (read); endpoint 8.

**Preconditions:** The stack is running. `${JWT}` holds a valid student token.

**Steps:**

1. Send `GET http://localhost:8080/api/v1/profile/me` with header
   `Authorization: Bearer ${JWT}` and no body.
2. Read the JSON response.

**Expected result:** `200 OK`. The body contains exactly these nine fields, all present:
`id`, `fullName`, `email`, `academicYear`, `monthlyAllowanceBaseline`, `monthlySavingsGoal`,
`currency`, `themePreference`, `fontScale`. `academicYear` may be `null` (a student who has not set
a year) and that is correct — the API substitutes no default. `currency` is three upper-case
letters, e.g. `USD`. `monthlyAllowanceBaseline` and `monthlySavingsGoal` are numbers. `id` is a
number, not a string.

**Result:** ☐ Pass / ☐ Fail

---

### M2-02 — Update `fullName`

**Covers:** UC-04 (update one field); endpoint 9.

**Preconditions:** M2-01 passed. Note the current `fullName` so it can be restored later.

**Steps:**

1. Send `PATCH http://localhost:8080/api/v1/profile/me` with
   `Authorization: Bearer ${JWT}` and `Content-Type: application/json`, body:
   `{"fullName": "Alex Nguyen Updated"}`
2. Read the response.
3. Send `GET /api/v1/profile/me` again to confirm the change persisted.

**Expected result:** Step 1 answers `200 OK` and returns the **complete** profile with
`fullName` set to `"Alex Nguyen Updated"` and every other field unchanged from M2-01. Step 3 returns
the same new value — the read is served from the database row, not from the token.

**Result:** ☐ Pass / ☐ Fail

---

### M2-03 — Update `academicYear`

**Covers:** UC-04 (update one field); endpoint 9.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"academicYear": "Year 3"}`
2. Read the response.

**Expected result:** `200 OK`, complete profile returned, `academicYear` is `"Year 3"`, and no
other field changed. `academicYear` is free text (`VARCHAR(30)`), not an enum — any string up to 30
characters after trimming is accepted and returned exactly as stored.

**Result:** ☐ Pass / ☐ Fail

---

### M2-04 — Update `monthlyAllowanceBaseline`

**Covers:** UC-04 (update one field); VĐ-04; endpoint 9.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"monthlyAllowanceBaseline": 750.50}`
2. Read the response.

**Expected result:** `200 OK`, complete profile returned, `monthlyAllowanceBaseline` is `750.50`
and no other field changed. Up to 13 integer digits and 2 decimal places are accepted.

**Result:** ☐ Pass / ☐ Fail

---

### M2-05 — Update `monthlySavingsGoal`

**Covers:** UC-04 (update one field); VĐ-04; endpoint 9.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"monthlySavingsGoal": 2000.00}`
2. Read the response.

**Expected result:** `200 OK`, complete profile returned, `monthlySavingsGoal` is `2000.00` and no
other field changed.

**Result:** ☐ Pass / ☐ Fail

---

### M2-06 — Omitting a field leaves it alone

**Covers:** UC-04 partial-update semantics (`docs/api/profile.md` §7); endpoint 9.

**Preconditions:** M2-01 passed. Record the current values of `fullName`, `academicYear`,
`monthlyAllowanceBaseline` and `monthlySavingsGoal`.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"monthlySavingsGoal": 1234.56}`
2. Compare the response to the values recorded before step 1.
3. Send `PATCH /api/v1/profile/me` with an empty body object `{}`.

**Expected result:** Step 1 answers `200 OK`. Only `monthlySavingsGoal` has changed; `fullName`,
`academicYear` and `monthlyAllowanceBaseline` hold exactly the values recorded in the precondition.
Step 3 answers `200 OK` and changes nothing — an empty object is valid.

**Result:** ☐ Pass / ☐ Fail

---

### M2-07 — Clearing `academicYear` with an empty string

**Covers:** UC-04 (clear the nullable field); endpoint 9.

**Preconditions:** M2-03 has been run, so `academicYear` currently holds a non-empty value such as
`"Year 3"`. Confirm with `GET /api/v1/profile/me`.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"academicYear": ""}`
2. Read the response.
3. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"academicYear": null}`

**Expected result:** Step 1 answers `200 OK` with `"academicYear": null` — the empty string is how
the only nullable profile field is cleared. Step 2 also answers `200 OK` and leaves
`academicYear` **as it is** (still `null` from step 1, and had it been set, it would have stayed
set): `null` means "unchanged", so `null` and `""` are **not** interchangeable. Note the contrast is
only observable while a value is set: repeat step 3 after M2-03 without running step 1 to see that a
`null` does not clear the year.

**Result:** ☐ Pass / ☐ Fail

---

### M2-08 — The `≥ 0` money rule

**Covers:** UC-04 validation; VĐ-04; database `ck_users_money`; endpoint 9.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"monthlyAllowanceBaseline": 0, "monthlySavingsGoal": 0}`
2. Read the response.
3. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and body:
   `{"monthlySavingsGoal": -1}`
4. Send `GET /api/v1/profile/me` to confirm nothing was written.

**Expected result:** Step 1 answers `200 OK` — `0` is valid and means "not stated yet"; both fields
are `0.00`. Step 3 answers `400` with `"errorCode": "VALIDATION_ERROR"` and a `fieldErrors` entry
whose `field` is `monthlySavingsGoal`. Step 4 shows `monthlySavingsGoal` still `0` from step 1: a
rejected body writes nothing. (The same rule applied to `monthlyAllowanceBaseline` behaves
identically.)

**Result:** ☐ Pass / ☐ Fail

---

### M2-09 — Read the UC-27 display preferences

**Covers:** UC-27 (read); endpoint 8.

**Preconditions:** The stack is running and `${JWT}` is valid.

**Steps:**

1. Send `GET /api/v1/profile/me` with `Authorization: Bearer ${JWT}`.
2. Read `themePreference` and `fontScale` in the response.

**Expected result:** `200 OK`. `themePreference` is one of `LIGHT`, `DARK`, `SYSTEM`; `fontScale` is
one of `SMALL`, `MEDIUM`, `LARGE`, `XLARGE`. On an account that has never changed them the schema
defaults are returned: `themePreference` = `SYSTEM`, `fontScale` = `MEDIUM`. The values are enum
member names in upper case, never numbers.

**Result:** ☐ Pass / ☐ Fail

---

### M2-10 — Update both UC-27 preferences

**Covers:** UC-27 (update); endpoint 10.

**Preconditions:** M2-09 passed.

**Steps:**

1. Send `PATCH http://localhost:8080/api/v1/profile/me/preferences` with
   `Authorization: Bearer ${JWT}` and `Content-Type: application/json`, body:
   `{"themePreference": "DARK", "fontScale": "LARGE"}`
2. Read the response.
3. Send `PATCH /api/v1/profile/me/preferences` with body `{"fontScale": "XLARGE"}` only.
4. Send `GET /api/v1/profile/me` to confirm persistence.

**Expected result:** Step 1 answers `200 OK` and returns the complete profile with
`themePreference` = `DARK` and `fontScale` = `LARGE`. Step 3 answers `200 OK`; `fontScale` is now
`XLARGE` and `themePreference` is still `DARK` — an omitted preference field is left alone, so
changing the text size does not require resending the theme. Step 4 confirms the same values, which
means they are stored server-side and would follow the student to another device.

**Result:** ☐ Pass / ☐ Fail

---

### M2-11 — An invalid enum value is rejected

**Covers:** UC-27 validation of the enum members; endpoint 10.

**Preconditions:** M2-10 passed, so `themePreference` is `DARK` and `fontScale` is `XLARGE`.

**Steps:**

1. Send `PATCH /api/v1/profile/me/preferences` with `Authorization: Bearer ${JWT}` and body:
   `{"themePreference": "dark"}` (lower case).
2. Send `PATCH /api/v1/profile/me/preferences` with body `{"themePreference": "BLUE"}` (unknown
   name).
3. Send `PATCH /api/v1/profile/me/preferences` with body `{"fontScale": 2}` (a JSON number, not a
   member name).
4. Send `GET /api/v1/profile/me`.

**Expected result:** Each of steps 1–3 answers `400` with `"errorCode": "VALIDATION_ERROR"` and a
`fieldErrors` entry whose `field` is the offending field name (`themePreference` for steps 1 and 2,
`fontScale` for step 3). A number is **not** read as the enum's ordinal position — `2` is rejected
rather than silently meaning `LARGE`. Step 4 shows the preferences unchanged from M2-10.

**Result:** ☐ Pass / ☐ Fail

---

### M2-12 — Per-field validation errors on the profile body

**Covers:** UC-04 validation with per-field errors; the recorded bounds (name 2–120 after trimming,
year ≤ 30 after trimming, money ≥ 0 with at most 2 decimals); endpoint 9.

**Preconditions:** M2-01 passed. Record the current profile so the "unchanged" checks are possible.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with body `{"fullName": "A"}` (one character — below the
2-character minimum).
2. Send `PATCH /api/v1/profile/me` with body `{"fullName": " A "}` (three characters padded, but
   one character after trimming — this is the case a naive length check would wrongly accept).
3. Send `PATCH /api/v1/profile/me` with body `{"fullName": ""}`.
4. Send `PATCH /api/v1/profile/me` with body `{"academicYear": "<31 characters>"}` (a year longer
   than the 30-character limit after trimming).
5. Send `PATCH /api/v1/profile/me` with body `{"monthlySavingsGoal": 10.999}` (three decimal
   places).
6. Send `PATCH /api/v1/profile/me` with body `{"fullName": "Valid Name", "monthlySavingsGoal": -5}`
   (one valid field and one invalid field together).
7. Send `GET /api/v1/profile/me`.

**Expected result:** Steps 1–5 each answer `400` with `"errorCode": "VALIDATION_ERROR"` and a
`fieldErrors` array naming the offending field — `fullName` for steps 1–3, `academicYear` for
step 4, `monthlySavingsGoal` for step 5. Lengths are measured **after trimming**, so `" A "` is
rejected for the same reason as `"A"`. Step 6 answers `400`, and step 7 shows `fullName` still holds
its old value: a body with one invalid field is rejected whole and the valid half is not applied.
`fieldErrors[]` uses the key `field`, never `path`.

**Result:** ☐ Pass / ☐ Fail

---

### M2-13 — A malformed request body is refused cleanly

**Covers:** the error contract for a body that is not valid JSON; endpoint 9; `docs/api/profile.md`
§5.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and
   `Content-Type: application/json`, but a truncated body such as `{"fullName": ` (unterminated).
2. Send `PATCH /api/v1/profile/me` with no body at all.
3. Send `PATCH /api/v1/profile/me` with a body whose field has the wrong type, e.g.
   `{"fullName": 12345}`.

**Expected result:** Each answers `400` with `"errorCode": "MALFORMED_REQUEST"` (a wrong type is
also reported as a malformed request in this project's contract). The response contains no stack
trace, no SQL statement, no class name and no parser detail — only `timestamp`, `status`,
`errorCode`, `message` and `path`.

**Result:** ☐ Pass / ☐ Fail

---

### M2-14 — A second student's profile is unreachable

**Covers:** BR-02 (ownership), enforced structurally; endpoints 8–10.

**Preconditions:** Both student tokens are ready: `${USER_A_JWT}` and `${USER_B_JWT}`. Note the
profile of student B (call `GET /api/v1/profile/me` with `${USER_B_JWT}`) so any later change would
be visible.

**Steps:**

1. Confirm there is no id in the path: `GET /api/v1/profile/me` carries no `{id}`, and
   `PATCH /api/v1/profile/me` accepts no `id` or `userId` in its body. Record this as a stated
   observation.
2. As student A, attempt to address student B's record by appending an id to the path:
   `GET /api/v1/profile/12345/me` and `GET /api/v1/profile/me/12345` with `${USER_A_JWT}`.
3. As student A, send `PATCH /api/v1/profile/me` with a body carrying another account's identity:
   `{"id": <student B's id>, "userId": <student B's id>, "fullName": "Intruder"}`
   using `${USER_A_JWT}`.
4. Call `GET /api/v1/profile/me` with `${USER_B_JWT}` and compare to the values noted in the
   preconditions.

**Expected result:** Because **no endpoint accepts a user identifier**, there is no request shape
that names another student's profile: the endpoints act only on the account in the token. Step 2
therefore does not reach another student's data — those paths are not mapped and answer `404` with
`"errorCode": "NOT_FOUND"`, so neither request returns student B's profile; the point to record is
that no route exists in which a student B id could be supplied at all. Step 3 answers `200 OK`, and it changes
**student A's own** `fullName` to `"Intruder"` — the `id` and `userId` keys are not part of any
request type and are discarded, so student B's row is untouched. Step 4 shows student B's profile is
byte-for-byte the same as the precondition snapshot. Restore student A's `fullName` afterwards.

**Result:** ☐ Pass / ☐ Fail

---

### M2-15 — An administrator's token is refused with `403`

**Covers:** BR-03 role separation; module report `docs/modules/MODULE_02_PROFILE.md` §5.7;
endpoints 8–10.

**Preconditions:** `${ADMIN_JWT}` holds a valid administrator token, obtained from
`POST /api/v1/admin/auth/login`. `${JWT}` holds student A's token.

**Steps:**

1. Send `GET /api/v1/profile/me` with `Authorization: Bearer ${ADMIN_JWT}`.
2. Send `PATCH /api/v1/profile/me` with `Authorization: Bearer ${ADMIN_JWT}`, body
   `{"fullName": "Admin"}`, `Content-Type: application/json`.
3. Send `PATCH /api/v1/profile/me/preferences` with `Authorization: Bearer ${ADMIN_JWT}`, body
   `{"themePreference": "DARK"}`, `Content-Type: application/json`.

**Expected result:** All three answer `403` with `"errorCode": "ACCESS_DENIED"`. This is not a
sign-in problem: the administrator is authenticated but not permitted on this route. The
administrator's route to the same table is UC-22 under `/api/v1/admin/**`, a different operation
with its own contract. If any of the three answered `200` instead, the admin token would have
reached a student-only endpoint — record that as a failure, since
`docs/modules/MODULE_02_PROFILE.md` §5.7 records that this exact rule was added to close that gap.

**Result:** ☐ Pass / ☐ Fail

---

### M2-16 — No sensitive fields in a profile response

**Covers:** the security property that the response never exposes security or account-management
material; endpoints 8–10; `docs/modules/MODULE_02_PROFILE.md` §10, §8.3.

**Preconditions:** M2-01 passed.

**Steps:**

1. Send `GET /api/v1/profile/me` with `Authorization: Bearer ${JWT}` and inspect the **full** set
   of response keys.
2. Send `PATCH /api/v1/profile/me` with body
   `{"role": "ADMIN", "status": "DISABLED", "email": "attacker@example.com", "passwordHash": "x", "tokenVersion": 99, "aiEnabled": true}`
   and `Authorization: Bearer ${JWT}`.
3. Read the response of step 2 and then send `GET /api/v1/profile/me` again.

**Expected result:** Step 1 returns only the nine documented fields. The response never contains
`passwordHash`, `tokenVersion`, `role`, `status`, `lastLoginAt`, `emailVerifiedAt` or `aiEnabled`.
Step 2 answers `200 OK` at most (the keys are unknown to the request type and are discarded; an
all-unknown body is equivalent to `{}` and changes nothing), and its response still exposes none of
the fields listed above. Step 3 shows the account unchanged: `email` is what it was, the account
still behaves as a student, and no field echoes back what step 2 sent. The body of step 2 is not a
privilege-escalation route — `role` and `status` have no setter on the entity and are absent from
every request type.

**Result:** ☐ Pass / ☐ Fail

---

## 3. Traceability

| Test ID | Covers (UC / BR / rule) | Endpoint(s) | API document reference |
|---|---|---|---|
| M2-01 | UC-04 (read), UC-27 (read) | `GET /profile/me` | `profile.md` §4 |
| M2-02 | UC-04 — update `fullName` | `PATCH /profile/me` | `profile.md` §5 |
| M2-03 | UC-04 — update `academicYear` | `PATCH /profile/me` | `profile.md` §5 |
| M2-04 | UC-04 — update `monthlyAllowanceBaseline`; VĐ-04 | `PATCH /profile/me` | `profile.md` §5 |
| M2-05 | UC-04 — update `monthlySavingsGoal`; VĐ-04 | `PATCH /profile/me` | `profile.md` §5 |
| M2-06 | UC-04 — omitted field left alone; `{}` is valid | `PATCH /profile/me` | `profile.md` §7 |
| M2-07 | UC-04 — clear `academicYear` with `""`; `null` means unchanged | `PATCH /profile/me` | `profile.md` §7, §9.5 |
| M2-08 | VĐ-04 — money `≥ 0`; rejection writes nothing; `ck_users_money` | `PATCH /profile/me` | `profile.md` §3, §5 |
| M2-09 | UC-27 — read preferences | `GET /profile/me` | `profile.md` §3, §4 |
| M2-10 | UC-27 — update both preferences; omitted preference left alone | `PATCH /profile/me/preferences` | `profile.md` §6, §7 |
| M2-11 | UC-27 — unknown enum value rejected per field; numbers are not ordinals | `PATCH /profile/me/preferences` | `profile.md` §3, §6 |
| M2-12 | UC-04 — per-field validation errors; trimmed-length bounds; rollback | `PATCH /profile/me` | `profile.md` §3, §5 |
| M2-13 | Error contract — `MALFORMED_REQUEST`, no internals | `PATCH /profile/me` | `profile.md` §5, §8 |
| M2-14 | BR-02 — ownership is structural (no identifier accepted) | `GET`/`PATCH /profile/me` | `profile.md` §1, §10 |
| M2-15 | BR-03 — role separation; administrator refused `403` | all three | `MODULE_02_PROFILE.md` §5.7 |
| M2-16 | No sensitive fields out; no privilege escalation (§7.5) | `GET`/`PATCH /profile/me` | `profile.md` §4, §10 |

---

## 4. What this procedure does not cover

- **Disabled account and revoked session.** A disabled account or a revoked session is rejected by
  the token filter before the controller, as `401 UNAUTHENTICATED`; producing that state from the
  UI requires the administrator's UC-22 flow (module 11) and a token issued before the change. The
  module's own suite asserts it. Not reproducible by hand from this module alone.
- **Concurrency.** Two parallel edits to different columns both surviving is asserted at the
  persistence level in `ProfileApiIT`; a manual run cannot reliably produce the timing.
- **Changing `email`, password, `role`, `status` or `currency`.** Deliberately absent — no such
  endpoint exists, which is why M2-16 asserts refusal rather than success. Currency is an
  application-wide setting (VĐ-08), not a per-student field.
- **`aiEnabled`.** The column exists but is not exposed here; it belongs to UC-08/UC-17 in a later
  module.

## Related documentation

- `docs/api/profile.md` — the endpoint contract
- `docs/modules/MODULE_02_PROFILE.md` — the module report
- `docs/api/API_INVENTORY.md` — endpoints 8–10
- `docs/CREDENTIALS.md` — the seeded accounts (read the credentials here; never copy them into a
  test artifact)
- `docs/api/authentication.md` — how to obtain a token
