# Module 4 — Transactions (UC-07, UC-10)

**Status:** DONE WITH DEFERRED NON-CRITICAL ITEMS

A student records what they spend and receive, edits it, removes it, and brings it back. Six
endpoints; the record's type is never sent because it is the category's; the removal is a soft delete
that writes an audit row the database owns; and the four rules UC-07 and UC-10 depend on stay where
the schema put them.

This report is the module record: what was built, what was tested, what the two reviews found, and the
traceability from requirement to test.

**The one deferred item** is a bound on the list endpoint, not a missing feature: with no `to`, the
list stops at today, so module 5's future-dated recurring occurrences are not in it until their date
arrives (§5.6). It costs nothing in this module, is already overridable by a parameter the client can
send, and is recorded here rather than silently inherited. It is deferred to module 5, which is the
module that can decide it. §11 lists it with the rest of the open items.

---

## 1. Scope

| Use case | Behaviour |
|---|---|
| UC-07 | Record one income or expense against one of the student's own categories |
| UC-10 | List the student's records, read one, edit one, delete one (soft) and restore it (A1) |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `type` field on any request | `transactions` has no type column. The type **is** the category's type (BR-05), and BR-05 holds by construction only while there is one source of truth |
| A `source` field on any request | Provenance, not a user choice — and a security boundary. `sp_validate_transaction` exempts `RECURRING` from the BR-08 future-date check, so a client able to claim it could record a future-dated expense |
| `userId` on any request | The owner is the account in the bearer token, which is what makes BR-02 structural rather than a comparison someone has to remember |
| `isDeleted` / `deletedAt` on any request | BR-09 requires the removal and the return to be logged. A body able to set the flag would move the record without the log row — the one thing the design exists to prevent |
| `PUT` for the whole transaction | UC-10 edits values; it does not replace a record |
| `/transactions/deleted`, `/all`, `/list` | `GET /transactions` already answers all three: live records by default, the trash with `includeDeleted=true`, and `from`/`to` to narrow |
| `/profile/me/transactions` | A transaction is one of many and is addressed by id, so ownership belongs in the query. Filing it under `/profile/me` would suggest the wrong model to modules 5 and 6 |
| Pagination | Neither UC-07 nor UC-10 asks for it, and the screen loads the list whole. The date range is the mechanism the use case describes — see §5.6 for what that range does and does not bound |
| A bulk or batch create | UC-11, module 12, which writes its own rows through its own contract |
| The AI suggestion columns | UC-08, module 12 |
| The anomaly flags | UC-24, module 12. A client able to set `flagType` could mark its own record as reviewed, which is the exact signal the feature exists to raise |
| Budget alerts | UC-14, module 6 — raised by the database's triggers. This module does not know budgets exist |

## 2. Endpoints

| # | Method | Endpoint | UC | Auth | Success |
|---|---|---|---|---|---|
| 16 | GET | `/api/v1/transactions` | UC-10 | Bearer, role `STUDENT` | `200` list |
| 17 | GET | `/api/v1/transactions/{id}` | UC-10 | Bearer, role `STUDENT` | `200` transaction |
| 18 | POST | `/api/v1/transactions` | UC-07 | Bearer, role `STUDENT` | `201` transaction |
| 19 | PATCH | `/api/v1/transactions/{id}` | UC-10 | Bearer, role `STUDENT` | `200` transaction |
| 20 | DELETE | `/api/v1/transactions/{id}` | UC-10 | Bearer, role `STUDENT` | `204` no body |
| 21 | POST | `/api/v1/transactions/{id}/restore` | UC-10 A1 | Bearer, role `STUDENT` | `200` transaction |

A transaction is one of many and is addressed by id, so ownership cannot be implied by the URL. It is
enforced by the query instead: every single-row lookup in `TransactionRepository` takes the caller's
id alongside the record's, so no service method can reach another student's row even by mistake.

**Delete and restore are separate operations, not a `PATCH` of a flag.** BR-09 requires the history to
show the removal and the return, and it is `sp_soft_delete_transaction` and `sp_restore_transaction`
that append those rows. A writable `isDeleted` would move the record without the log entry.

