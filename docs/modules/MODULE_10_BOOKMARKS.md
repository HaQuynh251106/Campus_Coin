# Module 10 — Bookmarks / Notes (UC-19)

| | |
|---|---|
| **Endpoints** | `GET /api/v1/bookmarks` (42), `POST /api/v1/bookmarks` (43), `PATCH /api/v1/bookmarks/{id}` (44), `DELETE /api/v1/bookmarks/{id}` (45) |
| **Requirements** | UC-19 (B1–B4), BR-02, BR-14, VĐ-02, VĐ-03, SRS §7.5, §7.6, §13, §26; SECURITY §12.2 |
| **Schema objects read** | `bookmarks`, `user_tips` (joined directly, not through `v_dashboard_tips`) |
| **Schema objects written** | `bookmarks` — by Hibernate only. **No schema object was changed** |
| **Tests** | 33 new (22 `BookmarksApiIT` + 10 `BookmarkWriteFailureTest` + `OpenApiContractIT` 12 → 13); suite 535 → **568** |
| **Status** | Complete. One deferred branch (the `INSIGHT` target), recorded as **OB-015** |

---

## 1. Scope

UC-19 is the saved-items screen: a student marks one of their own saving tips so it can be found
again later, may write a short note about why, reads the list, and lets an item go when it is no
longer needed.

**The module's defining fact is that it owns almost nothing.** Which tips exist, what they say and
what they could save are `sp_generate_tips`'s and the tip module's answers. This module records
*that a student chose to keep one* and adds the single piece of text the student authors. §3.1 is
the split, stated column by column.

**A bookmark is not a pin, and the whole module is shaped around keeping those apart.** VĐ-03
settled it: pinning is `user_tips.state = 'PINNED'` and it controls display order, so a pinned tip
leads the dashboard; marking is a row in `bookmarks` and it controls what survives a session.
Neither endpoint can do the other's act, and there is deliberately no `/pin` route here (§3.4).

**Four endpoints, one per act UC-19 names.**

| # | Endpoint | Carries |
|---|---|---|
| 42 | `GET /api/v1/bookmarks` | The marked list, newest first |
| 43 | `POST /api/v1/bookmarks` | Mark one of my tips, optionally noting why |
| 44 | `PATCH /api/v1/bookmarks/{id}` | Change or clear the note |
| 45 | `DELETE /api/v1/bookmarks/{id}` | Let the item go |

All four are `STUDENT`-only. §2 lists what was considered and rejected with the reason.

### What is deliberately not built

| Excluded | Why |
|---|---|
| `userId` anywhere | The account is the bearer token's. A saved entry is one student's advice plus their own note, so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/bookmarks/{id}` | A saved entry is only shown as part of the list, which orders it by when it was saved. A bare `{id}` read would be the same row without the ordering that gives it meaning (§3.6) |
| `POST /{id}/pin`, `PATCH {"pinned": true}` | Pinning is a different act on a different table, under `POST /api/v1/tips/{id}/state`. Adding either act to the other's URL is the overlap VĐ-03 exists to prevent (§3.4) |
| `PUT /api/v1/bookmarks/{id}` | A bookmark's target is unchangeable and `trg_bookmarks_before_update` re-checks it. A route promising to re-point one would promise something the schema declines |
| A note-removal route (`/note`, `DELETE /{id}/note`) | The note is one field of one row. `PATCH {"note": ""}` clears it, and a second URL would be a second way to write one column (§5.2) |
| `DELETE /api/v1/bookmarks` (clear all) | B4 is letting go of *an* item. A bulk delete is destructive and no requirement asks for it |
| A `201` on a repeat save | It would report a creation that did not happen and silently discard the note the request carried (§4) |
| `/export` | A saved list is not a report; BR-18 is about reports (§13) |
| An `INSIGHT` target | The column holds it and B1 names it, but insights are UC-17 — module 12, **locked** — and `insights` has no read path in the repository. Refused by name instead (§7) |

---

## 2. Endpoints

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 42 | `GET` | `/api/v1/bookmarks` | UC-19 B4 | Bearer, `STUDENT` | `200` | `401` `403` |
| 43 | `POST` | `/api/v1/bookmarks` | UC-19 B1/B2 | Bearer, `STUDENT` | `201` | `400` `401` `403` `404` `409` |
| 44 | `PATCH` | `/api/v1/bookmarks/{id}` | UC-19 B2 | Bearer, `STUDENT` | `200` | `400` `401` `403` `404` |
| 45 | `DELETE` | `/api/v1/bookmarks/{id}` | UC-19 B4 | Bearer, `STUDENT` | `204` | `401` `403` |

Parameter surface: `id` in the path on 44 and 45, and nothing else. `POST`'s body has three fields
(`itemType`, `itemId`, `note`) and `PATCH`'s has one (`note`), so there is no mass-assignment
surface. `OpenApiContractIT#bookmarkSchemasMatchTheDocumentedContract` asserts that no
`UpdateBookmarkRequest`, `CreateBookmarkNoteRequest`, `BookmarkNoteRequest`, `MoveBookmarkRequest` or
`CreateInsightRequest` schema exists.

`SecurityConfig` gained one rule, before the `/api/**` catch-all:

```java
.requestMatchers("/api/v1/bookmarks/**").hasRole("STUDENT")
```

A saved entry is the same readable prose about one student's spending that the tips rule protects,
plus text the student typed. Administrator work has no counterpart here — no view over `bookmarks` in
`db/02_views.sql` and no UC-20…UC-23 operation on the table — so refusing the role costs nothing.

---

## 3. Database alignment

### 3.1 What the database owns, and this module therefore does not restate

