# Module 09 — Saving Tips (UC-18) — Manual Test Procedure

**Scope:** endpoints 38–41 — `GET /api/v1/tips`, `GET /api/v1/tips/months`,
`POST /api/v1/tips/generate` and `POST /api/v1/tips/{id}/state`, all role `STUDENT` only.

**Sources of truth for this procedure:** [`docs/api/tips.md`](../../api/tips.md) (the UC-18
contract — §4 the six rules, §4.4 the template the generator never uses, §5 why reading never
generates, §6 the state machine, §7 the month, §10 what a rewiring must reconcile),
[`docs/modules/MODULE_09_TIPS.md`](../../modules/MODULE_09_TIPS.md) (§4.4 the locked row, §4.6 the
scheduler, §5 the seven defects found and fixed, §6 which suite drives which of the six rules),
[`docs/api/API_INVENTORY.md`](../../api/API_INVENTORY.md) (endpoints 38–41),
[`docs/CREDENTIALS.md`](../../CREDENTIALS.md) (seeded accounts), `db/03_procedures.sql` (the six
rules and their thresholds), `db/02_views.sql` (`v_dashboard_tips`) and `db/06_demo.sql` (the seeded
tips), and the implementation under `backend/src/main/java/com/campuscoin/tips/`.

**Audience:** a QA engineer or the project owner, testing by hand against a running stack. No source
code needs to be read to execute this document.

---

## 1. What is under test

| # | Method | Endpoint | Success |
|---|---|---|---|
| 38 | `GET` | `/api/v1/tips` | `200` one month's ranked tips |
| 39 | `GET` | `/api/v1/tips/months` | `200` the months that have tips |
| 40 | `POST` | `/api/v1/tips/generate` | `200` the month's tips, after generating |
| 41 | `POST` | `/api/v1/tips/{id}/state` | `200` the tip, in its new state |

**Four endpoints, and the property they are built around is that the database writes the tip and the
application writes only its state.** Which tips exist, what each says, what it could save and in
which order are all `sp_generate_tips`'s and `v_dashboard_tips`'s answers. The one column UC-18 lets
a student change is `state`. §3.5 is the part of this that is most likely to be misread.

**There is no fifth endpoint.** There is **no** `GET /api/v1/tips/{id}`, **no** `POST
/api/v1/tips` (create), **no** `PATCH`/`PUT`/`DELETE`, **no** `/{id}/pin`, `/{id}/unpin` or
`/{id}/dismiss`, **no** `/tips/export`, **no** `/tips/current`, `/tips/history` or `/tips/all`,
and **no** `/profile/me/tips`. An endpoint that is not in the table above does not exist; if a step
here asks you to call one, the document is wrong, not the server.

**Nothing takes a user id, and nothing takes a month on `/generate`.** `/generate` has no request
body at all; `/state` has exactly one body field. There is therefore no mass-assignment surface and
no way to name another account.

Parameters:

| Endpoint | Parameter | Accepted | Default |
|---|---|---|---|
| 38 | `month` | `yyyy-MM` — any real month | the current month |
| 41 | `id` (path) | the id of one of the caller's tips | — |

---

## 2. Before you start

### 2.1 Environment

- Backend running on `http://localhost:8080` (Swagger UI at `http://localhost:8080/swagger-ui.html`,
  which redirects to `/swagger-ui/index.html`; the OpenAPI document is at
  `http://localhost:8080/api-docs`).
- MySQL 8 running with the project's schema, seed data and demo data applied (`db/01_schema.sql` …
  `db/06_demo.sql`).
- **The scheduler must not be relied upon during this procedure.** `POST /api/v1/tips/generate` is
  how every case below produces tips, so the outcome does not depend on when the timer last fired.
  If your environment has `TIPS_SCHEDULER_ENABLED=true` (the default) a run may have happened at
  `00:10` local — that only ever *adds* tips the generator would produce anyway, and the dedupe key
  means it cannot add a second copy of one. **Do not treat a tip you did not generate as a defect.**
- **For M9-02 the database must be in its freshly seeded state.** That case asserts the demo
  account's seeded tips exactly. Writes made by an earlier module's manual tests, or by M9-04/M9-05
  below, change what is on the demo account. Re-seed before M9-02, or read it as "the shape is right
  and the tips are whatever the seed plus your earlier testing produced".

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
| `${USER_A_JWT}` | The `STUDENT` token of **owner A**, the seeded demo student whose tips M9-02 asserts. Same account as `${JWT}`; the ownership cases name it explicitly so the two sides of the case are unambiguous. | Alex Nguyen |
| `${USER_B_JWT}` | The `STUDENT` token of **owner B**, the seeded empty account with no transactions and no tips. | Bella Tran — `binh.tran@student.campuscoin.edu` |
| `${ADMIN_JWT}` | An `ADMIN` access token, obtained from the administrator sign-in endpoint. | System Administrator |

There are no other placeholders in this document. Where a numeric id appears (a `categoryId`, a tip
`id`), it is an **example**; always use the id you recorded in your own run.

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

**Most cases below use HTTP only.** The `mysql` command appears in §3.3, M9-02 and M9-03, always to
**read** what the views hold, and once in M9-08 to confirm a constraint the API must never be able to
violate. Every write case uses a student of its own or the demo account's own advice, so no case
disturbs another account.

### 2.3 Dates and the month

- "This month" means the current month in **`Asia/Ho_Chi_Minh` (`+07:00`)** — for example `2026-09`.
  Check it with `TZ=Asia/Ho_Chi_Minh date +%Y-%m`. The database session is pinned to the same offset.
- The month is rendered `"YYYY-MM"` with no day component.
- A **transaction date** used by the fixture steps is a plain calendar date `"YYYY-MM-DD"` with no
  time and no zone. **BR-08 refuses a future-dated transaction**, so use `date +%F` for today or an
  earlier date. **A month that has not started cannot be spent in at all** — a case needing a
  previous month's spending must date a transaction in that earlier month.
- **The current timezone is a deployment setting, not a guarantee.** What this module guarantees, and
  what M9-12 checks, is narrower: one request is resolved against one month before any query runs.

---

## 3. Preconditions

### 3.1 Accounts and data

- **Alex Nguyen** is seeded with three months of demo data (`db/06_demo.sql`), including **tips for
  the current month and the two before it** — three tips each, all in state `NEW`.
