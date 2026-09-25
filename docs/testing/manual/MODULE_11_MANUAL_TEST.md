# Module 11 — Administration (UC-20 … UC-23) — Manual Test Procedure

**Scope:** endpoints 46–61 — the sixteen administration operations: accounts
(`GET /api/v1/admin/users`, `POST /api/v1/admin/users/{id}/status`,
`POST /api/v1/admin/users/{id}/password-reset`), default categories
(`GET`/`POST /api/v1/admin/categories`, `PATCH /api/v1/admin/categories/{id}`), announcements
(`GET`/`POST /api/v1/admin/announcements`, `PATCH /api/v1/admin/announcements/{id}`), tip templates
(`GET`/`POST /api/v1/admin/tip-templates`, `PATCH /api/v1/admin/tip-templates/{id}`), settings
(`GET /api/v1/admin/settings`, `PATCH /api/v1/admin/settings/{key}`) and statistics
(`GET /api/v1/admin/stats`, `GET /api/v1/admin/stats/top-categories`). All role `ADMIN` only.

**Sources of truth for this procedure:** [`docs/api/administration.md`](../../api/administration.md)
(the request and response contract — §3 the field reference, §7 the six adjustable keys, §9 the
security properties, §10 the client notes), [`docs/modules/MODULE_11_ADMINISTRATION.md`](../../modules/MODULE_11_ADMINISTRATION.md)
(§3 what the database owns, §5 the two production defects found and fixed, §6 which suite drives which
behaviour), [`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 46–61),
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md) (seeded accounts and the permission table),
[`docs/SECURITY.md`](../../SECURITY.md) (the security model §4 and §5 summarise), `db/01_schema.sql`
(`admin_audit_log`, `password_reset_tokens`, `announcements`, `tip_templates`, `system_settings`,
`categories`), `db/02_views.sql` (`v_admin_usage_stats`, `v_admin_top_categories`),
`db/03_procedures.sql` (the eight `sp_admin_*` procedures and `sp_require_admin`), and the
implementation under `backend/src/main/java/com/campuscoin/admin/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 46 | `GET` | `/api/v1/admin/users` | `200` every account |
| 47 | `POST` | `/api/v1/admin/users/{id}/status` | `200` the account, in its new state |
| 48 | `POST` | `/api/v1/admin/users/{id}/password-reset` | `202` a message, never a token |
| 49 | `GET` | `/api/v1/admin/categories` | `200` the shared default categories |
| 50 | `POST` | `/api/v1/admin/categories` | `201` the category |
| 51 | `PATCH` | `/api/v1/admin/categories/{id}` | `200` the category |
| 52 | `GET` | `/api/v1/admin/announcements` | `200` every announcement |
| 53 | `POST` | `/api/v1/admin/announcements` | `201` the announcement |
| 54 | `PATCH` | `/api/v1/admin/announcements/{id}` | `200` the announcement, in its new state |
| 55 | `GET` | `/api/v1/admin/tip-templates` | `200` the templates |
| 56 | `POST` | `/api/v1/admin/tip-templates` | `201` the template |
| 57 | `PATCH` | `/api/v1/admin/tip-templates/{id}` | `200` the template |
| 58 | `GET` | `/api/v1/admin/settings` | `200` every setting, each marked adjustable or not |
| 59 | `PATCH` | `/api/v1/admin/settings/{key}` | `200` the setting, with its new value |
| 60 | `GET` | `/api/v1/admin/stats` | `200` one aggregate row |
| 61 | `GET` | `/api/v1/admin/stats/top-categories` | `200` the ranked categories |

**Sixteen endpoints, and the property they are built around is that the database was already complete
for this module.** All eight administrative procedures exist, each calling `sp_require_admin` as its
single authorisation gate and writing its own `admin_audit_log` row; the four admin views exist. This
module adds **no table, view, procedure, trigger or column** — the work is the endpoint set, the
refusal classification, and the "what must never leave the server" boundary. §3.2 is the part of this
most likely to be misread.

**There is no seventeenth endpoint.** There is **no** `PUT` anywhere, **no** `DELETE` on users,
categories, announcements or tip templates, **no** `GET /api/v1/admin/users/{id}`, **no**
`/api/v1/admin/audit-log`, **no** `POST .../{id}/activate` or `.../{id}/deactivate`, and **no**
`/api/v1/admin/insights/**`, `/api/v1/admin/anomalies/**` or `/api/v1/admin/ai/**`. An endpoint that
is not in the table above does not exist; if a step here asks you to call one, the document is wrong,
not the server.

**Nothing takes a caller's id.** Every route reads the actor from the verified token, so there is no
parameter anywhere that could claim to be another administrator.

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 47, 48 | `id` (path) | the id of an existing **account** | — |
| 51, 54, 57 | `id` (path) | the id of the default category / announcement / tip template | — |
| 59 | `key` (path) | one of the six adjustable keys; see §3.6 | — |

There are **no query parameters on any route**. No search, no filter, no pagination — UC-20…UC-23
define none, and a parameter invented without a requirement is a duplicate capability waiting to
happen. §3.5 lists what that means for a client.

Every request body is a closed record, so there is no mass-assignment surface: 47 takes `{status}`,
48 takes nothing, 50 and 51 take the category fields, 53 and 54 take the announcement fields, 56 and
57 take the template fields, and 59 takes `{value}`.

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema, seed data and demo data applied (`db/01_schema.sql` …
  `db/06_demo.sql`), and started with `--default-time-zone=+07:00` as the project's Docker stack does.
- **The reset link is delivered to a file, not to email.** This project has no mail provider, and the
  `dev` profile sets `campuscoin.security.password-reset.sink-enabled: true`, which substitutes a
  file sink for the mail client. The file defaults to **`backend/target/password-reset-dev.log`** and
  each line reads `{instant} | {email} | {link}`. That file is where you read the token for M11-03 and
  M11-13. It is inside the build directory and gitignored, so a generated token cannot be committed.
  **Do not look for the token in the response or the application log** — it is in neither, on purpose.
- **M11-07 reads `announcements` and M11-09 reads `tip_templates` and `system_settings` directly with
  `mysql`, only to read.** Every other case is HTTP only.
- **The seeded data is English and the counts in §3.1 are exact for a freshly seeded database.** If
  you have already run another module's manual procedure, the counts will differ by whatever it wrote;
  the cases below are written so that only the *shape* they assert depends on the seed, except where a
  case says otherwise.

### 2.2 Getting the four tokens — the only placeholders this document uses

A token is obtained by calling the sign-in endpoint and **copying the `accessToken` value out of the
response**. Passwords and tokens are never written into this document, never pasted into a source
file, a committed config file, or a bug report. The two supported places to put one are the
**Authorize** dialog in Swagger UI and a shell environment variable local to your session.

Sign in with the seeded accounts from `docs/CREDENTIALS.md`:

```
POST /api/v1/auth/login          (students — body: {"email": "...", "password": "..."})
POST /api/v1/admin/auth/login    (administrator)
```

The response contains `accessToken` (copy that value), `tokenType` (`"Bearer"`), `expiresIn`
(seconds — 7200 by default, so a long session may need a fresh sign-in) and a `user` object.

| Placeholder | What it is | Seeded account used here |
|---|---|---|
| `${ADMIN_JWT}` | An `ADMIN` access token, from the administrator sign-in endpoint. **Every route in this procedure needs this one.** | System Administrator — `admin@campuscoin.edu` |
| `${USER_A_JWT}` | A `STUDENT` token. The subject of the disable/re-enable cases and the refusal sweeps. | Alex Nguyen — `an.nguyen@student.campuscoin.edu` |
| `${USER_B_JWT}` | The `STUDENT` token of a **second** account, so a case can act on someone who is not `${USER_A_JWT}`. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${JWT}` | A generic student token, used where the case does not care which student. Same account as `${USER_A_JWT}`. | Alex Nguyen |

There are no other placeholders in this document. Where a numeric id appears (a user `id`, a category
`id`, an announcement `id`), it is an **example**; always use the id you recorded in your own run.

```bash
# Example: obtain an administrator token, then use it without ever writing it down.
# Read the password for the seeded administrator from docs/CREDENTIALS.md and type it at the
# prompt; it is not repeated in this document.
read -rs CC_PW
curl -s -X POST http://localhost:8080/api/v1/admin/auth/login \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e 'admin@campuscoin.edu' --arg p "$CC_PW" \
        '{email:$e,password:$p}')" \
  | jq -r .accessToken