Administrators are refused with `403`. `/api/v1/transactions/**` requires `STUDENT`; UC-07 and UC-10
are a student's own records, and the administrator reads aggregates through UC-21 and UC-22 in module
11, with no endpoint that writes a transaction.

## 3. Database alignment

No schema object was changed. `transactions` already had everything UC-07 and UC-10 need: the three
triggers, the soft-delete procedures, `ck_txn_amount`, `ck_txn_deleted`, the four indexes and the four
foreign keys.

**What the database owns, and Java does not restate:**

| Rule | Owner |
|---|---|
| A record's date may not be in the future (BR-08), except for `RECURRING` | `sp_validate_transaction`, reached through `trg_transactions_before_insert` and `..._before_update` |
| A record may only be filed under the caller's own category or a shared default (BR-02) | `sp_validate_transaction` |
| A record may not be filed under a retired category (BR-07) | `sp_validate_transaction`, with `require_active = 1` on insert only |
| The amount must be strictly positive | `ck_txn_amount` |
| The delete/restore pair is consistent with the timestamp | `ck_txn_deleted` |
| A transaction may not be hard-deleted (BR-09) | `trg_transactions_before_delete` |
| Every create, edit, delete and restore is logged with the full before/after payload (BR-09) | `trg_transactions_after_insert`, `trg_transactions_after_update` |
| The soft-delete transition, and ownership of it | `sp_soft_delete_transaction`, `sp_restore_transaction` |
| A budget threshold alerts once per budget (BR-12) | `budget_alert_log` unique key, via `sp_check_budget_alerts` |

Java performs the write and translates the refusals. Three checks are the application's — the category
is usable, the category is active on a new filing decision, and the date is not in the future — and
each is a **stricter restatement of a rule that already holds**, present only so the error can name
the field. `sp_validate_transaction` signals six different rules with one `SQLSTATE '45000'`, so a
client told only "the write was refused" could not point at what needs fixing. Every one of the three
is checked before the write *and* the trigger still runs; a request that slips past is caught by
`translateWriteFailure`, which is why that branch is answered `409` rather than with a field error.

**Four columns are deliberately unmapped** on the entity: `ai_suggested_category_id`, `ai_confidence`,
`ai_overridden` (UC-08), `is_flagged`, `flag_type`, `flag_note` (UC-24) and the two origin links
`recurring_rule_id` (UC-09), `import_batch_id` (UC-11). That is not only scope: an unmapped column
cannot be written by any statement this module issues, so an edit here cannot clear an AI suggestion,
a flag or a recurring rule's link. Leaving them out is what makes that guarantee structural.

**`scope_key` and `transaction_history` are also unmapped.** `scope_key` is a `VIRTUAL` generated
column MySQL computes; `transaction_history` has exactly one writer — the triggers — and mapping it
would invite a second.

**Verified:** every file under `db/` is still dated 2026-09-24 and `find db -type f -newermt
"2026-09-25 00:00"` returns nothing; `git diff --stat db/` is empty; `ddl-auto: validate` is the
setting in both `application-dev.yml` and `application-prod.yml`; no schema-altering statement appears
in any surefire report.

## 4. Implementation

| File | Role |
|---|---|
| `transaction/controller/TransactionController.java` | Six endpoints, `@AuthenticationPrincipal` identity, full OpenAPI annotations |
| `transaction/service/TransactionService.java` | The three application checks, the range bounds, and the refusal translation |
| `transaction/service/TransactionWriteFailure.java` | Classifies a database refusal by SQLSTATE, never by message |
| `transaction/repository/TransactionRepository.java` | Every single-row query takes the owner's id; the write paths take a row lock |
| `transaction/repository/TransactionProcedureDao.java` | The two soft-delete procedures, and the `refresh` that makes the restore response honest |
| `transaction/mapper/TransactionMapper.java` | The one place deciding which columns may leave the server |
| `transaction/entity/Transaction.java` | The row; `@DynamicUpdate`; no setter for `user_id`, `source` or the soft-delete pair |
| `transaction/entity/TransactionSource.java` | `MANUAL`/`CSV`/`RECURRING`, the column's own ENUM |
| `transaction/dto/TransactionResponse.java` | 12 fields out; `userId` is not among them |
| `transaction/dto/CreateTransactionRequest.java`, `UpdateTransactionRequest.java` | Four fields each; no ownership, provenance, state or flag field on either |
| `common/exception/TransactionStateException.java` | `409` with two distinct codes, because the remedy differs |

