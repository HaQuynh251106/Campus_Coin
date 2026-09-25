# Module 3 — Personal Categories (UC-06)

**Status:** DONE

A student keeps their own categories and uses the shared defaults the seed provides. Five
endpoints, no identifier accepted from the client that says who the caller is, and three rules left
where the database enforces them. This report is the module record: what was built, what was
tested, what the two reviews found, and the traceability from requirement to test.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-06 | List the categories a student can choose from, create personal ones, edit them, retire them and delete unused ones |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| Administering a default category | BR-06 and UC-20. The administrator writes `user_id IS NULL` rows through `sp_admin_upsert_default_category` in module 11, under `/api/v1/admin/**` |
| `?type=` filter or a `/categories/expense` route | The two types are already distinct in the response, and a picker wants one list. A filter is a second way to ask the same question |
| `PUT` for the whole category | UC-06 edits values; it does not replace a record. `PATCH` is the method that matches |
| `user_id` on any request | The owner comes from the bearer token, which is what makes BR-02 structural rather than a check |
| Category rules (`category_rules`) | UC-08, AI auto-categorisation, module 12 |
| Reordering as a separate operation | `sortOrder` is an ordinary editable field on `PATCH`; a dedicated reorder endpoint would be a convenience API with no use case |

## 2. Endpoints

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 11 | GET | `/api/v1/categories` | UC-06 | Bearer, role `STUDENT` | `200` list |
| 12 | GET | `/api/v1/categories/{id}` | UC-06 | Bearer, role `STUDENT` | `200` category |
| 13 | POST | `/api/v1/categories` | UC-06 | Bearer, role `STUDENT` | `201` category |
| 14 | PATCH | `/api/v1/categories/{id}` | UC-06 | Bearer, role `STUDENT` | `200` category |
| 15 | DELETE | `/api/v1/categories/{id}` | UC-06 | Bearer, role `STUDENT` | `204` no body |

A category is one of many, so unlike the profile module it is addressed by id and ownership cannot
be implied by the URL. It is enforced by the query instead: `findByIdAndUserId` is the **only**
single-row lookup in the repository, so no service method can reach another student's row.

Administrators are refused with `403` here. `/api/v1/categories/**` requires `STUDENT`, because an
administrator passing through would have the service write a personal category owned by that
administrator — a second route into the same table, bypassing the BR-06 gate.

## 3. Database alignment

No schema object was changed. `categories` already had everything UC-06 needs: the unique key, the
three triggers, the `scope_key` generated column and the foreign keys from `transactions`,
`budgets` and `recurring_rules`.

**What the database owns, and Java does not restate:**

| Rule | Owner |
|---|---|
| A student may not repeat a name within a type | `uk_categories_scope_type_name` on `(scope_key, type, name)` |
| A personal category may not shadow a default one | `trg_categories_before_insert` |
| A category may not change scope | `trg_categories_before_update` |
| A referenced category may not change type | `trg_categories_before_update` |
| A category with a budget may not be deleted | `trg_categories_before_delete` |
| A referenced category may not be deleted | `fk_txn_category`, `fk_recurring_category`, `fk_budget_category`, all `ON DELETE RESTRICT` |

Java performs the write and translates the refusals. It does **not** duplicate the rules: the
type-change rule spans three tables owned by modules 4–6, so asking about them from here would make
this package depend on entities it has no business knowing, and the trigger already holds for a
hand-run statement.

`scope_key` is deliberately unmapped — it is a `VIRTUAL` generated column that exists only so one
unique key can express both name rules. Mapping it would invite Hibernate to write a value MySQL
computes itself.

**Verified:** every file under `db/` is dated 2026-09-24 and `find db -type f -newermt
"2026-09-25 00:00"` returns nothing; `git diff --stat db/` is empty; `ddl-auto: validate` is still
the setting in both `application-dev.yml` and `application-prod.yml`; no schema-altering statement
appears in any surefire report.

## 4. Implementation

