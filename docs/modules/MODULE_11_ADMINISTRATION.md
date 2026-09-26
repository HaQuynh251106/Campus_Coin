# Module 11 — Administration (UC-20 … UC-23)

| | |
|---|---|
| **Endpoints** | `GET /api/v1/admin/users` (46), `POST /api/v1/admin/users/{id}/status` (47), `POST /api/v1/admin/users/{id}/password-reset` (48), `GET /api/v1/admin/categories` (49), `POST /api/v1/admin/categories` (50), `PATCH /api/v1/admin/categories/{id}` (51), `GET /api/v1/admin/announcements` (52), `POST /api/v1/admin/announcements` (53), `PATCH /api/v1/admin/announcements/{id}` (54), `GET /api/v1/admin/tip-templates` (55), `POST /api/v1/admin/tip-templates` (56), `PATCH /api/v1/admin/tip-templates/{id}` (57), `GET /api/v1/admin/settings` (58), `PATCH /api/v1/admin/settings/{key}` (59), `GET /api/v1/admin/stats` (60), `GET /api/v1/admin/stats/top-categories` (61) |
| **Requirements** | UC-20, UC-21 (B1–B4), UC-22 (B1–B5), UC-23, UC-05 E1; BR-01, BR-03, BR-06, BR-07, BR-15, VĐ-04, VĐ-05; SRS §7.2, §7.5, §13, §26 |
| **Schema objects read** | `users`, `categories`, `announcements`, `tip_templates`, `system_settings`, `v_admin_usage_stats`, `v_admin_top_categories`; `user_sessions` and `transactions` indirectly through the first view |
| **Schema objects written** | `users`, `categories`, `announcements`, `tip_templates`, `system_settings` and `admin_audit_log` — **all through the eight existing procedures, none through Hibernate. No schema object was changed and no `db/` line was added** |
| **Tests** | 168 new (37 `AdminSecurityIT` + 21 `AdminUserApiIT` + 20 `AdminCategoryApiIT` + 17 `AdminAnnouncementApiIT` + 17 `AdminTipTemplateApiIT` + 28 `AdminSettingsApiIT` + 11 `AdminStatsApiIT` + 16 `AdminWriteFailureTest` + `OpenApiContractIT` 13 → 14); suite 568 → **736**, all green |
| **Status** | Complete. Two production defects found and fixed by the suite (§5.1, §5.2). Two deferred items touched: **OB-005** (unchanged but now airtight at the API layer) and **OB-013** (module 11 serves the existing aggregate exposure) |

---

## 1. Scope

Module 11 is the administration surface: manage accounts (UC-22), maintain the shared default
categories (UC-20), publish and retract announcements and maintain saving-tip templates (UC-21), and
read the system-wide usage figures and adjust the tunable thresholds (UC-23, VĐ-05).

**The module's defining fact is that it writes nothing new into the database.** All eight
administrative procedures already exist — `sp_set_user_status`, `sp_admin_send_password_reset`,
`sp_admin_upsert_default_category`, `sp_admin_create_announcement`,
`sp_admin_set_announcement_active`, `sp_admin_upsert_tip_template`, `sp_admin_set_threshold`, and the
shared `sp_require_admin` they all call — and so do the four admin views. Not one of them was
referenced by Java before this module; every one was described in javadoc as "the database already
does this". So the work here is an endpoint set, a refusal classification, and a disclosure boundary,
and §3 is the split stated object by object.

**The specific consequence is that every write is a `CALL`.** The application account holds direct
table grants, so a `JpaRepository#save` would bypass `sp_require_admin` and write no audit row. That
is why `UserRepository`, `CategoryRepository` and `SystemSettingRepository` are read-only in this
module, and why §3.2 lists the trap in full.

**Sixteen operations, grouped by resource.**

| # | Endpoint | UC |
|---|---|---|
| 46–48 | `GET /admin/users`, `POST /admin/users/{id}/status`, `POST /admin/users/{id}/password-reset` | UC-22 |
| 49–51 | `GET`, `POST /admin/categories`, `PATCH /admin/categories/{id}` | UC-20 |
| 52–54 | `GET`, `POST /admin/announcements`, `PATCH /admin/announcements/{id}` | UC-21 |
| 55–57 | `GET`, `POST /admin/tip-templates`, `PATCH /admin/tip-templates/{id}` | UC-21 |
| 58–59 | `GET /admin/settings`, `PATCH /admin/settings/{key}` | UC-23 / VĐ-05 |
| 60–61 | `GET /admin/stats`, `GET /admin/stats/top-categories` | UC-23 |

All sixteen are `ADMIN`-only, and one line of `SecurityConfig` covers all of them (§4.1).

### What is deliberately not built

| Excluded | Why |
|---|---|
| `PUT` on any resource | Every writable thing is a `PATCH`, and every procedure is an "upsert" or a "set one column" |
| `DELETE` on users, categories, announcements, tip templates | No procedure exists; retirement is `PATCH { "isActive": false }`. `user_tips.tip_template_id` is `ON DELETE RESTRICT`, and `LOW_SAVINGS_RATE`'s template is legitimately seeded-but-unused |
| `GET /admin/users/{id}` | 47 and 48 already return the account; a bare read is the same row with none of the list's context |
| `POST .../activate`, `.../deactivate` | Three names for one write of one column — the `/tips/{id}/pin\|unpin\|dismiss` rejection |
| `GET /admin/audit-log` | UC-22 B5 requires *writing* an audit row, not reading one, and no view is defined over the table |
| `?role=`, `?status=`, `?q=` on 46 | Not in UC-22; the client filters what it has |
| `POST /admin/auth/logout` | UC-05 defines none; the shared `POST /auth/logout` serves any token |
| `/admin/insights/**`, `/admin/anomalies/**`, `/admin/ai/**` | Module 12, locked. No route, no key, no mention in the settings allow-list |
| A `GET` over `admin_audit_log` | The table is write-only by design in this build |