unset CC_PW
# copy that output into a local variable, e.g.:
# export ADMIN_JWT='<the value you copied>'      # not committed, not written to a file
```

In Swagger UI: click **Authorize**, paste the token, and it is sent as
`Authorization: Bearer <accessToken>` on every request until you sign out.

### 2.3 Dates

- "Now" means the current moment in **`Asia/Ho_Chi_Minh` (`+07:00`)** — check it with
  `TZ=Asia/Ho_Chi_Minh date +%FT%T`. The database session is pinned to the same offset, and the
  application's default zone is the same, so an announcement's `startsAt` is comparable to it.
- **Times are stored to the second, and that matters twice.** `starts_at` is `DATETIME`, which MySQL
  stores with no fractional part, and the service truncates both announcement times to the second
  before it does anything else. That is why a notice created without a `startsAt` is still readable
  back (§5.1) and why a window whose two ends differ only below a second is treated as one instant.
- Announcements carry a window (`startsAt`, `endsAt`) which you supply in M11-06 and M11-07. Use a
  date in the past or `TZ=Asia/Ho_Chi_Minh date -v+1d +%FT%T` for a future one; the exact value does
  not matter, only its relation to the other end and to "now".

---

## 3. Preconditions

### 3.1 Accounts and data

- **System Administrator** (`admin@campuscoin.edu`) is the seeded administrator, `role = 'ADMIN'`,
  `status = 'ACTIVE'`. It is the only administrator the seed creates, and it is the account every
  case here signs in as.
- **Alex Nguyen** and **Bella Tran** are the two seeded students. They are the targets of the
  account-management cases; both are `ACTIVE` and neither is an administrator.
- **A freshly seeded database holds exactly these rows in the module's tables**, and §3.3's counts
  depend on them:

  | Table | Rows | Which |
  |---|---|---|
  | `categories` | **12** | all `user_id IS NULL`: 5 `INCOME` and 7 `EXPENSE` |
  | `announcements` | **2** | both `STUDENTS`, both active, both with an end 60 and 90 days out |
  | `tip_templates` | **7** | `GENERIC`, `LOW_SAVINGS_RATE`, `CATEGORY_SPIKE`, `OVER_BUDGET`, `NEAR_BUDGET`, `NO_BUDGET_SET`, `SAVINGS_GOAL_AT_RISK` |
  | `system_settings` | **16** | of which **6** are adjustable through 59 (§3.6) |
  | `admin_audit_log` | **0** | the seed writes none; only the eight procedures do |
  | `insights` | **3** | `db/06_demo.sql` calls `sp_generate_monthly_insight` for three months — see §5.4 |

- **A newly registered account is a `STUDENT`.** There is no API that creates an administrator —
  `AuthService` fixes the role, which is UC-01's rule — so if you need a second administrator for a
  case, M11-13 says how, and it is a database write rather than a route.

### 3.2 Authentication, and the one thing to read before §4

All sixteen endpoints require `Authorization: Bearer <accessToken>` **and** an account whose
**current** role is `ADMIN`. A student token is refused with `403` (M11-12). A missing, malformed,
expired or revoked token answers `401`. The whole authorisation statement is one line of
`SecurityConfig`:

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

> ### 3.2.1 Every write goes through a stored procedure, and that is the module's central invariant
>
> The application's database account holds direct `INSERT`/`UPDATE` grants on every table this module
> touches. A `save` would therefore **succeed** while bypassing `sp_require_admin` and leaving **no
> audit row** — an unauthorised, unrecorded administrative write. That is why every write here is a
> `CALL`, and it is what OB-005 records. A tester cannot see this from the outside; M11-13 is what
> makes it observable, by checking that an audit row exists for each act.

> ### 3.2.2 Authority comes from the live `users` row, not from the token's role claim
>
> The JWT carries a `role` claim, and it is **never read**. The session service loads the account on
> every request and takes the role from the row, in the same way it takes `status` and
> `token_version`. The visible consequence is that a **promotion or demotion takes effect on a token
> already in circulation** — there is no re-sign-in. That is deliberate and is pinned by a test. **Do
> not report "a student's old token reached an admin route after promotion" as a defect** — M11-13
> step 5 is where you see it, and §5.3 explains why it is the safe direction.

### 3.3 What a response looks like — the counts are the interesting part

`GET /api/v1/admin/stats` with `${ADMIN_JWT}`:

```json
{
  "totalStudents": 2,
  "activeStudents": 2,
  "disabledStudents": 0,
  "activeUsers30d": 0,
  "totalTransactions": 31,
  "totalExpenseLogged": 0.00,
  "totalIncomeLogged": 0.00,
  "totalBudgets": 0,
  "totalTipsGenerated": 13,
  "totalInsightsGenerated": 3
}
```

**Five things about the shape, each pinned by a case below:**

- **Ten aggregates and no identifier of any kind** — M11-10.
- **`totalInsightsGenerated` is `3`, not `0`, and this is correct.** `db/06_demo.sql` calls
  `sp_generate_monthly_insight` for three demo months, so the table holds three rows and the figure is
  the table's own count. UC-17 is still not enabled — **nothing in the Java writes that table** — so
  the figure does not move when you act (§5.4, M11-10).
- **The money figures are `SUM`s over many students, never one student's amounts.** `totalExpenseLogged`
  and `totalIncomeLogged` are plaintext aggregates, which is the exposure recorded as OB-013 — a
  deferred decision this module serves and does not change (§5.2).
- **`activeUsers30d` is a session count, not a sign-in count.** It goes up when an account has a
  session row seen in the last 30 days, so signing in as a student makes it `1` (M11-10).
- **Every count is derivable from a table**, which is what M11-10 checks — the figure is asserted
  equal to its own `SELECT COUNT(*)`, not to a literal.

**`GET /api/v1/admin/stats/top-categories` answers a bare JSON array**, not a wrapper object:

```json
[
  { "categoryId": 10, "categoryName": "Entertainment", "type": "EXPENSE", "scope": "PERSONAL",
    "txnCount": 6, "totalAmount": 214.50, "distinctUsers": 1 },
  { "categoryId": 6,  "categoryName": "Food",          "type": "EXPENSE", "scope": "DEFAULT",
    "txnCount": 11, "totalAmount": 0.00,  "distinctUsers": 1 }
]
```

**Four more things, all pinned below:**

- **Every category appears, including ones no transaction has ever used** — with `txnCount` `0` and
  `totalAmount` `0.00`. There is **no `LIMIT`**: the view's definition is every category, and the
  ranking includes a student's own (`scope: "PERSONAL"`) beside the shared ones (`scope: "DEFAULT"`)
  — M11-10.
- **The order is the contract**: `txnCount` descending, then `totalAmount` descending, then
  `categoryId` ascending. The view itself declares no `ORDER BY`, so the DAO supplies a **total**
  order. Without the final tie-break two categories with equal counts and totals could swap between
  two identical calls and a client diffing them would see movement that never happened — M11-11.
- **`totalAmount` can be `0.00` on a category that has transactions**, because the demo data's amounts
  are what they are and the ranking is over all students. Do not read a zero as "no data".
- **`scope` and `type` are different questions.** `type` is `INCOME` or `EXPENSE`; `scope` is
  `DEFAULT` or `PERSONAL` — whether the category is shared or belongs to one student.

### 3.4 The response field sets, which the cases assert exactly

**`AdminUserResponse`** — eight fields: `id`, `email`, `fullName`, `role`, `status`, `academicYear`,
`lastLoginAt`, `createdAt`.

> **`academicYear` and `lastLoginAt` are absent, not `null`, on an account that has never set a year
> or signed in.** Both are `@JsonInclude(NON_NULL)`. A freshly registered account therefore answers
> with **six** fields, and both appear once the account has set a year (via
> `PATCH /api/v1/profile/me`) and has signed in (which writes `last_login_at`). That is the contract,
> not a gap — M11-01 is the case that checks all three states.

**`AnnouncementResponse`** — nine fields: `id`, `title`, `body`, `severity`, `audience`, `startsAt`,
`endsAt`, `isActive`, `createdAt`. `endsAt` is **absent** on an open-ended notice.

> **There is no author field.** Who published a notice is in `admin_audit_log`, which is the record of
> who did what; a second, weaker answer to the same question would be a second thing to keep in step.
> Do not report the missing author as a gap — M11-06 asserts it is absent.

**`TipTemplateResponse`** — seven fields: `id`, `code`, `conditionType`, `titleTemplate`,
`bodyTemplate`, `defaultPriority`, `isActive`. `conditionParams` is **not published**: the column is a
JSON blob whose meaning the schema does not document and nothing in this build reads.

**`SystemSettingResponse`** — five fields: `key`, `value`, `valueType`, `description`, `adjustable`.
`value` is always a **string** — `app.currency` is `USD` and `app.timezone` is a name, so the column's
contents are returned as stored and `valueType` says how to read them.

**`CategoryResponse`** — the shared default categories reuse the student module's response: `id`,
`name`, `type`, `icon`, `color`, `isDefault`, `isActive`, `sortOrder`, `description`. `icon`, `color`
and `description` are omitted when unset, and `isDefault` is `true` on every row these routes return.

**`AdminTopCategoryResponse`** — seven fields: `categoryId`, `categoryName`, `type`, `scope`,
`txnCount`, `totalAmount`, `distinctUsers`.

Every one of these sets is asserted as an exact literal by the automated suite, so a field added later
fails a test rather than arriving unannounced.

### 3.5 What no response may ever contain

| Must not appear | Why |
|---|---|
| `passwordHash`, `password_hash`, `password` | BR-01. The projection never selects the column |
| `tokenVersion`, `token_version` | It is the entire security meaning of a JWT's `tv` claim. Publishing it would let an attacker decide whether a stolen token is still live |
| `monthlyAllowanceBaseline`, `monthlySavingsGoal` | VĐ-04. An administrator manages accounts, not a student's finances |
| `conditionParams` | Not documented and not read by anything |
| `themePref`, `fontScale`, `aiEnabled`, `emailVerifiedAt`, `updatedAt` | No UC-22 screen has them |
| the raw password-reset token | It exists only in the message to the account's address |

These are absent **structurally** — the response records have no component for them — so no filter can
be forgotten. M11-11 scans every read endpoint's raw JSON for the whole list, at every depth.

### 3.6 The six adjustable settings, and the ten that are not

`GET /api/v1/admin/settings` lists all sixteen rows; exactly six answer `adjustable: true`:

| Key | Value shape |
|---|---|
| `budget.near_threshold_pct` | positive number |
| `budget.exceeded_threshold_pct` | positive number |
| `insight.spike_threshold_pct` | positive number |
| `insight.spike_baseline_months` | **whole number, 1 to 12** |
| `tips.max_dashboard` | positive number |
| `auth.reset_token_ttl_minutes` | positive number |

The other ten — `app.currency`, `app.currency_symbol`, `app.timezone`, `app.week_start`,
`auth.session_ttl_minutes`, `auth.max_login_attempts`, `anomaly.duplicate_window_days`,
`anomaly.unusual_multiplier`, `ai.enabled`, `ai.send_aggregates_only` — answer `adjustable: false` and
are refused with `409 THRESHOLD_NOT_ADJUSTABLE`. They are set for the deployment, read by Java rather
than by the procedure, or belong to a capability this build does not have. M11-09 checks both halves.

> **The two `insight.*` keys are adjustable, and that is worth knowing before you report it.** The
> name suggests module 12, which is locked. It is not: `sp_generate_tips` reads
> `insight.spike_threshold_pct` to decide BR-15's category-spike tip, and `v_category_spend_trend`
> joins both keys. That is **UC-25 / BR-15 spike detection, shipped in module 9** — not UC-17's monthly
> `insights` table, which is what module 12 actually holds. Refusing them would leave a shipped
> behaviour permanently untunable, against VĐ-05. Module 12's own surfaces have no settings key here.

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

> **Two cases change shared state, and both restore it.** M11-03 disables a student and M11-09 changes
> a threshold. Each case re-enables the account or puts the old value back in its own last step, so a
> later case is not affected by an earlier one. If you stop part-way through, restore both before
> continuing.

---

### M11-01 — The account list, its exact fields, and the two that are absent until set

**Covers:** UC-22 B1; `AdminUserResponse`'s field set; the `NON_NULL` omission; BR-01.

**Preconditions:** Signed in as `${ADMIN_JWT}`. A freshly registered student account: register one
with `POST /api/v1/auth/register` and note its email — call it `<NEW_EMAIL>`.

**Steps:**

1. `GET /api/v1/admin/users` with `${ADMIN_JWT}`. Record the count and the field names of the first
   row.
2. Find `<NEW_EMAIL>`'s row in the same response. Record **its** field names.
3. Sign in as the new account (`POST /api/v1/auth/login`) and copy its token.
4. `PATCH /api/v1/profile/me` with that token and body `{"academicYear": "Year 3"}`.
5. `GET /api/v1/admin/users` again and record `<NEW_EMAIL>`'s row.
6. Compare the raw JSON against the `AdminUserResponse` schema at `/api-docs`.

**Expected result:**

- Step 1: `200`, and **every account on the system is present** — students *and* administrators. The
  seeded administrator and the two seeded students are all in it. The list is ordered by `id`
  ascending, so two identical calls agree.
- Step 1: a row carries **exactly** `id`, `email`, `fullName`, `role`, `status`, `createdAt` and, when
  set, `academicYear` and `lastLoginAt`. Nothing else.
- Step 2: **six fields, not eight.** `<NEW_EMAIL>` has never set a year and has never signed in, so
  `academicYear` and `lastLoginAt` are **absent from the JSON** — not `null`, not empty strings.
  **Do not report the two missing fields as a defect** — §3.4 is the contract.
- Step 5: **both fields are now present.** `academicYear` reads `"Year 3"` (what step 4 set) and
  `lastLoginAt` is a datetime (step 3's sign-in wrote it). This is the case's real point: the omission
  is about the data, not about the field being unsupported.
- No row anywhere contains `passwordHash`, `tokenVersion`, `monthlyAllowanceBaseline`,
  `monthlySavingsGoal` or `conditionParams`, at any depth (§3.5).
- **`email` is published, and that is intended here.** The administrator screens list and act by it.
  It is not published on any student-facing route.

**Result:** [ ] Pass   [ ] Fail

---

### M11-02 — Disabling an account is immediate, and re-enabling restores it

**Covers:** UC-22 B3; BR-03; `sp_set_user_status`'s session revocation and `token_version` bump.

**Preconditions:** `${ADMIN_JWT}`, and `${USER_A_JWT}` — Alex Nguyen's **live token**, obtained in this
run, not copied from an earlier one. Note his `id` from M11-01 — call it `<A_ID>`.

**Steps:**

1. As Alex, `GET /api/v1/profile/me` with `${USER_A_JWT}` — confirm it works.
2. Record his `status` and `tokenVersion`… note that `tokenVersion` is not published, so read it from
   the database instead (this is a read-only step):

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, status, token_version FROM users WHERE id = <A_ID>;
      SELECT id, revoked_at, revoked_reason FROM user_sessions WHERE user_id = <A_ID> ORDER BY id;"
   ```

