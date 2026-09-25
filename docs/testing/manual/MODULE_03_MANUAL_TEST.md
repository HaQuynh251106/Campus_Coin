# Module 3 — Personal Categories — Manual Test Procedure

Manual acceptance tests for the personal-category endpoints (UC-06), for a QA engineer or the
project owner following the steps by hand against a running stack.

- **Source of truth:** `docs/api/categories.md` (contract) and
  `docs/modules/MODULE_03_CATEGORIES.md` (module record). Endpoint numbers 11–15 in
  `docs/api/API_INVENTORY.md`.
- **Endpoints under test:** `GET /api/v1/categories`, `GET /api/v1/categories/{id}`,
  `POST /api/v1/categories`, `PATCH /api/v1/categories/{id}`, `DELETE /api/v1/categories/{id}`.
  Base path `/api/v1`, content type `application/json`, bearer token required on all five.
- **Required role:** `STUDENT` only. `/api/v1/categories/**` requires `hasRole("STUDENT")`, so an
  administrator token is refused with `403`.
- **No client-supplied ownership.** No request type has a `userId`, `user_id`, `createdBy`,
  `isDefault` or `id` field. The owner is the account in the verified bearer token, and ownership
  is enforced by the query `CategoryRepository.findByIdAndUserId` — the only single-row lookup.

---

## 1. Environment and test accounts

### 1.1 The running stack

| Item | Value |
|---|---|
| Base URL (development) | `http://localhost:8080` |
| Base path | `/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/api-docs` |

Start the application with the development profile so the seeded default categories and the test
accounts are present. Confirm it is up before starting: `GET /actuator/health` answers `200`.

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

### 1.3 A note on two students and cleanup

Most tests need only `${JWT}` (student A). The ownership test (M3-14) needs student B's token
(`${USER_B_JWT}`), and the role test (M3-18) needs `${ADMIN_JWT}`. Sign in as all three accounts
once at the start so the tokens are ready.

Several tests **create** categories. Where a test creates a throwaway category, delete it at the
end of the test so the list stays predictable; where a category cannot be deleted (because a
record references it), retire it with `{"isActive": false}` instead and leave it. Each test that
creates something says so.

### 1.4 How to read a result

Every non-2xx response has the same body shape (`docs/api/authentication.md` §2):

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "CATEGORY_NAME_TAKEN",
  "message": "You already have a category named \"Coffee\" of this type.",
  "path": "/api/v1/categories"
}
```

A validation failure adds a `fieldErrors` array, present **only** on validation failures:

```json
{
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/categories",
  "fieldErrors": [ { "field": "color", "message": "Colour must be a hex value such as #F59E0B." } ]
}
```

Assert on `errorCode`, not on `message` — the message is human-readable text and may be reworded.
The two `409` codes are deliberately distinct because the remedy differs: `CATEGORY_NAME_TAKEN`
means "pick another name", `CATEGORY_IN_USE` means "stop deleting and disable instead". The field
name in `fieldErrors[]` is always the key `field`, never `path`.

Record the outcome of every test in the **Result** box as `Pass` or `Fail`.

---

## 2. Test cases

### M3-01 — List the categories I can use, including the shared defaults

**Covers:** UC-06 (list); endpoint 11; UC-01 B5 (a new account is usable without a setup step).

**Preconditions:** The stack is running and `${JWT}` is valid. Run this test **before** the create
tests, so the account under test has no personal categories yet.

**Steps:**

1. Send `GET http://localhost:8080/api/v1/categories` with header
   `Authorization: Bearer ${JWT}` and no body.
2. Read the JSON response — it is an **array**, not an object.

**Expected result:** `200 OK`, a JSON array. It contains **twelve** shared defaults, each with
`"isDefault": true`: five of type `INCOME` (`Allowance`, `Part-time Job`, `Scholarship`, `Gift`,
`Other Income`) and seven of type `EXPENSE` (`Food`, `Transport`, `Hostel/Rent`, `Academics`,
`Subscriptions`, `Entertainment`, `Miscellaneous`). Each entry has the fields `id`, `name`, `type`,
`isDefault`, `isActive`, `sortOrder`, and may carry `icon` and `color`; `description` is omitted
when unset. The array is ordered by `type` and then by `sortOrder`. If personal categories already
exist on the account they appear in the same array with `"isDefault": false`.