**`@DynamicUpdate` is a correctness requirement, not an optimisation.** A plain Hibernate `UPDATE`
writes every mapped column back with the values read when the request began — including `is_deleted`,
which a concurrent `sp_soft_delete_transaction` may have changed by SQL. Restricting the statement to
the columns that actually changed removes that class of lost update. `is_deleted` and `deleted_at` are
also mapped `updatable = false`, so the two procedures are the only writers.

**The write paths take `SELECT ... FOR UPDATE`, and the read path does not.** A state check made from
a lock-free read can be made twice: two deletes that both see a live row would both report success,
and the second one's trigger would record a state change rather than a `DELETE`. Reads take no lock,
because a report has no state to protect. The locking queries deliberately omit `JOIN FETCH` — a lock
on a join also locks the shared `categories` row, which would make two students editing records in the
same category block each other for no reason.

**The restore path reloads the row before answering.** The procedure changes `is_deleted` and
`deleted_at` with SQL Hibernate never sees, so without the `refresh` the response would report the
record as still deleted immediately after restoring it.

**Refusal translation by SQLSTATE, not by message.** `SIGNAL SQLSTATE '45000'` arrives through Spring
as `InvalidDataAccessResourceUsageException` — not `PersistenceException` — so the classifier walks the
cause chain for the SQLSTATE rather than matching the driver's text, which is localised and not a
contract. Anything unrecognised is rethrown, so the handler answers it as an internal error rather
than this module mislabelling it.

**Transactions.** Reads are `readOnly = true`; the four writes are `@Transactional`. Not annotated
blindly. `EARLIEST_DATE` is `LocalDate.of(1000, 1, 1)` rather than `LocalDate.MIN`, because MySQL's
`DATE` range starts in year 1000 and a value outside it would turn an ordinary "no lower bound"
request into a driver error.

## 5. Defects found and fixed

Every defect below was found by a test written to try to break the module, by review, or by
re-reading the module's own documents. None by a report.

### 5.1 `requireActiveTransaction` did not exist, and "it compiles" was wrong

The headline defect. `getTransaction` called `requireActiveTransaction(...)` while the class only
defined `requireActiveTransactionForUpdate(...)`. The method was missing, so the module did not
compile — and an earlier status note in this session reported it as compiling, because a stale class
file from a previous build was on disk and the check that "verified" it had not actually recompiled.
That is a §33 failure as much as a code failure: a claim of a passing build that had not been re-run.
The method was written, and the correction is recorded here rather than dropped because the mistake was
in the reporting rather than the code.

### 5.2 A documented test reference pointed at a test that did not exist

`docs/api/transactions.md` cited `SecurityHardeningIT.transactionRefusalsLogNoSchemaIdentifiers`. No
such test existed. Rather than delete the claim, the test was written and the citation corrected to
its real name. This is §28 applied to documentation: a document that cites evidence it does not have
is a fake completion, and the fix is to produce the evidence, not to soften the sentence.

### 5.3 The new test asserted more than is true, and the fix was to narrow the claim

The first draft of that test asserted that the **entire** captured log stream contained no schema
identifier. It failed, correctly: the dev profile sets `org.hibernate.SQL: DEBUG`, so Hibernate
logged `CALL sp_soft_delete_transaction(?, ?)`. The parameters are placeholders and no data is
exposed, but the claim as written was false. Rather than weaken or delete the assertion, the test now
filters to the module's own logger and asserts over those lines only, and its Javadoc states the bound
exactly — "our lines are clean", not "the log is clean" — naming `org.hibernate.SQL` and the profile
setting as the reason. The same over-broad-claim pattern had already occurred twice in module 3
(§5.1 and §5.5 of `MODULE_03_CATEGORIES.md`), which is why the scope note is in the test rather than
only in a document.

### 5.4 The list endpoint's default upper bound was justified with a rule that has an exception