| File | Role |
|---|---|
| `category/controller/CategoryController.java` | Five endpoints, `@AuthenticationPrincipal` identity, full OpenAPI annotations |
| `category/service/CategoryService.java` | Ownership, the name pre-checks, and the refusal translation |
| `category/service/CategoryWriteFailure.java` | Classifies a database refusal by SQLSTATE and constraint name |
| `category/repository/CategoryRepository.java` | Every single-row query takes the owner's id |
| `category/mapper/CategoryMapper.java` | The one place deciding which columns may leave the server |
| `category/entity/Category.java` | The row, `@DynamicUpdate`, no `user_id` setter |
| `category/entity/CategoryType.java` | `INCOME`/`EXPENSE`, shared with later modules because BR-05 makes it the source of truth |
| `category/dto/CategoryResponse.java` | 9 fields out; `user_id` and `created_by` are not among them |
| `category/dto/CreateCategoryRequest.java`, `UpdateCategoryRequest.java` | No ownership field on either |
| `common/exception/CategoryNameTakenException.java`, `CategoryInUseException.java` | Both map to `409`, with distinct codes |

**Partial-update semantics.** An absent field and an explicit `null` both mean "unchanged". The
three nullable text fields (`icon`, `color`, `description`) take an empty string to be cleared; the
fields with no empty state (`name`, `type`, `isActive`) do not. This mirrors module 2 exactly, and
the reason is the same: absent and `null` cannot both mean "clear".

**Refusal translation by SQLSTATE, not by message.** The service asks `CategoryWriteFailure` rather
than matching the driver's text. `SIGNAL SQLSTATE '45000'` arrives as
`InvalidDataAccessResourceUsageException` (not `PersistenceException`) and 1451/1062 arrive as
`DataIntegrityViolationException` with SQLSTATE `23000`, so the classifier walks the cause chain for
the SQLSTATE and the constraint name. Anything it does not recognise is rethrown, so the handler
answers it as a generic conflict rather than the service mislabelling it.

**Transactions.** Reads are `readOnly = true`; the three writes are `@Transactional`. Not
annotated blindly.

## 5. Defects found and fixed

Every defect below was found by a test written to try to break the module, or by review — none by a
report.

### 5.1 Sensitive schema identifiers in the log

`translateWriteFailure` logged the exception object at INFO on all three refusal paths. MySQL's
duplicate-key message embeds the constraint name and the scope key
(`Duplicate entry '2-EXPENSE-Coffee' for key 'categories.uk_categories_scope_type_name'`), and a
trigger's message names the table it guards. Fixed by dropping the exception from every refusal line
and logging it only on the unrecognised path, where it is a genuine fault. A test asserts this
against the real log stream.

### 5.2 The test claimed to exercise the unique-key branch; it did not

The concurrency test's Javadoc said it exercised the translation's unique-key branch. Measuring it
showed otherwise: running the test alone and grepping for `rejected by the unique name constraint`
returned **zero** occurrences, because the service pre-check catches every duplicate even with a
`CyclicBarrier`. The claim was corrected in two ways rather than left standing: the test's Javadoc
now states only the end-state guarantee and says the branch is verified elsewhere, and
`CategoryWriteFailure` was extracted so the branch is unit-tested directly with the exact exception
shapes MySQL and Spring produce. This is the §28 rule applied to a test: a passing test that claims
more than it proves is a fake completion.

### 5.3 `docs` claimed a `401 ACCOUNT_DISABLED` no endpoint returns

Found in the adversarial review. `categories.md` (and `profile.md`, carried over from module 2)
documented that a disabled account gives `401 ACCOUNT_DISABLED`. It cannot: `ACCOUNT_DISABLED` is
thrown only from the **sign-in** path. On any token-guarded endpoint the filter rejects a disabled
account with `UnauthenticatedException`, and `RestAuthenticationEntryPoint` always emits
`UNAUTHENTICATED` — deliberately, so a token holder cannot distinguish a disabled account from a
revoked session or a stale `token_version`. The code is right and the documentation was wrong, so
the documents were corrected and the behaviour pinned by a new test
(`CategoryApiIT.disabledAccountIsRefusedAsUnauthenticated`). The module-1 contract was not touched.