3. `POST /api/v1/admin/users/<A_ID>/status` with `${ADMIN_JWT}` and `{"status": "DISABLED"}`.
4. Immediately reuse `${USER_A_JWT}` on `GET /api/v1/profile/me`.
5. Re-run the step-2 query.
6. `POST /api/v1/admin/users/<A_ID>/status` with `{"status": "ACTIVE"}`.
7. `POST /api/v1/auth/login` as Alex again, and confirm he can sign in. Do **not** try the old token —
   see the note below.
8. Read the audit rows:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, admin_user_id, action, target_entity, target_id, detail, ip_address
        FROM admin_audit_log WHERE target_id = <A_ID> ORDER BY id;"
   ```

**Expected result:**

- Step 2: `status = 'ACTIVE'`, some `token_version` — call it `<TV_BEFORE>`. His sessions are live
  (`revoked_at` null).
- Step 3: **`200`**, and the body is **the account in its new state** — `status` reads `"DISABLED"`;
  the other seven fields are as in M11-01. Not a `204` and not an empty body.
- Step 4: **`401`** with `errorCode: "UNAUTHENTICATED"` — the token he was holding and using one
  request ago is now refused. There are **three independent reasons** and the response does not say
  which: the session row is revoked, the token's `tv` claim no longer matches `token_version`, and
  the account's status is no longer `ACTIVE`. Each alone would be enough; all three together is what
  makes "disable means disable" hold even if one is ever changed.
- Step 5: `status = 'DISABLED'`, `token_version = <TV_BEFORE> + 1` (a **delta of exactly one**), and
  **every session he had open now has `revoked_at` set with `revoked_reason = 'ADMIN_DISABLE'`.**
- Step 6: `200`, and the body reads `"ACTIVE"` again.
- Step 7: **he can sign in.** A fresh sign-in issues a new token with the new `tv`. **Do not try
  step 3's old token here** — re-enabling does *not* resurrect the token revoked in step 3; that token
  is gone for good, which is the point of revoking the session. Report "the old token still fails after
  re-enabling" as **correct**.
- Step 8: **two rows**, `USER_DISABLED` then `USER_ENABLED`. Each names the administrator's own id in
  `admin_user_id`, `target_entity = 'users'`, `target_id = <A_ID>`, and a `detail` that records the
  before and after status. **Neither row was written by the API** — both come from the procedure, which
  is the only way they can exist (§3.2.1).
- **Re-sending the same status is accepted, not refused.** `POST` `{"status": "ACTIVE"}` on an account
  already `ACTIVE` answers `200` and leaves a truthful audit row. The caller asked for the row to be
  in that state and it is; an error the client has to special-case would be worse.

**Result:** [ ] Pass   [ ] Fail

---

### M11-03 — An administrator cannot disable their own account, but may re-enable it

**Covers:** UC-22 A1; the one-sided guard; the Java pre-check.

**Preconditions:** `${ADMIN_JWT}`. Note the seeded administrator's own `id` — call it `<ADMIN_ID>`.

**Steps:**

1. `POST /api/v1/admin/users/<ADMIN_ID>/status` with `${ADMIN_JWT}` and `{"status": "DISABLED"}`.
2. Record the status, `errorCode` and message. **Read the message carefully for anything from the
   database.**
3. Confirm you can still use `${ADMIN_JWT}`: `GET /api/v1/admin/users`.
4. Read the administrator's own row and session, and the audit log:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, status, token_version FROM users WHERE id = <ADMIN_ID>;
      SELECT COUNT(*) AS disable_rows FROM admin_audit_log
        WHERE action = 'USER_DISABLED' AND target_id = <ADMIN_ID>;"
   ```

5. `POST /api/v1/admin/users/<ADMIN_ID>/status` with `${ADMIN_JWT}` and `{"status": "ACTIVE"}`.
6. Confirm `${ADMIN_JWT}` still works and read the audit log for the new row.

**Expected result:**

- Step 2: **`409 Conflict`** with `errorCode: "SELF_DISABLE_FORBIDDEN"`. The message says an
  administrator cannot disable their own account. **It contains no procedure name, no SQLSTATE, no
  `sp_`, and no BR- code** — the refusal is the application's, not the driver's text forwarded.
