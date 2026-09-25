# Module 2 — Profile & Preferences (UC-04, UC-27)

**Status:** DONE

A student reads and edits their own profile and display preferences. Three endpoints, no
identifier in any URL, one new exception type, and a lost-update hazard closed on the `users`
table. This report is the module record: what was built, what was tested, what the two reviews
found, and the traceability from requirement to test.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-04 | View and edit full name, academic year, monthly allowance baseline, monthly savings goal |
| UC-27 | View and edit theme preference and font scale |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| Email change | Not part of UC-04, and changing a sign-in identifier needs re-verification that no UC describes |
| Password change | UC-03 owns it; there is no change-password endpoint in the inventory |
| Role, account status | Administrator operations (UC-20…UC-23, module 11). A student editing their own role is an escalation |
| `ai_enabled` | The column exists, but UC-27 is appearance and text size. The AI toggle belongs to UC-08/UC-17 |
| Currency | VĐ-08 makes it one application-wide setting, not a per-student preference |

## 2. Endpoints

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 8 | GET | `/api/v1/profile/me` | UC-04, UC-27 | Bearer | `200` profile |
| 9 | PATCH | `/api/v1/profile/me` | UC-04 | Bearer | `200` profile |
| 10 | PATCH | `/api/v1/profile/me/preferences` | UC-27 | Bearer | `200` profile |

Both writes are `PATCH`, not `PUT`: a client sends only the fields it changed. Neither endpoint
takes a user id, which is what makes BR-02 structural here rather than a check that could be
forgotten — there is no identifier to tamper with. The account is always the one in the verified
bearer token.

`GET /me` serves both use cases because UC-04 and UC-27 return the same record.

## 3. Database alignment

The schema has no separate profile table: UC-04 and UC-27 columns live on `users`. `User` is
therefore mapped as the row it already was, rather than adding a second entity on the same table
(two persistence contexts able to write one row). Five columns were added to the mapping:
`academic_year`, `monthly_allowance_baseline`, `monthly_savings_goal`, `theme_pref`, `font_scale`.

No schema object was changed. Verified: every file under `db/` is dated 2026-09-24, none modified
during this module, `git status db/` shows no tracked change, and `ddl-auto: validate` is still the
setting in both `application-dev.yml` and `application-prod.yml`.

**Java owns the write.** No procedure or trigger on `users` writes a profile column — the only
database objects that read them are `v_dashboard_summary` and `sp_generate_tips`. So performing
the write is the application's job and duplicates no database logic (§5).

**What the database still owns, and Java does not repeat:** `ck_users_money` rejects a negative
amount and the `theme_pref` / `font_scale` ENUMs reject an unknown member. These are enforced twice
on purpose — the DTO validates first so a caller gets a per-field error, and the column remains the
authority. Both are asserted directly at the database in the tests, so the guarantee is the
constraint's, not the DTO's.

## 4. Implementation

| File | Role |
|---|---|
| `profile/controller/ProfileController.java` | Three endpoints, `@AuthenticationPrincipal` identity, full OpenAPI annotations |
| `profile/service/ProfileService.java` | Which columns a request may touch, and whose row |
| `profile/mapper/ProfileMapper.java` | The one place deciding which columns may leave the server |
| `profile/dto/ProfileResponse.java` | 9 fields out |
| `profile/dto/UpdateProfileRequest.java` | 4 optional fields in |
| `profile/dto/UpdatePreferencesRequest.java` | 2 optional enum fields in |
| `auth/entity/User.java` | 5 columns added, `@DynamicUpdate`, writable setters for UC-04/UC-27 only |
| `auth/entity/ThemePreference.java`, `FontScale.java` | Enums placed in `auth` so the dependency stays one-way |
| `common/exception/NotFoundException.java` | A missing caller row, with a message that does not distinguish "no such row" from "not yours" |

**Partial-update semantics.** An absent field and an explicit `null` both mean "unchanged". The one
asymmetry is `academicYear`: it is the only nullable column of the four, so it needs a way to be
unset, and an empty string does that. The other three have no empty state, so `""` there is a
validation error rather than a silent clear. Documented in `docs/api/profile.md` §7.