- **Bella Tran** is the seeded **empty** account: no transactions, no budgets, no tips. She is the
  clean subject for the empty-state, ownership and generate-from-nothing cases.
- Shared default categories (`Allowance`, `Food`, `Transport`, `Hostel/Rent`, `Academics`,
  `Subscriptions`, `Entertainment`, `Miscellaneous`, `Part-time Job`, `Scholarship`, `Gift`,
  `Other Income`) are visible to every student. `Allowance`, `Part-time Job`, `Scholarship`, `Gift`
  and `Other Income` are `INCOME`; the rest are `EXPENSE`. Use `GET /api/v1/categories` to read the
  real ids and types.

### 3.2 Authentication

All four endpoints require `Authorization: Bearer <accessToken>` **and** an account whose role is
`STUDENT`. An administrator token is refused with `403` (M9-10). A missing, malformed, expired or
revoked token answers `401`. There is no anonymous or shared view of anyone's tips.

### 3.3 What the response looks like

Endpoint 38, for the demo account's current month (`2026-09`), freshly seeded:

```json
{
  "periodMonth": "2026-09",
  "tips": [
    { "id": 7, "title": "Your savings goal is at risk",
      "body": "Your income minus spending is currently 71.00, below your goal of 100.00. Income this month is 260.00. Consider cutting one non-essential expense to get back on target.",
      "potentialSaving": 29.00, "state": "NEW" },
    { "id": 8, "categoryId": 11, "title": "Entertainment spending is up 127.3%",
      "body": "…", "potentialSaving": 14.00, "state": "NEW" },
    { "id": 9, "categoryId": 6, "title": "Food has used 80.0% of its budget",
      "body": "…", "potentialSaving": 3.00, "state": "NEW" }
  ]
}
```

Endpoint 39:

```json
{ "months": ["2026-09", "2026-08", "2026-07"] }
```

**Five things about the shape, each pinned by a case below:**

- **All amounts carry exactly two decimals** — `29.00`, not `29`. `potential_saving` is
  `DECIMAL(15,2)` and serialises with that scale.
- **`categoryId` is absent, not `null`,** on a tip that is about no single category — the
  savings-goal tip above has no `categoryId` key at all (M9-02, §3.4).
- **The array order is the contract.** Pinned tips lead; the rest follow the database's ranking. A
  client renders the array as it arrives and **must not re-sort** it (M9-03, M9-06).
- **A tip carries six fields and nothing else.** No `userId`, no `rankScore`, no `dedupeKey`, no
  `generatedAt`, no `pinnedAt`, no `dismissedAt`, no `tipTemplateId` (M9-09).
- **`state` never reads `DISMISSED`** in a response, because the view excludes those rows before the
  type is ever constructed — but the Swagger schema advertises all three members, because the
  response uses the same Java enum as the request (§3.5, M9-09).

### 3.4 The decision this module is built around — read this before §4

**A tip is written by the database and only its state is written by the application.**

`sp_generate_tips(user_id, period_month, max_tips)` applies six SQL rules over the student's own
views, renders each tip's text from a `tip_templates` row, ranks the results by what following each
could save, and keeps the top N. `v_dashboard_tips` publishes them in that order, pinned first.
Nothing in Java composes advice, decides how many tips there are, ranks one, or fills a gap.

| Rule | Template code | Fires when |
|---|---|---|
| 1 | `OVER_BUDGET` | A budget is at or over its limit |
| 2 | `NEAR_BUDGET` | A budget is at or past `budget.near_threshold_pct` (80%) but below its limit |
| 3 | `CATEGORY_SPIKE` | A category's month-on-month rise is at least `insight.spike_threshold_pct` (30%), its baseline average is above zero, and at least **one** earlier month exists to average over. The gate is *one* month, not the `insight.spike_baseline_months` (3) setting — that setting sizes the averaging window, not the requirement (see [`docs/api/tips.md` §4.6](../../api/tips.md)) |
| 4 | `NO_BUDGET_SET` | An expense category has spending this month and **no** budget row. At most 2, largest first |
| 5 | `SAVINGS_GOAL_AT_RISK` | `monthly_savings_goal > 0` and the month's net is below it |
| 6 | `GENERIC` | The month has no spending records at all |

**Seven templates exist; the procedure names six.** `LOW_SAVINGS_RATE` is a `tip_templates` row the
generator never uses. That rule belongs to UC-17 (monthly insights), which is module 12 and is
**locked pending the project owner's approval**. **Do not report a missing `LOW_SAVINGS_RATE` tip as
a defect, and do not ask a case to produce one.**

**Two consequences a tester should hold in mind throughout §4:**

- **Reading never generates.** `GET /api/v1/tips` calls no procedure, so opening the screen cannot
  change what is on it. M9-04 is the case that checks it, and it is the reason a client can re-fetch
  freely.
- **Generating is idempotent because of a unique key.** The procedure ends with `INSERT IGNORE`
  against `uk_tip_dedupe` = `user_id | period_month | tip_template_id | category_id`. A repeated run
  produces the same tips and no others, and a tip the student has already acted on is never
  regenerated. M9-05 is the case that checks it, and it is the source of BR-14's "a dismissed tip
  never comes back".

### 3.5 The two behaviours a tester is most likely to misread

Both are deliberate and documented. Read them before marking anything in §4 as a failure.

> **`state` never reads `DISMISSED`, but the schema lists it.** §3.3's response has two reachable
> values, `NEW` and `PINNED`; Swagger's `TipState` schema shows three. That is not an oversight: the
> response field and the request field are **one column**, so they are one Java type, because two
> enums over one column are two vocabularies that can drift. The rule that keeps `DISMISSED` out of a
> response is the **view's** (`WHERE state <> 'DISMISSED'`), applied before the type is constructed,
> and a schema cannot express a rule that belongs to a view. M9-09 asserts the reachable values
> against real rows instead.

> **An empty month is `200`, not `404`.** A month the student has no advice for returns
> `{"periodMonth": "…", "tips": []}`. The month exists; the student has no tips in it. And a student
> with **no records at all** is not empty either — rule 6 gives them one tip, so UC-18 A1's "too
> little data" case is a populated screen rather than a blank one (M9-04, M9-11).

---

## 4. Test cases

Each case is independent unless a step says otherwise. Record the outcome in the **Result** line.

---

### M9-01 — The current month's tips, with no month given

**Covers:** UC-18; the default month; the wrapper shape.

