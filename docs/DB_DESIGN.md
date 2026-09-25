# Database design — Campus Coin

This document explains **why** the database is designed as it is, and records the places where the requirements document was not clear enough to build the tables.

- Relationship diagram: [`ERD.md`](ERD.md)
- Cross-check table use case / business rule → database object: [`ERD.md`](ERD.md#documentation--database-object-cross-check)
- Try-out accounts: [`CREDENTIALS.md`](CREDENTIALS.md)

---

## 1. Design sources

| Document | Content used for the design |
|---|---|
| SRS Campus Coin v1.0 | §1.4 three-tier architecture, §1.6 category and transaction management, §1.8 security, §1.9 submission requirements |
| Use Case & Business Flow specification v1.0 | UC-01…UC-27, BR-01…BR-18, VĐ-01…VĐ-13, UAT-01…UAT-17, entity model section 8 |

SRS §1.6 states clearly: *"These are just examples; you do not have to adhere to these structures and can design your own table structure with different columns."* The model below therefore keeps the spirit of the sample tables in the SRS but extends it with the tables the use case specification requires.

DBMS: **MySQL 8.0+**, collation `utf8mb4_0900_ai_ci`, InnoDB engine.

---

## 2. Overview of the figures

| Component | Count |
|---|---|
| Tables | 23 |
| Views | 14 |
| Procedures | 24 |
| Functions | 1 |
| Triggers | 14 |
| Foreign keys | 38 |
| CHECK constraints | 14 |
| UNIQUE keys | 15 |

### 2.1 Inventory of the 38 foreign keys

The table below lists **all** foreign keys that actually exist in the database, generated directly from `information_schema` (not copied by hand) for cross-checking against the diagram in [`ERD.md`](ERD.md).

| Child table | Column | → Parent table | On parent delete |
|---|---|---|---|
| `admin_audit_log` | `admin_user_id` | `users` | CASCADE |
| `announcements` | `created_by` | `users` | SET NULL |
| `bookmarks` | `insight_id` | `insights` | CASCADE |
| `bookmarks` | `tip_id` | `user_tips` | CASCADE |
| `bookmarks` | `user_id` | `users` | CASCADE |
| `budget_alert_log` | `budget_id` | `budgets` | CASCADE |
| `budget_alert_log` | `category_id` | `categories` | CASCADE |
| `budget_alert_log` | `user_id` | `users` | CASCADE |
| `budgets` | `category_id` | `categories` | RESTRICT |
| `budgets` | `user_id` | `users` | CASCADE |
| `categories` | `created_by` | `users` | SET NULL |
| `categories` | `user_id` | `users` | CASCADE |
| `category_rules` | `category_id` | `categories` | CASCADE |
| `category_rules` | `user_id` | `users` | CASCADE |
| `import_batches` | `user_id` | `users` | CASCADE |
| `import_rows` | `batch_id` | `import_batches` | CASCADE |
| `import_rows` | `resolved_category_id` | `categories` | SET NULL |
| `import_rows` | `transaction_id` | `transactions` | SET NULL |
| `insights` | `user_id` | `users` | CASCADE |
| `notifications` | `user_id` | `users` | CASCADE |
| `password_reset_tokens` | `user_id` | `users` | CASCADE |
| `recent_activity` | `transaction_id` | `transactions` | CASCADE |
| `recent_activity` | `user_id` | `users` | CASCADE |
| `recurring_occurrences` | `rule_id` | `recurring_rules` | CASCADE |
| `recurring_occurrences` | `transaction_id` | `transactions` | SET NULL |
| `recurring_rules` | `category_id` | `categories` | RESTRICT |
| `recurring_rules` | `user_id` | `users` | CASCADE |
| `system_settings` | `updated_by` | `users` | SET NULL |
| `tip_templates` | `created_by` | `users` | SET NULL |
| `transaction_history` | `changed_by` | `users` | SET NULL |
| `transaction_history` | `transaction_id` | `transactions` | CASCADE |
| `transactions` | `ai_suggested_category_id` | `categories` | SET NULL |
| `transactions` | `category_id` | `categories` | RESTRICT |
| `transactions` | `user_id` | `users` | CASCADE |
| `user_sessions` | `user_id` | `users` | CASCADE |
| `user_tips` | `category_id` | `categories` | CASCADE |
| `user_tips` | `tip_template_id` | `tip_templates` | SET NULL |
| `user_tips` | `user_id` | `users` | CASCADE |

**Four columns deliberately have NO foreign key** (reasons in §4.8): `transactions.recurring_rule_id`, `transactions.import_batch_id`, `budget_alert_log.notification_id`, `import_rows.ai_suggested_category_id`. They therefore do not appear in the table above.

---

## 3. Gaps in the documentation and the additions

The use case specification describes the business in great detail but at some points does not state the data structure clearly. The table below records each such point and how it was handled, so a marker can see clearly which parts come from the documents and which are well-founded additions.

| Unclear point in the documents | Addition | Basis |
|---|---|---|
| The `TRANSACTION` table has no transaction-type column, while `CATEGORY` does | **Add no column.** The transaction type comes from `categories.type`; add a guard preventing the `type` of a category that already has transactions from being changed | BR-05 requires these two values to match — the only way to guarantee that is to keep a single source of truth. The guard rule is a design decision (see §4.7) |
| BR-09 says "preserve the history" but does not say what is recorded | `transaction_history` stores `old_values` / `new_values` as JSON together with the list of changed columns | BR-09, VĐ-09 |
| UC-08 says the system "learns from edits" but does not say how it learns | `category_rules` — a mapping from key to category, per student | UC-08, BA note: model training is not required |
| UC-26 says "across sessions, across devices" | `recent_activity` is tied to the account rather than to the browser | UC-26 |
| BR-03 says an open session is revoked, but does not say how it is revoked | `user_sessions` + `users.token_version` to invalidate already-issued JWTs | BR-03, UAT-13 |
| BR-17 requires a month with no data to show 0, but `GROUP BY` cannot produce an empty month | A `dim_month` dimension table to `LEFT JOIN` from | BR-17 |
| UC-22 B5 says "log administrative actions" but does not describe a table | `admin_audit_log` | UC-22 B5 |
| UC-11 says preview before importing, but does not say where the preview is stored | `import_batches` + `import_rows` | UC-11 B4–B9 |
| UC-23 defines "active users" but does not say the time window | The `v_admin_usage_stats` view uses a 30-day cut-off | BA note on UC-23 |
| BR-12 needs thresholds of 80% and 100%; BR-15 needs 30%; VĐ-05 says the thresholds must be adjustable | `system_settings` instead of constants in the source code | VĐ-05 |
| VĐ-08 says a single currency but does not say where it is configured | `system_settings` key `app.currency` | VĐ-08 |
| VĐ-10 says the week starts on Monday and the time zone must be fixed | `system_settings` keys `app.week_start` / `app.timezone`; uses `YEARWEEK(..., 3)` per the ISO standard (see §4.11) | VĐ-10 |
| VĐ-12 says only aggregate data is sent to the AI service | `system_settings` key `ai.send_aggregates_only` | VĐ-12 |
| The SRS requires exporting reports but does not say whether files are stored | No buffer table — the file is generated at call time, in the spirit of BR-18 | BR-18 |

---

## 4. Key design decisions

### 4.1 Generated `VIRTUAL` columns instead of `STORED`

Three tables need a unique key on a value derived from another column:

| Table | Generated column | Purpose |
|---|---|---|
| `categories` | `scope_key = IFNULL(user_id, 0)` | Both "default categories do not collide with each other" and "personal categories do not collide within the same student" |
| `user_tips` | `dedupe_key` | A dismissed tip is not regenerated in the same period |
| `bookmarks` | `dedupe_key` | Do not bookmark the same item twice |

**Why `VIRTUAL`:** MySQL 8 refuses to create a foreign key with `ON DELETE CASCADE` on a table containing a `STORED` generated column, reporting error 1215 *"Cannot add foreign key constraint"*. All three tables above have `ON DELETE CASCADE` foreign keys pointing at `users`. A `VIRTUAL` generated column can still be indexed, so the unique key keeps its effect.

**Why a generated column rather than a partial index:** MySQL does not support filtered indexes as PostgreSQL does. A generated column combined with a unique key is the equivalent expression.

### 4.2 Not naming a column `row_number`

`ROW_NUMBER` is a reserved keyword in MySQL 8 (a window function name). Using it as a column name causes syntax error 1064 as soon as the table is created. The column numbering the CSV rows in `import_rows` is therefore named `csv_row_no`.

### 4.3 Effect of triggers on cascading deletes

MySQL **does not fire triggers** for deletes that cascade through a foreign key. Consequences:

- `trg_transactions_before_delete` blocks every direct `DELETE` on `transactions` (BR-09), but when an account is deleted from `users`, the related data is still cleaned up normally.
- This is the desired behaviour: deleting an account is a legitimate administrative action, whereas deleting a single transaction must use a soft delete.

When you need to clean up data manually during development, drop the trigger and recreate it:

```sql
DROP TRIGGER trg_transactions_before_delete;
-- ... cleanup operations ...
-- recreate it by running db/04_triggers.sql again
```

### 4.4 Checking administrator privileges: a single gate, verified with `p_actor_id`

MySQL does not know who is signed in when an SQL statement is executed, so the question is: **how should administrator privileges be checked so they cannot be forged?**

The design uses **a single gate**. Every administrative action goes through:

```sql
CALL sp_require_admin(p_actor_id);
```

`sp_require_admin` takes the **id of the acting user** (it does not take a boolean flag), looks it up in `users`, and lets the call through only when the account has **both** `role = 'ADMIN'` **and** `status = 'ACTIVE'`. To pass this gate, the caller must name a real, active administrator account in the `users` table — they cannot "declare themselves an administrator" by setting a variable.

| Action | Procedure | Basis |
|---|---|---|
| Disable / enable an account | `sp_set_user_status` | UC-22 B3, BR-03 |
| Send a password reset link | `sp_admin_send_password_reset` | UC-22 B4 |
| Add / edit a default category | `sp_admin_upsert_default_category` | UC-20, BR-06 |
| Post a system-wide announcement | `sp_admin_create_announcement` | UC-21 B1 |
| Enable / disable an announcement | `sp_admin_set_announcement_active` | UC-21 B2 |
| Add / edit a tip template | `sp_admin_upsert_tip_template` | UC-21 B3 |
| Adjust business thresholds | `sp_admin_set_threshold` | VĐ-05 |

> **Design decision (changed):** an earlier version also used a session variable `@cc_is_admin` as a "final guard" at the trigger layer. That approach has been **removed**, because a MySQL session variable belongs to the **connection**, not to the user: with a connection pool, a connection that sets `@cc_is_admin = 1` and is then returned to the pool can leave that flag behind for another person's request that reuses the same connection. Administrator privileges are therefore decided **only** in `sp_require_admin`, based on data in `users`.

**A trade-off to be aware of:** SQL statements that operate directly on tables from a database administration tool (Adminer, Workbench) do not go through `sp_require_admin` and so are not blocked. This is a deliberate trade-off: a `BEFORE UPDATE` trigger cannot know who is running the statement, and the only way to "tell" it would be the very session variable that was removed. The application is therefore **not granted direct write privileges**: the only legitimate write path is through a procedure. See also §6 — "Data safety".

The trigger layer is still kept for rules that **do not depend on identity** (BR-05, BR-07, BR-09, BR-11, BR-13, `transaction_history`), because those rules hold for every caller, including hand-run statements.

### 4.5 Budget alerts use `ELSEIF`

When a transaction pushes consumption from below 80% straight past 100%, the `sp_check_budget_alerts` procedure generates only the **"exceeded"** notification and does not also generate "near limit". The reason is that two notifications at once cause noise and add no information.

UAT-07 still meets the requirement exactly because the test scenario consists of **two separate writes**: spending 24 (hitting 80%) generates a "near limit" alert, then spending a further 7 (reaching 103%) generates an "exceeded" alert.

### 4.6 Order in which the files run

```
01_schema.sql     Creates tables, foreign keys, indexes, CHECK constraints
      ↓
02_views.sql      Views referencing tables
      ↓
03_procedures.sql Functions and procedures
      ↓
04_triggers.sql   Triggers calling the procedures from file 03
      ↓
05_seed.sql       Required data
      ↓
06_demo.sql       Demonstration data (optional)
```

Changing the order will fail: file 04 calls procedures created by file 03, and file 06 calls procedures created by file 03, so it must come after it.

**The merged file `db/merged/campuscoin_full.sql`** concatenates the six files above in exactly this order, with identical content, so it can be loaded in one go with `mysql < db/merged/campuscoin_full.sql`. The file sits in a subdirectory because Docker runs every `.sql` at the top level of `db/` — putting it there would build the schema twice on the first initialisation.

### 4.7 Protecting history when `transactions` has no `type` column

Because the transaction type lives in `categories.type`, changing the `type` of a category that is already in use would **silently rewrite the entire reporting history** — every past expense suddenly becomes income, and vice versa. `trg_categories_before_update` blocks that operation, including for an administrator:

```sql
-- Blocked if the category is already referenced by a transaction, budget or recurring rule
UPDATE categories SET type = 'INCOME' WHERE id = <category already referenced>;
-- ERROR 1644: A category referenced by transactions, budgets or recurring rules
--             cannot change its type; create a new category instead
```

The blocking condition considers **all three** reference sources: `transactions`, `budgets` and `recurring_rules`. Checking only `transactions` is not enough: a category that has a budget limit but no transactions yet would still make the budget report wrong if its type changed from expense to income, and a category in use by a recurring rule would make that rule fail on its next run.

A category that is **not** referenced by any of the three sources can still change its type normally.

> **Design decision:** the rule "once it has data, the type cannot change" is not in the SRS or the Use Cases. It is a direct consequence of choosing a single source of truth. The way to handle it when a change is genuinely needed: create a new category of the right type and move the transactions to it.

### 4.8 Owner checks for cross-referenced data

There are many places where a foreign key is **not enough** to enforce BR-02 / BR-13, because a foreign key only knows "the row pointed at exists", not "who owns that row":

| Table | Column | Risk if only a foreign key exists | Mechanism |
|---|---|---|---|
| `bookmarks` | `tip_id`, `insight_id` | A student bookmarks — and thereby reads — another student's tip/insight | `trg_bookmarks_before_insert/update` compares `user_tips.user_id` / `insights.user_id` with `NEW.user_id` |
| `transactions` | `ai_suggested_category_id` | The AI suggestion points at another student's personal category, exposing that category's name | `sp_validate_transaction` takes an extra parameter, allowing `NULL` / a default category / the student's own category |
| `transactions` | `recurring_rule_id`, `import_batch_id` | Student A's transaction points at student B's recurring rule or CSV import batch, exposing B's data through that link | These two columns **deliberately have no foreign key** (only the indexes `ix_txn_recurring`, `ix_txn_batch`); `sp_validate_transaction` reads `recurring_rules.user_id` / `import_batches.user_id` and refuses if the owner differs or the row does not exist |
| `import_rows` | `ai_suggested_category_id` | The AI suggestion points at another student's personal category, exposing that category's name | **There is no guard at the SQL layer yet** — this is the remaining gap, stated explicitly so it is not mistaken for protected. This column is written only by the application during the preview step (UC-11 B5); no database procedure, trigger or view reads it back, so the impact is at the application layer. The column that determines a transaction's actual category is `resolved_category_id`, and `sp_apply_csv_batch` **does** check ownership when it writes the transaction (row above) — that is the real catch-point of the CSV import flow. To block it at the data layer too, a `BEFORE INSERT/UPDATE` trigger on `import_rows` would be needed, following the pattern of `trg_bookmarks_before_insert/update`; this has **not been done** |
| `recent_activity` | `transaction_id` | Student A could record activity history for student B's transaction | `sp_touch_recent_activity` looks up `transactions.user_id` before writing, and refuses if the owner differs |

**Why `recurring_rule_id` and `import_batch_id` have no foreign key:** if an `ON DELETE CASCADE` foreign key were added, deleting a CSV import batch would silently delete the transactions generated from that batch — contrary to BR-09 (preserve the history). If `ON DELETE SET NULL` were added, the column would lose the trace of its origin. So the two columns are only indexed, and correctness is guaranteed by `sp_validate_transaction`.

**Why `import_rows` has no `user_id` column:** the owner of a CSV import row is always derived from `import_batches.user_id`. Adding a duplicate `user_id` to `import_rows` would create a second source of truth that could **drift** from the batch containing it, with nothing guaranteeing the two values always match. `sp_apply_csv_batch` therefore reads the owner from the batch and uses it for every transaction it generates.

### 4.9 The `type` field in the change log

`transaction_history` still records the `type` field in `old_values` / `new_values`, even though the `transactions` table no longer has this column. This is a **snapshot** of the category's type at the moment the transaction occurred.

The reason: BR-09 requires change history to be preserved. If the log recorded only `categoryId`, then when a category is renamed, or an old report is exported, the historical content would change accordingly — no longer "history". A snapshot keeps the historical record always reflecting the state at the moment it was created.

A history record captures all 16 business fields: `categoryId`, `type`, `amount`, `description`, `txnDate`, `source`, `aiSuggestedCategoryId`, `aiConfidence`, `aiOverridden`, `recurringRuleId`, `importBatchId`, `isFlagged`, `flagType`, `flagNote`, `isDeleted`, `deletedAt`.

> **Since application-level field encryption:** the `description` inside the snapshot is now that
> column's **ciphertext** — the trigger copies the column value, and `transactions.description` holds
> an envelope. The other fifteen fields are unchanged. See §4.12 and `docs/SECURITY.md` §12.4.

### 4.10 The password reset token is consumed in a single statement

BR-04 requires the token to be usable **once only**. A separate check would be unsafe: if the procedure read the token to check it ("still valid, not used") and then wrote the used state in a later step, two requests sending the same token at the same time could both pass the check before either wrote.

`sp_complete_password_reset` therefore combines both operations into **a single `UPDATE` statement**:

```sql
UPDATE password_reset_tokens
   SET used_at = NOW()
 WHERE token_hash = ?
   AND used_at IS NULL
   AND expires_at > NOW();
```

The "not used" and "still valid" conditions are in the `WHERE` clause itself, so InnoDB locks the row for that token. The second request queues; when it runs, it evaluates the `WHERE` against the **new** row version (which now has `used_at`), no longer matches, and `ROW_COUNT()` returns 0 — the procedure `SIGNAL`s the error *"BR-04: reset token is invalid, already used, or expired"*. Because `token_hash` carries a UNIQUE key, the subsequent re-read of `user_id` is exact.

> **Design decision:** this is a technical detail for satisfying BR-04, not a new requirement. The SRS says nothing about concurrency handling.

### 4.11 Weekly spending grouping must use `YEARWEEK`, not `YEAR` + `WEEK`

VĐ-10 says the week starts on Monday, i.e. an **ISO** week. The seemingly correct way is `YEAR(txn_date)` for the year and `WEEK(txn_date, 3)` for the week — but these two functions do not share the same frame of reference:

- `YEAR()` returns the **calendar year**.
- `WEEK(..., 3)` returns the **ISO week**, and that week may belong to a **different ISO year**.

Consequence: 2026-12-31 and 2027-01-01 both fall in ISO week 2026-W53. `YEAR()` gives two values, 2026 and 2027, so two rows in the **same week** get pushed into two different groups, and that week is split in two in the report.

`YEARWEEK(date, 3)` returns both parts in a **single** value, in the form `ISO year * 100 + ISO week`, so the two parts cannot drift apart. Split them back with `DIV 100` and `MOD 100`:

```sql
CAST(YEARWEEK(t.txn_date, 3) DIV 100 AS UNSIGNED) AS iso_year,
CAST(YEARWEEK(t.txn_date, 3) MOD 100 AS UNSIGNED) AS iso_week
```

**Why a derived table is required:** grouping directly by `YEARWEEK(...)` and then projecting `YEARWEEK(...) DIV 100` would be rejected by MySQL under `only_full_group_by` (the default mode) with error 1055, because the projected expression is not considered functionally dependent on the expression in the `GROUP BY`. `v_weekly_spending_current_month` therefore computes `iso_year`, `iso_week`, `week_start` for **each row** in a derived table, and then `GROUP BY`s in the outer query on the plain columns.

`week_start` is the actual Monday of that ISO week (`WEEKDAY()` = 0 on Monday), and it **can fall outside the month under consideration** — this is correct: the reporting unit is the ISO week, and a month boundary must not be allowed to split a week in two.

> **Verification note:** this bug (1055) only shows up when the view is **queried**, not when it is **created** — MySQL accepts the definition and only refuses when the data is read. The verification step must therefore `SELECT` from each view, not just count whether they exist.

### 4.12 Three free-text columns hold application-level ciphertext

Three columns no longer hold what was typed into them. `transactions.description`,
`recurring_rules.description` and `bookmarks.note` hold a **Base64 AES-256-GCM envelope** written by
the application, and the database cannot read them. Full account in `docs/SECURITY.md` §12; what
matters at the schema level:

```text
envelope = format(1) || keyVersion(1) || iv(12) || ciphertext+tag(n), then Base64
```

All three columns are therefore `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin`:

- **`VARCHAR`, not `VARBINARY`**, because the envelope is Base64 and therefore pure ASCII. A
  character column maps directly onto the entity's `String` field, which keeps `ddl-auto=validate`
  meaningful; a binary column would have to be mapped as `byte[]` and encoded in Java at every
  boundary for no benefit.
- **`ascii_bin`, not the table default**, because Base64 is case-sensitive. Under a
  case-insensitive collation two envelopes differing only in case would compare equal, which would
  break the trigger's `OLD.description <=> NEW.description` changed-field check and make a real
  edit look like no change.
- **2048 characters** is the envelope's worst case for a 255-character note: 2 bytes of header,
  12 of IV, 16 of tag and up to 4 Base64 characters per input byte, rounded up. Sized to the
  column, not to the plaintext. The entity still declares `length = 255`, which describes the
  plaintext the column accepts.

**The audit trail follows automatically, and the trigger was not changed.** `trg_transactions_after_insert`
and `trg_transactions_after_update` copy `NEW.description` into `JSON_OBJECT(...)` for the
`transaction_history` snapshot. They now copy the column's ciphertext. That is a consequence of the
column already holding an envelope at the moment the trigger fires, not a decision the trigger
makes — and it is the desired outcome, because a trigger cannot encrypt (that would need the key
inside MySQL) and BR-09 must keep the snapshot atomic with the change. `transaction_history` is an
audit table with no HTTP read path; a direct `SELECT` on it shows the same ciphertext the
transaction row holds.

One procedure had to change. `sp_post_recurring_transactions` copies a rule's description into the
posted transaction through a local variable that was `VARCHAR(255)`; an envelope needs up to 2048
characters, so at the old width the value was truncated on fetch and **every** run failed with
`Data too long for column 'v_desc'`. It is now `VARCHAR(2048)` and the envelope is copied through
unchanged. See OB-014.

**What did not change, and why.** No amount column was encrypted. MySQL has no AES-GCM and cannot be
given the key, and twelve of the fourteen views read an amount — most by joining
`v_monthly_income_expense` or `v_category_month_totals` — as do five procedures. An encrypted amount
could not be summed, compared or ordered, so that work means moving the reporting tier into Java
first; it is recorded as OB-013. `insights` and `import_rows` likewise stay in plaintext because
their only writers are procedures belonging to the locked module 12 (OB-012).

---

## 5. Verification

The database was actually built on MySQL 8.4, and the key rules were verified using the very scenarios in the documents.

Verification method: load `db/merged/campuscoin_full.sql` into an empty MySQL 8 database, generated from **the merged file itself** (not from the six separate files, so the merged file is tested too), then run a set of **54 checks**: fixed figures (UAT-07, BR-15, BR-17), behaviour (each check runs one statement and compares against the expected result — the "must be refused" checks and the "must succeed" checks), and every view must **run**, not merely exist. Result: **54/54 passed**. Two later fixes (dynamic tip thresholds, and handling of a CSV category choice that is no longer valid) were verified with two separate tests in the last two rows of the table below; both passed, and the 54-check set still passes unchanged after the fixes.

| Rule / UAT | Verification method | Result |
|---|---|---|
| BR-12, UAT-07 | Budget Food 30; spend 24 → 80% | Exactly **one** `NEAR` alert: Food, 80.00%, spend 24.00 / limit 30.00 |
| BR-16 | Call `sp_post_recurring_transactions(DATE_ADD(first day of this month, INTERVAL 1 MONTH))` twice in a row | Run 1 generates **2** transactions (exactly the number of `ACTIVE` recurring templates in `06_demo.sql`); run 2 generates **nothing more** — the key `uk_occurrence_rule_period (rule_id, period_key)` blocks it at the data layer |
| BR-09, UAT-06 | Edit the amount and then soft-delete a transaction | The log records `CREATE` → `UPDATE` → `DELETE` in full; the deleted transaction no longer appears in the report views |
| BR-09 | `DELETE FROM transactions WHERE id = ...` | Blocked by the trigger: *"BR-09: transactions cannot be hard-deleted; use soft delete instead"* |
| BR-05 | Assign the `Food` category (expense) to a transaction of type `INCOME` | No longer possible: `transactions` has no `type` column, the type always comes from the category |
| BR-08 | Record a transaction with `txn_date` set to tomorrow | Blocked: *"BR-08: transaction date cannot be in the future"* |
| BR-02 | Student B uses student A's personal category | Blocked: *"BR-02: category belongs to another student"* |
| BR-02 | Student B bookmarks student A's tip | Blocked: *"BR-02: you can only bookmark your own tips"* |
| BR-02 | Student B records activity history for student A's transaction | Blocked: *"BR-02: cannot record activity for a transaction owned by another student"* |
| BR-02 | Student B's transaction points at A's `recurring_rule_id` / `import_batch_id` | Blocked: *"BR-02: recurring rule belongs to another student"* / *"BR-02: import batch belongs to another student"*; pointing at a non-existent id is blocked too |
| BR-13 | Student B's transaction points `ai_suggested_category_id` at A's personal category | Blocked: *"BR-13: suggested category belongs to another student"* |
| BR-11 | Set two budget limits for the same category, same month | Blocked by `uk_budget_user_cat_month` |
| BR-11 | Set a budget limit on an income category | Blocked: *"BR-11: a budget may only be set on an expense category"* |
| BR-04 | Reuse a password reset token a second time | Refused: *"BR-04: reset token is invalid, already used, or expired"*; an expired token is refused too; validity is exactly 30 minutes |
| BR-04 | Reset a password while the account has an open session | Every session is revoked: the number of valid sessions = 0 |
| BR-03 | An administrator disables an account | The open session is revoked, `token_version` increases, the administrative log records it |
| BR-15 | Entertainment spends 25 against a 3-month average of 11 | `pct_change` = 127.27%, `is_spike` = 1; exactly **one** category is flagged in the month |
| BR-15 | A category spends 300 in the first month, 0 in the next two (e.g. 300/0/0) | `baseline_avg_spend` = **100**, not 300 — an empty month counts as 0 |
| BR-17, UAT-09 | View the 6-month report of a student with no data | Returns all 6 rows; both empty months are 0; the current month has income 260.00 / expense 189.00 / remaining 71.00 |
| BR-14 | Three tips on the dashboard, ordered by potential saving | Exactly 3 tips; rank 1 *"Your savings goal is at risk"*, rank 2 *"Entertainment spending is up 127.3%"* |
| UC-13 | Budget consumption for the current month | 5 rows; Food `NEAR` 80.00%, the other 4 rows `ON_TRACK` |
| UC-15, VĐ-10 | Group spending by ISO week within the month | 2 weeks (ISO 2026-W36, 2026-W37); the total 128.00 + 61.00 = 189.00 matches the month's total expense exactly |
| ISO week/year | The two days 2026-12-31 and 2027-01-01 (the same ISO week) | `YEARWEEK(...,3)` gives the same value `202653`; the old `YEAR()` + `WEEK()` approach would split them into two groups |
| UC-09, BR-05 | Create a recurring rule with the wrong type / using another student's category / a deactivated category | Blocked at the moment the rule is created, without waiting for the scheduler to run |
| UC-20, BR-06 | Change the `type` of a category that is already referenced | Blocked: *"A category referenced by transactions, budgets or recurring rules cannot change its type; create a new category instead"* |
| UC-20, BR-06 | A category **not** referenced changes its `type` | Allowed; and blocked as soon as a budget points at it, allowed again after that budget is deleted |
| BR-06, UC-20 | A student calls `sp_admin_upsert_default_category` | Blocked: *"BR-06: administrator privileges required (role ADMIN and status ACTIVE)"* |
| BR-06, UC-20 | An `ACTIVE` administrator calls the same procedure | Succeeds, writes `admin_audit_log` |
| BR-06, UC-20 | A `DISABLED` administrator calls the same procedure | Blocked — the gate requires **both** `role = 'ADMIN'` **and** `status = 'ACTIVE'` |
| UC-22 | Search every procedure and trigger for the string `cc_is_admin` | No occurrence remains |
| UC-11 | CSV import: row ownership comes from the batch, not the row | Every generated transaction belongs to the batch's owner; `import_rows` has no `user_id` column |
| UC-11 | CSV import: the student changes a category in the preview step, the choice is still valid | The chosen category is kept as-is when writing; it is **not** resolved again by the name in the file |
| UC-11 | CSV import: the student chose a category but the choice is **no longer** valid at write time (deactivated, or another student's) | The row is marked `ERROR`, *"The category chosen for this row is no longer available"*; **no** transaction is generated and it is **not** silently replaced by another category. Later rows are still processed |
| BR-12, VĐ-05 | Does the budget tip read thresholds from `system_settings` | `sp_generate_tips` reads `budget.near_threshold_pct` / `budget.exceeded_threshold_pct`, no `80`/`100` constants are left in the code. Set near = 70: spend 69% → no tip, 70% → `NEAR_BUDGET`, 99% → `NEAR_BUDGET`, 100% → `OVER_BUDGET`. A missing key or an unusable value (text, `0`, negative) falls back to the default 80/100 |
| UC-24 / B-D | Record a transaction with a negative amount | Blocked by `ck_txn_amount` |
| VĐ-05 | An administrator sets a bad threshold (`-5`) or edits a key outside the allowed list | Blocked: *"Threshold must be a positive number"* / *"This configuration key cannot be changed through this procedure"* |
| All content | Scan every text column of all 22 tables (the `budgets` table has no text columns) for non-ASCII characters | No value remains — the entire system content is in English |

---

## 6. Operational notes

**Reloading the database:** MySQL only runs the files in `/docker-entrypoint-initdb.d` on the first initialisation, while the volume is empty. Here exactly one file is mounted — `db/merged/campuscoin_full.sql` under the name `01-campuscoin.sql`. Later `up -d`, `stop`/`start`, `restart` runs do not run it again, even if the `.sql` file has been edited. To reload: `docker compose down -v && docker compose up -d`.

**Time zone:** the server is started with `--default-time-zone=+07:00` and each SQL file sets `SET time_zone = '+07:00'`, ensuring day, week and month boundaries stay consistent (VĐ-10). The application layer must also set the same time zone when it opens a connection.

**Scheduler:** MySQL is started with `--event-scheduler=ON`. Recurring transactions (UC-09) are currently generated by calling `sp_post_recurring_transactions()`. To let the database run this daily on its own, add an `EVENT` that calls this procedure; alternatively, have the application layer call it on a schedule.

**Data safety:** the `root` account is used only during development. The application should connect as `campuscoin_app`, an account with privileges on the `campuscoin` database only.

**Why this matters more than before:** after removing the `@cc_is_admin` session variable (§4.4), administrator privileges are decided **only** inside `sp_require_admin`. That means an `UPDATE`/`INSERT`/`DELETE` statement run directly against a table will not pass through that gate. Two consequences to handle at the deployment layer:

1. **Do not grant direct table write privileges to the application account.** The application account should have only `EXECUTE` on the procedures, plus `SELECT` on the views it needs to read; every write path goes through a procedure. Then there is no side door left to bypass the administrative gate.
2. **The account used by a database administration tool must be a separate, controlled account.** That account will inevitably be able to write directly (that is its purpose), so it must be issued separately to the system administrator and not shared with the application account.

Rules that **do not depend on identity** are still protected by triggers even against hand-run statements (BR-05, BR-07, BR-09, BR-11, BR-13, and the writing of `transaction_history`).