---

## 2. Endpoints

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 46 | `GET` | `/api/v1/admin/users` | UC-22 B1 | ADMIN | `200` array | `401`, `403` |
| 47 | `POST` | `/api/v1/admin/users/{id}/status` | UC-22 B3 | ADMIN | `200` account | `400`, `401`, `403`, `404`, `409` self-disable |
| 48 | `POST` | `/api/v1/admin/users/{id}/password-reset` | UC-22 B4 | ADMIN | `202` message | `401`, `403`, `404` |
| 49 | `GET` | `/api/v1/admin/categories` | UC-20 | ADMIN | `200` array | `401`, `403` |
| 50 | `POST` | `/api/v1/admin/categories` | UC-20 | ADMIN | `201` category | `400`, `401`, `403`, `409` |
| 51 | `PATCH` | `/api/v1/admin/categories/{id}` | UC-20 | ADMIN | `200` category | `400`, `401`, `403`, `404`, `409` |
| 52 | `GET` | `/api/v1/admin/announcements` | UC-21 | ADMIN | `200` array | `401`, `403` |
| 53 | `POST` | `/api/v1/admin/announcements` | UC-21 B1 | ADMIN | `201` announcement | `400`, `401`, `403` |
| 54 | `PATCH` | `/api/v1/admin/announcements/{id}` | UC-21 B2 | ADMIN | `200` announcement | `400`, `401`, `403`, `404`, `409` |
| 55 | `GET` | `/api/v1/admin/tip-templates` | UC-21 | ADMIN | `200` array | `401`, `403` |
| 56 | `POST` | `/api/v1/admin/tip-templates` | UC-21 B3 | ADMIN | `201` template | `400`, `401`, `403`, `409` code taken |
| 57 | `PATCH` | `/api/v1/admin/tip-templates/{id}` | UC-21 B4 | ADMIN | `200` template | `400`, `401`, `403`, `404`, `409` code immutable |
| 58 | `GET` | `/api/v1/admin/settings` | UC-23 | ADMIN | `200` array | `401`, `403` |
| 59 | `PATCH` | `/api/v1/admin/settings/{key}` | UC-23 / VĐ-05 | ADMIN | `200` setting | `400`, `401`, `403`, `409` not adjustable |
| 60 | `GET` | `/api/v1/admin/stats` | UC-23 | ADMIN | `200` aggregate | `401`, `403` |
| 61 | `GET` | `/api/v1/admin/stats/top-categories` | UC-23 | ADMIN | `200` array | `401`, `403` |

The request and response contract, field by field, is in
[`docs/api/administration.md`](../api/administration.md).

### 2.1 The four decisions that shape the surface

**47 is `POST .../{id}/status`, not `PATCH .../{id} {status}`.** The write is a *transition* whose side
effects exceed the named column — every open session revoked with `revoked_reason='ADMIN_DISABLE'` and
`token_version` incremented — and it triggers UC-22 B5 logging. Same shape as
`POST /notifications/{id}/read` and `POST /tips/{id}/state`. It returns the account in its new state,
which is why there is no `GET /admin/users/{id}`.

**48 is not a duplicate of `POST /auth/password-reset/request`.** That one is public, addressed by
*email*, and answers identically whether the address exists (BR-04 anti-enumeration). This one is
ADMIN-only, addressed by *id*, answers `404` for a missing target, and leaves an audit row. Two use
cases, two disclosure policies, one table. §4.5 states the contrast with module 10's opposite policy.

**54 is `isActive` only, and that is what keeps OB-005 true.** `announcements` has no content-update
procedure while `tip_templates` does — a deliberate asymmetry. A notice is a thing that was published;
correcting it means publishing the corrected text and retracting the old one, which preserves the
record of what students actually read. The load-bearing consequence is that **every** administrative
write to this table goes through `sp_require_admin` and leaves an audit row, so there is no unaudited
path. A content-update endpoint would have had to write through Hibernate and would have broken that.

**49 exists because no other route can serve it.** `GET /api/v1/categories` is `STUDENT`-only and its
`findVisibleToUser` merges the caller's own rows with the defaults; the admin needs exactly
`user_id IS NULL`.

### 2.2 52 is not the dashboard's announcement slice

`DashboardViewDao` reads `v_active_announcements` with an added `audience IN ('ALL','STUDENTS')` filter
and drops `audience` from the response. An administrator must see `ADMINS` rows, inactive rows and
out-of-window rows in order to toggle 54 — the notice they need to act on is precisely the one that has
expired, has not started, or has been switched off. Reading through the view would leave a deactivated
notice unreachable and therefore unrecoverable, and would make the list disagree with the toggle that
acts on it. Reusing the dashboard's read would be a duplicate capability with a wrong answer.

---

## 3. Database alignment

### 3.1 What the database owns, so Java must not restate it

| Concern | Owner |
|---|---|
| Administrator authorisation | `sp_require_admin` — role `ADMIN` **and** status `ACTIVE`, looked up in `users`, never a session flag (a pooled connection could leak one) |
| Disable → revoke every open session + bump `token_version` | `sp_set_user_status` |
| Refuse self-disable | `sp_set_user_status` (`p_actor_id = p_target_user_id AND p_new_status = 'DISABLED'`) |
| Reset-token issue, one-time use, TTL | `sp_admin_send_password_reset` → `sp_create_password_reset_token` |
| Default-category authorisation and BR-06 scope | `sp_admin_upsert_default_category`, `trg_categories_before_insert/update` |
| Announcement window | `ck_ann_window` |
| Tip-template code uniqueness | `uk_tip_template_code` |
| Every audit row | the eight procedures write `admin_audit_log` themselves |
| Aggregate figures | `v_admin_usage_stats`, `v_admin_top_categories` |
| Threshold key allow-list and value ranges | `sp_admin_set_threshold` |