**Transactions.** `getProfile` is `readOnly = true`; the two writes are `@Transactional`. Not
annotated blindly — read-only is the honest signal for a method that writes nothing.

## 5. Defects found and fixed

Four real defects, each caught by a test I wrote to try to break the module, not by a report.

### 5.1 Jackson read a JSON number as an enum ordinal

`{"fontScale": 2}` was silently accepted as `LARGE`. The contract is the member name the column
stores; a number is a second encoding whose meaning depends on the order of the Java constants, so
reordering an enum would silently change what a stored client payload means. Fixed with
`spring.jackson.deserialization.fail-on-numbers-for-enums: true` in `application.yml`. The fix
applies to every enum in the API, present and future.

### 5.2 Lost update on `users` (BR-03 regression risk)

Hibernate's default UPDATE rewrites **every** mapped column. A profile edit would therefore write
back the `status` and `token_version` values read at the start of the request. If a password reset
or an administrator disable bumped `token_version` in between, that flush would silently undo the
revocation and the revoked tokens would keep working. Fixed with `@DynamicUpdate` on `User`, which
restricts the UPDATE to changed columns; the generated SQL now names only the edited columns.
`@DynamicInsert` is deliberately **not** applied — every column must be present on INSERT, because
Hibernate names them all and the schema defaults cannot be relied on otherwise. This was found by
a persistence-level test with the concurrent bump on a separate connection, because the token
filter rejects a revoked token before the controller runs and the window cannot be reached over
HTTP.

### 5.3 Trim vs minimum length on `fullName`

The service trims before storing, so a plain `@Size(min = 2)` let `" A "` through and then wrote
the one-character name `"A"`, shorter than the documented minimum. Fixed with a pattern that
measures the length between a non-space at each end: `^\s*\S.{0,118}\S\s*$`.

### 5.4 Trim vs maximum length on `academicYear`

The same gap in the other direction, found during the adversarial review. `docs/api/profile.md`
promises the 30-character limit applies *after trimming*, but `@Size(max = 30)` measures the raw
input, so `"  Year 3  "` padding that trims inside the column was rejected. Fixed with the same
pattern shape: `^\s*.{0,30}\s*$`, which also permits the empty string that clears the field.

### 5.5 Startup logged a generated security password

Not a profile defect, but found while starting the API to verify Swagger parity, and fixed here
because it is a §8 violation. Spring Boot's `UserDetailsServiceAutoConfiguration` created an
unused in-memory user and logged `Using generated security password: 8f13…` at startup. The account
could never authenticate (no HTTP Basic or form login is enabled), so it was not a vulnerability —
but a credential-shaped string in the application log is exactly what §8 keeps out.
`CampusCoinApplication` now excludes the auto-configuration. A test asserts the exclusion holds.

### 5.6 Test-harness limitation, fixed rather than worked around

`TestRestTemplate` could not issue `PATCH`: the JDK `HttpURLConnection` raises
`ProtocolException: Invalid HTTP method: PATCH`, and `TestRestTemplate` falls back to that client
when no Apache client is present. Adding `httpclient5` in test scope lets the harness speak the
documented method instead of contorting the API to fit a testing limitation.

### 5.7 An administrator's token could reach the student profile endpoints

Found in the PHASE B cross-module audit (§6 FLOW D). `SecurityConfig` listed three student-facing
paths with an explicit `hasRole("STUDENT")` rule — `/categories/**`, `/transactions/**` and
`/recurring-rules/**` — and then fell through to a `/api/**` catch-all that admitted **any**
authenticated caller. `/api/v1/profile/**` was not in the three, so it was governed only by the
catch-all: an administrator's token passed the filter chain and reached the controller.

Nothing leaked, because the service derives identity from the token rather than the request and an
administrator can only read or write **their own** row — so the effect was an administrator being able
to edit their own profile and preferences through a student-facing route, not a cross-user read. But
it is a role-boundary defect all the same, and it is the same shape each of the three existing rules
closes: a path that is specified as a student's own is reachable by a role that was never meant to
have it.

Fixed by adding the fourth rule, explicitly rather than by tightening the catch-all:

```java
// UC-04 and UC-27 are a student's own profile and preferences. There is no
// administrator route to these either: UC-22's "edit a user" is a different
// operation with its own contract under /api/v1/admin/**. The rule is stated
// explicitly rather than left to the /api/** catch-all below, because that
// catch-all admits any authenticated caller - so without this line an
// administrator token would reach the student profile endpoints, which is the
// same leak the three role rules below each close.
.requestMatchers("/api/v1/profile/**").hasRole("STUDENT")
```

The class javadoc's role-rule bullet now names all four paths instead of three, so the next module
adding a student route has the complete list in front of it rather than an example of one.

`anAdministratorTokenIsRefused` asserts `403` on all three profile endpoints. UC-22's own
administrator-facing user management is module 11 and uses `/api/v1/admin/**`, which is a different
operation with its own contract — this rule does not pre-empt it.

## 6. Tests

`ProfileApiIT` — 31 tests over HTTP → security filter → controller → service → repository → **real
MySQL 8** (Testcontainers, loading `db/merged/campuscoin_full.sql`). The development database is
never touched.

| Category | Tests |
|---|---|
| Happy path (read) | `newStudentProfileUsesTheSchemaDefaults`, `profileResponseExposesNoSensitiveFields` |
| Happy path (UC-04 write) | `profileUpdatePersistsEveryField`, `omittedFieldsAreLeftAlone`, `emptyAcademicYearClearsTheValue`, `zeroMoneyValuesAreAccepted` |
| Happy path (UC-27 write) | `preferencesAreUpdated`, `omittedPreferenceIsLeftAlone`, `everyEnumMemberIsAccepted`, `emptyPreferenceBodyIsAcceptedAndChangesNothing` |
| Validation | `invalidProfileFieldsAreReportedPerField`, `unknownPreferenceValueIsReportedPerField`, `malformedBodyIsRejectedWithoutLeakingInternals` |
| Boundary values | `boundaryLengthsAreAccepted`, `nameLengthBoundaryMatchesTheColumn`, `whitespaceIsTrimmedBeforeTheLengthIsJudged`, `moneyBeyondTwoDecimalsIsRejected` |
| Unauthenticated | `profileEndpointsRejectAnonymousCallers`, `malformedTokensAreRefused` |
| Revoked / disabled | `revokedSessionIsRefused`, `disabledAccountIsRefused` |
| Ownership (BR-02) | `profileUpdateTouchesOnlyTheCallersRow` |
| Mass assignment (§7.5) | `profileRequestCannotEscalatePrivileges` |
| Rollback | `rejectedUpdateWritesNothing` |
| Transaction atomicity | `savingAProfileChangeWritesOnlyTheChangedColumn` |
| Duplicate / idempotency | `repeatedIdenticalUpdatesAreIdempotent` |
| Encoding | `jsonNumbersAreNeverResolvedToEnumPositions` |
| DB constraint behaviour | `databaseCheckRejectsNegativeMoney`, `databaseRejectsUnknownTheme` |
| Concurrency | `concurrentEditsToDifferentColumnsAreBothKept` — two parallel `PATCH`es, one changing `fullName` and one `academicYear`, both `200`, both readable in the row afterwards |
| Authorization | `anAdministratorTokenIsRefused` — an administrator's token gets `403` on `GET /profile/me`, `PATCH /profile/me` and `PATCH /profile/me/preferences` |

`OpenApiContractIT` — 5 tests kept from this module and reused by later ones. Reads the live
`/api-docs` output and asserts the endpoint set equals the inventory, no path is duplicated, public
endpoints are documented as public and the rest require `bearerAuth`, no **response** schema exposes
a password or internal token, and the profile schemas have the documented shape (`field`, never
`path`). Verified to fail on drift by temporarily adding an undocumented field to
`UpdateProfileRequest`, then reverting.

`SecurityHardeningIT` gained `noDefaultInMemoryUserIsProvisioned` (§5.5). The class as a whole also
re-verifies the module-1 guarantees that profile responses depend on.

**Result: 92 tests, 0 failures** at the time this module was closed. The PHASE B audit added
`concurrentEditsToDifferentColumnsAreBothKept` and `anAdministratorTokenIsRefused`, taking the class to
31; the full M1–M5 suite is now **315 tests, 0 failures**.