**Preconditions:** Signed in as owner A (`${USER_A_JWT}`), freshly seeded (§2.1).

**Steps:**

1. `GET /api/v1/tips` with `Authorization: Bearer ${USER_A_JWT}` — **no `month` parameter.**

**Expected result:**

- `200 OK`.
- `periodMonth` equals the current month in `+07:00` (e.g. `"2026-09"`) — **and the month is echoed
  back, not left to the client to assume.** Confirm it agrees with
  `TZ=Asia/Ho_Chi_Minh date +%Y-%m`.
- `tips` is a **present, non-null array** with three entries (§3.3).
- Every amount carries two decimals.
- **No `userId` key anywhere in the body.**

**Result:** [ ] Pass   [ ] Fail

---

### M9-02 — The demo account's tips match the seeded month, and the rules that produced them

**Covers:** UC-18; the seeded figures a reviewer will check by hand (UAT). **Read-only.**

This is the case that observes **rules 2, 3 and 5** as they appear on a **real seeded month** — the
figures a reviewer will check by hand. The rules' *boundaries* are driven automatically by
`TipsRuleCoverageIT` (see [`docs/modules/MODULE_09_TIPS.md` §6](../../modules/MODULE_09_TIPS.md));
what this case adds is that the seeded output a person reads on screen is the same thing the
database holds.

**Preconditions:** A **freshly seeded** database (§2.1). Signed in as owner A (`${USER_A_JWT}`). Do
not run any write case before this one.

**Step 1: ask the database what the view holds.**

The base table, not the view: `tip_id` and `display_order` are columns of `v_dashboard_tips` only —
`user_tips` has `id` and `rank_score` — and reading the view the API reads would make this step
predict the API by construction. Querying `user_tips` and restating the view's ordering from the
real columns is the independent check.

```bash
mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
  "SELECT t.id, t.period_month, t.title, t.potential_saving, t.state, t.category_id
     FROM user_tips t
    WHERE t.user_id = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu')
      AND t.state <> 'DISMISSED'
    ORDER BY t.period_month DESC, (t.state = 'PINNED') DESC, t.rank_score DESC, t.id;"
```

**Step 2:** `GET /api/v1/tips` with `Authorization: Bearer ${USER_A_JWT}`.

**Expected result:**

- Step 1 shows **tips for three months** — the current month and the two before it — **three tips
  each, all `state = 'NEW'`** (nine rows).
- Step 2's `tips` array matches step 1's newest month **exactly, row for row and in the same order**:
  1. `Your savings goal is at risk` — `potentialSaving: 29.00`, **no `categoryId` key at all**.
     This is **rule 5** for the demo account: a `100.00` monthly savings goal against a `71.00` net
     (income `260.00`, expenses `189.00`), so the advice is worth `29.00` — the shortfall itself.
  2. `Entertainment spending is up 127.3%` — `categoryId: 11`, `potentialSaving: 14.00`.
     This is **rule 3**: the category's rise against its own three-month baseline. The `127.3` in the
     title is the database's own figure, not the client's — §3.4's rule 3 threshold is `30%`, and
     this rise is well past it.
  3. `Food has used 80.0% of its budget` — `categoryId: 6`, `potentialSaving: 3.00`.
     This is **rule 2**: spending `24.00` against a `30.00` limit is exactly `80.0%`, the
     `budget.near_threshold_pct` boundary. The same split `sp_check_budget_alerts` uses, so the
     budget screen's "Approaching budget limit: Food" alert and this tip agree about which side of
     the limit Food is on.
- **The amounts inside the prose are the database's.** Do not expect to be able to change them
  without changing the data: the text is rendered by `fn_render_template` from a template row and the
  figures the view returned, and Java never composes it.
- **No tip has a `LOW_SAVINGS_RATE` shape**, and there is no fourth tip. The demo account's
  `tips.max_dashboard` is `3`, so at most three are shown per month (§3.4).
- Step 1's other two months each hold three tips — that is why M9-06's months list has three entries.

**Result:** [ ] Pass   [ ] Fail

---

### M9-03 — Setting a budget changes the advice that gets generated

**Covers:** UC-18; rules 1, 2 and 4 observed live; the generated text is the database's.

**Preconditions:** A student of your own — use owner B (`${USER_B_JWT}`), but she has no categories,
so if you want to work on the demo account instead, do this case **after** M9-02. You have an
`EXPENSE` `categoryId` from `GET /api/v1/categories`.

**Steps:**

1. As owner B, read `GET /api/v1/tips` — note the empty list, because nothing has been generated yet.
2. Record an expense of `40.00` in an `EXPENSE` category, dated **today**:

   ```json
   { "categoryId": 12, "amount": 40.00, "description": "Books", "txnDate": "<today in +07:00>" }
   ```

3. `POST /api/v1/tips/generate` — **no body.**
4. Read the response. Note the title and `potentialSaving`.
5. Now set a budget for that category with a **low** limit — `POST /api/v1/budgets` with
   `limitAmount: 20.00` for that category in the current month, so the `40.00` already spent is
   `200%` of it.
6. `POST /api/v1/tips/generate` again. Read the response.

**Expected result:**

- Step 3: `200`, and the response is the resulting **list**, not an acknowledgement —
  `periodMonth` plus a `tips` array with **one entry**. No second request is needed to see what the
  run produced.
- Step 4: the one tip is **rule 4** (`NO_BUDGET_SET`) — the category has spending this month and no
  budget row for it. Its `categoryId` is the category you used, and its `potentialSaving` is
  **positive** (advice about a heavy unbudgeted category carries a figure).
- Step 6: **the shape of the advice changes**, because the data it is drawn from changed. The
  category now has a budget whose spending is `200%` of its limit, so **rule 1** (`OVER_BUDGET`)
  applies and produces a different title and body. The tip's `id` **may be a new row** — the rule 4
  tip and the rule 1 tip are different templates, hence different entries in `uk_tip_dedupe`'s key,
  hence **both rows survive** and the view ranks them by what following each could save.
- **The advice you see in step 6 was not written by the server's Java.** Every figure and every
  sentence came from the procedure and the template. If a number disagrees with
  `GET /api/v1/budgets`, the database is the one to check, not the endpoint.
- **You cannot make a tip appear by editing it.** There is no create, update or delete route; the
  only way to change the list is to change the spending or the budgets and generate again.