| Concern | Owner | Where |
|---|---|---|
| That the tip saved is the caller's own | DB | `trg_bookmarks_before_insert` / `trg_bookmarks_before_update` compare the target's owner with `NEW.user_id` and `SIGNAL` (BR-02) |
| That an item is saved once | DB | `uk_bookmark_dedupe`, whose generated `dedupe_key` is `user_id\|item_type\|tip_id\|insight_id` |
| Which target columns a row may carry | DB | `ck_bookmark_target` — `tip_id` for `TIP`, `insight_id` for `INSIGHT`, exactly one |
| What the tip says, and what it could save | DB | `user_tips`, written by `sp_generate_tips` (module 9) |
| When the item was saved | DB | `bookmarks.created_at` default — what the list orders by |
| That removing a tip removes its bookmarks | DB | `fk_bookmark_tip` with `ON DELETE CASCADE` |

`BookmarkService` never composes advice, never decides which tip to offer, never re-ranks anything.
The only content it produces is the student's own note, encrypted (§5).

### 3.2 The module's own work

- That only the tip branch is served, and the refusal of the other (§7).
- That a note is encrypted before it is stored, and decrypted on the way out (§5).
- That clearing a note has a spelling distinct from not touching it (§5.2).
- The refusal of a duplicate save in words the caller can act on (§4).
- That the caller cannot reach another student's bookmark, structurally (§3.3).

### 3.3 The read joins `user_tips` directly, not `v_dashboard_tips`, and that is the point

`BookmarkViewDao` reads `bookmarks` `LEFT JOIN` `user_tips`, **not** the tips module's view.
`v_dashboard_tips` carries `WHERE state <> 'DISMISSED'`, so reading through it would silently drop
every saved entry whose tip was later dismissed — a row the student kept and did not remove (§6.3).
Joining the table is what makes `aDismissedTipStaysInTheSavedList` true rather than aspirational.

`LEFT JOIN` rather than `INNER JOIN` is the second half of the same decision: if a tip row were ever
absent, the bookmark would still be listed with a null tip rather than vanishing from the student's
list. In practice the foreign key makes that unreachable, and the join direction documents which of
the two rows the list is *about*.

### 3.4 Where the pin/bookmark boundary is enforced

| Act | Route | Table and column | Effect |
|---|---|---|---|
| Pin / unpin / dismiss a tip | `POST /api/v1/tips/{id}/state` | `user_tips.state` (+ its paired timestamp) | Display order, and whether the tip is shown at all |
| Mark / note / un-mark an item | `/api/v1/bookmarks` (all four) | `bookmarks` | Whether it survives into a later session |

Neither `BookmarkController` nor `TipController` can write the other's column. `Bookmark` has no
`state` field, `UserTip` has no `bookmarked` flag, and `OpenApiContractIT#noEndpointIsDuplicated`
carries an explicit `doesNotContain` block for `/api/v1/bookmarks/{id}/pin`, `/unpin` and
`/insights`, so adding one fails the build.

### 3.5 The entity is loaded to write one column, so `@DynamicUpdate` is applied

`Bookmark` is loaded, its note is replaced, and it is flushed. Without `@DynamicUpdate`, Hibernate
would write every mapped column back — including `user_id`, `item_type`, `tip_id` and `created_at`.
Those are exactly the columns `trg_bookmarks_before_update` re-validates and `uk_bookmark_dedupe`
is built from, so a stale copy would be re-submitted to a trigger whose job is to decide whether the
target is still the caller's. `@DynamicUpdate` narrows the statement to the one column that changed,
which is the same choice `UserTip` makes for a tip's state (module 9 §3.5) and for the same reason:
an entity write must not carry columns the module does not own.

`dedupe_key` is deliberately **unmapped**: it is a `VIRTUAL` generated column that exists so
`uk_bookmark_dedupe` can work, and mapping it would invite Hibernate to write a value the database
computes itself — the choice made for `scope_key` on `Category` and `key_hash` on
`PasswordResetToken`. `insight_id` is unmapped too, because this build never writes it (§7);
`ck_bookmark_target` requires it to be `NULL` for a `TIP` row, and leaving it out of the entity is
what makes writing it impossible rather than merely discouraged.

### 3.6 `db/` is untouched

**A caveat on the usual evidence.** `db/` is **untracked** in this repository (`git status --short
db/` reports `?? db/`, and `git ls-files db/` is empty), so `git diff --stat -- db/` prints nothing
**whatever the files contain** — an untracked path produces no diff. That command therefore cannot
substantiate "`db/` is unmodified" here, and is not offered as proof.

What does substantiate it: no file under `db/` was modified during this module. The schema-level
evidence is stronger than a timestamp, because the module's tests load the real
`db/merged/campuscoin_full.sql` into a fresh MySQL 8 container and exercise the objects it depends
on unmodified — `CampusCoinApplicationTests` under `ddl-auto=validate` proves the `Bookmark` entity
matches the real `bookmarks` table, and `BookmarksApiIT` drives `uk_bookmark_dedupe`,
`ck_bookmark_target`, `fk_bookmark_tip` and both `trg_bookmarks_before_*` triggers against the
shipped definitions.

The one thing that looked like it might need a schema change — the note column being
`VARCHAR(2048)` while the module accepts 255 characters — was already the shape the encryption phase
produced (the envelope budget), not something this module asked for.

---

## 4. Implementation

### 4.1 Layering

```
BookmarkController ──► BookmarkService ──┬──► BookmarkViewDao      (native projection, one query per read)
                                         ├──► BookmarkRepository   (the entity, for the writes)
                                         ├──► EncryptionService    (the note boundary)
                                         └──► BookmarkMapper       (the single publication gate)

                                          BookmarkWriteFailure    (package-private, the refusal classifier)
```