**Result:** ☐ Pass / ☐ Fail

---

### M3-02 — Create an own category with the minimum body

**Covers:** UC-06 (create); endpoint 13; column defaults for a new category.

**Preconditions:** M3-01 passed.

**Steps:**

1. Send `POST http://localhost:8080/api/v1/categories` with `Authorization: Bearer ${JWT}` and
   `Content-Type: application/json`, body:
   `{"name": "Test Books", "type": "EXPENSE"}`
2. Read the response and note the assigned `id`.
3. Send `GET /api/v1/categories` and find the new row.

**Expected result:** Step 1 answers `201 Created` and returns the created category with an `id`
assigned by the database, `name` = `"Test Books"`, `type` = `"EXPENSE"`, and the documented
column defaults: `sortOrder` = `0`, `isActive` = `true`, `isDefault` = `false`. `icon`, `color` and
`description` are **omitted** from the response (they are absent, not `null`). Step 3 shows the row
in the list. Keep its `id` for later tests, or delete it now.

**Result:** ☐ Pass / ☐ Fail

---

### M3-03 — Create with every optional field, and colour normalisation

**Covers:** UC-06 (create); field bounds; trimming; colour upper-casing; endpoints 13 and 12.

**Preconditions:** M3-01 passed.

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body:
   `{"name": "  Campus Cafe  ", "type": "EXPENSE", "icon": "coffee", "color": "#f59e0b", "description": "Coffee and snacks between classes", "sortOrder": 5, "isActive": true}`
2. Read the response and note the `id`.
3. Send `GET /api/v1/categories/{id}` with that id and `Authorization: Bearer ${JWT}`.

**Expected result:** Step 1 answers `201`. `name` comes back trimmed as `"Campus Cafe"` — the
padding is not stored, because the server trims before applying the length rule and before
storage. `color` comes back **upper case** as `"#F59E0B"` — the value is normalised. `icon`,
`description` and `sortOrder` come back exactly as sent (trimmed). `isActive` is `true`,
`isDefault` is `false`. Step 3 answers `200` with the same category — the row can be refreshed by
id without reloading the list.

**Result:** ☐ Pass / ☐ Fail

---

### M3-04 — Create an INCOME category

**Covers:** UC-06 (create, both types); endpoint 13.

**Preconditions:** M3-01 passed.

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body:
   `{"name": "Test Tutoring", "type": "INCOME", "icon": "book", "color": "#16A34A"}`
2. Read the response and note the `id`.
3. Send `GET /api/v1/categories`.

**Expected result:** `201 Created`, `type` = `"INCOME"`, and in the list the new row is grouped with
the income categories. A personal category may be either type; both `INCOME` and `EXPENSE` are
accepted. Personal categories of a type usually appear **before** the seeded defaults of the same
type, because a new category defaults to `sortOrder` 0 while the seeded defaults start at 1
(income) and 10 (expense).

**Result:** ☐ Pass / ☐ Fail

---

### M3-05 — Update several fields of an own category

**Covers:** UC-06 (edit); endpoint 14; partial-update semantics (only the supplied fields change).

**Preconditions:** M3-02 or M3-03 created a category; note its `id` and current field values.

**Steps:**

1. Send `PATCH http://localhost:8080/api/v1/categories/{id}` with `Authorization: Bearer ${JWT}`
   and `Content-Type: application/json`, body:
   `{"name": "Textbooks", "color": "#0EA5E9", "sortOrder": 20}`
2. Compare the response to the values noted before step 1.

**Expected result:** `200 OK`, the category **as it now is in the database**: `name` = `"Textbooks"`,
`color` = `"#0EA5E9"`, `sortOrder` = `20`. Every field not named in the body — `type`, `icon`,
`description`, `isActive` — is unchanged. A field sent as `null` would mean the same as omitting
it: "unchanged".

**Result:** ☐ Pass / ☐ Fail

---

### M3-06 — Clear an optional field with an empty string

**Covers:** UC-06 (clear a nullable field); endpoint 14; the `""`-clears / `null`-leaves rule.

**Preconditions:** A category whose `icon`, `color` and `description` are set (M3-03 produces one).