**Result:** [ ] Pass   [ ] Fail

---

### M9-04 — Reading a month never generates; a month with no tips is a valid empty answer

**Covers:** UC-18; the read/generate separation; the empty state.

**Preconditions:** A student of your own (`${USER_B_JWT}`), freshly seeded so she has no tips at all.

**Steps:**

1. `GET /api/v1/tips/months` as owner B. Record `months`.
2. `GET /api/v1/tips` as owner B three times in a row.
3. `GET /api/v1/tips/months` again; compare.
4. `GET /api/v1/tips?month=2020-01` — a real month, long before the account existed.

**Expected result:**

- Step 1: `200` with **`{"months": []}`** — she has no tips, so no month is offered. An empty array,
  not `null` and not an error.
- Step 2: each of the three responses is **byte-for-byte identical**, and each is
  `{"periodMonth": "<this month>", "tips": []}` — **`200`**, not `404`, and not an error. Opening the
  tips screen does not write tips; if it did, the second and third reads would show more than the
  first.
- Step 3: **still `{"months": []}`.** The three reads added nothing. This is the property that makes
  the read safe to repeat — and the reason `/generate` exists as a separate call (§2.2).
- Step 4: `200` with an empty `tips` array and `periodMonth: "2020-01"`. A month the account did not
  exist in is still a valid month; the student simply has no advice for it.
- **A student with no records is not "empty" once generated** — that is M9-11's case, and the
  contrast between step 2 and M9-11 is the point of the read/generate split. Here, nothing has been
  generated, so there is genuinely nothing.

**Result:** [ ] Pass   [ ] Fail

---

### M9-05 — Generating twice changes nothing; the advice is idempotent and worth more than `0.00`

**Covers:** UC-18; BR-14 (idempotency by unique key); UC-18 A1.

**Preconditions:** Owner B (`${USER_B_JWT}`), untouched by M9-04.

**Steps:**

1. `POST /api/v1/tips/generate` as owner B. Record the full `tips` array — ids, titles,
   `potentialSaving`.
2. `POST /api/v1/tips/generate` again. Record the array.
3. Compare the two arrays.
4. Confirm against the database that the row count did not grow:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT COUNT(*) AS tips_for_bella FROM user_tips
       WHERE user_id = (SELECT id FROM users WHERE email = 'binh.tran@student.campuscoin.edu');"
   ```

**Expected result:**

- Step 1: `200` with **one** tip — owner B has no records at all, so **rule 6** (`GENERIC`) produces
  the "record your first transactions" advice. This is UC-18 A1: a student with too little data gets
  advice rather than a blank screen.
- **That tip's `potentialSaving` is `0.00`, and `categoryId` is absent** — advice with no figure
  attached and no category to link to.
- Steps 2–3: the second array is **identical** to the first — **the same `id`**, the same title, the
  same state. The tips were not re-created, and there is no second copy.
- Step 4: **the count is `1`** after two runs. This is `INSERT IGNORE` against `uk_tip_dedupe`: the
  second run hit the same key and was skipped by the database, not by the Java.
- **This is why the scheduled run is safe to repeat and safe on more than one instance** — a second
  instance's timer produces the same tips and no others. There is no leader election and none is
  needed.
- **It is also why a tip the student acted on is never regenerated** — M9-07 depends on it.

**Result:** [ ] Pass   [ ] Fail

---

### M9-06 — Pinning moves a tip to the front; the months list offers only months that return something

**Covers:** UC-18; BR-14 (ranking); the picker's source.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded, or owner B with several generated tips.

**Steps:**

1. `GET /api/v1/tips` and record the order of the tips (by `id` and title). Note which tip is
   **last**.
2. `POST /api/v1/tips/{id}/state` with `{"state": "PINNED"}` for **the last tip in the list**.
3. `GET /api/v1/tips` again and re-read the order.
4. `GET /api/v1/tips/months`. Record `months`.
5. `GET /api/v1/tips?month=` for **each** month the list offers; check each returns at least one tip.

**Expected result:**

- Step 2: `200` with the tip, now `"state": "PINNED"`.
- Step 3: **that tip is now first**, and the others keep the order they had — pinned tips lead, and
  the rest still follow the database's ranking. **The relative order of the unpinned tips does not
  change**, because a pin is a display preference, not a re-ranking.
- **A client must render the array as it arrives.** Sorting by `potentialSaving` would disagree with
  the view's ranking, and the tip the student pinned would drift back down the page.
- Step 4: `months` is **newest first** — `["2026-09", "2026-08", "2026-07"]` for the seeded demo
  account. It is read from the same view as step 1, so it cannot offer a month that would return
  nothing.
- Step 5: **every** month offered returns at least one tip. That is the property a picker depends on:
  no menu entry leading to an empty screen.

**Result:** [ ] Pass   [ ] Fail

---

### M9-07 — A dismissal is one-way, survives a regeneration, and can empty a month

**Covers:** UC-18; BR-14 ("a dismissed tip never comes back"); the terminal transition.

**Preconditions:** A student of your own, with a month whose tips you are willing to spend. A month
with **exactly one** tip makes step 6 unambiguous — owner B's generated month (M9-05) is ideal.

**Steps:**

1. `GET /api/v1/tips`; record the tips.
2. `POST /api/v1/tips/{id}/state` with `{"state": "DISMISSED"}` for one of them.
3. `GET /api/v1/tips` — is the dismissed tip still there?
4. `POST /api/v1/tips/generate`.
5. `GET /api/v1/tips` again.
6. If the month had only one tip, `GET /api/v1/tips/months`.
7. `POST /api/v1/tips/{id}/state` with `{"state": "PINNED"}` for the **dismissed** tip.
8. `POST /api/v1/tips/{id}/state` with `{"state": "DISMISSED"}` for it a second time.

**Expected result:**

- Step 3: the dismissed tip is **gone from the list**. `v_dashboard_tips` has
  `WHERE state <> 'DISMISSED'`, so it is filtered before the response is built.
- Step 5: **still gone.** The regeneration did **not** bring it back — this is BR-14, and it works
  because of M9-05: the row still exists with its `dedupe_key`, so `INSERT IGNORE` skips it. The tip
  is retired, not hidden.
- Step 6: if that was the month's only tip, **the month is no longer offered by
  `GET /api/v1/tips/months`.** It would return an empty array if asked for, so it is not offered —
  a dismissal can empty a month, and the picker follows.
- Step 7: **`400 VALIDATION_ERROR`** with a `fieldErrors` entry whose `"field"` is `"state"`, and a
  message beginning "This tip was dismissed and cannot be restored." **This is the only state
  transition the module refuses**, and it is refused rather than applied: a dismissed tip is a
  decision, not a preference.
- Step 8: `200` — **dismissing an already-dismissed tip is not an error.** It is idempotent, the same
  treatment a second mark-read gets. Asking for the state a tip already holds always succeeds
  (M9-08 covers the pin side of this).
- **There is no "undo dismissal"**, and none is planned. Un-pinning exists (M9-08) because pinning is
  a display preference.

**Result:** [ ] Pass   [ ] Fail

---

### M9-08 — Un-pinning is allowed, a repeated pin does not restamp, and the constraint holds

**Covers:** UC-18; the two-column write; `ck_tip_state`; the `PINNED → NEW` transition.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded.

**Steps:**

1. Pin a tip: `POST /api/v1/tips/{id}/state` with `{"state": "PINNED"}`. Record the response's
   `state`.
2. Read the row's timestamps from the database:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "SELECT id, state, pinned_at, dismissed_at FROM user_tips WHERE id = <your tip id>;"
   ```