### 3.2 Every write is a `CALL` — the trap stated in full

The application's database account has direct `INSERT`/`UPDATE` grants on every table this module
touches. A `JpaRepository#save`, or any mapped entity's dirty-checking flush, would therefore write
`users`, `categories`, `announcements`, `tip_templates` or `system_settings` **without** calling
`sp_require_admin` and **without** leaving an `admin_audit_log` row — silently converting an
audited, authorised administrative write into an unaudited one. That is why:

- every admin write in `Repository` and `Service` is a `CALL` through a `*ProcedureDao`;
- `CategoryRepository`, `UserRepository` and `SystemSettingRepository` are read-only here
  (`CategoryService` legitimately uses `save` for *personal* rows; UC-20's row belongs to nobody, which
  is what BR-06 guards);
- the projections are records, not entities, so there is no managed instance anyone could set a field
  on and flush.

`Announcement` is the case that shows the discipline working. The plan for this module called for one
mapped entity to read the created id back; the implementation instead projects `AdminAnnouncementRow`
through `createNativeQuery(SQL, Tuple.class)`, because the toggle is served by a procedure and nothing
in the module changes an announcement's content through Hibernate. **No entity was added**, so the
module has no managed instance of any of its five tables. It is the reason
`AdminAnnouncementViewDao`'s javadoc can say "there is no managed instance anyone could set a field on
and flush" and mean it structurally rather than by convention.

### 3.3 A read-back after a CREATE must not use `LAST_INSERT_ID()`

No admin procedure declares an `OUT` parameter, and **each inserts its `admin_audit_log` row after its
target row**. `admin_audit_log.id` is `AUTO_INCREMENT`, so at the moment the `CALL` returns,
`LAST_INSERT_ID()` reports the **audit** row's id, not the created resource's. Nothing in the module
calls it. The read-back keys on a natural identity instead:

- categories → `(user_id IS NULL, type, name)`, guaranteed by `uk_categories_scope_type_name`;
- tip templates → `code`, guaranteed by `uk_tip_template_code`;
- announcements → no unique key exists, so `created_by = :actor AND title = :title AND starts_at =
  :startsAt ORDER BY id DESC LIMIT 1`, inside the same transaction as the write, so no competing insert
  can slip between them.

A test asserts each `201` returns the row that was actually created, cross-checked against the audit
row's `target_id` — which is the assertion that would have caught a `LAST_INSERT_ID()` implementation.

### 3.4 Two behaviours pinned rather than assumed

**`v_admin_top_categories` has no `ORDER BY`.** The DAO adds a total order —
`txnCount DESC, totalAmount DESC, categoryId ASC` — because without the final tie-break two categories
with equal counts and totals could swap between two identical calls and a client diffing them would see
movement that never happened. **No `LIMIT`**: the view's definition is every category, including unused
ones, which is what an administrator reading a usage report needs.

**`sp_admin_send_password_reset` does not verify the target exists.** An unknown id hits `fk_prt_user` →
SQLSTATE 23000. Java loads the account first (it needs the email for the link anyway) and answers `404`;
the FK refusal is classified as a fallback rather than relied on.

### 3.5 The self-disable refusal needs a Java pre-check, and the reason is testable

`sp_set_user_status` raises SQLSTATE 45000 for all three of its refusals — `Account does not exist`,
`Invalid status`, and `An administrator cannot disable their own account` — with no way to tell them
apart except by matching the procedure's prose, which §17 of the master prompt forbids. So
`AdminUserService` checks `targetId.equals(actor.userId()) && requested == DISABLED` **before** calling
and throws `SelfDisableForbiddenException` with no round trip. The surviving 45000 branch then only ever
means "the target does not exist", and it is classified by a pre-read rather than by the message — the
same shape `TransactionService` already uses for "already deleted". The procedure's own check stays as
the guarantee for a hand-run `CALL`.

The guard is **one-sided**, and that is deliberate rather than an oversight: an administrator **may**
re-enable their own account (`ACTIVE` on an already-active account is a permitted no-op). The danger is
locking yourself out, not letting yourself back in. It is stated here so a reviewer does not "fix" it.

### 3.6 The exclusion list for the account response

Selected by `AdminUserViewDao`, published by `AdminUserMapper`, and enforced **structurally** —
`AdminUserRow` has no component for the forbidden columns, so the whole guarantee is visible on one
screen:

- **`password_hash`** — never selected (BR-01).
- **`token_version`** — never selected. It is the entire security meaning of a JWT's `tv` claim;
  publishing it would let an attacker decide whether a stolen token is still live.
- **Not published, for want of a use case:** `monthly_allowance_baseline`, `monthly_savings_goal`
  (VĐ-04), `theme_pref`, `font_scale`, `ai_enabled` (UC-27), `email_verified_at`, `updated_at`.
- **Published:** `id`, `email`, `fullName`, `role`, `status`, `academicYear`, `lastLoginAt`,
  `createdAt`. `lastLoginAt` is justified by UC-23's "active users" notion.

`academicYear` and `lastLoginAt` are `@JsonInclude(NON_NULL)`, so they are **omitted** from an account
that has never set a year or signed in. That omission is the contract, and §5.3 records the test defect
where an assertion contradicted it.

`condition_params` is likewise not published: the schema does not document its meaning and nothing in
this build reads it. Recorded as a follow-up rather than guessed at.

### 3.7 `db/` untouched

