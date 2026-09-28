# CAMPUS COIN — DATA CLEANUP & DEMO DATASET REPORT

**Project:** Campus Coin — student personal-finance web application
**Scope of this run:** QA/junk removal, demo dataset, OpenAPI example cleanup, reproducible clean rebuild
**Report date:** 2026-09-26
**Tester:** Independent QA (external to the implementation)
**Deliverable:** this file — `DATA_CLEANUP_REPORT.md`

> **Statement this report is required to carry:** **M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL**
>
> **This report does not claim the system is "clean" or "ready".** The *dataset* was cleaned and
> revalidated (§E), and no QA residue remains. The *product* is **not** defect-free: §F lists defects
> that were observed at runtime while verifying the cleaned data, including one **HIGH**-severity
> session defect. Section F is the section to read before accepting anything else here.

> **Addendum — 2026-09-27, by the final QA / test-data / pre-deployment pass.**
>
> **This report is a dated record of the cleanup run it describes, and its "no QA residue remains"
> statement is true of that run — but it is no longer the current state.** Substantial QA residue
> accumulated again *after* 2026-09-26, during the corrective-implementation and chatbot work, and was
> removed by the pass referenced below.
>
> | Then (this report, 2026-09-26) | Now |
> |---|---|
> | "no QA residue remains" | **Superseded.** Post-seed QA transactions, a QA budget, QA-generated tips, withdrawn probe announcements, import batches and a QA-named default category all existed at the start of the final pass. All were removed or retired, and the database counts were verified back to baseline |
> | This report's `users` / `transactions` figures | **Superseded.** The current figures, each queried rather than recalled: **4 users, 131 transactions, 15 categories, 10 budgets, 14 tip rows, 9 insights, 3 notifications, 7 tip templates, 16 settings, 0 import batches** |
> | Defects listed in §F | **Partly fixed since.** In particular the two behavioural defects found in the final pass — `v_budget_consumption` reading only the near threshold, and `tips.max_dashboard` not bounding the dashboard read — are fixed, with causal tests. §F is kept as the dated record |
>
> **Do not quote a count or a status from the body of this report as the current state.** The current
> consolidated handoff is `docs/FINAL_QA_TEST_DATA_DEPLOYMENT_REPORT.md`; its §8 and §12 carry the
> cleanup inventory and the two deliberate exceptions (the BR-09-undelible retired category id 15, and
> the project owner's own account).

---

## 0. How to read this report

The task asked for six sections. They are §A–§F below. Three conventions apply throughout:

1. **Scope of what was changed.** No API was redesigned, no endpoint added or removed, no business
   rule changed, and no required feature removed. The only source changes are (a) `@Schema`
   *annotation text* on 9 Java DTOs, (b) API markdown documentation, (c) `db/06_demo.sql` and its
   mirror in `db/merged/campuscoin_full.sql`, (d) `docs/CREDENTIALS.md` demo-account notes. This is
   proven mechanically for (a) in §D.
2. **No secrets.** No access token, password, JWT secret, DB password, SMTP credential or AI key
   appears in this report. Where a token had to be described it is written `Bearer eyJ...<redacted>`.
   The two demo login passwords are already published in `docs/CREDENTIALS.md` and are referred to by
   that document rather than repeated.
3. **Evidence, not memory.** Every count in §A and §B was read back out of the database, not recalled
   from an earlier revision of the work.

### 0.1 Files touched by this run

| File | Change |
|---|---|
| `db/06_demo.sql` | Check-2 label corrected so the UAT-07 assertion names the account it is about (+7/−2 lines) |
| `db/merged/campuscoin_full.sql` | Mirror of the above; regenerated and reloaded clean |
| 9 × `backend/.../*/dto/*.java` | `@Schema` annotation text only — no signature, field or type changed |
| `docs/api/budgets.md`, `notifications.md`, `ai-and-insights.md`, `transactions.md`, `API_INVENTORY.md` | Example values aligned with the seed; "Example Value is not database data" note added |
| `docs/CREDENTIALS.md` | Both demo accounts documented with their real, different datasets |
| `docs/HANDOFF_M1_M11.md`, `docs/REVIEW_CHECKLIST.md` | Short "updated for `db/06_demo.sql`" notes |
| **Not** changed | Frontend behaviour, schema, views, procedures, triggers, tests, `db/05_seed.sql` |

---

## A. Removed data

### A.1 Where the "before" numbers come from

The pre-cleanup state was **not** reconstructed from memory. The live development volume was dumped
to `/tmp/cc_backup/campuscoin_before_cleanup.sql` (223,739 bytes) before any deletion and restored
into a scratch database (`cc_before`) inside the throwaway container `cc-verify`, so the figures
below are a **query result against the actual pre-cleanup data**. The live volume itself was never
destroyed (`docker compose down -v` was not run).

### A.2 Inventory — records KEPT vs records DELETED

Residue was identified by three independent signals, and a row had to match at least one to be
deleted: (1) the owning account's e-mail matched `qa.*`, `*journey*` or `*probe*`; (2) the record's
own name/title/code contained `QA`; (3) the record was admin audit history whose only subject was one
of the above.

**Account-level residue — 4 of 7 accounts deleted**

| Account | Name | Rows it owned | Action | Reason |
|---|---|---|---|---|
| `nobita.journey@example.com` | Nobita Journey Updated | 1 txn, 2 sessions | **DELETE** | `journey` — manual test account |
| `qa.journey.c1d8488c@example.com` | QA Journey Student Updated | 4 txn, 1 rule, 3 tips, 6 sessions | **DELETE** | `qa.` + `journey` |
| `qa.student.b.dd39f463@example.com` | QA Student B | 5 txn, 1 budget, 2 notifs, 3 sessions | **DELETE** | `qa.` — a probe of the ownership-isolation path |
| `qa.reset.probe.bb51bae8@example.com` | QA Reset Probe | 2 sessions | **DELETE** | `qa.` + `probe` — password-reset probe |
| `admin@campuscoin.edu` | System Administrator | — | **KEEP** | Required: one working ADMIN |
| `an.nguyen@student.campuscoin.edu` | Alex Nguyen | — | **KEEP** | Required: primary demo STUDENT |
| `binh.tran@student.campuscoin.edu` | Bella Tran | — | **KEEP** | Required: second STUDENT for isolation |

**Category-level residue — 3 of 15 deleted**

| Id | Name | Scope | Action | Reason |
|---|---|---|---|---|
| 13 | `Campus Cafe QA` | personal, owned by account 5 | **DELETE** | `QA` in the name **and** owned by a deleted account |
| 14 | `QA Default Category v2` | default (`user_id IS NULL`) | **DELETE** | `QA` in the name; had already been renamed from `QA Default Category` by an audit-logged edit |
| 15 | `QA Retired Cat` | personal, owned by account 5, already `is_active = 0` | **DELETE** | `QA` in the name; an artefact of a BR-07 retire-don't-delete test |
| 1–12 | the twelve defaults | default (`user_id IS NULL`) | **KEEP** | Required seed: 5 INCOME + 7 EXPENSE |

Note on `Campus Cafe QA` (id 13): this was the artefact behind the previous tester's
*"Campus Cafe / category id 13"* lead. It is confirmed here to have been a **personal** category,
owned by a since-deleted QA account, named with the `QA` marker. It was removed. The separate string
`Campus Cafe` — which appears as a transaction description in `db/06_demo.sql` and in the frontend's
preserved taught-mapping fixture — is a different object and was **kept**, see §A.4.

**Content-level residue**

| Table | Before | Deleted | Reason |
|---|---|---|---|
| `users` | 7 | **4** | residue accounts above |
| `categories` | 15 | **3** | QA-named categories above |
| `transactions` | 41 | **10** | owned by residue accounts |
| `transaction_history` | 46 | **10** | the BR-09 history rows of those transactions |
| `budgets` | 6 | **1** | owned by `qa.student.b...` |
| `recurring_rules` | 3 | **1** | owned by `qa.journey...` |
| `notifications` | 3 | **2** | owned by `qa.student.b...` |
| `user_tips` | 14 | **3** | owned by `qa.journey...` |
| `insights` | 3 | **0** | all three belonged to a kept account |
| `announcements` | 3 | **1** | `QA announcement`, already `is_active = 0` |
| `tip_templates` | 8 | **1** | `QA_RUN_TEMPLATE` |
| `admin_audit_log` | 10 | **10** | every row recorded only a QA-topic action |
| `user_sessions` | 28 | **13** | sessions of the four deleted accounts |
| `password_reset_tokens` | 5 | **5** | all five belonged to QA probe accounts |
| `recurring_occurrences` | 0 | 0 | nothing to delete |
| `bookmarks`, `import_batches`, `import_rows`, `category_rules`, `recent_activity` | 0 each | 0 | nothing to delete |
| `system_settings`, `dim_month` | 16, 96 | 0 | required seed / reference dimension — untouched |

The 10 audit rows removed were, verbatim by action: `CATEGORY_CREATED` ×1, `CATEGORY_UPDATED` ×1,
`ANNOUNCEMENT_CREATED` ×1, `ANNOUNCEMENT_DEACTIVATED` ×1, `USER_DISABLED` ×1, `USER_ENABLED` ×1,
`TIP_TEMPLATE_SAVED` ×2, `SETTING_CHANGED` ×1, `PASSWORD_RESET_SENT` ×1. Each `detail` payload named
`QA Default Category`, `QA announcement`, `QA_RUN_TEMPLATE` or a residue account id, so none of them
records anything about the kept data.

### A.3 Two "duplicates" that were investigated and found **not** to be duplicates

- **`Part-time Job` transactions.** Two rows named *Part-time shift* exist on different dates in the
  same month. These are two genuinely separate pay events, not a double entry — the anomaly rules
  would flag a true duplicate within `anomaly.duplicate_window_days` (3), and `is_flagged = 0` for
  every row after the cleanup. **Kept.**
- **`Monthly allowance` / `Monthly bus pass`.** Each appears once per month. These are the *posted
  instances* of the recurring rules, and per BR-16 one per period is the correct count, not a
  duplicate. **Kept.**

No other duplicate group was found: `duplicate_emails = 0`, `duplicate_groups = 0` for categories,
`duplicate_groups = 0` for budgets against `uk_budget_user_cat_month`, and `duplicate_groups = 0` for
alerts against `uk_alert_budget_threshold` (§E, checks 05/06/16/17).

### A.4 Deliberate non-deletions

- **`Campus Cafe`** (transaction description in `db/06_demo.sql`, and the taught-mapping fixture in
  `frontend/src/app/mock-data/`) is the worked example for the AI categorisation path
  (`CategorisationApiIT`). Deleting it would break documented behaviour and a passing integration
  test, so it is retained as intentional content, not residue.
- **Two soft-deleted transactions** existed before the cleanup, both owned by residue accounts, so
  both left with their owners. No soft-deleted row remains (§E check 18).
- **Row-level delete guards were respected.** `transactions` is protected by a BR-09 BEFORE DELETE
  trigger and `users`/`categories` by BR-07 retire-don't-delete. For the four residue accounts a hard
  delete was required (a "retired" QA account would still be residue), so the guard trigger was
  dropped **for the duration of that one script only and re-created immediately afterwards from
  `db/04_triggers.sql`**. Foreign-key checking was **never** disabled, no `SET FOREIGN_KEY_CHECKS=0`
  appears anywhere, and no child row was orphaned to force a parent delete: children were removed
  before their parents, in dependency order. §E check 11 confirms zero orphans remain.
- The repair script (`/tmp/cc_cleanup.sql`) was a **one-off repair of the running development volume**,
  not a repo file. A clean rebuild from `db/merged/campuscoin_full.sql` produces the same end state
  without it — this is stated in the script's own header and is what §E check "fresh load" proves.

---

## B. Preserved required seed data

Everything below is created by `db/05_seed.sql`, which remains **required-content-only**: its
`INSERT` targets are exactly `users`, `categories`, `system_settings`, `announcements`,
`tip_templates`. It contains **zero** transactions, budgets, tips, insights, notifications or
recurring rules — all demo content lives in `db/06_demo.sql`, as §B.4 requires.

| Required item | Delivered | Evidence |
|---|---|---|
| System settings | **16** rows | `app.currency = USD`, `app.currency_symbol = $`, `app.timezone = Asia/Ho_Chi_Minh`, `app.week_start = MONDAY`, plus the BR-12/14/15 thresholds, AI toggles and auth TTLs |
| Time zone / currency | `Asia/Ho_Chi_Minh` / `USD ($)` | same 16 rows; the six-month report's month boundaries follow the setting |
| Six-month month dimension | **96** rows in `dim_month` | BR-17's zero-fill source; untouched by the cleanup |
| Default income categories | **5** — `Allowance`, `Part-time Job`, `Scholarship`, `Gift`, `Other Income` | ids 1–5, `user_id IS NULL` |
| Default expense categories | **7** — `Food`, `Transport`, `Hostel/Rent`, `Academics`, `Subscriptions`, `Entertainment`, `Miscellaneous` | ids 6–12, `user_id IS NULL` |
| Tip templates | **7** active | `OVER_BUDGET`, `NEAR_BUDGET`, `CATEGORY_SPIKE`, `NO_BUDGET_SET`, `SAVINGS_GOAL_AT_RISK`, `LOW_SAVINGS_RATE`, `GENERIC` |
| Welcome announcements | **2** active — `Welcome to Campus Coin`, `Import your past spending from a CSV file` | both `is_active = 1`, `audience = STUDENTS` |
| One working ADMIN | `admin@campuscoin.edu` | logs in, `/admin/*` returns 200 (§E) |
| ≥ 2 working STUDENT accounts | `an.nguyen@…` (Alex Nguyen), `binh.tran@…` (Bella Tran) | both log in, both have their own data (§C) |

**Seed-guaranteed identifiers** — the only ids any example may hard-code, verified on a fresh load in
§E: `6` = `Food` (EXPENSE), `11` = `Entertainment` (EXPENSE), `3` = the account
`binh.tran@student.campuscoin.edu` / `Bella Tran`. All three derive from `05_seed.sql`'s own insert
order, so they hold on any clean rebuild.

---

## C. Demo dataset summary

`db/06_demo.sql` (411 lines) is the only place demo content lives. It inserts **only** budgets,
one personal category, recurring rules and transactions, then calls the **real server procedures** to
produce everything derived. Insert counts: `transactions` ×10 statements, `budgets` ×2,
`recurring_rules` ×2, `categories` ×1; plus `CALL sp_generate_tips` ×5,
`CALL sp_generate_monthly_insight` ×8. There is **no** `INSERT INTO user_tips`,
`INSERT INTO insights`, `INSERT INTO notifications` or `INSERT INTO budget_alert_log` anywhere in the
file — a text search for those statements returns nothing.

### C.1 Accounts (PART 3-A and PART 4)

| | **Alex Nguyen** — `an.nguyen@student.campuscoin.edu` | **Bella Tran** — `binh.tran@student.campuscoin.edu` |
|---|---|---|
| Role / year | STUDENT, Year 3 | STUDENT, Year 1 |
| Monthly allowance | $200.00 | $150.00 |
| Savings goal | $100.00 | $50.00 |
| Transactions | **31** | **38** |
| Months with activity | Jun–Sep 2026 (4) + two zero-filled months | Apr–Sep 2026 (all 6) |
| Personal category | none | **`Gym & Sports`** — hers alone |
| Budgets | 5 (Sep) | 5 (Sep), **different categories and amounts** |
| Alerts | 1 × `NEAR` on Food | 1 × `NEAR` **and** 1 × `EXCEEDED` on Food |
| Tips / insights | 9 / 3 | 4 / 5 |
| Recurring rules | 2 | 2 |

The two datasets are deliberately **not** copies: different allowance, goal, year, category mix
(Bella has `Gym & Sports`, Alex does not; Alex spends on `Academics`, Bella does not), different
budget amounts for the same categories, and different alert states. That is what makes the isolation
test in §E meaningful.

### C.2 Transactions and categories used (PART 3-B, PART 3-C)

Per-category totals as stored (live dev DB, `deleted_at IS NULL`):

| Alex — category | type | n | total |  | Bella — category | type | n | total |
|---|---|---|---|---|---|---|---|---|
| Hostel/Rent | EXPENSE | 4 | 480.00 | | Food | EXPENSE | 11 | 156.00 |
| Academics | EXPENSE | 3 | 95.00 | | Gym & Sports | EXPENSE | 5 | 110.00 |
| Food | EXPENSE | 4 | 84.00 | | Transport | EXPENSE | 5 | 48.00 |
| Entertainment | EXPENSE | 4 | 58.00 | | Entertainment | EXPENSE | 3 | 37.00 |
| Transport | EXPENSE | 4 | 57.00 | | Subscriptions | EXPENSE | 5 | 30.00 |
| Subscriptions | EXPENSE | 4 | 32.00 | | Allowance | INCOME | 6 | 900.00 |
| Allowance | INCOME | 4 | 800.00 | | Part-time Job | INCOME | 2 | 85.00 |
| Part-time Job | INCOME | 3 | 230.00 | | Gift | INCOME | 1 | 40.00 |
| Scholarship | INCOME | 1 | 150.00 | | | | | |

**11 of the 13 categories are exercised.** The two that are not — `Other Income` and
`Miscellaneous` — are intentional: they exist so BR-14's *"no budget set"* tip branch and the
uncategorised fallback have a target, and so a tester has an empty category to try. Their absence is
not missing data.

**Multi-month history.** Bella carries six months with a genuine trough: April 2026 has only 2
transactions ($9.00 out, $150.00 in), against $62–$84 of spending in the four months after it. Alex
carries four months of activity (Jun–Sep) and two months that are **zero**, which is a second, more
extreme case of the same requirement. The BR-17 six-month view returns six rows for each student
either way (§E check 19). Combined, the dataset demonstrates both a near-empty month and a
zero-filled month; see §F-3 for the one caveat about how the requirement is split between accounts.

**Descriptions** are realistic student free text in English (`Dorm rent`, `Monthly bus pass`,
`Campus canteen`, `Reference materials`, `Sports centre membership`, `Board game cafe`). They are
seeded as **plaintext**, which is correct and not a defect: the encryption layer's `decryptStored()`
tolerates legacy plaintext by design, so a hand-written row in `db/06_demo.sql` is readable without
the application ever needing the key. See §F-4.

### C.3 Budgets (PART 3-D)

Sep 2026, both accounts — all ten rows:

| Account | Category | Limit | Spent | Consumed | State |
|---|---|---|---|---|---|
| Alex | Transport | $25.00 | $12.00 | 48% | **low utilisation** |
| Alex | Subscriptions | $15.00 | $8.00 | 53% | low |
| Alex | Entertainment | $40.00 | $25.00 | 63% | safe |
| Alex | Hostel/Rent | $160.00 | $120.00 | 75% | safe |
| Alex | **Food** | **$30.00** | **$24.00** | **80%** | **at the warning threshold** |
| Bella | Entertainment | $25.00 | $8.00 | 32% | low |
| Bella | Subscriptions | $10.00 | $6.00 | 60% | low |
| Bella | Transport | $20.00 | $9.00 | 45% | low |
| Bella | Gym & Sports | $30.00 | $22.00 | 73% | safe |
| Bella | **Food** | **$25.00** | **$30.00** | **120%** | **above the limit** |

Alex's Food budget is sized so that spending **seven more dollars** raises exactly one `EXCEEDED`
alert and no repeat — that is the deliberate UAT-07 demonstration, left un-fired so a tester can
trigger it live. Bella's Food budget is already over, so the exceeded state is visible on screen
immediately without editing anything.

### C.4 Saving tips (PART 3-E)

Tips were **not** fabricated. They are produced by the server's own generator:

```
CALL sp_generate_tips(<user>, <month>)      -- ×5
CALL sp_generate_monthly_insight(<user>, <month>)  -- ×8
```

Result: **13 `user_tips`** (Alex 9, Bella 4) spanning **5 distinct tip templates**, and **8
`insights`** (Alex 3 for Jul–Sep, Bella 5 for May–Sep), every one `generated_by = RULE_BASED`.
Top-ranked tips by `potential_saving` (BR-14) include `Hostel/Rent spending is up 200.0%` ($80.00),
`Your savings goal is at risk` ($29.00) and `Entertainment spending is up 127.3%` ($14.00) — the last
one being the BR-15 spike that `06_demo.sql` deliberately sets up.

### C.5 Notifications and alerts (PART 3-G)

Notifications exist **only where a business rule supports them**: all three come from
`sp_check_budget_alerts`, and each is backed by a `budget_alert_log` row. There is no
`INSERT INTO notifications` in the demo file.

| Account | Threshold | Consumed | Spent / Limit | Notification |
|---|---|---|---|---|
| Alex | `NEAR` | 80.00% | 24.00 / 30.00 | `Approaching budget limit: Food` |
| Bella | `NEAR` | 80.00% | 20.00 / 25.00 | `Approaching budget limit: Food` |
| Bella | `EXCEEDED` | 120.00% | 30.00 / 25.00 | `Budget exceeded: Food` |

Bella carries **both** rows for the same budget, which is the correct BR-12 behaviour: crossing 80%
fires the near alert, and it is retained rather than replaced when the budget is later exceeded.

### C.6 Recurring rules (PART 3-H)

Four rules, two per account, all `MONTHLY`, `interval_count = 1`, `status = ACTIVE`:

| Account | Type | Amount | Description | Next run |
|---|---|---|---|---|
| Alex | INCOME | 200.00 | Monthly allowance | 2026-10-01 |
| Alex | EXPENSE | 8.00 | Music streaming plan | 2026-10-01 |
| Bella | INCOME | 150.00 | Monthly allowance | 2026-10-01 |
| Bella | EXPENSE | 22.00 | Sports centre membership | 2026-10-01 |

**No occurrence rows were fabricated.** `recurring_occurrences` is empty and the demo deliberately
places every `next_run_date` in the *following* month, so seeding posts nothing and the demo figures
stay exactly as calculated. The file documents — as a comment, not as executed code — how to verify
BR-16 catch-up:

```sql
CALL sp_post_recurring_transactions(DATE_ADD(@m0, INTERVAL 1 MONTH));
```

### C.7 Announcements and admin data (PART 3-I, PART 3-J)

- **Announcements:** the 2 active welcome announcements from the seed, plus 0 QA announcements.
- **Admin data:** `admin_audit_log` is **0 rows** after the cleanup, by design — every pre-existing
  row recorded a QA action. The admin surface is verified against live reads instead:
  `/admin/stats`, `/admin/users`, `/admin/categories`, `/admin/announcements`, `/admin/tip-templates`
  and `/admin/settings` all return **200** (§E).
- **Settings:** all 16 rows intact, including `ai.enabled = true` and
  `ai.send_aggregates_only = true`.

### C.8 Bookmarks — requested, but not producible (PART 3-F)

**`bookmarks` contains 0 rows, and that is a finding, not an omission.** Three facts combine:

1. The server *does* support bookmarks — the flow was exercised live end to end and returned
   `POST /api/v1/bookmarks` → **201** with a real row, then `DELETE /api/v1/bookmarks/{id}` → **204**.
   The row was then removed so the dataset is left at 0.
2. There is **no seed procedure** that writes a bookmark, so nothing in `db/06_demo.sql` can produce
   one through a supported code path.
3. `bookmarks.note` is one of the three **encrypted** columns (AES-256-GCM, OB-012). Writing a row by
   hand would mean hand-writing ciphertext — i.e. fabricating data, which this task forbids.

`docs/HDSD_CAMPUS_COIN.md` §5.5(d) already records that bookmarks, CSV import, tip management,
default-category administration and settings tuning are *"chức năng có ở máy chủ, chưa có màn hình"*
(server-side only, no screen). The empty table therefore matches the documented product. Classified
**BLOCKED** in §F — it needs a UI or a seed procedure, neither of which exists.

### C.9 Runtime rows that are not seed data

On the **live development database** `user_sessions` holds 49 rows (admin 12, Alex 23, Bella 14).
These are not seed data: they accumulate from every sign-in performed during testing, including this
run's verification. On a **fresh rebuild** the same table loads with **0 rows** (§E). A reviewer
comparing the two environments should expect that difference and not read it as a data defect.

---

## D. OpenAPI / Swagger cleanup (PART 5)

### D.1 The principle applied

Swagger's *"Example Value"* is **not** database data. It is a string literal compiled into the DTO,
read by a human before anything is queried, and identical on an empty database and a full one. Two
rules follow, and both were applied:

- **Never hard-code a runtime DB id** unless the id is guaranteed by `db/05_seed.sql`. Budget ids,
  notification ids, tip ids and transaction ids are all assigned at runtime, so their examples now
  say so explicitly instead of implying reproducibility.
- **Where an example names both an id and a name, the pair must agree.** The only ids used are the
  three seed-guaranteed ones: `6` = `Food`, `11` = `Entertainment`, `3` = Bella's account.

### D.2 Stale examples removed or replaced

Old and new values were diffed **machine-to-machine** between the OpenAPI document served by the
pre-change build and the one served after the rebuild: 2,696 string nodes compared, **22 changed**,
all of them example values or `description` text on schema properties — no `example` block on a
*request* body and no path/operation was added, removed, renamed or re-typed.

| Schema / field | Before | After |
|---|---|---|
| `AdminUserResponse.email` | `student@campus.edu` | `binh.tran@student.campuscoin.edu` |
| `AdminUserResponse.fullName` | `Nguyen Van A` | `Bella Tran` |
| `AdminUserResponse.id` (+ description) | `3` | `3`, now stating it belongs to the address and name above |
| `BudgetResponse.categoryId` | `1` | `6`, with "`6` is `Food`" and the BR-11 EXPENSE-only rule |
| `BudgetResponse.categoryName` | `Food & Drinks` | `Food` |
| `BudgetResponse.limitAmount` | `300.00` | `30.00` |
| `BudgetResponse.spentAmount` | `244.50` | `24.00` |
| `BudgetResponse.remainingAmount` | `55.50` | `6.00` — and the description now states `limitAmount - spentAmount`, so the three agree |
| `BudgetResponse.consumedPct` | `81.50` | `80.00` |
| `NotificationResponse.title` | `Approaching budget limit: Food & Drinks` | `Approaching budget limit: Food` |
| `NotificationResponse.body` | `You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).` | `You have used 80.00% of your Food budget (24.00 of 30.00).` |
| `TransactionResponse.categoryColor` | `#EF4444` | `#F97316`, matching `Food`'s seeded colour |
| `CreateBudgetRequest.categoryId` | `1` | `6`, with the EXPENSE-only rule stated |
| `DashboardTopCategoryResponse.categoryId`, `ReportCategoryResponse.categoryId`, `TransactionResponse.categoryId` | `1` | `6`, each paired with the name that goes with it |
| `DashboardTipResponse.categoryId`, `TipResponse.categoryId` | `1` / `10` | `11` = `Entertainment`, a real seed pair |
| `…Response.id` (budget, notification, tip, transaction) | bare "Identifier" | states the value illustrates the *type* and that a real id comes from a response |

### D.3 Stale QA values

A whole-document scan of the **served** document for QA markers returns **0** for `qa.`, `journey`,
`test@`, `Food & Drinks`, `Nguyen Van A`, `student@campus.edu`, `244.50`, `300.00` and `#EF4444`.
The single remaining hit for the substring `probe` is the English verb in the sentence
*"…so that category identifiers cannot be probed"* — a legitimate description, not residue.

### D.4 No contract changes

| Property | Before | After |
|---|---|---|
| Paths | 56 | **56** |
| Operations | 76 | **76** |
| Request-body examples changed | — | **0** |
| Endpoints / status codes / types changed | — | **0** |

Proven mechanically for the Java side: each DTO's file was re-read with every `@Schema(...)`
annotation **stripped** (multi-line aware) and compared byte-for-byte against the same file at
`HEAD`. All **9 of 9** compare identical, so no field, type, name, order or default changed — only
annotation text.

### D.5 One important operational note

The document is served at **`http://localhost:8080/api-docs`** (springdoc is configured with a
custom path). `/v3/api-docs` returns **404** on this build, and `/swagger-ui/index.html` returns 200
while `/swagger-ui.html` redirects. A reviewer who tries the default paths will wrongly conclude
Swagger is missing. (Related: an **earlier** QA report listed `/api/v1/...` paths as 404 because
module 12 was locked at the time. That is no longer the situation — see §F-2.)

---

## E. Validation results

### E.1 The 20-point check, run twice

The same validation script was executed against **two independent environments**:

1. **Fresh rebuild** — `db/merged/campuscoin_full.sql` loaded into an empty database in the
   throwaway container: `mysql < db/merged/campuscoin_full.sql` → `exit=0`, stderr empty (0 errors).
2. **Live development DB** — the running `campuscoin-mysql` volume.

Both produced **identical** results, all 20 checks passing:

| # | Check | Result (identical on both) |
|---|---|---|
| 01 | 3 users, no QA/test/journey/probe residue | `users=3 residue=0` |
| 02 | 12 default + 1 personal category, none QA-named | `total=13 default=12 personal=1 qa_named=0` |
| 03 | BR-08 amount > 0, no future-dated transaction | `n=69 amount_le_0=0 future=0` |
| 04 | one `transaction_history` row per transaction (BR-09) | `txns=69 history=69` |
| 05 | BR-11 one budget per (user, category, month) | `budgets=10 duplicate_groups=0` |
| 06 | BR-12 one alert per (budget, threshold) | `alerts=3 duplicate_groups=0` |
| 07 | every alert backed by a notification row | `alerts=3 with_notification=3 notifications=3` |
| 08 | `transactions` has no `type` column; type comes from the category (BR-05) | `type_columns=0 non_expense_txn_on_expense_cat=0` |
| 09 | BR-06 a personal category is used only by its owner | `violations=0` |
| 10 | BR-02 every transaction has a real owner | `orphan_owners=0` |
| 11 | no orphan rows in any child table | `orphan_txns=0 orphan_budgets=0 orphan_rules=0 orphan_tips=0 orphan_insights=0` |
| 12 | recurring: 4 rules, no fabricated occurrence rows | `rules=4 occurrences=0` |
| 13 | tips and insights exist for BOTH students | `tips_A=9 tips_B=4 insights_A=3 insights_B=5` |
| 14 | required seed content present, no QA rows | `announcements=2 qa_announcements=0 templates=7 qa_templates=0 settings=16 dim_month=96` |
| 15 | no residue in QA-touched tables | `audit=0 imports=0 import_rows=0 category_rules=0 recent_activity=0 reset_tokens=0` |
| 16 | no duplicate user e-mail | `duplicate_emails=0` |
| 17 | no duplicate category name within one scope and type | `duplicate_groups=0` |
| 18 | no soft-deleted or leftover-flagged rows | `soft_deleted=0 flagged=0` |
| 19 | BR-17 six-month view returns six rows per student | `rows=12` |
| 20 | one usable ADMIN, two usable STUDENT accounts | `admins_active=1 students_active=2` |

**Fresh-load counts** (proves the rebuild, not just the repaired volume):
`users 3`, `transactions 69`, `transaction_history 69`, `budgets 10`, `budget_alert_log 3`,
`notifications 3`, `user_tips 13`, `insights 8`, `recurring_rules 4`, `announcements 2`,
`tip_templates 7`, `system_settings 16`, `dim_month 96`, `bookmarks 0`, `admin_audit_log 0`,
`user_sessions 0`, `password_reset_tokens 0`. Every figure matches §A/§C.

### E.2 Database integrity

The rebuild loads a **3922-line** merged file with exit code 0 and no error output, on a full
schema-first load (23 tables, 14 views, 25 procedures + 1 function, 14 triggers, 38 foreign keys).
No constraint was bypassed. Check 11 confirms referential integrity is intact after the deletions.

### E.3 API startup, login and endpoint sweep (live)

After rebuilding the jar (`./mvnw -o package -DskipTests` → BUILD SUCCESS) and restarting the
backend:

| Layer | Result |
|---|---|
| OpenAPI document | `GET /api-docs` → **200**, 56 paths / 76 operations |
| Swagger UI | `/swagger-ui/index.html` → **200** |
| ADMIN login | `admin@campuscoin.edu` → **200** |
| STUDENT logins | Alex → **200**, Bella → **200** |
| Logout contract | `POST /auth/logout` → **204**; reusing the token afterwards → **401** |

**Student endpoints, both accounts, all 200:**
`/dashboard`, `/reports`, `/budgets`, `/transactions`, `/categories`, `/profile/me`,
`/notifications`, `/tips`, `/bookmarks`, `/recurring-rules`, `/insights/months`, `/anomalies`.

**Admin endpoints, all 200:** `/admin/stats`, `/admin/users`, `/admin/categories`,
`/admin/announcements`, `/admin/tip-templates`, `/admin/settings`.

`/admin/stats` payload — every figure reconcilable against §C:

```json
{"totalStudents":2,"activeStudents":2,"disabledStudents":0,"activeUsers30d":3,
 "totalTransactions":69,"totalExpenseLogged":1187.0,"totalIncomeLogged":2205.0,
 "totalBudgets":10,"totalTipsGenerated":13,"totalInsightsGenerated":8}
```

### E.4 Ownership isolation (BR-02)

| Probe | Result | Expected |
|---|---|---|
| `GET /transactions` as Alex | 200, **31** rows | ✔ |
| `GET /transactions` as Bella | 200, **38** rows | ✔ |
| Alex → Bella's transaction | **404** | 404, **not 403** ✔ |
| Bella → Alex's transaction | **404** | 404 ✔ |
| Alex → Alex's own transaction | **200** | 200 ✔ |
| `PATCH` Alex → Bella's transaction | **404** | ✔ |
| `DELETE` Alex → Bella's transaction | **404** | ✔ |

Different row counts (31 vs 38) and a 404-not-403 for every cross-account access confirm the two
datasets are genuinely separate and that the isolation contract is intact.

### E.5 Frontend, with the documented demo credentials

Driven through a real browser against the live backend, signing in with the credentials published in
`docs/CREDENTIALS.md`. **Alex:** feed shows net `+$71.00`, income `$260.00`, expenses `$189.00`,
5 budgets with Food at `80%+`; reports show expenses `$189.00`, income `$260.00`, net `+$71.00`,
6-month trend Jun–Sep at `280/211, 350/199, 290/207, 260/189` and Apr–May at zero; budgets page shows
the alert banner *"Approaching Budget Limit — Food / 80% spent ($24 of $30)"* and an envelope of
`$189.00 of $270.00` (70%); categories show 7 expense + 5 income; profile shows `Alex Nguyen`,
`Year 3`, allowance `200`, goal `100`; quick-add lists the 7 current-month entries with correct
categories; the notification dropdown shows *"You have used 80.00% of your Food budget (24.00 of
30.00)."*. **Bella:** feed net `+$115.00`, income `$190.00`, expenses `$75.00`, Food marked **Over**
(`$30 of $25`), her own `Gym & Sports` visible; reports savings rate `61%`, 6-month trend Apr–Sep
matching the DB exactly, category breakdown including `Gym & Sports $22.00 (29.3%)`. **Admin:**
dashboard, user directory (exactly 3 accounts, all Active) and categories page (12 defaults +
7 template rows) all render. In every module the **data on screen matched the database**.