### 5.4 `description` rejected a valid multi-line note

The pattern `^\s*.{0,255}\s*$` does not match a newline, so a two-line description was refused with
the message "Description must be at most 255 characters" — a rule the caller could not act on,
since the value was well within the length limit and the column (`VARCHAR(255)`) holds a newline
perfectly well. Fixed with `(?s)` on both DTOs, which makes the message true and keeps the length
bound. `name` and `icon` keep the single-line behaviour, which is correct for those two.

### 5.5 "No schema identifiers in the log" was broader than the truth

The documentation asserted that a refused write never puts a schema identifier in the log. That is
true of the module's own lines, but Hibernate's `SqlExceptionHelper` logs the raw driver message at
`ERROR` whenever a constraint or trigger actually fires — visible in the run output as
`Duplicate entry '44-EXPENSE-Raced' for key 'categories.uk_categories_scope_type_name'`. The
pre-check hides the ordinary path, but the concurrent path reaches the database and the driver's
text is written by Hibernate, outside the module's control. Rather than leave an assurance that
does not hold, the bound is now stated exactly: in `categories.md` §13, in `SECURITY.md` §7, and in
the test's own Javadoc. Suppressing it would mean disabling Hibernate's SQL-error logging, which
would also hide genuine faults.

## 6. Tests

`CategoryApiIT` — 44 tests over HTTP → security filter → controller → service → repository → **real
MySQL 8** (Testcontainers, loading `db/merged/campuscoin_full.sql`). The development database is
never touched.

| Category | Tests |
|---|---|
| Happy path (read) | `newStudentSeesTheDefaultsAndNoPersonalCategories`, `createdCategoryIsListedWithTheDefaults`, `defaultsComeFirstOnATieOfDisplayOrder`, `ownCategoryCanBeReadById`, `absentNullableFieldsAreOmittedFromTheResponse` |
| Happy path (create) | `personalCategoryPersistsEveryField`, `minimalBodyUsesTheColumnDefaults`, `optionalTextIsTrimmedAndEmptied` |
| Happy path (update/delete) | `partialUpdateLeavesTheOtherFieldsAlone`, `emptyUpdateBodyChangesNothing`, `emptyStringClearsANullableField`, `retireAndRestoreThroughIsActive`, `unusedCategoryIsDeleted` |
| Validation | `nameLengthBoundaryMatchesTheColumn`, `malformedColourIsRejected`, `unknownTypeIsRejectedPerField`, `missingRequiredFieldsAreReported`, `multiLineDescriptionIsAccepted`, `malformedBodyIsRejectedWithoutLeakingInternals`, `malformedIdentifierIsBadRequest` |
| Name uniqueness (BR-06) | `duplicateNameForTheSameTypeIsRefused`, `sameNameIsAllowedForADifferentType`, `personalCategoryCannotShadowADefaultOne`, `renameHonoursUniqueness`, `renameCannotTakeADefaultName` |
| DB constraint / trigger behaviour | `defaultNameRuleIsEnforcedByTheDatabase` (direct `INSERT` the trigger refuses), `typeChangeOnAReferencedCategoryIsRefused`, `typeChangeIsAllowedWhenUnreferenced`, `renameOfAReferencedCategoryIsAllowed`, `categoryWithABudgetCannotBeDeleted`, `categoryWithATransactionCannotBeDeleted` |
| Ownership (BR-02) | `anotherStudentsCategoryIsUnreachable`, `defaultCategoriesAreReadOnlyForStudents` |
| Role / unauthenticated | `categoryEndpointsRejectAnonymousCallers`, `administratorTokenIsRefused` |
| Session state (BR-03) | `revokedSessionIsRefused`, `disabledAccountIsRefusedAsUnauthenticated` |
| Mass assignment (§7.5) | `categoryRequestCannotSetOwnership`, `scopeCannotBeChangedByRequest` |
| Rollback | The refused writes above assert the row and its referencing records are unchanged |
| Duplicate / idempotency | `duplicateCreateIsRefused`, `repeatedIdenticalUpdatesAreIdempotent` |
| Concurrency | `simultaneousIdenticalCreatesAreSerialisedByTheUniqueKey` — 6 barrier-synchronised identical creates, one `201`, five `409`s, no `500`, one row |
| Error consistency | `assertFieldError` asserts `field`, never `path`; `malformedIdentifierIsBadRequest` pins `INVALID_REQUEST` for a non-numeric path variable |
| Logging (§7.6) | `refusalsDoNotWriteDatabaseInternalsToTheLog` |

