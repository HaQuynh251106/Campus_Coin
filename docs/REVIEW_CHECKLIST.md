# Final review cross-check — Campus Coin

This document cross-checks **each of the 9 final review points** against its exact location in the SQL source, so a reviewer can open the file and see at once where the change is — rather than taking a description on trust.

It is accompanied by the results of section **C. Final verification** (6 steps) and section **B. All content in English**.

---

## A. Nine points fixed

| # | Requirement | Location | How it was done |
|---|---|---|---|
| 1 | Remove `@cc_is_admin` from the main privilege mechanism | `db/03_procedures.sql:210` (`sp_require_admin`), `db/04_triggers.sql` (`trg_categories_before_update`) | Delete every `SET @cc_is_admin = 1;` from `sp_admin_upsert_default_category` and delete the block in the trigger that reads this variable. Administrator privileges are decided by `sp_require_admin(p_actor_id)` **only**, based on data in `users`. Verification: no occurrence remains in any procedure or trigger |
| 2 | `sp_require_admin()` requires **both** `role = 'ADMIN'` **and** `status = 'ACTIVE'` | `db/03_procedures.sql:210-222` | Reads both columns into `v_role`, `v_status` and then refuses if either fails: *"BR-06: administrator privileges required (role ADMIN and status ACTIVE)"* |
| 3 | `sp_touch_recent_activity()` must check ownership of the transaction | `db/03_procedures.sql:1501-1507` | Looks up `transactions.user_id`; if it does not exist → *"Transaction does not exist"*; if it differs from `p_user_id` → *"BR-02: cannot record activity for a transaction owned by another student"*. It also rejects an `action` outside `VIEWED`/`EDITED` |
| 4 | Remove `import_rows.user_id`; ownership is derived from `import_batches` | `db/01_schema.sql:639` (comment), `db/03_procedures.sql` (`sp_apply_csv_batch`) | Deletes the column and the `fk_import_row_user` foreign key. The cursor in `sp_apply_csv_batch` no longer reads `user_id`; every transaction it generates uses `v_user_id` taken from the batch. Verification: `information_schema` returns 0 `user_id` columns on `import_rows` and 0 `fk_import_row_user` constraints |
| 5 | `sp_apply_csv_batch()` must respect `resolved_category_id` | `db/03_procedures.sql:862-875` | The category chosen in the preview step is checked for continued validity (it exists, is `is_active = 1`, and is a default category or belongs to the student) and then **used as-is**; only when `resolved_category_id IS NULL` is it resolved by the name in the file. An inline comment states why the file's `type` is **not** cross-checked: a student editing the category is exactly how they correct a wrong type, and cross-checking would silently undo that correction |
| 6 | Check ownership of `recurring_rule_id` and `import_batch_id` | `db/03_procedures.sql:128-151` (`sp_validate_transaction`) | Reads `recurring_rules.user_id` / `import_batches.user_id`; if it does not exist → *"... does not exist"*; if it belongs to someone else → *"... belongs to another student"*. These two columns deliberately have **no** foreign key (see `DB_DESIGN.md` §4.8 for the reason) |
| 7 | Do not allow `categories.type` to change once the category is referenced | `db/04_triggers.sql:94-102` | `trg_categories_before_update` blocks the change if `type` changes **and** the category is referenced by `transactions`, `budgets` **or** `recurring_rules`. The simple rule chosen: once used, the type cannot change — create a new category instead |
| 8 | Fix the ISO week grouping | `db/02_views.sql:256-277` | `v_weekly_spending_current_month` uses `YEARWEEK(txn_date, 3)` (a single value carrying both the ISO year and the ISO week) and then splits it with `DIV 100` / `MOD 100`, replacing `YEAR()` + `WEEK(...,3)`, which take the calendar year and so split a week in two at a year boundary. It must go through a derived table to avoid error 1055 under `only_full_group_by` |
| 9 | Password reset must be atomic | `db/03_procedures.sql:1033-1041` (`sp_complete_password_reset`) | Merges checking and consuming the token into **one** `UPDATE ... SET used_at = NOW() WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()` statement, then tests `ROW_COUNT() = 0` to refuse. There is no longer a gap between the check step and the write step |

---

## B. All system content in English

Converted: table/column names, ENUM values (`STUDENT`, `ADMIN`, `ACTIVE`, `DISABLED`, `INCOME`, `EXPENSE`, `MANUAL`, `CSV`, `RECURRING`, `PINNED`, `DISMISSED`, …), constraint names, procedure/function/trigger/view names, seed data (16 configuration keys, 12 default categories, 7 tip templates, 2 announcements, 3 accounts), and every error message returned by a procedure or trigger.