### E.6 Backend test suite

`Tests run: 1090, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS` (2 min 41 s), executed
**after** the final DTO edits, so the annotation-only changes are covered by a green suite.

---

## F. Remaining problems

Classified as **DATA ISSUE / DOCUMENTATION ISSUE / FRONTEND BUG / BACKEND BUG / BLOCKED**.
Nothing in this section was worked around by changing test data.

### F-1 · FRONTEND BUG · **HIGH** · "Sign Out" does not end the session

Clicking **Sign Out** performs **no HTTP request at all**. Verified by instrumenting
`XMLHttpRequest` in the live page, clicking Sign Out, and reading the captured list back:
**0 requests**. The token and user record remain in `localStorage`, and navigating straight back to
`/app/home` renders the authenticated feed for the same student.

Mechanism (read in source, matches the observation exactly): `nav-sidebar.component.ts:136` calls
`this.auth.logout()`, and `auth.service.ts:128` returns a **cold** `Observable` — it only issues the
`POST /auth/logout` when something **subscribes**, and nothing does. The `tap(() => this.logoutLocally())`
inside that pipe therefore never runs either, which is precisely why the token survives. The backend
side is correct: `POST /api/v1/auth/logout` → **204**, and reusing the same token afterwards → **401**
(§E.3).

Impact: on a shared machine the next person to open the browser is still authenticated as the
previous user, against that user's financial data. This is **already documented** as *TỒN TẠI 1 —
HIGH* in `docs/HDSD_CAMPUS_COIN.md` §5.5(b), with the same workaround (close the browser / clear site
data). It is restated here because this run reproduced it, and because the standard the task set —
*do not claim clean if a defect remains* — is not met while it stands. **The fix is one `.subscribe()`
at one call site.**