`bookmark/entity/BookmarkRow` is a record, not an entity: every read is a native projection by alias,
so a column added to or reordered in the join cannot break the mapping — the pattern `TipViewDao`,
`DashboardViewDao` and `BudgetConsumptionDao` each established.

### 4.2 A write's response goes through the same read as the list

`create` and `updateNote` both end by reading the row back through `BookmarkViewDao.findOne` and
mapping it, rather than mapping the entity they just wrote. Two properties follow:

- **The client renders one shape.** The created entry is described exactly as it will be described
  from now on — including the note as the database stored it, decrypted, rather than as the request
  spelled it. A request note of `"  padded  "` comes back trimmed, which is the value that is
  actually stored.
- **A vanished row is a fault, not a `404`.** `loadForResponse` throws `IllegalStateException` if the
  caller's own write is not readable back. Either the row exists or something is badly wrong, and
  blaming the caller's request for it would be the wrong answer.

### 4.3 The refusal classifier is a separate, unit-tested class

`BookmarkWriteFailure` — a package-private `final` class in `service/` — answers three questions
about a `Throwable`: is this the dedupe key, is this a `SIGNAL`, is this any other constraint
violation. It is separate from `BookmarkService` on purpose: through the API the duplicate branch is
nearly unreachable (the service checks first, so the key fires only if a competing request commits in
the gap), and a concurrency test can show the right end state but cannot show that *this* branch ran.
`BookmarkWriteFailureTest` reaches it directly.

The classifier walks the cause chain rather than assuming a depth, asks by SQLSTATE and by constraint
**name** rather than by matching the driver's message (so a reworded trigger is still recognised as
the same rule), and answers `false` for anything it does not know so the service's final rethrow is
live code rather than unreachable.

### 4.4 `translateWriteFailure` collapses two refusals into one answer, deliberately

| Refusal | SQLSTATE | Spring type | Translated to |
|---|---|---|---|
| The tip is not the caller's | `45000` | `InvalidDataAccessResourceUsageException` | `404 NOT_FOUND` |
| No such tip | `23000` on `fk_bookmark_tip` | `DataIntegrityViolationException` | `404 NOT_FOUND` |
| Already saved | `23000` on `uk_bookmark_dedupe` | `DataIntegrityViolationException` | `409 BOOKMARK_ALREADY_EXISTS` |
| Anything else | — | — | rethrown unchanged |

The first two collapsing is the security property: a caller who could tell them apart could
enumerate other students' tip identifiers one request at a time (§7.5).

**The exception is deliberately not logged.** MySQL's duplicate-key message carries the constraint
name and the dedupe key, and the trigger's names the rule and the table — all internal identifiers
the response already withholds. The log line names the user id and the tip id, which describe the
refusal well enough to investigate it.

### 4.5 The note's encryption boundary

`Bookmark#setNote(String)` and `Bookmark#newTipBookmark(...)` take an **encrypted** value, and their
parameter is named `encryptedNote`. This is the design decision that makes the boundary survivable:
the entity has no setter that accepts plaintext, so no other caller can store a note in the clear by
forgetting a step. `BookmarkRow` names the same field `encryptedNote` for the same reason, and
`BookmarkMapper` is the only place that calls `decryptStored`.

`trimToNull` runs before `encrypt`, so the trim is part of what is encrypted — "you wrote spaces" and
"you wrote nothing" produce the same `NULL`, and a padded note stores the trimmed text rather than a
padded envelope.

---

## 5. Defects found and fixed

### 5.1 A self-contradictory pair of assertions, caught by the first full-suite run

`savingATipReturnsTheEntryWithTheTipsOwnWords` asserted that the response's field set equals the
documented ten-field list — while the same test, two lines later, asserted that `note` is **absent**
because the request sent none. The two assertions cannot both hold: `note` is
`@JsonInclude(NON_NULL)`, so a no-note save omits it.

The suite failed exactly once, on exactly this test:

```
[a saved entry must publish exactly the documented fields]
Expecting actual: [… "tipMonth", "createdAt"]
to contain exactly in any order: [… "tipMonth", "note", "createdAt"]
but could not find the following elements: ["note"]
```

**Fixed by making the assertion say what the test means** — the documented fields except the one
that is optional — rather than by weakening it into a subset check, which would have stopped
asserting that nothing *extra* is published. The same expression already existed correctly in
`aBlankNoteStoresNoNote`; the two are now written the same way. This is a test fix, not a
production-code change: omitting an absent optional field is the intended, documented behaviour.

### 5.2 A duplicated constant that would not have compiled

`AbstractBookmarksApiIT` used `longValuesFrom(String, Long, LocalDate)` in `theListIsNewestFirst`,
which the fixture base did not declare — the helper existed in the tips module's base and had not
been carried over. Caught by `./mvnw -o -q clean test-compile`, which reported
`cannot find symbol: method longValuesFrom(String,Long,LocalDate)`; fixed by adding the helper
alongside the other database-read helpers.

The same pass caught a missing three-argument overload, `saveTipExpectingCreated(token, tipId, note)`,
which two tests called. Fixed by adding the overload and delegating the two-argument form to it with
`null`, so there is one implementation rather than two.

### 5.3 A helper that needed its own test, and got one

`setNoteToNull` exists separately from `setNote` because `Map.of` refuses null values, so the explicit
JSON `null` spelling cannot be expressed through the general-purpose body builder. That is not a
workaround — it is the subject of `anAbsentOrNullNoteLeavesTheNoteUnchanged`, which is the test that
proves the absent/null/empty distinction is a contract rather than an accident of how the service
happens to read a DTO.

### 5.4 A test named for a typo