`CategoryWriteFailureTest` — 8 unit tests with the exact `SQLException`/Spring exception shapes:
`duplicateKeyIsRecognised`, `otherIntegrityViolationsAreNotNameClashes`, `nestingDepthDoesNotHideTheConstraint`, `signalledRefusalIsRecognised`, `duplicateKeyIsNotASignalledRefusal`, `eachRefusalMatchesOnlyItsOwnRule`, `unrelatedFailuresAreNotClaimed`, `nullMessagesAreSurvivable`.

`OpenApiContractIT` — extended with the 5 category endpoints, three alias guards, and
`categorySchemasMatchTheDocumentedContract`, which pins the response and request field sets and
asserts `userId`/`createdBy`/`isDefault` appear on neither request type.

`SecurityHardeningIT` — gained `categoryRefusalsLogNoSchemaIdentifiers` (see 5.1, 5.5).

**Result: 146 tests, 0 failures, 0 errors, 0 skipped** (`mvnw clean test`), of which 44 are this
module's HTTP suite and 8 its unit tests.

## 7. Phase 9 — first review

Reviewed as if it were another contributor's pull request, against criteria A–V.

| # | Criterion | Finding |
|---|---|---|
| A | SRS compliance | No SRS on disk (OB-001); scope derived from the schema, the Use Case document and the module order |
| B | UC completeness | All of UC-06 is covered; exclusions justified in §1 |
| C | BR compliance | BR-02 structural; BR-05 and BR-07 left to the triggers and translated; BR-06 enforced in the database, with one documented application-side addition |
| D | Database alignment | No schema change; `validate` passes; `scope_key` deliberately unmapped |
| E | API correctness | Status codes, bodies and field names match `categories.md` and `/api-docs` |
| F | API duplication | No alias route, no type filter, no `/profile/me/categories`, no `PUT` |
| G | Validation | Per-field `field` errors; boundaries match the column widths; patterns measure the trimmed value |
| H | Authorization | Bearer required; `hasRole("STUDENT")`; anonymous → `401`, admin → `403` |
| I | Ownership | Enforced by the query, not a comparison; asserted by test |
| J | Security | No ownership or scope field on any request type; no `user_id` setter; `@DynamicUpdate`; refusal text never forwarded |
| K | Transaction boundaries | Read-only where nothing is written; writes transactional |
| L | Error handling | Two distinct `409` codes, because the remedy differs; `404` never reveals existence |
| M | Test coverage | Every §13 phase-5 category present, including concurrency and DB-constraint behaviour |
| N | Swagger | Matches the implementation; machine-checked |
| O | Documentation | `categories.md` complete; the `ACCOUNT_DISABLED` claim was found wrong here and fixed (5.3) |
| P | Frontend integration | §12 opens with the current `category.model.ts` and a rewrite of `category.service.ts` |
| Q | Naming | Feature-first under `com.campuscoin.category`, consistent with `auth` and `profile` |
| R | Dead code | None; the extracted `CategoryWriteFailure` replaced code that was duplicated inside the service |
| S | Unnecessary abstraction | One mapper, three DTOs, one service, one classifier |
| T | Scope creep | Nothing beyond UC-06 was built |
| U | English consistency | All artifacts English (§2); `VĐ-*` kept as the source's own identifier (§3) |
| V | Regression risk | Full suite green; modules 1 and 2 re-verified |