**Steps:**

1. Send `PATCH /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}` and body:
   `{"icon": ""}`
2. Read the response.
3. Send `PATCH /api/v1/categories/{id}` with body `{"description": null}`.
4. Send `PATCH /api/v1/categories/{id}` with body `{"color": ""}`.

**Expected result:** Step 1 answers `200` and the `icon` key is **absent** from the response — the
empty string cleared it, and an unset optional field is omitted rather than sent as `null`. Step 3
answers `200` and `description` still holds the value it had — `null` means "unchanged", so `null`
and `""` are **not** interchangeable. Step 4 answers `200` and `color` is cleared. Note
`name`, `type` and `isActive` have no empty state: sending `""` for `name` is a validation error
rather than a clear (see M3-17).

**Result:** ☐ Pass / ☐ Fail

---

### M3-07 — An empty update body is a no-op, not an error

**Covers:** UC-06 (edit); endpoint 14.

**Preconditions:** A category created by this procedure; note all its field values.

**Steps:**

1. Send `PATCH /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}` and
   `Content-Type: application/json`, body `{}`.
2. Compare the response to the values noted before step 1.

**Expected result:** `200 OK` with the category unchanged in every field. An empty body changes
nothing and is not an error — no `UPDATE` statement is issued.

**Result:** ☐ Pass / ☐ Fail

---

### M3-08 — A type change is allowed while the category is unreferenced

**Covers:** UC-06 (edit); BR-05 context (the type is fixed only once something uses the category);
endpoint 14.

**Preconditions:** A personal category that **no transaction, budget or recurring rule uses** —
M3-02's `Test Books` is suitable.

**Steps:**

1. Send `PATCH /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}` and body:
   `{"type": "INCOME"}`
2. Read the response.

**Expected result:** `200 OK` and `type` is now `"INCOME"`. Nothing refers to the category, so the
database's type-change trigger does not fire and the change is allowed. (Compare M3-09, where the
category is referenced and the same change is refused.)

**Result:** ☐ Pass / ☐ Fail

---

### M3-09 — The type is immutable once anything references the category

**Covers:** BR-05; UC-06; endpoint 14. Enforced by the trigger `trg_categories_before_update`.

**Preconditions:** M3-01 passed. A valid student token `${JWT}`. A date for the transaction that is
not in the future (use today or an earlier date; "today" is judged in `Asia/Ho_Chi_Minh`).

**Steps:**

1. Create a category for this test:
   `POST /api/v1/categories` with body
   `{"name": "Test Referenced", "type": "EXPENSE"}` — note the returned `id`.
2. Make something reference it by recording a transaction under it:
   `POST /api/v1/transactions` with `Authorization: Bearer ${JWT}` and body
   `{"categoryId": <the id from step 1>, "amount": 5.00, "txnDate": "<today or earlier>", "description": "manual test reference"}`
   Expect `201 Created` (recording a transaction is UC-07, module 4; this step exists only to
   create the reference).
3. Send `PATCH /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}` and body:
   `{"type": "INCOME"}`
4. Read the `errorCode` in the response.
5. As a contrast, send `PATCH /api/v1/categories/{id}` with body `{"name": "Test Referenced Renamed"}`
   (a rename, not a type change).

**Expected result:** Step 3 answers `409 Conflict` with `"errorCode": "CATEGORY_IN_USE"` and a
message explaining that the category is used by a transaction, a budget or a recurring rule, so its
type cannot be changed. This is the database's rule (`trg_categories_before_update`), not the
application's — the same refusal holds for a hand-run SQL statement. The reason is that a
transaction's type **is** its category's type (BR-05): changing the category's type after it has
been used would silently rewrite every recorded expense as income in the reports. To genuinely move
a category to the other type, create a new category and move the data across. Step 5 answers
`200 OK` — a **rename** of a referenced category is allowed; only the type is frozen. Finish by
retiring this category (`PATCH {"isActive": false}`) or leaving it; it cannot be deleted while the
transaction references it (see M3-12).

**Result:** ☐ Pass / ☐ Fail

---

### M3-10 — Retire a category and re-enable it

**Covers:** UC-06; BR-07 (retire instead of delete); endpoint 14.