- Step 3: `200`. Nothing happened: the caller is still `ACTIVE`, `token_version` is **unchanged**, no
  session was revoked, and step 4 shows **zero** `USER_DISABLED` rows naming this account.
- **The refusal is answered before the database is called.** `sp_set_user_status` signals SQLSTATE
  45000 for all three of its refusals — the account does not exist, the status is invalid, and this one
  — with no way to tell them apart except by matching the procedure's prose, which this project
  forbids. With this one answered in Java, a surviving 45000 can only mean "the target does not
  exist". The procedure's own check remains the guarantee for a hand-run `CALL`.
- Step 5: **`200`** — the guard is **one-sided on purpose**. An administrator *may* re-enable their own
  account; `ACTIVE` on an already-active account is a permitted no-op. The danger is locking yourself
  out, not letting yourself back in. **Do not report this as an inconsistency** — §5.1 says so again.
- Step 6: the audit log gains a `USER_ENABLED` row naming the administrator and themselves.
- **Do not read step 5 as proof that self-disable was attempted and silently allowed.** It is a
  different request with a different answer, and step 4 shows the first one left nothing behind.

**Result:** [ ] Pass   [ ] Fail

---

### M11-04 — A reset link is sent, and the raw token is in exactly one place

**Covers:** UC-22 B4; BR-04; the notifier port; the response's shape.

**Preconditions:** `${ADMIN_JWT}`, `${USER_A_JWT}`. Note Alex's `id` — `<A_ID>`. The reset sink file is
at `backend/target/password-reset-dev.log` (§2.1); note its current line count first.

**Steps:**

1. `wc -l backend/target/password-reset-dev.log` — record the count.
2. As Alex, request a reset the **public** way: `POST /api/v1/auth/password-reset/request` with
   `{"email": "<Alex's email>"}`. Record the status and body.
3. As the administrator, `POST /api/v1/admin/users/<A_ID>/password-reset` with `${ADMIN_JWT}`.
   **Record the whole response.**
4. `wc -l` the sink file again, and read the last two lines.
5. Check the database for what was stored:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, user_id, LENGTH(token_hash) AS hash_len, used_at, expires_at
        FROM password_reset_tokens WHERE user_id = <A_ID> ORDER BY id DESC LIMIT 2;"
   ```

6. `POST /api/v1/admin/users/<a large id that does not exist>/password-reset` with `${ADMIN_JWT}` —
   use `SELECT MAX(id) + 1 FROM users` for the value.
7. Search the sink file and the application log for a line containing the **raw** token from step 4's
   last link.

**Expected result:**

- Step 2: `200` with a generic message, **the same for any address** (BR-04 anti-enumeration), and the
  database gains a token row. Note this route is **public and addressed by email**.
- Step 3: **`202 Accepted`**, and the body is **`{"message": "..."}` and nothing else** — a
  `fieldNamesOf` listing shows exactly one key. **The raw link is not in the response**, and neither
  is any token-shaped value.
- Step 4: the sink gained a line, `{instant} | {email} | {link}`. The link points at the Angular reset
  screen with `?token=<raw>`. **This file is the only place the raw value exists** outside the message
  it stands in for. It lives under `target/`, which is gitignored.
- Step 5: **`hash_len` is 64** — SHA-256 hex, not the token itself. The two newest rows are step 2's
  and step 3's, and each carries an expiry (`expires_at`) and `used_at` null. **The two flows share
  the procedure** that issues the row, so their TTL and one-time-use rules are the same.
- **The two routes are not duplicates.** The public one answers identically whether the address exists;
  this one answers `202` for a real account and `404` for an unknown id, because an administrator is
  entitled to know. Two use cases, two disclosure policies, one table.
- Step 6: **`404 NOT_FOUND`**, and the database gains **no** token row and **no** audit row. The
  service loads the account first (it needs the address for the link anyway); the foreign key is only a
  fallback.
- Step 7: **the raw token appears in the sink file and nowhere else** — not in the response, not in the
  application log, at any log level. That is the design (§2.1), not an oversight.
- **Do not report "the response does not contain the link" as a gap.** An administrator sends the
  link; they do not receive it.

**Result:** [ ] Pass   [ ] Fail

---

### M11-05 — Default categories: create, rename, retire, and the student list is untouched

**Covers:** UC-20; BR-06; BR-07; `uk_categories_scope_type_name`.

**Preconditions:** `${ADMIN_JWT}`, `${USER_A_JWT}`. A freshly seeded database has the 12 defaults (§3.1).

**Steps:**

1. `GET /api/v1/admin/categories` with `${ADMIN_JWT}`. Record the count and the field names of one row.
2. `GET /api/v1/categories` with `${USER_A_JWT}` — the **student's** list. Record its count.
3. Create one: `POST /api/v1/admin/categories` with `${ADMIN_JWT}` and
   `{"name": "Campus Cafe", "type": "EXPENSE", "color": "#F59E0B", "sortOrder": 20}`.
   **Record the whole response and its `id`** — call it `<CAT_ID>`.
4. `GET` the admin list again; is the new row there, and where?
5. `POST /api/v1/admin/categories` again with the **same name and type**.
6. `POST /api/v1/admin/categories` with the same name and type **`INCOME`**.
7. `PATCH /api/v1/admin/categories/<CAT_ID>` with `{"name": "Campus Coffee"}`.
8. `PATCH /api/v1/admin/categories/<CAT_ID>` with `{"isActive": false}`.
9. `GET /api/v1/admin/categories` and `GET /api/v1/categories` (as Alex) again.
10. `PATCH /api/v1/admin/categories/<a student's personal category id>` — take one from
    `GET /api/v1/categories` with a student token — with `{"isActive": false}`.

**Expected result:**

- Step 1: `200`, `12` rows, all `isDefault: true`. Fields are the nine of §3.4.
- Step 2: **`12` rows or more** — the student list merges the shared defaults with Alex's own
  categories, so it is the same defaults plus whatever he has. The two routes are different questions
  and this is why 49 exists at all.
- Step 3: **`201`, and the response is the row that was written** — `name` is `"Campus Cafe"`, `type`
  is `"EXPENSE"`, `isDefault` is `true`, `isActive` is `true`. The `id` is a new one, **not the id of
  any other row** and not an audit-log id. (The service reads the row back by its natural identity,
  `(type, name)` among the default rows, because `LAST_INSERT_ID()` after these procedures reports the
  *audit* row — §5.5.)
- Step 4: `13` rows, and the new one is ordered by `sortOrder` within its type. The list order is
  `type` ascending, then `sortOrder`, then `id` — the same sequence a student's picker uses.
- Step 5: **`409 Conflict`** with `errorCode: "CATEGORY_NAME_TAKEN"` — the same code the student
  module gives, because the remedy is the same: choose another name. There is no second row.
- Step 6: **`201`** — the uniqueness key is **per type**, so `Campus Cafe` may exist as both an expense
  and an income category. This is the case's point: the key is `(user_id IS NULL, type, name)`.
- Step 7: `200`, `name` reads `"Campus Coffee"` and everything else is unchanged.
- Step 8: `200`, `isActive` reads `false`. **Retirement is `PATCH {isActive: false}` — there is no
  `DELETE`** (BR-07), the same answer the student module gives for a personal category.
- Step 9: **the retirement shows up in both lists.** The admin list still shows the row (retired rows
  are listed on purpose — a retired row is exactly the one an administrator needs to find to bring it
  back). The **student** list, and the picker a student sees, no longer offers it. **Do not report the
  retired row's absence from the student list as a defect** — that is what retiring means.
- Step 10: **`404 NOT_FOUND`.** A student's own category is not reachable through the admin routes —
  they query `user_id IS NULL` and a personal row is not one. **Do not report this as "the admin
  cannot edit categories"** — a personal category is a different resource, and UC-20 is about the
  shared defaults.

**Result:** [ ] Pass   [ ] Fail

---

### M11-06 — Announcements: publish, retract, and the admin list sees what a dashboard hides

**Covers:** UC-21 B1–B2; `ck_ann_window`; the create-once decision; the table read.

**Preconditions:** `${ADMIN_JWT}`, `${USER_A_JWT}`. A freshly seeded database has 2 announcements (§3.1).

**Steps:**

1. `GET /api/v1/admin/announcements` with `${ADMIN_JWT}`. Record the count and one row's field names.
2. `GET /api/v1/dashboard` with `${USER_A_JWT}` and look at the announcements it returns.
3. `POST /api/v1/admin/announcements` with `${ADMIN_JWT}` and:
   `{"title": "Library closed Sunday", "body": "The library is closed this Sunday for maintenance.", "severity": "WARNING", "audience": "STUDENTS"}`.
   **Record the response and the `id`** — call it `<ANN_ID>`.