**Fixed as a result of this review:** the `ACCOUNT_DISABLED` documentation error (5.3) and the
`description` newline rejection (5.4).

## 8. Phase 10 — adversarial review

Attempting to break the module as each kind of hostile or careless caller.

| # | Attack | Result |
|---|---|---|
| 8.1 | Anonymous caller | `401` on all five endpoints |
| 8.2 | Malformed / tampered token | `401`; the signature check rejects a flipped signature |
| 8.3 | Administrator token | `403` on read and write; the admin's route is UC-20 under `/api/v1/admin/**` |
| 8.4 | Student reading another student's category | `404` on GET, PATCH and DELETE; the row is byte-identical afterwards |
| 8.5 | Student editing or deleting a **default** category | `404` on all three verbs; `countDefaults()` still 12 and the row snapshot unchanged |
| 8.6 | Body carrying `userId`, `user_id`, `isDefault`, `createdBy`, `id` | Ignored; the row is owned by the caller |
| 8.7 | Body carrying `userId: null` to change scope | Ignored; the row stays personal |
| 8.8 | Duplicate request (double-clicked save) | Second identical create → `409`; one row |
| 8.9 | Concurrent identical creates | Six barrier-synchronised requests → one `201`, five `409`s, no `500`, one row |
| 8.10 | Malformed JSON body | `400 MALFORMED_REQUEST`, no stack trace, no SQL, no class names |
| 8.11 | Enum sent as a number | Rejected per field, not resolved to a member (project-wide Jackson setting) |
| 8.12 | Invalid path identifier | `a` → `400 INVALID_REQUEST`; `99999999999999999999999` → `400`; unknown id → `404` |
| 8.13 | Boundary values | 80/81 name measured after trimming, 255/256 description, `#RRGGBB` enforced, `sortOrder` 0–32767 |
| 8.14 | DB constraint via the API | Prevented by the pre-check; the trigger is asserted directly to still hold |
| 8.15 | Partial transaction failure | A refused write leaves the category, the referencing transaction and its `transaction_history` row intact |
| 8.16 | Stale token / revoked session | `401`; cannot read or change anything |
| 8.17 | Disabled account with a valid token | `401`, before the controller is reached |
| 8.18 | Empty body `{}` on PATCH | `200`, nothing changed, no `UPDATE` issued |
| 8.19 | Multi-line description | Accepted; the length bound still applies |
| 8.20 | Startup / restart | No in-memory category state; the row is the authority, so a restart is transparent |

No defect survived this pass other than 5.3–5.5, found here and fixed.

## 9. Traceability