**Preconditions:** A personal category created by this procedure.

**Steps:**

1. Send `PATCH /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}` and body:
   `{"isActive": false}`
2. Read the response.
3. Send `GET /api/v1/categories` and look for the row.
4. Send `PATCH /api/v1/categories/{id}` with body `{"isActive": true}`.
5. _(Optional cross-check, module 4)._ Attempt to record a new transaction under the retired
   category from step 1 before re-enabling it, and observe that it is refused.

**Expected result:** Step 1 answers `200` with `"isActive": false`. Step 3 shows the retired
category **still present in the list** — a retired category is kept so the student can find it
again and restore it; the client shows it in a disabled state. Step 4 answers `200` with
`"isActive": true`, so the retirement is reversible. Step 5 is documented in
`docs/api/transactions.md` §7: a retired category answers `400` naming `categoryId`, because
retiring it is what stops new records being filed under it while the records already filed keep
their category.

**Result:** ☐ Pass / ☐ Fail

---

### M3-11 — Delete an unused category

**Covers:** UC-06 (delete); endpoint 15.

**Preconditions:** A personal category that nothing references — create a fresh one for this test.

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body
   `{"name": "Test Delete Me", "type": "EXPENSE"}` — note the `id`.
2. Send `DELETE http://localhost:8080/api/v1/categories/{id}` with `Authorization: Bearer ${JWT}`
   and no body.
3. Send `GET /api/v1/categories/{id}`.
4. Send `GET /api/v1/categories` and confirm the row is gone from the list.

**Expected result:** Step 2 answers `204 No Content` with **no body**. Step 3 answers `404` with
`"errorCode": "NOT_FOUND"` — the row is gone. Step 4 shows the category no longer in the list. This
is a **hard delete**: there is no soft-delete for categories, and BR-07's "keep the history" is
served by refusing the delete while records point at the row (M3-12) and by retiring it instead
(M3-10).

**Result:** ☐ Pass / ☐ Fail

---

### M3-12 — A referenced category cannot be deleted

**Covers:** BR-07; UC-06; endpoint 15. Enforced by the `ON DELETE RESTRICT` foreign keys and the
trigger `trg_categories_before_delete`.

**Preconditions:** M3-09's `Test Referenced` category exists and a transaction references it.

**Steps:**

1. Send `DELETE /api/v1/categories/{id}` with `Authorization: Bearer ${JWT}`, where `{id}` is the
   referenced category from M3-09.
2. Read the `errorCode` in the response.
3. Send `GET /api/v1/categories/{id}`.
4. Apply the documented remedy: `PATCH /api/v1/categories/{id}` with body `{"isActive": false}`.

**Expected result:** Step 1 answers `409 Conflict` with `"errorCode": "CATEGORY_IN_USE"` and a
message saying the category is still used by a transaction, a budget or a recurring rule and
advising "disable it instead". This is BR-07: deleting would strip those records of their category,
and the reporting views join through it. Step 3 shows the category is still there, and the
transaction still points at it — the refused delete changed nothing. Step 4 answers `200` and
retires it, which is the intended remedy. The same refusal occurs for a category referenced by a
budget or by a recurring rule; this test exercises the transaction case.

**Result:** ☐ Pass / ☐ Fail

---

### M3-13 — Another student's category is not reachable, and a default is read-only

**Covers:** BR-02 (ownership); BR-06 (defaults are read-only for students); endpoints 12, 14, 15.

**Preconditions:** Both student tokens are ready. As **student B** (`${USER_B_JWT}`), create a
category and note its `id` (call it `{B_ID}`). From M3-01's list, note the `id` of a shared default
(call it `{DEFAULT_ID}`, e.g. `Food`).

**Steps:**

1. As student B, confirm the category exists: `GET /api/v1/categories/{B_ID}` with
   `Authorization: Bearer ${USER_B_JWT}` — expect `200`.
2. As **student A**, send `GET /api/v1/categories/{B_ID}` with `Authorization: Bearer ${JWT}`.
3. As student A, send `PATCH /api/v1/categories/{B_ID}` with body `{"name": "Stolen"}` and
   `Authorization: Bearer ${JWT}`.