## 7. Phase 9 — first review

Reviewed as if it were another developer's pull request, against criteria A–V.

| # | Criterion | Finding |
|---|---|---|
| A | SRS compliance | No SRS on disk (OB-001); scope derived from the schema and the module order |
| B | UC completeness | UC-04's four fields and UC-27's two are all covered, with the exclusions in §1 justified |
| C | BR compliance | BR-02 structural (no identifier in any request); BR-03 preserved by `@DynamicUpdate`; VĐ-04/08 respected |
| D | Database alignment | No schema change; `validate` still passes; the constraint and ENUM are the database's authority |
| E | API correctness | Status codes, bodies and field names match `docs/api/profile.md` and `/api-docs` |
| F | API duplication | No alias route, no second read endpoint, no `PUT` |
| G | Validation | Per-field `field` errors; boundaries match the column widths |
| H | Authorization | Bearer required; no anonymous path |
| I | Ownership | No user id accepted anywhere; asserted by test |
| J | Security | No security column leaves; no mass assignment; enum ordinals rejected |
| K | Transaction boundaries | Read-only where nothing is written; write methods transactional |
| L | Error handling | `NotFoundException` message does not distinguish "missing" from "not yours" |
| M | Test coverage | All §13 phase-5 categories present, including concurrency — `savingAProfileChangeWritesOnlyTheChangedColumn` drives an out-of-band `token_version` bump on a second connection (see §8.13) |
| N | Swagger | Matches implementation; now machine-checked |
| O | Documentation | `profile.md` complete; §7 partial-update table matches the service |
| P | Frontend integration | §9 of `profile.md` opens with the current `user.model.ts` and a rename table |
| Q | Naming | Feature-first under `com.campuscoin.profile`, consistent with `auth` |
| R | Dead code | No unused imports after the `@Size` removal; no scaffold left |
| S | Unnecessary abstraction | One mapper, three DTOs, one service — no speculative layers |
| T | Scope creep | Nothing beyond UC-04/UC-27 was built |
| U | English consistency | All artifacts English (§2) |
| V | Regression risk | Full suite green; module-1 guarantees re-asserted |

**Fixed as a result of this review:** the stale class Javadoc on `UpdateProfileRequest` (it
described an earlier draft in which `null` cleared `academicYear` and the other fields were
`@NotBlank`/`@NotNull`; neither was true of the shipped code, and it contradicted both
`profile.md` and `ProfileService`). The comment now matches the implementation.

## 8. Phase 10 — adversarial review

Attempting to break the module as each kind of hostile or careless caller.

| # | Attack | Result |
|---|---|---|
| 8.1 | Anonymous caller | 401 on all three endpoints |
| 8.2 | Malformed / tampered token | 401; the signature check rejects a flipped signature |
| 8.3 | Student escalating to `ADMIN` by body field | `role`, `status`, `email`, `passwordHash`, `tokenVersion`, `id` all sent — row unchanged, no response field echoes them |
| 8.4 | Two students; one writes the other's row | Impossible: no endpoint takes an id. Asserted that the second student's row is byte-identical afterwards |
| 8.5 | Duplicate request (double-clicked save) | Idempotent; row identical after the second call |
| 8.6 | Malformed JSON | 400 `MALFORMED_REQUEST`, no stack trace, no SQL, no class names |
| 8.7 | Enum sent as a number | Rejected per field, not resolved to a member |
| 8.8 | Boundary values | 120/121 name, 30/31 year measured after trimming, `≥ 0` money, 2-decimal limit |
| 8.9 | DB constraint failure via the API | Prevented by DTO validation; the constraint is asserted directly to still hold |
| 8.10 | Partial transaction failure | One invalid field rejects the whole body; the valid half is not applied |
| 8.11 | Stale token / revoked session | 401; cannot read or change the profile |
| 8.12 | Disabled account with a valid token | 401 — only the status check can stop this, and it does |
| 8.13 | Concurrent revocation during a profile write | Closed by `@DynamicUpdate`; asserted at the persistence level with a second connection |
| 8.14 | Empty data state | A newly registered student reads nulls and schema defaults, not a substituted value |
| 8.15 | Application / Docker restart | No in-memory profile state; the row is the authority, so a restart is transparent |
| 8.16 | Administrator token on a student route | `403` — the defect found in the PHASE B cross-module audit and fixed as §5.7 |
| 8.17 | Two concurrent edits to the same row | Both to different columns survive (§5.2, `@DynamicUpdate`); two edits to the *same* column are last-writer-wins, which is what "set this value" means |