**Verification method (not counted by eye):** generate a `SELECT` for **every** text column of **every** table (88 columns across **22** tables, including `JSON` and `ENUM`; the `budgets` table has no text columns so it does not appear) to count the rows containing non-ASCII characters. Result: **0 rows**.

Five places were fixed during the sweep, all of them stored content displayed to users:

| Place | Before | After |
|---|---|---|
| `system_settings.description` (`app.currency`) | `Currency used system-wide (VĐ-08)` | `Currency used system-wide` |
| `system_settings.description` (`app.timezone`) | `Time zone fixing the day / week / month boundaries (VĐ-10)` | `Time zone fixing the day / week / month boundaries` |
| `system_settings.description` (`app.week_start`) | `Weeks start on Monday (VĐ-10)` | `Weeks start on Monday` |
| `system_settings.description` (`ai.send_aggregates_only`) | `VĐ-12: send aggregates only, never student identifiers` | `Send aggregates only to the AI service, never student identifiers` |
| `tip_templates.body_template` (2 templates) | an em dash `—` and `15–20%` | `-` and `15-20%` |

**Deliberate exception:** the reference code `VĐ-xx` (short for "vấn đề" — an item needing clarification in the Use Case document) remains in **`--` comments** in some SQL files, used to trace back to the requirements document. Comments are not stored in the database and are not shown to end users — they appear only when a developer opens the `.sql` file. If you do not want to keep them, they can be deleted with no effect on operation.

**Exception by requirement:** content entered by the student themselves (for example `transactions.description`) may be in any language — the requirement applies only to content generated by the system.

---

## C. Final verification

| Step | What was done | Result |
|---|---|---|
| 1 | Rebuild from an empty MySQL 8 database | `DROP DATABASE` then `CREATE DATABASE`, run again from scratch |
| 2 | Run all 6 files in the correct order | All 6 files exit without error, no `ERROR` line at all |
| 3 | Check tables, views, procedures, functions, triggers, FKs, UNIQUE, CHECK | 23 tables · 14 views · 24 procedures · 1 function · 14 triggers · **38** FKs · 15 UNIQUE · 14 CHECK |
| 3b | `SELECT` from **each** view to be sure it runs (not just that it exists) | **Found 1 real bug:** `v_weekly_spending_current_month` could be created but broke when queried (error 1055 from `only_full_group_by`) — fixed with a derived table, see `DB_DESIGN.md` §4.11 |
| 4 | Scan the entire SQL source for left-over Vietnamese text | See section B above: 0 non-ASCII rows in stored data |
| 4b | Scan the **body of procedures/functions/triggers/views** (not just table data) for error messages still in Vietnamese | Scanned all 53 objects, separating the **executable code** from the **string literals**: 0 non-ASCII characters in both. Three objects have non-ASCII characters because of **`--` comments** inside their body (`sp_admin_set_threshold`, `sp_generate_tips`, `trg_transactions_after_insert`) — see the exception in section B |
| 5 | Check that no reference to a deleted column remains | No `import_rows.user_id`, no `fk_import_row_user`. Two results named `t.type` are **false positives** (the alias `t` points at the view `v_category_month_totals`, not `transactions`) — each was checked individually |
| 5b | Cross-check the **documentation** against the real database | Checked 125 column declarations + 19 ENUM declarations in `ERD.md`: every column exists, every ENUM value extracted is real. Also checked all **59 relationship lines** against **each foreign key**: **0 cardinality errors**, **0 wrong `FK` labels**. See section C.2 below |
| 6 | Run the regression tests | **54/54** checks (fixed figures + behaviour + every view runs), all passing |
| 7 | Reload the **merged file** `db/merged/campuscoin_full.sql` from scratch into an empty database | No errors; the counts come back as exactly 23/14/24/1/14/38/15/14; **54/54** runs again on the build made from the merged file itself |
| 7b | Compare **line by line** the merged file against the 6 source files | **Found 1 real bug:** the merged file still held the **old** version of `06_demo.sql` (a hard-coded date `'2027-01-01'`), meaning the fix from step 5 had never made it into the merged file. The merged file was regenerated from the 6 source files and re-checked: all 6 parts are **identical** to their source |

### Rules covered by the regression run

**As required:** BR-02, BR-05, BR-07, BR-08, BR-09, BR-11, BR-12, BR-13, BR-15, BR-16, UAT-06, UAT-07, UAT-09.

**Additional:** BR-03, BR-04, BR-06, BR-14, BR-17, UC-09, UC-11, UC-13, UC-15, UC-20, UC-22, UC-24, VĐ-05, VĐ-10.

### What the regression tests caught

Recorded to show this step was not a formality:

1. **Error 1055 on the weekly view** (section C step 3b) — a real bug, produced by the very fix in point 8. The step of "counting whether a view exists" **does not** catch it, because MySQL accepts a bad definition and only refuses when the data is read.
2. **The procedure's protective constraint did not run in some of the first checks** — the initial test set only caught error code `1644`, so two checks "passed" thanks to the UNIQUE (`1062`) and CHECK (`3819`) constraints without ever reaching the procedure under test. The test set was fixed to accept every error code and print the real one.
3. **`sp_apply_csv_batch` once had a bug caused by the fix in point 5 itself** — the check condition still cross-checked the file's `type`, so a student's valid category edit was rejected and then silently resolved again by the name in the file, which is precisely what point 5 required it not to do. That condition was removed.

### C.2 Cross-checking the documentation against the real database

This step was not one of the 6 you asked for, but it was done because cross-checking revealed a real bug. Results:

**A real bug in the source — wrong reference code system.** `db/01_schema.sql` cited `OQ-01..OQ-13` in 13 places, but **that code exists in no document**: the SRS has no `OQ` codes at all, while the Use Case document uses `VĐ-01..VĐ-13` for section 11, "Assumptions & open issues". All 13 places were changed to the correct `VĐ-xx` code from the source table (for example `VĐ-10` for the time zone and Monday week start, `VĐ-12` for the data sent to the AI service). From now on the reference codes in the SQL point at the real sections of the document.

**A real bug in the merged file — a fix that did not propagate.** `db/merged/campuscoin_full.sql` still contained the **old** version of `06_demo.sql`, whose instructions for checking BR-16 used a hard-coded date `'2027-01-01'` — meaning the hard-coded-date fix had never reached the file the user actually loads. Only comparing it line by line against the 6 source files revealed this: counting lines and counting objects both **fail** to catch it because it changes no quantity at all. The merged file was regenerated from the 6 source files and re-checked: all 6 parts are **identical** to their source (711 + 353 + 1552 + 430 + 180 + 197 lines).

**Seven places where the documentation described the database incorrectly** (fixed in `docs/ERD.md`):

| Place | Documentation said | Reality in the database |
|---|---|---|
| Relationship to `budget_alert_log` / `notifications` | Drawn as a foreign key | `budget_alert_log.notification_id` has **no** foreign key — a real relationship but deliberately unconstrained |
| `transactions.recurring_rule_id`, `.import_batch_id` | Labelled `FK` | No foreign key (index only), exactly as `DB_DESIGN.md` §4.8 explains |
| Cardinality of `categories → user_tips`, `tip_templates → user_tips`, `user_tips/insights → bookmarks` | Parent side is `\|\|` (mandatory) | The child column allows `NULL`, so it must be `\|o` |
| Cardinality of `budget_alert_log → notifications` | `\|o--o\|` in the overview diagram, `\|o--\|\|` in the group diagram — **the two contradict each other** | Unified to `\|o--o\|` in both places |
| Number of table groups | "split into 7 groups" | The diagram draws 5 sections; `01_schema.sql` splits them into 7 groups. Both are now stated explicitly and explained |
| Opening sentence about relationships | "Four columns without a foreign key **are still drawn**" | Only **three** columns are drawn. The fourth (`import_rows.ai_suggested_category_id`) is not drawn — corrected to an explicit list, with the reason |
| Documentation pointer | "The inventory of the 38 real foreign keys is in §2 of `DB_DESIGN.md`" | §2 **only has the count table**, no inventory. A **§2.1** section listing all 38 foreign keys (generated from `information_schema`) was added so the pointer is correct |

Checked by machine, not read by eye: queries were generated to cross-check **every column declaration in `ERD.md`** and **all 59 relationship lines** against `information_schema` — cardinality derived directly from the child column's `is_nullable` and the uniqueness of the index, each foreign key checked **column by column** rather than by table pair (because some tables have two foreign keys pointing at the same parent, for example `transactions` has both `category_id` and `ai_suggested_category_id`). After the fixes: **0 cardinality errors**, **0 wrong `FK` labels**, and the **8 real foreign keys that are not drawn** are now listed explicitly in the "How to read the diagrams" section so readers do not mistake the diagram for an incomplete one.

**Two places where the documentation described the mechanism incorrectly** (fixed in the two cross-check tables of `ERD.md`): the BR-04 row said "`ck_...` via the procedure" whereas `password_reset_tokens` has **no** CHECK constraint — the real mechanism is `sp_complete_password_reset`; and the BR-07 row named only the trigger whereas `trg_categories_before_delete` **checks budgets only**, while transactions and recurring rules are blocked by the `ON DELETE RESTRICT` foreign keys (the trigger does not run on cascading deletes).