4. `GET /api/v1/admin/announcements` again, and re-read the dashboard as Alex.
5. `POST /api/v1/admin/announcements` with `{"title": "Internal", "body": "Staff only.", "audience": "ADMINS"}`.
6. Re-read the admin list and the dashboard.
7. `PATCH /api/v1/admin/announcements/<ANN_ID>` with `{"isActive": false}`.
8. Re-read both lists.
9. `PATCH /api/v1/admin/announcements/<ANN_ID>` with `{"isActive": true}`.
10. `POST /api/v1/admin/announcements` with `{"title": "Bad window", "body": "x", "startsAt": "2026-01-02T10:00:00", "endsAt": "2026-01-02T09:00:00"}`.
11. `POST /api/v1/admin/announcements` with an equal pair:
    `{"title": "One instant", "body": "x", "startsAt": "2026-01-02T10:00:00", "endsAt": "2026-01-02T10:00:00"}`.
12. `PATCH /api/v1/admin/announcements/<ANN_ID>` with `{"title": "Edited", "isActive": true}`.
13. Read the audit log:

    ```bash
    mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
      "SELECT id, action, target_entity, target_id, detail FROM admin_audit_log
         WHERE target_entity = 'announcements' ORDER BY id;"
    ```

**Expected result:**

- Step 1: `200`, `2` rows, newest first. Fields are the nine of §3.4 — `id`, `title`, `body`,
  `severity`, `audience`, `startsAt`, `endsAt`, `isActive`, `createdAt`. **No author field.**
- Step 2: the dashboard shows the notices addressed to students and **within their window**. It filters
  `audience IN ('ALL','STUDENTS')` and applies the time window.
- Step 3: **`201`**, the response is the row that was written, `audience` reads `"STUDENTS"`,
  `severity` reads `"WARNING"`, `isActive` is `true`, and `startsAt` is **now** (the documented
  default when you omit it). `endsAt` is **absent** — you sent none, so the notice is open-ended, and
  the field is omitted rather than sent as `null`.
- Step 4: the admin list has `3` rows; the dashboard also shows the new notice. The two agree about a
  live student notice — which is what makes the next steps' differences meaningful.
- Step 5: `201` with `audience: "ADMINS"`.
- Step 6: **the admin list has `4` rows and shows the `ADMINS` notice; the dashboard does not.** This
  is the case's point: **52 reads the table, not `v_active_announcements`.** An administrator must see
  `ADMINS` rows, inactive rows and out-of-window rows in order to act on them — the notice they need
  to reach is precisely the one the view hides. **Do not report the dashboard's absence of the
  `ADMINS` notice as a defect** — it is addressed to administrators, and a student's dashboard never
  receives one.
- Step 8: **the notice is still in the admin list, with `isActive: false`, and it is gone from the
  dashboard.** Reading the admin list through the dashboard's view would have left the deactivated
  notice unreachable — and therefore unrecoverable by step 9, which is exactly the action that needs
  it.
- Step 9: `200`, `isActive` reads `true`, and the notice is back on the dashboard.
- Step 10: **`400 VALIDATION_ERROR`** with a `fieldErrors` entry whose `"field"` is **`"endsAt"`**.
  **Not a `500`, and not a conflict naming `ck_ann_window`** — the service validates the window in Java
  first, so the caller gets a field error rather than the constraint's name. The constraint remains the
  authority; Java's check is a better-worded report of the same rule.
- Step 11: **`400`** on `endsAt` as well — the rule is **strict**. An end equal to the start is
  refused, because a notice whose window is one instant long would never be seen.
- Step 12: **`400 VALIDATION_ERROR`** with a field error on a field that is **not** `isActive` — the
  body is `{"isActive": bool}` and nothing else, because an announcement's content is create-once.
  **Do not report "the title was not updated" as a defect**: a correction is a new notice with the old
  one deactivated, which preserves the record of what students actually read. That decision is also
  what keeps every administrative write to this table on a procedure — a content-update endpoint would
  have had to write through Hibernate, where `sp_require_admin` and the audit row do not run (§3.2.1).
- Step 13: **`ANNOUNCEMENT_CREATED`, then `ANNOUNCEMENT_DEACTIVATED`, then `ANNOUNCEMENT_ACTIVATED`** —
  one row for each act, plus one more `ANNOUNCEMENT_CREATED` for step 5's notice. Each names
  `target_entity = 'announcements'` and the right `target_id`. The refusals in steps 10–12 left
  **nothing**.

**Result:** [ ] Pass   [ ] Fail

---

### M11-07 — Tip templates: the code is immutable, and the silent path is the one to watch

**Covers:** UC-21 B3–B4; `uk_tip_template_code`; the immutability refusal.

**Preconditions:** `${ADMIN_JWT}`. A seeded database has 7 templates (§3.1).

**Steps:**

1. `GET /api/v1/admin/tip-templates` with `${ADMIN_JWT}`. Record the count, one row's field names, and
   the order.
2. `POST /api/v1/admin/tip-templates` with `${ADMIN_JWT}` and:
   `{"code": "MANUAL_TEST", "conditionType": "GENERIC", "titleTemplate": "Test {category_name}", "bodyTemplate": "A template for the manual run.", "defaultPriority": 5}`.
   Record the response and the `id` — `<TT_ID>`.
3. `POST` again with the **same `code`**.
4. `POST` with a **lower-case** code: `"manual_test_lower"`.
5. `PATCH /api/v1/admin/tip-templates/<TT_ID>` with `{"titleTemplate": "Changed title"}`.
6. `PATCH /api/v1/admin/tip-templates/<TT_ID>` with `{"code": "DIFFERENT_CODE"}`.
7. `PATCH /api/v1/admin/tip-templates/<TT_ID>` with `{"code": "MANUAL_TEST"}` — the current code.
8. `GET` the list and read `<TT_ID>`'s row back.
9. `POST` with a code that is not `[A-Za-z0-9_]`: `"bad code!"`.
10. `PATCH /api/v1/admin/tip-templates/<TT_ID>` with `{"isActive": false}`.

**Expected result:**

- Step 1: `200`, `7` rows, ordered by `defaultPriority` ascending then `id` — the same order the tip
  generators consider templates in, which is what makes a priority editable with any confidence.
- Step 2: **`201`**, and the response is the row that was written. Fields are the seven of §3.4.
  **`conditionParams` is not present** — the column is a JSON blob the schema does not document and
  nothing in this build reads, so publishing it would expose a value no reader could interpret.
- Step 3: **`409 Conflict`** with `errorCode: "TIP_TEMPLATE_CODE_TAKEN"`. Classified from
  `uk_tip_template_code` **by constraint name**, not by SQLSTATE alone (23000 also covers the foreign
  keys). There is no second row.
- Step 4: **`201`**, and the stored `code` reads `"MANUAL_TEST_LOWER"` — upper case. `code` is the
  template's identity and `sp_generate_tips` matches on it, so it is normalised on the way in.
- Step 5: `200`, `titleTemplate` reads `"Changed title"`, and `code` is **unchanged**.
- Step 6: **`409 Conflict`** with `errorCode: "TIP_TEMPLATE_CODE_IMMUTABLE"`. **This is the case that
  matters.** The procedure's update branch writes every editable column and does **not** write `code`
  at all, so without the service's pre-check this request would have **succeeded, changed nothing, and
  answered "saved"** — leaving a client with a code that never moved. A silent wrong answer is worse
  than a refusal, and refusing is what the service does.
- Step 7: **`200`** — sending the *current* code is accepted as a no-op, so a client can round-trip a
  full representation without having to know to omit it. Only a **different** one is refused.
- Step 8: `<TT_ID>`'s `code` is still `"MANUAL_TEST"` and its title is still `"Changed title"` — step
  6 changed nothing, and step 7 changed nothing either.
- Step 9: **`400 VALIDATION_ERROR`** with a field error on `code`, matching the column's `VARCHAR(50)`.
  An unknown `conditionType` is refused the same way — **by name, never by ordinal**, so a typo does
  not shift meaning to the next member.
- Step 10: `200`, `isActive` reads `false`. A template is retired, never deleted: user tips point at
  it and the foreign key is `ON DELETE RESTRICT`, and the seeded `LOW_SAVINGS_RATE` template is
  legitimately unused until a student's condition matches it.

**Result:** [ ] Pass   [ ] Fail

---

### M11-08 — The settings list, all sixteen rows, exactly six editable

**Covers:** UC-23 / VĐ-05; the allow-list; the `adjustable` flag.

**Preconditions:** `${ADMIN_JWT}`.

**Steps:**

1. `GET /api/v1/admin/settings` with `${ADMIN_JWT}`. Record the count, the field names, and the order.
2. List the keys that answer `adjustable: true`.
3. List the keys that answer `adjustable: false`.
4. Read one read-only row's `value` — e.g. `app.timezone`.
5. `PATCH /api/v1/admin/settings/app.timezone` with `{"value": "UTC"}`.
6. `PATCH /api/v1/admin/settings/no.such.setting` with `{"value": "1"}`.

**Expected result:**

- Step 1: `200`, **`16` rows**, in key order, each with **exactly** the five fields of §3.4 — `key`,
  `value`, `valueType`, `description`, `adjustable`. **Not `updatedBy` and not `updatedAt`**: the
  audit trail records who changed a threshold and when, and a `SETTING_CHANGED` row carries the
  previous value too, which a per-row copy could not.
- Step 2: **exactly the six of §3.6.** Assert the set literally — a seventh key added to the procedure
  without a decision should fail here rather than quietly join the list.