No defect survived this pass other than 5.4, which was found here and fixed. §5.7 was found later, in
the cross-module audit rather than in this module's own review — which is itself the finding worth
recording: a per-module review could not have caught it, because the rule that was missing lives in
`SecurityConfig` alongside the rules for modules 3, 4 and 5.

## 9. Traceability

| UC / BR | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|
| UC-04 read | `GET /profile/me` | `ProfileController.getMyProfile` | `ProfileService.getProfile` | `users` (5 columns) | `newStudentProfileUsesTheSchemaDefaults` |
| UC-04 edit | `PATCH /profile/me` | `updateMyProfile` | `updateProfile` | `users.full_name`, `academic_year`, `monthly_allowance_baseline`, `monthly_savings_goal` | `profileUpdatePersistsEveryField` |
| UC-04 clear year | `PATCH /profile/me` | `updateMyProfile` | `updateProfile` | `users.academic_year` (nullable) | `emptyAcademicYearClearsTheValue` |
| UC-27 read | `GET /profile/me` | `getMyProfile` | `getProfile` | `users.theme_pref`, `font_scale` | `newStudentProfileUsesTheSchemaDefaults` |
| UC-27 edit | `PATCH /profile/me/preferences` | `updateMyPreferences` | `updatePreferences` | `users.theme_pref`, `font_scale` | `preferencesAreUpdated` |
| BR-02 ownership | all three | `@AuthenticationPrincipal` | `requireCaller` | `users.id` from token | `profileUpdateTouchesOnlyTheCallersRow` |
| BR-03 revocation | all three | — | — | `users.status`, `token_version` | `savingAProfileChangeWritesOnlyTheChangedColumn`, `disabledAccountIsRefused` |
| BR-03 role separation | all three | `SecurityConfig` (`/api/v1/profile/**` → `hasRole("STUDENT")`) | — | — | `anAdministratorTokenIsRefused` |
| VĐ-04 money | `PATCH /profile/me` | `updateMyProfile` | `updateProfile` | `ck_users_money` | `databaseCheckRejectsNegativeMoney` |
| VĐ-08 currency | `GET /profile/me` | `getMyProfile` | `getProfile` | `system_settings.app.currency` | `newStudentProfileUsesTheSchemaDefaults` |
| §7.5 escalation | `PATCH /profile/me` | `updateMyProfile` | `updateProfile` | `users.role`, `status` | `profileRequestCannotEscalatePrivileges` |
| §7.2 no secrets out | all three | `ProfileMapper` | — | — | `profileResponseExposesNoSensitiveFields`, `noResponseSchemaExposesSensitiveFields` |
| §19 `field` key | all three | `GlobalExceptionHandler` | — | — | `assertFieldError` helper, `profileSchemasMatchTheDocumentedContract` |

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` |
| Every field traced to a documented requirement | Yes — §9 |
| Validation with per-field errors using `field` | Yes |
| Ownership enforced server-side, structurally | Yes — no identifier accepted |
| No sensitive field in any response | Yes |
| Tests through HTTP against real MySQL 8 | Yes — 31 + 5 tests |
| No schema change; `validate` holds | Yes |
| API document written; Angular can integrate without guessing | Yes — `docs/api/profile.md` |
| Inventory updated; no duplicate endpoint | Yes |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 92 tests |
| All artifacts English | Yes |

## 11. Deferred / blocked

Nothing in this module is blocked. Two global items affect it and are recorded in
`OVERNIGHT_BLOCKERS.md`:

- **OB-001** — the SRS and Use Case `.docx` are not on disk, so UC-04/UC-27 wording is derived from
  the schema and the existing design documents rather than quoted.
- **OB-002** — no production email provider. Not used by this module, noted because it is the same
  external dependency that affects module 1.