4. As student A, send `DELETE /api/v1/categories/{B_ID}` with `Authorization: Bearer ${JWT}`.
5. As student A, send `GET /api/v1/categories/{DEFAULT_ID}` with `Authorization: Bearer ${JWT}`.
6. As student A, send `PATCH /api/v1/categories/{DEFAULT_ID}` with body `{"name": "Mine"}` and
   `Authorization: Bearer ${JWT}`.
7. As student A, send `DELETE /api/v1/categories/{DEFAULT_ID}` with `Authorization: Bearer ${JWT}`.
8. As student B, re-read `GET /api/v1/categories/{B_ID}` with `${USER_B_JWT}`.

**Expected result:** Steps 2–7 each answer `404` with `"errorCode": "NOT_FOUND"`. An id that is not
the caller's own — whether it belongs to another student or is a shared default — is deliberately
indistinguishable from an id that does not exist, so the endpoints cannot be used to discover which
category ids exist. A default category answers `404` rather than `403` for the same reason: the
student's route to it does not exist, and `403` would confirm that the id names a real row. Step 8
shows student B's category unchanged, proving steps 3 and 4 wrote nothing. (Default categories are
administered by UC-20 under `/api/v1/admin/**`, module 11.)

**Result:** ☐ Pass / ☐ Fail

---

### M3-14 — Coverage of the no-duplicate-name rule

**Covers:** BR-06; UC-06; endpoints 13 and 14. Enforced by the unique key
`uk_categories_scope_type_name` on `(scope_key, type, name)`.

**Preconditions:** M3-01 passed. No personal category named `Coffee` exists on the account; delete
any left from earlier runs first, or use a fresh name of your own in its place.

**Steps:**

1. Create the first category: `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and
   body `{"name": "Coffee", "type": "EXPENSE"}` — note the `id`; expect `201 Created`.
2. Create the same name and type again: `POST /api/v1/categories` with body
   `{"name": "Coffee", "type": "EXPENSE"}`.
3. Create the same name under the **other** type: `POST /api/v1/categories` with body
   `{"name": "Coffee", "type": "INCOME"}`.
4. Rename a third category onto the taken name:
   `PATCH /api/v1/categories/{id}` with body `{"name": "Coffee"}`.
5. Delete the throwaway `INCOME` category from step 3 (`DELETE /api/v1/categories/{id}` →
   `204`), then repeat step 3's create.

**Expected result:** Step 2 answers `409 Conflict` with `"errorCode": "CATEGORY_NAME_TAKEN"` and a
message such as `You already have a category named "Coffee" of this type.` Step 3 answers
`201 Created` — the rule is per **name and type**, so the same name is allowed for a different
type. Step 4 answers `409 CATEGORY_NAME_TAKEN` — a rename is checked against the resulting name and
type, so a rename cannot reach a state the create rule forbids. Step 5 answers `201` again: once the
conflicting row is deleted, the name is free. Note also that the database collation is
case-insensitive (`utf8mb4_0900_ai_ci`), so a duplicate differing only in case (e.g. `"coffee"`)
is the same name and is refused identically — verify this by repeating step 2 with `"coffee"` if
wished. Clean up the categories this test created.

**Result:** ☐ Pass / ☐ Fail

---

### M3-15 — A personal category may not take a default category's name

**Covers:** BR-06; UC-06; endpoints 13 and 14. Enforced on insert by
`trg_categories_before_insert`.

**Preconditions:** M3-01 passed and the twelve defaults are present, including `Food` (EXPENSE).

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body:
   `{"name": "Food", "type": "EXPENSE"}`
2. Read the `errorCode` and message.
3. Create a throwaway category: `POST /api/v1/categories` with body
   `{"name": "Test Rename To Default", "type": "EXPENSE"}` — note the `id`.
4. Send `PATCH /api/v1/categories/{id}` with body `{"name": "Food"}`.

**Expected result:** Step 1 answers `409 Conflict` with `"errorCode": "CATEGORY_NAME_TAKEN"` and a
message such as `"Food" is the name of a standard category and cannot be reused.` A personal
category may not shadow a shared default, because it would be ambiguous which one a picker meant.
Step 4 also answers `409 CATEGORY_NAME_TAKEN`. Note the mechanism: the insert rule is the
trigger's, while the **rename** path is the one rule in this module that the application decides —
the update trigger checks only scope and type, so a rename could otherwise reach exactly the state
the insert rule exists to prevent. `CategoryService.update` applies the same check on the rename
path. Still, a shared default cannot be used twice by name and type, so the refusal is the same
code either way.

**Result:** ☐ Pass / ☐ Fail

---

### M3-16 — Per-field validation errors on create and update

**Covers:** UC-06 (reject invalid input); the recorded bounds (name 1–80 after trimming, icon ≤ 50,
colour `#RRGGBB`, description ≤ 255 with newlines permitted, `sortOrder` 0–32767); endpoints 13
and 14.