**One place that could rot over time:** `db/06_demo.sql` instructed checking BR-16 with a fixed date `2027-01-01`. It was changed to the relative expression `DATE_ADD(@m0, INTERVAL 1 MONTH)` so the instructions do not go stale. The two remaining fixed points (`2023-01-01`, `2030-12-01`, which generate `dim_month`) are a valid range and still cover well beyond the required 6 months.

---

## D. Remaining work

**The database has not been re-Dockerised, and the state of the container needs to be stated clearly — because it differs from the original expectation.**

The `campus-coin-mysql` container is still running untouched (no `docker compose down`, `down -v`, `up` or `restart` was ever run during the whole verification). However, the command used to verify step 7 — loading `db/merged/campuscoin_full.sql` — **accidentally rebuilt the `campuscoin` database itself**, rather than a temporary database as the name `cc_final` suggests. The reason: the merged file (and `01_schema.sql` too) contains its own three statements

```sql
DROP DATABASE IF EXISTS campuscoin;
CREATE DATABASE campuscoin ...;
USE campuscoin;
```

so the database name given on the `mysql` command line has no effect on the merged file — the file decides its own destination.

**Consequences, checked through `information_schema` rather than assumed:**

- `campuscoin` now holds the **new schema**: 23 tables · 14 views · 24 procedures · 1 function · 14 triggers · **38** FKs · 15 UNIQUE · 14 CHECK; `import_rows` does **not** have a `user_id` column; **0** occurrences of `cc_is_admin` in any procedure/trigger. This is no longer the "old SQL build" this document once described.
- The sample data matches the seed set exactly: 3 users · 12 default categories · 31 transactions · 5 budgets · **1** alert (UAT-07) · 9 tips · 3 insights.
- The privileges of the application account `campuscoin_user` are still intact (`GRANT ALL PRIVILEGES ON campuscoin.*`) because MySQL grants privileges by database **name**, not by content; this was confirmed by successfully reading with that account.
- Every temporary database has been deleted; only `campuscoin` remains.

**This does NOT mean it "has been re-Dockerised".** The rebuild above went through the `mysql` command line only, **not** through Docker's initialisation path. The two paths differ in one important respect: Docker runs **one file at a time** in filename order in `db/` (confirmed against the init log: `running /docker-entrypoint-initdb.d/01_schema.sql` … `06_demo.sql`), not the merged file; and Docker does **not** descend into the `db/merged/` subdirectory (the init log never mentions `merged`). The six-separate-file path was re-run on a temporary database for cross-checking: all 6 files exit without error, and the 54-check set passes **54/54**.

So, before you confirm, three things need to be stated clearly:

1. `campuscoin` **does** hold the correct, most recent schema — but it was built through the command line, not through Docker's initialisation mechanism.
2. The `mysqldata` volume still holds its data the way Docker manages it; only the database content inside has been replaced with the new build.
3. To let Docker rebuild it properly through its own mechanism (and to be sure the init path runs correctly from an empty volume), the next step is still `docker compose down -v && docker compose up -d` — to be run **only after you confirm**.

If you prefer, I can run nothing at all and leave the current state as it is; the database already has the right schema so it remains usable immediately.

---

### D.2 Update — re-Dockerised (final step)

After you confirmed, the Docker part was settled:

- `docker-compose.yml` was rewritten: image `mysql:8.0`, container `campuscoin-mysql`, port `3306:3306`, volume `mysqldata`, a healthcheck using the application account itself, `restart: unless-stopped`. Adminer moved to port `8080:8080`.
- Credentials moved out to `.env` (already covered by `.gitignore`); `.env.example` is the committed template. The application account was renamed to `campuscoin_app`; `campuscoin_user` no longer exists.
- The init path changed from six separate files to **one** file: `db/merged/campuscoin_full.sql` mounted as `/docker-entrypoint-initdb.d/01-campuscoin.sql`. Only a single line, `running /docker-entrypoint-initdb.d/01-campuscoin.sql`, appears in the log.
- `docker compose down -v` was run on the old stack and then `up -d` from an empty volume. Verification: the container is `healthy`; exactly 23 tables · 14 views · 24 procedures · 1 function · 14 triggers · 38 FKs · 15 UNIQUE · 14 CHECK; the sample data 3/12/31/5/1/9/3/7/16 matches exactly; `sp_generate_tips` and `sp_apply_csv_batch` are indeed the patched versions (no `80`/`100` constants left in the code).
- Checked that no reload happens: `restart` and `down` + `up -d` on the existing volume both **do not** re-run the init directory; the `create_time` of the `users` table and the transaction row count are unchanged across both.

Section D above is therefore of historical value only — the state it describes (`campuscoin_user` account, ports `3307`/`8081`, six separate files) has been replaced by the configuration in this section D.2.
