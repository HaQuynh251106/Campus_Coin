# Module 10 — Bookmarks / Notes (UC-19) — Manual Test Procedure

**Scope:** endpoints 42–45 — `GET /api/v1/bookmarks`, `POST /api/v1/bookmarks`,
`PATCH /api/v1/bookmarks/{id}` and `DELETE /api/v1/bookmarks/{id}`, all role `STUDENT` only.

**Sources of truth for this procedure:** [`docs/api/bookmarks.md`](../../api/bookmarks.md) (the
UC-19 contract — §4 why a repeat save is a `409`, §5 the note's three spellings and its encryption,
§6 un-marking, §7 why the insight branch is refused, §10 what a rewiring must reconcile),
[`docs/modules/MODULE_10_BOOKMARKS.md`](../../modules/MODULE_10_BOOKMARKS.md) (§3 what the database
owns, §4 the implementation, §5 the defects found and fixed, §6 which suite drives which behaviour),
[`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 42–45),
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md) (seeded accounts), `db/01_schema.sql` (`bookmarks`,
its generated `dedupe_key`, `uk_bookmark_dedupe`, `ck_bookmark_target`, `fk_bookmark_tip`),
`db/04_triggers.sql` (`trg_bookmarks_before_insert` / `trg_bookmarks_before_update`), and the
implementation under `backend/src/main/java/com/campuscoin/bookmark/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 42 | `GET` | `/api/v1/bookmarks` | `200` the caller's saved list, newest first |
| 43 | `POST` | `/api/v1/bookmarks` | `201` the saved entry |
| 44 | `PATCH` | `/api/v1/bookmarks/{id}` | `200` the entry, with its note changed |
| 45 | `DELETE` | `/api/v1/bookmarks/{id}` | `204` no body |

**Four endpoints, and the property they are built around is that the module owns almost nothing.**
Which tips exist, what each says and what it could save are `sp_generate_tips`'s and module 9's
answers. This module records **that a student chose to keep one** and holds the single piece of text
the student authors. §3.4 is the part of this that is most likely to be misread: a bookmark is not a
pin, and a pin is not a bookmark.

**There is no fifth endpoint.** There is **no** `GET /api/v1/bookmarks/{id}`, **no**
`PUT /api/v1/bookmarks/{id}`, **no** `PATCH`/`DELETE` on a sub-resource (`/{id}/note`), **no**
`DELETE /api/v1/bookmarks` (clear all), **no** `/{id}/pin`, `/{id}/unpin` or `/{id}/dismiss`, **no**
`/insights` route, and **no** `/bookmarks/export`. An endpoint that is not in the table above does not
exist; if a step here asks you to call one, the document is wrong, not the server.

**Nothing takes a user id.** A saved entry is the caller's by construction, so there is no parameter
anywhere that could name another account.

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 44, 45 | `id` (path) | the id of one of the caller's bookmarks | — |

Endpoint 43's body has exactly three fields (`itemType`, `itemId`, `note`); endpoint 44's has exactly
one (`note`). There is no mass-assignment surface.

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema, seed data and demo data applied (`db/01_schema.sql` …
  `db/06_demo.sql`).
- **A saved entry must point at one of your own tips**, so several cases below begin by generating a
  tip through module 9's `POST /api/v1/tips/generate`. That endpoint is a precondition for those
  cases, not the subject of them — this module has no way to create a tip, and none is planned.
- **No scheduler is involved.** Unlike module 9, nothing here runs on a timer. The saved list is the
  rows you wrote and nothing else.
- **For M10-01 the database must be in its freshly seeded state.** That case reads the demo account's
  own advice. Writes made by an earlier module's manual tests change what is on the account. Re-seed
  before M10-01, or read it as "the tip content is whatever the seed plus your earlier testing
  produced" — the *shape* the case asserts does not depend on it.

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
| `${JWT}` | A `STUDENT` access token. | Alex Nguyen — `an.nguyen@student.campuscoin.edu` |
| `${USER_A_JWT}` | The `STUDENT` token of **owner A**, the seeded demo student who already has three months of tips. Same account as `${JWT}`; the ownership cases name it explicitly so the two sides of a case are unambiguous. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of **owner B**, the seeded empty account — no transactions, no tips. She is the clean subject for the empty-list, generate-from-nothing and cross-owner cases. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` access token, obtained from the administrator sign-in endpoint. | System Administrator |

There are no other placeholders in this document. Where a numeric id appears (a bookmark `id`, a tip
`id`, a `categoryId`), it is an **example**; always use the id you recorded in your own run.

```bash
# Example: obtain a student token, then use it without ever writing it down.
# Read the password for the seeded student from docs/CREDENTIALS.md and type it at the
# prompt; it is not repeated in this document.
read -rs CC_PW
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e 'an.nguyen@student.campuscoin.edu' --arg p "$CC_PW" \
        '{email:$e,password:$p}')" \
  | jq -r .accessToken