**Preconditions:** M3-01 passed. One personal category exists for the update cases.

**Steps:**

1. Send `POST /api/v1/categories` with body `{"name": "   ", "type": "EXPENSE"}` (whitespace-only
   name).
2. Send `POST /api/v1/categories` with body `{"type": "EXPENSE"}` (name missing).
3. Send `POST /api/v1/categories` with body `{"name": "Test No Type"}` (type missing).
4. Send `POST /api/v1/categories` with body
   `{"name": "Test Bad Type", "type": "expense"}` (lower case), and then a second call with
   `{"name": "Test Bad Type 2", "type": 0}` (a JSON number, not a member name).
5. Send `POST /api/v1/categories` with body
   `{"name": "Test Bad Colour", "type": "EXPENSE", "color": "#FFF"}`.
6. Send `POST /api/v1/categories` with body
   `{"name": "Test Bad Order", "type": "EXPENSE", "sortOrder": -1}`.
7. Send `POST /api/v1/categories` with a name of 81 characters after trimming.
8. Send `PATCH /api/v1/categories/{id}` with body `{"name": ""}`.
9. Send `POST /api/v1/categories` with a multi-line `description`, e.g.
   `{"name": "Test Multi Line", "type": "EXPENSE", "description": "First line\nSecond line"}`.

**Expected result:** Steps 1–8 each answer `400` with `"errorCode": "VALIDATION_ERROR"` and a
`fieldErrors` entry naming the offending field — `name` for steps 1, 2, 7, 8; `type` for steps 3
and 4; `color` for step 5; `sortOrder` for step 6. A number is **not** read as the enum's ordinal
position: `0` is rejected rather than silently meaning `INCOME`. Lower case is also rejected — send
the member name exactly. A whitespace-only `icon`, `color` or `description` is treated as "not set"
and stored as `null`, but a whitespace-only `name` is rejected. Step 9 answers `201 Created`: a
**multi-line description is valid** — newlines are permitted in `description`, and the
255-character bound still applies.

**Result:** ☐ Pass / ☐ Fail

---

### M3-17 — An administrator's token is refused with `403`

**Covers:** role boundary; endpoint set 11–15; `docs/modules/MODULE_03_CATEGORIES.md` §2, §8.3.

**Preconditions:** `${ADMIN_JWT}` holds a valid administrator token.

**Steps:**

1. Send `GET /api/v1/categories` with `Authorization: Bearer ${ADMIN_JWT}`.
2. Send `GET /api/v1/categories/1` with `Authorization: Bearer ${ADMIN_JWT}`.
3. Send `POST /api/v1/categories` with `Authorization: Bearer ${ADMIN_JWT}`, body
   `{"name": "Admin Category", "type": "EXPENSE"}`, `Content-Type: application/json`.
4. Send `PATCH /api/v1/categories/1` with `Authorization: Bearer ${ADMIN_JWT}`, body
   `{"name": "Admin Rename"}`, `Content-Type: application/json`.
5. Send `DELETE /api/v1/categories/1` with `Authorization: Bearer ${ADMIN_JWT}`.
6. Send `GET /api/v1/categories` **without** any `Authorization` header.

**Expected result:** Steps 1–5 each answer `403` with `"errorCode": "ACCESS_DENIED"`. This is a role
boundary, not a sign-in problem: the administrator is authenticated. Allowing an administrator
through would have the service write a personal category owned by that administrator — a second
route into the same table, bypassing the BR-06 gate. The administrator's route to the same table is
UC-20 (`sp_admin_upsert_default_category`) under `/api/v1/admin/**`. Step 6 answers `401` with
`"errorCode": "UNAUTHENTICATED"` — no endpoint in this module works without a token.