`BookmarksApiIT.savingTheSameTipTwiceIsAReflict` — "Reflict" for "Conflict" — was found while
enumerating the class's methods for this report. §27 requires a test name to describe the behaviour it
asserts, and a name with a misspelling in it is read by every future maintainer of the report's
traceability tables and of the API document, which cite it by name. Renamed to
`savingTheSameTipTwiceIsAConflict` here and in both documents that referenced it, so the citations
cannot dangle. Behaviour unchanged; the suite was re-run green afterwards.

---

## 6. Tests

| Class | Tests | Kind |
|---|---|---|
| `bookmark/BookmarksApiIT.java` | 22 | HTTP → Controller → Security → Service → DAO/Repository → MySQL 8 (Testcontainers) |
| `bookmark/service/BookmarkWriteFailureTest.java` | 10 | Plain unit — the refusal classifier, with the exception shapes MySQL and Spring actually produce |
| `support/OpenApiContractIT.java` | 13 | Contract — 1 of its tests is new for this module, 4 others extended |

Full suite: **568 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. Module 9's baseline was
**535**; module 10 adds **33** (22 + 10 + `OpenApiContractIT` 12 → 13).

Per-class totals (`Tests run:` from the surefire report):

| Class | Tests | | Class | Tests |
|---|---|---|---|---|
| `RecurringRuleApiIT` | 88 | | `RecurringRuleWriteFailureTest` | 11 |
| `TransactionApiIT` | 59 | | **`TipsRuleCoverageIT`** | **11** |
| `BudgetApiIT` | 45 | | **`BookmarkWriteFailureTest`** | **10** |
| `CategoryApiIT` | 44 | | `TransactionWriteFailureTest` | 9 |
| `ProfileApiIT` | 31 | | `CategoryWriteFailureTest` | 8 |
| `DashboardApiIT` | 27 | | `AdminAuthApiIT` | 8 |
| `TipsApiIT` | 23 | | `TipsStateConsistencyIT` | 7 |
| **`BookmarksApiIT`** | **22** | | `TipGenerationSchedulerTest` | 7 |
| `EncryptionServiceTest` | 21 | | `CampusCoinApplicationTests` | 1 |
| `SpendingReportApiIT` | 20 | | | |
| `ReportsApiIT` | 20 | | | |
| `NotificationApiIT` | 20 | | | |
| `SecurityHardeningIT` | 19 | | | |
| `AuthenticationApiIT` | 18 | | | |
| `PasswordResetApiIT` | 14 | | | |
| **`OpenApiContractIT`** | **13** | | **Total** | **568** |
| `BudgetWriteFailureTest` | 12 | | | |

### What the two suites cover

**`BookmarksApiIT` (all four endpoints, UC-19).** Saving: the entry carries the tip's own words and
nothing internal, and the tip's title, body and figure are asserted **equal to the tips endpoint's own
answer** rather than re-derived. Reading: the list is newest first with `id` as the tie-break, and a
saved item is still there in a later session (B3, the postcondition). The note: the column holds an
envelope and the API holds the plaintext, two identical notes produce different ciphertext, a blank
note stores nothing, absent and explicit `null` both mean "leave it", an empty string clears it, an
edit keeps the saved time and the list position, 255 characters is the bound on both writes, and
neither the plaintext nor the envelope reaches the log. Letting go: removing the entry leaves the tip
alone, a repeat removal succeeds, and removing another student's entry is a no-op that leaves the row.
Ownership: another student's tip is indistinguishable from a missing one, one student's list is not
reachable from another's token, and saving twice is a `409`. The target: an insight is refused by name,
and a request naming no item or an impossible one is a field error. VĐ-03: a dismissed tip stays in
the saved list. Routing: a method this API does not serve answers `404` when nothing is routed to the
path and `400` when the path serves a different method — the distinction both manual procedures rely
on, and the one place `/bookmarks/{id}` being method-narrow makes it observable. And §7.5: an
administrator is refused while a tokenless caller is unauthenticated.

**`BookmarkWriteFailureTest`** — the classifier directly. A duplicate key recognised through Spring's
wrapper and at greater nesting depth; another integrity violation not reported as a duplicate (and the
reason that matters); a `SIGNAL` recognised as `45000` though Spring types it differently; the same
refusal recognised when its message is not the trigger's current text; a duplicate not mistaken for a
`SIGNAL`; the three recognisers shown distinguishable, which is what makes the service's ordering
load-bearing; something unrelated left unrecognised rather than mislabelled; and a null message or a
missing cause survived, both of which would otherwise turn a refusal into a `500`.

### Test design note

**The fixtures generate a real tip and bookmark it.** A bookmark's whole content comes from a
`user_tips` row, so a hand-inserted tip would test the API against data the generator would never
produce. `generatedTip(token)` calls `POST /api/v1/tips/generate` and takes the row a real caller
would take; a fresh student has no records, so rule 6 supplies the `GENERIC` tip and the fixture never
has to build spending to get one.

**A second, distinct tip for the same student comes from the procedure.** `uk_bookmark_dedupe`
refuses the same item twice and `/generate` only ever runs for the current month, so
`generateATipFor(userId, month)` calls `CALL sp_generate_tips(?, ?, ?)` for a named earlier month —
the same route module 9's fixtures document. The bookmark it produces is a row a student could have
saved last month, which is what makes `theListIsNewestFirst` a real ordering test rather than an
ordering test against two rows saved microseconds apart.

**The note is read straight from the column.** `storedNoteOf(bookmarkId)` plus `decryptField` reads
`bookmarks.note` and decrypts it, rather than reading the note back through the API. Only the raw
column can show that what is stored is an envelope rather than the student's words, and only the
decryption of it can show the envelope round-trips — which is why the trim assertion
(`decryptField(storedNoteOf(...)) == "padded note"`) is proof the round trip happened in the database
rather than in the service's head.