unset CC_PW
# copy that output into a local variable, e.g.:
# export USER_A_JWT='<the value you copied>'      # not committed, not written to a file
# export JWT="$USER_A_JWT"
```

In Swagger UI: click **Authorize**, paste the token, and it is sent as
`Authorization: Bearer <accessToken>` on every request until you sign out.

**Most cases below use HTTP only.** The `mysql` command appears in §3.3 and in M10-04 and M10-05,
always to **read** what the `bookmarks` table holds — most importantly, to see that the note is
ciphertext. Every write case uses a tip of its own or the demo account's own advice, so no case
disturbs another account.

### 2.3 Dates

- "This month" means the current month in **`Asia/Ho_Chi_Minh` (`+07:00`)** — for example `2026-09`.
  Check it with `TZ=Asia/Ho_Chi_Minh date +%Y-%m`. The database session is pinned to the same offset.
- A **bookmark** carries a saved time (`createdAt`) but no date the caller supplies — the database
  defaults it. There is therefore nothing to date in this procedure, unlike module 9's transactions.
- **BR-08 refuses a future-dated transaction**, which matters only if you need to build a tip to
  bookmark; use `date +%F` for today or an earlier date.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with three months of demo data (`db/06_demo.sql`), including **tips for
  the current month and the two before it** (three tips each). Those are the tips M10-01 bookmarks;
  they are module 9's rows and this module does not change them.
- **Bella Tran** is the seeded **empty** account: no transactions, no budgets, no tips, no bookmarks.
  She is the clean subject for the empty-list and ownership cases, and `POST /api/v1/tips/generate`
  gives her one `GENERIC` tip to bookmark.
- Shared default categories are visible to every student; modules 9 and 10 do not change them. Use
  `GET /api/v1/categories` if you need a real id for another module's fixture.
- **A fresh account has no bookmarks.** The seeded data writes none, so `GET /api/v1/bookmarks` on a
  freshly seeded account returns an empty array `[]` until you save something.

### 3.2 Authentication

All four endpoints require `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403` (M10-09). A missing, malformed, expired or
revoked token answers `401`. There is no anonymous or shared view of anyone's saved items.

### 3.3 What the response looks like

Endpoint 43, saving the demo account's first tip with a note:

```json
{
  "id": 1,
  "itemType": "TIP",
  "tipId": 7,
  "tipTitle": "Your savings goal is at risk",
  "tipBody": "Your income minus spending is currently 71.00, below your goal of 100.00. …",
  "tipPotentialSaving": 29.00,
  "tipState": "NEW",
  "tipMonth": "2026-09",
  "note": "worth checking again at the end of the month",
  "createdAt": "2026-09-25T09:14:03"
}
```

Endpoint 42 answers with a **bare JSON array** — the entries themselves, with no wrapper object:

```json
[ { "id": 1, "itemType": "TIP", … } ]
```

**Seven things about the shape, each pinned by a case below:**

- **The tip's words are the tip's own.** `tipTitle`, `tipBody`, `tipPotentialSaving` and `tipState`
  come from the `user_tips` row the bookmark points at. They are asserted equal to what
  `GET /api/v1/tips` returns for the same tip (M10-01).
- **`note` is absent, not `null`,** on an entry saved without one — the field is
  `@JsonInclude(NON_NULL)` (M10-01, M10-04).
- **`itemType` is `"TIP"`** in every response this build can produce. The `INSIGHT` member is
  refused on the way in (M10-08).
- **`tipState` is read from `user_tips`, not from the bookmark** — so a saved entry can read
  `"DISMISSED"` (M10-07). This is the one place a `DISMISSED` value appears in any response in the
  project, and it is deliberate.
- **`tipMonth` is the tip's month**, not when the bookmark was saved; `createdAt` is when it was
  saved. They are usually different (M10-01).
- **The list is newest first, with the bookmark `id` as the tie-break** (M10-02).
- **An entry carries ten fields and nothing else.** No `userId`, no `insightId`, no `dedupeKey`, no
  `updatedAt`, no `deletedAt` (M10-09).

### 3.4 The decision this module is built around — read this before §4

**A bookmark is not a pin.** VĐ-03 settled it, and the whole module is shaped around keeping the two
apart.

| Act | Route | Table and column | Effect |
|---|---|---|---|
| Pin / unpin / dismiss a tip | `POST /api/v1/tips/{id}/state` | `user_tips.state` | **Display order**, and whether the tip is shown at all |
| Mark / note / un-mark an item | `/api/v1/bookmarks` (all four) | `bookmarks` | **Whether it survives into a later session** |

Neither route can do the other's act. Bookmarking a tip does **not** pin it, and pinning a tip does
**not** bookmark it. **Do not report a missing `/{id}/pin` route here as a gap** — that act lives on
the tips module, and duplicating it would be the overlap VĐ-03 exists to prevent.

**Three more consequences a tester should hold in mind throughout §4:**

- **Saving the same item twice is `409`, not `201`.** `uk_bookmark_dedupe` refuses a second copy, and
  the server answers with `BOOKMARK_ALREADY_EXISTS` rather than silently creating a duplicate or
  silently discarding the note the second request carried (M10-03).
- **The note is encrypted at rest.** What is stored in `bookmarks.note` is an AES-256-GCM envelope,
  not the student's words. M10-04 reads the column directly to check it.
- **The note has three distinct spellings and they mean three different things.** §3.5 is the part of
  this that is most likely to be misread.

### 3.5 The three behaviours a tester is most likely to misread

All three are deliberate and documented. Read them before marking anything in §4 as a failure.

> **A missing note and an explicit `null` both mean "leave it alone".** `PATCH` with `{}` or with
> `{"note": null}` does **not** clear the note — it changes nothing. Only `{"note": ""}` clears it.
> A client that serialised an absent optional field as `null` would otherwise erase notes by accident.
> M10-05 is the case that checks all three spellings.
>
> A note that is **only whitespace** is trimmed to nothing, so `{"note": "   "}` clears it too. And a
> padded note is stored **trimmed**: `{"note": "  text  "}` stores `text`.

> **A saved entry whose tip was later dismissed stays in the saved list.** The list joins `user_tips`
> **directly**, not module 9's `v_dashboard_tips` (which carries `WHERE state <> 'DISMISSED'`).
> Reading through the view would silently drop a row the student kept and did not remove. If you
> dismiss a tip on the tips screen and then open your saved list, **the entry is still there** and its
> `tipState` reads `"DISMISSED"`. That is M10-07, and it is correct.

> **Un-marking an entry that is not there answers `204`, not `404`.** `DELETE /{id}` on an id you
> already removed — or on an id belonging to another student — succeeds with no body, exactly as
> removing your own entry does. This is deliberate: if the two answers differed, the route could be
> used to discover which bookmark ids exist. **Do not report "deleting someone else's bookmark
> succeeded" as a defect** — nothing was deleted; the row is verified present and still its owner's
> (M10-06).

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M10-01 — Saving one of my own tips returns it, with the tip's own words

**Covers:** UC-19 B1, B3; the response shape; the optional note.

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`), freshly seeded (§2.1). Note one tip `id`
from `GET /api/v1/tips` — call it `<TIP_A>`.

**Steps:**

1. `GET /api/v1/tips` with `${USER_A_JWT}` — record the first tip's `id`, `title`, `body`,
   `potentialSaving`, `state` and the response's `periodMonth`.