- Step 3: **exactly the ten of §3.6**, each `adjustable: false`.
- Step 4: the read-only row **still shows its value** (`Asia/Ho_Chi_Minh`). The flag means "not
  editable through this API", not "hidden" — a client renders the row read-only rather than omitting
  it, so the list is informative about the deployment.
- Step 5: **`409 Conflict`** with `errorCode: "THRESHOLD_NOT_ADJUSTABLE"`, and `app.timezone`'s value
  is **unchanged** in the database. The key exists, so it is not a `404`; it is named and not
  adjustable, so it is not a `400`.
- Step 6: **`404 NOT_FOUND`** — a key that exists nowhere is a missing resource, a different answer
  from step 5's. **The two statuses mean different things and the difference is worth reading**: `404`
  says "no such setting", `409` says "this setting exists and this API does not change it".
- **The flag and the allow-list are the same list.** A client that renders from `adjustable` and a
  request that ignored it are answered by one constant, so the two cannot drift — a request that
  ignored the flag is refused rather than accepted and dropped.

**Result:** [ ] Pass   [ ] Fail

---

### M11-09 — Changing a threshold, and what a bad value does

**Covers:** VĐ-05; BR-15; the six keys; the value shapes.

**Preconditions:** `${ADMIN_JWT}`. **This case changes shared state — do the restoring step.**

**Steps:**

1. `PATCH /api/v1/admin/settings/insight.spike_threshold_pct` with `{"value": "41"}`.
2. `GET /api/v1/admin/settings` and read that row's `value`.
3. Verify in the database:
   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT setting_key, setting_value FROM system_settings
        WHERE setting_key = 'insight.spike_threshold_pct';"
   ```
4. `PATCH` each of the other five adjustable keys with a valid value, one at a time.
5. `PATCH /api/v1/admin/settings/tips.max_dashboard` with `{"value": "abc"}`.
6. `PATCH /api/v1/admin/settings/budget.near_threshold_pct` with `{"value": "-5"}`.
7. `PATCH /api/v1/admin/settings/insight.spike_baseline_months` with `{"value": "0"}`.
8. `PATCH /api/v1/admin/settings/insight.spike_baseline_months` with `{"value": "13"}`.
9. `PATCH /api/v1/admin/settings/insight.spike_baseline_months` with `{"value": "6.5"}`.
10. `PATCH /api/v1/admin/settings/insight.spike_baseline_months` with `{"value": "6"}`.
11. **Restore** `insight.spike_threshold_pct` to its seeded value and confirm it:
    `PATCH` with `{"value": "30"}`, then read the row back.
12. Read the audit log:
    ```bash
    mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
      "SELECT id, action, target_entity, target_id, detail FROM admin_audit_log
         WHERE action = 'SETTING_CHANGED' ORDER BY id;"
    ```

**Expected result:**

- Step 1: `200`, and the response is the setting with its new value — `value` reads `"41"`.
- Step 3: **the column holds `41`.** The value really moved; the response is not a lie.
- Step 4: **each of the five answers `200`** and each value is stored. Further assurance that the
  allow-list is a set of keys, not one privileged key.
- Step 5: **`400 VALIDATION_ERROR`** with a `fieldErrors` entry whose `"field"` is `"value"`. **Not a
  `500`** — the shape is checked before the procedure is reached.
- Step 6: `400` on `value` — the percentage keys take a **positive** number, and `-5` is not one.
- Steps 7–9: `400` on `value` — `insight.spike_baseline_months` takes a **whole number from 1 to
  12**, so `0`, `13` and `6.5` are all refused. **This key is the one with the narrow shape**; the
  other five accept a positive decimal.
- Step 10: **`200`** — `6` is inside the range, so the bound is inclusive at both ends.
- Step 11: the seeded value is back. **Do the restore** — the module 6 and module 9 suites and other
  testers read this row, and a threshold left at `41` changes which tips a student receives
  (`sp_generate_tips` reads it for BR-15).
- Step 12: **one `SETTING_CHANGED` row per successful change** — six from step 4 plus step 1's and
  step 10's and step 11's. Each has `target_entity = 'system_settings'`, **`target_id` NULL** (the
  key is in the detail, not in the id column), and a `detail` carrying `{key, oldValue, newValue}`.
  **The refusals in steps 5–9 left nothing.**

**Result:** [ ] Pass   [ ] Fail

---

### M11-10 — The statistics: every figure equals its table, and nothing here moves the insights count

**Covers:** UC-23; `v_admin_usage_stats`; the module 12 lock; the money position.

**Preconditions:** `${ADMIN_JWT}`. For the deltas below, note the current values first.

**Steps:**

1. `GET /api/v1/admin/stats` with `${ADMIN_JWT}`. Record all ten field names and the values.
2. Compare each count against the table it is derived from:
   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT (SELECT COUNT(*) FROM users WHERE role='STUDENT') AS students,
             (SELECT COUNT(*) FROM transactions WHERE deleted_at IS NULL) AS txns,
             (SELECT COUNT(*) FROM budgets) AS budgets,
             (SELECT COUNT(*) FROM user_tips) AS tips,
             (SELECT COUNT(*) FROM insights) AS insights;"
   ```
3. `GET /api/v1/admin/stats/top-categories`; record its count.
4. `POST /api/v1/tips/generate` as a student (with `${USER_A_JWT}`), then re-read both statistics
   endpoints.
5. `POST /api/v1/admin/users/<Alex's id>/status` with `{"status": "DISABLED"}`, re-read `GET
   /api/v1/admin/stats`, then re-enable him with `{"status": "ACTIVE"}` and re-read once more.
6. `GET /api/v1/admin/stats/top-categories` twice more and diff the two responses.

**Expected result:**

- Step 1: `200`, **ten fields** — `totalStudents`, `activeStudents`, `disabledStudents`,
  `activeUsers30d`, `totalTransactions`, `totalExpenseLogged`, `totalIncomeLogged`, `totalBudgets`,
  `totalTipsGenerated`, `totalInsightsGenerated` — and **no identifier of any kind**. No user id, no
  category id, no timestamp.
- Step 2: **each figure equals its own table's count.** `totalTransactions` excludes soft-deleted rows
  (BR-09), so it counts `deleted_at IS NULL`. `totalInsightsGenerated` is **`3`** on a seeded
  database and equals `SELECT COUNT(*) FROM insights` — it is the table's own count, not a
  hard-coded zero (§5.4).
- Step 3: one row **per category**, including ones nobody has used and including students' personal
  categories (`scope: "PERSONAL"`). A category with no transactions appears with `txnCount: 0` and
  `totalAmount: 0.00`.
- Step 4: `totalTipsGenerated` **goes up** by the tips that generation produced. **`totalInsights-
  Generated` does not move** — generating tips writes `user_tips`, not `insights`.
- Step 5: `totalStudents` is unchanged, `activeStudents` **goes down** by one and `disabledStudents`
  **up** by one; `totalTransactions` and the money totals are **unchanged**. Re-enabling puts the
  three back. **Do not read `totalStudents` as "all accounts"** — it counts the `STUDENT` role, so the
  seeded administrator is not in it.
- **Signing in as a student raises `activeUsers30d`.** It counts distinct accounts with a session seen
  in the last 30 days, so if you signed in during §2.2, this figure is at least `1` — it is not a
  sign-in tally and it does not reset.
- Step 6: **the two responses are identical, in the same order.** The view declares no `ORDER BY`, so
  the DAO supplies a **total** order — `txnCount` descending, `totalAmount` descending, `categoryId`
  ascending. The final tie-break is what stops two categories with equal counts and totals swapping
  between two identical calls and a client diffing them seeing movement that never happened. **A
  changed order between step 6's two calls is a finding.**
- **Every money figure here is a sum over many students.** `totalExpenseLogged`, `totalIncomeLogged`
  and each row's `totalAmount` are `SUM(amount)` over plaintext amounts — the exposure recorded as
  OB-013, a deferred decision this module serves and does not change (§5.2). **No route returns one
  student's amounts.**

**Result:** [ ] Pass   [ ] Fail

---

### M11-11 — No response leaks a secret, and no route names anyone

**Covers:** §7.2; BR-01; VĐ-04; §15.

**Preconditions:** `${ADMIN_JWT}`. `jq` available.

**Steps:**

1. Capture the raw bodies of every read endpoint:

   ```bash
   for path in users categories announcements tip-templates settings stats stats/top-categories; do
     curl -s "http://localhost:8080/api/v1/admin/$path" \
       -H "Authorization: Bearer ${ADMIN_JWT}" -o "/tmp/cc_admin_$(echo $path | tr / _).json"
   done
   ```

2. List every key the bodies actually contain, at every level:

   ```bash
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_admin_*.json | sort -u
   ```

3. Open `http://localhost:8080/api-docs` and compare the `AdminUserResponse`, `AnnouncementResponse`,
   `TipTemplateResponse` and `SystemSettingResponse` schemas with what you printed.
4. Grep the bodies for the forbidden names of §3.5.

**Expected result:**

- **None of the §3.5 fields appears in any body, at any level.** In particular:
  - **`passwordHash` / `password_hash` / `password`** — never selected (BR-01).
  - **`tokenVersion` / `token_version`** — the entire security meaning of a JWT's `tv` claim.
    Publishing it would let an attacker holding a stolen token decide whether it is still live.
  - **`monthlyAllowanceBaseline`, `monthlySavingsGoal`** — VĐ-04. An administrator manages accounts,
    not a student's finances.
  - **`conditionParams`** — not documented and not read by anything.