`git diff -- db/` shows only the PHASE 0 encryption work approved earlier — the `description`, `note`
and `recurring_rules.description` envelope columns and the `v_desc` widening that follows from them.
**Module 11 added no table, view, procedure, trigger or column**, and the diff contains no `sp_admin*`,
no `v_admin*` and no change to `announcements`, `tip_templates`, `system_settings` or `categories`. The
grep that proves it is in §10.

---

## 4. Implementation

### 4.1 Layering

```
Admin*Controller  →  Admin*Service  →  Admin*ProcedureDao (CALL) / Admin*ViewDao (SELECT)
                                     →  Admin*Mapper     →  DTO
```

Six controllers (one per resource), six services, ten repository classes, five mappers. One line of
`SecurityConfig`:

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

No rule needed changing; only the surrounding comment, which had implied the administrator *login* was
the only route under that prefix. It is declared ahead of the student rules, and that ordering is
load-bearing: an administrator token would otherwise be admitted through a student route.

### 4.2 The refusal classifier

`AdminWriteFailure` (package-private, unit-tested with the real driver exceptions) is the module's
mirror of `BookmarkWriteFailure`, `BudgetWriteFailure` and `CategoryWriteFailure`. It recognises by
**SQLSTATE** (`45000` → `InvalidDataAccessResourceUsageException`, `23000` →
`DataIntegrityViolationException`) and by **constraint name**, walking the cause chain.

| SQLSTATE | Driver exception | Translated to |
|---|---|---|
| `45000` | `InvalidDataAccessResourceUsageException` | The refusal that identifier maps to, after a Java pre-read has eliminated the alternatives |
| `23000` | `DataIntegrityViolationException` | `409` with the most specific code the constraint name implies, else `DATA_CONFLICT` |

Procedure prose is never matched. A unit test asserts that a log-shaped string mentioning a constraint
name is *not* matched, which is the negative that keeps the recogniser honest.

### 4.3 The settings allow-list

`AdminThresholds` is the single place that decides what `PATCH /admin/settings/{key}` accepts: six keys,
each paired with a value shape (`POSITIVE_NUMBER`, or `WHOLE_NUMBER_1_TO_12` for
`insight.spike_baseline_months`). `isAdjustable(String)` answers `GET`'s `adjustable` flag from the same
map, so the flag a client renders and the allow-list the write enforces cannot drift.

### 4.4 What must never leave the server

Enforced by the response records having no component for these: `password_hash`, `token_version`,
`monthly_allowance_baseline`, `monthly_savings_goal`, `condition_params`, `theme_pref`, `font_scale`,
`ai_enabled`, `email_verified_at`, `updated_at`, and the raw reset token. `AdminSecurityIT` scans every
read endpoint's raw JSON recursively for the forbidden names, with a non-triviality guard that plants
`tokenVersion` in a control payload and proves the scan rejects it.

### 4.5 The disclosure position, and why it differs from module 10's

`404` on 47 and 48 does reveal whether an id exists. That is **intended for an administrator**, and it
is the opposite of module 10's deliberate "not yours and does not exist look identical" policy. The
difference is the audience: the bookmarks routes are reachable by any student, so distinguishing the two
would be an enumeration oracle; these are behind `hasRole("ADMIN")`, where the caller is authorised to
know and needs to, because "that id is wrong" and "that account is gone" call for different actions. The
policy is safe only while the role rule holds — which is why that rule has its own parameterised test
rather than being assumed.

---

## 5. Defects found and fixed

### 5.1 Production — `POST /admin/announcements` answered `500` whenever `startsAt` was omitted

**Symptom.** Thirteen tests in `AdminAnnouncementApiIT` failed with
`IllegalStateException: The announcement just published was not readable back.`

**Cause.** `db/01_schema.sql` declares `starts_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`, and
`DATETIME` is stored with no fractional part. The service defaulted a missing start time to
`LocalDateTime.now(APPLICATION_ZONE)`, which carries nanoseconds and is **rounded** on the way into the
column. The read-back matches on `(created_by, title, starts_at)`, so the value held in Java and the
value the row held were never equal and the lookup matched nothing — on every create that omitted the
field.

**Fix.** `AdminAnnouncementService.create` now truncates **both** `startsAt` and `endsAt` to the second
before anything else happens, via `truncateToSecond(LocalDateTime)` using `ChronoUnit.SECONDS`.

**Why truncation and not rounding, and why before the check.** `DATETIME` does not carry the
information either way, and truncation is what `requireValidWindow`'s comparison then agrees with. That
closes a second, latent hole the javadoc now records: a start of `10:00:00.4` and an end of
`10:00:00.9` are ordered in Java but are **one instant once stored**, so `ck_ann_window` would have
refused a pair the service had just approved — a `500`, not a field error. Truncating first makes the
service's check and the constraint see the same pair and removes the disagreement.

### 5.2 Production — `GET`/`POST /admin/tip-templates` answered `500` on every row

**Symptom.** Thirteen tests in `AdminTipTemplateApiIT` failed with
`ClassCastException: class java.lang.Boolean cannot be cast to class java.lang.Number` at
`AdminTipTemplateViewDao.java:145`, reached from `findByCode`.

**Cause.** MySQL Connector/J's `tinyInt1isBit` connection property is on by default, so a `TINYINT(1)`
column arrives as a `Boolean`, not a `Number`. Both admin view DAOs read `is_active` as `((Number)
row.get(...)).intValue() != 0` — code that reads naturally from the single digit the column holds and
is wrong on the default connection string.

**Fix.** A new package-private `AdminTupleValues` provides `toBoolean(Object)` — pattern-matching
`Boolean` first, then `Number` — and `toLocalDateTime(Timestamp)`; the two identical private copies were
deleted and both DAOs call the shared helper.