3. Pin the **same** tip again. Record the response and re-run step 2's query.
4. Un-pin it: `POST` with `{"state": "NEW"}`. Record the response and re-run step 2's query.
5. Try to write a pinned tip **without** a timestamp, and a `NEW` tip **with** one, directly:

   ```bash
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "UPDATE user_tips SET state='PINNED', pinned_at=NULL WHERE id = <your tip id>;"
   mysql -h 127.0.0.1 -u campuscoin_app -p campuscoin -e \
     "UPDATE user_tips SET state='NEW', pinned_at=NOW() WHERE id = <your tip id>;"
   ```

**Expected result:**

- Step 1: `200`, `"state": "PINNED"`.
- Step 2: `state = 'PINNED'` **and `pinned_at` is not null** — the two were written together.
  `dismissed_at` is `NULL`.
- Step 3: `200`, and `pinned_at` is **unchanged** — the second pin returned the tip with the
  timestamp it received the first time. It did not restamp, because the tip was already pinned.
  Asking for the state a tip already holds is not an error and does not move a timestamp.
- Step 4: `200`, `"state": "NEW"`, and the row now has `pinned_at = NULL` **and**
  `dismissed_at = NULL` — one value cleared the field it had set. **This is how a tip is unpinned;
  there is no `/{id}/unpin` route.**
- Step 5: **both statements are refused by MySQL** with an error naming the check constraint
  `ck_tip_state`. The constraint requires `PINNED` to carry a `pinned_at`, `DISMISSED` a
  `dismissed_at`, and `NEW` neither.
- **That is why the endpoint writes two columns from one value.** A client that could set `pinnedAt`
  directly could produce a pinned tip with no pinned time — a row the schema refuses. The server
  decides both fields from the one `state` the client sends, so that row is unrepresentable through
  the API.
- Step 5's refusals **do not change the row**: re-run step 2's query and confirm the tip is still in
  whatever state step 4 left it in.

**Result:** [ ] Pass   [ ] Fail

---

### M9-09 — No response names the owner or publishes anything internal

**Covers:** UC-18; BR-02; §15 (no sensitive or internal field in a response).

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:**

1. Capture the raw bodies:

   ```bash
   curl -s http://localhost:8080/api/v1/tips \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_tips.json
   curl -s http://localhost:8080/api/v1/tips/months \
     -H "Authorization: Bearer ${USER_A_JWT}" -o /tmp/cc_tip_months.json
   ```

2. List the keys each body actually contains, at every level:

   ```bash
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_tips.json | sort -u
   jq -r '[paths(scalars) | join(".")] | .[]' /tmp/cc_tip_months.json | sort -u
   ```

3. Open `http://localhost:8080/api-docs` and find the `TipResponse` schema. Read the members listed
   for `state`.

**Expected result:**

- None of these appears in either body, at any level:

  | Must not appear | Why |
  |---|---|
  | `userId`, `user_id`, `user` | A tip is the caller's by construction. A `userId` is one more place an identity could leak into a log or a proxy cache |
  | `email`, `fullName`, `full_name` | The account's name is not part of a tip |
  | `rankScore` | The ranking's internals. The **order** is published; the score is not, so a client cannot re-derive or contest it |
  | `dedupeKey` | The unique key's value. Publishing it would expose the procedure's internal identity for a tip |
  | `generatedAt` | When the row was written. Not part of the contract and not shown on the screen |
  | `pinnedAt`, `dismissedAt` | The paired timestamps. `state` is the public fact; the timestamps are what the schema keeps consistent with it |
  | `tipTemplateId` | The template's id. A client branches on the **title and body**, which are the rendered advice, not on which template produced it |
  | `passwordHash`, `password_hash`, `tokenVersion`, `refreshToken` | Never present in any response; checked globally by `OpenApiContractIT` |

- The tip body's keys are exactly: `periodMonth`, `id`, `categoryId`, `title`, `body`,
  `potentialSaving`, `state`. **Nothing else** — and `categoryId` is present on some rows and absent
  on others, which is correct (§3.3).
- The months body's key is exactly: `months`.
- Step 3: the `state` schema lists **all three** members, `NEW`, `PINNED` and `DISMISSED`, even
  though no response carries `DISMISSED`. **That is not a bug** — §3.5 explains it. What the schema
  *does* pin is the six-field set, so a column added to `user_tips` cannot appear in a response
  silently.
- **No nested user object anywhere in either body.**

**Result:** [ ] Pass   [ ] Fail

---

### M9-10 — Every endpoint requires a student's token

**Covers:** §7.5; the role rule; the token requirement.

**Preconditions:** All four tokens available.