- **No response carries a raw password-reset token**, and 48's body is `{message}` and nothing else.
- **No route accepts a caller's identity as a parameter**, so there is nothing to tamper with: the
  actor comes from the verified token and the path id is only ever the account being acted on.
- **The guarantee is structural.** The response records have **no component** for any of these fields,
  so there is no filter that a later edit could drop. The suite also scans every read endpoint's raw
  JSON recursively, with a non-triviality control that puts a forbidden key into a sample payload and
  proves the scan rejects it — a scan that collected nothing would otherwise pass silently.

**Result:** [ ] Pass   [ ] Fail

---

### M11-12 — Every route refuses a student, and a missing token is not a refusal by role

**Covers:** UC-05 E1; §7.5; the `hasRole("ADMIN")` rule.

**Preconditions:** All four tokens available.

**Steps:** Send each request and record the status and `errorCode`.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/admin/users` — no `Authorization` header | `401` `UNAUTHENTICATED` |
| b | `GET /api/v1/admin/users` — `Authorization: Bearer not.a.token` | `401` |
| c | `GET /api/v1/admin/users` — `${USER_A_JWT}` | `403` `ACCESS_DENIED` |
| d | `POST /api/v1/admin/users/1/status` with `{"status":"DISABLED"}` — `${USER_A_JWT}` | `403` |
| e | `POST /api/v1/admin/categories` with `{"name":"X","type":"EXPENSE"}` — `${USER_A_JWT}` | `403` |
| f | `PATCH /api/v1/admin/announcements/1` with `{"isActive":false}` — `${USER_B_JWT}` | `403` |
| g | `POST /api/v1/admin/tip-templates` with a valid body — `${USER_B_JWT}` | `403` |
| h | `PATCH /api/v1/admin/settings/tips.max_dashboard` with `{"value":"5"}` — `${USER_A_JWT}` | `403` |
| i | `GET /api/v1/admin/stats` — `${USER_B_JWT}` | `403` |
| j | `GET /api/v1/admin/stats/top-categories` — `${ADMIN_JWT}` | `200` |
| k | `GET /api/v1/admin/users/999999/unknown-sub-resource` — `${ADMIN_JWT}` | `404` `NOT_FOUND` |
| l | `GET /api/v1/admin/users/999999/unknown-sub-resource` — `${USER_A_JWT}` | `403` |

**Expected result:**

- a, b: `401` with `errorCode: "UNAUTHENTICATED"`. A malformed token is not a `400`, and **no stack
  trace or token text** is returned or logged.
- c–i: `403` with `errorCode: "ACCESS_DENIED"` — **all sixteen routes, reads and writes alike.**
  Spring Security enforces the rule **before** the controller, so no service method is ever reached.
  **Send a valid body on every write** so a `400` cannot be mistaken for the right answer.
- **A student is refused because these routes manage other people's accounts.** `GET /admin/users`
  publishes every account's email; 47 disables an account and revokes its sessions; 48 issues a reset
  link for it; 60 and 61 publish system-wide financial aggregates. None of that is a student's.
- k: **`404`** — the administrator is admitted, the dispatcher is reached, and no mapping exists. This
  is what distinguishes "no such path" from "not for you".
- l: **`403`** — the role rule runs first, so a student cannot use path probing to learn which routes
  exist. **Do not report l's `403` as "the route leaked"** — it is the same answer for every path,
  including ones that do not exist.
- j: `200`, the ranked list — the control that proves the rule is selective rather than blanket.

**Result:** [ ] Pass   [ ] Fail

---

### M11-13 — The audit trail, the live-role rule, and the routes that do not exist

**Covers:** UC-22 B5; §3.2.2; §13 (no duplicate capability); the endpoint inventory.

**Preconditions:** `${ADMIN_JWT}`, `${USER_A_JWT}`, `${USER_B_JWT}`. **This case changes shared state
and restores it in step 5.** Note the `admin_audit_log` row count first
(`SELECT COUNT(*) FROM admin_audit_log`).

**Steps:**

1. Perform one act of each kind, noting the audit row each leaves:
   a. disable then re-enable a student;
   b. create a default category;
   c. publish an announcement;
   d. create a tip template;
   e. change one threshold and restore it.
2. Read the whole log:
   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, admin_user_id, action, target_entity, target_id, ip_address
        FROM admin_audit_log ORDER BY id;"
   ```
3. Promotion: register a fresh student, sign in as them to get a **student** token, confirm that token
   is refused on `GET /api/v1/admin/users`, then promote the account:
   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "UPDATE users SET role='ADMIN' WHERE email='<the fresh account>';"
   ```
   and reuse **the token from before the promotion**.
4. Send each request below with `${ADMIN_JWT}` and record the status.
5. **Restore**: demote the account from step 3 back to `STUDENT`, and confirm both the restore and the
   demoted account's token.

| # | Request | Expected |
|---|---|---|
| a | `PUT /api/v1/admin/categories/1` | `400` `INVALID_REQUEST` |
| b | `DELETE /api/v1/admin/users/1` | `400` `INVALID_REQUEST` |
| c | `DELETE /api/v1/admin/categories/1` | `400` `INVALID_REQUEST` |
| d | `DELETE /api/v1/admin/announcements/1` | `400` `INVALID_REQUEST` |
| e | `GET /api/v1/admin/users/1` | `400` `INVALID_REQUEST` |
| f | `GET /api/v1/admin/audit-log` | `404` |
| g | `POST /api/v1/admin/announcements/1/activate` | `404` |
| h | `POST /api/v1/admin/users/1/password-reset/extra` | `404` |
| i | `GET /api/v1/admin/insights` | `400` `INVALID_REQUEST` |
| j | `GET /api/v1/admin/users?role=ADMIN` | `200`, **every** account |

**Expected result:**

- Step 1: **one audit row per act, and none of them was written by the API.** Every row's
  `admin_user_id` is the administrator's own id, taken from the token — there is no parameter that
  could name a different actor. This is the visible face of §3.2.1: the writes are `CALL`s, so
  `sp_require_admin` ran and the procedure logged.
- Step 2: **the ten action strings appear and no others**: `USER_DISABLED`, `USER_ENABLED`,
  `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `ANNOUNCEMENT_CREATED`, `ANNOUNCEMENT_ACTIVATED`,
  `ANNOUNCEMENT_DEACTIVATED`, `TIP_TEMPLATE_SAVED`, `PASSWORD_RESET_SENT` and `SETTING_CHANGED`.
  `target_entity` names the table, `target_id` the row — **except `SETTING_CHANGED`, whose
  `target_id` is NULL and whose key lives in the `detail` JSON**, because a setting has no id.
  `ip_address` is the caller's address.
- **The audit log is write-only in this build.** There is no route over it, and (f) confirms it: UC-22
  B5 requires *writing* an audit row, not reading one, and no view is defined over the table.
- Step 3: **the student token is refused with `403` before the promotion** — the control — **and `200`
  after it, with the same token.** That is not a bug: the authority for a request is the **live
  `users` row**, re-read on every request, not the `role` claim inside the token. The row is already
  the authority for `status` and `token_version`, both re-read per request for exactly this reason, and
  reading the role from the claim as well would make the role the one account attribute a stale token
  could assert about itself. **The safe direction is the live row in both directions** — a demotion
  takes effect immediately too, which is the more important half and which step 5 shows: the demoted
  account's token is refused again at once.
- Step 5: the account is `STUDENT` again and its token answers `403`. **Do the restore** — the
  security suite and any later tester must not find an unexpected administrator.
- Table, steps a–e: **`400 INVALID_REQUEST`**, "The HTTP method is not supported by this endpoint."
  The **path exists** — for `PATCH` and `POST` respectively — and the verb does not. **`GET
  /api/v1/admin/users/1` (e) is the one to read carefully: there is still no `/{id}` read.** It is a
  method error rather than a `404` only because other verbs legitimately live on that URL, so **do not
  read (e)'s `400` as "the route exists"** — 47 and 48 return the account, and a bare read would be the
  same row with none of the list's context.
- Table, f–h: **`404`** — `/admin/audit-log`, `/{id}/activate` and a deeper path are URLs no controller
  maps. Each absence is deliberate (§1): three names for one write of one column is the
  `/tips/{id}/pin|unpin|dismiss` rejection, and reading the audit log has no use case.
- Table, i: **`400 INVALID_REQUEST`** — `insights` matches the `/admin/users/{id}` pattern just as
  readily as a number, so this is a method failure rather than a missing path. **Module 12 is locked
  pending the project owner's approval**: there is no `/admin/insights/**` route, no `insights` read
  path in Java, and no settings key for it. The statistics count serves the table's own row count
  because the view defines it (§5.4), not because this module generates anything.
- Table, j: **`200` with every account.** `role` is **not** a parameter either endpoint reads — the
  list already carries each account's `role`, so a filter would be a second expression of a rule the
  contract does not have. **A `200` returning only administrators would be a finding.**
