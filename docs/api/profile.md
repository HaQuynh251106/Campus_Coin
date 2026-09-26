# Profile & Preferences API

Module 2 of the Campus Coin backend. Covers **UC-04 (manage personal profile)** and **UC-27
(display preferences)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [GET /api/v1/profile/me](#4-get-apiv1profileme)
5. [PATCH /api/v1/profile/me](#5-patch-apiv1profileme)
6. [PATCH /api/v1/profile/me/preferences](#6-patch-apiv1profilemepreferences)
7. [Partial update semantics](#7-partial-update-semantics)
8. [Status codes](#8-status-codes)
9. [Angular integration notes](#9-angular-integration-notes)
10. [Security properties](#10-security-properties)
11. [Traceability](#11-traceability)

---

## 1. Scope and what is deliberately absent

UC-04 lets a student maintain **their own** profile. UC-27 lets them choose how the application
looks. This module implements exactly those two, and nothing else.

| Not implemented | Why |
|---|---|
| `GET/PUT /api/v1/users/{id}` | UC-04 is a student managing their **own** profile. An endpoint addressed by identifier would need an ownership check that the token-based design makes unnecessary, and is one more place to get it wrong. An administrator acting on another account is UC-22, in module 11 |
| Changing `email` | Not part of UC-04. Changing a sign-in identifier is a security operation (it needs re-verification and a session decision), not a profile edit |
| Changing the password | UC-03 already owns this, through the reset flow |
| Changing `role`, `status` | Not UC-04. Accepting either would be a privilege-escalation route. `status` belongs to UC-22 |
| Changing `currency` | VĐ-08 makes the currency one application-wide setting (`system_settings.app_currency`), not a per-student field |
| A separate `PUT` for the whole profile | UC-04 describes editing values, not replacing a document. `PATCH` is the method that matches, and one endpoint is enough |
| `aiEnabled` | The column exists, but it toggles UC-08 and UC-17, which are later modules. Exposing it here would put another module's contract in this one |
| Avatar, student ID, university, major | Not columns in `users`. See §9.1 — the frontend currently displays these |

---

## 2. Endpoints at a glance

| Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|
| `GET` | `/api/v1/profile/me` | UC-04, UC-27 | Read my profile and preferences | `200` |
| `PATCH` | `/api/v1/profile/me` | UC-04 | Change profile fields | `200` |
| `PATCH` | `/api/v1/profile/me/preferences` | UC-27 | Change display preferences | `200` |

All three require `Authorization: Bearer <accessToken>`. All three act on the caller's own
account, taken from the token. No endpoint accepts a user identifier.

Both update endpoints return the **complete** profile, so the client can replace its cached copy
rather than merge.

---

## 3. Field reference

The response of all three endpoints, and the request fields of the two `PATCH` calls.

| Field | Type | In request | Validation when supplied | Source |
|---|---|---|---|---|
| `id` | number | read-only | — | `users.id` |
| `fullName` | string | optional | 2–120 characters after trimming, not blank | `users.full_name` |
| `email` | string | read-only | — | `users.email` |
| `academicYear` | string \| null | optional | max 30 characters after trimming; `""` clears it | `users.academic_year` |
| `monthlyAllowanceBaseline` | number | optional | ≥ 0, max 13 integer digits, max 2 decimals | `users.monthly_allowance_baseline` |
| `monthlySavingsGoal` | number | optional | ≥ 0, max 13 integer digits, max 2 decimals | `users.monthly_savings_goal` |
| `currency` | string | read-only | — | `users.currency` |
| `themePreference` | string enum | optional | `LIGHT` \| `DARK` \| `SYSTEM` | `users.theme_pref` |
| `fontScale` | string enum | optional | `SMALL` \| `MEDIUM` \| `LARGE` \| `XLARGE` | `users.font_scale` |

**Whitespace is trimmed before the length rule is applied.** `fullName` and `academicYear` are
stored trimmed, so the documented length is the length of the value that is actually saved and
echoed back — not of the raw input. `"  An Nguyen  "` is stored as `"An Nguyen"`, and `" A "` is
rejected with a `fullName` field error even though it is three characters long, because it trims
to one. Send values without relying on padding.

`currency` is read-only here and always three letters (ISO 4217), e.g. `USD`.

**Defaults for a new account**, as the database declares them: `academicYear` is `null`,
both money values are `0.00`, `themePreference` is `SYSTEM`, `fontScale` is `MEDIUM`.

### Enum values are member names, never numbers

`themePreference` and `fontScale` accept the member name the database stores, in upper case:
`"DARK"`, `"XLARGE"`. They do **not** accept a number. `2` is rejected with a field error rather
than being read as the ordinal `LARGE` — an ordinal encoding would silently change meaning if the
Java constants were ever reordered, and it is not part of this contract.

Lower case (`"dark"`) is also rejected. Send the member name exactly.

---

## 4. `GET /api/v1/profile/me`

Returns the signed-in student's profile and display preferences.

### Request

No body. The only input is the bearer token.

```bash
curl -X GET http://localhost:8080/api/v1/profile/me \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

```json
{
  "id": 2,
  "fullName": "An Nguyen",
  "email": "an.nguyen@student.campuscoin.edu",
  "academicYear": "Year 3",
  "monthlyAllowanceBaseline": 650.00,
  "monthlySavingsGoal": 1500.00,
  "currency": "USD",
  "themePreference": "SYSTEM",
  "fontScale": "MEDIUM"
}
```

A student who has not set a year receives `"academicYear": null`. The API does not substitute a
default: the client decides how to render "not set".

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | No token, or the token is malformed, tampered with, expired, revoked, or the account was disabled after the token was issued (BR-03) |
| `404` | `NOT_FOUND` | The account in the token no longer exists |

On this endpoint every one of those `401` causes is reported as `UNAUTHENTICATED`. A disabled
account is **not** given the distinct `ACCOUNT_DISABLED` code here: the token filter rejects it
before the request reaches the controller, and its entry point deliberately reports no-token,
bad-signature, expired, revoked and disabled identically, so the caller cannot tell them apart.
`ACCOUNT_DISABLED` belongs to the sign-in endpoints, where the account being disabled is a message
the caller needs — see [authentication.md](authentication.md). The client's action is the same
either way: clear the session and return to sign-in.

The response never contains `passwordHash`, `tokenVersion`, `role`, `status`, `lastLoginAt`,
`emailVerifiedAt` or `aiEnabled`.

---

## 5. `PATCH /api/v1/profile/me`

Changes the supplied profile fields and leaves the rest as they are (UC-04).

### Request

A JSON object containing **only the fields to change**. See §7 for the full semantics.

```bash
curl -X PATCH http://localhost:8080/api/v1/profile/me \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "fullName": "An Nguyen",
        "academicYear": "Year 3",
        "monthlyAllowanceBaseline": 650.00,
        "monthlySavingsGoal": 1500.00
      }'
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `fullName` | string | optional | 2–120 characters after trimming, must contain a non-space character |
| `academicYear` | string | optional | At most 30 characters after trimming. Send `""` to clear the field |
| `monthlyAllowanceBaseline` | number | optional | ≥ `0`. `0` is valid and means "not stated yet" |
| `monthlySavingsGoal` | number | optional | ≥ `0`. `0` is valid |

An empty object `{}` is valid and changes nothing.

### Response `200 OK`

The complete profile in the same shape as §4, including the changes just applied.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A supplied field failed validation; `fieldErrors` names it |
| `400` | `MALFORMED_REQUEST` | The body is missing, truncated, or not JSON |
| `401` | `UNAUTHENTICATED` | As in §4 — no token, invalid, expired, revoked, or the account is disabled |
| `404` | `NOT_FOUND` | The account in the token no longer exists |

A validation failure writes **nothing**: if a body contains one valid field and one invalid field,
the whole request is rejected and the valid half is not applied.

### Example — invalid body

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/profile/me",
  "fieldErrors": [
    { "field": "monthlySavingsGoal", "message": "Monthly savings goal cannot be negative." }
  ]
}
```

---

## 6. `PATCH /api/v1/profile/me/preferences`

Changes the display preferences (UC-27).

### Request

```bash
curl -X PATCH http://localhost:8080/api/v1/profile/me/preferences \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "themePreference": "DARK", "fontScale": "LARGE" }'
```

| Field | Type | Required | Accepted values |
|---|---|---|---|
| `themePreference` | string | optional | `LIGHT`, `DARK`, `SYSTEM` |
| `fontScale` | string | optional | `SMALL`, `MEDIUM`, `LARGE`, `XLARGE` |

Any other value — including a number, lower case, or an unknown name — is a `400`
`VALIDATION_ERROR` naming that field.

### Response `200 OK`

The complete profile in the same shape as §4.

### Failures

As §5. An unrecognised value produces:

```json
{
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/profile/me/preferences",
  "fieldErrors": [
    { "field": "themePreference", "message": "The value is not one of the accepted values for this field." }
  ]
}
```

---

## 7. Partial update semantics

Both `PATCH` endpoints treat an **absent** field as "leave it alone", so a client may send only
what it edited. Sending the text size must not require resending the theme.

| Body | Effect |
|---|---|
| `{}` | Nothing changes |
| `{ "fontScale": "LARGE" }` | Only `fontScale` changes |
| `{ "fullName": "New", "fontScale": "LARGE" }` | Only `fullName` changes on this endpoint — `fontScale` is not a profile field, so it is ignored here |
| `{ "academicYear": null }` | `academicYear` is **left as it is** |
| `{ "academicYear": "" }` | `academicYear` is **cleared** (set to `null`) |
| `{ "fullName": null }` | `fullName` is left as it is |

The one asymmetry is deliberate. `academicYear` is the only nullable profile field, so it needs a
way to be unset — an empty string does that. The other three have no empty state (there is no
meaningful blank name or allowance), so a `null` there simply means "unchanged", and an empty
string is rejected with a field error.

`null` and `""` are therefore **not** interchangeable on any field.

### Unknown fields are ignored

A body may carry keys this contract does not define; they are discarded, not honoured. This is not
a supported way to send data — it is a safety property. A client that sends `"role": "ADMIN"` or
`"status": "DISABLED"` finds that nothing happens: the fields are not part of the DTO, the entity
exposes no setter for them, and the row is unchanged. Do not rely on unknown fields being
ignored; send only documented fields.

---

## 8. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body and update the cached profile |
| `400` | Validation failed, or the body is malformed | If `fieldErrors` is present, show each `message` beside its `field`. Otherwise show `message` |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `404` | The account no longer exists | Clear the session and return to sign-in |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

`fieldErrors[].field` is the canonical property name project-wide. It is `field`, never `path`.

---

## 9. Angular integration notes

### 9.1 The current frontend must be rewired

`frontend/src/app/core/models/user.model.ts` currently declares:

```ts
export interface User {
  id: string;
  studentId: string;
  name: string;
  avatar: string;
  university: string;
  major: string;
  academicYear: string;
  monthlyAllowance: number;
  savingsGoal: number;
  settings: { darkMode: boolean; fontSize: 'small'|'medium'|'large'; currency: string };
  status: 'ACTIVE'|'DISABLED';
  joinedDate: string;
}
```

None of `studentId`, `avatar`, `university`, `major`, `name`, `settings`, `joinedDate` or `status`
is in this contract, and one of them (`status`) is not a profile field at all. The names that do
correspond need renaming, and the value types differ:

| Frontend today | API field | Change needed |
|---|---|---|
| `name` | `fullName` | Rename |
| `monthlyAllowance` | `monthlyAllowanceBaseline` | Rename |
| `savingsGoal` | `monthlySavingsGoal` | Rename |
| `academicYear` | `academicYear` | Same name, but the API has no fixed list of years — see below |
| `settings.darkMode` + `settings.fontSize` | `themePreference` + `fontScale` | Replace: the API stores a theme **name**, not a boolean |
| `settings.currency` | `currency` | Move out of `settings`; read-only |
| `id: string` | `id: number` | The API returns a number |
| `studentId`, `avatar`, `university`, `major`, `joinedDate`, `status` | — | Not in the contract. Either drop from the UI or source them elsewhere |

Two further differences worth noting before rewiring:

- **Theme is not a boolean.** The database stores `LIGHT`, `DARK` or `SYSTEM`, and `SYSTEM` is a
  meaningfully different state from `LIGHT` — it follows the operating system and changes when the
  user changes it. A `darkMode: boolean` cannot represent that. `ThemeService` currently keeps
  `isDarkMode` in `localStorage` and applies a `dark` class; it needs a third state, and the
  resolved appearance is then `themePreference === 'DARK' || (themePreference === 'SYSTEM' &&
  prefersDark)`.
- **Text size has four steps.** `FontSizePreference` currently allows three (`small`, `medium`,
  `large`); the API adds `XLARGE`. The `applyFontSize` class list needs a fourth entry, and the
  API values are upper case.

- **`academicYear` is free text**, `VARCHAR(30)`, not an enum. The register and profile forms
  currently use a `<select>` with labels like `'Junior (3rd Year)'`, which is a reasonable set of
  choices to **offer** — just remember the server accepts any string up to 30 characters and will
  return whatever was stored, including a label the current `<select>` no longer lists.

### 9.2 Which call to make

- On entering the profile screen: `GET /api/v1/profile/me`. Do not read the profile from the
  sign-in response alone — it carries only `id`, `fullName`, `email` and `role`, and it goes stale
  as soon as the student edits anything.
- Saving the profile form: `PATCH /api/v1/profile/me` with only the changed fields.
- Changing the theme or text size: `PATCH /api/v1/profile/me/preferences`.

Send the token as `Authorization: Bearer <accessToken>` on all three. There is no endpoint in this
module that works without a token.

### 9.3 Sending only what changed

`PATCH` semantics mean the client should build its body from the form's **dirty** fields rather
than sending the whole form. Angular's `FormGroup` gives this directly:

```ts
const dirty = Object.fromEntries(
  Object.entries(form.controls)
    .filter(([, control]) => control.dirty)
    .map(([name, control]) => [name, control.value]),
);
if (Object.keys(dirty).length === 0) { return; }   // nothing to save
this.http.patch<Profile>('/api/v1/profile/me', dirty).subscribe(profile => this.profile.set(profile));
```

This matters for more than tidiness: sending an unchanged `fullName` alongside a changed
`academicYear` is harmless, but sending a field the student did not touch risks overwriting a
change made in another tab.

### 9.4 Handling each status

```ts
this.http.patch<Profile>(url, body).subscribe({
  next: profile => this.profile.set(profile),          // replace the cached copy, do not merge
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'VALIDATION_ERROR':
        response.error.fieldErrors?.forEach((e: { field: string; message: string }) =>
          this.form.get(e.field)?.setErrors({ server: e.message }));
        break;
      case 'UNAUTHENTICATED':
      case 'NOT_FOUND':
        this.auth.signOutLocally();
        this.router.navigate(['/login']);
        break;
      default:
        this.toast.show(response.error?.message ?? 'Something went wrong.');
    }
  },
});
```

Switch on `errorCode`, not on `message` — messages are text and may be reworded. The
`fieldErrors[].field` values match the request field names exactly, so
`form.get(e.field).setErrors(...)` works without a mapping table.

### 9.5 Clearing the academic year

The profile form holds a string. To clear the field, send `""` rather than omitting the key:

```ts
const body = form.controls.academicYear.dirty
  ? { academicYear: form.controls.academicYear.value ?? '' }
  : {};
```

### 9.6 Applying preferences locally

`themePreference` and `fontScale` are stored server-side, so they follow the student to another
device. Apply them from the API response rather than from `localStorage`, and treat
`localStorage` as a cache for the first paint only — otherwise a student who changes the theme on
one machine sees the old setting on another.

---

## 10. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. No endpoint accepts a user id, and the identity comes from the verified token. There is no identifier in the request to tamper with |
| **No privilege escalation** | The DTOs define four profile fields and two preference fields. `role`, `status`, `password_hash`, `token_version`, `email` and `id` are absent from every request type, and the entity exposes no setter for them. `ProfileApiIT.profileRequestCannotEscalatePrivileges` sends all of them and asserts the row is unchanged |
| **No sensitive response fields** | `ProfileMapper` is the single place that decides what leaves the server. `password_hash` and `token_version` are never mapped to the response |
| **Disabled account** | Rejected by the token filter before the request reaches the controller (BR-03) |
| **Revoked session** | Rejected by the token filter on every request (UC-02 B5) |
| **No unauthorised write on failure** | Validation runs before the service. A rejected body is not partially applied |
| **No lost update** | `User` is annotated `@DynamicUpdate`, so an UPDATE names only the changed columns. Without it, a profile edit would write back the `status` and `token_version` values read at the start of the request and could silently undo a concurrent password-reset or account-disable revocation (BR-03) |
| **No internal detail in errors** | Validation failures return constraint text only. A malformed body returns `MALFORMED_REQUEST` with no parser or class name |
| **No SQL or driver leak** | A database constraint failure is mapped to `DATA_CONFLICT`; the driver message is logged server-side, never returned |
| **Audit** | Profile and preference changes are logged with the account id and no field values |

---

## 11. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-04 | Read own profile | `GET /profile/me` | `ProfileController.getMyProfile` | `ProfileService.getProfile` | `users.full_name`, `academic_year`, `monthly_allowance_baseline`, `monthly_savings_goal` | `ProfileApiIT.newStudentProfileUsesTheSchemaDefaults` |
| UC-04 | Update profile | `PATCH /profile/me` | `ProfileController.updateMyProfile` | `ProfileService.updateProfile` | same four columns | `ProfileApiIT.profileUpdatePersistsEveryField` |
| UC-04 | Partial update | `PATCH /profile/me` | as above | as above | as above | `ProfileApiIT.omittedFieldsAreLeftAlone` |
| UC-04 | Clear the year | `PATCH /profile/me` | as above | as above | `users.academic_year` (nullable) | `ProfileApiIT.emptyAcademicYearClearsTheValue` |
| UC-04 | Reject invalid input | `PATCH /profile/me` | `UpdateProfileRequest` constraints | — | `ck_users_money` (second line) | `ProfileApiIT.invalidProfileFieldsAreReportedPerField`, `ProfileApiIT.databaseCheckRejectsNegativeMoney` |
| UC-27 | Read preferences | `GET /profile/me` | `ProfileController.getMyProfile` | `ProfileService.getProfile` | `users.theme_pref`, `users.font_scale` | `ProfileApiIT.newStudentProfileUsesTheSchemaDefaults` |
| UC-27 | Update preferences | `PATCH /profile/me/preferences` | `ProfileController.updateMyPreferences` | `ProfileService.updatePreferences` | same two columns | `ProfileApiIT.preferencesAreUpdated` |
| UC-27 | Reject an unknown value | `PATCH /profile/me/preferences` | enum-typed DTO | — | `theme_pref` / `font_scale` ENUM | `ProfileApiIT.unknownPreferenceValueIsReportedPerField`, `ProfileApiIT.everyEnumMemberIsAccepted` |
| BR-02 | Students act only on their own data | all three | `@AuthenticationPrincipal` | `requireCaller` from token | `users.id` from the token | `ProfileApiIT.profileUpdateTouchesOnlyTheCallersRow` |
| BR-03 | Disabled account refused | all three | — | token filter | `users.status`, `users.token_version` | `ProfileApiIT.disabledAccountIsRefused`, `ProfileApiIT.savingAProfileChangeWritesOnlyTheChangedColumn` |
| §7.5 | No privilege escalation | all three | DTO field set | entity setter set | `users.role`, `users.status` | `ProfileApiIT.profileRequestCannotEscalatePrivileges` |
| §7.7 | No sensitive fields, no internals | all three | — | `ProfileMapper` | — | `ProfileApiIT.profileResponseExposesNoSensitiveFields`, `ProfileApiIT.malformedBodyIsRejectedWithoutLeakingInternals` |
| VĐ-04 | Allowance and savings goal | `PATCH /profile/me` | `UpdateProfileRequest` | `ProfileService.updateProfile` | `ck_users_money`, read by `v_dashboard_summary` | `ProfileApiIT.zeroMoneyValuesAreAccepted`, `ProfileApiIT.rejectedUpdateWritesNothing` |

`VĐ-04` is the source rule behind the two money fields; the only database objects that read them
are `v_dashboard_summary` (module 7) and `sp_generate_tips` (module 9), so this module reads and
writes the columns without duplicating any database logic.

---

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, interceptors, the shared error contract, the enum reference and the master
  table of all 76 operations
- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