**The finding worth recording is not the cast, it is why the cast survived.** The helper was a *private
duplicate* in two classes. Both copies were wrong in the same way, both were written in the same
sitting, and neither was read by the other's author as a second opinion. Duplication did not just
double the fix; it removed the chance that writing the second one would reveal the first was wrong.
`AdminTupleValues`' javadoc says exactly this, so a later reader does not re-inline it as "only two
callers".

### 5.3 Test — three assertions that contradicted their own comments

Three test defects were found and fixed, all the same species as module 10's §5.1 (an assertion
disagreeing with the javadoc directly above it):

| Test | Defect | Resolution |
|---|---|---|
| `AdminUserApiIT#theListPublishesExactlyTheDocumentedFields` | Expected all eight documented field names on a freshly registered account, whose comment said "the nullable fields are absent or null" — they are `@JsonInclude(NON_NULL)`, so two names are genuinely **absent** | Compare against the documented eight minus the two omitted-until-set, assert those two are absent, and add a companion test that sets them and gets all eight |
| `AdminSecurityIT#noResponsePublishesAForbiddenKey` | Called `ok(...)` on `POST /admin/tip-templates`, which is a `201`, so the helper reported the correct status as the failure. Masked until §5.1 was fixed, because the announcement listing failed first | Use `created(...)`, the helper that asserts `201` |
| `AdminStatsApiIT#noInsightHasEverBeenGenerated` | Asserted `insights` is empty and called the count "the module 12 lock made observable". The container loads `db/merged/campuscoin_full.sql`, which appends `db/06_demo.sql`, whose three `CALL sp_generate_monthly_insight` statements leave **3 rows** | Rewritten: the endpoint's figure must equal the table's own count (falsifiable, and not a hard-coded zero), and no administrative write may move it |

### 5.4 Test — a premise that the code contradicts, resolved in the code's favour

`AdminSecurityIT#promotionDoesNotWidenAnExistingToken` asserted that a student promoted to `ADMIN`
*after* signing in is still refused, on the stated premise that "the JWT's role claim is what the filter
chain reads".

**The premise is false.** `JwtService` mints a `role` claim and decodes it into `JwtClaims`, but
`SessionService.authenticateToken` builds the `AuthenticatedUser` from `user.getRole()` — the live
`users` row, re-read on every request — and compares only the token's `tv` claim against the row.
`claims.role()` has no reader in the codebase. So a promotion **does** take effect on a token already in
circulation.

**Resolution: the test was corrected, not the code**, and the test now asserts the observed behaviour
with the reasoning recorded. The behaviour is coherent with the rest of the model: the row is already
the authority for `status` and `token_version`, both re-read per request for exactly this reason, and
reading the role from the claim as well would make the role the one account attribute a stale token
could assert about itself. The live row is the safe direction in **both** directions — a promotion is
effective immediately, and so is a demotion, which is the more important half and which
`anAdministratorDisabledAfterSigningInIsRefusedImmediately` already pins for `status`. The rewritten
test asserts the student's pre-promotion token is refused *before* the promotion (the control), then
reaches the route after it, so a 200 cannot come from a route that never checked.

This was flagged as a genuine production-behaviour question rather than silently adjusted: the option of
making the claim authoritative was considered and rejected, because the claim is the only place a
request would be asserting its own authority.

### 5.5 Other test defects fixed in the same pass

`AdminStatsApiIT#disablingAStudentMovesTheCounts` called the `patched(...)` helper (HTTP `PATCH`) against
`/admin/users/{id}/status`, which is a `POST`; the dispatcher answered `400`. `AdminUserApiIT` already
used `ok(HttpMethod.POST, ...)` for the same route, so the fix is the call the other class was already
making. And `AdminSecurityIT#theSweepCoversEveryDeclaredRoute` compared concrete paths
(`/999999/status`) against the templated declaration (`/{id}/status`), which can never be equal; the
declaration is now instantiated with the same placeholder values before comparison.

---

## 6. Tests

### 6.1 Per class

| Class | Tests | Pins |
|---|---|---|
| `AdminSecurityIT` | 37 | The cross-cutting guarantees, asserted once over all 16 routes |
| `AdminUserApiIT` | 21 | 46–48 |
| `AdminCategoryApiIT` | 20 | 49–51 |
| `AdminAnnouncementApiIT` | 17 | 52–54 |
| `AdminTipTemplateApiIT` | 17 | 55–57 |
| `AdminSettingsApiIT` | 28 | 58–59, including the six-key allow-list |
| `AdminStatsApiIT` | 11 | 60–61 |
| `AdminWriteFailureTest` | 16 | The classifier, unit-level, with the real driver exception shapes |
| `OpenApiContractIT` | +1 (13 → 14) | The document lists all 61 operations on 43 paths at the time module 11 was written — 76 on 56 now that module 12 is built; the 14th test pins the new response schemas |
| **Total new** | **168** | Suite 568 → **736** |

All integration classes extend `AbstractMySqlIntegrationTest`, so the real procedures, triggers and
views run against MySQL 8 in Testcontainers; `AdminWriteFailureTest` is a plain unit test of a
classifier whose duplicate-code branch the HTTP path cannot deterministically reach.

### 6.2 The load-bearing assertions

- **Every route refuses a student and admits an administrator** — parameterised over all 16 method+path
  pairs: a student token → `403 ACCESS_DENIED`, no token → `401 UNAUTHENTICATED`, with a valid body on
  every request so a `400` cannot be mistaken for the right answer.
- **BR-06 end to end** — a token issued to an administrator disabled *after* login is refused, because
  the session row is revoked, `token_version` no longer matches, and `users.status` is checked: three
  independent reasons.
- **47's postcondition in one test** — `token_version` delta `+1`, and every previously open session has
  `revoked_at IS NOT NULL` with `revoked_reason = 'ADMIN_DISABLE'`; then the student's pre-disable token
  gets `401`.
