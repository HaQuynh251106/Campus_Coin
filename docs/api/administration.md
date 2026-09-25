# Administration API — endpoints 46–61

Request and response contract for module 11 (UC-20 … UC-23). Sixteen operations on thirteen paths, all
of them `ADMIN`-only, all of them wired to stored procedures or views that already exist in `db/`.

This document is the companion to [API_INVENTORY.md](API_INVENTORY.md); the inventory says which
operations exist and why none of them duplicate another, and this one says what each one accepts and
returns.

---

## Table of contents

1. [Scope](#1-scope)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [Managing accounts — 46–48](#4-managing-accounts--4648)
5. [Default categories — 49–51](#5-default-categories--4951)
6. [Announcements and tip templates — 52–57](#6-announcements-and-tip-templates--5257)
7. [Settings and statistics — 58–61](#7-settings-and-statistics--5861)
8. [Status codes](#8-status-codes)
9. [Security properties](#9-security-properties)
10. [Angular integration notes](#10-angular-integration-notes)
11. [Traceability](#11-traceability)

---

## 1. Scope

| Covered by this document | Use case |
|---|---|
| List, disable/enable and send a reset link to an account | UC-22 |
| Read and maintain the shared default categories | UC-20 |
| Read, publish, retract announcements; read and upsert tip templates | UC-21 |
| Read and change the adjustable thresholds; read the system-wide usage figures | UC-23 / VĐ-05 |

**Deliberately not here.** Each of these was considered and refused in
[API_INVENTORY.md § Module 11](API_INVENTORY.md#module-11--administration); the list is repeated so a
reader of this document does not go looking for them:

| Not built | Why |
|---|---|
| `PUT` on any resource | Every writable thing is a `PATCH`, and every procedure is an "upsert" or a "set one column" |
| `DELETE` on users, categories, announcements, tip templates | No procedure exists; retirement is `PATCH { "isActive": false }` |
| `GET /admin/users/{id}` | 47 and 48 already return the account; a bare read is the same row with less context |
| `POST .../activate`, `.../deactivate` | Three names for one write of one column |
| `GET /admin/audit-log` | UC-22 B5 requires *writing* an audit row, not reading one, and no view is defined over the table |
| `?role=`, `?status=`, `?q=` on 46 | Not in UC-22; the client filters what it has |
| `/admin/insights/**`, `/admin/anomalies/**`, `/admin/ai/**` | Module 12, which is locked |

---

## 2. Endpoints at a glance

| # | Method | Path | Body | Success |
|---|---|---|---|---|
| 46 | `GET` | `/api/v1/admin/users` | — | `200` array of `AdminUserResponse` |
| 47 | `POST` | `/api/v1/admin/users/{id}/status` | `SetUserStatusRequest` | `200` `AdminUserResponse` |
| 48 | `POST` | `/api/v1/admin/users/{id}/password-reset` | — | `202` `AdminPasswordResetResponse` |
| 49 | `GET` | `/api/v1/admin/categories` | — | `200` array of `CategoryResponse` |
| 50 | `POST` | `/api/v1/admin/categories` | `UpsertDefaultCategoryRequest` | `201` `CategoryResponse` |
| 51 | `PATCH` | `/api/v1/admin/categories/{id}` | `UpdateDefaultCategoryRequest` | `200` `CategoryResponse` |
| 52 | `GET` | `/api/v1/admin/announcements` | — | `200` array of `AnnouncementResponse` |
| 53 | `POST` | `/api/v1/admin/announcements` | `CreateAnnouncementRequest` | `201` `AnnouncementResponse` |
| 54 | `PATCH` | `/api/v1/admin/announcements/{id}` | `UpdateAnnouncementRequest` | `200` `AnnouncementResponse` |
| 55 | `GET` | `/api/v1/admin/tip-templates` | — | `200` array of `TipTemplateResponse` |
| 56 | `POST` | `/api/v1/admin/tip-templates` | `CreateTipTemplateRequest` | `201` `TipTemplateResponse` |
| 57 | `PATCH` | `/api/v1/admin/tip-templates/{id}` | `UpdateTipTemplateRequest` | `200` `TipTemplateResponse` |
| 58 | `GET` | `/api/v1/admin/settings` | — | `200` array of `SystemSettingResponse` |
| 59 | `PATCH` | `/api/v1/admin/settings/{key}` | `UpdateThresholdRequest` | `200` `SystemSettingResponse` |
| 60 | `GET` | `/api/v1/admin/stats` | — | `200` `AdminUsageStatsResponse` |
| 61 | `GET` | `/api/v1/admin/stats/top-categories` | — | `200` array of `AdminTopCategoryResponse` |

### Parameters

Only three paths take a variable, and none of them takes a query parameter.

| Parameter | In | Type | Notes |
|---|---|---|---|
| `id` | path, 47–48 | `long` | The account's id. Not an email, so the client uses what 46 returned. |
| `id` | path, 51, 54, 57 | `long` | The resource's id. |
| `key` | path, 59 | `string` | One of the six adjustable keys; see [§7](#7-settings-and-statistics--5861). URL-encoded: the keys contain dots, which are legal in a path segment unencoded, but a client should encode them anyway. |

### Authentication

Every route needs `Authorization: Bearer <token>` where the account's *current* role is `ADMIN`. This is
one line in `SecurityConfig`, and it is the whole authorisation statement for the module:

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

The rule is declared **ahead of** the student-facing rules. That ordering is load-bearing: an
administrator's token would otherwise be admitted through a student route, and a student's would reach
these. See [§9](#9-security-properties).

---

## 3. Field reference

### `AdminUserResponse` (46, 47)

| Field | Type | Present | Notes |
|---|---|---|---|
| `id` | number | always | The database's id. |
| `email` | string | always | Published only to an administrator — see [§9](#9-security-properties). |
| `fullName` | string | always | |
| `role` | `STUDENT` \| `ADMIN` | always | |
| `status` | `ACTIVE` \| `DISABLED` | always | A disabled account cannot sign in and its existing tokens are refused (BR-03). |
| `academicYear` | string | when set | **Omitted** when the account has not set one, not sent as `null`. |
| `lastLoginAt` | datetime | when set | **Omitted** when the account has never signed in. |
| `createdAt` | datetime | always | |

**Never present**, and absent from `AdminUserRow` structurally rather than filtered on the way out:
`passwordHash` (BR-01), `tokenVersion` (it is the entire security meaning of a JWT's `tv` claim),
`monthlyAllowanceBaseline` and `monthlySavingsGoal` (VĐ-04 — an administrator manages accounts, not a
student's finances), and `themePreference`, `fontScale`, `aiEnabled`, `emailVerifiedAt`, `updatedAt`
(no UC-22 screen has them).

> The two omitted fields are the contract, not a gap. `UpdateProfileRequest` is where `academicYear`
> is set, and signing in is what writes `last_login_at` — so both appear on any account that has done
> either. A client rendering a column for them must treat "absent" as "never / not set".

### `CategoryResponse` (49–51)

The category endpoints reuse `com.campuscoin.category.dto.CategoryResponse`, which is UC-06's response —
the same table, the same row shape, and one definition of a category rather than two that could drift.
Module 11 adds no second category DTO.

`id`, `name`, `type` (`INCOME` \| `EXPENSE`), `icon`, `color`, `isDefault`, `isActive`, `sortOrder`,
`description`. `icon`, `color` and `description` are **omitted** when not set.

`isDefault` is `true` for every row these routes return, since all of them have `user_id IS NULL`. The
field is carried rather than assumed because it is the same response a student's own category list uses,
where it is `false`; a client rendering these three routes can rely on it being `true`.

A student's personal categories are a different resource at `/api/v1/categories` and are not reachable
through these routes.

### `AnnouncementResponse` (52–54)

| Field | Type | Present | Notes |
|---|---|---|---|
| `id` | number | always | |
| `title` | string | always | |
| `body` | string | always | |
| `severity` | `INFO` \| `WARNING` \| `SUCCESS` | always | |
| `audience` | `ALL` \| `STUDENTS` \| `ADMINS` | always | |
| `startsAt` | datetime | always | |
| `endsAt` | datetime | when set | **Omitted** for an open-ended notice. |
| `isActive` | boolean | always | |
| `createdAt` | datetime | always | |

There is **no author field**. Who published a notice is recorded in `admin_audit_log`, which is the
record of who did what; a second, weaker answer to the same question would be a second thing to keep
in step. The student-facing dashboard takes the same position.

### `TipTemplateResponse` (55–57)

`id`, `code`, `conditionType`, `titleTemplate`, `bodyTemplate`, `defaultPriority`, `isActive`.

`conditionType` is one of seven: `GENERIC`, `LOW_SAVINGS_RATE`, `CATEGORY_SPIKE`, `OVER_BUDGET`,
`NEAR_BUDGET`, `NO_BUDGET_SET`, `SAVINGS_GOAL_AT_RISK`. The type names the rule a template belongs to;
`sp_generate_tips` decides whether the rule applies from each student's own data, not from the template's
type alone.

`conditionParams` is **not published**. The column is a JSON blob whose meaning the schema does not
document and which nothing in this build reads — `sp_generate_tips` decides its conditions from each
student's own data, not from this column — so publishing it would expose a value no reader could
interpret. Recorded as a follow-up rather than guessed at.

### `SystemSettingResponse` (58, 59)

| Field | Type | Present | Notes |
|---|---|---|---|
| `key` | string | always | |
| `value` | string | always | Always a string; the value's shape is a separate concern. |
| `valueType` | string | always | The database's declared type for the row — `STRING`, `INT`, `DECIMAL`, `BOOLEAN` or `JSON`. |
| `description` | string | when set | **Omitted** when the row has no description. |
| `adjustable` | boolean | always | `true` only for the six keys [§7](#7-settings-and-statistics--5861) lists. The ten others answer `false`, which is why the field is always present and never omitted. |

A client should render `adjustable == true` rows as editable and everything else as read-only. The flag
and `PATCH`'s allow-list are the same list, so a request that ignored the flag is refused rather than
accepted and dropped.

`updatedBy` and `updatedAt` are **not published**: the audit trail records who changed a threshold and
when, and a `SETTING_CHANGED` row carries the previous value too, which a per-row copy could not.

### `AdminUsageStatsResponse` (60)

Ten aggregates and nothing else — no identifier of any kind.

| Field | Type |
|---|---|
| `totalStudents`, `activeStudents`, `disabledStudents` | number |
| `activeUsers30d` | number — distinct users with a session seen in the last 30 days |
| `totalTransactions` | number — excludes soft-deleted rows |
| `totalExpenseLogged`, `totalIncomeLogged` | decimal |
| `totalBudgets`, `totalTipsGenerated`, `totalInsightsGenerated` | number |

> **`totalInsightsGenerated` is not zero**, and a reader who expects it to be should know why. The
> integration and demo databases are seeded from `db/06_demo.sql`, which calls
> `sp_generate_monthly_insight` for three demo months, so `insights` holds three rows. UC-17 is still
> locked: **no Java in this build reads or writes that table**, and no administrative procedure touches
> it. The figure is served because `v_admin_usage_stats` defines it, and hiding a column the schema
> declares would be a worse answer than reporting it.

### `AdminTopCategoryResponse` (61)

`categoryId`, `categoryName`, `type`, `scope` (`DEFAULT` \| `PERSONAL`), `txnCount`, `totalAmount`,
`distinctUsers`.

**The order of the array is the contract.** The view `v_admin_top_categories` declares no `ORDER BY`,
so the DAO supplies a total order: `txnCount DESC, totalAmount DESC, categoryId ASC`. Without the final
tie-break two categories with equal counts and totals could swap between two identical calls and a
client diffing them would see movement that never happened. There is **no `LIMIT`**: the ranking covers
every category, including ones no transaction has ever used (they appear with zeroes).

---

## 4. Managing accounts — 46–48

### 46 — `GET /api/v1/admin/users`

Every account, students and administrators alike, ordered by created time. There are **no search or
filter parameters**; UC-22 does not define any and the client filters the list it holds.

**Response** `200` — an array of [`AdminUserResponse`](#adminuserresponse-46-47).

### 47 — `POST /api/v1/admin/users/{id}/status`

Body: `{ "status": "ACTIVE" | "DISABLED" }`.

**This is a `POST` to a sub-resource rather than a `PATCH` on the user, and the reason is the side
effects.** Disabling an account is not a field edit: `sp_set_user_status` also

- revokes every open session of that account, with `revoked_reason = 'ADMIN_DISABLE'`, and
- increments `users.token_version` (BR-06).

So the token the student is holding stops working immediately, for three independent reasons — the
session row is revoked, the `tv` claim no longer matches the row, and `sp_require_admin` /
`SessionService` read `users.status` on every request. `POST /notifications/{id}/read` and
`POST /tips/{id}/state` have the same shape for the same reason.

Enabling an already-active account, or disabling an already-disabled one, is **accepted** and leaves an
audit row saying so. The caller asked for the row to be in that state and it is; a truthful record of a
request that needed no change is better than an error the client has to special-case.

Self-disable is refused with `409 SELF_DISABLE_FORBIDDEN`. The guard is **one-sided**: an administrator
*may* re-enable their own account (`ACTIVE` on an already-active account is a permitted no-op). That is
deliberate — the danger is locking yourself out, not letting yourself back in — and it is stated here so
a reviewer does not "fix" it.

The service checks this **before** calling, and that order is testable rather than cosmetic.
`sp_set_user_status` signals SQLSTATE 45000 for all three of its refusals (`Account does not exist`,
`Invalid status`, `An administrator cannot disable their own account`) with no way to tell them apart
except by matching the procedure's prose, which this project forbids. With the self-disable answered in
Java, a surviving 45000 can only mean "the target does not exist", and it is classified by a pre-read
rather than by the message. The procedure's own check remains the guarantee for a hand-run `CALL`.

**Response** `200` — the account in its new state, as `AdminUserResponse`.

### 48 — `POST /api/v1/admin/users/{id}/password-reset`

Sends the target account a reset link.

**Not a duplicate of `POST /api/v1/auth/password-reset/request`**, and the differences are the point:

| | `POST /auth/password-reset/request` (public) | `POST /admin/users/{id}/password-reset` |
|---|---|---|
| Caller | Anyone | An administrator |
| Addressed by | Email address | Account id |
| Unknown target | `202` regardless — anti-enumeration, BR-04 | `404` — the caller is entitled to know |
| Audit | None | An `admin_audit_log` row (`PASSWORD_RESET_SENT`) |
| Token delivered | To the account's address | To the account's address |

Two use cases, two disclosure policies, one table.

**Response** `202 Accepted` with `{ "message": "..." }` and nothing else. **The raw token is never in
the response** — the database stores a 64-character hex `token_hash`, and the only place the raw value
exists is the message sent to the account (in development, the sink file).

A `404` here and on 47 does reveal whether an id exists. That is intended for an administrator, and it
differs deliberately from module 10's "not yours and does not exist look identical" policy: the
bookmarks routes are reachable by any student, so distinguishing the two would be an enumeration oracle;
these are behind `hasRole("ADMIN")`, where the caller is authorised to know and needs to. See
[§9](#9-security-properties).

---

## 5. Default categories — 49–51

### 49 — `GET /api/v1/admin/categories`

Every shared default category, `WHERE user_id IS NULL`.

This route exists because no other one can serve it: `GET /api/v1/categories` is `STUDENT`-only and its
`findVisibleToUser` merges the caller's own rows with the defaults, which is what a student's screen
wants and not what an administrator's editor wants.

**Response** `200` — an array of [`CategoryResponse`](#categoryresponse-4951).

### 50 — `POST /api/v1/admin/categories`

Creates a default category. Body: `UpsertDefaultCategoryRequest` — `name` (required), `type`
(required), `icon`, `color`, `sortOrder`, `isActive`.

The write is `CALL sp_admin_upsert_default_category`, never a `save`. This matters: the application
account has direct table grants, so a `JpaRepository#save` would bypass `sp_require_admin` and write no
audit row. Every write in this module is a `CALL` for that reason, which is the invariant OB-005 states.

`trg_categories_before_insert` enforces BR-06's scope rules independently.

**Response** `201` — the created category. The row is read back by its natural identity
`(type, name)` among the default rows, guaranteed unique by `uk_categories_scope_type_name`; see
[§6](#6-announcements-and-tip-templates--5257) for why the id cannot come from `LAST_INSERT_ID()`.

### 51 — `PATCH /api/v1/admin/categories/{id}`

Updates a default category. Body: `UpdateDefaultCategoryRequest` — `name`, `type`, `icon`, `color`,
`sortOrder`, `isActive`. Follows `UpdateCategoryRequest`'s convention: **absent or `null` means
"unchanged"**, and `""` clears a nullable field.

Only a row with `user_id IS NULL` can be reached; a personal category's id answers `404`. Retirement is
`PATCH { "isActive": false }` — there is no `DELETE`, because `sp_admin_upsert_default_category` only
inserts or updates and retiring is BR-07's answer (the same one module 3 gives for a personal category).

**Response** `200` — the category in its new state.

---

## 6. Announcements and tip templates — 52–57

### 52 — `GET /api/v1/admin/announcements`

Every announcement — live, expired, not yet started and retracted alike — newest first.

**This reads the table, not `v_active_announcements`.** The view applies the time window and the
`is_active` flag, which is exactly what a student's dashboard wants and exactly what an administrator
must not have: the notice an administrator needs to act on is the expired, the not-yet-started or the
switched-off one, and 54 exists to switch it back on. Reading through the view would leave a deactivated
notice unreachable and therefore unrecoverable, and would make the list disagree with the toggle that
acts on it.

This is also not the dashboard's announcement slice: that one additionally filters
`audience IN ('ALL','STUDENTS')` and drops `audience` from the response. Reusing it would be a duplicate
capability with a wrong answer.

**Response** `200` — an array of [`AnnouncementResponse`](#announcementresponse-5254).

### 53 — `POST /api/v1/admin/announcements`

Body: `CreateAnnouncementRequest` — `title` (required), `body` (required), `severity`, `audience`,
`startsAt` (optional; defaults to now), `endsAt` (optional; omitted means open-ended).

**The window is validated in Java before the call**, so the caller gets a `400` with a field error on
`endsAt` rather than a constraint violation naming `ck_ann_window`. The constraint remains the
authority; Java's check is a better-worded report of the same rule. The rule is strict — an end equal to
the start is refused, because a notice whose window is one instant long would never be seen.

Both times are truncated to the second before anything else happens, and that is load-bearing:
`starts_at` is `DATETIME`, which MySQL stores with no fractional part, and the row is read back by
`(created_by, title, starts_at)`. A value carrying nanoseconds would be rounded on the way in and the
read-back would match nothing. Truncating before the window check also keeps that check and
`ck_ann_window` agreeing on a pair that differs only below a second.

**Response** `201` — the created announcement. It is located by `(created_by, title, starts_at)`
`ORDER BY id DESC LIMIT 1`, within the same transaction as the write.

### 54 — `PATCH /api/v1/admin/announcements/{id}`

Body: `{ "isActive": true | false }` — **and nothing else, because an announcement's content is
create-once.**

`announcements` has no content-update procedure, while `tip_templates` has an upsert; the asymmetry is
deliberate. A notice is a thing that was published, and correcting it means publishing the corrected
text and retracting the old one, which preserves the record of what students actually read. The
practical consequence is that **every** administrative write to this table goes through
`sp_require_admin` and leaves an audit row — there is no unaudited path. A typo is fixed by posting a
corrected notice, not by editing history.

**Response** `200` — the announcement in its new state.

### 55–57 — tip templates

`GET /api/v1/admin/tip-templates` (55) lists every template ordered by `defaultPriority` then id — the
same order the generators would consider them in, which is what makes a priority editable with any
confidence.

`POST` (56) takes `CreateTipTemplateRequest`; `PATCH` (57) takes `UpdateTipTemplateRequest` with the
usual "absent means unchanged" convention.

**A template's `code` is immutable, and the silent path is the dangerous one.**
`sp_admin_upsert_tip_template`'s update branch writes every editable column and does not write `code` at
all, so a `PATCH` carrying a different code would **succeed, change nothing, and answer "saved"** —
leaving the client with a code that never moved. The service therefore loads the row first:

- a **different** code → `409 TIP_TEMPLATE_CODE_IMMUTABLE`;
- the **same** code → accepted as a no-op, so a client can round-trip a full representation;
- a code that **already exists** on insert → `409 TIP_TEMPLATE_CODE_TAKEN`, classified from
  `uk_tip_template_code` by name rather than by SQLSTATE alone (23000 also covers the foreign keys).

`code` is the template's identity: `sp_generate_tips` and the seeded data both refer to templates by it,
which is why `uk_tip_template_code` exists and why it cannot be reassigned.

---

## 7. Settings and statistics — 58–61

### 58 — `GET /api/v1/admin/settings`

Every row of `system_settings`, each marked with whether this API may change it.

The table holds sixteen rows and `sp_admin_set_threshold` permits **six**. The other ten are set for the
deployment, are read by Java rather than by that procedure, or belong to a capability this build does not
have. A client that offered to edit one would be offering an edit the database refuses, so the response
says which are which.

**Response** `200` — an array of [`SystemSettingResponse`](#systemsettingresponse-58-59).

### 59 — `PATCH /api/v1/admin/settings/{key}`

Body: `{ "value": "<string>" }`.

The key is in the **path**, not the body, so one setting occupies one URL and the allow-list is
discoverable. The six adjustable keys, and the shape each value must have:

| Key | Shape |
|---|---|
| `budget.near_threshold_pct` | positive number |
| `budget.exceeded_threshold_pct` | positive number |
| `insight.spike_threshold_pct` | positive number |
| `insight.spike_baseline_months` | whole number, 1–12 |
| `tips.max_dashboard` | positive number |
| `auth.reset_token_ttl_minutes` | positive number |

A key that exists but is not adjustable (`app.currency`, `ai.enabled`, …) → `409
THRESHOLD_NOT_ADJUSTABLE`. A value of the wrong shape → `400` with a field error on `value`.

> **The two `insight.*` keys are adjustable, and that is a correction worth recording.** An earlier plan
> proposed refusing them by name as locked module-12 surfaces. That premise is false:
> `sp_generate_tips` (`db/03_procedures.sql`) reads `insight.spike_threshold_pct` to decide BR-15's
> category-spike tip, and `v_category_spend_trend` (`db/02_views.sql`) joins both keys. That is
> **UC-25 / BR-15 spike detection, shipped in module 9** and pinned by `TipsRuleCoverageIT` — not
> UC-17's monthly `insights` table, which is what module 12 actually holds. Refusing them would leave a
> shipped behaviour permanently untunable, against VĐ-05. Module 12's own surfaces — the `insights`
> table and its procedure — stay untouched and have no settings key here.

Each change writes an audit row (`SETTING_CHANGED`) whose `detail` carries `{key, oldValue, newValue}`
with `target_id IS NULL`.

**Response** `200` — the setting with its new value.

### 60 — `GET /api/v1/admin/stats`

The ten system-wide aggregates of [`AdminUsageStatsResponse`](#adminusagestatsresponse-60).

> **These two routes and 61 publish money, and the way they are shaped is the answer to that.**
> `totalExpenseLogged`, `totalIncomeLogged` and each ranking row's `totalAmount` are `SUM(amount)` over
> plaintext `transactions.amount` — the aggregate exposure recorded as OB-013, a deferred decision this
> module does not change. What module 11 does is serve the figures, record the exposure, and **publish
> no per-student amount anywhere**: every money value here is a sum over many students, and no
> administrative response carries a monetary field belonging to one account.

### 61 — `GET /api/v1/admin/stats/top-categories`

The ranked list of [`AdminTopCategoryResponse`](#admintopcategoryresponse-61), ordered as described
there.

60 and 61 are separate routes because they are two shapes, not one: `v_admin_usage_stats` is a single
scalar aggregate row and `v_admin_top_categories` is a ranked list including unused categories. Merging
them would nest a list inside a scalar row or drop `categoryId`.

---

## 8. Status codes

| Status | `errorCode` | When |
|---|---|---|
| `200` | — | A successful read or `PATCH` |
| `201` | — | A successful `POST` that created a resource (50, 53, 56) |
| `202` | — | The reset link was accepted for delivery (48) — a message, never a token |
| `400` | `VALIDATION_ERROR` | A body field failed validation, or an announcement window that `ck_ann_window` would refuse |
| `401` | `UNAUTHENTICATED` | No token, an expired one, a revoked session, a stale `tv`, or a disabled account |
| `403` | `ACCESS_DENIED` | Authenticated, but the account's current role is not `ADMIN` |
| `404` | `NOT_FOUND` | The id does not exist (47, 48, 51, 54, 57), or no route is mapped |
| `409` | `SELF_DISABLE_FORBIDDEN` | An administrator tried to disable their own account |
| `409` | `THRESHOLD_NOT_ADJUSTABLE` | The key exists but is not one of the six |
| `409` | `TIP_TEMPLATE_CODE_TAKEN` | The code is already used by another template |
| `409` | `TIP_TEMPLATE_CODE_IMMUTABLE` | A `PATCH` carried a different `code` |
| `409` | `DATA_CONFLICT` | A database rule refused the write and no more specific code applies |
| `500` | `INTERNAL_ERROR` | Unexpected; the cause is logged server-side and never sent |

The **code** is the contract, not the message: a client should branch on `errorCode` and may display
`message` as-is.

---

## 9. Security properties

**The role rule is one line and it is the whole statement.** `/api/v1/admin/**` requires `ADMIN`, and
the rule sits ahead of every student rule in `SecurityConfig`. A parameterised test sweeps all sixteen
operations with a student token (expects `403 ACCESS_DENIED`) and with no token (expects
`401 UNAUTHENTICATED`) — which is what makes "a route added later lands inside the rule" a tested fact
rather than a hope.

**Authority comes from the live `users` row, not from the token's role claim.** `SessionService`
re-reads the account on every request and builds the principal from `user.getRole()`; the JWT's `role`
claim is decoded and then never used, and only the `tv` claim is compared against the row. The
consequence is that a **promotion takes effect on a token already in circulation** — there is no
re-sign-in — and, symmetrically, a demotion or disable does too. Both are pinned by tests. This is
deliberate: the row is already the authority for `status` and `token_version`, and reading the role from
the claim as well would make the role the one account attribute a stale token could assert about itself.

**Every write is a stored procedure.** The application account holds direct table grants, so a
`JpaRepository#save` would bypass `sp_require_admin` and write no audit row. Every administrative write
therefore issues a `CALL`, and each procedure calls `sp_require_admin` (role `ADMIN` **and** status
`ACTIVE`, looked up in `users` — never a session flag, which a pooled connection could leak) and inserts
its own `admin_audit_log` row. Decision 54's "`isActive` only" is what keeps this true: a content-update
endpoint would have had to write through Hibernate.

**A database refusal is classified by SQLSTATE, never by prose.**

| SQLSTATE | Driver exception | Translated to |
|---|---|---|
| `45000` | `InvalidDataAccessResourceUsageException` | The refusal that identifier maps to — after a Java pre-read has eliminated the alternatives |
| `23000` | `DataIntegrityViolationException` | `409` with the most specific code the constraint name implies, else `DATA_CONFLICT` |

`AdminWriteFailure` walks the cause chain and asks by SQLSTATE and by constraint **name**. Procedure
message text is never matched, and a unit test asserts that a log-shaped string mentioning a constraint
name is *not* matched.

**What must never leave the server**, enforced structurally — the response records have no component for
these, so a reviewer can see the whole guarantee on one screen:

- `password_hash` (BR-01) and `token_version`;
- `monthly_allowance_baseline`, `monthly_savings_goal` (VĐ-04);
- `condition_params`;
- `theme_pref`, `font_scale`, `ai_enabled`, `email_verified_at`, `updated_at`;
- the raw password-reset token.

A recursive key scan over every read endpoint's raw JSON asserts that none of the forbidden key names
appears anywhere, with a non-triviality guard that plants a forbidden key in a control payload and
proves the scan rejects it.

---

## 10. Angular integration notes

### Sign-in and token handling

Administrators sign in at `POST /api/v1/admin/auth/login` (UC-05 B1). The token is a bearer token like
any other, and an `ADMIN` account's token reaches these routes and no student route. Store it the same
way the student token is stored; there is nothing special about it beyond the role.

### A `403` on these routes is not "sign in again"

| Status | What the client should do |
|---|---|
| `401` | The token is gone, expired or revoked → send the user to sign-in. |
| `403` | The account is authenticated but is not an administrator → do **not** retry; the user has no business on this screen. |
| `404` | The id is wrong or the row is gone → reload the list; do not blind-retry. |
| `409` | The refusal is specific and actionable — read `errorCode` and show it next to the control that caused it. |
| `400` | A field error; `fieldErrors[].field` names the input. |

### Divergences from the student-facing screens

| Difference | Why |
|---|---|
| The user list has **no** server-side search or filter | Not in UC-22; filter the array client-side |
| `academicYear` and `lastLoginAt` are **absent** on accounts that have never set them or signed in | `@JsonInclude(NON_NULL)` — a column that means "never" should not read as a date |
| The category editor shows **only** `user_id IS NULL` rows | A student's personal categories are a different resource and are not administrable |
| An announcement's body is **read-only** after creation | Content is create-once; publish a corrected notice and retract the old one |
| A tip template's `code` is **read-only** after creation | Immutable, and a `PATCH` that changes it is refused rather than silently ignored |
| Only six settings rows are editable | The other ten are read-only; render from the `adjustable` flag |
| `totalInsightsGenerated` is a **non-zero** count on a seeded database | The demo seed generates three insights; module 12 is still locked |

### The recurring `409` cases are the interesting ones

The four specific `409` codes exist because the alternative in each case was a silent success. Angular
should surface them:
`TIP_TEMPLATE_CODE_IMMUTABLE` means the form let the user edit a field it should have rendered
read-only; `TIP_TEMPLATE_CODE_TAKEN` means "choose another code"; `SELF_DISABLE_FORBIDDEN` means "ask
another administrator"; `THRESHOLD_NOT_ADJUSTABLE` means the client offered an edit it should have
greyed out from the `adjustable` flag.

---

## 11. Traceability

| Requirement | Where it is implemented | Where it is pinned |
|---|---|---|
| UC-20 — manage default categories | 49–51, `sp_admin_upsert_default_category` | `AdminCategoryApiIT` (20 tests) |
| UC-21 — announcements and tip templates | 52–57 | `AdminAnnouncementApiIT` (17), `AdminTipTemplateApiIT` (17) |
| UC-22 — manage accounts | 46–48, `sp_set_user_status`, `sp_admin_send_password_reset` | `AdminUserApiIT` (21) |
| UC-23 — usage statistics | 60–61, `v_admin_usage_stats`, `v_admin_top_categories` | `AdminStatsApiIT` (11) |
| VĐ-05 — adjustable thresholds | 58–59, `sp_admin_set_threshold`, `AdminThresholds` | `AdminSettingsApiIT` (28) |
| BR-03 — disable means disable | `sp_set_user_status`, `SessionService` | `AdminSecurityIT` |
| BR-06 — role and scope | `trg_categories_before_*`, `sp_require_admin` | `AdminCategoryApiIT`, `AdminSecurityIT` |
| UC-22 B5 — audit every administrative write | the eight procedures' own `admin_audit_log` inserts | `AdminCategoryApiIT`, `AdminUserApiIT`, `AdminSettingsApiIT` |
| UC-05 E1 — students are refused | `SecurityConfig` role rule | `AdminSecurityIT` (parameterised over all 16) |
| OB-005 — no unaudited administrative write | every write is a `CALL` | `AdminSecurityIT`, and the design in `AdminAnnouncementService` |
| OB-013 — the plaintext aggregates | 60–61 serve them; no per-student amount anywhere | `AdminStatsApiIT` |

See [MODULE_11_ADMINISTRATION.md](../modules/MODULE_11_ADMINISTRATION.md) for the module's own report,
including the two production defects the suite found and how the read-back identity works.

---

## Related documentation

- [FRONTEND_API_GUIDE.md](FRONTEND_API_GUIDE.md) — **start here.** The single entry point for the
  frontend: base URL, interceptors, the shared error contract, the enum reference and the master
  table of all 61 operations
- [API_INVENTORY.md](API_INVENTORY.md) — the full endpoint inventory, including why nothing here
  duplicates an existing route.
- [authentication.md](authentication.md) — tokens, sessions and UC-05.
- [categories.md](categories.md) — a student's personal categories, the shared defaults' other reader.
- [tips.md](tips.md) — the saving-tip engine the templates here configure.
- [../modules/MODULE_11_ADMINISTRATION.md](../modules/MODULE_11_ADMINISTRATION.md) — the module report.
- [../testing/manual/MODULE_11_MANUAL_TEST.md](../testing/manual/MODULE_11_MANUAL_TEST.md) — the manual
  test procedure.
- [../SECURITY.md](../SECURITY.md) — the security model this document's §9 summarises.
- [../OVERNIGHT_BLOCKERS.md](../OVERNIGHT_BLOCKERS.md) — OB-005 (unaudited writes) and OB-013
  (plaintext amounts), both referenced above.
