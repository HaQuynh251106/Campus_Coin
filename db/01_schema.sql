-- ============================================================================
--  CAMPUS COIN — 01_schema.sql
--  MySQL 8.0+  |  utf8mb4_0900_ai_ci  |  InnoDB
--
--  Design sources:
--    [SRS]  Campus Coin — Software Requirements Specification v1.0 (§1.6, §1.8)
--    [UC]   Use Case & Business Flow Specification v1.0
--           (UC-01..UC-27, BR-01..BR-18, VĐ-01..VĐ-13, UAT-01..UAT-17)
--
--  This file contains the DATABASE and TABLES only. Run order:
--    01_schema.sql -> 02_views.sql -> 03_procedures.sql -> 04_triggers.sql
--    -> 05_seed.sql -> 06_demo.sql (optional)
-- ============================================================================

DROP DATABASE IF EXISTS campuscoin;
CREATE DATABASE campuscoin
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE campuscoin;

SET NAMES utf8mb4;
-- VĐ-10: pin the system time zone. The server is also started with
-- --default-time-zone=+07:00 in docker-compose.yml so that every connection
-- shares one time basis.
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 1;


-- ============================================================================
--  GROUP 1 — ACCOUNTS & SECURITY
-- ============================================================================

-- ---------------------------------------------------------------------------
-- users — UC-04 (profile), UC-05 (sign in), UC-22 (admin user management),
-- UC-27 (display preferences)
-- Students and administrators share one table, distinguished by `role`.
-- UC-05 describes two separate sign-in screens, but that is a front-end routing
-- concern; at the data layer both user types carry the same attributes.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
  id                         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email                      VARCHAR(190)    NOT NULL,
  password_hash              VARCHAR(100)    NOT NULL,   -- BR-01: bcrypt hash only
  full_name                  VARCHAR(120)    NOT NULL,
  role                       ENUM('STUDENT','ADMIN') NOT NULL DEFAULT 'STUDENT',
  academic_year              VARCHAR(30)     NULL,       -- UC-04, e.g. 'Year 3'
  monthly_allowance_baseline DECIMAL(15,2)   NOT NULL DEFAULT 0.00,  -- VĐ-04
  monthly_savings_goal       DECIMAL(15,2)   NOT NULL DEFAULT 0.00,  -- VĐ-04
  currency                   CHAR(3)         NOT NULL DEFAULT 'USD',
  status                     ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  theme_pref                 ENUM('LIGHT','DARK','SYSTEM') NOT NULL DEFAULT 'SYSTEM',      -- UC-27
  font_scale                 ENUM('SMALL','MEDIUM','LARGE','XLARGE') NOT NULL DEFAULT 'MEDIUM',
  ai_enabled                 TINYINT(1)      NOT NULL DEFAULT 1,     -- toggles UC-08, UC-17
  email_verified_at          DATETIME        NULL,
  last_login_at              DATETIME        NULL,       -- UC-23 "active in the last 30 days"
  token_version              INT UNSIGNED    NOT NULL DEFAULT 0,     -- BR-03: bump to revoke old JWTs
  created_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  KEY ix_users_role_status (role, status),
  CONSTRAINT ck_users_money CHECK (monthly_allowance_baseline >= 0
                               AND monthly_savings_goal      >= 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- user_sessions — BR-03: disabling an account revokes its open sessions
-- ---------------------------------------------------------------------------
CREATE TABLE user_sessions (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id            BIGINT UNSIGNED NOT NULL,
  session_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ip_address         VARCHAR(45)     NULL,
  user_agent         VARCHAR(255)    NULL,
  issued_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at       DATETIME        NULL,
  expires_at         DATETIME        NOT NULL,
  revoked_at         DATETIME        NULL,
  revoked_reason     ENUM('LOGOUT','LOGOUT_ALL','ADMIN_DISABLE',
                          'PASSWORD_RESET','EXPIRED','REPLACED') NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sessions_token (session_token_hash),
  KEY ix_sessions_user_active (user_id, revoked_at, expires_at),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- password_reset_tokens — BR-04: single-use token, expires after 30 minutes.
-- The SHA-256 digest is stored, never the raw token: a database leak does not
-- let an attacker take over an account.
-- ---------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  token_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  requested_ip VARCHAR(45)     NULL,
  expires_at   DATETIME        NOT NULL,
  used_at      DATETIME        NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_prt_token (token_hash),
  KEY ix_prt_user_active (user_id, used_at, expires_at),
  CONSTRAINT fk_prt_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 2 — CONFIGURATION & TIME DIMENSION
-- ============================================================================

-- ---------------------------------------------------------------------------
-- system_settings — VĐ-05 (thresholds), VĐ-08 (currency), VĐ-10 (time zone and
-- week start), VĐ-12 (what may be sent to the external AI service).
-- Every business threshold lives here so an administrator can retune the system
-- without editing source code or restarting.
-- ---------------------------------------------------------------------------
CREATE TABLE system_settings (
  setting_key   VARCHAR(60)  NOT NULL,
  setting_value VARCHAR(255) NULL,
  value_type    ENUM('STRING','INT','DECIMAL','BOOLEAN','JSON') NOT NULL DEFAULT 'STRING',
  description   VARCHAR(255) NULL,
  updated_by    BIGINT UNSIGNED NULL,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (setting_key),
  CONSTRAINT fk_settings_updated_by FOREIGN KEY (updated_by)
    REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- dim_month — BR-17: the six-month report must always return six columns, with
-- months that have no data shown as 0. A plain GROUP BY cannot produce missing
-- months, so a dimension table is needed for the LEFT JOIN.
-- ---------------------------------------------------------------------------
CREATE TABLE dim_month (
  month_start DATE              NOT NULL,
  month_end   DATE              NOT NULL,
  year_no     SMALLINT UNSIGNED NOT NULL,
  month_no    TINYINT UNSIGNED  NOT NULL,
  label_short CHAR(7)           NOT NULL,   -- '2026-09'
  PRIMARY KEY (month_start),
  UNIQUE KEY uk_dim_month_label (label_short)
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 3 — CATEGORIES
-- ============================================================================

-- ---------------------------------------------------------------------------
-- categories — UC-06 (student-managed personal categories) + UC-20 (admin-managed
-- default categories)
--   user_id IS NULL  = system-wide default category (shared by everyone)
--   user_id NOT NULL = personal category owned by one student
--
-- scope_key is a generated column that lets one UNIQUE key express both
-- "default categories must not collide" and "a student's personal categories
-- must not collide" — MySQL has no partial index, so this is the equivalent.
--
-- VIRTUAL is mandatory here (not STORED): MySQL 8 refuses to create a foreign
-- key with ON DELETE CASCADE on a table holding a STORED generated column
-- (error 1215 "Cannot add foreign key constraint"). A VIRTUAL generated column
-- can still be indexed, so the UNIQUE key keeps its full effect.
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NULL,
  name        VARCHAR(80)     NOT NULL,
  type        ENUM('INCOME','EXPENSE') NOT NULL,
  icon        VARCHAR(50)     NULL,
  color       CHAR(7)         NULL,
  description VARCHAR(255)    NULL,
  sort_order  SMALLINT        NOT NULL DEFAULT 0,
  is_active   TINYINT(1)      NOT NULL DEFAULT 1,   -- BR-07: retiring = hide, not delete
  scope_key   BIGINT UNSIGNED GENERATED ALWAYS AS (IFNULL(user_id, 0)) VIRTUAL,
  created_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_categories_scope_type_name (scope_key, type, name),  -- BR-06
  KEY ix_categories_user_type_active (user_id, type, is_active, sort_order),
  CONSTRAINT fk_categories_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by)
    REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 4 — TRANSACTIONS
-- ============================================================================

-- ---------------------------------------------------------------------------
-- transactions — UC-07 (create), UC-10 (edit/delete), UC-11 (CSV import),
-- UC-24 (anomaly flagging)
--
-- There is NO `type` column. A transaction's type IS the type of the category it
-- points at, so exactly one source of truth exists and BR-05 ("transaction type
-- must match category type") cannot be violated. The sample TRANSACTION table in
-- SRS §1.6 has no such column either.
--
-- Necessary consequence: changing `categories.type` on a category that has
-- already produced data would silently rewrite the entire reporting history, so
-- that operation is blocked by trg_categories_before_update (see 04_triggers.sql).
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id                  BIGINT UNSIGNED NOT NULL,
  category_id              BIGINT UNSIGNED NOT NULL,
  -- NOT encrypted, deliberately. MySQL cannot decrypt, so an encrypted amount
  -- could not be SUM()-ed, compared or ordered by any view or procedure, and the
  -- whole reporting tier (M6 consumption, M7 dashboard, M8 reports, M9 tip rules,
  -- M11 admin stats) would have to be rebuilt in the application. That is a
  -- separate, separately-approved project - see OB-013 in docs/OVERNIGHT_BLOCKERS.md.
  -- `amount` therefore remains the one sensitive transaction field MySQL itself
  -- must read. ck_txn_amount below still enforces BR-08.
  amount                   DECIMAL(15,2)   NOT NULL,          -- BR-08: > 0
  -- ENCRYPTED. Holds a Base64 AES-256-GCM envelope:
  --   format(1) || keyVersion(1) || iv(12) || ciphertext+tag
  -- Unlike amount, description has no SQL logic on it anywhere - no WHERE, no
  -- SUM, no ORDER BY - so encrypting it breaks nothing. See docs/SECURITY.md.
  --
  -- VARCHAR rather than VARBINARY because the envelope is Base64 and therefore
  -- pure ASCII: a character column maps straight onto the entity's String field,
  -- which keeps `ddl-auto=validate` meaningful, and lets the history triggers copy
  -- the value into their JSON snapshot as an ordinary string instead of MySQL's
  -- opaque `base64:typeNN:` binary encoding.
  --
  -- ascii_bin, not the table default: Base64 is case-sensitive and has no notion
  -- of collation. Nothing compares this column, but a case-insensitive collation
  -- on ciphertext is a trap for whoever adds the first comparison.
  --
  -- 2048 chars: the plaintext is at most 255 characters, each up to 4 bytes in
  -- UTF-8, which Base64 expands to about 1360 characters, plus the envelope.
  description              VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NULL,
  txn_date                 DATE            NOT NULL,          -- BR-08: not in the future
  source                   ENUM('MANUAL','CSV','RECURRING') NOT NULL DEFAULT 'MANUAL',
  -- BR-13: an AI suggestion may only point at a default category or at a
  -- category owned by the same student; a trigger enforces this because a
  -- foreign key cannot express "either of these two".
  ai_suggested_category_id BIGINT UNSIGNED NULL,
  ai_confidence            DECIMAL(5,4)    NULL,
  ai_overridden            TINYINT(1)      NOT NULL DEFAULT 0,-- UC-08 B6: learn from overrides
  recurring_rule_id        BIGINT UNSIGNED NULL,
  import_batch_id          BIGINT UNSIGNED NULL,
  is_flagged               TINYINT(1)      NOT NULL DEFAULT 0,-- UC-24
  flag_type                ENUM('NONE','DUPLICATE','UNUSUAL_AMOUNT') NOT NULL DEFAULT 'NONE',
  flag_note                VARCHAR(255)    NULL,
  is_deleted               TINYINT(1)      NOT NULL DEFAULT 0,-- BR-09: soft delete
  deleted_at               DATETIME        NULL,
  created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_txn_user_date      (user_id, txn_date, is_deleted),
  KEY ix_txn_user_cat_date  (user_id, category_id, txn_date, is_deleted),
  KEY ix_txn_category_date  (category_id, txn_date, is_deleted),
  KEY ix_txn_recurring      (recurring_rule_id),
  KEY ix_txn_batch          (import_batch_id),
  KEY ix_txn_flagged        (user_id, is_flagged),
  CONSTRAINT fk_txn_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  -- ON DELETE RESTRICT = BR-07 enforced by the database: a category that still
  -- has transactions cannot be hard-deleted.
  CONSTRAINT fk_txn_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT fk_txn_ai_category FOREIGN KEY (ai_suggested_category_id)
    REFERENCES categories(id) ON DELETE SET NULL,
  CONSTRAINT ck_txn_amount  CHECK (amount > 0),
  CONSTRAINT ck_txn_ai_conf CHECK (ai_confidence IS NULL
                                OR (ai_confidence >= 0 AND ai_confidence <= 1)),
  CONSTRAINT ck_txn_deleted CHECK ((is_deleted = 0 AND deleted_at IS NULL)
                                OR (is_deleted = 1 AND deleted_at IS NOT NULL))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- transaction_history — BR-09, VĐ-09: "preserve history" of changes.
-- old_values / new_values are stored as JSON so that adding a column to
-- `transactions` later does not require changing this table.
--
-- KNOWN PLAINTEXT — RESIDUAL EXPOSURE, DELIBERATE.
-- The snapshots contain `amount`, which is the field this project could not
-- encrypt without rebuilding the whole reporting tier (see `transactions.amount`
-- above and OB-013). Encrypting the snapshots while `amount` itself stays readable
-- would protect nothing extra, so history is left as it is and both are deferred
-- to the same piece of work. Consequence, stated plainly: a direct SELECT on this
-- table still reveals every amount and description a transaction ever held.
--
-- When that work happens, the snapshot moves to an encrypted MEDIUMBLOB written by
-- TransactionService, because a MySQL trigger cannot encrypt - it would need the
-- key, and the key must never reach MySQL.
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_history (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  transaction_id BIGINT UNSIGNED NOT NULL,
  action         ENUM('CREATE','UPDATE','DELETE','RESTORE') NOT NULL,
  changed_by     BIGINT UNSIGNED NULL,
  changed_fields VARCHAR(500)    NULL,
  old_values     JSON            NULL,
  new_values     JSON            NULL,
  changed_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_hist_txn (transaction_id, changed_at),
  KEY ix_hist_changed_by (changed_by),
  CONSTRAINT fk_hist_txn FOREIGN KEY (transaction_id)
    REFERENCES transactions(id) ON DELETE CASCADE,
  CONSTRAINT fk_hist_user FOREIGN KEY (changed_by)
    REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- recurring_rules — UC-09: recurring transaction templates.
--
-- `type` is KEPT here (unlike in `transactions`): a rule can be created for a
-- category that has no transaction yet, and the BR-05 check must run at the
-- moment the rule is created rather than when the scheduler posts it. Triggers
-- trg_recurring_rules_before_insert/update do that: the category must exist,
-- must be a default category or one owned by the same student, must be active,
-- and `type` must match `categories.type`.
-- ---------------------------------------------------------------------------
CREATE TABLE recurring_rules (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  category_id    BIGINT UNSIGNED NOT NULL,
  type           ENUM('INCOME','EXPENSE') NOT NULL,
  -- NOT encrypted, for the same reason as transactions.amount: the scheduler
  -- procedure sp_post_recurring_transactions SELECTs it directly, and a rule's
  -- amount is the source of the transaction it posts. See OB-013.
  amount         DECIMAL(15,2)   NOT NULL,
  -- ENCRYPTED, same Base64 envelope and the same VARCHAR/ascii_bin reasoning as
  -- transactions.description.
  --
  -- LIMITATION, and it is real: sp_post_recurring_transactions copies this column
  -- into the transaction it posts (see 03_procedures.sql), and a MySQL procedure
  -- cannot decrypt. Transactions posted by the scheduler therefore carry the rule's
  -- ciphertext into a column that is meant to hold the ciphertext of the
  -- transaction's own plaintext. TransactionService repairs this when it reads such
  -- a row, by recognising the envelope and decrypting it exactly once; the
  -- limitation and the repair are recorded as OB-014.
  description    VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NULL,
  frequency      ENUM('DAILY','WEEKLY','MONTHLY','QUARTERLY','YEARLY') NOT NULL,
  interval_count SMALLINT UNSIGNED NOT NULL DEFAULT 1,   -- "every 2 weeks" => 2
  day_of_month   TINYINT UNSIGNED NULL,                   -- hint for the UI
  day_of_week    TINYINT UNSIGNED NULL,                   -- 1 = Monday (VĐ-10)
  start_date     DATE            NOT NULL,
  end_date       DATE            NULL,
  next_run_date  DATE            NOT NULL,
  last_run_date  DATE            NULL,
  status         ENUM('ACTIVE','PAUSED','ENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_recurring_due (status, next_run_date),
  KEY ix_recurring_user (user_id, status),
  CONSTRAINT fk_recurring_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_recurring_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT ck_recurring_amount   CHECK (amount > 0),
  CONSTRAINT ck_recurring_interval CHECK (interval_count >= 1),
  CONSTRAINT ck_recurring_dates    CHECK (end_date IS NULL OR end_date >= start_date)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- recurring_occurrences — BR-16: even when the scheduler has to catch up over
-- many periods, each PERIOD may post exactly ONE transaction.
-- UNIQUE(rule_id, period_key) is that guarantee.
-- ---------------------------------------------------------------------------
CREATE TABLE recurring_occurrences (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_id        BIGINT UNSIGNED NOT NULL,
  period_key     VARCHAR(12)     NOT NULL,  -- '2026-09-24' | '2026-W38' | '2026-09' | '2026-Q3' | '2026'
  scheduled_date DATE            NOT NULL,
  transaction_id BIGINT UNSIGNED NULL,
  status         ENUM('POSTED','SKIPPED','FAILED') NOT NULL DEFAULT 'POSTED',
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_occurrence_rule_period (rule_id, period_key),   -- BR-16
  KEY ix_occurrence_txn (transaction_id),
  CONSTRAINT fk_occurrence_rule FOREIGN KEY (rule_id)
    REFERENCES recurring_rules(id) ON DELETE CASCADE,
  CONSTRAINT fk_occurrence_txn FOREIGN KEY (transaction_id)
    REFERENCES transactions(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- category_rules — UC-08: "learn from corrections" implemented as a per-student
-- keyword-to-category mapping (the BA note explicitly does not require training
-- a machine-learning model).
-- ---------------------------------------------------------------------------
CREATE TABLE category_rules (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  keyword      VARCHAR(80)     NOT NULL,   -- normalised: lower case, trimmed
  match_mode   ENUM('EXACT','CONTAINS') NOT NULL DEFAULT 'EXACT',
  category_id  BIGINT UNSIGNED NOT NULL,
  type         ENUM('INCOME','EXPENSE') NOT NULL,
  hit_count    INT UNSIGNED    NOT NULL DEFAULT 0,
  confidence   DECIMAL(5,4)    NOT NULL DEFAULT 1.0000,
  source       ENUM('ACCEPTED','OVERRIDE','IMPORT') NOT NULL DEFAULT 'ACCEPTED',
  last_used_at DATETIME        NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_user_keyword (user_id, keyword, match_mode),
  KEY ix_rule_lookup (user_id, keyword),
  CONSTRAINT fk_rule_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_rule_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE CASCADE,
  CONSTRAINT ck_rule_confidence CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 5 — BUDGETS & ALERTS
-- ============================================================================

-- ---------------------------------------------------------------------------
-- budgets — UC-13, BR-11: at most ONE limit per (student, expense category, month).
-- period_month always stores the first day of the month (matching the SRS sample
-- table's `month DATE` column).
-- ---------------------------------------------------------------------------
CREATE TABLE budgets (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  category_id  BIGINT UNSIGNED NOT NULL,
  period_month DATE            NOT NULL,
  limit_amount DECIMAL(15,2)   NOT NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_budget_user_cat_month (user_id, category_id, period_month),  -- BR-11
  KEY ix_budget_month (period_month),
  CONSTRAINT fk_budget_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_budget_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT ck_budget_limit CHECK (limit_amount > 0),
  CONSTRAINT ck_budget_month CHECK (DAYOFMONTH(period_month) = 1)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- budget_alert_log — BR-12: each threshold fires AT MOST ONCE per category per
-- month. UNIQUE(budget_id, threshold_type) is that guarantee, and it is what
-- makes UAT-07 (spend 24 of 30, then 7 more) raise exactly one NEAR alert and
-- one EXCEEDED alert.
-- ---------------------------------------------------------------------------
CREATE TABLE budget_alert_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  budget_id       BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  category_id     BIGINT UNSIGNED NOT NULL,
  threshold_type  ENUM('NEAR','EXCEEDED') NOT NULL,
  threshold_pct   DECIMAL(6,2)    NOT NULL,
  consumed_pct    DECIMAL(9,2)    NOT NULL,
  -- NOT encrypted: written by sp_check_budget_alerts, which needs the numbers to
  -- compute consumed_pct. Part of the same deferred work as transactions.amount
  -- (OB-013). A direct SELECT here reveals what a student spent in a month where
  -- an alert fired - residual exposure, recorded rather than silently ignored.
  spent_amount    DECIMAL(15,2)   NOT NULL,
  limit_amount    DECIMAL(15,2)   NOT NULL,
  notification_id BIGINT UNSIGNED NULL,
  triggered_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alert_budget_threshold (budget_id, threshold_type),  -- BR-12
  KEY ix_alert_user (user_id, triggered_at),
  CONSTRAINT fk_alert_budget FOREIGN KEY (budget_id)
    REFERENCES budgets(id) ON DELETE CASCADE,
  CONSTRAINT fk_alert_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_alert_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE CASCADE
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 6 — NOTIFICATIONS, INSIGHTS, TIPS, BOOKMARKS
-- ============================================================================

-- ---------------------------------------------------------------------------
-- notifications — UC-14 (budget alerts), UC-21 (administrator announcements)
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  type            ENUM('BUDGET_NEAR','BUDGET_EXCEEDED','ANNOUNCEMENT',
                       'SYSTEM','INSIGHT_READY','TIP','RECURRING_POSTED') NOT NULL,
  title           VARCHAR(150)    NOT NULL,
  body            TEXT            NULL,
  link_url        VARCHAR(255)    NULL,
  ref_entity_type VARCHAR(40)     NULL,
  ref_entity_id   BIGINT UNSIGNED NULL,
  is_read         TINYINT(1)      NOT NULL DEFAULT 0,
  read_at         DATETIME        NULL,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_notif_user_unread (user_id, is_read, created_at),
  CONSTRAINT fk_notif_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT ck_notif_read CHECK ((is_read = 0 AND read_at IS NULL)
                               OR (is_read = 1 AND read_at IS NOT NULL))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- announcements — UC-21: system-wide announcements with an active window
-- ---------------------------------------------------------------------------
CREATE TABLE announcements (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title      VARCHAR(150)    NOT NULL,
  body       TEXT            NOT NULL,
  severity   ENUM('INFO','WARNING','SUCCESS') NOT NULL DEFAULT 'INFO',
  audience   ENUM('ALL','STUDENTS','ADMINS') NOT NULL DEFAULT 'STUDENTS',
  starts_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ends_at    DATETIME        NULL,
  is_active  TINYINT(1)      NOT NULL DEFAULT 1,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_ann_active_window (is_active, starts_at, ends_at),
  CONSTRAINT fk_ann_created_by FOREIGN KEY (created_by)
    REFERENCES users(id) ON DELETE SET NULL,
  CONSTRAINT ck_ann_window CHECK (ends_at IS NULL OR ends_at > starts_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- insights — UC-17, BR-13, BR-15: one insight per month, kept for later viewing.
-- flagged_categories stores the list of unusual categories so it does not have
-- to be recomputed.
--
-- KNOWN PLAINTEXT — RESIDUAL EXPOSURE, DELIBERATE.
-- total_income / total_expense / net_amount / flagged_categories are aggregates
-- over transaction amounts, so they are exactly as sensitive as the encrypted
-- columns in `transactions`. They are NOT encrypted here, and the reason is
-- scope, not oversight:
--
--   - The only writer is sp_generate_monthly_insight, and the only reader would
--     be UC-17's API. UC-17 belongs to module 12, which is LOCKED pending the
--     project owner's approval.
--   - Encrypting these columns without rewriting that procedure would stop the
--     schema from loading at all (the procedure writes DECIMAL sums into what
--     would become VARBINARY).
--   - Rewriting the procedure to aggregate in the application is module 12 work,
--     and doing it here would be implementing a locked module by the back door.
--
-- Consequence, stated plainly: a direct SELECT on `insights` still reveals a
-- student's monthly income, expense and net totals. Recorded as OB-012 in
-- docs/OVERNIGHT_BLOCKERS.md, to be closed when UC-17 is approved and built.
-- ---------------------------------------------------------------------------
CREATE TABLE insights (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id            BIGINT UNSIGNED NOT NULL,
  period_month       DATE            NOT NULL,
  summary_text       TEXT            NULL,
  advice_text        TEXT            NULL,
  flagged_categories JSON            NULL,  -- [{categoryId,name,currentTotal,baselineAvg,pctChange}]
  total_income       DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  total_expense      DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  net_amount         DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  generated_by       ENUM('AI','RULE_BASED','MANUAL') NOT NULL DEFAULT 'RULE_BASED',
  model_name         VARCHAR(80)     NULL,
  status             ENUM('DRAFT','READY','FAILED') NOT NULL DEFAULT 'READY',
  error_message      VARCHAR(255)    NULL,
  generated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_insight_user_month (user_id, period_month),
  KEY ix_insight_user_generated (user_id, generated_at),
  CONSTRAINT fk_insight_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- tip_templates — UC-21: administrator-managed tip templates, content may
-- contain {..} placeholders substituted at generation time.
-- ---------------------------------------------------------------------------
CREATE TABLE tip_templates (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code             VARCHAR(50)     NOT NULL,
  condition_type   ENUM('OVER_BUDGET','NEAR_BUDGET','CATEGORY_SPIKE','NO_BUDGET_SET',
                        'SAVINGS_GOAL_AT_RISK','LOW_SAVINGS_RATE','GENERIC') NOT NULL,
  title_template   VARCHAR(200)    NOT NULL,
  body_template    TEXT            NOT NULL,
  condition_params JSON            NULL,
  default_priority SMALLINT        NOT NULL DEFAULT 100,
  is_active        TINYINT(1)      NOT NULL DEFAULT 1,
  created_by       BIGINT UNSIGNED NULL,
  created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tip_template_code (code),
  KEY ix_tip_template_active (is_active, condition_type),
  CONSTRAINT fk_tip_tpl_created_by FOREIGN KEY (created_by)
    REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- user_tips — UC-18, UC-19, BR-14: tips generated for one student together with
-- their pinned / dismissed state. dedupe_key guarantees a dismissed tip is never
-- generated again in the same period. potential_saving drives the BR-14 ranking.
-- dedupe_key is VIRTUAL (see the explanation on the categories table).
-- ---------------------------------------------------------------------------
CREATE TABLE user_tips (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id          BIGINT UNSIGNED NOT NULL,
  tip_template_id  BIGINT UNSIGNED NULL,
  period_month     DATE            NOT NULL,
  category_id      BIGINT UNSIGNED NULL,
  title            VARCHAR(200)    NOT NULL,
  body             TEXT            NOT NULL,
  potential_saving DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  rank_score       DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
  state            ENUM('NEW','PINNED','DISMISSED') NOT NULL DEFAULT 'NEW',
  pinned_at        DATETIME        NULL,
  dismissed_at     DATETIME        NULL,
  generated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  dedupe_key       VARCHAR(120) GENERATED ALWAYS AS (
                     CONCAT(user_id, '|', period_month, '|',
                            IFNULL(tip_template_id, 0), '|', IFNULL(category_id, 0))
                   ) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tip_dedupe (dedupe_key),
  KEY ix_tip_user_state_rank (user_id, period_month, state, rank_score),
  CONSTRAINT fk_tip_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_tip_template FOREIGN KEY (tip_template_id)
    REFERENCES tip_templates(id) ON DELETE SET NULL,
  CONSTRAINT fk_tip_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE CASCADE,
  CONSTRAINT ck_tip_state CHECK ((state = 'PINNED'    AND pinned_at    IS NOT NULL)
                              OR (state = 'DISMISSED' AND dismissed_at IS NOT NULL)
                              OR (state = 'NEW'       AND pinned_at IS NULL
                                                      AND dismissed_at IS NULL))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- bookmarks — UC-19, VĐ-02 (a short note is allowed).
-- VĐ-03: "pin" (user_tips.state = 'PINNED') is distinct from "bookmark" (this
-- table) — pinning controls display order, bookmarking saves an item for later.
-- dedupe_key is VIRTUAL (see the explanation on the categories table).
-- ---------------------------------------------------------------------------
-- `note` is ENCRYPTED - a student's own words about what they saved, and free
-- text has no SQL logic on it, so it is the same case as transactions.description:
-- the same Base64 envelope, the same VARCHAR/ascii_bin reasoning. Module 10 is
-- implemented against this column, so its entity maps String and encrypts at the
-- service boundary (see docs/SECURITY.md).
CREATE TABLE bookmarks (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  item_type  ENUM('TIP','INSIGHT') NOT NULL,
  tip_id     BIGINT UNSIGNED NULL,
  insight_id BIGINT UNSIGNED NULL,
  note       VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  dedupe_key VARCHAR(120) GENERATED ALWAYS AS (
               CONCAT(user_id, '|', item_type, '|',
                      IFNULL(tip_id, 0), '|', IFNULL(insight_id, 0))
             ) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bookmark_dedupe (dedupe_key),
  KEY ix_bookmark_user (user_id, created_at),
  CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_bookmark_tip FOREIGN KEY (tip_id)
    REFERENCES user_tips(id) ON DELETE CASCADE,
  CONSTRAINT fk_bookmark_insight FOREIGN KEY (insight_id)
    REFERENCES insights(id) ON DELETE CASCADE,
  CONSTRAINT ck_bookmark_target CHECK (
    (item_type = 'TIP'     AND tip_id     IS NOT NULL AND insight_id IS NULL) OR
    (item_type = 'INSIGHT' AND insight_id IS NOT NULL AND tip_id     IS NULL)
  )
) ENGINE=InnoDB;


-- ============================================================================
--  GROUP 7 — CSV IMPORT, RECENT ACTIVITY, ADMIN AUDIT
-- ============================================================================

-- ---------------------------------------------------------------------------
-- import_batches / import_rows — UC-11: upload -> preview -> confirm -> import.
-- Nothing is written to `transactions` until the student confirms (A2: cancelling
-- means nothing is imported).
-- ---------------------------------------------------------------------------
CREATE TABLE import_batches (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL,
  original_filename VARCHAR(255)    NOT NULL,
  file_hash         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status            ENUM('UPLOADED','PREVIEWED','COMMITTED','CANCELLED','FAILED')
                    NOT NULL DEFAULT 'UPLOADED',
  total_rows        INT UNSIGNED    NOT NULL DEFAULT 0,
  valid_rows        INT UNSIGNED    NOT NULL DEFAULT 0,
  error_rows        INT UNSIGNED    NOT NULL DEFAULT 0,
  duplicate_rows    INT UNSIGNED    NOT NULL DEFAULT 0,
  imported_rows     INT UNSIGNED    NOT NULL DEFAULT 0,
  error_report      JSON            NULL,
  created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  committed_at      DATETIME        NULL,
  PRIMARY KEY (id),
  KEY ix_batch_user_status (user_id, status, created_at),
  CONSTRAINT fk_batch_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- The owner of a row is ALWAYS derived from import_batches.user_id. There is
-- deliberately no user_id column here: a second copy of the owner could disagree
-- with the batch, and nothing would keep the two in step.
CREATE TABLE import_rows (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id                 BIGINT UNSIGNED NOT NULL,
  -- This column must NOT be named row_number: that is a reserved word in
  -- MySQL 8 (the ROW_NUMBER window function), and using it as a column name
  -- raises syntax error 1064.
  csv_row_no               INT UNSIGNED    NOT NULL,
  raw_data                 JSON            NULL,
  parsed_date              DATE            NULL,
  -- KNOWN PLAINTEXT — RESIDUAL EXPOSURE, DELIBERATE. Same reasoning as `insights`:
  -- parsed_amount and parsed_description are a CSV row's sensitive values, but the
  -- only writer is sp_apply_csv_batch and the surrounding feature is UC-11, which
  -- belongs to the LOCKED module 12. No Java code calls that procedure yet, so
  -- there is no read path to protect and rewriting it here would be implementing a
  -- locked module. Closed together with OB-012 when UC-11 is approved.
  parsed_amount            DECIMAL(15,2)   NULL,
  parsed_type              ENUM('INCOME','EXPENSE') NULL,
  parsed_description       VARCHAR(255)    NULL,   -- free text, any language
  parsed_category_name     VARCHAR(80)     NULL,
  -- Category selected or overridden by the student during the preview step
  -- (UC-11 B5/B6). When it is set, sp_apply_csv_batch uses it as-is.
  resolved_category_id     BIGINT UNSIGNED NULL,
  -- Same rule as transactions.ai_suggested_category_id: may only point at a
  -- default category or at one owned by the student importing the file.
  ai_suggested_category_id BIGINT UNSIGNED NULL,
  row_status               ENUM('VALID','ERROR','DUPLICATE','IMPORTED','SKIPPED')
                           NOT NULL DEFAULT 'VALID',
  error_message            VARCHAR(255)    NULL,
  transaction_id           BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_import_row (batch_id, csv_row_no),
  KEY ix_import_row_status (batch_id, row_status),
  CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id)
    REFERENCES import_batches(id) ON DELETE CASCADE,
  CONSTRAINT fk_import_row_category FOREIGN KEY (resolved_category_id)
    REFERENCES categories(id) ON DELETE SET NULL,
  CONSTRAINT fk_import_row_txn FOREIGN KEY (transaction_id)
    REFERENCES transactions(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- recent_activity — UC-26: "per account, not per browser", "across sessions and
-- devices".
-- ---------------------------------------------------------------------------
CREATE TABLE recent_activity (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  transaction_id BIGINT UNSIGNED NOT NULL,
  action         ENUM('VIEWED','EDITED') NOT NULL,
  occurred_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recent (user_id, transaction_id, action),
  KEY ix_recent_user_time (user_id, occurred_at),
  CONSTRAINT fk_recent_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_recent_txn FOREIGN KEY (transaction_id)
    REFERENCES transactions(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- admin_audit_log — UC-22 B5: "record administrative actions"
-- ---------------------------------------------------------------------------
CREATE TABLE admin_audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  admin_user_id BIGINT UNSIGNED NOT NULL,
  action        VARCHAR(60)     NOT NULL,
  target_entity VARCHAR(40)     NULL,
  target_id     BIGINT UNSIGNED NULL,
  detail        JSON            NULL,
  ip_address    VARCHAR(45)     NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_audit_admin_time (admin_user_id, created_at),
  KEY ix_audit_target (target_entity, target_id),
  CONSTRAINT fk_audit_admin FOREIGN KEY (admin_user_id)
    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