- **Self-disable refusal** — `409 SELF_DISABLE_FORBIDDEN`, with the caller still `ACTIVE`, its
  `token_version` unchanged, its session not revoked, and **no audit row**.
- **Every audit action string** — all ten (`USER_DISABLED`, `USER_ENABLED`, `PASSWORD_RESET_SENT`,
  `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `ANNOUNCEMENT_CREATED`, `ANNOUNCEMENT_ACTIVATED`,
  `ANNOUNCEMENT_DEACTIVATED`, `TIP_TEMPLATE_SAVED`, `SETTING_CHANGED`), checking `admin_user_id`,
  `target_entity`, `target_id` and `detail`.
- **No sensitive field in any response** — a recursive key scan over every read endpoint, with the
  non-triviality control described in §4.4.
- **The exact field set** of each response, as a literal list, so a later "just add the last login IP"
  fails a test.
- **All six threshold keys change**, each verified in `system_settings` and by `SETTING_CHANGED`; a key
  that exists but is not adjustable → `409 THRESHOLD_NOT_ADJUSTABLE`; a bad value shape → `400`.
- **Read-back correctness after CREATE**, cross-checked against the audit row's `target_id` — the
  `LAST_INSERT_ID()` trap of §3.3.
- **Duplicate template code → `409`**; a `PATCH` carrying a different `code` → `409` with the stored
  code unchanged.
- **48 on an unknown id → `404`** with no `password_reset_tokens` row and no audit row; the positive case
  stores a 64-hex `token_hash`, returns a body containing **no** token, and delivers the raw token only
  through the notifier port.
- **`v_admin_top_categories` ordering stability** — two identical `GET`s return the same order, and a
  zero-transaction category still appears.

### 6.3 Test-design notes

**Deliberately written out rather than derived.** `AdminSecurityIT`'s route map and
`AbstractAdminApiIT.MODULE_11_ROUTES` are both written by hand, in two *different* spellings — concrete
paths in the first because the sweep has to request one, templated in the second because that is the
inventory's shape. Asserting the two agree is what stops the file from quietly testing fifteen routes
after somebody adds a sixteenth, and the templated-versus-concrete mismatch that this revealed is §5.5.

**Delta-based assertions, because the container is shared.** `AbstractMySqlIntegrationTest` starts one
static `MySQLContainer` for the whole JVM run and seeds it from the project's own merged script. Rows are
only ever added, so `token_version`, audit counts and session counts are all asserted as deltas or
scoped to a specific row id. The settings test that had to change a shared value restores it in a
`finally` and then asserts the restore, so it cannot contaminate the module 6 or module 9 suites that
read the same rows.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every route and field traces to a UC, BR or VĐ (§11). Nothing is published that no use case asks for; the exclusions are named in §3.6 rather than merely absent |
| B. Duplicate / missing endpoints | None. Sixteen operations, none a restatement of another — the four non-obvious choices are argued in §2.1 and §2.2, and `OpenApiContractIT#noEndpointIsDuplicated` carries an explicit `doesNotContain` block of 25 rejected aliases |
| C. Layering | Controller → Service → DAO → MySQL. No DAO is called from a controller; no entity is returned; `Admin*Row` records stay in the repository package |
| D. Validation placement | In the DTOs for shape, and in the services for the four rules bean validation cannot express: the announcement window (§3.1), self-disable (§3.5), the immutable template code (§3.4's rationale), and the threshold value shape (§4.3). Every refusal names its field |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`. **Four codes were added**, all `409`: `SELF_DISABLE_FORBIDDEN`, `THRESHOLD_NOT_ADJUSTABLE`, `TIP_TEMPLATE_CODE_TAKEN`, `TIP_TEMPLATE_CODE_IMMUTABLE` — each because the remedy is specific and a generic conflict would tell the client nothing |
| F. Transaction boundaries | Reads `readOnly = true`; every `CALL` is plain `@Transactional`, never read-only, because MySQL refuses any `CALL` on a read-only connection |
| G. N+1 and fetch strategy | One query per read, none in a loop. The user list is one projection; `findOne`/`findByCode` are single rows; `findCreatedBy` is one row inside the writing transaction. `open-in-view: false` |
| H. Locking | **No explicit lock is taken.** The toggles and the upsert are last-write-wins field writes, which is what the caller asked for. Uniqueness that *is* decided from current state (template code, category name) is enforced by the database's unique keys and pre-checked in Java for the error message, not guarded by a lock |
| I. Schema coupling | `ddl-auto: validate` holds for all 736 tests at the module-11 checkpoint (1090 now that module 12 is built) and every integration context starts under it. Native queries projected by alias; no entity in this module at all, so no mapping to drift |
| J. Security | [`docs/api/administration.md` §9](../api/administration.md#9-security-properties): one role rule, authority from the live row, every write through `sp_require_admin` |
| K. Sensitive output | `Admin*Mapper` is the single gate per resource, and the *records* have no component for the forbidden fields, so the guarantee is structural rather than a filter that a later edit could drop |
| L. Logging | Ids and enum values only: actor, target id, audience, key name. No email body, no token, no ciphertext, no driver message. A refusal is logged as `reference=` plus an identifier, never the id of a row the caller did not name |
| M. Dead code | `AdminTupleValues`' numeric branch is unreachable on the default connection string but is deliberate and documented (§5.2); `AdminThresholds`' second value shape is exercised by `insight.spike_baseline_months`; `AdminWriteFailure`'s recognisers are all called and all tested |
| N. Naming | `Admin*ViewDao` / `Admin*ProcedureDao` by function; `Admin*Mapper` mirrors the other mappers; `AdminWriteFailure` mirrors `BookmarkWriteFailure`; `AbstractAdminApiIT` mirrors `AbstractBookmarksApiIT` |
| O. Documentation | [`docs/api/administration.md`](../api/administration.md), inventory rows 46–61, this report, and the manual procedure |
| P. Tests through HTTP | 151 of the 168 are HTTP integration tests; 16 are unit tests of the classifier, and 1 is the contract test. Every fixture precondition is built through a public route or the same procedure a real generation uses |
| Q. Empty state | Covered: a brand-new account appears with two fields omitted; a category with no transactions still ranks with zeroes; a `404` for an unknown id on 47/48/51/54/57; `POST` with no announcements returns `[]`; an idempotent status re-send is accepted and audited |
| R. Determinism | `v_admin_top_categories` is given an explicit total order including the id tie-break (§3.4); the user list orders by creation; tip templates by priority then id. Two identical calls returning the same order is asserted |
| S. Idempotency | A re-sent `POST .../{id}/status` is accepted and leaves a truthful audit row; `PATCH` re-sending the current value is accepted. Both are stated rather than left as surprises |
| T. Restart behaviour | No state in memory. Every read is a query; nothing about a caller is cached across requests, which is what makes §4.5's live-row authority work |
| U. Configurability | The six adjustable keys are the module's configuration surface and nothing else was invented. The allow-list is a `Map` constant, not a setting, because widening it is a code change with a test |
| V. Language | All artifacts English |

**Two production defects were found by this pass** (§5.1, §5.2), along with three test defects (§5.3)
and one test premise that the code contradicted (§5.4). No criterion failed. The two production defects
are the substantive result of the module: neither was visible from reading the code, and both required
the suite to run against a real MySQL with the project's own schema.

---

## 8. Phase 10 — adversarial review

| Attack | Result |
|---|---|
| A student calls any of the 16 routes | `403 ACCESS_DENIED`, asserted by a parameterised test over all 16 with a valid body on each |
| An anonymous caller calls any of them | `401 UNAUTHENTICATED`, asserted the same way |
| An administrator disables their own account, then re-enables it | Disable refused `409` with no audit row; re-enable **accepted** — the guard is one-sided by design (§3.5) |
| A disabled administrator's token is replayed | `401` — three independent reasons (§6.2) |
| A student is promoted while holding a token | The token reaches the admin routes, because authority is the live row (§5.4). Recorded as intended, with the demotion direction pinned too |
| A student's token is used after a `token_version` bump | `401` |
| A `PATCH` sends a different tip-template `code` | `409 TIP_TEMPLATE_CODE_IMMUTABLE`, and the stored code is asserted unchanged |
| A `POST` reuses a tip-template `code` | `409 TIP_TEMPLATE_CODE_TAKEN`, classified from `uk_tip_template_code` by name, not by SQLSTATE alone |
| A `PATCH` sends a threshold key that exists but is not adjustable | `409 THRESHOLD_NOT_ADJUSTABLE` |
| A `PATCH` sends a malformed threshold value | `400` with a field error on `value` |
| An announcement is created with `endsAt` before `startsAt`, or equal to it | `400` on `endsAt`, not a `500` — Java checks before the constraint sees it (§5.1) |
| An announcement is created with a start time and a start time one nanosecond later | Both store the same instant, so the read-back finds the right row rather than the wrong one (§5.1) |
| A response is scanned for `passwordHash`, `tokenVersion`, `conditionParams`, the VĐ-04 figures | Absent from every payload; the scan is proven non-vacuous by a planted control |
| An id from another table is sent to 47/48 | `404`, with no `password_reset_tokens` row and no audit row |
| The reset response is inspected for the raw token | A `message` field and nothing else; the token exists only in `password_reset_tokens.token_hash` (64 hex) and the notifier's message |
| Two identical rankings are diffed | Identical order, including the id tie-break (§3.4) |
| A category with no transactions is looked for in the ranking | Present, with zeroes |
| The self-disable is attempted through a hand-run `CALL` | Still refused — `sp_set_user_status`'s own check remains the authority (§3.5) |
| An admin write is attempted whose procedure refuses it | Classified by SQLSTATE and constraint name; the driver's message is never surfaced and never matched |
| A test leaves the shared container's thresholds changed | Not possible: the one test that changes one restores it in a `finally` and asserts the restore (§6.3) |

No attack produced a `500`, a leak, an unaudited write or a duplicate capability.

---

## 9. Traceability

| Requirement | Where it is implemented | Where it is pinned |
|---|---|---|
| UC-20 — manage default categories | 49–51, `sp_admin_upsert_default_category`, `trg_categories_before_*` | `AdminCategoryApiIT` (20) |
| UC-21 B1–B2 — announcements | 52–54, `sp_admin_create_announcement`, `sp_admin_set_announcement_active` | `AdminAnnouncementApiIT` (17) |
| UC-21 B3–B4 — tip templates | 55–57, `sp_admin_upsert_tip_template` | `AdminTipTemplateApiIT` (17) |
| UC-22 B1 — list accounts | 46, `AdminUserViewDao` | `AdminUserApiIT` (21) |
| UC-22 B3 — disable / enable | 47, `sp_set_user_status` | `AdminUserApiIT`, `AdminSecurityIT` |
| UC-22 B4 — send a reset link | 48, `sp_admin_send_password_reset` | `AdminUserApiIT` |
| UC-22 B5 — audit every administrative write | the eight procedures' own `admin_audit_log` inserts | `AdminCategoryApiIT`, `AdminUserApiIT`, `AdminSettingsApiIT` |
| UC-23 — usage statistics and the ranking | 60–61, `v_admin_usage_stats`, `v_admin_top_categories` | `AdminStatsApiIT` (11) |
| VĐ-05 — adjustable thresholds | 58–59, `sp_admin_set_threshold`, `AdminThresholds` | `AdminSettingsApiIT` (28) |
| BR-01 — passwords are never exposed | `AdminUserViewDao` never selects `password_hash` | `AdminSecurityIT`, `AdminUserApiIT` |
| BR-03 — disable means disable | `sp_set_user_status`, `SessionService` | `AdminSecurityIT` |
| BR-06 — role and scope rules | `trg_categories_before_*`, `sp_require_admin` | `AdminCategoryApiIT`, `AdminSecurityIT` |
| BR-07 — retirement, not deletion | `PATCH { isActive: false }`, no `DELETE` anywhere | `AdminCategoryApiIT`, `AdminAnnouncementApiIT` |
| BR-15 — spike detection stays tunable | 59 exposes `insight.spike_threshold_pct` | `AdminSettingsApiIT`, `TipsRuleCoverageIT` |
| VĐ-04 — private financial figures stay private | not selected, not mapped, not published | `AdminSecurityIT`, `AdminUserApiIT` |
| UC-05 E1 — students are refused | `SecurityConfig` role rule | `AdminSecurityIT` (parameterised over 16) |
| §13 — no duplicate capabilities | the sixteen are distinct operations | `OpenApiContractIT#noEndpointIsDuplicated` |
| §26 — every route documented | inventory rows 46–61 | `OpenApiContractIT` (14) |
| OB-005 — no unaudited administrative write | every write is a `CALL` (§3.2) | `AdminSecurityIT`, and the design in `AdminAnnouncementService` |
| OB-013 — the plaintext aggregates | 60–61 serve them; no per-student amount anywhere | `AdminStatsApiIT` |

---

## 10. Definition of done

| Requirement | Status |
|---|---|
| Every operation implements a UC or BR that asks for it | Yes (§11); nothing speculative |
| No endpoint duplicates another | Yes — `OpenApiContractIT#noEndpointIsDuplicated` asserts a 25-alias `doesNotContain` block, and the four close calls are argued in §2.1–§2.2 |
| No implementation logic duplicated | The one duplication is stated, not hidden: `AdminRequestContext` restates `auth.controller.ClientAddress`'s five lines. `ClientAddress` is package-private and its three call sites are all in the auth package, and neither widening it to `public` (exposing an auth-internal helper for an unrelated reason) nor reaching across packages was acceptable. The cost is that the "no proxy header" decision now lives in two places, and both javadocs say so |
| `db/` unchanged by this module | Yes. `git diff --name-only -- db/` lists only the three PHASE 0 encryption files, and `git diff -- db/ \| grep sp_admin\|v_admin` is empty (§3.7) |
| `ddl-auto: validate` holds | Yes — every integration context starts under it, and this module adds no entity, so there is nothing new to validate |
| Full suite green | Yes — **736 tests, 0 failures, 0 errors, 0 skipped** at this module's checkpoint (1090 with module 12) |
| Every route in the inventory | Yes — 46–61 in `docs/api/API_INVENTORY.md`, with the "Total: 16 endpoints" no-duplicates paragraph |
| API document written | Yes — `docs/api/administration.md` |
| Manual test procedure written | Yes — `docs/testing/manual/MODULE_11_MANUAL_TEST.md`, §29 placeholders only |
| No plaintext secret anywhere | Yes — no key, token or hash is logged or published; the reset token exists in the response only as a message |
| Module 12 not built | Yes — no route, no key, no `insights` read path. `totalInsightsGenerated` is served because the view defines it, not because this module generates anything (§3, and `AdminStatsApiIT`) |

---

## 11. Deferred / blocked

| Item | Status | Note |
|---|---|---|
| **OB-005** — database administrators bypass `sp_require_admin` | **READY FOR REVIEW**, unchanged | This module does not change the blocker, and it is the module that would have broken it: decision 54's "`isActive` only" is what keeps every administrative write on a procedure. The API layer now has no unaudited administrative write path at all, so the blocker is narrower than it was — it now describes only a person with direct database credentials, not the application |
| **OB-013** — amounts are deliberately not encrypted | **OPEN**, unchanged | 60 and 61 serve `SUM(amount)` over plaintext, which is the exposure this blocker records. Module 11's contribution is to serve it and to state that **no route returns one student's amounts**: every money figure here is a sum over many students. Module 11 is now added to OB-013's module list |
| `condition_params` on tip templates | Follow-up | The column is a JSON blob the schema does not document and nothing in this build reads. Not exposed; recorded rather than guessed at |
| Searching and filtering the user list | Follow-up | UC-22 defines neither, and a query parameter invented without a requirement is a duplicate capability waiting to happen |
| Reading `admin_audit_log` | Follow-up | UC-22 B5 requires writing an audit row. A read surface needs a use case, a view and a disclosure decision, none of which exist |
| An administrator updating an announcement's content | By design | Content is create-once (§2.1). This is a decision, not a gap |
| Real-time or paginated statistics | Follow-up | UC-23 asks for figures, not a stream, and the views compute on read |

---

## Related documentation

- [`docs/api/administration.md`](../api/administration.md) — the request and response contract for
  endpoints 46–61.
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — rows 46–61 and the no-duplicates argument.
- [`docs/testing/manual/MODULE_11_MANUAL_TEST.md`](../testing/manual/MODULE_11_MANUAL_TEST.md) — the
  manual test procedure.
- [`docs/modules/MODULE_10_BOOKMARKS.md`](MODULE_10_BOOKMARKS.md) — the previous module, and the other
  half of the disclosure-policy contrast in §4.5.
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — OB-005 and OB-013, both referenced above.
- [`docs/SECURITY.md`](../SECURITY.md) — the security model §4.4 and §4.5 summarise.
- [`db/DB_DESIGN.md`](../../db/DB_DESIGN.md) — the procedures and views this module is wired to, and
  §4.4's note on why MySQL cannot enforce the administrator gate by itself.