The service and `transactions.md` both explained the default `to = today` by saying BR-08 makes a
future-dated row impossible, so the bound could not hide anything. That is false in general: BR-08
**exempts `RECURRING`**, and module 5's scheduler writes those rows ahead of their date. Found by
re-reading the module's own Javadoc against `sp_validate_transaction` rather than by a failing test.
Fixed in three places: the service Javadoc now states the limit and hands the decision to module 5,
`transactions.md` §5 says the same to the integrator, and a new test
(`futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven`) pins the actual behaviour — the row is
absent from an unbounded list and present once `to` is given. Recording a limit as a test is what
stops a later module inheriting it without noticing.

### 5.5 Two pieces of dead or misleading code in the module

- `TransactionRepository.findAnyByIdAndUserId` had no caller. The lock-free form of the either-state
  lookup is a trap rather than a convenience: the only reason to read a deleted row is to act on it,
  and acting on a lock-free read is the race the locked twin exists to prevent. Removed, with the
  reason written into the class comment so the next module does not re-add it.
- `WriteOperation.DELETE` was declared and never used: delete and restore call the procedures, which
  the service has already guarded with the row lock and an explicit state read. Removed, and the enum
  now documents why there is no fourth call site.

Also corrected: `Transaction`'s Javadoc pointed at `TransactionRepository.findByIdAndUserId`, a method
that does not exist, and the repository's class comment still said "the four single-row lookups" after
there were three. Both were documentation that had drifted from the code.

### 5.6 The list endpoint bounds less than "the date range keeps a response bounded" implies

The §1 exclusion table justified omitting pagination by saying the date range is the mechanism that
keeps a response bounded. It does bound the **upper** end — and, with no `from`, it reaches back to
the earliest date the column can hold, so a long-lived account's request touches every row it owns.
The exclusion is still right for UC-10, which asks to see the student's records rather than a page of
them, but the reason was overstated. `transactions.md` §1 now separates the two claims and names the
decision to revisit if a later screen needs paging. Deferred, not fixed: not implementing pagination is
correct here, and inventing a page parameter no use case asks for would be the scope creep §7 forbids.

### 5.7 The §11 heading said "four rules" over an eight-row table

A drift between a heading and its own content in `transactions.md`. Corrected to name the section for
what it is — the rules the database enforces, not this module — rather than restating a count that
would have to be maintained.

## 6. Tests

`TransactionApiIT` — 57 tests over HTTP → security filter → controller → service → repository → **real
MySQL 8** (Testcontainers, loading `db/merged/campuscoin_full.sql`). The development database is never
touched.