**Result:** ☐ Pass / ☐ Fail

---

### M3-18 — No client-supplied ownership, and no sensitive fields out

**Covers:** the security properties that ownership cannot be set by a request and `user_id` /
`created_by` never leave the server; endpoints 13 and 12; `docs/modules/MODULE_03_CATEGORIES.md`
§13.

**Preconditions:** M3-01 passed.

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body:
   `{"name": "Test Ownership", "type": "EXPENSE", "userId": <student B's id>, "user_id": <student B's id>, "isDefault": true, "createdBy": <student B's id>, "id": 999999}`
2. Read the response and note the returned `id`.
3. Send `GET /api/v1/categories/{returned id}` with `Authorization: Bearer ${JWT}`.
4. Send `GET /api/v1/categories` with `Authorization: Bearer ${USER_B_JWT}` and search for
   `"Test Ownership"`.
5. Inspect any category object from the list — shared default or personal — for the keys it
   carries.

**Expected result:** Step 1 answers `201 Created`. The response's `id` is the id the database
assigned, **not** `999999`; `isDefault` is `false`, not `true`; the row is owned by student A,
proving none of the ownership fields took effect — they are not part of the request type and are
discarded. Step 3 answers `200` and confirms student A owns it. Step 4 shows the category is **not**
in student B's list, so it was not created for student B. Step 5 shows no response object ever
contains `userId`, `user_id`, `createdBy` or anything about who created the row — `CategoryMapper`
omits them, and a default category belongs to nobody. Within one student's list, two categories of
the same name and type cannot both exist (BR-06), so there is no ambiguity about whose row is
shown. Clean up this test's category.

**Result:** ☐ Pass / ☐ Fail

---

### M3-19 — Repeat creates and updates are idempotent, and the identifier is validated

**Covers:** UC-06 duplicate submission handling; the `INVALID_REQUEST` path; endpoints 12, 13, 14.

**Preconditions:** M3-01 passed.

**Steps:**

1. Send `POST /api/v1/categories` with `Authorization: Bearer ${JWT}` and body
   `{"name": "Test Repeat", "type": "EXPENSE"}` — expect `201`; note the `id`.
2. Send the **identical** `POST` again.
3. Send `PATCH /api/v1/categories/{id}` with body `{"description": "same value"}` twice in a row.
4. Send `GET /api/v1/categories/abc` with `Authorization: Bearer ${JWT}` (a non-numeric id).
5. Send `GET /api/v1/categories/99999999999999999999999` with `Authorization: Bearer ${JWT}` (too
   large for the column).
6. Send `GET /api/v1/categories/999999` with `Authorization: Bearer ${JWT}` (a valid number for no
   row).

**Expected result:** Step 2 answers `409` with `"errorCode": "CATEGORY_NAME_TAKEN"` — the second
identical create is refused, so a double-clicked save produces one row, not two. Step 3 answers
`200` both times and the value does not change on the repeat (a repeated identical update is
idempotent). Steps 4 and 5 answer `400` with `"errorCode": "INVALID_REQUEST"` — the path segment is
not a usable identifier. Step 6 answers `404` with `"errorCode": "NOT_FOUND"` — the number is valid
but names no category of the caller's. None of the error bodies contains a SQL statement, a
constraint name, a trigger name or a driver message. Clean up the category from step 1.

**Result:** ☐ Pass / ☐ Fail

---

## 3. Traceability