**Steps:** Send each request and record the status.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/tips` — no `Authorization` header | `401` `UNAUTHENTICATED` |
| b | `GET /api/v1/tips` — `Authorization: Bearer not.a.token` | `401` |
| c | `GET /api/v1/tips` — `${ADMIN_JWT}` | `403` `ACCESS_DENIED` |
| d | `GET /api/v1/tips/months` — `${ADMIN_JWT}` | `403` |
| e | `POST /api/v1/tips/generate` — `${ADMIN_JWT}` | `403` |
| f | `POST /api/v1/tips/1/state` with `{"state":"PINNED"}` — `${ADMIN_JWT}` | `403` |
| g | `POST /api/v1/tips/generate` — no header | `401` |
| h | `GET /api/v1/tips` — `${JWT}` | `200` |

**Expected result:**

- a, b, g: `401` with `errorCode: "UNAUTHENTICATED"`. A malformed token is not a `400`, and **no stack
  trace or token text** is returned or logged.
- c–f: `403` with `errorCode: "ACCESS_DENIED"` — **all four endpoints, including the two writes.**
  An administrator is **not** a student. The rule is on `/api/v1/tips/**` and Spring Security
  enforces it *before* the controller, so the service is never reached.
- **An administrator is refused because a tip's title and body are readable prose about one named
  student's spending** — "Entertainment spending is up 127.3%" is a figure from the admin's own
  ledger. Admission would let the role read a named student's habits through a route never meant to
  name anyone. `/api/v1/dashboard/**` is guarded the same way for the same reason.
- Administrator work on tip **templates** (UC-20) is a different table and lives under
  `/api/v1/admin/**` in module 11. This refusal does not block it.
- h: `200`, the caller's own tips.

**Result:** [ ] Pass   [ ] Fail

---

### M9-11 — No parameter can name another student, and a fresh student still gets advice

**Covers:** §15; BR-02 (ownership); UC-18 A1.

**Preconditions:** Owner A (`${USER_A_JWT}`) and owner B (`${USER_B_JWT}`), both freshly seeded. Owner
B has no records and no tips.

**Steps:**

1. `GET /api/v1/tips` with `${USER_A_JWT}`; record the tip count.
2. `GET /api/v1/tips` with `${USER_B_JWT}`; record the tip count.
3. `GET /api/v1/tips?userId=<owner A's id>` with `${USER_B_JWT}`.
4. `POST /api/v1/tips/generate` with `${USER_B_JWT}`.
5. `GET /api/v1/tips` with `${USER_B_JWT}` again.
6. `POST /api/v1/tips/generate` with `${USER_B_JWT}` a second time; compare the two months' tips.
7. As owner B, try to act on one of **owner A's** tip ids: `POST /api/v1/tips/<A's tip id>/state`
   with `{"state": "PINNED"}`.

**Expected result:**

- Step 1: owner A's three tips (M9-02). Step 2: owner B's — empty, or whatever her own cases left.
  **Never owner A's three.**
- Step 3: `200`, with **owner B's own** tips. `userId` is **ignored** — it is not a parameter the
  endpoint reads, so it cannot be wrong. A `200` returning owner A's tips here would be a
  **critical** finding; report it above every other result in this procedure.
- Step 5: owner B now has **one** tip — rule 6, `potentialSaving: 0.00`, **no `categoryId`**. A
  student with no records at all is not shown a blank screen (UC-18 A1).
- Step 6: the second run produced the **same single tip** (M9-05).
- Step 7: **`404 NOT_FOUND`** with message "Tip not found." — **not `403`.** Ownership is decided by
  the query that loads the tip (`findByIdAndUserId` takes the caller's id), so another student's tip
  is **not found** rather than found and refused. "Not yours" and "does not exist" answer
  identically, so the endpoint cannot be used to discover which tip identifiers exist.
- **Ownership is structural, not checked.** No method at any layer takes a user identifier, so there
  is no way to *ask* for another student's tips — the caller's id comes from the verified token and
  is bound into every query. There is therefore nothing to tamper with.

**Result:** [ ] Pass   [ ] Fail

---

### M9-12 — A month that is not a month is refused, and `/generate` refuses to take a month at all

**Covers:** UC-18 A2; validation; the single month resolution.

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:** Send each request and record the status, `errorCode` and the `field` named.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/tips?month=2026-13` | `400`, field `month` |
| b | `GET /api/v1/tips?month=2026-9` | `400`, field `month` |
| c | `GET /api/v1/tips?month=202609` | `400`, field `month` |
| d | `GET /api/v1/tips?month=2026-09-01` | `400`, field `month` |
| e | `GET /api/v1/tips?month=September` | `400`, field `month` |
| f | `GET /api/v1/tips?month=` (blank) | `200`, the current month |
| g | `GET /api/v1/tips?month=<this month>` | `200`, identical to omitting it |
| h | `POST /api/v1/tips/generate?month=2026-08` | `200` — **the parameter is ignored**, not honoured |
| i | `POST /api/v1/tips/1/state` with `{"state": "ARCHIVED"}` | `400`, field `state` |
| j | `POST /api/v1/tips/1/state` with `{}` | `400`, field `state` |

**Expected result:**

- a–e: each is `400` with `errorCode: "VALIDATION_ERROR"` and a `fieldErrors` entry whose `"field"` is
  `"month"` and whose message begins "Enter a real month in yyyy-MM form, for example 2026-09."
  **`2026-13` is not clamped to `2026-12`, and `2026-9` is not accepted as `2026-09`.** The parse is
  strict, so a malformed month can never be silently read as a different, valid one — a request that
  means October must not be answered as September.
- f–g: `200`. A blank month and the current month named explicitly are both the same request as
  omitting it; refusing them would be a trap. Confirm g's body is **identical** to M9-01's.
- h: `200`, and the response's `periodMonth` is **the current month**, not `2026-08`. **`/generate`
  takes no month**, so a client cannot pull advice about a month whose tips were never meant to be
  shown. An ignored parameter is the correct outcome; a `200` generating August would be a finding.
- i: `400` — `ARCHIVED` is not a member of `TipState`. Members are matched **by name, never by
  ordinal**, so an unknown value fails at the read rather than shifting meaning to the next member.
- j: `400` — `state` is required.
- **Every refusal names the parameter that was wrong**, so a client can correct the request rather
  than re-read the documentation.

**Result:** [ ] Pass   [ ] Fail

---

### M9-13 — There is no other tips route, and reading writes nothing

**Covers:** §13 (no duplicate capabilities); the endpoint inventory; the read/generate split.

**Preconditions:** Owner A (`${USER_A_JWT}`).

**Steps:**

1. Send each request below as owner A and record the status.
2. Record the tip count (`GET /api/v1/tips`), then call `GET /api/v1/tips` three times and
   `GET /api/v1/tips/months` three times, then record the count again.

| # | Request | Expected |
|---|---|---|
| a | `GET /api/v1/tips/1` | `404` |
| b | `GET /api/v1/tips/all` | `404` |
| c | `GET /api/v1/tips/current` | `404` |
| d | `GET /api/v1/tips/export` | `404` |
| e | `GET /api/v1/tips/history` | `404` |
| f | `POST /api/v1/tips` | `400` `INVALID_REQUEST` — a create route does not exist |
| g | `POST /api/v1/tips/1/pin` | `404` |
| h | `POST /api/v1/tips/1/unpin` | `404` |
| i | `POST /api/v1/tips/1/dismiss` | `404` |
| j | `PATCH /api/v1/tips/1` | `404` |
| k | `DELETE /api/v1/tips/1` | `404` |
| l | `GET /api/v1/profile/me/tips` | `404` |
| m | `GET /api/v1/tips?state=PINNED` | `200`, **the caller's whole list** — `state` is ignored |

**Expected result:**

- a–e, g–l: `404`. **None of these routes exists, and that is deliberate** (§3.4): a tip is only
  meaningful inside its month's ranked list, and there is no create, no per-transition route, no
  per-tip `PATCH`/`DELETE` and no export. Each absence is asserted by the contract test, so a future
  developer adding one fails the build with the reason attached.
- f: `400` with `errorCode: "INVALID_REQUEST"` and message "The HTTP method is not supported by this
  endpoint." **This is the one case where a route exists but the method does not** — `/api/v1/tips`
  serves `GET` and `POST /generate`'s collection, and `POST` on the bare collection is the wrong verb.
  It is the application-wide behaviour for an unsupported method (every module answers the same way),
  not a `405`. **Nothing is created, changed or deleted.** It carries no data.
- **No `/{id}` path exists at all here, unlike `/api/v1/bookmarks/{id}`**, which serves `PATCH` and
  `DELETE`. That is why j and k are `404` rather than `400`: nothing is routed to
  `/api/v1/tips/{id}`, so the request fails at path matching before the method is ever considered.
  That contrast is now machine-checked from the bookmarks side by
  `BookmarksApiIT#unroutedPathsAndWrongMethodsFailDifferently`, so the two codes cannot silently
  start meaning the same thing.
- m: `200` with the **whole** list, pinned and unpinned alike — `state` is not a parameter either
  endpoint reads. The list already carries each row's state and is already ordered pinned-first, so
  a filter would be a second expression of the view's own rule. **A `200` that returned only pinned
  tips would be a finding**, because it would mean a client-side filter had become server behaviour.
- Step 2: the two counts are **equal**, and the repeated reads are **byte-for-byte identical**. The
  read paths are `@Transactional(readOnly = true)` and call no procedure, so opening the screen
  cannot change what is on it.
- The OpenAPI document at `/api-docs` lists **exactly four** tips operations. This is machine-checked
  by `OpenApiContractIT` (28 distinct paths, 41 operations overall as of module 9; the count grows as
  later modules add routes), and the path count is asserted deliberately — adding a route without
  adding it to `docs/api/API_INVENTORY.md` fails that test on purpose.

**Result:** [ ] Pass   [ ] Fail

---

### M9-14 — The four endpoints of one month agree, and the dashboard shows the same tips

**Covers:** UC-18; the single source (`v_dashboard_tips`); the scheduler's idempotency.

**Preconditions:** Owner A (`${USER_A_JWT}`), freshly seeded.

**Steps:**

1. `GET /api/v1/tips` — record the ids and order.
2. `GET /api/v1/tips/months` — is the first entry the month from step 1?
3. `POST /api/v1/tips/generate` — does the response match step 1?
4. `GET /api/v1/dashboard` as owner A — find the tips block and compare it with step 1.
5. `POST /api/v1/tips/generate` twice more and re-read `GET /api/v1/tips`.

**Expected result:**

- Step 2: the first (newest) month in `months` is the month step 1 answered.
- Step 3: the response's `tips` array is **the same rows in the same order** as step 1. Generating did
  not reorder or re-create anything — nothing had changed, so `INSERT IGNORE` skipped every row.
- Step 4: the dashboard's tips block carries **the same tips** — the same ids, titles,
  `potentialSaving` and order — because both endpoints project `v_dashboard_tips` for the same month.
  The dashboard's block is bounded separately by `tips.max_dashboard`; if it shows fewer than three
  tips for a month with three, that is the bound at work, not a disagreement.
- Step 5: **identical again.** Three runs, one set of tips. This is the property that lets the
  scheduled run be repeated, run on more than one instance, and run alongside an on-demand
  generation, without ever producing a duplicate.
- **The scheduler is not a fifth implementation.** `TipGenerationScheduler` calls the same
  `sp_generate_tips` through the same DAO that `/generate` uses, and it leaves the month to the
  database rather than to the JVM. **Do not wait for the timer to test this** — step 5 exercises the
  same idempotency the timer relies on.

**Result:** [ ] Pass   [ ] Fail

---

## 5. Three behaviours a tester is most likely to misread

All three are deliberate and documented. Read this section before marking anything in §4 as a
failure. None is a defect in the module.

> ### 5.1 The `state` schema lists `DISMISSED`, but no response ever carries it
>
> A response's `state` is `NEW` or `PINNED`. Swagger's `TipState` schema lists all three members,
> including `DISMISSED`.
>
> **Both are correct.** The response field and the request field are **one column**, so they are one
> Java type — two enums over one column would be two vocabularies that could drift apart, which is
> exactly the drift a shared type prevents. The rule that keeps `DISMISSED` out of a response is the
> **view's** (`WHERE state <> 'DISMISSED'`), applied before the type is ever constructed — and a
> schema cannot express a rule that belongs to a view. `TipsApiIT` asserts the reachable values
> against real rows instead.
>
> **Do not report the schema's third member as a defect, and do not expect a `DISMISSED` tip in a
> list.** M9-07's step 3 is where a dismissal's effect is observed.

> ### 5.2 A student with no records gets one tip — the screen is never blank
>
> A student with **no transactions at all** does not get an empty list from `/generate`. They get
> **one** tip, from rule 6, with `potentialSaving: 0.00` and **no `categoryId`**.
>
> **This is UC-18 A1, not a bug.** "Too little data to analyse" is answered with advice to start
> recording, so the tips screen is a populated screen rather than a blank one. The tip has no figure
> attached because there is no spending to compute a figure from, and no category because it is not
> about one.
>
> **Note the asymmetry with a month that genuinely has no advice.** A student with no records *and no
> generation yet* gets `{"tips": []}` (M9-04); the same student *after generating* gets one tip
> (M9-05). The difference is whether the generator has run, which is why reading never generates and
> why `/generate` exists as its own call.

> ### 5.3 There is no way to un-dismiss a tip, and that is the requirement
>
> `POST /{id}/state` with `{"state": "PINNED"}` on a dismissed tip is refused with `400`
> `VALIDATION_ERROR`, and the tip stays gone.
>
> **This is BR-14.** Allowing `DISMISSED → NEW` would let a stale client or a replayed request restore
> advice the student deleted, and would make "dismissed" mean "hidden until someone asks". The
> refusal is a **validation** error rather than a `403` or a `409`: the value sent is a valid
> `TipState`, it simply does not apply to this tip's current state.
>
> **Un-pinning is different and is allowed** (`PINNED → NEW`, M9-08) because pinning is a display
> preference; dismissing is a decision. **Do not report the one-way transition as a defect, and do
> not report a missing "restore" action as a gap** — no use case asks for one and none is planned.
> A UI that offers a "delete/ignore" action must make it one-way too, or the `400` will surprise the
> user ([`docs/api/tips.md` §10.3](../../api/tips.md) records that, among the nine mock-versus-contract
> divergences a rewiring must reconcile).

---

## 6. Traceability

| Test ID | Covers |
|---|---|
| M9-01 | UC-18; the default month; the echoed `periodMonth`; the wrapper shape |
| M9-02 | UC-18; **rules 2, 3 and 5** observed on the seeded month; the absent `categoryId`; the absent `LOW_SAVINGS_RATE` tip; the three-month seed. The same rules' *boundaries* are driven automatically by `TipsRuleCoverageIT` — this case is the hand-check that the seeded output matches |
| M9-03 | UC-18; **rules 1 and 4** observed live; a changed budget changes the advice; the text is the database's; no write route exists |
| M9-04 | UC-18; reading never generates; an empty month is `200`; an empty months list |
| M9-05 | BR-14 (idempotency by `uk_tip_dedupe`); UC-18 A1 — **rule 6**; `potentialSaving` `0.00` |
| M9-06 | UC-18; BR-14 (pinned-first ranking); a pin is not a re-ranking; the picker offers only months that return something |
| M9-07 | UC-18; BR-14 ("a dismissed tip never comes back"); the terminal transition; a dismissal can empty a month |
| M9-08 | UC-18; the two-column write; `ck_tip_state`; repeated pin does not restamp; `PINNED → NEW` |
| M9-09 | UC-18; BR-02 (no response names the owner); §15 (no internal field published); the six-field set |
| M9-10 | §7.5 (a token, role `STUDENT`; admin `403` on all four, reads and writes) |
| M9-11 | §15; BR-02 (ownership, structurally); UC-18 A1; a foreign tip is `404`, not `403` |
| M9-12 | UC-18 A2; strict month parsing; `/generate` takes no month; unknown state; missing state |
| M9-13 | §13 (no duplicate capability); the endpoint inventory; unsupported methods; reads write nothing |
| M9-14 | UC-18; the four endpoints agree on one month; the dashboard reads the same rows; generation is idempotent |
| §5.1 | The shared `TipState` type and the view's own rule |
| §5.2 | UC-18 A1 versus an empty month |
| §5.3 | BR-14's one-way dismissal |

---

## 7. What this procedure deliberately does not cover

Listed so a gap is not mistaken for a pass.

| Not covered | Why |
|---|---|
| A `LOW_SAVINGS_RATE` tip | The template row exists but `sp_generate_tips` never produces it (§3.4). That rule belongs to UC-17 (monthly insights), which is module 12 and is **locked pending the project owner's approval**. No case asks for one |
| The scheduler firing on its own | It runs at `00:10` local behind a conditionally-enabled bean. M9-14 step 5 exercises the same idempotency it depends on; observing the timer would mean waiting for a wall-clock minute and would prove nothing the on-demand run does not |
| A concurrent pin and dismiss, or a generate racing a pin | Raced deliberately by `TipsApiIT#twoPinsOfOneTipSettleOnOneState` and `#generatingWhilePinningDoesNotDuplicateATip` inside one process. **A manual tester cannot produce it reliably by hand**, and a hand-run race that happens not to interleave proves nothing — §2.2 of the module report records how the automated cases do it |
| A `500`-level failure path | Not reachable through the API. There is no input to make fail and no fault injection |
| Every rule boundary exactly at its threshold | M9-02 reads the seeded `80.0%` Food tip, which sits exactly on `budget.near_threshold_pct`, but this procedure does not walk each rule's edge. Doing so by hand means setting a limit and then spending to it repeatedly, which is slow and error-prone for a person; the boundaries are covered instead by `TipsRuleCoverageIT`, which places spending exactly on a threshold (`30.00` against a `30.00` limit) and one step inside it, and by the seeded `80.0%` case here |
| A retired category's spending producing a tip | The generator reads the views, which do not filter a retired category's records, so a tip about one is still produced. That is a consequence of OB-011/OB-009's "frozen rather than deleted" and is recorded in the module report, not tested here |
| Budgets, notifications, the dashboard, transactions | Deliberately not on this screen: `GET /api/v1/budgets`, `/notifications`, `/dashboard` and `/transactions` own them. M9-14 reads the dashboard's tips block only, to check the two agree |
| A disabled account's token, a revoked session, an expired token | Disabling is module 11's action and revocation needs module 1's sign-out. M9-10 covers the missing, malformed and wrong-role cases |
| The Angular tips screen | There is none. `InsightsComponent` is the nearest mock and shows `MOCK_INSIGHTS`, which is UC-17's shape, not this contract's. No Angular source is changed by this module, and all its services are still mock-only. [`docs/api/tips.md` §10](../../api/tips.md) records the nine divergences a rewiring must reconcile |

---

**End of procedure.** Every behaviour asserted above is documented in `docs/api/tips.md`,
`docs/modules/MODULE_09_TIPS.md`, `docs/api/API_INVENTORY.md`, `docs/CREDENTIALS.md`,
`db/02_views.sql`, `db/03_procedures.sql`, `db/06_demo.sql` or the implementation under
`backend/src/main/java/com/campuscoin/tips/`.