| Category (§13 phase 5) | Tests |
|---|---|
| Happy path (read) | `emptyStateIsAnEmptyArray`, `recordsAreOrderedNewestFirstWithATotalOrder`, `ownTransactionCanBeReadById`, `absentNullableFieldsAreOmitted` |
| Happy path (create) | `creatingAnExpenseDerivesEverythingFromTheCategory`, `theCategoryAloneDecidesIncomeOrExpense`, `minimalBodyUsesTheColumnDefaults`, `descriptionIsTrimmedAndBlankBecomesNull` |
| Happy path (update/delete/restore) | `partialUpdateLeavesTheOtherFieldsAlone`, `emptyUpdateBodyChangesNothing`, `emptyStringClearsTheDescription`, `deleteIsSoftAndKeepsTheRecord`, `restoreBringsTheRecordBack` |
| Validation | `missingRequiredFieldsAreReported`, `amountBoundariesMatchTheColumn`, `multiLineDescriptionIsAccepted`, `malformedBodyIsRejectedWithoutLeakingInternals`, `malformedIdentifierIsBadRequest`, `malformedDateParameterIsRejected`, `invertedRangeIsAFieldError` |
| Boundary values | `amountBoundariesMatchTheColumn` (0 refused, 2 dp enforced, 13 digits), `theDateBoundaryIsTodayInTheApplicationZone` (today accepted, tomorrow refused, in `Asia/Ho_Chi_Minh`) |
| Date range | `dateRangeIsInclusiveAndEitherBoundMayBeOmitted`, `futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven` |
| DB constraint / trigger behaviour | `futureDateIsRefusedByTheDatabaseToo`, `anotherStudentsCategoryIsRefusedByTheDatabaseToo`, `hardDeleteIsRefusedByTheDatabase` — each a direct statement the trigger refuses, proving the rule does not depend on the API |
| Not-found / invalid id | `unknownIdentifierIsNotFound`, `unusableCategoryIsNotFound`, `retiredCategoryCannotBeUsedForANewRecord` |
| Ownership (BR-02) | `anotherStudentsTransactionIsUnreachable` — all six endpoints |
| Role / unauthenticated | `anonymousCallerIsRefused`, `administratorTokenIsRefused`, `malformedTokenIsRefused` |
| Session state (BR-03) | `revokedSessionIsRefused`, `disabledAccountIsRefusedAsUnauthenticated` |
| Client-supplied fields (§7.5) | `createBodyCannotSetServerOwnedFields`, `updateBodyCannotSetServerOwnedFields`, `claimingRecurringDoesNotUnlockFutureDates` |
| BR-05 type integrity | `movingToAnotherCategoryChangesTheType`, `recordUnderARetiredCategoryStaysEditable` |
| History (BR-09) | `createWritesAFullHistoryRow`, `updateRecordsOnlyWhatChanged` |
| Budget alerts (UC-14) | `recordingAnExpenseRaisesTheBudgetAlerts`, `budgetAlertsRespectTheCategoryAndTheMonth`, `budgetAlertsReachOnlyTheOwner`, `budgetAlertsAreNotDuplicated` |
| Duplicate / idempotency | `identicalRecordsAreNotDeduplicated`, `repeatedIdenticalUpdatesAreIdempotent`, `deleteAndRestoreAreIdempotentInEffect`, `deletingTwiceIsAConflict`, `restoringALiveRecordIsAConflict` |
| Concurrency | `simultaneousDeletesProduceOneDeletion`, `simultaneousEditsAreSerialised` — barrier-synchronised, asserting the log and the record's state as well as the status codes |
| Error consistency | `assertFieldError` asserts `field`, never `path`; `refusalsDoNotLeakDatabaseInternals` |
| Logging (§7.6) | `refusalsDoNotLeakDatabaseInternals` (response side), `SecurityHardeningIT.transactionRefusalsKeepTheSchemaOutOfEverything` (log side, module lines only) |

`TransactionWriteFailureTest` — 9 unit tests with the exact `SQLException`/Spring exception shapes.
`sp_validate_transaction` signals six rules with one SQLSTATE and the service pre-checks them all, so
the signalled branch is nearly unreachable through the API; leaving it to an integration test would
leave it unverified.

`OpenApiContractIT` — `transactionSchemasMatchTheDocumentedContract` pins the 12 response fields, the
4+4 request fields, the `type` and `source` enum vocabularies, the three alias paths that must not
exist, and that `userId`/`type`/`source`/`isDeleted` appear on neither request type.

`SecurityHardeningIT` — gained `transactionRefusalsKeepTheSchemaOutOfEverything` (§5.2, §5.3).

**Result: 214 tests, 0 failures, 0 errors, 0 skipped** (`mvnw clean test`), of which 57 are this
module's HTTP suite and 9 its unit tests.

## 7. Phase 9 — first review

Reviewed as if it were another contributor's pull request, against criteria A–V.