| UC / BR | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|
| UC-06 list | `GET /categories` | `listCategories` | `listCategories` | `categories` filtered by `user_id` | `newStudentSeesTheDefaultsAndNoPersonalCategories` |
| UC-06 read one | `GET /categories/{id}` | `getCategory` | `getCategory` | `findByIdAndUserId` | `ownCategoryCanBeReadById` |
| UC-06 create | `POST /categories` | `createCategory` | `create` | `categories` INSERT, `scope_key` generated | `personalCategoryPersistsEveryField`, `minimalBodyUsesTheColumnDefaults` |
| UC-06 edit | `PATCH /categories/{id}` | `updateCategory` | `update` | `categories` UPDATE | `partialUpdateLeavesTheOtherFieldsAlone`, `emptyUpdateBodyChangesNothing` |
| UC-06 clear a field | `PATCH /categories/{id}` | `updateCategory` | `update` | nullable `icon`, `color`, `description` | `emptyStringClearsANullableField` |
| UC-06 delete | `DELETE /categories/{id}` | `deleteCategory` | `delete` | `categories` DELETE | `unusedCategoryIsDeleted` |
| BR-02 ownership | all five | `@AuthenticationPrincipal` | `requireOwnCategory` | `findByIdAndUserId` | `anotherStudentsCategoryIsUnreachable` |
| BR-03 revocation | all five | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.token_version` | `revokedSessionIsRefused`, `disabledAccountIsRefusedAsUnauthenticated` |
| BR-05 type is the source of truth | `PATCH` | — | translates the refusal | `trg_categories_before_update` | `typeChangeOnAReferencedCategoryIsRefused`, `typeChangeIsAllowedWhenUnreferenced`, `renameOfAReferencedCategoryIsAllowed` |
| BR-06 unique name | `POST`, `PATCH` | — | `requireNameIsFree` | `uk_categories_scope_type_name` | `duplicateNameForTheSameTypeIsRefused`, `sameNameIsAllowedForADifferentType`, `renameHonoursUniqueness` |
| BR-06 no shadowing a default | `POST`, `PATCH` | — | `requireNameIsFree` | `trg_categories_before_insert`; the update path is the application's documented addition | `personalCategoryCannotShadowADefaultOne`, `defaultNameRuleIsEnforcedByTheDatabase`, `renameCannotTakeADefaultName` |
| BR-06 defaults are read-only | `GET/{id}`, `PATCH`, `DELETE` | — | `requireOwnCategory` | `user_id IS NULL` | `defaultCategoriesAreReadOnlyForStudents` |
| BR-07 retire instead of delete | `DELETE`, `PATCH` | — | translates the refusal | `trg_categories_before_delete`, `fk_txn_category`, `fk_budget_category` | `categoryWithABudgetCannotBeDeleted`, `categoryWithATransactionCannotBeDeleted`, `retireAndRestoreThroughIsActive` |
| §7.5 no client identity | all five | DTO field set, `SecurityConfig` | entity setter set | `users.role` | `categoryRequestCannotSetOwnership`, `scopeCannotBeChangedByRequest`, `administratorTokenIsRefused` |
| §7.6 logging | all writes | — | `translateWriteFailure` | — | `SecurityHardeningIT.categoryRefusalsLogNoSchemaIdentifiers` |
| §7.7 no internals in a response | all five | — | `CategoryWriteFailure` | — | `refusalsDoNotWriteDatabaseInternalsToTheLog`, `malformedBodyIsRejectedWithoutLeakingInternals` |
| §13 P5 concurrency | `POST` | — | — | `uk_categories_scope_type_name` | `simultaneousIdenticalCreatesAreSerialisedByTheUniqueKey` |
| §13 P5 refusal classification | — | — | `CategoryWriteFailure` | — | `CategoryWriteFailureTest` |
| UC-06 UAT: empty state is usable | `GET /categories` | — | — | twelve seeded defaults | `newStudentSeesTheDefaultsAndNoPersonalCategories` |

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` |
| Every field traced to a documented requirement | Yes — §9 |
| Validation with per-field errors using `field` | Yes |
| Ownership enforced server-side, structurally | Yes — the only single-row query takes the owner's id |
| No sensitive field in any response | Yes — `CategoryMapper` omits `user_id` and `created_by` |
| Tests through HTTP against real MySQL 8 | Yes — 44 HTTP tests + 8 unit tests |
| No schema change; `validate` holds | Yes |
| API document written; Angular can integrate without guessing | Yes — `docs/api/categories.md` |
| Inventory updated; no duplicate endpoint | Yes |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 146 tests |
| All artifacts English | Yes |

## 11. Deferred / blocked

Nothing in this module is blocked. Two global items affect it and are recorded in
`OVERNIGHT_BLOCKERS.md`:

- **OB-001** — the SRS and Use Case `.docx` are not on disk, so UC-06 wording is derived from the
  schema and the existing design documents rather than quoted.
- **OB-002** — no production email provider. Not used by this module; noted because it affects
  module 1.

One limitation is recorded rather than blocked, because it is not fixable in the application layer
without a cost that is not worth paying: Hibernate's `SqlExceptionHelper` writes the driver's raw
error message when a constraint or trigger fires (5.5). It names a schema object and a value the
caller supplied, and suppressing it would mean disabling Hibernate's SQL-error logging.
