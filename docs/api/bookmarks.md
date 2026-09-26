# Bookmarks / Notes API

**Endpoints 42–45.** `GET /api/v1/bookmarks` — everything the caller has saved, newest first
(UC-19 B4). `POST /api/v1/bookmarks` — save one of the caller's own tips, with an optional note
(UC-19 B1/B2). `PATCH /api/v1/bookmarks/{id}` — set or clear the note on one saved item (UC-19 B2).
`DELETE /api/v1/bookmarks/{id}` — un-mark it (UC-19 B4).

**The module's central property is that a bookmark points at a tip and the tip belongs to the
database.** Which tips exist, what they say and what they could save are `sp_generate_tips`'s and
`v_dashboard_tips`'s answers (module 9); this module only records *that a student chose to keep
one*, and the one thing it adds of its own is the note they wrote — §5.

**A bookmark is not a pin.** §3.1 is the distinction, and VĐ-03 is the requirement that settled it.

---

## Contents

| § | |
|---|---|
| 1 | [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent) |
| 2 | [Endpoints at a glance](#2-endpoints-at-a-glance) |
| 3 | [Field reference](#3-field-reference) |
| 4 | [Saving, and being told you already did](#4-saving-and-being-told-you-already-did) |
| 5 | [The note — encryption, and the three spellings of "leave it"](#5-the-note--encryption-and-the-three-spellings-of-leave-it) |
| 6 | [Un-marking, and why a dismissed tip stays](#6-un-marking-and-why-a-dismissed-tip-stays) |
| 7 | [The insight branch is refused, not served](#7-the-insight-branch-is-refused-not-served) |
| 8 | [Status codes](#8-status-codes) |
| 9 | [Security properties](#9-security-properties) |
| 10 | [Angular integration notes](#10-angular-integration-notes) |
| 11 | [Traceability](#11-traceability) |

---

## 1. Scope and what is deliberately absent

| Use case | Behaviour |
|---|---|
| UC-19 B1 | Mark one of the student's own tips so it can be found again |
| UC-19 B2 | Add a short note to a marked item, and change or remove it later |
| UC-19 B3 | The system saves it; the marked item survives into a later session |
| UC-19 B4 | The marked list is readable, and an item can be un-marked when it is no longer needed |
| BR-02 | A student acts only on their own rows, enforced server-side |
| VĐ-02 | "Notes" had no requirement of its own, so a note is a short free-text field *on* a bookmark |
| VĐ-03 | "Pin" and "mark" are different acts: pinning keeps a tip on the dashboard, marking saves it into a reference list |

Deliberately not in scope, with the reason:

| Excluded | Why |
|---|---|
| A `userId` parameter | The account is the bearer token's. A saved entry is advice about one student's spending plus text they typed, so there is no identifier to tamper with (BR-02) |
| `GET /api/v1/bookmarks/{id}` | A saved entry is only ever shown as part of the list. A bare `/{id}` read would be the same row without the ordering that gives it meaning — the reasoning that keeps `/tips/{id}` out of module 9 |
| `POST /api/v1/bookmarks/{id}/pin`, `/unpin`, `/star` | Pinning is a different act on a different table, and it lives at `POST /api/v1/tips/{id}/state`. Adding either act to the other's URL is the overlap VĐ-03 exists to prevent — §3.1 |
| `PUT /api/v1/bookmarks/{id}` | A bookmark's target is unchangeable, and `trg_bookmarks_before_update` re-checks that. A route promising to re-point one would promise something the schema declines |
| A route that removes the note | The note is one field of one row; clearing it is `PATCH {"note": ""}` — §5 |
| `DELETE /api/v1/bookmarks` (clear all) | UC-19 B4 is letting go of *an* item. A bulk delete is a destructive action no requirement asks for, and a client that wants it can loop |
| An idempotent `201` on a repeat save | The caller would be told a bookmark was created when none was, and the note they sent would be silently discarded — §4 |
| `/api/v1/bookmarks/export` | A saved list is not a report, and BR-18 is about reports. No use case asks for it |
| An `INSIGHT` target | The column holds it and UC-19 B1 names it, but insights are UC-17 — inside module 12, which is **locked** — and `insights` has no read path anywhere in this repository. It is refused by name — §7 |

---

## 2. Endpoints at a glance

| # | Method | Path | UC | Auth | Success | Failure |
|---|---|---|---|---|---|---|
| 42 | `GET` | `/api/v1/bookmarks` | UC-19 | Bearer, `STUDENT` | `200` | `401` `403` |
| 43 | `POST` | `/api/v1/bookmarks` | UC-19 | Bearer, `STUDENT` | `201` | `400` `401` `403` `404` `409` |
| 44 | `PATCH` | `/api/v1/bookmarks/{id}` | UC-19 | Bearer, `STUDENT` | `200` | `400` `401` `403` `404` |
| 45 | `DELETE` | `/api/v1/bookmarks/{id}` | UC-19 | Bearer, `STUDENT` | `204` | `401` `403` |

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 44, 45 | `id` (path) | The id of one of the caller's saved items | — |

**No endpoint takes a query parameter.** `POST` is the only request with a body beyond one field:
`CreateBookmarkRequest` has exactly three (`itemType`, `itemId`, `note`), and
`UpdateBookmarkNoteRequest` has exactly one (`note`). There is therefore no mass-assignment surface.
`OpenApiContractIT` asserts that no `UpdateBookmarkRequest`, `CreateBookmarkNoteRequest`,
`BookmarkNoteRequest`, `MoveBookmarkRequest` or `CreateInsightRequest` schema exists.

`SecurityConfig` gained one rule, before the `/api/**` catch-all:

```java
.requestMatchers("/api/v1/bookmarks/**").hasRole("STUDENT")
```

A saved entry is the same readable prose about one student's spending that the tips rule protects,
plus text the student typed. Admitting the administrator role would let it reach a named student's
private jottings through a route with no use case for them — and there is no administrative
counterpart at all: no view over `bookmarks` in `db/02_views.sql` and no UC-20…UC-23 operation on
the table, so refusing the role costs nothing.

---

## 3. Field reference

### 3.1 `BookmarkResponse` (all four endpoints)

| Field | Type | Always present | Notes |
|---|---|---|---|
| `id` | integer | yes | Used by the note and un-mark actions |
| `itemType` | string | yes | `TIP` for every entry in this build — §7 |
| `tipId` | integer | yes | The tips endpoint's `id` for the saved tip |
| `tipTitle` | string | yes | The saved tip's headline, as module 9 publishes it |
| `tipBody` | string | yes | The saved advice itself |
| `tipPotentialSaving` | number | yes | What following the advice could save. `0.00` for advice with no figure |
| `tipState` | string | yes | The saved tip's state: `NEW`, `PINNED` or `DISMISSED` — §6 |
| `tipMonth` | string | yes | The month the saved tip is about, `yyyy-MM` |
| `note` | string | **no** | The caller's own note. Omitted entirely when there is none — §5 |
| `createdAt` | string | yes | When the item was saved, in `Asia/Ho_Chi_Minh`. The list is ordered by this |

`userId`, `insightId`, `dedupeKey`, `updatedAt` and `deletedAt` are all **absent**, and
`OpenApiContractIT#bookmarkSchemasMatchTheDocumentedContract` pins the field set to the ten above so
a new column cannot appear in a response silently.

**The tip's own words travel with the bookmark.** UC-19's postcondition is that a marked item can be
looked at again later, so the response carries the tip's title, body and estimated saving rather
than an identifier the client would have to resolve with a second request per row. `tipState` and
`tipMonth` are published for the same reason: the month is what the tips screen is scoped by, and
the state says whether the advice is still new, was pinned, or was dismissed after it was saved.

**The order of the array is the contract.** Newest saved first, with `id` as the tie-break, because
`DATETIME` has second precision and two saves in the same second would otherwise be free to swap
places between two calls of the same endpoint.

### 3.2 `CreateBookmarkRequest` (endpoint 43)

| Field | Type | Required | Notes |
|---|---|---|---|
| `itemType` | string | yes | `TIP` or `INSIGHT`. `INSIGHT` is refused — §7 |
| `itemId` | integer | yes | The `id` the tips endpoint returned. **Must be the caller's own tip** — BR-02 |
| `note` | string | no | At most 255 characters. Trimmed; a blank note stores no note — §5 |

`itemId` is a positive integer. Zero and negative values are refused as a field error before any
query runs, because no such row can exist and a round trip to be told "not found" would be a
pointless one.

### 3.3 `UpdateBookmarkNoteRequest` (endpoint 44)

| Field | Type | Required | Notes |
|---|---|---|---|
| `note` | string | no | The new note, at most 255 characters. Omission or `null` leaves it unchanged; `""` clears it — §5 |

One field, because a bookmark's target cannot change and the note is the only thing about a bookmark
the student authors.

### 3.4 `tipState` — three members, and `DISMISSED` is reachable here

`tipState` is the **saved tip's** state, not the bookmark's, and it uses the same `TipState` type
module 9 publishes. All three members are reachable in a response, which is the difference from
`TipResponse`: `v_dashboard_tips` excludes dismissed rows, so a tip read through module 9 never
shows `DISMISSED` — but this module reads `user_tips` directly, so a tip that was saved and later
dismissed is still listed here carrying `DISMISSED`. That is §6's subject.

### 3.5 Amounts

`tipPotentialSaving` is `DECIMAL(15,2)` and serialises with that scale: `14.00`, not `14`. The
currency is not published on the response — the account's currency symbol is already inside the
tip's rendered body text, inserted there by `fn_render_template` from the `app.currency_symbol`
setting.

### 3.6 `tipMonth` is the tip's month, not the month it was saved

`tipMonth` is `user_tips.period_month` — the month the advice is *about*. `createdAt` is when the
student saved it. They are usually different, and a client that needs "saved in September, about
August" reads both. Confusing them is the mistake M10-04 in the manual procedure is written to catch.

---

## 4. Saving, and being told you already did

`POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": 12}` saves the tip and answers `201`
with the entry. Saving the same tip again answers **`409 BOOKMARK_ALREADY_EXISTS`**.

The unique key `uk_bookmark_dedupe` makes a second row impossible; its generated `dedupe_key` is
`user_id|item_type|tip_id|insight_id`, which is "one student saves one item once" stated exactly.
The collision is *reported* rather than absorbed, for two reasons that both concern what the caller
would conclude:

- **Answering `201` with the existing row would say a bookmark was created when none was.** The
  status is the client's evidence that its write happened.
- **It would discard the `note` the request carried.** A caller who sent a note and received `201`
  would believe it was stored. It was not.

So the remedy is named instead: the item is already in the list, and its note is changed through
`PATCH`. The shape is deliberately the same as `BudgetAlreadyExistsException`, which answers the
same kind of collision for UC-13.

The pre-check is not the guarantee. `BookmarkService.create` asks whether the bookmark exists so the
caller gets a precise message, but two requests racing past that check settle on the database's
answer: the second insert fails on the unique key and is translated to the same `409`.

**Ownership of the tip is the database's to decide.** The service does not load the tip first: the
trigger `trg_bookmarks_before_insert` already answers "is this the caller's tip?" with the row in
hand, and module 9 publishes no read that could load one without taking a write lock. A refusal
arrives as a `SIGNAL` (SQLSTATE `45000`) and is translated into the same `404` a missing tip
produces — §9.3.

---

## 5. The note — encryption, and the three spellings of "leave it"

### 5.1 The note is encrypted at rest

`bookmarks.note` holds a Base64 **AES-256-GCM envelope** — `format(1) || keyVersion(1) || iv(12) ||
ciphertext+tag` — in a `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin` column. The service
encrypts on write (`BookmarkService.create`, `updateNote`) and `BookmarkMapper` decrypts on read, so:

- A direct `SELECT note FROM bookmarks` shows nothing the student typed.
- The API publishes the plaintext to the owner, over TLS, exactly as before.
- The plaintext never reaches a log line. The service logs the user id and the bookmark id and
  nothing else.

**2048 is the ciphertext budget, not the plaintext limit.** A 255-character plaintext produces at
most 2048 envelope characters; the entity still declares `length = 255`, which is the *plaintext*
the column accepts and the bound the request is validated against. A note longer than 255 characters
is refused with a field error on `note` on both write paths.

`ascii_bin` is load-bearing rather than incidental. Under `utf8mb4_unicode_ci`, `'QUJD'` compares
equal to `'qUJD'`, so a case-insensitive collation would make the history trigger's
`OLD.description <=> NEW.description` changed-field check miss real edits. Envelopes are compared
byte-for-byte and the column is declared to say so.

`identicalNotesDoNotShareCiphertext` proves the IV is fresh per operation: two rows carrying the
same words hold different ciphertext, so an attacker reading the database cannot recognise that two
students wrote the same note.

### 5.2 Absent, null and empty mean three different things

| The request sends | The note becomes |
|---|---|
| the field omitted | **unchanged** |
| `"note": null` | **unchanged** |
| `"note": ""` | **removed** (stored as `NULL`) |
| `"note": "   "` | **removed** — a note of spaces is no note |
| `"note": "  text  "` | `"text"` — trimmed |

The two "unchanged" spellings exist because a JSON body has only those two ways to say "I did not
touch this", and a client written in any language has to be able to express it. Clearing therefore
needs a spelling of its own, and the empty string is it — the same convention `UpdateCategoryRequest`
uses. If `null` meant "clear", a client that serialised an absent optional field as `null` would
erase notes by accident.

### 5.3 A note is edited with `PATCH`, not by removing and saving again

Recording a note by deleting the bookmark and creating it anew would:

- give the new row a new `created_at`, moving it to the top of a list ordered by when things were
  saved;
- re-fire `trg_bookmarks_before_update`, whose job is partly to make a bookmark's target
  unchangeable;
- and tell the student their saved item is newer than it is.

The student's intent is to write a note, and `PATCH` is the write that does only that. Whether the
note is absent from the response is also meaningful: **the field is omitted entirely when there is
no note**, so a client can tell "no note" from "an empty note" without a second field. That is why
the field is declared `@JsonInclude(NON_NULL)` and why the documented field list in the test fixture
is filtered by `note` in the two tests that save without one.

---

## 6. Un-marking, and why a dismissed tip stays

### 6.1 `DELETE` is idempotent

Removing an entry answers `204`. Removing an entry that is already gone also answers `204`, because
"It is not in my list" is the end state the caller asked for. A retry, or two devices acting at once,
is not a failure and must not raise an error dialog — the treatment a second logout gets.

Un-marking someone else's entry is also `204`, and the row survives. The caller cannot tell the
difference between "removed mine" and "that was never mine", so `DELETE` cannot be used to discover
which bookmark identifiers exist.

### 6.2 Deleting a bookmark never touches the tip

The foreign key runs from `bookmarks` to `user_tips` with `ON DELETE CASCADE`, so removing a *tip*
takes its bookmarks with it — never the reverse. `Bookmark` declares no cascade of its own, and
`BookmarkRepository` loads by id *and* owner before deleting, so a bookmark belonging to another
student is not found rather than found and refused. The saved advice stays on the tips screen; only
this student's entry in their own list is removed.

### 6.3 A dismissed tip stays in the saved list

Dismissing a tip on the tips screen (`POST /api/v1/tips/{id}/state`) removes it from
`v_dashboard_tips`, so it stops being shown. **It does not remove the bookmark**, and the entry keeps
appearing here carrying `tipState: "DISMISSED"`.

That is VĐ-03 applied consistently: keeping an item and displaying it are different acts. The student
chose to keep this one, and BR-14's dismissal rule is applied where the tips are *read*. Filtering
the row out here would remove something the student did not remove, and the count they see would
disagree with the number of removals it takes to empty their list. B4's remedy for an entry no longer
wanted is `DELETE`, which is a request only the student can make.

---

## 7. The insight branch is refused, not served

`bookmarks.item_type` is `ENUM('TIP','INSIGHT')` and the `CHECK` constraint `ck_bookmark_target`
supports both shapes. UC-19 B1 says "a tip or an insight". **This build serves only `TIP`.**

Insights are **UC-17**, inside module 12, which is locked pending the project owner's approval — and
`insights` has no read path anywhere in the repository: no view in `db/02_views.sql`, no endpoint, no
Java type. Serving the branch would mean exposing a locked module's contract through this one; faking
it would be worse.

So `POST` accepts `itemType` and answers `INSIGHT` with a `400 VALIDATION_ERROR` carrying a field
error on `itemType` whose message names UC-17. It is refused **by name** rather than by narrowing the
enum, because `INSIGHT` is a real value of the column: a narrowed enum would answer with a JSON
parsing failure calling a genuine column value "invalid", which is both untrue and unhelpful.

This is the treatment module 9 gives `LOW_SAVINGS_RATE` — the value exists in the schema and the
module records what it does not serve. In practice `itemType` holds only `TIP` in a response, and the
field is published anyway because the column will carry `INSIGHT` once UC-17 is approved; removing it
now would be a breaking change to add one back. Recorded as **OB-015** in
[`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md).

---

## 8. Status codes

| Endpoint | Status | Code | When |
|---|---|---|---|
| 42 | `200` | — | Always, including an empty list |
| 42, 43, 44, 45 | `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| 42, 43, 44, 45 | `403` | `ACCESS_DENIED` | Authenticated, but the role is not `STUDENT` |
| 43 | `201` | — | Saved |
| 43 | `400` | `VALIDATION_ERROR` | `itemType` is `INSIGHT`; `itemId` missing, zero or negative; `note` over 255 characters |
| 43 | `404` | `NOT_FOUND` | No such tip, **or** a tip that is not the caller's — §9.3 |
| 43, 44 | `404` | `NOT_FOUND` | No such bookmark of the caller's |
| 43 | `409` | `BOOKMARK_ALREADY_EXISTS` | The tip is already in the caller's list — §4 |
| 44 | `200` | — | The entry, with its note |
| 44 | `400` | `VALIDATION_ERROR` | `note` over 255 characters |
| 45 | `204` | — | Removed, or was already gone |

**An empty list is `200`, not `404`.** A student who has saved nothing has an empty reference list,
which is a fact about their own data rather than a missing resource.

Errors use the standard envelope `{timestamp, status, errorCode, message, path, fieldErrors[]}`. The
`errorCode` is the contract; `message` is human-readable and may be reworded. No driver message,
SQLSTATE, constraint name or trigger text is ever forwarded — `anotherStudentsTipIsIndistinguishableFromAMissingOne`
asserts the body contains none of `BR-02`, `SIGNAL`, `trg_bookmarks`, `SQLSTATE` or `fk_bookmark_tip`,
and `savingTheSameTipTwiceIsAConflict` asserts the same of `uk_bookmark_dedupe`, `dedupe_key` and
`Duplicate`.

---

## 9. Security properties

### 9.1 Identity comes from the token, and nowhere else

Every method reads the caller with `@AuthenticationPrincipal`. **No endpoint accepts a user id**, and
no method at any layer takes one, so reading another student's saved list is *impossible* rather than
refused. Every query is bound with the caller's own id.

### 9.2 A saved entry is private prose

A bookmark's whole content is advice about one student's spending — naming their categories and the
amounts they spent — plus a note in their own words. It is the same material the tips rule protects,
which is why the `STUDENT` role is required and the administrator role is refused.

### 9.3 Another student's tip is indistinguishable from a missing one

Two different database refusals produce the same answer:

| Refusal | SQLSTATE | Springs as | Translated to |
|---|---|---|---|
| The tip is not the caller's (`trg_bookmarks_before_insert` / `_before_update`) | `45000` | `InvalidDataAccessResourceUsageException` | `404 NOT_FOUND` |
| No such tip (`fk_bookmark_tip`) | `23000` | `DataIntegrityViolationException` | `404 NOT_FOUND` |

A caller who could tell which happened could enumerate other students' tip identifiers one request at
a time (section 7.5). The same indistinguishability is what `findByIdAndUserId` achieves by returning
empty for both cases.

The classification is done by `BookmarkWriteFailure`, which asks the database **by SQLSTATE and by
constraint name**, never by matching the driver's message text — so a reworded trigger is still
recognised as the same rule. Anything it does not recognise is rethrown unchanged and answered as a
generic conflict or an internal error rather than mislabelled as a rule the caller could act on. The
refusal itself is deliberately **not** logged with its message: MySQL's duplicate-key text carries the
constraint name and the dedupe key, and the trigger's names the rule and the table.

### 9.4 What is deliberately not claimed

- **The note is protected at rest, not in transit by this module.** Transport security is TLS, a
  deployment concern; see [`docs/SECURITY.md`](../SECURITY.md).
- **The tip's own text is not encrypted, and it is not student-authored either.** `user_tips.title`
  and `body` are rendered by `sp_generate_tips` from a `tip_templates` row and read by SQL, so they
  are outside the "encrypt free text that no SQL statement reads" rule (SECURITY.md §12.2). A
  bookmark copies them into a response; it does not change how they are stored.

---

## 10. Angular integration notes

**The current frontend does not call this API.** There is no saved-items screen. The nearest thing is
`InsightsComponent` at `frontend/src/app/features/insights/insights.component.ts`, and its bookmarks
are entirely client-side: `InsightService` holds `MOCK_INSIGHTS` in a signal, toggles an
`isBookmarked` boolean on the object, and persists the ids to `localStorage` under
`campus_coin_bookmarked_insights`. Nothing in the frontend source is changed by this module. This
section records what a rewiring would have to reconcile.

### 10.1 The mock's model is not this contract

`frontend/src/app/core/models/insight.model.ts` describes `MonthlyInsight` — one long narrative per
month (`narrativeSummary`), a human month label (`"September 2026"`), an optional category flag, and
`isBookmarked`. That is **UC-17's monthly insight**, which belongs to module 12. It is not a saving
tip, and it is not the row this module saves.

The item this module bookmarks is a `TipResponse` from `GET /api/v1/tips` — the same row the
dashboard's tips block shows. A tips screen and the home screen display the same rows, and this
module's saved list is a third view of them.

### 10.2 Which call to make

| Screen element | The endpoint to call |
|---|---|
| The saved-items list | `GET /api/v1/bookmarks` |
| The "save this tip" action on a tip card | `POST /api/v1/bookmarks` with `{"itemType":"TIP","itemId":<tip.id>}` |
| A note editor on a saved row | `PATCH /api/v1/bookmarks/{id}` with `{"note":"…"}`, or `{"note":""}` to clear |
| The "let this go" action | `DELETE /api/v1/bookmarks/{id}` |
| The "keep it on my dashboard instead" action | **Not this module.** `POST /api/v1/tips/{id}/state` with `{"state":"PINNED"}` — §3.1 / VĐ-03 |

### 10.3 Divergences to reconcile

| # | The mock does | The contract does | Consequence |
|---|---|---|---|
| 1 | A bookmark is `isBookmarked: boolean` on a `MonthlyInsight`, kept in `localStorage` | A bookmark is a row with its own `id`, kept in the database | The saved list survives a reload, a sign-out and a device change; `localStorage` must be dropped or it will disagree with the server |
| 2 | The bookmark's subject is a monthly narrative | The bookmark's subject is a per-category saving tip | The saved list shows tips, not paragraphs — the item ids come from `GET /api/v1/tips` |
| 3 | Bookmarking and "pinned tips" are the same flag | Bookmarking is this module; pinning is `user_tips.state` and a different route | The UI's "Pinned Tips" tab is two features wearing one word — split it, or the student's pins and their saved items will drift apart (VĐ-03) |
| 4 | There is no note | A note is the one thing a bookmark adds | Adding a note needs `PATCH`; an edit must not re-create the row (§5.3) |
| 5 | There is no "let it go" action; un-bookmarking is a toggle | `DELETE` is a separate request and idempotent | A toggle can be mapped to POST-or-DELETE, but the client must handle `409` on the POST branch by treating the item as already saved |
| 6 | The count shown is `insights.filter(i => i.isBookmarked).length` | The count is `GET /api/v1/bookmarks`'s array length | Counting a client-side list will disagree with the server as soon as a second device is used |
| 7 | `id: 'ins-2026-09'` — a string | Integer | Type change |
| 8 | `avatarUrl`, `categoryFlag`, `createdAt` | None of them | Drop the avatar, or supply a static one client-side |
| 9 | A dismissed insight is not modelled | A dismissed **tip** stays in the saved list carrying `tipState: "DISMISSED"` | A UI that hides saved entries whose tip was dismissed will show fewer rows than the server returns. Decide deliberately: the contract's position is to show it (§6.3) |

### 10.4 Handling each status

| Response | What the client should do |
|---|---|
| `200` with an empty array | A real answer. Show "nothing saved yet" with a link to the tips screen, not an error |
| `201` | Saved. Use the returned `id` for the note and un-mark actions — do not assume it |
| `204` | Removed, or was already gone. Either way the row leaves the list, with no error |
| `409` on `POST` | The item is already saved. Do not show an error; either navigate to the existing entry or send `PATCH` to set the note the user just typed |
| `400` on `POST` with `fieldErrors[0].field === "itemType"` | The UI offered something this build does not serve. Show the message (it names UC-17) rather than retrying |
| `400` on a note over 255 characters | `fieldErrors[0].field` is `note`. Show the count and let the user shorten it |
| `404` on `POST` | The tip is gone, or was never the caller's. Re-read the tips list and refresh the screen |
| `404` on `PATCH` / implicit on `DELETE` | The entry is gone or was never theirs. Re-read `GET /api/v1/bookmarks` |
| `401` | The token is gone or revoked — return to sign-in |
| `403` | The session is not a student — the sign-in screen, not a retry |
| — | **Never resolve the tip per row.** `tipTitle`, `tipBody`, `tipPotentialSaving`, `tipState` and `tipMonth` are already in the response — §3.1 |

---

## 11. Traceability

| UC / BR | Requirement | API | Controller | Service | DB object | Test |
|---|---|---|---|---|---|---|
| UC-19 B1 | Mark one of the student's own tips | `POST /bookmarks` | `createBookmark` | `create` | `bookmarks` + `trg_bookmarks_before_insert` | `savingATipReturnsTheEntryWithTheTipsOwnWords` |
| UC-19 B1 | An insight is named in the flow | `POST /bookmarks` | `createBookmark` | `requireTipTarget` | `bookmarks.item_type` (`ENUM`) | `anInsightIsRefusedByName` — refused by name, §7 |
| UC-19 B2 | Add a short note to the marked item | `POST /bookmarks`, `PATCH /{id}` | `createBookmark`, `updateNote` | `create`, `updateNote` | `bookmarks.note` (encrypted) | `aNoteIsCiphertextAtRestAndPlaintextThroughTheApi` |
| UC-19 B2 | Change or remove the note later | `PATCH /{id}` | `updateNote` | `updateNote` | `bookmarks.note` | `anEmptyStringClearsTheNote`, `aBlankNoteStoresNoNote`, `editingANoteKeepsTheSavedTimeAndPlace` |
| UC-19 B3 | The system saves it | `POST /bookmarks` | `createBookmark` | `saveAndFlush` | `bookmarks.created_at` default | `savingATipReturnsTheEntryWithTheTipsOwnWords` |
| UC-19 B3 | The marked item is reachable in a later session | `GET /bookmarks` | `listBookmarks` | `list` | `ix_bookmark_user (user_id, created_at)` | `aSavedItemSurvivesIntoAnotherSession` |
| UC-19 B4 | Read the marked list | `GET /bookmarks` | `listBookmarks` | `list` | `BookmarkViewDao` over `bookmarks` + `user_tips` | `theListIsNewestFirst` |
| UC-19 B4 | Let an item go | `DELETE /{id}` | `deleteBookmark` | `delete` | the row, via `fk_bookmark_user` | `unmarkingRemovesTheEntryAndLeavesTheTip` |
| UC-19 B4 | A retry of "let it go" is not a failure | `DELETE /{id}` | `deleteBookmark` | `delete` (find-and-if-present) | — | `unmarkingIsIdempotent` |
| BR-02 | A student acts only on their own tips | `POST /bookmarks` | — | — | `trg_bookmarks_before_insert` | `anotherStudentsTipIsIndistinguishableFromAMissingOne` |
| BR-02 | A student reaches only their own list | all | `@AuthenticationPrincipal` | `principal.userId()` | `user_id` bound in every query | `oneStudentsListIsNotAnothers` |
| BR-02 | Ownership cannot be named in a request | all | — | — | — | `OpenApiContractIT#bookmarkSchemasMatchTheDocumentedContract` (no `userId` in any schema) |
| BR-14 | Dismissal is applied where tips are read, not here | `GET /bookmarks` | — | — | `BookmarkViewDao` reads `user_tips` directly, not `v_dashboard_tips` | `aDismissedTipStaysInTheSavedList` |
| VĐ-02 | "Notes" is a short note on a bookmark | `POST`, `PATCH` | both | `trimToNull` | `bookmarks.note` | `aBlankNoteStoresNoNote`, `anAbsentOrNullNoteLeavesTheNoteUnchanged` |
| VĐ-03 | Mark and pin are different acts on different rows | all | — | — | `bookmarks` vs `user_tips.state` | `aDismissedTipStaysInTheSavedList`, `OpenApiContractIT#noEndpointIsDuplicated` (no `/{id}/pin`) |
| §7.5 | Student-only; administrator refused | all | — | — | — | `administratorsAreRefusedAndAnonymousCallersAreNotAuthenticated` |
| §7.5 | A refusal never forwards a constraint or trigger | `POST` | — | `translateWriteFailure` | — | `anotherStudentsTipIsIndistinguishableFromAMissingOne`, `savingTheSameTipTwiceIsAConflict` |
| §7.6 | The note never reaches the log | all | — | `log.info` names ids only | — | `aNoteNeverReachesTheLog` |
| §13 | No duplicate endpoint | all | — | — | — | `OpenApiContractIT#noEndpointIsDuplicated` (the bookmark alias block) |
| §26 | Every endpoint is in the inventory and the document | all | — | — | — | `OpenApiContractIT#documentMatchesTheInventory` |
| SECURITY §12.2 | The note is encrypted at rest | `POST`, `PATCH` | — | `encrypt` / `decryptStored` | `bookmarks.note` | `aNoteIsCiphertextAtRestAndPlaintextThroughTheApi`, `identicalNotesDoNotShareCiphertext` |

---

## Related documentation

- [`docs/api/FRONTEND_API_GUIDE.md`](FRONTEND_API_GUIDE.md) — **start here.** The single entry point
  for the frontend: base URL, interceptors, the shared error contract, the enum reference and the
  master table of all 76 operations
- [`docs/api/API_INVENTORY.md`](API_INVENTORY.md) — endpoints 42–45, and the decisions recorded with
  them
- [`docs/api/tips.md`](tips.md) — the tips this module points at, and the pinning act VĐ-03
  distinguishes from bookmarking
- [`docs/modules/MODULE_10_BOOKMARKS.md`](../modules/MODULE_10_BOOKMARKS.md) — the module report:
  tests, reviews, traceability
- [`docs/testing/manual/MODULE_10_MANUAL_TEST.md`](../testing/manual/MODULE_10_MANUAL_TEST.md) — the
  hand-run procedure
- [`docs/SECURITY.md`](../SECURITY.md) §12.2 — what is encrypted and why `bookmarks.note` is among it
- [`docs/OVERNIGHT_BLOCKERS.md`](../OVERNIGHT_BLOCKERS.md) — OB-015 (the insight branch deferred
  behind locked module 12)