| Test ID | Covers (UC / BR / rule) | Endpoint(s) | API document reference |
|---|---|---|---|
| M3-01 | UC-06 list; UC-01 B5 (usable empty state); twelve seeded defaults | `GET /categories` | `categories.md` §4, §5 |
| M3-02 | UC-06 create; column defaults (`sortOrder` 0, `isActive` true) | `POST /categories` | `categories.md` §3, §7 |
| M3-03 | UC-06 create with every field; trimming; colour normalised to upper case | `POST`, `GET /categories/{id}` | `categories.md` §3, §6, §7 |
| M3-04 | UC-06 create — both `INCOME` and `EXPENSE`; display order | `POST /categories` | `categories.md` §3, §5, §7 |
| M3-05 | UC-06 edit; partial update leaves other fields alone | `PATCH /categories/{id}` | `categories.md` §8 |
| M3-06 | UC-06 clear a nullable field; `""` clears, `null` leaves | `PATCH /categories/{id}` | `categories.md` §3, §8 |
| M3-07 | UC-06 — `{}` is a no-op, not an error | `PATCH /categories/{id}` | `categories.md` §8 |
| M3-08 | UC-06 edit — type change allowed while unreferenced | `PATCH /categories/{id}` | `categories.md` §10 |
| M3-09 | BR-05 — type immutable once referenced (`trg_categories_before_update`) | `PATCH /categories/{id}` | `categories.md` §8, §10 |
| M3-10 | BR-07 — retire and re-enable through `isActive` | `PATCH /categories/{id}` | `categories.md` §8, §9 |
| M3-11 | UC-06 delete an unused category | `DELETE /categories/{id}` | `categories.md` §9 |
| M3-12 | BR-07 — a referenced category cannot be deleted; retire instead | `DELETE /categories/{id}` | `categories.md` §8, §9, §10 |
| M3-13 | BR-02 ownership; BR-06 defaults read-only for students | `GET`, `PATCH`, `DELETE /categories/{id}` | `categories.md` §4, §6, §13 |
| M3-14 | BR-06 — unique name per student and type (`uk_categories_scope_type_name`); rename honoured | `POST`, `PATCH` | `categories.md` §7, §8, §10 |
| M3-15 | BR-06 — a personal category may not shadow a default (`trg_categories_before_insert`; rename path is the application's addition) | `POST`, `PATCH` | `categories.md` §10 |
| M3-16 | UC-06 reject invalid input per field; documented bounds; multi-line description valid | `POST`, `PATCH` | `categories.md` §3, §7, §8 |
| M3-17 | Role boundary — administrator refused `403`; anonymous refused `401` | all five | `categories.md` §5, §13 |
| M3-18 | No client-supplied ownership; no sensitive fields out | `POST`, `GET` | `categories.md` §3, §13 |
| M3-19 | Duplicate-create refusal; idempotent update; invalid identifier `INVALID_REQUEST` | `POST`, `PATCH`, `GET /categories/{id}` | `categories.md` §6, §7, §11 |

---

## 4. What this procedure does not cover

- **Disabled account and revoked session.** A disabled account or a revoked session is rejected by
  the token filter before the controller, as `401 UNAUTHENTICATED`. Producing that state requires
  the administrator's UC-22 flow (module 11) and a token issued before the change, so it is not
  reproducible by hand from this module alone. Note that this module never returns
  `ACCOUNT_DISABLED`; that code belongs only to the sign-in endpoints.
- **Concurrency.** Six simultaneous identical creates producing one `201` and five `409`s is
  asserted in the module's suite; a manual run cannot reliably produce the timing.
- **A category referenced by a budget or a recurring rule.** M3-09 and M3-12 exercise the
  transaction case. The budget case (refused delete by `trg_categories_before_delete`) and the
  recurring-rule case (refused by `fk_recurring_category`) are the same `409 CATEGORY_IN_USE` and
  would need modules 5 and 6 to set up; both are documented in `categories.md` §10.
- **Administering a default category.** Deliberately absent here — it is UC-20 under
  `/api/v1/admin/**` (`GET`/`POST`/`PATCH /api/v1/admin/categories`), module 11, covered by
  `MODULE_11_MANUAL_TEST.md`. This procedure tests only the student-facing module 3 route.
- **A type filter and a `PUT`.** Deliberately absent: the list is filtered by the client, and
  `PATCH` is the only update method. There is no endpoint to test.

## Related documentation

- `docs/api/categories.md` — the endpoint contract
- `docs/modules/MODULE_03_CATEGORIES.md` — the module report
- `docs/api/API_INVENTORY.md` — endpoints 11–15
- `docs/api/transactions.md` — the transaction endpoint used to create a reference in M3-09
- `docs/CREDENTIALS.md` — the seeded accounts (read the credentials here; never copy them into a
  test artifact)
- `docs/api/authentication.md` — how to obtain a token