| # | Criterion | Finding |
|---|---|---|
| A | SRS compliance | No SRS on disk (OB-001); scope derived from the schema, the Use Case document and the module order |
| B | UC completeness | UC-07 and UC-10 complete, including A1 restore; exclusions justified in §1 |
| C | BR compliance | BR-02 structural; BR-05 by construction (no type column); BR-07 and BR-08 left to the trigger and translated; BR-09 owned by the procedures and the triggers; BR-12 enforced by a unique key |
| D | Database alignment | No schema change; `validate` passes; six columns deliberately unmapped |
| E | API correctness | Status codes, bodies and field names match `transactions.md` and `/api-docs` |
| F | API duplication | No alias path, no `/profile/me/transactions`, no `PUT`, no bulk create |
| G | Validation | Per-field `field` errors; boundaries match `DECIMAL(15,2)`; the description pattern measures the trimmed value |
| H | Authorization | Bearer required; `hasRole("STUDENT")`; anonymous → `401`, admin → `403` |
| I | Ownership | Enforced by the query, structurally; asserted across all six endpoints |
| J | Security | No ownership, provenance, state or flag field on any request; no setter for them on the entity; `@DynamicUpdate`; `updatable = false` on the soft-delete pair; refusal text never forwarded |
| K | Transaction boundaries | Read-only where nothing is written; writes transactional; `FOR UPDATE` only where a state decision is made |
| L | Error handling | Two distinct `409` codes for the two state mismatches; `404` never reveals existence |
| M | Test coverage | Every §13 phase-5 category present, including concurrency, DB-constraint and rollback behaviour |
| N | Swagger | Matches the implementation; machine-checked |
| O | Documentation | `transactions.md` complete; two of its claims were found wrong here (§5.4, §5.6) and fixed |
| P | Frontend integration | §13 opens with the current `transaction.model.ts` and the rewiring `transaction.service.ts` needs |
| Q | Naming | Feature-first under `com.campuscoin.transaction`, consistent with `auth`, `profile` and `category` |
| R | Dead code | Two items removed (§5.5); one stale Javadoc reference corrected |
| S | Unnecessary abstraction | One mapper, three DTOs, one service, one classifier, one DAO for the two procedures |
| T | Scope creep | Nothing beyond UC-07 and UC-10 was built |
| U | English consistency | All artifacts English (§2); `VĐ-*` and `BR-*` kept as the source's own identifiers (§3) |
| V | Regression risk | Full suite green; modules 1–3 re-verified |

**Fixed as a result of this review:** the BR-08 over-claim in the service and the API document
(§5.4), the overstated pagination justification (§5.6), the §11 heading (§5.7), the two dead items and
the stale Javadoc reference (§5.5).

## 8. Phase 10 — adversarial review

Attempting to break the module as each kind of hostile or careless caller.

| # | Attack | Result |
|---|---|---|
| 8.1 | Anonymous caller | `401` on all six endpoints |
| 8.2 | Malformed / tampered token | `401`; the signature check rejects a flipped signature |
| 8.3 | Administrator token | `403` on every verb |
| 8.4 | Student reading, editing, deleting or restoring another student's record | `404` on all four verbs; the row is byte-identical afterwards |
| 8.5 | Student filing a record under another student's category | `404` from the service; `sp_validate_transaction` refuses the direct insert as well |
| 8.6 | Body carrying `userId`, `type`, `source`, `isDeleted`, `deletedAt`, the AI columns, the flags or the origin links | Ignored; the row is owned by the caller, is `MANUAL`, and is live |
| 8.7 | Body claiming `source: "RECURRING"` to unlock a future date | Ignored; the date is still refused |
| 8.8 | Body with `amount: 0`, `-1.00`, `1.005`, or 14 digits | `400` with a `fieldErrors` entry naming `amount` |
| 8.9 | Date of tomorrow, in and outside `Asia/Ho_Chi_Minh` | `400` naming `txnDate`; the boundary is the same day the database session is pinned to |
| 8.10 | Duplicate request (double-clicked save) | Two records, deliberately — a ledger has no natural key. `identicalRecordsAreNotDeduplicated` pins it as correct rather than a defect |
| 8.11 | Duplicate delete, sequential and concurrent | `409 TRANSACTION_ALREADY_DELETED`; one deletion, one history row |
| 8.12 | Two concurrent deletes | Row lock; exactly one deletion, the other answers `409`, no `500` |
| 8.13 | Two concurrent edits | Serialised; neither loses an update; the history records what actually changed |
| 8.14 | Restoring a live record | `409 TRANSACTION_NOT_DELETED`, not a silent success |
| 8.15 | Malformed JSON body, malformed path id, malformed date parameter | `400`; no stack trace, no SQL, no class names, no schema identifier in the response |
| 8.16 | Enum sent as a number or in lower case | Rejected per field (project-wide Jackson setting) |
| 8.17 | Stale token / revoked session / disabled account | `401` before the controller is reached; the disabled case is deliberately indistinguishable from a revoked one |
| 8.18 | Direct `DELETE FROM transactions` | Refused by `trg_transactions_before_delete` with `BR-09` in the message; the row survives |
| 8.19 | Partial failure | A refused write leaves the record, its history and the budget alert log untouched — the trigger and the alert commit or roll back together |
| 8.20 | Empty data state | `[]`, not `null` and not `404` |
| 8.21 | A record in the trash | Absent from the list, absent from every figure, `404` by id, present with `includeDeleted=true` |
| 8.22 | Application restart | No in-memory state; the row is the authority, so a restart is transparent |
| 8.23 | A future-dated `RECURRING` row | Absent from an unbounded list, present with `to` — the limit from §5.4/§5.6, pinned by a test rather than left as a surprise for module 5 |