---

## 7. Phase 9 — first review

Reviewed as another developer's PR, against criteria A–V.

| Criterion | Finding |
|---|---|
| A. Requirement traceability | Every field traces to UC-19 or to a joined column (§11, and the API document's §11). Nothing is published that no use case asks for; `insightId`, `dedupeKey` and `userId` are deliberately unmapped |
| B. Duplicate / missing endpoints | None. Four paths, four methods, one per act UC-19 names. `OpenApiContractIT#noEndpointIsDuplicated` carries an explicit `doesNotContain` block naming nine rejected aliases, including `/bookmarks/{id}/pin` and `/bookmarks/{id}/insights` |
| C. Layering | Controller → Service → DAO/Repository → MySQL. `BookmarkRow` is a record, not an entity; no DAO is called from the controller; `Bookmark` never reaches a response |
| D. Validation placement | In the DTOs by `@Valid @NotNull`/`@Pattern` for the shape, and in `requireTipTarget` for the two things a bean-validation annotation cannot express — that `INSIGHT` is out of scope and that a non-positive `itemId` cannot exist. Every refusal names its field |
| E. Error contract | `{timestamp, status, errorCode, message, path, fieldErrors[]}`. **One error code was added**, `BOOKMARK_ALREADY_EXISTS` (409), because a collision the caller can act on is not the same answer as a generic conflict — the shape `BudgetAlreadyExistsException` established for UC-13 |
| F. Transaction boundaries | Reads `readOnly = true`; create, note-edit and delete are writes. Not annotated mechanically — `delete` is a write with a find-then-delete, not a `@Modifying` query, because the load is what applies ownership |
| G. N+1 and fetch strategy | One query per read, none in a loop: the list is one projection, and `findOne` is one row. `open-in-view: false` |
| H. Locking | **No lock is taken, deliberately.** Nothing here is decided from a row's current value — the note edit is a last-write-wins field edit, which is what the caller asked for. The contrast with a tip's state (which *is* decided from the current value and is locked) is recorded in the service javadoc |
| I. Schema coupling | `ddl-auto: validate` holds. Native queries projected by alias; `itemType` and `tipState` carry `columnDefinition`/enum mapping that `validate` accepts against MySQL `ENUM` |
| J. Security | [`docs/api/bookmarks.md` §9](../api/bookmarks.md#9-security-properties): identity from the token only, every query scoped by `user_id`, role enforced in `SecurityConfig` |
| K. Sensitive output | `BookmarkMapper` is the single gate. `userId`, `insightId`, `dedupeKey` and the second timestamps are unmapped, and both `BookmarksApiIT` and `OpenApiContractIT#bookmarkSchemasMatchTheDocumentedContract` compare against literal field lists — the contract test also pins the exact **forbidden** names, so an alias column cannot appear under a different spelling |
| L. Logging | Four lines, all ids: create, note update, removal, and the no-op removal. No note text, no envelope, no token, no driver message. `aNoteNeverReachesTheLog` asserts the plaintext *and* the ciphertext are absent from both streams |
| M. Dead code | `BookmarkRow` is used by the mapper; `BookmarkWriteFailure`'s three recognisers are all called and all tested; `BookmarkItemType.INSIGHT` is reachable — it is the value that produces the refusal, not dead weight |
| N. Naming | `BookmarkViewDao` mirrors `TipViewDao`; `BookmarkMapper` mirrors the other mappers; `BookmarkWriteFailure` mirrors `CategoryWriteFailure` and `BudgetWriteFailure`; the test base mirrors `AbstractTipsApiIT` |
| O. Documentation | [`docs/api/bookmarks.md`](../api/bookmarks.md), inventory rows 42–45, this report, and the manual procedure |
| P. Tests through HTTP | 22 of the 33 new (`BookmarksApiIT`); 10 are unit tests of a classifier whose duplicate branch the HTTP path cannot deterministically reach (§4.3), and 1 is the contract test. Every fixture precondition is built through a public route or the same stored procedure a real generation uses |
| Q. Empty state | Asserted separately: a student with nothing saved gets `200` and `[]`; a note that is only whitespace stores nothing and the field is omitted; removing an entry that is already gone is `204`; an entry whose tip was dismissed is still present. Four different absences with four different answers |
| R. Determinism | The list carries an explicit `id` tie-break, so two entries saved in the same second cannot swap places between two calls. `theListIsNewestFirst` asserts the order **and** the tie-break's effect |
| S. Idempotency | `unmarkingIsIdempotent` for the removal, and the repeated save is explicitly *not* idempotent — it is a `409` — with the reason recorded rather than left as a surprise |
| T. Restart behaviour | No state in memory. Every read is a query; the encryption key is read once at startup and never held per request |
| U. Configurability | Nothing new to configure. The note's bound is a DTO constant and the ciphertext budget is the encryption phase's; neither is a setting, and no setting was invented to avoid a constant |
| V. Language | All artifacts English |

Three test-quality defects were found and fixed in this pass (§5.1 the contradictory assertion, §5.2 the
missing helpers, §5.4 the misspelled test name). No production defect was found, and no criterion
failed.

---

## 8. Phase 10 — adversarial review

Attempts to break the module, and what happened.

| Attempt | Result |
|---|---|
| Read another student's saved list | Impossible — no method at any layer takes a user id. `oneStudentsListIsNotAnothers` proves the other student's list is empty |
| Name another student with a parameter | No parameter can. There is no query parameter anywhere on this controller |
| Save another student's tip | `404`, identical to a tip that does not exist, so tip identifiers cannot be enumerated (§4.4). The trigger's refusal is translated, not forwarded |
| Read the trigger's or the key's name out of a refusal | `anotherStudentsTipIsIndistinguishableFromAMissingOne` asserts the body contains none of `BR-02`, `SIGNAL`, `trg_bookmarks`, `SQLSTATE`, `fk_bookmark_tip` |
| Save the same tip twice | `409 BOOKMARK_ALREADY_EXISTS`, and the constraint name, `dedupe_key` and `Duplicate` are all absent from the body |
| Save the same tip twice from two requests at once | The pre-check may be passed by both; the unique key refuses the second, and the translation is the same `409`. The first draft of the service could have missed this — the pre-check is documented as an optimisation, not the guarantee (§4.4) |
| Save a tip that does not exist | `404` before any row is written, whether the id is real-but-foreign or simply absent |
| Save an item id of `0` or `-1` | `400` with a field error on `itemId`, before any query — no such row can exist |
| Save without naming a kind | `400` with a field error on `itemType`. A server that defaulted it would be guessing which column the id belongs in |
| Save an insight | `400` with a field error naming UC-17, and nothing written. The value is a real column member, so it is refused by name rather than as a JSON parsing failure (§7) |
| Edit a note on another student's entry | `404`, and the row is verified unchanged |
| Edit a note on an entry that does not exist | Same `404` |
| Clear a note by sending `null` | It does not clear — `null` means "leave it", and `""` is the only spelling that clears. A client that serialised an absent optional field as `null` would otherwise erase notes by accident (§5.2) |
| Write a note of 256 characters | `400` on both writes, field error on `note`. The bound is the plaintext limit, not the envelope column's width |
| Write a note of exactly the envelope's length and overflow the column | Impossible — the envelope for a 255-character plaintext is at most 2048 characters, which is the column's width (§5.1) |
| Have two rows with the same note recognised as equal | `identicalNotesDoNotShareCiphertext` — a fresh IV per encryption means the ciphertexts differ |
| Have a note survive a case-insensitive comparison wrongly | The column is `ascii_bin`; `utf8mb4_unicode_ci` would make `'QUJD' = 'qUJD'` and break the history trigger's changed-field check on another table (§5.1) |
| Tap `PATCH` repeatedly to move an entry up the list | It cannot — the saved time is the row's and is not rewritten. `editingANoteKeepsTheSavedTimeAndPlace` asserts `created_at` and the list order are byte-identical |
| Re-point a bookmark at a different tip | No route accepts a target on update, and `PATCH`'s body has one field. `trg_bookmarks_before_update` re-checks the target regardless |
| Re-point a bookmark by writing the entity directly | `insight_id` is unmapped and `tipId` has no setter; only `newTipBookmark` writes a target, and it writes both sides of `ck_bookmark_target` together |
| Have a `PATCH` write back a stale target | `@DynamicUpdate` narrows the statement to `note` (§3.5) |
| Restore a dismissed tip by editing the bookmark | Impossible — the bookmark holds no state. `tipState` is read from `user_tips`, and `aDismissedTipStaysInTheSavedList` shows the bookmark survives while the tip stays dismissed |
| Have a dismissed tip disappear from the saved list | It does not — the read joins `user_tips`, not `v_dashboard_tips` (§3.3) |
| Un-mark someone else's entry and have it vanish | `204` (indistinguishable from removing one's own), and the row is verified present and still the owner's |
| Use the removal route to discover which bookmark ids exist | Both answers are `204`, so there is nothing to discover |
| Delete a bookmark and take the tip with it | Impossible — the cascade runs from `bookmarks` to `user_tips`, never the reverse, and `Bookmark` declares no cascade |
| Delete a tip and leave its bookmarks orphaned | Impossible — `fk_bookmark_tip` is `ON DELETE CASCADE` |
| Use an administrator's token | `403 ACCESS_DENIED` before the controller, on all four |
| Use a missing or malformed token | `401` for `GET`, `PATCH` and `DELETE` |
| Have the note prose leaked to an administrator | Cannot: the role is refused, and the entry is one student's spending advice plus their own words |
| Find `userId` or `insightId` in any payload | Absent. Two tests pin the field sets, the contract test also pinning the forbidden aliases |
| Have a field added to the DTO widen the contract silently | `bookmarkSchemasMatchTheDocumentedContract` compares against literal lists, so the build fails first |
| Have a route added without the inventory being updated | `documentMatchesTheInventory` and the 45-entry `DOCUMENTED_ENDPOINTS` fail, and `noEndpointIsDuplicated`'s block names nine rejected aliases |
| Have a second way to write one column appear | `PATCH /{id}` is the only note write; there is no `/note` sub-resource, and the contract test asserts that schema does not exist |
| Read a saved entry on its own | There is no `/{id}` read: `GET /bookmarks/{id}` is refused (`400`, because the path serves `PATCH` and `DELETE` only). A saved entry is read as part of the list, which carries the ordering that gives it meaning |
| Have the plaintext or the envelope appear in a log | `aNoteNeverReachesTheLog` asserts both are absent from `getOut()` and `getErr()` after a save, an edit and a list |
| Make a list read write something | `readOnly = true`, and the list is re-read in several tests without the row count changing |

**Nothing in this pass failed.** The module refuses what it cannot honour — the `INSIGHT` branch (§7),
a re-pointed target, a bulk delete — rather than bending the schema or the contract to fit.

**One limitation is real and is recorded rather than implied away:** the duplicate-save branch is
reached through the API only when two requests race past the pre-check, so the branch itself is
verified by unit test (§4.3) rather than by an HTTP test. The HTTP test asserts the end state a
collision produces.

---

## 9. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-19 B1 | Mark one of the student's own tips | 43 | `createBookmark` | `create` | `bookmarks` + `trg_bookmarks_before_insert` | `savingATipReturnsTheEntryWithTheTipsOwnWords` |
| UC-19 B1 | An insight is named in the flow | 43 | `createBookmark` | `requireTipTarget` | `bookmarks.item_type` (`ENUM`), `ck_bookmark_target` | `anInsightIsRefusedByName` |
| UC-19 B2 | Add a short note to the marked item | 43, 44 | `createBookmark`, `updateNote` | `create`, `updateNote` | `bookmarks.note` (encrypted) | `aNoteIsCiphertextAtRestAndPlaintextThroughTheApi` |
| UC-19 B2 | Change the note later | 44 | `updateNote` | `updateNote` | `bookmarks.note` | `editingANoteKeepsTheSavedTimeAndPlace` |
| UC-19 B2 | Remove the note | 44 | `updateNote` | `trimToNull` → `null` | `bookmarks.note` | `anEmptyStringClearsTheNote`, `aBlankNoteStoresNoNote` |
| UC-19 B2 | "Leave it" is expressible | 44 | `updateNote` | the `request.note() != null` guard | — | `anAbsentOrNullNoteLeavesTheNoteUnchanged` |
| UC-19 B3 | The system saves it | 43 | `createBookmark` | `saveAndFlush` | `bookmarks.created_at` default | `savingATipReturnsTheEntryWithTheTipsOwnWords` |
| UC-19 B3 | The marked item is reachable later | 42 | `listBookmarks` | `list` | `ix_bookmark_user (user_id, created_at)` | `aSavedItemSurvivesIntoAnotherSession` |
| UC-19 B4 | Read the marked list | 42 | `listBookmarks` | `list` | `BookmarkViewDao` over `bookmarks` + `user_tips` | `theListIsNewestFirst` |
| UC-19 B4 | Let an item go | 45 | `deleteBookmark` | `delete` | `fk_bookmark_user` | `unmarkingRemovesTheEntryAndLeavesTheTip` |
| UC-19 B4 | A repeat removal is not a failure | 45 | `deleteBookmark` | find-and-if-present | — | `unmarkingIsIdempotent` |
| BR-02 | Act only on the caller's own tips | 43 | — | — | `trg_bookmarks_before_insert` | `anotherStudentsTipIsIndistinguishableFromAMissingOne` |
| BR-02 | Reach only the caller's own list | 42 | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `oneStudentsListIsNotAnothers` |
| BR-02 | Ownership cannot be named in a request | all | — | — | — | `OpenApiContractIT#bookmarkSchemasMatchTheDocumentedContract` |
| BR-02 | Another student's entry is not removable | 45 | — | `findByIdAndUserId` returns empty | the `user_id` predicate | `unmarkingSomebodyElsesEntryIsANoOp` |
| BR-14 | Dismissal applies where tips are read, not here | 42 | — | — | `BookmarkViewDao` reads `user_tips` directly | `aDismissedTipStaysInTheSavedList` |
| VĐ-02 | "Notes" is a short note on a bookmark | 43, 44 | both | `trimToNull`; the 255-character bound | `bookmarks.note` | `aBlankNoteStoresNoNote`, `anOverlongNoteIsRefused` |
| VĐ-03 | Mark and pin are different acts on different rows | all | — | — | `bookmarks` vs `user_tips.state` | `aDismissedTipStaysInTheSavedList`, `OpenApiContractIT#noEndpointIsDuplicated` |
| §7.5 | Student-only; administrator refused | all | — | — | — | `administratorsAreRefusedAndAnonymousCallersAreNotAuthenticated` |
| §7.5 | A refusal forwards no constraint or trigger name | 43, 44 | — | `translateWriteFailure` | — | `anotherStudentsTipIsIndistinguishableFromAMissingOne`, `savingTheSameTipTwiceIsAConflict` |
| §7.6 | The note never reaches the log | all | — | `log.info` names ids only | — | `aNoteNeverReachesTheLog` |
| §13 | No duplicate endpoint | all | — | — | — | `OpenApiContractIT#noEndpointIsDuplicated` (the bookmark alias block) |
| §26 | Every endpoint is in the inventory and the document | all | — | — | — | `OpenApiContractIT#documentMatchesTheInventory` |
| SECURITY §12.2 | The note is encrypted at rest | 43, 44 | — | `encrypt` / `decryptStored` | `bookmarks.note` | `aNoteIsCiphertextAtRestAndPlaintextThroughTheApi` |
| SECURITY §12.1 | A fresh IV per operation | 43, 44 | — | `EncryptionService.encrypt` | — | `identicalNotesDoNotShareCiphertext` |
| `uk_bookmark_dedupe` | One student saves one item once | 43 | — | pre-check + translation | `uk_bookmark_dedupe` | `savingTheSameTipTwiceIsAConflict` |
| `uk_bookmark_dedupe` | The collision branch is the one that fired | 43 | — | `BookmarkWriteFailure` | — | `BookmarkWriteFailureTest` (10) |
| `ck_bookmark_target` | A row carries exactly one target | 43 | — | `Bookmark.newTipBookmark` | `ck_bookmark_target` | `savingATipReturnsTheEntryWithTheTipsOwnWords` (nothing else can be written) |

---

## 10. Definition of done

| Check | State |
|---|---|
| Endpoints match the inventory and Swagger | Yes — machine-checked by `OpenApiContractIT` (30 paths, 45 operations) |
| Every field traced to a documented requirement | Yes — §9, and [`docs/api/bookmarks.md` §11](../api/bookmarks.md#11-traceability) |
| Validation with per-field errors using `field` | Yes — `itemType`, `itemId` and `note` each name themselves |
| Ownership enforced server-side, structurally | Yes — no method at any layer takes a user id; the trigger is the second line |
| No sensitive field in any response | Yes — `userId`, `insightId` and `dedupeKey` unmapped; two tests compare against literal field lists, one of them including the forbidden aliases |
| Tests through HTTP against real MySQL 8 | Yes — 22 of the 33; 10 are unit tests of a classifier branch the HTTP path cannot deterministically reach (§4.3) and 1 is the contract test |
| No schema change; `validate` holds | Yes — every schema object this module touches is exercised unmodified against the shipped `campuscoin_full.sql`. `db/` is untracked, so `git diff --stat` is not usable as evidence here (§3.6) |
| API document written; Angular can integrate without guessing | Yes — [`docs/api/bookmarks.md`](../api/bookmarks.md), including the nine mock-vs-contract divergences a rewiring must reconcile |
| Inventory updated; no duplicate endpoint | Yes — endpoints 42–45 |
| First and adversarial reviews performed | Yes — §7, §8 |
| Regression suite green | Yes — 568 tests, 0 failures, 0 errors, 0 skipped |
| All artifacts English | Yes |
| No mandatory requirement incomplete | **Almost** — UC-19 B1's insight target is deferred behind locked module 12 and refused by name; recorded as OB-015, not hidden (§11) |
| Coverage gaps recorded rather than implied away | Yes — the duplicate branch's unit-test route, the mock frontend's divergence list, and the `db/`-integrity evidence's untracked-path caveat (§3.6) |

---

## 11. Deferred / blocked

**One branch of UC-19 is deferred, and it is the only thing in this module that is not built.**

| Item | How it is handled |
|---|---|
| Saving an **insight** (UC-19 B1's second target) | Refused by name with a `400` field error that names UC-17, before any query runs. The schema supports the branch (`item_type`, `ck_bookmark_target`, `fk_bookmark_insight`) and the response publishes `itemType` so adding it later is not a breaking change. **Recorded as OB-015** (§7 of the API document gives the reasoning) |

Properties of the schema this module works around rather than changes, with the reason each is not a
defect:

| Property | How it is handled |
|---|---|
| `v_dashboard_tips` excludes dismissed tips | The read joins `user_tips` directly, because the saved list is a different question from the tips screen (§3.3) |
| No procedure exists for a bookmark write | An entity write with `@DynamicUpdate`, so the statement carries only `note` (§3.5). No procedure exists for a bookmark in `db/03_procedures.sql`, and the trigger already enforces the ownership rule. |
| `bookmarks.note` is `VARCHAR(2048)` for a 255-character plaintext | The envelope budget the encryption phase produced; the DTO validates against the plaintext limit, not the column width (§5.1) |
| `dedupe_key` and `insight_id` are columns the entity must not write | Both unmapped, as `scope_key` and `key_hash` are elsewhere (§3.5) |

**Global items that touch this module:**

| Item | Effect here |
|---|---|
| **OB-001** — the authoritative source documents are not on disk | The same caveat as every module. UC-19's requirements were taken from the UC table in [`docs/ERD.md`](../ERD.md) and cross-checked against `bookmarks`, its two triggers, and module 9's published tip shape — the schema's own statement of what is stored |
| **OB-002** — no production email provider | Unaffected. A bookmark is not delivered by mail; it is read from the screen |
| **OB-003** — production secrets management | Affected in one place: the note is encrypted with `CAMPUSCOIN_ENCRYPTION_KEY`, so this module inherits OB-003's answer for every encrypted column. No new credential is added |
| **OB-004** — throttle counters are per instance | Unaffected. `POST` and `PATCH` are not throttled; each is idempotent in effect (a repeat `POST` is refused rather than duplicating) and costs one indexed write, so the cost is bounded by that rather than by a rate limit — recorded rather than claimed to be protected |
| **OB-012** — the locked M12 surface stays plaintext | Directly related: this module's deferred branch is the *same* module-12 dependency, recorded separately as OB-015 because it is a module-10 scope decision rather than an encryption gap |
| **OB-013** — amounts are deliberately not encrypted | Visible here as the tip's `tipPotentialSaving`, which is a plaintext amount read from `user_tips`. This module publishes it and does not aggregate it, so nothing changes — but a reader should know the figure is not protected at rest |
| **OB-015** — the insight branch waits for module 12 | This module's own item (§11) |

**The frontend is still mock-only and has no saved-items screen.** `InsightsComponent` shows
`MOCK_INSIGHTS` — one long narrative per month, bookmarked in `localStorage` — which is UC-17's shape,
not this contract's. It is **not changed by this module**. [`docs/api/bookmarks.md` §10](../api/bookmarks.md#10-angular-integration-notes)
records the nine divergences a rewiring must reconcile, most importantly that the mock treats
bookmarking and pinning as one boolean while VĐ-03 makes them two acts on two tables.

---

## Related documentation

- [`docs/api/bookmarks.md`](../api/bookmarks.md) — the UC-19 contract, the note's three spellings,
  and why the insight branch is refused rather than served
- [`docs/api/API_INVENTORY.md`](../api/API_INVENTORY.md) — endpoints 42–45, and the decisions
  recorded with them
- [`docs/testing/manual/MODULE_10_MANUAL_TEST.md`](../testing/manual/MODULE_10_MANUAL_TEST.md) — the
  hand-run procedure
- [`docs/modules/MODULE_09_TIPS.md`](MODULE_09_TIPS.md) — the module this one follows: the tips it
  points at, the pinning act VĐ-03 distinguishes from bookmarking, and the `LOW_SAVINGS_RATE`
  refusal this module's insight refusal is shaped after
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — OB-015 (the deferred insight branch),
  OB-012, OB-013
- [`docs/SECURITY.md`](../SECURITY.md) §12 — application-level field encryption, and why
  `bookmarks.note` is among the encrypted columns