2. `POST /api/v1/bookmarks` with `${USER_A_JWT}` and body:
   `{"itemType": "TIP", "itemId": <TIP_A>}` — **no `note` field.**
3. Record the full response.
4. `GET /api/v1/tips` again and compare the tip's fields with step 3's.

**Expected result:**

- Step 2: **`201 Created`** (not `200` — the entry was created), with a `Location`-style body that is
  the saved entry itself.
- Step 3 — **the tip's own words, matching step 1 exactly**, field for field:
  `tipTitle` = the tip's `title`, `tipBody` = its `body`, `tipPotentialSaving` = its
  `potentialSaving` (`29.00` for the demo account's first tip), `tipState` = its `state` (`"NEW"`),
  and `tipMonth` = the `periodMonth` step 1 answered.
  **Do not expect the server to have re-composed any of it** — the text was rendered by
  `fn_render_template` from a template row, exactly as it is on the tips screen.
- Step 3 — **`note` is absent from the body, not `null`.** The entry was saved without one, and the
  field is omitted rather than serialised as `null` (§3.3).
- Step 3 — `itemType` is `"TIP"`, `tipId` is `<TIP_A>`, `id` is a **new** id (the bookmark's own, not
  the tip's), and `createdAt` is now, in the local offset.
- Step 3 — **no `userId`, no `insightId`, no `dedupeKey`** anywhere in the body.
- Step 4: the tip is **unchanged on the tips screen.** Bookmarking it did not pin it, did not
  re-rank it and did not alter its text (§3.4). The tips list is byte-for-byte identical to step 1's.

**Result:** [ ] Pass   [ ] Fail

---

### M10-02 — The list is newest first, and a saved item is still there later

**Covers:** UC-19 B3 (the postcondition), B4; the list order and its tie-break.

**Preconditions:** Owner A (`${USER_A_JWT}`). You need **two saved entries**. Save a second tip:
either another of this month's tips, or a tip for an **earlier month** — `POST /api/v1/tips/generate`
only runs for the current month, so if you want a tip in a previous month, ask the database for one
of the demo account's seeded earlier-month tips (`SELECT id, period_month FROM user_tips WHERE …`).

**Steps:**

1. Save the first tip (M10-01 if you have not already). Record its bookmark `id` and `createdAt`.
2. Save a second tip. Record its bookmark `id` and `createdAt`.
3. `GET /api/v1/bookmarks` with `${USER_A_JWT}`.
4. `GET /api/v1/bookmarks` again, twice more.
5. If the second tip is from an **earlier month**, note that both entries are in the list.

**Expected result:**

- Step 3: `200`, and the array is **newest first** — the entry saved in step 2 leads the entry saved
  in step 1. Ordering is by the bookmark's `createdAt`, descending.
- **Two entries saved in the same second still have a stable order.** The tie-break is the bookmark
  `id`, descending, so two entries saved back to back do not swap places between two calls. If your
  two entries have the same `createdAt` to the second, the one with the larger **bookmark** `id` still
  leads.
- Step 4: all three responses are **byte-for-byte identical**. Reading the list writes nothing, so a
  client can re-fetch it freely.
- Step 5: **both entries are present**, including the earlier month's, and each carries its own
  `tipMonth` — the month the tip is about, not the month it was saved in. The list is not restricted
  to the current month. This is the case that shows B3 ("the marked item is reachable later"): the
  entry survives a new session because it is a row, not a screen state.
- **A client must render the array as it arrives** — it is already ordered, and re-sorting by
  `tipTitle` or `tipMonth` would give a different order than the contract's.

**Result:** [ ] Pass   [ ] Fail

---

### M10-03 — Saving the same tip twice is a `409`, and the note the second request carried is not silently taken

**Covers:** UC-19 B1; `uk_bookmark_dedupe`; the collision's answer.

**Preconditions:** Owner A (`${USER_A_JWT}`), with `<TIP_A>` already saved from M10-01.

**Steps:**

1. `POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": <TIP_A>}` — the tip already saved.
2. Read the response's status, `errorCode` and message.
3. `POST /api/v1/bookmarks` again with the same body **plus** `"note": "a different thought"`.
4. `GET /api/v1/bookmarks` and read the existing entry's `note`.
5. `POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": <a tip id that belongs to owner B or does not exist>}`.
6. `POST /api/v1/bookmarks` with `{"itemType": "INSIGHT", "itemId": 1}`.
7. `POST /api/v1/bookmarks` with `{"itemId": <TIP_A>}` (no `itemType`), and with
   `{"itemType": "TIP", "itemId": 0}`.

**Expected result:**

- Step 2: **`409 Conflict`** with `errorCode: "BOOKMARK_ALREADY_EXISTS"` and a message that says the
  item is already saved. **Not `201`, and not a silent no-op** — a client that re-saved by accident
  deserves to be told, and a UI can offer "go to it" rather than "saved".
- Step 2's body contains **none** of `dedupe_key`, `uk_bookmark_dedupe`, `Duplicate`, `SQLSTATE` or
  `BR-`. The refusal is the application's, not the driver's message forwarded.
- Steps 3–4: **the entry's `note` is unchanged.** The second request's note was **not** written. A
  `409` says "this is already saved"; it does not say "and here is an edit". Editing a note is
  `PATCH` (M10-05), and a `POST` that quietly applied one would be a second way to write the column.
- Step 5: **`404 Not Found`** with message "Tip not found." — whether the tip belongs to another
  student or simply does not exist. **The two answer identically on purpose**: a caller who could tell
  them apart could enumerate other students' tip identifiers one request at a time.
- Step 5's message contains **no** trigger name (`trg_bookmarks`), no `BR-02`, no `SIGNAL` and no
  `23000`. The trigger's `SIGNAL` is translated, not forwarded.
- Step 6: **`400 VALIDATION_ERROR`** with a `fieldErrors` entry whose `"field"` is `"itemType"` and
  whose message names UC-17. Insights are module 12, which is **locked pending the project owner's
  approval** — the branch is refused **by name** rather than served (§7).
- Step 7: both are **`400`** with a `fieldErrors` entry on the field that was wrong — `itemType` when
  it is missing, `itemId` when it is `0`. **Neither reaches a query**, so no such row can be looked
  up. A server that defaulted `itemType` would be guessing which column the id belongs to.

**Result:** [ ] Pass   [ ] Fail

---

### M10-04 — The note is ciphertext in the database and plaintext through the API

**Covers:** UC-19 B2; SECURITY §12.2; a fresh IV per encryption.

**Preconditions:** Owner A (`${USER_A_JWT}`), with a saved entry (M10-01). You need `mysql` access with
the `campuscoin_app` account.

**Steps:**

1. `PATCH /api/v1/bookmarks/{id}` with `{"note": "worth checking again at the end of the month"}`.
2. `GET /api/v1/bookmarks` and read the entry's `note` — it should read exactly the text you sent.
3. Read the raw column straight from the database:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, note FROM bookmarks ORDER BY id DESC LIMIT 3;"
   ```

4. Save **a second** tip with **the same note text** and read its raw column too.
5. `PATCH` the first entry again with the same text it already has, and re-run step 3.

**Expected result:**

- Step 2: the note reads **exactly** the text sent — `"worth checking again at the end of the month"`.
  The API returns the plaintext, because the service decrypts on the way out.
- Step 3: the column holds **base64 ciphertext**, not the student's words — beginning with a short
  envelope header such as `AQE...` (a one-byte format marker, a one-byte key version, a 12-byte random
  IV, then the ciphertext and its authentication tag, Base64-encoded). **The plaintext does not appear
  anywhere in the column.**
- Step 4: the second entry's ciphertext is **different** from the first's, **even though the note text
  is identical**. This is the property that matters: a fresh random IV is drawn for every encryption,
  so two identical notes do not produce identical ciphertexts and the column leaks nothing by pattern.
- Step 5: the ciphertext **changes again**, for the same plaintext. There is no deterministic
  encryption here and no IV reuse.
- **A note that is only whitespace stores nothing.** `PATCH` with `{"note": "   "}` leaves the column
  `NULL`, and `GET` omits `note` from the body — the same treatment an empty note gets (M10-05).
- **The `note` column is `VARCHAR(2048)`, which is the envelope's budget, not the note's limit.** The
  API accepts at most **255 characters** of plaintext; the column is wider because the ciphertext of a
  255-character note plus its format marker and IV is longer than the plaintext. **Do not report the
  2048 as the note limit** — a 256-character note is refused with a `400` field error (M10-08).

**Result:** [ ] Pass   [ ] Fail

---

### M10-05 — The note's three spellings: unchanged, cleared, trimmed

**Covers:** UC-19 B2; the absent / `null` / empty distinction; the trim.

**Preconditions:** Owner A (`${USER_A_JWT}`), with a saved entry carrying a note (M10-04).

**Steps:**

1. `PATCH /api/v1/bookmarks/{id}` with `{}` — **an empty body.**
2. `GET /api/v1/bookmarks`; is the note still there?
3. `PATCH` with `{"note": null}`.
4. `GET /api/v1/bookmarks` again.
5. `PATCH` with `{"note": ""}`.
6. `GET /api/v1/bookmarks` — is `note` present?
7. `PATCH` with `{"note": "   "}` (three spaces).
8. `GET /api/v1/bookmarks` again.
9. Save a **new** entry with `{"note": "  padded note  "}` and read its `note` back.

**Expected result:**

- Steps 1–4: the note is **unchanged** in both. An empty body and an explicit `null` both mean
  "leave it alone" — `null` is **not** a request to clear. Only step 5 clears.
- Steps 5–6: `200`, and `note` is now **absent from the body** — the note was cleared and the field is
  omitted rather than serialised as `null`.
- Steps 7–8: the note is **still absent**. A whitespace-only note trims to nothing, so it clears —
  the same answer an empty string gives. There is no way to store "a note that is only spaces".
- Step 9: the note reads **`"padded note"`** — trimmed of the spaces you sent. What is encrypted is
  the trimmed text, so the padding never reaches the column.
- **The summary of the three spellings** — this table is the contract:

  | You send | Result |
  |---|---|
  | field omitted (`{}`) | **unchanged** |
  | `{"note": null}` | **unchanged** |
  | `{"note": ""}` | **cleared** |
  | `{"note": "   "}` | **cleared** |
  | `{"note": "  text  "}` | **stored as `text`** |

- **Do not report "the note was not cleared" after steps 1–4 as a defect.** That is the case's whole
  point: a client that sends `null` for an optional field it did not mean to change must not erase the
  student's note.

**Result:** [ ] Pass   [ ] Fail

---

### M10-06 — Un-marking removes the entry and leaves the tip alone; a repeat is not an error

**Covers:** UC-19 B4; `fk_bookmark_tip`; the idempotent removal; ownership on the removal path.

**Preconditions:** Owner A (`${USER_A_JWT}`), with a saved entry. Owner B (`${USER_B_JWT}`) available.

**Steps:**

1. Note the saved entry's `tipId`, then `GET /api/v1/tips` and confirm that tip is still listed.
2. `DELETE /api/v1/bookmarks/{id}` with `${USER_A_JWT}`.
3. Record the status and whether there is a body.
4. `GET /api/v1/bookmarks` — is the entry gone?
5. `GET /api/v1/tips` — is the tip still there?
6. `DELETE /api/v1/bookmarks/{id}` for the **same id** again.
7. As owner B, `DELETE /api/v1/bookmarks/{<owner A's bookmark id>}` — another student's entry, if you
   have one; otherwise use a saved entry of her own and note the outcome. Then re-read owner A's list
   and the raw row.

**Expected result:**

- Step 3: **`204 No Content`** — no body at all, not `{"message": "..."}`.
- Step 4: the entry is **gone from the list**.
- Step 5: **the tip is still there**, unchanged. Removing a bookmark removes *the bookmark*, never the
  tip — the foreign key's cascade runs from `bookmarks` to `user_tips`, never the reverse. A tip the
  student saved and then un-saved is still published on the tips screen.
- Step 6: **`204` again**, not `404`. Un-marking an entry that is already gone is not a failure. A
  client that retries a removal must not be shown an error for the second attempt.
- Step 7: **`204`**, and owner A's row is **still present and still hers** — nothing was deleted. The
  two answers being identical is the security property (§3.5): a caller who could tell "not there"
  from "not yours" could enumerate bookmark ids. Verify with:

  ```bash
  mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
    "SELECT b.id, b.user_id, b.tip_id FROM bookmarks b WHERE b.id = <owner A's bookmark id>;"
  ```

- **Do not report step 7's `204` as a defect** — no other student's row was touched.

**Result:** [ ] Pass   [ ] Fail

---

### M10-07 — A dismissed tip stays in the saved list

**Covers:** UC-19 B3; BR-14; the read's join against `user_tips`, not the view.

**Preconditions:** Owner A (`${USER_A_JWT}`), with a saved entry whose `tipState` is `"NEW"`. You will
dismiss its tip — pick one you are willing to spend (the VĐ-03 case below).

**Steps:**

1. `GET /api/v1/bookmarks`; note the entry and its `tipId`.
2. `POST /api/v1/tips/{tipId}/state` with `{"state": "DISMISSED"}`.
3. `GET /api/v1/tips` — is the tip still on the tips screen?
4. `GET /api/v1/bookmarks` — is the entry still in the saved list, and what does `tipState` read?
5. `POST /api/v1/tips/{tipId}/state` with `{"state": "DISMISSED"}` a second time.
6. `GET /api/v1/bookmarks` again.

**Expected result:**

- Step 3: the tip is **gone from the tips screen** — `v_dashboard_tips` excludes dismissed rows.
- Step 4: **the entry is still in the saved list**, and its `tipState` reads **`"DISMISSED"`**.
  The saved list is a different question from the tips screen: "what did I keep" versus "what should
  I read now". A student who kept an item and then dismissed the tip has kept the item.
- Step 4 is why the read joins `user_tips` **directly** rather than through `v_dashboard_tips`. Had it
  read the view, this entry would have silently vanished from the list — a row the student kept and
  did not remove.
- **This is the only response in the project that can carry `DISMISSED`.** Module 9's tips never do
  (its view filters them). Here the value describes the tip the entry points at, not the entry.
- Steps 5–6: the entry is **unchanged and still present**. Acting on the tip again does not
  re-create, re-order or remove the bookmark — the two tables are independent (§3.4).
- **Do not report the disappeared tip on step 3 as a defect.** That is module 9's dismissal rule, and
  it is correct; the *tip* is retired, the *bookmark* is not.

**Result:** [ ] Pass   [ ] Fail

---

### M10-08 — What is refused, and every refusal names its field

**Covers:** UC-19 B1/B2; validation; the insight refusal; the note's bound.

**Preconditions:** Owner A (`${USER_A_JWT}`), with a saved entry.

**Steps:** Send each request and record the status, `errorCode` and the `field` named.

| # | Request | Expected |
|---|---|---|
| a | `POST /api/v1/bookmarks` with `{"itemType": "INSIGHT", "itemId": 1}` | `400`, field `itemType` |
| b | `POST /api/v1/bookmarks` with `{"itemId": 7}` | `400`, field `itemType` |
| c | `POST /api/v1/bookmarks` with `{"itemType": "TIP"}` | `400`, field `itemId` |
| d | `POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": 0}` | `400`, field `itemId` |
| e | `POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": -1}` | `400`, field `itemId` |
| f | `POST /api/v1/bookmarks` with `{"itemType": "TIPS", "itemId": 7}` | `400`, field `itemType` |
| g | `PATCH /api/v1/bookmarks/{id}` with a 256-character note | `400`, field `note` |
| h | `PATCH /api/v1/bookmarks/{id}` with a 255-character note | `200` |
| i | `POST /api/v1/bookmarks` with a 256-character note | `400`, field `note` |
| j | `PATCH /api/v1/bookmarks/{<an id that does not exist>}` with `{"note": "x"}` | `404` |

**Expected result:**

- a: `400` with a `fieldErrors` entry whose `"field"` is `"itemType"` and whose message **names
  UC-17** — the insight branch is refused **by name**, not as a JSON parsing failure. The value
  `"INSIGHT"` is a real member of the column's `ENUM` and a real member of the request type; the
  refusal is a scope decision, explained in the message (§7).
- b: `400`, `itemType` is required. **The server does not default it** — it would be guessing which
  column the id belongs to.
- c, d, e: `400`, field `itemId`. `0` and `-1` are refused **before any query** — no such row can
  exist, and the two ids are the ones a client that forgot to set the field would send.
- f: `400`, field `itemType`. An unknown value is refused **by name, never by ordinal**, so a typo
  does not shift meaning to the next member.
- g, i: `400`, field `note`, on **both** writes — the bound is the same whether the note is set at
  creation or edited later. **`255` is the note's limit**, the plaintext length; the column's
  `VARCHAR(2048)` is the ciphertext's budget (M10-04), not a second limit.
- h: `200`, and the note reads back **exactly 255 characters**. The bound is inclusive.
- j: `404 NOT_FOUND` — an id that does not exist is not found rather than refused. **`PATCH` on
  another student's entry answers the same `404`** (identity is bound into the query), so the route
  cannot be used to discover which bookmark ids exist.
- **Every refusal names the parameter that was wrong**, so a client can correct the request rather
  than re-read the documentation.

**Result:** [ ] Pass   [ ] Fail

---

### M10-09 — Every endpoint requires a student's token, and administrators are refused

**Covers:** §7.5; the role rule; the token requirement.

**Preconditions:** All four tokens available.

**Steps:** Send each request and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/bookmarks` — no `Authorization` header | `401` `UNAUTHENTICATED` |
| b | `GET /api/v1/bookmarks` — `Authorization: Bearer not.a.token` | `401` |
| c | `GET /api/v1/bookmarks` — `${ADMIN_JWT}` | `403` `ACCESS_DENIED` |
| d | `POST /api/v1/bookmarks` with `{"itemType":"TIP","itemId":7}` — `${ADMIN_JWT}` | `403` |
| e | `PATCH /api/v1/bookmarks/1` with `{"note":"x"}` — `${ADMIN_JWT}` | `403` |
| f | `DELETE /api/v1/bookmarks/1` — `${ADMIN_JWT}` | `403` |
| g | `POST /api/v1/bookmarks` with a valid body — no header | `401` |
| h | `PATCH /api/v1/bookmarks/1` with `{"note":"x"}` — no header | `401` |
| i | `DELETE /api/v1/bookmarks/1` — no header | `401` |
| j | `GET /api/v1/bookmarks` — `${JWT}` | `200` |

**Expected result:**

- a, b, g, h, i: `401` with `errorCode: "UNAUTHENTICATED"`. A malformed token is not a `400`, and **no
  stack trace or token text** is returned or logged.
- c–f: `403` with `errorCode: "ACCESS_DENIED"` — **all four endpoints, including the two writes.**
  An administrator is **not** a student. The rule is on `/api/v1/bookmarks/**` and Spring Security
  enforces it *before* the controller, so the service is never reached.
- **An administrator is refused because a saved entry's `tipTitle` and `tipBody` are readable prose
  about one named student's spending, and its `note` is text that student typed.** Admission would let
  the role read a named student's habits — and their private note — through a route never meant to
  name anyone.
- **No administrator counterpart exists.** No view over `bookmarks` and no UC-20…UC-23 operation
  touches the table, so refusing the role costs nothing. Administrator work on tip **templates**
  (UC-20) is a different table and lives under `/api/v1/admin/**` in module 11.
- j: `200`, the caller's own list.

**Result:** [ ] Pass   [ ] Fail

---

### M10-10 — No response names the owner or publishes anything internal

**Covers:** BR-02; §15 (no sensitive or internal field in a response).

**Preconditions:** Owner A (`${USER_A_JWT}`), with at least one saved entry carrying a note.

**Steps:**

1. Capture the raw bodies:

   ```bash
   curl -s http://localhost:8080/api/v1/bookmarks \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_bookmarks.json
   ```

2. List the keys the body actually contains, at every level:

   ```bash
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_bookmarks.json | sort -u
   ```

3. Open `http://localhost:8080/api-docs` and find the `BookmarkResponse` schema. Compare its fields
   with the list you printed.

**Expected result:**

- None of these appears in the body, at any level:

  | Must not appear | Why |
  |---|---|
  | `userId`, `user_id`, `user` | A saved entry is the caller's by construction. A `userId` is one more place an identity could leak into a log or a proxy cache |
  | `email`, `fullName`, `full_name` | The account's name is not part of a saved entry |
  | `insightId` | The other target column. This build never writes it, and publishing it would advertise a branch that is refused |
  | `dedupeKey`, `dedupe_key` | The unique key's value. Publishing it would expose the row's internal identity |
  | `updatedAt`, `deletedAt` | Not part of the contract and not shown on the screen |
  | `passwordHash`, `password_hash`, `tokenVersion`, `refreshToken` | Never present in any response; checked globally by `OpenApiContractIT` |

- The entry's keys are exactly: `id`, `itemType`, `tipId`, `tipTitle`, `tipBody`, `tipPotentialSaving`,
  `tipState`, `tipMonth`, `createdAt`, plus `note` **only on an entry that has one**. **Nothing else** —
  and `note` is present on some entries and absent on others, which is correct (§3.3).
- No nested user object anywhere in the body.
- **The contract test pins this twice** — against the documented field list *and* against the
  forbidden aliases above — so a column added to the join cannot appear under a different spelling
  without failing the build.

**Result:** [ ] Pass   [ ] Fail

---

### M10-11 — No parameter can name another student, and a fresh student gets an empty list

**Covers:** §15; BR-02 (ownership, structurally); the empty state.

**Preconditions:** Owner A (`${USER_A_JWT}`) and owner B (`${USER_B_JWT}`), both freshly seeded. Owner
B has no tips and no bookmarks.

**Steps:**

1. `GET /api/v1/bookmarks` with `${USER_A_JWT}`; record the count.
2. `GET /api/v1/bookmarks` with `${USER_B_JWT}`; record the count.
3. `GET /api/v1/bookmarks?userId=<owner A's id>` with `${USER_B_JWT}`.
4. `POST /api/v1/tips/generate` with `${USER_B_JWT}` — she needs a tip of her own (rule 6 gives her
   one `GENERIC` tip).
5. As owner B, `POST /api/v1/bookmarks` with her own tip id.
6. As owner B, `POST /api/v1/bookmarks` with one of **owner A's** tip ids.
7. As owner B, `DELETE /api/v1/bookmarks/{<owner A's bookmark id>}`.
8. `GET /api/v1/bookmarks` with `${USER_A_JWT}` — is owner A's list intact?

**Expected result:**

- Step 1: owner A's saved entries. Step 2: **`[]`** — an empty array, not `null`, not `{}` and not an
  error. A fresh account has nothing saved.
- Step 3: `200`, with **owner B's own** list (empty). `userId` is **ignored** — it is not a parameter
  the endpoint reads, so it cannot be wrong. A `200` returning owner A's list here would be a
  **critical** finding; report it above every other result in this procedure.
- Step 5: `201`, and the entry's `tipTitle` is the `GENERIC` advice text ("record your first
  transactions" shape), `tipPotentialSaving: 0.00`, no category — her tip's own words.
- Step 6: **`404 Not Found`** with message "Tip not found." — **not `403`.** Ownership is decided by
  the trigger that checks the target's owner against the row's `user_id`, and the refusal is
  translated to the same answer a missing tip gets. "Not yours" and "does not exist" answer
  identically, so the endpoint cannot be used to discover which tip identifiers exist.
- Step 7: `204`, and step 8 shows **owner A's list unchanged** — nothing was removed (§3.5, M10-06).
- **Ownership is structural, not checked.** No method at any layer takes a user identifier; the
  caller's id comes from the verified token and is bound into every query. There is therefore nothing
  to tamper with.

**Result:** [ ] Pass   [ ] Fail

---

### M10-12 — There is no other bookmarks route, and editing a note does not move the entry

**Covers:** §13 (no duplicate capability / no duplicate implementation); the endpoint inventory;
`PATCH` versus remove-and-recreate.

**Preconditions:** Owner A (`${USER_A_JWT}`), with two saved entries.

**Steps:**

1. Record the list order and each entry's `createdAt`.
2. `PATCH` the **last** entry's note to something new.
3. `GET /api/v1/bookmarks` — has the order changed? Is `createdAt` the same?
4. Send each request below and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/bookmarks/1` | `400` `INVALID_REQUEST` — **the path exists**, for `PATCH` and `DELETE` |
| b | `PUT /api/v1/bookmarks/1` | `400` `INVALID_REQUEST` |
| c | `PATCH /api/v1/bookmarks/1/note` | `404` — no such path at all |
| d | `DELETE /api/v1/bookmarks/1/note` | `404` |
| e | `DELETE /api/v1/bookmarks` (no id — clear all) | `400` `INVALID_REQUEST` — the collection exists, for `GET` and `POST` |
| f | `POST /api/v1/bookmarks/1/pin` | `404` |
| g | `POST /api/v1/bookmarks/1/unpin` | `404` |
| h | `POST /api/v1/bookmarks/insights` | `400` `INVALID_REQUEST` — it matches the `/bookmarks/{id}` pattern |
| i | `GET /api/v1/bookmarks/export` | `400` `INVALID_REQUEST` — likewise |
| j | `GET /api/v1/bookmarks?itemType=INSIGHT` | `200`, the caller's whole list — the parameter is ignored |

**Expected result:**

- Step 3: **the entry's `createdAt` is unchanged and its place in the list is unchanged.** Editing a
  note is a field edit, not a remove-and-recreate: the row is the same row, and when it was saved is
  still when it was saved. **A `PATCH` that moved the entry to the top would be a finding** — it would
  mean the saved time had been rewritten, and the list's order would drift every time someone
  corrected a typo.
- **The two failure codes mean different things, and the difference is worth reading.** A
  **`404 NOT_FOUND`** means the path does not exist at all — nothing is routed there. A **`400
  INVALID_REQUEST`** means the path **does** exist but not for the method you used: Spring matched
  the URL to a controller mapping and found no handler for that verb. That is
  `HttpRequestMethodNotSupportedException`, answered as `INVALID_REQUEST` rather than `405` (the
  application-wide behaviour, the same in every module), with the fixed message "The HTTP method is
  not supported by this endpoint." **Nothing is created, changed or deleted** on either code. This
  distinction is machine-checked by `BookmarksApiIT#unroutedPathsAndWrongMethodsFailDifferently`, so
  a change to Spring's resolver behaviour or to the two exception handlers fails the suite rather
  than surfacing here as a surprise.
- c, d, f, g: **`404`** — `/bookmarks/{id}/note`, `/pin` and `/unpin` are paths **no controller
  maps**. Each absence is deliberate (§1, §3.4): no `/{id}/note` sub-resource (a second URL writing
  one column), no `/{id}/pin` or `/{id}/unpin` (that act belongs to module 9's
  `/tips/{id}/state`).
- a, b, e, h, i: **`400` `INVALID_REQUEST`**, because the **path** these requests name does exist —
  `/bookmarks/{id}` for `PATCH`/`DELETE` (a), `/bookmarks` for `GET`/`POST` (e), and `{id}` matches
  `insights` and `export` just as readily as a number, so (h) and (i) are method failures rather
  than missing paths. **None of them does anything.** The one to read carefully is (a): **there is
  still no `/{id}` read** — a `GET` on that path is refused, and the path is not a second way to read
  a row outside its list. The refusal is a method error rather than a `404` only because `PATCH` and
  `DELETE` legitimately live on that URL.
- **Do not read (a)'s `400` as "the route exists".** It exists for two methods and the third is
  refused; the read is still absent.
- j: `200` with the **whole** list. `itemType` is not a parameter either endpoint reads — the list
  already carries each entry's `itemType`, so a filter would be a second expression of a rule the
  contract does not have. **A `200` returning only insight entries would be a finding.**
- The OpenAPI document at `/api-docs` lists **exactly four** bookmarks operations. This is
  machine-checked by `OpenApiContractIT` (30 distinct paths, 45 operations overall), and the path
  count is asserted deliberately — adding a route without adding it to `docs/api/API_INVENTORY.md`
  fails that test on purpose.

**Result:** [ ] Pass   [ ] Fail

---

### M10-13 — Marking and pinning do not overlap (VĐ-03)

**Covers:** VĐ-03; BR-14; the boundary between the two acts on two tables.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded. You need one tip neither saved nor
pinned — call it `<TIP_X>`.

**Steps:**

1. `GET /api/v1/tips`; note `<TIP_X>`'s place in the order and its `state` (should be `"NEW"`).
2. Save it: `POST /api/v1/bookmarks` with `{"itemType": "TIP", "itemId": <TIP_X>}`.
3. `GET /api/v1/tips` — did bookmarking it pin it or move it?
4. `POST /api/v1/tips/{<TIP_X>}/state` with `{"state": "PINNED"}`.
5. `GET /api/v1/bookmarks` — did pinning it bookmark anything new, or change the saved list?
6. `POST /api/v1/tips/{<TIP_X>}/state` with `{"state": "NEW"}` (un-pin).
7. `GET /api/v1/bookmarks` — is the entry still saved?

**Expected result:**

- Step 3: **nothing changed on the tips screen.** The tip is still `"NEW"` and in the same place.
  Saving a tip is not pinning it. **Bookmarking does not re-rank anything**, because the tips order is
  the view's and the saved list is this module's — two different questions over the same tip.
- Step 5: the saved list is **unchanged** — pinning does not create or modify a bookmark. Only
  `POST /api/v1/bookmarks` writes the `bookmarks` table, and only `/tips/{id}/state` writes
  `user_tips.state`. Neither route can do the other's act (§3.4).
- Step 7: the entry is **still there**. Un-pinning does not un-bookmark.
- **A client that treats one boolean as both acts is the bug this case prevents.** The Angular mock
  does exactly that (`docs/api/bookmarks.md` §10 records it among the divergences a rewiring must
  reconcile): it has a single "bookmarked" flag over a localStorage list, which stands for both
  ideas. The contract makes them two acts on two tables with two different effects, and this case is
  where the difference is visible.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Three behaviours a tester is most likely to misread

All three are deliberate and documented. Read this section before marking anything in §4 as a
failure. None is a defect in the module.

> ### 5.1 A missing note and an explicit `null` both mean "leave it alone"
>
> `PATCH` with `{}` or with `{"note": null}` changes nothing. Only `{"note": ""}` (or a
> whitespace-only string) clears the note.
>
> **This is a contract, not an accident of how the service reads the DTO.** A client that serialised
> an absent optional field as `null` would otherwise erase the student's note every time it sent an
> unrelated edit. **Do not report "the note did not clear" after a `null` as a defect** — M10-05's
> table is the whole contract, and only the two clearing spellings clear.

> ### 5.2 A dismissed tip stays in the saved list, and its `tipState` reads `DISMISSED`
>
> Dismiss a tip on the tips screen and its bookmark **does not disappear**. The entry remains, and its
> `tipState` reads `"DISMISSED"` — **the only response in the project that can carry that value.**
>
> **Both are correct.** The saved list answers "what did I keep"; the tips screen answers "what should
> I read now". They are different questions about the same tip, and a student who kept an item and
> then dismissed the tip has kept the item. The list reads `user_tips` **directly** rather than
> through `v_dashboard_tips` for exactly this reason — through the view, the entry would silently
> vanish (§3.5, M10-07). **Do not report the surviving entry as stale data.**

> ### 5.3 Un-marking an entry that is not there answers `204`
>
> `DELETE /{id}` on an id you already removed — or on an id belonging to another student — succeeds
> with no body, exactly as removing your own entry does.
>
> **This is the security property, not sloppiness.** If "not there" and "not yours" answered
> differently, the route could be used to discover which bookmark ids exist, one request at a time.
> **Nothing is deleted when the id is not yours** — M10-06 verifies the row is still present and still
> its owner's. **Do not report the `204` on a foreign id as a defect.** The same reasoning gives the
> `404` on a foreign *tip* when saving (M10-03 step 5) and on a foreign *bookmark* when editing
> (M10-08 step j).

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M10-01 | UC-19 B1, B3; the entry carries the tip's own words; `note` absent when unset; bookmarking does not touch the tip |
| M10-02 | UC-19 B3 (the postcondition), B4; the newest-first order and its `id` tie-break; reading writes nothing; an earlier month's tip is listable |
| M10-03 | UC-19 B1; `uk_bookmark_dedupe` → `409`; the second request's note is not applied; a foreign tip is `404`, not `403`; the insight refusal; per-field validation |
| M10-04 | UC-19 B2; SECURITY §12.2 (ciphertext at rest, plaintext through the API); a fresh IV per encryption; the `VARCHAR(2048)` envelope budget versus the 255-character plaintext bound |
| M10-05 | UC-19 B2; the absent / `null` / empty / whitespace / padded spellings and their trim |
| M10-06 | UC-19 B4; `fk_bookmark_tip` (the tip survives); the idempotent removal; ownership on the removal path |
| M10-07 | UC-19 B3; BR-14; the read joins `user_tips`, not the view; `tipState` can read `DISMISSED` |
| M10-08 | UC-19 B1/B2; the insight refusal by name; the 255-character bound on both writes; every refusal names its field |
| M10-09 | §7.5 (a token, role `STUDENT`; admin `403` on all four, reads and writes) |
| M10-10 | BR-02 (no response names the owner); §15 (no internal field published); the ten-field set |
| M10-11 | §15; BR-02 (ownership, structurally); the empty list; a foreign tip is `404` |
| M10-12 | §13 (no duplicate capability or implementation); the endpoint inventory; `PATCH` does not move the entry; unsupported methods |
| M10-13 | VĐ-03 (mark ≠ pin); BR-14; the two tables and the two routes |
| §5.1 | The note's three spellings |
| §5.2 | A dismissed tip's entry survives |
| §5.3 | An idempotent removal that leaks nothing |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass.

| Not covered | Why |
|---|---|
| Saving an **insight** | The branch is refused by name (M10-03 step 6). Insights are UC-17 — **module 12, locked pending the project owner's approval** — and have no read path in the repository. The schema supports the branch and the response publishes `itemType`, so adding it later is not a breaking change. Recorded as **OB-015** |
| The duplicate-save branch firing through the API | The service checks for an existing row first, so the unique key fires only when two requests race past that check. **A manual tester cannot produce it reliably**, and a hand-run race that happens not to interleave proves nothing. The branch itself is covered by `BookmarkWriteFailureTest`, which reaches the classifier directly (§6 of the module report) |
| A `500`-level failure path | Not reachable through the API. There is no input to make fail and no fault injection |
| Two identical saves committing at the same instant | Same as above — covered by the unit test of the classifier, not by hand |
| The encryption key's rotation or absence | A deployment concern. `SECURITY.md` §12 covers it; the application refuses to start without `CAMPUSCOIN_ENCRYPTION_KEY`, and a build configured with a different key cannot read notes written by another. Not exercised here — rotating the key mid-procedure would make the earlier cases' notes unreadable |
| Amounts being unencrypted | `tipPotentialSaving` is a plaintext amount read from `user_tips`, as module 9's amounts are. This module publishes it and does not aggregate it. Recorded as **OB-013**, not tested here |
| Tips, budgets, notifications, the dashboard | Deliberately not on this screen: `GET /api/v1/tips`, `/budgets`, `/notifications` and `/dashboard` own them. M10-07 and M10-13 read the tips screen only, to check the two agree about a tip's state |
| A disabled account's token, a revoked session, an expired token | Disabling is module 11's action and revocation needs module 1's sign-out. M10-09 covers the missing, malformed and wrong-role cases |
| The Angular bookmarks screen | There is none. `InsightsComponent` is the nearest mock and shows `MOCK_INSIGHTS` bookmarked in `localStorage` — UC-17's shape, not this contract's. No Angular source is changed by this module. [`docs/api/bookmarks.md` §10](../../api/bookmarks.md) records the nine divergences a rewiring must reconcile |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/bookmarks.md`,
`docs/modules/MODULE_10_BOOKMARKS.md`, `docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md`,
`db/01_schema.sql`, `db/04_triggers.sql` or the implementation under
`backend/src/main/java/com/campuscoin/bookmark/`.