- The OpenAPI document at `/api-docs` lists **exactly sixteen** administration operations on thirteen
  paths, and 61 in total across 43 paths. This is machine-checked by `OpenApiContractIT`, and the
  counts are asserted deliberately — adding a route without adding it to `docs/api/API_INVENTORY.md`
  fails that test on purpose.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Behaviours a tester is most likely to misread

All are deliberate and documented. Read this section before marking anything in §4 as a failure. None
is a defect in the module.

> ### 5.1 The self-disable guard is one-sided
>
> An administrator **cannot** disable their own account (`409 SELF_DISABLE_FORBIDDEN`) but **may**
> re-enable it — `ACTIVE` on an already-active account is a permitted no-op.
>
> **That asymmetry is the design, not an oversight.** The danger is locking yourself out, not letting
> yourself back in. **Do not "fix" it**, and do not report M11-03 step 5 as an inconsistency.
>
> The refusal is also answered **before** the database is called, and one reason is worth knowing:
> `sp_set_user_status` signals the same SQLSTATE 45000 for all three of its refusals — the account does
> not exist, the status is invalid, and this one — so a refusal reaching Java could not be told apart
> from "no such account" without matching the procedure's prose, which this project forbids. With this
> one answered in Java, a surviving 45000 can only mean "the target does not exist". The procedure's
> own check remains the guarantee for a hand-run `CALL`.

> ### 5.2 Money is published as sums, never as one student's amounts
>
> `totalExpenseLogged`, `totalIncomeLogged` and each ranking row's `totalAmount` are `SUM(amount)` over
> **plaintext** amounts. That is **OB-013**, a deferred decision: MySQL cannot sum ciphertext, most
> views aggregate amounts, and the project chose to record the gap rather than hide it or invent
> deterministic encryption.
>
> **Module 11 serves the figures and does not change the exposure, and it publishes no per-student
> amount anywhere.** Every money value on these two routes is a sum over many students. **Do not
> report `totalAmount` as a leak of an individual's spending** — M11-10 is where you can check the
> shape. See `docs/SECURITY.md` §12 and OB-013.

> ### 5.3 A promotion takes effect on a token already in circulation
>
> Promote a student to `ADMIN` and the token they are already holding **reaches the administration
> routes at once** — there is no re-sign-in. Demote them and the same token is refused just as
> immediately.
>
> **This is deliberate.** The JWT's `role` claim is decoded and then never read; the session service
> takes the role, the status and the `token_version` from the `users` row on every request. The row is
> already the authority for two of the three, so reading the role from the claim would make the role
> the one account attribute a stale token could assert about itself. **Do not report M11-13 step 3 as a
> privilege-escalation defect** — the privilege was granted by the database, and the response is
> correct in both directions.

> ### 5.4 `totalInsightsGenerated` is not zero, and that is not module 12 being built
>
> A seeded database holds **three** `insights` rows, because `db/06_demo.sql` calls
> `sp_generate_monthly_insight` for three demo months. The statistic is the **table's own count**, and
> it is served because `v_admin_usage_stats` defines it — hiding a column the schema declares would be
> a worse answer than reporting it.
>
> **UC-17 is still locked.** Nothing in the Java reads or writes that table, and no administrative
> procedure touches it; M11-10 step 4 is the check that the figure does not move when you act.
> **Do not report the non-zero figure as module 12 being partially built**, and do not expect the count
> to be `0`.

> ### 5.5 A created row is read back by its natural identity, not by the last insert id
>
> Each `201` returns the row that was actually created, and it is found by a natural key — categories
> by `(type, name)` among the default rows, tip templates by `code`, announcements by
> `(created_by, title, starts_at)` — rather than by `LAST_INSERT_ID()`.
>
> **The reason is a trap worth knowing.** No admin procedure declares an `OUT` parameter, and **each
> writes its `admin_audit_log` row after its target row**. `admin_audit_log.id` is `AUTO_INCREMENT`, so
> after the `CALL` returns, `LAST_INSERT_ID()` reports the **audit** row's id, not the created
> resource's. A `201` body whose `id` matched an audit row would be the visible symptom — M11-05 step 3
> and M11-07 step 2 are where to check.

> ### 5.6 An announcement's content is create-once, and that is not a missing feature
>
> `PATCH /api/v1/admin/announcements/{id}` carries `{"isActive": bool}` and **nothing else**. There is
> no way to edit a notice's title or body — a typo is fixed by publishing a corrected notice and
> deactivating the old one.
>
> **Two reasons, and both matter.** A notice is a thing that was published, and correcting it in place
> would destroy the record of what students actually read. And the load-bearing one: every
> administrative write to `announcements` therefore goes through `sp_require_admin` and leaves an audit
> row, so there is **no unaudited path** to that table. A content-update endpoint would have had to
> write through Hibernate, where neither happens. **Do not report M11-06 step 12's `400` as a gap** —
> it is what keeps OB-005's claim true.

> ### 5.7 Tip templates are retired, never deleted — including an unused one
>
> A template is switched off with `PATCH {"isActive": false}`. There is **no `DELETE`**: `user_tips`
> rows point at templates and the foreign key is `ON DELETE RESTRICT`, and the seeded
> `LOW_SAVINGS_RATE` template is legitimately **unused until a student's condition matches it**.
>
> **Do not report a template showing no tips as dead data worth deleting.** Availability and use are
> different questions, and deactivating is the reversible answer to both.

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M11-01 | UC-22 B1; `AdminUserResponse`'s field set; the `NON_NULL` omission of `academicYear` and `lastLoginAt`; BR-01 (`email` is published only here) |
| M11-02 | UC-22 B3; BR-03 (the disable is immediate, three reasons); the session revocation and the `token_version` delta; the audit rows |
| M11-03 | UC-22 A1 (the one-sided self-disable guard); the Java pre-check and why it exists |
| M11-04 | UC-22 B4; BR-04 (the two disclosure policies); the notifier port; the 64-hex `token_hash`; the raw token's single location |
| M11-05 | UC-20; BR-06; BR-07 (retire, do not delete); `uk_categories_scope_type_name` and its per-type key; a personal category is not administrable |
| M11-06 | UC-21 B1–B2; `ck_ann_window`; create-once content; the table read versus the dashboard's view |
| M11-07 | UC-21 B3–B4; `uk_tip_template_code`; the immutable `code` and the silent path it prevents; retire, not delete |
| M11-08 | UC-23 / VĐ-05; the sixteen rows and the six adjustable; the flag equal to the allow-list; `404` versus `409` on a key |
| M11-09 | VĐ-05; BR-15; the six keys' value shapes; `SETTING_CHANGED` with a NULL `target_id` |
| M11-10 | UC-23; `v_admin_usage_stats` and `v_admin_top_categories`; the ranking's total order; the module 12 lock; OB-013's sum position |
| M11-11 | §7.2; BR-01; VĐ-04; §15 (no internal or secret field published) |
| M11-12 | UC-05 E1; §7.5; the role rule ahead of every other rule; `403` versus `404` |
| M11-13 | UC-22 B5; §3.2.2 (the live row is the authority); §13 (no duplicate capability); the endpoint inventory |
| §5.1–§5.7 | The seven behaviours most likely to be misread |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass.

| Not covered | Why |
|---|---|
| The `sp_require_admin` refusal firing through the API | Unreachable by design: the filter chain refuses a non-administrator first, and the procedure is a second gate for a direct `CALL`. Its behaviour against a hand-run `CALL` is `DB_DESIGN.md`'s to state |
| Two requests racing past the same pre-check | A manual tester cannot produce it reliably, and a hand-run race that happens not to interleave proves nothing. The classifiers are covered by `AdminWriteFailureTest` |
| A `500`-level failure path | Not reachable through the API. There is no input to make fail and no fault injection |
| The encryption key's rotation or absence | A deployment concern, unchanged by this module. This module adds no encrypted field and reads none |
| Direct-database administration | **OB-005**: someone with direct credentials can write these tables without `sp_require_admin` and without an audit row. The API cannot prevent that, and M11-13 covers only the API's half — that no route provides such a path |
| Per-student amounts | **OB-013**, and deliberately: UC-23 asks for system-wide figures, and no administrative response carries a monetary field belonging to one account |
| Reading `admin_audit_log` through the API | There is no route and no view (§5, M11-13 f). UC-22 B5 requires writing an audit row, not reading one |
| Searching or filtering the account list | UC-22 defines neither; M11-13 j checks that `role` is ignored rather than silently honoured |
| The `condition_params` column | Not exposed (§3.4). The schema does not document its meaning and nothing in this build reads it — recorded as a follow-up rather than guessed at |
| Modules 1–10's own behaviour | Their own procedures cover them. This document reads the student profile, dashboard, tips and categories only where a module 11 case needs a counterpart to compare against |
| The Angular administration screens | There are none wired. `docs/api/administration.md` §10 records the divergences a rewiring must reconcile. No Angular source is changed by this module |
| Module 12 | Locked pending the project owner's approval. No `/admin/insights/**` route, no `insights` read path, and no settings key — M11-13 i is the case that pins the absence |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/administration.md`,
`docs/modules/MODULE_11_ADMINISTRATION.md`, `docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md`,
`docs/SECURITY.md`, `db/01_schema.sql`, `db/02_views.sql`, `db/03_procedures.sql` or the implementation
under `backend/src/main/java/com/campuscoin/admin/`.