No defect survived this pass other than §5.3–§5.7, found here and fixed. §5.6 is the one deferred: the
behaviour is correct for this module and the decision belongs to module 5.

## 9. Traceability

| UC / BR | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|
| UC-07 record | `POST /transactions` | `createTransaction` | `create` | `transactions` INSERT; `trg_transactions_before_insert` | `creatingAnExpenseDerivesEverythingFromTheCategory` |
| UC-10 list | `GET /transactions` | `listTransactions` | `listTransactions` | `findForStudent` on `ix_txn_user_date` | `emptyStateIsAnEmptyArray`, `recordsAreOrderedNewestFirstWithATotalOrder` |
| UC-10 range | `GET /transactions` | `listTransactions` | `EARLIEST_DATE`, `today()` | `txn_date` bounds, both inclusive | `dateRangeIsInclusiveAndEitherBoundMayBeOmitted`, `futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven` |
| UC-10 read one | `GET /transactions/{id}` | `getTransaction` | `requireActiveTransaction` | `findActiveByIdAndUserId` | `ownTransactionCanBeReadById`, `deletedRecordIsNotFoundById` |
| UC-10 edit | `PATCH /transactions/{id}` | `updateTransaction` | `update` | `transactions` UPDATE; `trg_transactions_after_update` | `partialUpdateLeavesTheOtherFieldsAlone`, `emptyUpdateBodyChangesNothing`, `updateRecordsOnlyWhatChanged` |
| UC-10 delete | `DELETE /transactions/{id}` | `deleteTransaction` | `delete` | `sp_soft_delete_transaction`; `trg_transactions_before_delete` | `deleteIsSoftAndKeepsTheRecord`, `hardDeleteIsRefusedByTheDatabase`, `deletingTwiceIsAConflict` |
| UC-10 A1 restore | `POST /{id}/restore` | `restoreTransaction` | `restore` | `sp_restore_transaction` | `restoreBringsTheRecordBack`, `restoringALiveRecordIsAConflict` |
| BR-02 ownership | all six | `@AuthenticationPrincipal` | every `require...` lookup | `find*ByIdAndUserId`; `sp_validate_transaction` for the category | `anotherStudentsTransactionIsUnreachable`, `anotherStudentsCategoryIsRefusedByTheDatabaseToo` |
| BR-03 revocation | all six | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.status`, `users.token_version` | `revokedSessionIsRefused`, `disabledAccountIsRefusedAsUnauthenticated` |
| BR-05 the category decides the type | `POST`, `PATCH` | — | `create`, `update` | `categories.type`; no `transactions.type` column | `theCategoryAloneDecidesIncomeOrExpense`, `movingToAnotherCategoryChangesTheType` |
| BR-07 a retired category takes no new record | `POST`, `PATCH` | — | `requireActiveCategory` | `sp_validate_transaction`, `require_active = 1` on insert | `retiredCategoryCannotBeUsedForANewRecord`, `recordUnderARetiredCategoryStaysEditable` |
| BR-08 no future date | `POST`, `PATCH` | both DTOs | `requireNotInTheFuture` | `sp_validate_transaction`, with the `RECURRING` exemption | `theDateBoundaryIsTodayInTheApplicationZone`, `futureDateIsRefusedOnUpdate`, `futureDateIsRefusedByTheDatabaseToo` |
| BR-08 positive amount | `POST`, `PATCH` | both DTOs | — | `ck_txn_amount`; `DECIMAL(15,2)` | `amountBoundariesMatchTheColumn` |
| BR-09 soft delete only | `DELETE` | — | — | `trg_transactions_before_delete` | `hardDeleteIsRefusedByTheDatabase` |
| BR-09 full history | `POST`, `PATCH`, `DELETE`, restore | — | — | `trg_transactions_after_insert`, `trg_transactions_after_update`, `transaction_history` | `createWritesAFullHistoryRow`, `updateRecordsOnlyWhatChanged`, `restoreBringsTheRecordBack` |
| BR-12 one alert per threshold | `POST`, `PATCH` | — | — | `budget_alert_log` unique key, `sp_check_budget_alerts` | `budgetAlertsAreNotDuplicated` |
| UC-14 alerts follow the record | `POST`, `PATCH` | — | — | `sp_check_budget_alerts` | `recordingAnExpenseRaisesTheBudgetAlerts`, `budgetAlertsRespectTheCategoryAndTheMonth`, `budgetAlertsReachOnlyTheOwner` |
| §7.5 no client identity, provenance or state | `POST`, `PATCH` | DTO field sets | entity setter set | `users.role`; entity `updatable = false` | `createBodyCannotSetServerOwnedFields`, `updateBodyCannotSetServerOwnedFields`, `claimingRecurringDoesNotUnlockFutureDates` |
| §7.6 no schema identifiers out | all writes | — | `translateWriteFailure` | — | `SecurityHardeningIT.transactionRefusalsKeepTheSchemaOutOfEverything` |
| §7.7 no internals in a response | all six | — | `TransactionWriteFailure` | — | `refusalsDoNotLeakDatabaseInternals`, `malformedBodyIsRejectedWithoutLeakingInternals`, `malformedDateParameterIsRejected` |
| §13 P5 concurrency | `DELETE`, `PATCH` | — | `@Lock(PESSIMISTIC_WRITE)` | `SELECT ... FOR UPDATE` | `simultaneousDeletesProduceOneDeletion`, `simultaneousEditsAreSerialised` |
| §13 P5 refusal classification | — | — | `TransactionWriteFailure` | — | `TransactionWriteFailureTest` |
| UC-10 UAT: empty state | `GET /transactions` | — | — | empty `transactions` | `emptyStateIsAnEmptyArray` |

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` |
| Every field traced to a documented requirement | Yes — §9 |
| Validation with per-field errors using `field` | Yes |
| Ownership enforced server-side, structurally | Yes — every single-row query takes the owner's id |
| No sensitive field in any response | Yes — `TransactionMapper` omits `userId` |
| Tests through HTTP against real MySQL 8 | Yes — 57 HTTP tests + 9 unit tests |
| No schema change; `validate` holds | Yes |
| API document written; Angular can integrate without guessing | Yes — `docs/api/transactions.md`, including the rewiring the current mock needs |
| Inventory updated; no duplicate endpoint | Yes |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 214 tests |
| All artifacts English | Yes |
| No mandatory requirement incomplete | Yes — the one deferred item is a bound on a default, not a missing feature, and it is overridable by a parameter the client already has |