### F-2 · FRONTEND BUG · MEDIUM · net-balance and Savings Rate are wrong or blank off the feed screen

Two related presentation defects, both visible on the cleaned dataset:

- **"SEP NET" reads `+$0.00` on every screen except the ones that load transactions.** Observed
  `+$0.00 / ↑ $0.00 / ↓ $0.00` on `/app/reports`, `/app/budgets`, `/app/profile` and
  `/app/categories`, while `/app/home` and `/app/quick-add` correctly showed `+$71.00 / ↑ $260.00 /
  ↓ $189.00`. Cause: `top-bar.component.ts:109` reads `TransactionService.getMonthlyBalance('2026-09')`,
  which sums a private signal that only a transaction-fetching page populates
  (`transaction.service.ts:81`), and there is no loading state — so "no data yet" renders as a
  confident `$0.00`.
- **"Savings Rate" shows two different numbers for the same account and month on two screens.**
  For Alex, September: home feed **71%**, reports **27%**. Reports is right (net ÷ income =
  71/260). The feed renders `summary.savingsGoalPct`, which is net ÷ savings **goal** = 71/100. For
  Bella the same field produces **230%** (115/50) — a value that cannot be a percentage of income.
  The glossary term is unambiguous (`docs/HDSD_CAMPUS_COIN.md:239`, *"tỷ lệ phần trăm giữa số dư
  ròng và tổng thu"* = net ÷ total income), and §4.3 documents only the reports formula. So this is a
  label/field mismatch, not a wrong computation: the field is documented in
  `docs/testing/manual/MODULE_07_MANUAL_TEST.md:256` as `net ÷ goal × 100`, but the screen calls it
  "Savings Rate". Two names are needed, not one.

Both are display-only; no stored data is wrong. **Both are outside the permitted edit scope**
(frontend behaviour changes were limited to stale OpenAPI examples), so they are reported, not fixed.

### F-3 · DATA ISSUE · LOW · PART 3-D's three budget states are split across the two accounts

PART 3 asked one student's budgets to include *one low, one near-threshold and one above-threshold*.
On the full dataset all three exist and are reachable on screen. **On Alex alone**, the account
PART 3 is about, they are low (Transport 48%) and near (Food 80%) — but **not** above, because
Alex's Food budget is deliberately sized so a tester can push it over and watch the `EXCEEDED` alert
fire exactly once. That is a design choice for UAT-07, not an accident. The above-threshold state
lives on Bella (Food 120%, §C.3).

Recorded honestly rather than smoothed over: if the project owner reads PART 3-D as strictly
per-account, `db/06_demo.sql` needs one more over-budget row on Alex — at the cost of the UAT-07
demonstration. My recommendation is to **keep it as is** and treat the split as intentional, but the
decision is the owner's.

Related and milder: Alex's *history* covers four months plus two zero-filled ones, while Bella's
covers all six (§C.2). The requirement is satisfied across the dataset and both a near-empty and a
zero month are demonstrable; only the strictest per-account reading would call this short.

### F-4 · DATA ISSUE · LOW · `db/06_demo.sql` seeds unencrypted descriptions — by design

`transactions.description`, `recurring_rules.description` and `bookmarks.note` are AES-256-GCM
encrypted at the application layer, yet the demo file writes plaintext (e.g. `Dorm rent`). This is
**correct and intended**: `decryptStored()` tolerates legacy plaintext, so the seeded rows read
normally through the API, and the alternative — hand-writing ciphertext — would be fabricating data
*and* require the key inside a SQL file. Recorded here so a reviewer who greps the SQL for base64
does not mistake it for a leak. It is a consequence of OB-012, which is already tracked.

### F-5 · BLOCKED · bookmarks cannot be seeded through any supported flow

See §C.8. Server support is proven (`POST` → 201, `DELETE` → 204), but there is no seed procedure and
`bookmarks.note` is encrypted, so no bookmark can be created without a UI or by fabricating
ciphertext. Matches `docs/HDSD_CAMPUS_COIN.md` §5.5(d), which lists bookmarks (with CSV import, tip
management, default-category administration and settings tuning) as server-side only. **Needs a
screen or a seed procedure — neither exists.**

### F-6 · BLOCKED · the recurring scheduler's posting half is still unverified

Unchanged from the earlier QA report. `recurring_occurrences` is empty and every transaction's
`source` is `MANUAL`; the four rules are stored and displayed correctly but nothing has been posted
by the job. The job runs at 00:05 `Asia/Ho_Chi_Minh`; the only documented ways to force a post are
to back-date `nextRunDate` and wait, or to re-time and restart the backend — and forcing it by
editing the database is explicitly not permitted. M5-24 (catch-up) and M5-25 (idempotency) remain
unverified. **The demo dataset is not a workaround for this**: `06_demo.sql` deliberately sets every
`next_run_date` to the following month so it posts nothing, rather than manufacturing the appearance
of a working scheduler.

### F-7 · FRONTEND BUG · MEDIUM · admin dashboard: locale number formatting and a mislabelled KPI

On the admin dashboard (`/admin/dashboard`), as observed:

- **Total Volume Logged renders `$1.187`** and **Avg. Monthly Student Outflow renders `$594`.**
  The backend sends `1187.0` and the average is `1187 / 2 = 593.5`. `admin-dashboard.component.ts:60,70`
  call `.toLocaleString()` **with no locale**, so the browser's locale decides the separator. In the
  test browser (`navigator.language = vi-VN`) `1187` renders as `1.187` — which an English reader
  reads as *one dollar eighty-seven*. The value is correct; the formatting is not pinned. This is
  exactly the class of defect the dataset's realism exposes and a mock-data build would hide.
- **"Avg. Monthly Student Outflow" is mislabelled.** `admin.service.ts:50` computes
  `totalExpenseLogged / activeStudents`, i.e. **total** expense across all six months divided by
  students — not a per-month average. The label promises a monthly figure and the number is not one.
- **The 14-day "Daily Campus Activity & Volume" chart is hardcoded** (`admin-dashboard.component.ts:159`,
  `Sep 11 … Sep 24`, counts 180–400). The database holds **69 transactions in total across six
  months**, so the chart is off by more than an order of magnitude. Already documented as item 2 in
  `docs/HDSD_CAMPUS_COIN.md` §5.5(c), which correctly classes it as illustrative and advises against
  using it for reporting.
- **Allowance and Goal columns read `$0`** for accounts whose real values are `200` and `100`
  (`admin-users.component.ts:350,352`). Already documented as item 3 in §5.5(c).

None of these is a data defect — the API returns correct values — and all are outside the permitted
edit scope.

### F-8 · DOCUMENTATION ISSUE · MEDIUM · the READMEs publish credentials that do not work

A reviewer following the repository's own instructions **cannot log in**:

| Location | Published | Actually works |
|---|---|---|
| `README.md` (~line 39) and `frontend/README.md` (~line 39) | `alex.morgan@campus.edu` / `password123` | — |
| Both READMEs, admin row | `admin@campuscoin.edu` / `adminpass` | `Admin@123` per `docs/CREDENTIALS.md` |
| `forgot-password.component.ts:40` | placeholder `alex.morgan@campus.edu` | no such account |
| `register.component.ts:61` | placeholder `student@campus.edu` | domain does not exist — the campus domain is `student.campuscoin.edu` |

The running login screen's own **Student Fill** button fills the correct
`an.nguyen@student.campuscoin.edu`, so the application and the README disagree. Two further stale
claims sit in the same table: `README.md:29` says *"The Angular frontend is still mock-only and has
not yet been wired to the API"* and `README.md:39` lists the presentation tier as *"In progress (mock
data only)"* — both false; this run drove the live frontend against the live API for every module in
§E.5. `frontend/src/app/mock-data/` (4 files) is now **orphaned**: zero importers anywhere under
`frontend/`, with `useMockData: false` in both environments.

These are the *"references that are clearly incorrect"* the task allowed to be removed. They were
**left in place and reported instead**, because the permitted edit scope was frontend behaviour and
OpenAPI examples, and unilaterally rewriting a reviewer-facing README is a project-owner decision.
Each is a one-line fix.

### F-9 · NOT A DEFECT · two observations recorded so they are not misread later

- **`user_sessions` = 49 on the live DB, 0 on a fresh rebuild** (§C.9). Runtime rows from testing,
  not seed data.
- **`admin_audit_log` = 0.** Correct after the cleanup, since every pre-existing row recorded a QA
  action (§A.2). The admin surface works; it simply has no history because none of the surviving
  data has an admin action behind it.

### F-10 · BACKEND BUG · none found

No backend defect was observed in this run, and none is claimed. Where behaviour looked inconsistent
the cause was traced and is stated as such: the `SEP NET` zeros are a frontend data-population issue
(F-2), and `$1.187` is a frontend formatting issue over a correct API value (F-7). **No
cross-endpoint inconsistency remains unexplained.** In particular, the earlier report's
`/api/v1/...` 404 list is superseded — module 12 is implemented and locked, and every one of those
paths now answers **200** (§E.3).

---

## Summary against the ten requested parts

| Part | Status |
|---|---|
| 1 — know what must stay | ✔ §B, all 9 items present and verified |
| 2 — remove QA/junk | ✔ §A, inventory kept/deleted with reasons; FKs respected, no guard disabled |
| 3 — realistic demo dataset (A–J) | ✔ §C, with **two qualifications reported, not hidden**: bookmarks cannot be produced (F-5), and the three budget states are split across accounts (F-3) |
| 4 — second student, different data | ✔ §C.1 — 38 vs 31 rows, own category, own alerts, different budget mix |
| 5 — OpenAPI example cleanup | ✔ §D — 22 changes, 0 contract changes, all 9 DTO diffs provably annotation-only |
| 6 — reproducible clean rebuild | ✔ §E.1–E.2 — `exit=0`, 0 errors, every documented figure reproduced |
| 7 — 20-point validation | ✔ §E.1 — 20/20 on both a fresh load and the live DB |
| 8 — verify the real frontend | ✔ §E.5 — every module, both students, one admin, documented credentials |
| 9 — do not hide bugs | ✔ §F — 8 findings classified, including one HIGH retained from the earlier report |
| 10 — this report | ✔ — §A–§F present |

**Final status: M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL.**

**The dataset is clean and validated. The system is not defect-free**, and this report does not say
otherwise: F-1 is a HIGH-severity session defect that is reproducible on the cleaned dataset and is
unfixed. F-2, F-7 and F-8 are user-visible and also unfixed. F-5 and F-6 are blocked pending a
decision outside this task's scope.