## 11. Deferred / blocked

Nothing in this module is blocked. One item is deferred, and three global items affect it and are
recorded in `OVERNIGHT_BLOCKERS.md`.

**Deferred to module 5:**

- **The list's default upper bound is today**, so a future-dated `RECURRING` occurrence is not in an
  unbounded list until its date arrives (§5.4, §5.6). Correct here — no row this API creates can be
  dated ahead — and overridable today by sending `to`. Module 5 decides whether an occurrence should
  appear in advance; the parameter it would need is already in the contract, so no contract change is
  implied either way. Pinned by `futureRecurringRowsAreHiddenUnlessAnUpperBoundIsGiven` so it cannot
  be inherited unnoticed.

**Global, not fixable in this module:**

- **OB-001** — the SRS and Use Case `.docx` are not on disk, so UC-07 and UC-10 wording is derived from
  the schema and the existing design documents rather than quoted.
- **OB-002** — no production email provider. Not used by this module.
- **OB-005** — a database administrator can bypass `sp_require_admin`. This module does not depend on
  that gate; recorded because it is a project-wide limitation.

**Recorded rather than blocked:** the dev profile sets `org.hibernate.SQL: DEBUG`, so Hibernate logs
every statement it issues, including a `CALL` to the soft-delete procedure. The parameters are
placeholders and no data is exposed, and suppressing it would mean disabling Hibernate's SQL logging
altogether. The module's own lines are clean and that bound is stated in §5.3, in `transactions.md`
and in the test's Javadoc.
