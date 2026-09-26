-- ============================================================================
--  CAMPUS COIN - COMPLETE DATABASE SCRIPT (single merged file)
--  MySQL 8.0+  |  utf8mb4_0900_ai_ci  |  InnoDB  |  time zone +07:00
--
--  This is the merged form of the six files in db/. The content is identical;
--  it just runs in one pass instead of six.
--
--  ---------------------------------------------------------------------------
--  HOW TO RUN
--  ---------------------------------------------------------------------------
--  Command line:
--      mysql -u root -p --default-character-set=utf8mb4 < campuscoin_full.sql
--
--  Or open it in MySQL Workbench / DBeaver / Adminer and execute the whole file.
--
--  ---------------------------------------------------------------------------
--  WARNING
--  ---------------------------------------------------------------------------
--  The script begins with DROP DATABASE IF EXISTS campuscoin. Any data currently
--  held in the campuscoin database is deleted and recreated from scratch.
--
--  ---------------------------------------------------------------------------
--  REQUIRED ORDER (do not rearrange)
--  ---------------------------------------------------------------------------
--      PART 1  01_schema.sql      Tables, foreign keys, indexes, CHECK constraints
--      PART 2  02_views.sql       Views (reference tables from part 1)
--      PART 3  03_procedures.sql  Functions and stored procedures
--      PART 4  04_triggers.sql    Triggers (call procedures from part 3)
--      PART 5  05_seed.sql        Mandatory seed data
--      PART 6  06_demo.sql        Demo data (optional)
--
--  ---------------------------------------------------------------------------
--  DESIGN SOURCES
--  ---------------------------------------------------------------------------
--  [SRS]  Campus Coin - Software Requirements Specification v1.0
--  [UC]   Use Case & Business Flow Specification v1.0
--         (UC-01..UC-27, BR-01..BR-18, UAT-01..UAT-17)
--
--  ---------------------------------------------------------------------------
--  RESULT AFTER A SUCCESSFUL RUN
--  ---------------------------------------------------------------------------
--      23 tables | 14 views | 25 procedures | 1 function | 14 triggers
--      38 foreign keys | 15 UNIQUE constraints | 14 CHECK constraints
--      16 system settings | 12 default categories | 7 tip templates | 3 accounts
--      (demo data from part 6 not included in the figures above)
--
--  Demo sign-in accounts:
--      admin@campuscoin.edu             / Admin@123
--      an.nguyen@student.campuscoin.edu / Student@123
--      binh.tran@student.campuscoin.edu / Student@123
-- ============================================================================

-- ##########################################################################
--  PART 1/6 - 01_schema.sql
-- ##########################################################################

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

-- ##########################################################################
--  PART 2/6 - 02_views.sql
-- ##########################################################################

-- ============================================================================
--  CAMPUS COIN — 02_views.sql
--  14 REPORTING VIEWS behind the dashboard, the reports and the admin metrics.
--
--  Common rule: EVERY reporting view filters is_deleted = 0 (BR-09). The
--  application layer therefore cannot accidentally include a soft-deleted
--  transaction in any figure.
-- ============================================================================

USE campuscoin;


-- ---------------------------------------------------------------------------
-- UC-15, BR-10: income / expense / net difference per month.
-- The income-or-expense distinction comes from categories.type (single source
-- of truth), never from transactions.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_monthly_income_expense AS
SELECT
  t.user_id,
  CAST(DATE_FORMAT(t.txn_date, '%Y-%m-01') AS DATE) AS period_month,
  SUM(CASE WHEN c.type = 'INCOME'  THEN t.amount ELSE 0 END) AS total_income,
  SUM(CASE WHEN c.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS total_expense,
  SUM(CASE WHEN c.type = 'INCOME'  THEN t.amount ELSE -t.amount END) AS net_amount,
  COUNT(*) AS txn_count
FROM transactions t
JOIN categories c ON c.id = t.category_id
WHERE t.is_deleted = 0
GROUP BY t.user_id, CAST(DATE_FORMAT(t.txn_date, '%Y-%m-01') AS DATE);


-- ---------------------------------------------------------------------------
-- UC-12 B2, UC-15: total spending per category in a month (the pie chart source)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_category_month_totals AS
SELECT
  t.user_id,
  t.category_id,
  c.name AS category_name,
  c.type,
  CAST(DATE_FORMAT(t.txn_date, '%Y-%m-01') AS DATE) AS period_month,
  SUM(t.amount) AS total_amount,
  COUNT(*)      AS txn_count
FROM transactions t
JOIN categories c ON c.id = t.category_id
WHERE t.is_deleted = 0
GROUP BY t.user_id, t.category_id, c.name, c.type,
         CAST(DATE_FORMAT(t.txn_date, '%Y-%m-01') AS DATE);


-- ---------------------------------------------------------------------------
-- UC-13 B5, BR-11, BR-12: live budget consumption progress bars.
-- The "near limit" threshold is read from system_settings, never hard-coded
-- (VĐ-05).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_budget_consumption AS
SELECT
  b.id AS budget_id,
  b.user_id,
  b.category_id,
  c.name AS category_name,
  b.period_month,
  b.limit_amount,
  IFNULL(s.spent_amount, 0) AS spent_amount,
  ROUND(IFNULL(s.spent_amount, 0) / b.limit_amount * 100, 2) AS consumed_pct,
  ROUND(b.limit_amount - IFNULL(s.spent_amount, 0), 2) AS remaining_amount,
  CASE
    WHEN IFNULL(s.spent_amount, 0) >= b.limit_amount THEN 'EXCEEDED'
    WHEN IFNULL(s.spent_amount, 0) >= b.limit_amount
         * IFNULL(CAST(near_s.setting_value AS DECIMAL(6,2)), 80) / 100 THEN 'NEAR'
    ELSE 'ON_TRACK'
  END AS consumption_status
FROM budgets b
JOIN categories c ON c.id = b.category_id
LEFT JOIN system_settings near_s ON near_s.setting_key = 'budget.near_threshold_pct'
LEFT JOIN (
  -- No filter on type is needed here: trg_budgets_before_insert only allows a
  -- budget on an expense category (BR-11), so every row that joins below is
  -- spending by construction.
  SELECT user_id,
         category_id,
         CAST(DATE_FORMAT(txn_date, '%Y-%m-01') AS DATE) AS period_month,
         SUM(amount) AS spent_amount
  FROM transactions
  WHERE is_deleted = 0
  GROUP BY user_id, category_id, CAST(DATE_FORMAT(txn_date, '%Y-%m-01') AS DATE)
) s ON s.user_id    = b.user_id
   AND s.category_id = b.category_id
   AND s.period_month = b.period_month;


-- ---------------------------------------------------------------------------
-- BR-15, UC-17, UC-25: detect expense categories rising abnormally against the
-- student's OWN three-month average (never against other students).
-- is_spike = 1 when the rise reaches the configured threshold (30% by default).
--
-- The divisor of the average is the NUMBER OF MONTHS IN THE WINDOW (3 by
-- default), not the number of months that happen to have data: a month with no
-- transactions counts as 0. Spending 300 in the first month and 0, 0 in the next
-- two therefore gives an average of 100, not 300.
-- The month count comes from system_settings, so changing the setting changes
-- both the window and the divisor together (VĐ-05) — no "3-month window divided
-- by 6" mismatch.
-- `baseline_months` keeps its original meaning: the number of months that
-- ACTUALLY hold data, so the caller can tell whether a conclusion is safe when
-- the student does not have enough history yet.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_category_spend_trend AS
SELECT
  b.user_id,
  b.category_id,
  b.category_name,
  b.current_month,
  b.current_spend,
  b.baseline_avg_spend,
  b.baseline_months,
  CASE WHEN b.baseline_avg_spend > 0
       THEN ROUND((b.current_spend - b.baseline_avg_spend) / b.baseline_avg_spend * 100, 2)
  END AS pct_change,
  CASE WHEN b.baseline_avg_spend > 0
        AND (b.current_spend - b.baseline_avg_spend) / b.baseline_avg_spend * 100
            >= IFNULL(CAST(st.setting_value AS DECIMAL(6,2)), 30)
       THEN 1 ELSE 0 END AS is_spike
FROM (
  SELECT
    cur.user_id,
    cur.category_id,
    cur.category_name,
    cur.period_month AS current_month,
    cur.total_amount AS current_spend,
    -- NULLIF(...,0) guards the division: if the setting were 0 or text, CAST
    -- would return 0 (with a warning) rather than NULL, so it is blocked
    -- explicitly here.
    IFNULL((SELECT SUM(h.total_amount) FROM v_category_month_totals h
            WHERE h.user_id      = cur.user_id
              AND h.category_id  = cur.category_id
              AND h.period_month >= DATE_SUB(cur.period_month,
                    INTERVAL IFNULL(NULLIF(CAST(bs.setting_value AS UNSIGNED), 0), 3) MONTH)
              AND h.period_month <  cur.period_month), 0)
      / IFNULL(NULLIF(CAST(bs.setting_value AS UNSIGNED), 0), 3) AS baseline_avg_spend,
    (SELECT COUNT(*) FROM v_category_month_totals h2
     WHERE h2.user_id     = cur.user_id
       AND h2.category_id = cur.category_id
       AND h2.period_month >= DATE_SUB(cur.period_month,
             INTERVAL IFNULL(NULLIF(CAST(bs.setting_value AS UNSIGNED), 0), 3) MONTH)
       AND h2.period_month <  cur.period_month) AS baseline_months
  FROM v_category_month_totals cur
  LEFT JOIN system_settings bs ON bs.setting_key = 'insight.spike_baseline_months'
  WHERE cur.type = 'EXPENSE'
) b
LEFT JOIN system_settings st ON st.setting_key = 'insight.spike_threshold_pct';


-- ---------------------------------------------------------------------------
-- BR-17, UAT-09: the last six months for EVERY student, with months that hold
-- no data returned as 0 instead of being missing. dim_month guarantees six rows.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_monthly_income_expense_6m AS
SELECT
  u.id AS user_id,
  m.month_start AS period_month,
  IFNULL(x.total_income, 0)  AS total_income,
  IFNULL(x.total_expense, 0) AS total_expense,
  IFNULL(x.net_amount, 0)    AS net_amount
FROM users u
JOIN dim_month m
  ON m.month_start >= CAST(DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 5 MONTH), '%Y-%m-01') AS DATE)
 AND m.month_start <= CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
LEFT JOIN v_monthly_income_expense x
  ON x.user_id = u.id AND x.period_month = m.month_start
WHERE u.role = 'STUDENT';


-- ---------------------------------------------------------------------------
-- UC-12 B2: the "highest-spending category this month" widget
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_top_category_current_month AS
SELECT user_id, period_month, category_id, category_name, total_amount
FROM (
  SELECT t.user_id,
         t.period_month,
         t.category_id,
         t.category_name,
         t.total_amount,
         ROW_NUMBER() OVER (PARTITION BY t.user_id, t.period_month
                            ORDER BY t.total_amount DESC) AS rn
  FROM v_category_month_totals t
  WHERE t.type = 'EXPENSE'
    AND t.period_month = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
) x
WHERE rn = 1;


-- ---------------------------------------------------------------------------
-- UC-12 B1, BR-10, VĐ-04: current-month totals plus each student's savings goal
-- progress.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_dashboard_summary AS
SELECT
  u.id AS user_id,
  u.full_name,
  u.currency,
  CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE) AS period_month,
  IFNULL(m.total_income, 0)  AS total_income,
  IFNULL(m.total_expense, 0) AS total_expense,
  IFNULL(m.net_amount, 0)    AS net_amount,
  u.monthly_allowance_baseline,
  u.monthly_savings_goal,
  CASE WHEN u.monthly_savings_goal > 0
       THEN ROUND(IFNULL(m.net_amount, 0) / u.monthly_savings_goal * 100, 2) END
    AS savings_goal_pct
FROM users u
LEFT JOIN v_monthly_income_expense m
  ON m.user_id = u.id
 AND m.period_month = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
WHERE u.role = 'STUDENT';


-- ---------------------------------------------------------------------------
-- UC-15: spending summary by DAY for the current month
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_daily_spending_current_month AS
SELECT t.user_id, t.txn_date, SUM(t.amount) AS total_expense, COUNT(*) AS txn_count
FROM transactions t
JOIN categories c ON c.id = t.category_id
WHERE t.is_deleted = 0
  AND c.type = 'EXPENSE'
  AND t.txn_date >= CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
  AND t.txn_date <  DATE_ADD(CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE), INTERVAL 1 MONTH)
GROUP BY t.user_id, t.txn_date;


-- ---------------------------------------------------------------------------
-- UC-15, VĐ-10: spending summary by WEEK for the current month.
--
-- ISO week grouping must use YEARWEEK(date, 3), not YEAR(date) + WEEK(date, 3).
-- YEAR() returns the CALENDAR year while WEEK(...,3) returns the ISO week, and
-- those two disagree around New Year: 2026-12-31 falls in ISO week 53 of ISO
-- year 2026, while 2027-01-01 falls in ISO week 53 of ISO year 2026 as well —
-- but YEAR() would report 2027 for the second row. Two rows of the SAME ISO week
-- would then land in different groups and the week would be split.
-- YEARWEEK(date, 3) returns both parts together as `ISOyear * 100 + ISOweek`,
-- so splitting it back out with DIV and MOD keeps the pair consistent.
--
-- week_start / week_end are the real Monday..Sunday boundaries of that ISO week
-- (WEEKDAY() is 0 on Monday), which may reach outside the current month — that
-- is correct: the ISO week is the unit being reported, and a month boundary
-- never splits a week.
--
-- The ISO parts are computed per row in a derived table so that the outer
-- GROUP BY names only plain columns. Grouping directly on YEARWEEK(...) would
-- leave the projected `YEARWEEK(...) DIV 100` unrecognised as functionally
-- dependent on the GROUP BY expression, and MySQL rejects the query under
-- only_full_group_by (the default) with error 1055.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_weekly_spending_current_month AS
SELECT w.user_id,
       w.iso_year,
       w.iso_week,
       MIN(w.week_start) AS week_start,
       DATE_ADD(MIN(w.week_start), INTERVAL 6 DAY) AS week_end,
       SUM(w.amount) AS total_expense,
       COUNT(*) AS txn_count
FROM (
  SELECT t.user_id,
         CAST(YEARWEEK(t.txn_date, 3) DIV 100 AS UNSIGNED) AS iso_year,
         CAST(YEARWEEK(t.txn_date, 3) MOD 100 AS UNSIGNED) AS iso_week,
         DATE_SUB(t.txn_date, INTERVAL WEEKDAY(t.txn_date) DAY) AS week_start,
         t.amount
  FROM transactions t
  JOIN categories c ON c.id = t.category_id
  WHERE t.is_deleted = 0
    AND c.type = 'EXPENSE'
    AND t.txn_date >= CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
    AND t.txn_date <  DATE_ADD(CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE), INTERVAL 1 MONTH)
) w
GROUP BY w.user_id, w.iso_year, w.iso_week;


-- ---------------------------------------------------------------------------
-- UC-12 B3, UC-18 B4/B6, BR-14: tips shown on the dashboard.
-- Pinned tips always come first; dismissed tips never come back.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_dashboard_tips AS
SELECT user_id, period_month, id AS tip_id, category_id, title, body,
       potential_saving, state,
       ROW_NUMBER() OVER (PARTITION BY user_id, period_month
                          ORDER BY (state = 'PINNED') DESC, rank_score DESC) AS display_order
FROM user_tips
WHERE state <> 'DISMISSED';


-- ---------------------------------------------------------------------------
-- UC-12 B3, UC-21 B2: announcements currently inside their active window
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_active_announcements AS
SELECT id, title, body, severity, audience, starts_at, ends_at
FROM announcements
WHERE is_active = 1
  AND starts_at <= NOW()
  AND (ends_at IS NULL OR ends_at >= NOW());


-- ---------------------------------------------------------------------------
-- UC-26: the "recently viewed" list, skipping soft-deleted transactions
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_user_recent_activity AS
SELECT ra.user_id, ra.action, ra.occurred_at,
       t.id AS transaction_id, t.category_id, c.type, t.amount, t.description, t.txn_date
FROM recent_activity ra
JOIN transactions t ON t.id = ra.transaction_id
JOIN categories   c ON c.id = t.category_id
WHERE t.is_deleted = 0;


-- ---------------------------------------------------------------------------
-- UC-23: aggregate figures for the administrator dashboard.
-- BA note: "active user" = a session seen within the last 30 days.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_admin_usage_stats AS
SELECT
  (SELECT COUNT(*) FROM users WHERE role = 'STUDENT')                        AS total_students,
  (SELECT COUNT(*) FROM users WHERE role = 'STUDENT' AND status = 'ACTIVE')  AS active_students,
  (SELECT COUNT(*) FROM users WHERE role = 'STUDENT' AND status = 'DISABLED') AS disabled_students,
  (SELECT COUNT(DISTINCT user_id) FROM user_sessions
    WHERE last_seen_at >= DATE_SUB(NOW(), INTERVAL 30 DAY))                  AS active_users_30d,
  (SELECT COUNT(*) FROM transactions WHERE is_deleted = 0)                   AS total_transactions,
  (SELECT IFNULL(SUM(t.amount), 0) FROM transactions t
     JOIN categories c ON c.id = t.category_id
    WHERE t.is_deleted = 0 AND c.type = 'EXPENSE')                           AS total_expense_logged,
  (SELECT IFNULL(SUM(t.amount), 0) FROM transactions t
     JOIN categories c ON c.id = t.category_id
    WHERE t.is_deleted = 0 AND c.type = 'INCOME')                            AS total_income_logged,
  (SELECT COUNT(*) FROM budgets)                                             AS total_budgets,
  (SELECT COUNT(*) FROM user_tips)                                            AS total_tips_generated,
  (SELECT COUNT(*) FROM insights)                                            AS total_insights_generated;


-- ---------------------------------------------------------------------------
-- UC-23: most-used categories system-wide (default and personal told apart)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_admin_top_categories AS
SELECT
  c.id AS category_id,
  c.name AS category_name,
  c.type,
  CASE WHEN c.user_id IS NULL THEN 'DEFAULT' ELSE 'PERSONAL' END AS scope,
  COUNT(t.id) AS txn_count,
  IFNULL(SUM(t.amount), 0) AS total_amount,
  COUNT(DISTINCT t.user_id) AS distinct_users
FROM categories c
LEFT JOIN transactions t ON t.category_id = c.id AND t.is_deleted = 0
GROUP BY c.id, c.name, c.type;

-- ##########################################################################
--  PART 3/6 - 03_procedures.sql
-- ##########################################################################

-- ============================================================================
--  CAMPUS COIN — 03_procedures.sql
--  1 utility function + 25 business procedures.
--
--  Why stored procedures instead of putting all the logic in the Java layer:
--    - BR-05, BR-06, BR-07 and BR-08 span several tables, so a CHECK constraint
--      cannot express them; enforcing them in the data layer leaves no path
--      around them.
--    - BR-12 and BR-16 need an atomic "insert if not already there" — UNIQUE +
--      INSERT IGNORE inside a procedure guarantees that even when several
--      processes run at the same time.
--
--  All messages returned by SIGNAL are plain English: they are surfaced by the
--  API and shown to the user, so they are user-facing text.
-- ============================================================================

USE campuscoin;

DELIMITER $$

-- ---------------------------------------------------------------------------
-- fn_render_template — substitutes the {..} placeholders in a tip template or
-- an announcement template (UC-21 B3)
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS fn_render_template $$
CREATE FUNCTION fn_render_template(
  p_template TEXT,
  p_category VARCHAR(80),
  p_amount   DECIMAL(15,2),
  p_pct      DECIMAL(9,2),
  p_limit    DECIMAL(15,2),
  p_baseline DECIMAL(15,2),
  p_change   DECIMAL(9,2),
  p_currency VARCHAR(8)
) RETURNS TEXT
  DETERMINISTIC
  NO SQL
BEGIN
  DECLARE v TEXT;
  SET v = IFNULL(p_template, '');
  SET v = REPLACE(v, '{category_name}', IFNULL(p_category, ''));
  SET v = REPLACE(v, '{amount}',        IFNULL(CAST(ROUND(p_amount, 2)   AS CHAR), ''));
  SET v = REPLACE(v, '{pct}',           IFNULL(CAST(ROUND(p_pct, 1)      AS CHAR), ''));
  SET v = REPLACE(v, '{limit}',         IFNULL(CAST(ROUND(p_limit, 2)    AS CHAR), ''));
  SET v = REPLACE(v, '{baseline_avg}',  IFNULL(CAST(ROUND(p_baseline, 2) AS CHAR), ''));
  SET v = REPLACE(v, '{pct_change}',    IFNULL(CAST(ROUND(p_change, 1)   AS CHAR), ''));
  SET v = REPLACE(v, '{currency}',      IFNULL(p_currency, ''));
  RETURN v;
END $$


-- ============================================================================
--  GROUP A — VALIDATION
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_validate_transaction — BR-02, BR-07, BR-08, BR-13, UC-07
-- Called automatically from the BEFORE INSERT / BEFORE UPDATE triggers of
-- `transactions`.
--
-- There is NO p_type parameter and NO BR-05 comparison: `transactions` has no
-- `type` column, the transaction type IS `categories.type`, so the two values
-- cannot drift apart.
--
-- p_recurring_rule_id / p_import_batch_id exist because `transactions` carries
-- indexes on those two columns but no foreign key, and both point at rows that
-- are owned per student. Without an explicit check a student could write a
-- transaction that references another student's recurring rule or CSV batch,
-- which would leak that row's existence and corrupt the "my data only" rule of
-- BR-02.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_validate_transaction $$
CREATE PROCEDURE sp_validate_transaction(
  IN p_user_id           BIGINT UNSIGNED,
  IN p_category_id       BIGINT UNSIGNED,
  IN p_txn_date          DATE,
  IN p_source            VARCHAR(12),
  IN p_require_active    TINYINT,
  IN p_ai_category_id    BIGINT UNSIGNED,
  IN p_recurring_rule_id BIGINT UNSIGNED,
  IN p_import_batch_id   BIGINT UNSIGNED
)
BEGIN
  DECLARE v_cat_type    VARCHAR(10)     DEFAULT NULL;
  DECLARE v_cat_user    BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_cat_active  TINYINT         DEFAULT NULL;
  DECLARE v_ai_owner    BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_rule_owner  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_batch_owner BIGINT UNSIGNED DEFAULT NULL;

  SELECT type, user_id, is_active
    INTO v_cat_type, v_cat_user, v_cat_active
    FROM categories WHERE id = p_category_id;

  IF v_cat_type IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-05: category does not exist';
  END IF;
  -- BR-02: isolation between students, enforced at the data layer
  IF v_cat_user IS NOT NULL AND v_cat_user <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: category belongs to another student';
  END IF;
  IF IFNULL(p_require_active, 0) = 1 AND v_cat_active = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: category has been disabled';
  END IF;
  -- Recurring transactions are allowed a future date because the scheduler
  -- creates them ahead of time.
  IF p_txn_date > CURDATE() AND IFNULL(p_source, 'MANUAL') <> 'RECURRING' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-08: transaction date cannot be in the future';
  END IF;

  -- BR-13: a category suggested by AI must be a default category (user_id IS
  -- NULL) or a category of THIS student. The foreign key only proves the
  -- category exists, not who owns it, so it is checked explicitly.
  IF p_ai_category_id IS NOT NULL THEN
    -- No row found leaves v_ai_owner NULL and the foreign key raises 1452;
    -- only the "exists but owned by somebody else" case is handled here.
    SELECT user_id INTO v_ai_owner FROM categories WHERE id = p_ai_category_id;
    IF v_ai_owner IS NOT NULL AND v_ai_owner <> p_user_id THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-13: suggested category belongs to another student';
    END IF;
  END IF;

  -- BR-02: recurring_rule_id must point at one of THIS student's own rules.
  IF p_recurring_rule_id IS NOT NULL THEN
    SELECT user_id INTO v_rule_owner FROM recurring_rules WHERE id = p_recurring_rule_id;
    IF v_rule_owner IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: recurring rule does not exist';
    END IF;
    IF v_rule_owner <> p_user_id THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: recurring rule belongs to another student';
    END IF;
  END IF;

  -- BR-02: import_batch_id must point at one of THIS student's own batches.
  IF p_import_batch_id IS NOT NULL THEN
    SELECT user_id INTO v_batch_owner FROM import_batches WHERE id = p_import_batch_id;
    IF v_batch_owner IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: import batch does not exist';
    END IF;
    IF v_batch_owner <> p_user_id THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: import batch belongs to another student';
    END IF;
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- sp_validate_budget — BR-11, UC-13: a limit may only be set on an EXPENSE
-- category of one's own.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_validate_budget $$
CREATE PROCEDURE sp_validate_budget(
  IN p_user_id     BIGINT UNSIGNED,
  IN p_category_id BIGINT UNSIGNED
)
BEGIN
  DECLARE v_type   VARCHAR(10)     DEFAULT NULL;
  DECLARE v_owner  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_active TINYINT         DEFAULT NULL;

  SELECT type, user_id, is_active
    INTO v_type, v_owner, v_active
    FROM categories WHERE id = p_category_id;

  IF v_type IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Category does not exist';
  END IF;
  IF v_type <> 'EXPENSE' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-11: a budget may only be set on an expense category';
  END IF;
  IF v_owner IS NOT NULL AND v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: category belongs to another student';
  END IF;
  IF v_active = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-07: category has been disabled';
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- sp_require_admin — BR-06, BR-03: the shared administrator authorisation gate.
--
-- This is the ONLY authorisation check for every administrative operation. It
-- takes the id of the person performing the operation and looks the account up
-- in `users` in the same statement that follows, so authorisation is always
-- re-derived from the database and never carried in from the caller.
--
-- Two conditions must hold (BR-03 adds the second one):
--   role   = 'ADMIN'    — the account really is an administrator
--   status = 'ACTIVE'   — a disabled administrator immediately loses the right
--                         to act, exactly like a disabled student loses the
--                         right to sign in.
--
-- There is deliberately NO session-variable shortcut such as the former
-- @cc_is_admin flag: a MySQL user variable belongs to a CONNECTION, and a
-- connection pool hands the same connection to whichever request comes next, so
-- a flag left behind by one call could authorise the following one.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_require_admin $$
CREATE PROCEDURE sp_require_admin(IN p_actor_id BIGINT UNSIGNED)
BEGIN
  DECLARE v_role   VARCHAR(10) DEFAULT NULL;
  DECLARE v_status VARCHAR(10) DEFAULT NULL;

  SELECT role, status INTO v_role, v_status FROM users WHERE id = p_actor_id;

  IF IFNULL(v_role, '') <> 'ADMIN' OR IFNULL(v_status, '') <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-06: administrator privileges required (role ADMIN and status ACTIVE)';
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- sp_validate_recurring_rule — UC-09, BR-02, BR-05, BR-07
--
-- Validates at the moment the rule is created or edited rather than when the
-- scheduler posts it: otherwise a broken rule would only surface days later,
-- and worse, from inside the loop of sp_post_recurring_transactions.
--
-- Why `recurring_rules` KEEPS a `type` column while `transactions` dropped it:
-- a rule is a configuration template that can be set up before any transaction
-- exists, so there has to be something to compare against `categories.type` at
-- the moment the rule is written.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_validate_recurring_rule $$
CREATE PROCEDURE sp_validate_recurring_rule(
  IN p_user_id     BIGINT UNSIGNED,
  IN p_category_id BIGINT UNSIGNED,
  IN p_type        VARCHAR(10)
)
BEGIN
  DECLARE v_type   VARCHAR(10)     DEFAULT NULL;
  DECLARE v_owner  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_active TINYINT         DEFAULT NULL;

  SELECT type, user_id, is_active
    INTO v_type, v_owner, v_active
    FROM categories WHERE id = p_category_id;

  IF v_type IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-05: category does not exist';
  END IF;
  -- BR-05: the type of the rule must match the type of the category
  IF v_type <> p_type THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-05: recurring rule type must match the category type';
  END IF;
  -- BR-02: default categories are shared, personal categories belong to one owner
  IF v_owner IS NOT NULL AND v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: category belongs to another student';
  END IF;
  -- BR-07: no new rule on a category that has been disabled
  IF v_active = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: category has been disabled';
  END IF;
END $$


-- ============================================================================
--  GROUP B — BUDGET ALERTS (BR-11, BR-12, UC-14)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_check_budget_alerts — called automatically after every insert or update of
-- an expense transaction.
--
-- ELSEIF is deliberate: if one transaction jumps straight past 100%, only the
-- "exceeded" notification is raised, so the student never receives two messages
-- at once. UAT-07 still passes because 24 then +7 are two separate writes: the
-- first reaches 80% -> NEAR, the second passes 100% -> EXCEEDED.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_check_budget_alerts $$
CREATE PROCEDURE sp_check_budget_alerts(
  IN p_user_id      BIGINT UNSIGNED,
  IN p_category_id  BIGINT UNSIGNED,
  IN p_period_month DATE
)
BEGIN
  DECLARE v_budget_id BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_limit     DECIMAL(15,2)   DEFAULT 0;
  DECLARE v_spent     DECIMAL(15,2)   DEFAULT 0;
  DECLARE v_pct       DECIMAL(9,2)    DEFAULT 0;
  DECLARE v_near      DECIMAL(6,2)    DEFAULT 80;
  DECLARE v_exceed    DECIMAL(6,2)    DEFAULT 100;
  DECLARE v_cat_name  VARCHAR(80)     DEFAULT '';
  DECLARE v_notif_id  BIGINT UNSIGNED DEFAULT NULL;

  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 80) INTO v_near
    FROM system_settings WHERE setting_key = 'budget.near_threshold_pct';
  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 100) INTO v_exceed
    FROM system_settings WHERE setting_key = 'budget.exceeded_threshold_pct';

  SELECT id, limit_amount INTO v_budget_id, v_limit
    FROM budgets
   WHERE user_id = p_user_id
     AND category_id = p_category_id
     AND period_month = p_period_month
   LIMIT 1;

  IF v_budget_id IS NOT NULL AND IFNULL(v_limit, 0) > 0 THEN

    -- BR-09: only transactions that are not soft-deleted count.
    -- No type filter is needed: a budget can only exist on an expense category
    -- (BR-11, blocked by sp_validate_budget when the budget is created).
    SELECT IFNULL(SUM(amount), 0) INTO v_spent
      FROM transactions
     WHERE user_id = p_user_id
       AND category_id = p_category_id
       AND is_deleted = 0
       AND txn_date >= p_period_month
       AND txn_date <  DATE_ADD(p_period_month, INTERVAL 1 MONTH);

    SET v_pct = ROUND(v_spent / v_limit * 100, 2);
    SELECT name INTO v_cat_name FROM categories WHERE id = p_category_id;

    IF v_pct >= v_exceed THEN
      -- BR-12: INSERT IGNORE + UNIQUE(budget_id, threshold_type) blocks repeats
      INSERT IGNORE INTO budget_alert_log
        (budget_id, user_id, category_id, threshold_type, threshold_pct,
         consumed_pct, spent_amount, limit_amount, triggered_at)
      VALUES
        (v_budget_id, p_user_id, p_category_id, 'EXCEEDED', v_exceed,
         v_pct, v_spent, v_limit, NOW());

      IF ROW_COUNT() > 0 THEN
        INSERT INTO notifications
          (user_id, type, title, body, link_url, ref_entity_type, ref_entity_id)
        VALUES
          (p_user_id, 'BUDGET_EXCEEDED',
           CONCAT('Budget exceeded: ', v_cat_name),
           CONCAT('You have spent ', CAST(ROUND(v_spent, 2) AS CHAR), ' of ',
                  CAST(ROUND(v_limit, 2) AS CHAR), ' (',
                  CAST(v_pct AS CHAR), '%) on ', v_cat_name, '.'),
           '/budgets', 'BUDGET', v_budget_id);
        SET v_notif_id = LAST_INSERT_ID();
        UPDATE budget_alert_log SET notification_id = v_notif_id
         WHERE budget_id = v_budget_id AND threshold_type = 'EXCEEDED';
      END IF;

    ELSEIF v_pct >= v_near THEN
      INSERT IGNORE INTO budget_alert_log
        (budget_id, user_id, category_id, threshold_type, threshold_pct,
         consumed_pct, spent_amount, limit_amount, triggered_at)
      VALUES
        (v_budget_id, p_user_id, p_category_id, 'NEAR', v_near,
         v_pct, v_spent, v_limit, NOW());

      IF ROW_COUNT() > 0 THEN
        INSERT INTO notifications
          (user_id, type, title, body, link_url, ref_entity_type, ref_entity_id)
        VALUES
          (p_user_id, 'BUDGET_NEAR',
           CONCAT('Approaching budget limit: ', v_cat_name),
           CONCAT('You have used ', CAST(v_pct AS CHAR), '% of your ',
                  v_cat_name, ' budget (', CAST(ROUND(v_spent, 2) AS CHAR), ' of ',
                  CAST(ROUND(v_limit, 2) AS CHAR), ').'),
           '/budgets', 'BUDGET', v_budget_id);
        SET v_notif_id = LAST_INSERT_ID();
        UPDATE budget_alert_log SET notification_id = v_notif_id
         WHERE budget_id = v_budget_id AND threshold_type = 'NEAR';
      END IF;
    END IF;
  END IF;
END $$


-- ============================================================================
--  GROUP C — SAVING TIPS & INSIGHTS (BR-13, BR-14, BR-15, UC-17, UC-18)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_generate_tips — generates tips from the student's own data, ranks them by
-- potential saving and keeps only the top N (BR-14, 3 by default).
-- INSERT IGNORE + dedupe_key guarantee that a pinned or dismissed tip is never
-- generated a second time.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_generate_tips $$
CREATE PROCEDURE sp_generate_tips(
  IN p_user_id      BIGINT UNSIGNED,
  IN p_period_month DATE,
  IN p_max_tips     TINYINT UNSIGNED
)
BEGIN
  DECLARE v_max_tips  TINYINT UNSIGNED DEFAULT 3;
  DECLARE v_spike_pct DECIMAL(6,2)     DEFAULT 30;
  DECLARE v_near_pct  DECIMAL(6,2)     DEFAULT 80;
  DECLARE v_exceed_pct DECIMAL(6,2)    DEFAULT 100;
  DECLARE v_currency  VARCHAR(8)       DEFAULT '$';
  DECLARE v_income    DECIMAL(15,2)    DEFAULT 0;
  DECLARE v_expense   DECIMAL(15,2)    DEFAULT 0;
  DECLARE v_net       DECIMAL(15,2)    DEFAULT 0;
  DECLARE v_goal      DECIMAL(15,2)    DEFAULT 0;
  DECLARE v_rows      INT              DEFAULT 0;

  IF p_period_month IS NULL THEN
    SET p_period_month = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE);
  END IF;

  SELECT IFNULL(MAX(CAST(setting_value AS UNSIGNED)), 3) INTO v_max_tips
    FROM system_settings WHERE setting_key = 'tips.max_dashboard';
  IF p_max_tips IS NOT NULL AND p_max_tips > 0 THEN
    SET v_max_tips = p_max_tips;
  END IF;

  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 30) INTO v_spike_pct
    FROM system_settings WHERE setting_key = 'insight.spike_threshold_pct';
  -- VĐ-05: the budget thresholds are configuration, never constants. These read
  -- the same keys, with the same defaults, as v_budget_consumption and
  -- sp_check_budget_alerts (near = 80, exceeded = 100), so all three agree on
  -- where NEAR ends and EXCEEDED begins.
  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 80) INTO v_near_pct
    FROM system_settings WHERE setting_key = 'budget.near_threshold_pct';
  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 100) INTO v_exceed_pct
    FROM system_settings WHERE setting_key = 'budget.exceeded_threshold_pct';
  -- A missing key already keeps the default above. A value that is present but
  -- unusable (non-numeric casts to 0, negative stays negative) would otherwise
  -- make every budget look "near", so fall back to the default as well.
  IF v_near_pct IS NULL OR v_near_pct <= 0 THEN
    SET v_near_pct = 80;
  END IF;
  IF v_exceed_pct IS NULL OR v_exceed_pct <= 0 THEN
    SET v_exceed_pct = 100;
  END IF;
  SELECT IFNULL(MAX(setting_value), '$') INTO v_currency
    FROM system_settings WHERE setting_key = 'app.currency_symbol';
  SELECT monthly_savings_goal INTO v_goal FROM users WHERE id = p_user_id;

  SELECT IFNULL(SUM(CASE WHEN c.type = 'INCOME'  THEN t.amount ELSE 0 END), 0),
         IFNULL(SUM(CASE WHEN c.type = 'EXPENSE' THEN t.amount ELSE 0 END), 0)
    INTO v_income, v_expense
    FROM transactions t
    JOIN categories c ON c.id = t.category_id
   WHERE t.user_id = p_user_id
     AND t.is_deleted = 0
     AND t.txn_date >= p_period_month
     AND t.txn_date <  DATE_ADD(p_period_month, INTERVAL 1 MONTH);
  SET v_net = v_income - v_expense;

  DROP TEMPORARY TABLE IF EXISTS tmp_tips;
  CREATE TEMPORARY TABLE tmp_tips (
    tip_template_id  BIGINT UNSIGNED NULL,
    category_id      BIGINT UNSIGNED NULL,
    title            VARCHAR(200)    NOT NULL,
    body             TEXT            NOT NULL,
    potential_saving DECIMAL(15,2)   NOT NULL DEFAULT 0,
    rank_score       DECIMAL(18,4)   NOT NULL DEFAULT 0
  ) ENGINE=InnoDB;

  -- Rule 1 — category already over the configured "exceeded" threshold (BR-12)
  INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                        potential_saving, rank_score)
  SELECT tt.id, v.category_id,
         fn_render_template(tt.title_template, v.category_name, v.spent_amount,
                            v.consumed_pct, v.limit_amount, NULL, NULL, v_currency),
         fn_render_template(tt.body_template,  v.category_name, v.spent_amount,
                            v.consumed_pct, v.limit_amount, NULL, NULL, v_currency),
         GREATEST(v.spent_amount - v.limit_amount, 0),
         GREATEST(v.spent_amount - v.limit_amount, 0) * 1.0
  FROM v_budget_consumption v
  JOIN tip_templates tt ON tt.code = 'OVER_BUDGET' AND tt.is_active = 1
  WHERE v.user_id = p_user_id
    AND v.period_month = p_period_month
    AND v.consumed_pct >= v_exceed_pct;

  -- Rule 2 — approaching the limit. The band is [near, exceeded): the same split
  -- sp_check_budget_alerts uses, so a tip and an alert always agree.
  INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                        potential_saving, rank_score)
  SELECT tt.id, v.category_id,
         fn_render_template(tt.title_template, v.category_name, v.spent_amount,
                            v.consumed_pct, v.limit_amount, NULL, NULL, v_currency),
         fn_render_template(tt.body_template,  v.category_name, v.spent_amount,
                            v.consumed_pct, v.limit_amount, NULL, NULL, v_currency),
         GREATEST(v.limit_amount - v.spent_amount, 0) * 0.5,
         GREATEST(v.limit_amount - v.spent_amount, 0) * 0.6
  FROM v_budget_consumption v
  JOIN tip_templates tt ON tt.code = 'NEAR_BUDGET' AND tt.is_active = 1
  WHERE v.user_id = p_user_id
    AND v.period_month = p_period_month
    AND v.consumed_pct >= v_near_pct
    AND v.consumed_pct <  v_exceed_pct;

  -- Rule 3 — category rising abnormally against this student's own habits (BR-15)
  INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                        potential_saving, rank_score)
  SELECT tt.id, s.category_id,
         fn_render_template(tt.title_template, s.category_name, s.current_spend,
                            NULL, NULL, s.baseline_avg_spend, s.pct_change, v_currency),
         fn_render_template(tt.body_template,  s.category_name, s.current_spend,
                            NULL, NULL, s.baseline_avg_spend, s.pct_change, v_currency),
         GREATEST(s.current_spend - s.baseline_avg_spend, 0),
         GREATEST(s.current_spend - s.baseline_avg_spend, 0) * 0.8
  FROM v_category_spend_trend s
  JOIN tip_templates tt ON tt.code = 'CATEGORY_SPIKE' AND tt.is_active = 1
  WHERE s.user_id = p_user_id
    AND s.current_month = p_period_month
    AND s.baseline_months >= 1
    AND s.baseline_avg_spend > 0
    AND s.pct_change >= v_spike_pct;

  -- Rule 4 — a category with heavy spending but no budget set
  INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                        potential_saving, rank_score)
  SELECT tt.id, t.category_id,
         fn_render_template(tt.title_template, t.category_name, t.total_amount,
                            NULL, NULL, NULL, NULL, v_currency),
         fn_render_template(tt.body_template,  t.category_name, t.total_amount,
                            NULL, NULL, NULL, NULL, v_currency),
         ROUND(t.total_amount * 0.2, 2),
         ROUND(t.total_amount * 0.5, 2)
  FROM v_category_month_totals t
  JOIN tip_templates tt ON tt.code = 'NO_BUDGET_SET' AND tt.is_active = 1
  WHERE t.user_id = p_user_id
    AND t.period_month = p_period_month
    AND t.type = 'EXPENSE'
    AND NOT EXISTS (SELECT 1 FROM budgets b
                    WHERE b.user_id     = t.user_id
                      AND b.category_id = t.category_id
                      AND b.period_month = p_period_month)
  ORDER BY t.total_amount DESC
  LIMIT 2;

  -- Rule 5 — the savings goal is at risk of being missed (VĐ-04)
  IF v_goal > 0 AND v_net < v_goal THEN
    INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                          potential_saving, rank_score)
    SELECT tt.id, NULL,
           fn_render_template(tt.title_template, NULL, v_net, NULL, v_goal,
                              v_income, NULL, v_currency),
           fn_render_template(tt.body_template,  NULL, v_net, NULL, v_goal,
                              v_income, NULL, v_currency),
           GREATEST(v_goal - v_net, 0),
           GREATEST(v_goal - v_net, 0) * 0.6
    FROM tip_templates tt
    WHERE tt.code = 'SAVINGS_GOAL_AT_RISK' AND tt.is_active = 1;
  END IF;

  -- Rule 6 — a new student with too little data to analyse (UC-18 A1)
  SELECT COUNT(*) INTO v_rows
    FROM v_category_month_totals
   WHERE user_id = p_user_id AND period_month = p_period_month;
  IF v_rows = 0 THEN
    INSERT INTO tmp_tips (tip_template_id, category_id, title, body,
                          potential_saving, rank_score)
    SELECT tt.id, NULL,
           fn_render_template(tt.title_template, NULL, 0, NULL, NULL, NULL, NULL, v_currency),
           fn_render_template(tt.body_template,  NULL, 0, NULL, NULL, NULL, NULL, v_currency),
           0, 0.1
    FROM tip_templates tt
    WHERE tt.code = 'GENERIC' AND tt.is_active = 1;
  END IF;

  -- BR-14: keep only the top N tips by potential saving
  INSERT IGNORE INTO user_tips
    (user_id, tip_template_id, period_month, category_id, title, body,
     potential_saving, rank_score, state, generated_at)
  SELECT r.user_id, r.tip_template_id, r.period_month, r.category_id, r.title,
         r.body, r.potential_saving, r.rank_score, 'NEW', NOW()
  FROM (
    SELECT p_user_id AS user_id,
           tip_template_id,
           p_period_month AS period_month,
           category_id, title, body, potential_saving, rank_score,
           ROW_NUMBER() OVER (ORDER BY rank_score DESC, potential_saving DESC) AS rn
    FROM tmp_tips
  ) r
  WHERE r.rn <= v_max_tips;

  DROP TEMPORARY TABLE IF EXISTS tmp_tips;
END $$


-- ---------------------------------------------------------------------------
-- sp_generate_monthly_insight — UC-17, BR-13, BR-15
--
-- The data layer aggregates the figures, flags unusual categories and produces a
-- rule-based fallback summary. The application layer then calls the AI service,
-- updates summary_text / advice_text and switches generated_by to 'AI'.
-- VĐ-12: only aggregates leave the system — never an email address or a name.
--
-- If the insight was generated by AI, a re-run does NOT overwrite the text.
--
-- summary_text / advice_text are stored user-visible content, so they are
-- written in English.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_generate_monthly_insight $$
CREATE PROCEDURE sp_generate_monthly_insight(
  IN p_user_id      BIGINT UNSIGNED,
  IN p_period_month DATE
)
BEGIN
  DECLARE v_income   DECIMAL(15,2) DEFAULT 0;
  DECLARE v_expense  DECIMAL(15,2) DEFAULT 0;
  DECLARE v_net      DECIMAL(15,2) DEFAULT 0;
  DECLARE v_flagged  JSON          DEFAULT NULL;
  DECLARE v_top_name VARCHAR(80)   DEFAULT NULL;
  DECLARE v_top_amt  DECIMAL(15,2) DEFAULT 0;
  DECLARE v_label    CHAR(7)       DEFAULT '';
  DECLARE v_summary  TEXT          DEFAULT NULL;
  DECLARE v_advice   TEXT          DEFAULT NULL;

  IF p_period_month IS NULL THEN
    SET p_period_month = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE);
  END IF;
  SET v_label = DATE_FORMAT(p_period_month, '%Y-%m');

  SELECT IFNULL(SUM(CASE WHEN c.type = 'INCOME'  THEN t.amount ELSE 0 END), 0),
         IFNULL(SUM(CASE WHEN c.type = 'EXPENSE' THEN t.amount ELSE 0 END), 0)
    INTO v_income, v_expense
    FROM transactions t
    JOIN categories c ON c.id = t.category_id
   WHERE t.user_id = p_user_id
     AND t.is_deleted = 0
     AND t.txn_date >= p_period_month
     AND t.txn_date <  DATE_ADD(p_period_month, INTERVAL 1 MONTH);
  SET v_net = v_income - v_expense;

  SELECT category_name, total_amount INTO v_top_name, v_top_amt
    FROM v_category_month_totals
   WHERE user_id = p_user_id
     AND period_month = p_period_month
     AND type = 'EXPENSE'
   ORDER BY total_amount DESC
   LIMIT 1;

  SELECT JSON_ARRAYAGG(JSON_OBJECT(
           'categoryId',   t.category_id,
           'categoryName', t.category_name,
           'currentTotal', t.current_spend,
           'baselineAvg',  t.baseline_avg_spend,
           'pctChange',    t.pct_change))
    INTO v_flagged
    FROM v_category_spend_trend t
   WHERE t.user_id = p_user_id
     AND t.current_month = p_period_month
     AND t.is_spike = 1;

  IF v_flagged IS NULL THEN
    SET v_flagged = JSON_ARRAY();
  END IF;

  SET v_summary = CONCAT(
    v_label, ': you received ', CAST(ROUND(v_income, 2) AS CHAR),
    ' and spent ', CAST(ROUND(v_expense, 2) AS CHAR),
    ', net difference ', CAST(ROUND(v_net, 2) AS CHAR), '. ',
    IF(v_top_name IS NOT NULL,
       CONCAT('Highest spending category: ', v_top_name, ' (',
              CAST(ROUND(v_top_amt, 2) AS CHAR), '). '),
       'No spending has been recorded yet. ')
  );

  SET v_advice = CASE
    WHEN JSON_LENGTH(v_flagged) > 0 THEN
      'One or more categories are rising above your usual level. Consider setting a weekly cap for those categories next month.'
    WHEN v_net < 0 THEN
      'Total spending is higher than total income this month. Cut non-essential spending first.'
    ELSE
      'You are keeping a positive balance. Consider moving the surplus toward your savings goal at the start of the month.'
  END;

  INSERT INTO insights
    (user_id, period_month, summary_text, advice_text, flagged_categories,
     total_income, total_expense, net_amount, generated_by, status, generated_at)
  VALUES
    (p_user_id, p_period_month, v_summary, v_advice, v_flagged,
     v_income, v_expense, v_net, 'RULE_BASED', 'READY', NOW())
  ON DUPLICATE KEY UPDATE
    summary_text       = IF(insights.generated_by = 'AI', insights.summary_text, v_summary),
    advice_text        = IF(insights.generated_by = 'AI', insights.advice_text,  v_advice),
    flagged_categories = v_flagged,
    total_income       = v_income,
    total_expense      = v_expense,
    net_amount         = v_net,
    generated_at       = NOW();
END $$


-- ============================================================================
--  GROUP D — RECURRING TRANSACTIONS (BR-16, UC-09)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_post_recurring_transactions — the scheduler calls this once a day.
--
-- A1 (catch-up after downtime): the WHILE loop walks over EVERY missing period.
-- INSERT IGNORE into recurring_occurrences relies on
-- UNIQUE(rule_id, period_key), so no matter how often it runs, each period
-- produces exactly one transaction — that is BR-16.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_post_recurring_transactions $$
CREATE PROCEDURE sp_post_recurring_transactions(IN p_as_of DATE)
BEGIN
  DECLARE v_done        INT DEFAULT 0;
  DECLARE v_rule_id     BIGINT UNSIGNED;
  DECLARE v_user_id     BIGINT UNSIGNED;
  DECLARE v_category_id BIGINT UNSIGNED;
  DECLARE v_amount      DECIMAL(15,2);
  -- Sized to the COLUMN, not to the plaintext. Since recurring_rules.description holds a Base64
  -- AES-256-GCM envelope (up to 2048 characters for a 255-character note), the old VARCHAR(255)
  -- truncated on the first fetch and every run failed with "Data too long for column 'v_desc'".
  -- The envelope is copied through unchanged: a procedure cannot decrypt, and must not, because
  -- that would require the key inside MySQL.
  DECLARE v_desc        VARCHAR(2048);
  DECLARE v_freq        VARCHAR(12);
  DECLARE v_interval    INT;
  DECLARE v_next        DATE;
  DECLARE v_end         DATE;
  DECLARE v_period_key  VARCHAR(12);
  DECLARE v_txn_id      BIGINT UNSIGNED;
  DECLARE v_guard       INT DEFAULT 0;
  DECLARE v_as_of       DATE;

  -- The c.is_active = 1 condition is a design decision (not found in the SRS or
  -- the Use Case document): it stops the scheduler from dying mid-loop when a
  -- category is disabled after the rule was created. The rule stays ACTIVE but
  -- posts nothing until the category is enabled again.
  DECLARE cur CURSOR FOR
    SELECT r.id, r.user_id, r.category_id, r.amount, r.description,
           r.frequency, r.interval_count, r.next_run_date, r.end_date
      FROM recurring_rules r
      JOIN categories c ON c.id = r.category_id
     WHERE r.status = 'ACTIVE'
       AND c.is_active = 1
       AND r.next_run_date <= IFNULL(p_as_of, CURDATE())
       AND (r.end_date IS NULL OR r.next_run_date <= r.end_date);
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  SET v_as_of = IFNULL(p_as_of, CURDATE());

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_rule_id, v_user_id, v_category_id, v_amount, v_desc,
                   v_freq, v_interval, v_next, v_end;
    IF v_done = 1 THEN LEAVE read_loop; END IF;

    SET v_guard = 0;
    -- Safety stop after 500 periods so a misconfigured template cannot hang the
    -- whole system.
    WHILE v_next <= v_as_of
          AND (v_end IS NULL OR v_next <= v_end)
          AND v_guard < 500 DO

      SET v_period_key = CASE v_freq
        WHEN 'DAILY'     THEN DATE_FORMAT(v_next, '%Y-%m-%d')
        WHEN 'WEEKLY'    THEN CONCAT(DATE_FORMAT(v_next, '%x'), '-W',
                                     LPAD(WEEK(v_next, 3), 2, '0'))
        WHEN 'MONTHLY'   THEN DATE_FORMAT(v_next, '%Y-%m')
        WHEN 'QUARTERLY' THEN CONCAT(YEAR(v_next), '-Q', QUARTER(v_next))
        WHEN 'YEARLY'    THEN DATE_FORMAT(v_next, '%Y')
        ELSE DATE_FORMAT(v_next, '%Y-%m-%d')
      END;

      INSERT IGNORE INTO recurring_occurrences
        (rule_id, period_key, scheduled_date, status)
      VALUES (v_rule_id, v_period_key, v_next, 'POSTED');

      -- ROW_COUNT() = 0 means this period was already posted: skip it and only
      -- advance the date.
      IF ROW_COUNT() > 0 THEN
        -- No `type` is passed: the transaction type is the category type (BR-05).
        INSERT INTO transactions
          (user_id, category_id, amount, description, txn_date,
           source, recurring_rule_id)
        VALUES
          (v_user_id, v_category_id, v_amount, v_desc, v_next,
           'RECURRING', v_rule_id);
        SET v_txn_id = LAST_INSERT_ID();

        UPDATE recurring_occurrences
           SET transaction_id = v_txn_id
         WHERE rule_id = v_rule_id AND period_key = v_period_key;
      END IF;

      SET v_next = CASE v_freq
        WHEN 'DAILY'     THEN DATE_ADD(v_next, INTERVAL v_interval DAY)
        WHEN 'WEEKLY'    THEN DATE_ADD(v_next, INTERVAL (v_interval * 7) DAY)
        WHEN 'MONTHLY'   THEN DATE_ADD(v_next, INTERVAL v_interval MONTH)
        WHEN 'QUARTERLY' THEN DATE_ADD(v_next, INTERVAL (v_interval * 3) MONTH)
        WHEN 'YEARLY'    THEN DATE_ADD(v_next, INTERVAL v_interval YEAR)
        ELSE DATE_ADD(v_next, INTERVAL v_interval DAY)
      END;
      SET v_guard = v_guard + 1;
    END WHILE;

    UPDATE recurring_rules
       SET next_run_date = v_next,
           last_run_date = (SELECT MAX(scheduled_date) FROM recurring_occurrences
                             WHERE rule_id = v_rule_id),
           status = CASE WHEN v_end IS NOT NULL AND v_next > v_end
                         THEN 'ENDED' ELSE status END
     WHERE id = v_rule_id;
  END LOOP;
  CLOSE cur;
END $$


-- ============================================================================
--  GROUP E — CSV IMPORT (UC-11)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_apply_csv_batch — UC-11 B9: imports only the rows currently in state VALID.
-- Error rows stay in import_rows so the report can still show them.
--
-- Every row has its own block with an EXIT HANDLER, so one bad row cannot break
-- the whole batch.
--
-- Two design points that matter:
--
--  * The owner of a row is ALWAYS read from import_batches.user_id. import_rows
--    has no user_id column of its own — a second copy of the owner could
--    disagree with the batch and nothing would keep the two in step.
--
--  * The category the student chose (or overrode) during the preview step is
--    stored in import_rows.resolved_category_id and is used AS-IS. The name from
--    the CSV file is only resolved when resolved_category_id IS NULL. Without
--    this, confirming the import would silently throw away the student's
--    correction and re-apply the machine's first guess.
--
--    The two cases are therefore deliberately asymmetric: a NULL choice is
--    resolved from the name, while a choice that has since become unusable is
--    reported as an ERROR row rather than quietly replaced.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_apply_csv_batch $$
CREATE PROCEDURE sp_apply_csv_batch(IN p_batch_id BIGINT UNSIGNED)
BEGIN
  DECLARE v_done       INT DEFAULT 0;
  DECLARE v_user_id    BIGINT UNSIGNED;
  DECLARE v_batch_st   VARCHAR(20);
  DECLARE v_row_id     BIGINT UNSIGNED;
  DECLARE v_r_date     DATE;
  DECLARE v_r_amount   DECIMAL(15,2);
  DECLARE v_r_type     VARCHAR(10);
  DECLARE v_r_desc     VARCHAR(255);
  DECLARE v_r_cat_name VARCHAR(80);
  DECLARE v_r_cat_id   BIGINT UNSIGNED;
  DECLARE v_cat_ok     BIGINT UNSIGNED;
  DECLARE v_choice_bad TINYINT DEFAULT 0;
  DECLARE v_txn_id     BIGINT UNSIGNED;
  DECLARE v_imported   INT DEFAULT 0;
  DECLARE v_errors     INT DEFAULT 0;
  DECLARE v_skipped    INT DEFAULT 0;
  DECLARE v_total      INT DEFAULT 0;

  DECLARE cur CURSOR FOR
    SELECT id, parsed_date, parsed_amount, parsed_type,
           parsed_description, parsed_category_name, resolved_category_id
      FROM import_rows
     WHERE batch_id = p_batch_id AND row_status = 'VALID'
     ORDER BY csv_row_no;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  SELECT user_id, status INTO v_user_id, v_batch_st
    FROM import_batches WHERE id = p_batch_id;

  IF v_user_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Import batch does not exist';
  END IF;
  IF v_batch_st NOT IN ('UPLOADED','PREVIEWED') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Import batch has already been processed or cancelled';
  END IF;

  OPEN cur;
  row_loop: LOOP
    FETCH cur INTO v_row_id, v_r_date, v_r_amount, v_r_type,
                   v_r_desc, v_r_cat_name, v_r_cat_id;
    IF v_done = 1 THEN LEAVE row_loop; END IF;

    SET v_txn_id = NULL;
    SET v_cat_ok = NULL;
    SET v_choice_bad = 0;

    -- Case A — the student made a final choice during the preview
    -- (resolved_category_id IS NOT NULL). That choice is authoritative and is
    -- never re-derived from the file's category name.
    --
    -- It must still be usable at commit time: it has to exist, be active, and be
    -- a default category or one owned by this student. The foreign key is
    -- ON DELETE SET NULL, so a category deleted since the preview has already
    -- become NULL and lands in case B; this check also covers a category disabled
    -- between preview and commit, or a tampered preview payload.
    --
    -- If the choice no longer qualifies the row becomes an ERROR. Silently
    -- resolving the name instead would import the row under a category the
    -- student explicitly did not pick — the failure this guards against.
    --
    -- Note that the type from the file is deliberately NOT compared here. The
    -- student overrode the category on the preview screen, and correcting the
    -- category is exactly how they correct a wrong type: the transaction's type
    -- is whatever their chosen category says (BR-05). Requiring a match would
    -- silently undo their correction.
    -- These lookups use scalar subqueries assigned with SET rather than
    -- SELECT ... INTO. A SELECT ... INTO that matches no row raises the same
    -- NOT FOUND condition as an exhausted cursor, which this procedure's
    -- handler translates into "cursor finished" — that would end the loop at
    -- the first row whose category could not be resolved, silently skipping
    -- every row after it. A scalar subquery yields NULL instead and leaves the
    -- handler untouched.
    IF v_r_cat_id IS NOT NULL THEN
      SET v_cat_ok = (SELECT id FROM categories
                       WHERE id = v_r_cat_id
                         AND is_active = 1
                         AND (user_id IS NULL OR user_id = v_user_id)
                       LIMIT 1);
      IF v_cat_ok IS NULL THEN
        SET v_choice_bad = 1;
      END IF;
    END IF;

    -- Case B — no preview choice: resolve from the file's category name,
    -- preferring a personal category, then a default one (UC-11 B6). This whole
    -- chain is skipped when the student's choice turned out to be unusable.
    IF v_r_cat_id IS NULL AND v_choice_bad = 0 AND v_r_cat_name IS NOT NULL THEN
      SET v_r_cat_id = (SELECT id FROM categories
                         WHERE type = v_r_type
                           AND is_active = 1
                           AND name = v_r_cat_name
                           AND (user_id = v_user_id OR user_id IS NULL)
                         ORDER BY (user_id IS NULL)
                         LIMIT 1);
    END IF;

    -- Still nothing: fall back to the default category for the type.
    -- BR-13: the system only suggests; the student can correct it after import.
    IF v_r_cat_id IS NULL AND v_choice_bad = 0 THEN
      SET v_r_cat_id = (SELECT id FROM categories
                         WHERE user_id IS NULL
                           AND type = v_r_type
                           AND name = IF(v_r_type = 'INCOME', 'Other Income', 'Miscellaneous')
                         LIMIT 1);
    END IF;

    IF v_choice_bad = 1 THEN
      UPDATE import_rows
         SET row_status = 'ERROR',
             error_message = 'The category chosen for this row is no longer available'
       WHERE id = v_row_id;
      SET v_errors = v_errors + 1;
    ELSEIF v_r_cat_id IS NULL THEN
      UPDATE import_rows
         SET row_status = 'ERROR',
             error_message = 'No matching category could be determined'
       WHERE id = v_row_id;
      SET v_errors = v_errors + 1;
    ELSE
      BEGIN
        DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
          UPDATE import_rows
             SET row_status = 'ERROR',
                 error_message = 'Rejected by validation (BR-02/BR-07/BR-08)'
           WHERE id = v_row_id;
          SET v_errors = v_errors + 1;
        END;

        -- No `type` is passed: the transaction type is the category type (BR-05).
        -- The row owner comes from the batch, never from the row.
        INSERT INTO transactions
          (user_id, category_id, amount, description, txn_date,
           source, import_batch_id)
        VALUES
          (v_user_id, v_r_cat_id, v_r_amount, v_r_desc, v_r_date,
           'CSV', p_batch_id);
        SET v_txn_id = LAST_INSERT_ID();

        UPDATE import_rows
           SET row_status = 'IMPORTED',
               transaction_id = v_txn_id,
               resolved_category_id = v_r_cat_id,
               error_message = NULL
         WHERE id = v_row_id;
        SET v_imported = v_imported + 1;
      END;
    END IF;
  END LOOP;
  CLOSE cur;

  SELECT COUNT(*) INTO v_total FROM import_rows WHERE batch_id = p_batch_id;

  -- The three counters below are read from the rows rather than derived from the walk
  -- above, and that is a correction rather than a style choice. The cursor only ever
  -- visits rows that were already 'VALID', so a row the preview had refused (a typo in
  -- the amount, an unreadable date) is never seen by this procedure at all - and
  -- deriving the duplicate count by subtraction reported every such row as "you already
  -- recorded this". Counting each state directly also makes the preview's own counter
  -- refresh and this commit agree by construction: there is one definition per counter
  -- and both paths use it.
  SELECT COUNT(*) INTO v_imported FROM import_rows
   WHERE batch_id = p_batch_id AND row_status = 'IMPORTED';
  SELECT COUNT(*) INTO v_errors FROM import_rows
   WHERE batch_id = p_batch_id AND row_status = 'ERROR';
  SELECT COUNT(*) INTO v_skipped FROM import_rows
   WHERE batch_id = p_batch_id AND row_status = 'DUPLICATE';

  UPDATE import_batches
     SET status = 'COMMITTED',
         committed_at = NOW(),
         total_rows = v_total,
         valid_rows = v_imported,
         error_rows = v_errors,
         duplicate_rows = v_skipped,
         imported_rows = v_imported
   WHERE id = p_batch_id;
END $$


-- ============================================================================
--  GROUP F — ACCOUNTS, PASSWORDS, ADMINISTRATION (BR-01..BR-04, UC-03, UC-22)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_create_password_reset_token — BR-04: single-use token, TTL from settings.
-- A new request invalidates every older unused token of the same account.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_password_reset_token $$
CREATE PROCEDURE sp_create_password_reset_token(
  IN p_user_id    BIGINT UNSIGNED,
  IN p_token_hash CHAR(64),
  IN p_ip         VARCHAR(45)
)
BEGIN
  DECLARE v_ttl INT DEFAULT 30;

  SELECT IFNULL(MAX(CAST(setting_value AS UNSIGNED)), 30) INTO v_ttl
    FROM system_settings WHERE setting_key = 'auth.reset_token_ttl_minutes';

  UPDATE password_reset_tokens SET used_at = NOW()
   WHERE user_id = p_user_id AND used_at IS NULL;

  INSERT INTO password_reset_tokens (user_id, token_hash, requested_ip, expires_at)
  VALUES (p_user_id, p_token_hash, p_ip, DATE_ADD(NOW(), INTERVAL v_ttl MINUTE));
END $$


-- ---------------------------------------------------------------------------
-- sp_verify_password_reset_token — UC-03 B5: the token must exist, be unused and
-- not expired. Returns NULL when it is not valid.
--
-- This is a read-only pre-check used to decide whether the "choose a new
-- password" screen may open. It does NOT consume the token: consumption happens
-- atomically in sp_complete_password_reset.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_verify_password_reset_token $$
CREATE PROCEDURE sp_verify_password_reset_token(
  IN  p_token_hash CHAR(64),
  OUT p_user_id    BIGINT UNSIGNED
)
BEGIN
  SET p_user_id = NULL;
  SELECT user_id INTO p_user_id
    FROM password_reset_tokens
   WHERE token_hash = p_token_hash
     AND used_at IS NULL
     AND expires_at > NOW()
   LIMIT 1;
END $$


-- ---------------------------------------------------------------------------
-- sp_complete_password_reset — BR-04 + the BA note on UC-03: once the password
-- has been reset, EVERY open session of that account is revoked.
--
-- Atomicity: validation and consumption are ONE statement. The UPDATE both
-- proves the token is usable and marks it used, so two requests arriving at the
-- same moment cannot both pass. InnoDB takes a row lock on the matching row: the
-- second caller blocks until the first commits, then re-evaluates its WHERE
-- against the new row version, sees used_at IS NOT NULL, matches nothing, and
-- ROW_COUNT() = 0 gets it refused.
--
-- The caller (the service layer) should run the whole password-reset flow inside
-- a single transaction: if a later step fails, the rollback also restores the
-- token, so a token is never burned by a reset that did not happen.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_complete_password_reset $$
CREATE PROCEDURE sp_complete_password_reset(
  IN  p_token_hash        CHAR(64),
  IN  p_new_password_hash VARCHAR(100),
  OUT p_user_id           BIGINT UNSIGNED
)
BEGIN
  DECLARE v_uid BIGINT UNSIGNED DEFAULT NULL;

  -- Consume and validate in one atomic step.
  UPDATE password_reset_tokens
     SET used_at = NOW()
   WHERE token_hash = p_token_hash
     AND used_at IS NULL
     AND expires_at > NOW();

  IF ROW_COUNT() = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-04: reset token is invalid, already used, or expired';
  END IF;

  -- token_hash is UNIQUE, so this reads back exactly the row just consumed.
  SELECT user_id INTO v_uid FROM password_reset_tokens WHERE token_hash = p_token_hash;

  UPDATE users
     SET password_hash = p_new_password_hash,
         token_version = token_version + 1
   WHERE id = v_uid;

  UPDATE user_sessions
     SET revoked_at = NOW(), revoked_reason = 'PASSWORD_RESET'
   WHERE user_id = v_uid AND revoked_at IS NULL;

  SET p_user_id = v_uid;
END $$


-- ---------------------------------------------------------------------------
-- sp_set_user_status — UC-22 B3/B5 + A1, BR-03. Administrators only.
-- Disabling an account revokes every open session and invalidates old JWTs.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_set_user_status $$
CREATE PROCEDURE sp_set_user_status(
  IN p_target_user_id BIGINT UNSIGNED,
  IN p_actor_id       BIGINT UNSIGNED,
  IN p_new_status     VARCHAR(10),
  IN p_ip             VARCHAR(45)
)
BEGIN
  DECLARE v_target VARCHAR(10) DEFAULT NULL;

  CALL sp_require_admin(p_actor_id);

  SELECT status INTO v_target FROM users WHERE id = p_target_user_id;

  IF v_target IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Account does not exist';
  END IF;
  IF p_new_status NOT IN ('ACTIVE', 'DISABLED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid status';
  END IF;
  IF p_actor_id = p_target_user_id AND p_new_status = 'DISABLED' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'An administrator cannot disable their own account';
  END IF;

  UPDATE users SET status = p_new_status WHERE id = p_target_user_id;

  IF p_new_status = 'DISABLED' THEN
    UPDATE user_sessions
       SET revoked_at = NOW(), revoked_reason = 'ADMIN_DISABLE'
     WHERE user_id = p_target_user_id AND revoked_at IS NULL;
    UPDATE users SET token_version = token_version + 1 WHERE id = p_target_user_id;
  END IF;

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id,
     IF(p_new_status = 'DISABLED', 'USER_DISABLED', 'USER_ENABLED'),
     'users', p_target_user_id,
     JSON_OBJECT('previousStatus', v_target, 'newStatus', p_new_status),
     p_ip);
END $$


-- ---------------------------------------------------------------------------
-- sp_admin_send_password_reset — UC-22 B4.
-- VĐ-06: an administrator only SENDS a reset link; user data is never deleted.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_send_password_reset $$
CREATE PROCEDURE sp_admin_send_password_reset(
  IN p_target_user_id BIGINT UNSIGNED,
  IN p_actor_id       BIGINT UNSIGNED,
  IN p_token_hash     CHAR(64),
  IN p_ip             VARCHAR(45)
)
BEGIN
  CALL sp_require_admin(p_actor_id);

  CALL sp_create_password_reset_token(p_target_user_id, p_token_hash, p_ip);

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id, 'PASSWORD_RESET_SENT', 'users', p_target_user_id,
     JSON_OBJECT('channel', 'email'), p_ip);
END $$


-- ---------------------------------------------------------------------------
--  ADMINISTRATOR PROCEDURES — DEFAULT CATEGORIES, ANNOUNCEMENTS, TIP TEMPLATES,
--  SYSTEM SETTINGS
--
--  These cover the administration operations the SRS / Use Case documents ask
--  for: UC-20 (default categories), UC-21 (system announcements and tip
--  templates) and VĐ-05 (adjustable business thresholds). Every procedure takes
--  `p_actor_id`, calls sp_require_admin() to look the account up in `users`, and
--  only then writes data and appends a row to admin_audit_log.
--
--  sp_require_admin() is the SINGLE authorisation gate (see its definition
--  above). There is deliberately no session-variable shortcut: a MySQL user
--  variable lives on a CONNECTION, and a connection pool reuses connections
--  across requests, so a flag left behind by one call could authorise the next.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- sp_admin_upsert_default_category — UC-20, BR-06: create or edit a default
-- category. Pass p_category_id = NULL to INSERT; pass an id to UPDATE.
-- A default category is one with user_id IS NULL.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_upsert_default_category $$
CREATE PROCEDURE sp_admin_upsert_default_category(
  IN p_actor_id      BIGINT UNSIGNED,
  IN p_category_id   BIGINT UNSIGNED,   -- NULL = insert
  IN p_name          VARCHAR(80),
  IN p_type          VARCHAR(10),
  IN p_icon          VARCHAR(50),
  IN p_color         CHAR(7),
  IN p_description   VARCHAR(255),
  IN p_sort_order    SMALLINT,
  IN p_is_active     TINYINT,
  IN p_ip            VARCHAR(45)
)
BEGIN
  DECLARE v_action      VARCHAR(20) DEFAULT 'CATEGORY_CREATED';
  DECLARE v_old_name    VARCHAR(80) DEFAULT NULL;
  DECLARE v_old_active  TINYINT     DEFAULT NULL;

  -- BR-06: the only way in. No default category belongs to an individual, so
  -- the account making the change must be an active administrator.
  CALL sp_require_admin(p_actor_id);

  IF p_category_id IS NULL THEN
    IF p_name IS NULL OR p_type NOT IN ('INCOME','EXPENSE') THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Missing name, or the category type is not valid';
    END IF;

    INSERT INTO categories
      (user_id, name, type, icon, color, description, sort_order, is_active, created_by)
    VALUES
      (NULL, p_name, p_type, p_icon, p_color, p_description,
       IFNULL(p_sort_order, 0), IFNULL(p_is_active, 1), p_actor_id);
    SET p_category_id = LAST_INSERT_ID();
  ELSE
    SELECT name, is_active INTO v_old_name, v_old_active
      FROM categories WHERE id = p_category_id AND user_id IS NULL;

    IF v_old_name IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-06: default category to update was not found';
    END IF;

    SET v_action = 'CATEGORY_UPDATED';
    UPDATE categories
       SET name        = IFNULL(p_name, name),
           type        = IFNULL(p_type, type),
           icon        = IFNULL(p_icon, icon),
           color       = IFNULL(p_color, color),
           description = IFNULL(p_description, description),
           sort_order  = IFNULL(p_sort_order, sort_order),
           is_active   = IFNULL(p_is_active, is_active)
     WHERE id = p_category_id AND user_id IS NULL;
  END IF;

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id, v_action, 'categories', p_category_id,
     JSON_OBJECT('name', p_name, 'type', p_type,
                 'previousName', v_old_name, 'previousActive', v_old_active),
     p_ip);
END $$


-- ---------------------------------------------------------------------------
-- sp_admin_create_announcement — UC-21 B1/B2: publish a system-wide announcement.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_create_announcement $$
CREATE PROCEDURE sp_admin_create_announcement(
  IN p_actor_id  BIGINT UNSIGNED,
  IN p_title     VARCHAR(150),
  IN p_body      TEXT,
  IN p_severity  VARCHAR(10),
  IN p_audience  VARCHAR(10),
  IN p_starts_at DATETIME,
  IN p_ends_at   DATETIME,
  IN p_ip        VARCHAR(45)
)
BEGIN
  DECLARE v_id BIGINT UNSIGNED DEFAULT NULL;

  CALL sp_require_admin(p_actor_id);

  IF p_title IS NULL OR p_body IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Title and body must not be empty';
  END IF;

  INSERT INTO announcements
    (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
  VALUES
    (p_title, p_body,
     IFNULL(p_severity, 'INFO'), IFNULL(p_audience, 'STUDENTS'),
     IFNULL(p_starts_at, NOW()), p_ends_at, 1, p_actor_id);
  SET v_id = LAST_INSERT_ID();

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id, 'ANNOUNCEMENT_CREATED', 'announcements', v_id,
     JSON_OBJECT('title', p_title, 'audience', p_audience, 'severity', p_severity),
     p_ip);
END $$


-- ---------------------------------------------------------------------------
-- sp_admin_set_announcement_active — UC-21: enable or disable an announcement.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_set_announcement_active $$
CREATE PROCEDURE sp_admin_set_announcement_active(
  IN p_actor_id    BIGINT UNSIGNED,
  IN p_announce_id BIGINT UNSIGNED,
  IN p_is_active   TINYINT,
  IN p_ip          VARCHAR(45)
)
BEGIN
  DECLARE v_title VARCHAR(150) DEFAULT NULL;

  CALL sp_require_admin(p_actor_id);

  SELECT title INTO v_title FROM announcements WHERE id = p_announce_id;
  IF v_title IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Announcement does not exist';
  END IF;
  IF p_is_active NOT IN (0, 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid status';
  END IF;

  UPDATE announcements SET is_active = p_is_active WHERE id = p_announce_id;

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id,
     IF(p_is_active = 1, 'ANNOUNCEMENT_ACTIVATED', 'ANNOUNCEMENT_DEACTIVATED'),
     'announcements', p_announce_id,
     JSON_OBJECT('title', v_title, 'isActive', p_is_active), p_ip);
END $$


-- ---------------------------------------------------------------------------
-- sp_admin_upsert_tip_template — UC-21 B3/B4: create or edit a saving-tip
-- template. This is the administrator "edit template content" action — it is not
-- part of the automatic tip generation flow of sp_generate_tips, so it is a
-- separate procedure with its own permission check.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_upsert_tip_template $$
CREATE PROCEDURE sp_admin_upsert_tip_template(
  IN p_actor_id         BIGINT UNSIGNED,
  IN p_template_id      BIGINT UNSIGNED,   -- NULL = insert
  IN p_code             VARCHAR(50),
  IN p_condition_type   VARCHAR(20),
  IN p_title_template   VARCHAR(200),
  IN p_body_template    TEXT,
  IN p_default_priority SMALLINT,
  IN p_is_active        TINYINT,
  IN p_ip               VARCHAR(45)
)
BEGIN
  DECLARE v_old_active TINYINT DEFAULT NULL;

  CALL sp_require_admin(p_actor_id);

  IF p_template_id IS NULL THEN
    IF p_code IS NULL OR p_title_template IS NULL OR p_body_template IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Missing tip template code, title or body';
    END IF;

    INSERT INTO tip_templates
      (code, condition_type, title_template, body_template,
       default_priority, is_active, created_by)
    VALUES
      (p_code, IFNULL(p_condition_type, 'GENERIC'),
       p_title_template, p_body_template,
       IFNULL(p_default_priority, 100), IFNULL(p_is_active, 1), p_actor_id);
    SET p_template_id = LAST_INSERT_ID();
  ELSE
    SELECT is_active INTO v_old_active FROM tip_templates WHERE id = p_template_id;
    IF v_old_active IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tip template does not exist';
    END IF;

    UPDATE tip_templates
       SET title_template   = IFNULL(p_title_template, title_template),
           body_template    = IFNULL(p_body_template, body_template),
           condition_type   = IFNULL(p_condition_type, condition_type),
           default_priority = IFNULL(p_default_priority, default_priority),
           is_active        = IFNULL(p_is_active, is_active)
     WHERE id = p_template_id;
  END IF;

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id, 'TIP_TEMPLATE_SAVED', 'tip_templates', p_template_id,
     JSON_OBJECT('code', p_code, 'isActive', p_is_active), p_ip);
END $$


-- ---------------------------------------------------------------------------
-- sp_admin_set_threshold — VĐ-05, BR-12, BR-15: adjust a business threshold.
-- Only known threshold keys may be changed, so the settings table cannot be used
-- as a free-form write target. Values must be positive numbers in a sane range.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_admin_set_threshold $$
CREATE PROCEDURE sp_admin_set_threshold(
  IN p_actor_id  BIGINT UNSIGNED,
  IN p_key       VARCHAR(60),
  IN p_value     VARCHAR(255),
  IN p_ip        VARCHAR(45)
)
BEGIN
  DECLARE v_old     VARCHAR(255) DEFAULT NULL;
  DECLARE v_allowed TINYINT      DEFAULT 0;
  DECLARE v_num     DECIMAL(10,4) DEFAULT NULL;

  CALL sp_require_admin(p_actor_id);

  IF p_key IN ('budget.near_threshold_pct',
               'budget.exceeded_threshold_pct',
               'insight.spike_threshold_pct',
               'insight.spike_baseline_months',
               'tips.max_dashboard',
               'auth.reset_token_ttl_minutes') THEN
    SET v_allowed = 1;
  END IF;
  IF v_allowed = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'This configuration key cannot be changed through this procedure';
  END IF;

  IF p_key = 'insight.spike_baseline_months' THEN
    -- The three-month window is BR-15's convention; 1..12 is allowed so that
    -- VĐ-05 still holds.
    SET v_num = CAST(p_value AS DECIMAL(10,4));
    IF v_num IS NULL OR v_num < 1 OR v_num > 12 OR v_num <> FLOOR(v_num) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Baseline month count must be a whole number from 1 to 12';
    END IF;
  ELSE
    SET v_num = CAST(p_value AS DECIMAL(10,4));
    IF v_num IS NULL OR v_num <= 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Threshold must be a positive number';
    END IF;
  END IF;

  SELECT setting_value INTO v_old FROM system_settings WHERE setting_key = p_key;
  IF v_old IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Configuration key does not exist';
  END IF;

  UPDATE system_settings
     SET setting_value = p_value, updated_by = p_actor_id
   WHERE setting_key = p_key;

  INSERT INTO admin_audit_log
    (admin_user_id, action, target_entity, target_id, detail, ip_address)
  VALUES
    (p_actor_id, 'SETTING_CHANGED', 'system_settings', NULL,
     JSON_OBJECT('key', p_key, 'oldValue', v_old, 'newValue', p_value), p_ip);
END $$


-- ============================================================================
--  GROUP G — TRANSACTIONS: SOFT DELETE, RECENT ACTIVITY, NOTIFICATIONS
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sp_soft_delete_transaction — BR-09 + BR-02: only one's own transaction can be
-- deleted. The row is NOT removed; it is only flagged so every report skips it.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_soft_delete_transaction $$
CREATE PROCEDURE sp_soft_delete_transaction(
  IN p_txn_id  BIGINT UNSIGNED,
  IN p_user_id BIGINT UNSIGNED
)
BEGIN
  DECLARE v_owner   BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_deleted TINYINT         DEFAULT NULL;

  SELECT user_id, is_deleted INTO v_owner, v_deleted
    FROM transactions WHERE id = p_txn_id;

  IF v_owner IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Transaction does not exist';
  END IF;
  IF v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: transaction belongs to another student';
  END IF;
  IF v_deleted = 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Transaction has already been deleted';
  END IF;

  UPDATE transactions SET is_deleted = 1, deleted_at = NOW() WHERE id = p_txn_id;
END $$


-- ---------------------------------------------------------------------------
-- sp_restore_transaction — UC-10 A1: restore a soft-deleted transaction.
-- BR-09: the earlier history is kept as it is; a RESTORE row is appended.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_restore_transaction $$
CREATE PROCEDURE sp_restore_transaction(
  IN p_txn_id  BIGINT UNSIGNED,
  IN p_user_id BIGINT UNSIGNED
)
BEGIN
  DECLARE v_owner BIGINT UNSIGNED DEFAULT NULL;

  SELECT user_id INTO v_owner FROM transactions WHERE id = p_txn_id;
  IF v_owner IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Transaction does not exist';
  END IF;
  IF v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: transaction belongs to another student';
  END IF;

  UPDATE transactions SET is_deleted = 0, deleted_at = NULL WHERE id = p_txn_id;
END $$


-- ---------------------------------------------------------------------------
-- sp_touch_recent_activity — UC-26: record or refresh the "recently viewed /
-- recently edited" marker. Each (student, transaction, action) triple keeps one
-- row; a later call only moves the timestamp.
--
-- The transaction must belong to the student. The foreign key on transaction_id
-- only proves the row exists, not who owns it, so without this check a student
-- could create recent-activity rows that point at somebody else's transaction —
-- and the recent-activity list would then expose that transaction.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_touch_recent_activity $$
CREATE PROCEDURE sp_touch_recent_activity(
  IN p_user_id BIGINT UNSIGNED,
  IN p_txn_id  BIGINT UNSIGNED,
  IN p_action  VARCHAR(10)
)
BEGIN
  DECLARE v_owner BIGINT UNSIGNED DEFAULT NULL;

  IF p_action NOT IN ('VIEWED', 'EDITED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid recent-activity action';
  END IF;

  SELECT user_id INTO v_owner FROM transactions WHERE id = p_txn_id;
  IF v_owner IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Transaction does not exist';
  END IF;
  IF v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: cannot record activity for a transaction owned by another student';
  END IF;

  INSERT INTO recent_activity (user_id, transaction_id, action, occurred_at)
  VALUES (p_user_id, p_txn_id, p_action, NOW())
  ON DUPLICATE KEY UPDATE occurred_at = NOW();
END $$


-- ---------------------------------------------------------------------------
-- sp_flag_transaction — UC-24: set or clear the anomaly flag on one of the
-- student's OWN transactions.
--
-- The three flag columns (is_flagged, flag_type, flag_note) have existed since
-- the schema was written, together with ix_txn_flagged and the two
-- `anomaly.*` settings, but no procedure or view read or wrote them: UC-24 is
-- module 12 and until now it was not built. This procedure is that write path,
-- and it is a procedure rather than an UPDATE issued by the application for the
-- same reason sp_touch_recent_activity is: the ownership check belongs in the
-- database. `fk_txn_user` proves the row exists, not whose it is, so without the
-- check below one student could flag another student's record.
--
-- It is NOT reachable from the API as a client-supplied flag. The API only ever
-- passes a value its own detector computed - see the note on the endpoint.
--
-- `p_flag_type = 'NONE'` is the clearing form: it sets is_flagged = 0 and the
-- note to NULL, so "not flagged" has exactly one representation and a stale note
-- cannot survive an unflag.
--
-- The UPDATE fires trg_transactions_after_update, which appends a history row
-- when - and only when - one of the three columns actually changed (BR-09). A
-- rescan that reaches the same conclusion therefore writes nothing at all, and
-- one that flips a flag leaves a record of the system's own decision.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_flag_transaction $$
CREATE PROCEDURE sp_flag_transaction(
  IN p_txn_id    BIGINT UNSIGNED,
  IN p_user_id   BIGINT UNSIGNED,
  IN p_flag_type VARCHAR(20),
  IN p_flag_note VARCHAR(255)
)
BEGIN
  DECLARE v_owner BIGINT UNSIGNED DEFAULT NULL;

  IF p_flag_type NOT IN ('NONE', 'DUPLICATE', 'UNUSUAL_AMOUNT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid anomaly flag type';
  END IF;

  SELECT user_id INTO v_owner FROM transactions WHERE id = p_txn_id;
  IF v_owner IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Transaction does not exist';
  END IF;
  IF v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: cannot flag a transaction owned by another student';
  END IF;

  UPDATE transactions
     SET is_flagged = IF(p_flag_type = 'NONE', 0, 1),
         flag_type  = p_flag_type,
         flag_note  = IF(p_flag_type = 'NONE', NULL, p_flag_note)
   WHERE id = p_txn_id;
END $$

-- ---------------------------------------------------------------------------
-- sp_mark_notification_read — UC-14 B4: only one's own notification can be marked
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_mark_notification_read $$
CREATE PROCEDURE sp_mark_notification_read(
  IN p_notification_id BIGINT UNSIGNED,
  IN p_user_id         BIGINT UNSIGNED
)
BEGIN
  UPDATE notifications
     SET is_read = 1, read_at = NOW()
   WHERE id = p_notification_id
     AND user_id = p_user_id
     AND is_read = 0;
END $$


-- ---------------------------------------------------------------------------
-- sp_seed_dim_month — fills the month dimension over [p_from, p_to].
-- Required so BR-17 always returns six rows, even for months with no
-- transactions at all.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_seed_dim_month $$
CREATE PROCEDURE sp_seed_dim_month(IN p_from DATE, IN p_to DATE)
BEGIN
  DECLARE v_cur DATE;
  SET v_cur = CAST(DATE_FORMAT(IFNULL(p_from, '2023-01-01'), '%Y-%m-01') AS DATE);
  WHILE v_cur <= IFNULL(p_to, '2030-12-01') DO
    INSERT INTO dim_month (month_start, month_end, year_no, month_no, label_short)
    VALUES (v_cur, LAST_DAY(v_cur), YEAR(v_cur), MONTH(v_cur),
            DATE_FORMAT(v_cur, '%Y-%m'))
    ON DUPLICATE KEY UPDATE month_end = LAST_DAY(v_cur);
    SET v_cur = DATE_ADD(v_cur, INTERVAL 1 MONTH);
  END WHILE;
END $$

DELIMITER ;

-- ##########################################################################
--  PART 4/6 - 04_triggers.sql
-- ##########################################################################

-- ============================================================================
--  CAMPUS COIN — 04_triggers.sql
--  14 TRIGGERS enforcing the business rules a CHECK constraint cannot express,
--  because they have to read several tables (BR-02, BR-05, BR-06, BR-07, BR-08,
--  BR-09, BR-13).
--
--  With this layer in place, the rules hold no matter where the data comes from:
--  the web application, a SQL script, or a database tool.
--
--  IMPORTANT MySQL NOTE: triggers are NOT fired by foreign-key cascade deletes.
--  Deleting an account from `users` therefore still cleans up its related rows
--  normally.
--
--  AUTHORISATION NOTE: these triggers deliberately contain no session-variable
--  switch. A MySQL user variable belongs to a CONNECTION and a connection pool
--  reuses connections across requests, so a flag set by one call could authorise
--  the next one. Authorisation is decided in exactly one place — the procedures
--  of 03_procedures.sql, through sp_require_admin(p_actor_id) — and a BEFORE
--  trigger only enforces integrity rules that must hold for every caller.
-- ============================================================================

USE campuscoin;

DELIMITER $$

-- ============================================================================
--  CATEGORIES — BR-06, BR-07, UC-06, UC-20
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Only an active administrator may create a default category, and a personal
-- category may not reuse the name of a default category of the same type —
-- otherwise it would be ambiguous which one to prefer when displaying.
--
-- This check CAN live in a trigger because "who is creating this row" is part of
-- the row itself (NEW.created_by), so no out-of-band state is needed.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_categories_before_insert $$
CREATE TRIGGER trg_categories_before_insert
BEFORE INSERT ON categories FOR EACH ROW
BEGIN
  IF NEW.user_id IS NULL AND
     (SELECT COUNT(*) FROM users u
       WHERE u.id = NEW.created_by
         AND u.role = 'ADMIN'
         AND u.status = 'ACTIVE') = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-06: only an active administrator can create a default category';
  END IF;

  IF NEW.user_id IS NOT NULL AND
     (SELECT COUNT(*) FROM categories d
       WHERE d.user_id IS NULL AND d.type = NEW.type AND d.name = NEW.name) > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-06: a default category with the same name and type already exists';
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- Two integrity rules that hold for EVERY caller, including an administrator.
--
-- 1. A category may not change scope: a default category (user_id IS NULL) can
--    never become personal, and vice versa. Scope decides who is allowed to see
--    and edit the row, so moving it would move ownership of existing data.
--
-- 2. A category may not change its type once anything references it. The whole
--    design takes `categories.type` as the single source of truth for the type
--    of a transaction (see 01_schema.sql), so flipping it would silently rewrite
--    past reports: every recorded expense would read back as income. Budgets
--    (BR-11) and recurring rules (BR-05) depend on the type in the same way, so
--    all three referencing tables are checked — not just `transactions`.
--    The rule "no type change once referenced" is not in the SRS or the Use Case
--    document; it is a design decision that protects the correctness of history.
--    To genuinely move a category to another type, create a new category and
--    move the data across.
--
-- WHO MAY EDIT A DEFAULT CATEGORY (BR-06) is decided in
-- sp_admin_upsert_default_category, which calls sp_require_admin(p_actor_id) to
-- look the account up in `users`. It is deliberately not decided here: a BEFORE
-- UPDATE trigger cannot see which account issued the statement, and the only way
-- to tell it would be a session variable — the mechanism that was removed
-- because a pooled connection can leak it into an unrelated request.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_categories_before_update $$
CREATE TRIGGER trg_categories_before_update
BEFORE UPDATE ON categories FOR EACH ROW
BEGIN
  IF (OLD.user_id IS NULL) <> (NEW.user_id IS NULL) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-06: a category cannot change scope between default and personal';
  END IF;

  IF NOT (OLD.type <=> NEW.type) AND (
       (SELECT COUNT(*) FROM transactions    t WHERE t.category_id = OLD.id) > 0
    OR (SELECT COUNT(*) FROM budgets         b WHERE b.category_id = OLD.id) > 0
    OR (SELECT COUNT(*) FROM recurring_rules r WHERE r.category_id = OLD.id) > 0
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A category referenced by transactions, budgets or recurring rules cannot change its type; create a new category instead';
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- BR-07: a category that already has a budget must NOT be hard-deleted. The
-- correct way is is_active = 0, which "retires" it.
-- (Transactions are already blocked by the ON DELETE RESTRICT foreign key.)
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_categories_before_delete $$
CREATE TRIGGER trg_categories_before_delete
BEFORE DELETE ON categories FOR EACH ROW
BEGIN
  IF (SELECT COUNT(*) FROM budgets b WHERE b.category_id = OLD.id) > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: this category has a budget; disable it instead of deleting it';
  END IF;
END $$


-- ============================================================================
--  BUDGETS — BR-11, UC-13
-- ============================================================================

DROP TRIGGER IF EXISTS trg_budgets_before_insert $$
CREATE TRIGGER trg_budgets_before_insert
BEFORE INSERT ON budgets FOR EACH ROW
BEGIN
  CALL sp_validate_budget(NEW.user_id, NEW.category_id);
END $$

DROP TRIGGER IF EXISTS trg_budgets_before_update $$
CREATE TRIGGER trg_budgets_before_update
BEFORE UPDATE ON budgets FOR EACH ROW
BEGIN
  CALL sp_validate_budget(NEW.user_id, NEW.category_id);
END $$


-- ============================================================================
--  RECURRING_RULES — UC-09, BR-02, BR-05, BR-07
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Validate WHEN THE RULE IS CREATED OR EDITED: the category must exist, be a
-- default category or one of the student's own, still be active, and the rule's
-- `type` must match `categories.type`.
--
-- Waiting until the scheduler posts a transaction would leave a broken rule
-- sitting in the table for days before anyone noticed, and would break the
-- catch-up loop of sp_post_recurring_transactions.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_recurring_rules_before_insert $$
CREATE TRIGGER trg_recurring_rules_before_insert
BEFORE INSERT ON recurring_rules FOR EACH ROW
BEGIN
  CALL sp_validate_recurring_rule(NEW.user_id, NEW.category_id, NEW.type);
END $$

DROP TRIGGER IF EXISTS trg_recurring_rules_before_update $$
CREATE TRIGGER trg_recurring_rules_before_update
BEFORE UPDATE ON recurring_rules FOR EACH ROW
BEGIN
  -- The scheduler only touches next_run_date / last_run_date / status; repeating
  -- the check here is still cheap and correct, and it also catches an attempt to
  -- point an existing rule at a different category or type.
  CALL sp_validate_recurring_rule(NEW.user_id, NEW.category_id, NEW.type);
END $$


-- ============================================================================
--  TRANSACTIONS — BR-02, BR-08, BR-09, BR-13, UC-07, UC-10, UC-14
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Validation before a write: the category must exist, belong to the student
-- writing the row (or be a default category), still be active, the date may not
-- be in the future, and an AI-suggested category may not belong to somebody else
-- (BR-13). The two optional references — recurring_rule_id and import_batch_id —
-- must belong to the same student (BR-02).
--
-- There is NO BR-05 check here any more: `transactions` has no `type` column, so
-- "the transaction type must match the category type" is true by construction.
--
-- require_active = 1 on INSERT; skipped on UPDATE so that editing an old
-- transaction that points at a since-disabled category is still possible.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_transactions_before_insert $$
CREATE TRIGGER trg_transactions_before_insert
BEFORE INSERT ON transactions FOR EACH ROW
BEGIN
  CALL sp_validate_transaction(NEW.user_id, NEW.category_id, NEW.txn_date,
                               NEW.source, 1, NEW.ai_suggested_category_id,
                               NEW.recurring_rule_id, NEW.import_batch_id);
END $$

DROP TRIGGER IF EXISTS trg_transactions_before_update $$
CREATE TRIGGER trg_transactions_before_update
BEFORE UPDATE ON transactions FOR EACH ROW
BEGIN
  CALL sp_validate_transaction(NEW.user_id, NEW.category_id, NEW.txn_date,
                               NEW.source, 0, NEW.ai_suggested_category_id,
                               NEW.recurring_rule_id, NEW.import_batch_id);
END $$


-- ---------------------------------------------------------------------------
-- BR-09: hard-deleting a transaction is forbidden. Use
-- sp_soft_delete_transaction instead.
--
-- To clean data manually during development:
--   DROP TRIGGER trg_transactions_before_delete;
-- then re-create it afterwards by re-running db/04_triggers.sql.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_transactions_before_delete $$
CREATE TRIGGER trg_transactions_before_delete
BEFORE DELETE ON transactions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'BR-09: transactions cannot be hard-deleted; use soft delete instead';
END $$


-- ---------------------------------------------------------------------------
-- After INSERT of a transaction:
--   (1) UC-07 B8, BR-09 — write the creation row into transaction_history
--   (2) UC-14 B1 — check the budget thresholds straight away
--
-- The history row captures the FULL business payload (BR-09, VĐ-09). The `type`
-- field is still written even though the table no longer has that column: it is
-- a SNAPSHOT of the category type at the moment the transaction was created, so
-- the log is not rewritten if the category is later renamed or retyped.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_transactions_after_insert $$
CREATE TRIGGER trg_transactions_after_insert
AFTER INSERT ON transactions FOR EACH ROW
BEGIN
  DECLARE v_type VARCHAR(10) DEFAULT NULL;

  -- Read the category type into a variable: JSON_OBJECT does not accept a
  -- subquery directly in its argument list.
  SELECT c.type INTO v_type FROM categories c WHERE c.id = NEW.category_id;

  INSERT INTO transaction_history
    (transaction_id, action, changed_by, changed_fields, new_values)
  VALUES
    (NEW.id, 'CREATE', NEW.user_id, 'created',
     JSON_OBJECT(
       'categoryId',             NEW.category_id,
       'type',                   v_type,
       'amount',                 NEW.amount,
       'description',            NEW.description,
       'txnDate',                NEW.txn_date,
       'source',                 NEW.source,
       'aiSuggestedCategoryId',  NEW.ai_suggested_category_id,
       'aiConfidence',           NEW.ai_confidence,
       'aiOverridden',           NEW.ai_overridden,
       'recurringRuleId',        NEW.recurring_rule_id,
       'importBatchId',          NEW.import_batch_id,
       'isFlagged',              NEW.is_flagged,
       'flagType',               NEW.flag_type,
       'flagNote',               NEW.flag_note,
       'isDeleted',              NEW.is_deleted,
       'deletedAt',              NEW.deleted_at));

  -- Called unconditionally: if that month has no budget for this category, the
  -- procedure exits right after one indexed query. That keeps the trigger from
  -- having to know the category type (budgets only exist on expense categories —
  -- BR-11).
  IF NEW.is_deleted = 0 THEN
    CALL sp_check_budget_alerts(
      NEW.user_id, NEW.category_id,
      CAST(DATE_FORMAT(NEW.txn_date, '%Y-%m-01') AS DATE));
  END IF;
END $$


-- ---------------------------------------------------------------------------
-- After UPDATE of a transaction: write the change log (BR-09) and recompute the
-- budget.
--
-- History is written only when a column actually changed. That matters because
-- the application layer may touch the row without editing anything (for example
-- when refreshing a "recently viewed" marker); writing unconditionally would
-- fill UAT-06 with noise instead of the expected number of history rows.
--
-- The before/after snapshots are as complete as the CREATE row, so BR-09
-- "preserve the history" has enough data to rebuild the previous state.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_transactions_after_update $$
CREATE TRIGGER trg_transactions_after_update
AFTER UPDATE ON transactions FOR EACH ROW
BEGIN
  DECLARE v_action VARCHAR(10)  DEFAULT 'UPDATE';
  DECLARE v_fields VARCHAR(500) DEFAULT NULL;
  DECLARE v_old_type VARCHAR(10) DEFAULT NULL;
  DECLARE v_new_type VARCHAR(10) DEFAULT NULL;

  IF OLD.is_deleted = 0 AND NEW.is_deleted = 1 THEN
    SET v_action = 'DELETE';
  ELSEIF OLD.is_deleted = 1 AND NEW.is_deleted = 0 THEN
    SET v_action = 'RESTORE';
  END IF;

  SET v_fields = CONCAT_WS(',',
    IF(NOT (OLD.category_id             <=> NEW.category_id),             'categoryId',            NULL),
    IF(NOT (OLD.amount                  <=> NEW.amount),                  'amount',                NULL),
    IF(NOT (OLD.description             <=> NEW.description),             'description',           NULL),
    IF(NOT (OLD.txn_date                <=> NEW.txn_date),                'txnDate',               NULL),
    IF(NOT (OLD.source                  <=> NEW.source),                  'source',                NULL),
    IF(NOT (OLD.ai_suggested_category_id<=> NEW.ai_suggested_category_id),'aiSuggestedCategoryId', NULL),
    IF(NOT (OLD.ai_confidence           <=> NEW.ai_confidence),           'aiConfidence',          NULL),
    IF(NOT (OLD.ai_overridden           <=> NEW.ai_overridden),           'aiOverridden',          NULL),
    IF(NOT (OLD.recurring_rule_id       <=> NEW.recurring_rule_id),       'recurringRuleId',       NULL),
    IF(NOT (OLD.import_batch_id         <=> NEW.import_batch_id),         'importBatchId',         NULL),
    IF(NOT (OLD.is_flagged              <=> NEW.is_flagged),              'isFlagged',             NULL),
    IF(NOT (OLD.flag_type               <=> NEW.flag_type),               'flagType',              NULL),
    IF(NOT (OLD.flag_note               <=> NEW.flag_note),               'flagNote',              NULL),
    IF(NOT (OLD.is_deleted              <=> NEW.is_deleted),              'isDeleted',             NULL),
    IF(NOT (OLD.deleted_at              <=> NEW.deleted_at),              'deletedAt',             NULL));

  IF v_action <> 'UPDATE' OR v_fields IS NOT NULL THEN
    SELECT c.type INTO v_old_type FROM categories c WHERE c.id = OLD.category_id;
    SELECT c.type INTO v_new_type FROM categories c WHERE c.id = NEW.category_id;

    INSERT INTO transaction_history
      (transaction_id, action, changed_by, changed_fields, old_values, new_values)
    VALUES
      (NEW.id, v_action, NEW.user_id, IFNULL(v_fields, ''),
       JSON_OBJECT(
         'categoryId',            OLD.category_id,
         'type',                  v_old_type,
         'amount',                OLD.amount,
         'description',           OLD.description,
         'txnDate',               OLD.txn_date,
         'source',                OLD.source,
         'aiSuggestedCategoryId', OLD.ai_suggested_category_id,
         'aiConfidence',          OLD.ai_confidence,
         'aiOverridden',          OLD.ai_overridden,
         'recurringRuleId',       OLD.recurring_rule_id,
         'importBatchId',         OLD.import_batch_id,
         'isFlagged',             OLD.is_flagged,
         'flagType',              OLD.flag_type,
         'flagNote',              OLD.flag_note,
         'isDeleted',             OLD.is_deleted,
         'deletedAt',             OLD.deleted_at),
       JSON_OBJECT(
         'categoryId',            NEW.category_id,
         'type',                  v_new_type,
         'amount',                NEW.amount,
         'description',           NEW.description,
         'txnDate',               NEW.txn_date,
         'source',                NEW.source,
         'aiSuggestedCategoryId', NEW.ai_suggested_category_id,
         'aiConfidence',          NEW.ai_confidence,
         'aiOverridden',          NEW.ai_overridden,
         'recurringRuleId',       NEW.recurring_rule_id,
         'importBatchId',         NEW.import_batch_id,
         'isFlagged',             NEW.is_flagged,
         'flagType',              NEW.flag_type,
         'flagNote',              NEW.flag_note,
         'isDeleted',             NEW.is_deleted,
         'deletedAt',             NEW.deleted_at));
  END IF;

  -- Editing a transaction must also recompute the budget, including when the
  -- transaction is moved to a different category.
  IF NEW.is_deleted = 0 THEN
    CALL sp_check_budget_alerts(
      NEW.user_id, NEW.category_id,
      CAST(DATE_FORMAT(NEW.txn_date, '%Y-%m-01') AS DATE));
  END IF;
END $$


DELIMITER ;

-- ============================================================================
--  BOOKMARKS — BR-02, UC-19
-- ============================================================================

DELIMITER $$

-- ---------------------------------------------------------------------------
-- A student may only bookmark their OWN tip or insight.
--
-- The foreign key only proves that `tip_id` / `insight_id` points at a row that
-- exists; it knows nothing about who owns that row. Without these two triggers a
-- student could bookmark — and thereby read the content of — another student's
-- tip, breaking the data isolation of BR-02.
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_bookmarks_before_insert $$
CREATE TRIGGER trg_bookmarks_before_insert
BEFORE INSERT ON bookmarks FOR EACH ROW
BEGIN
  IF NEW.item_type = 'TIP' THEN
    IF (SELECT COUNT(*) FROM user_tips t
         WHERE t.id = NEW.tip_id AND t.user_id = NEW.user_id) = 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: you can only bookmark your own tips';
    END IF;
  ELSEIF NEW.item_type = 'INSIGHT' THEN
    IF (SELECT COUNT(*) FROM insights i
         WHERE i.id = NEW.insight_id AND i.user_id = NEW.user_id) = 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: you can only bookmark your own insights';
    END IF;
  END IF;
END $$

DROP TRIGGER IF EXISTS trg_bookmarks_before_update $$
CREATE TRIGGER trg_bookmarks_before_update
BEFORE UPDATE ON bookmarks FOR EACH ROW
BEGIN
  IF NEW.item_type = 'TIP' THEN
    IF (SELECT COUNT(*) FROM user_tips t
         WHERE t.id = NEW.tip_id AND t.user_id = NEW.user_id) = 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: you can only bookmark your own tips';
    END IF;
  ELSEIF NEW.item_type = 'INSIGHT' THEN
    IF (SELECT COUNT(*) FROM insights i
         WHERE i.id = NEW.insight_id AND i.user_id = NEW.user_id) = 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-02: you can only bookmark your own insights';
    END IF;
  END IF;
END $$

DELIMITER ;

-- ##########################################################################
--  PART 5/6 - 05_seed.sql
-- ##########################################################################

-- ============================================================================
--  CAMPUS COIN — 05_seed.sql
--  REQUIRED seed data so the system works immediately after installation:
--    - System settings (business thresholds, currency, time zone)
--    - The month dimension used by the six-month report (BR-17)
--    - An account for EVERY user type (mandatory in SRS §1.9)
--    - 12 default categories (5 income + 7 expense) per SRS §1.6
--    - Saving tip templates (UC-18, UC-21)
--    - Welcome announcements (UC-21)
--
--  This file contains NO sample transactions. For demo data, run 06_demo.sql
--  afterwards.
--
--  Everything stored here is system-generated content and is written in English:
--  setting descriptions, category names, tip template titles and bodies, and
--  announcement text are all shown to users.
-- ============================================================================

USE campuscoin;


-- ============================================================================
--  1. SYSTEM SETTINGS
--  VĐ-05, VĐ-08, VĐ-10, VĐ-12: every business threshold lives here so an
--  administrator can retune it without editing source code or restarting.
-- ============================================================================
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
 ('app.currency',                  'USD',              'STRING',  'Currency used system-wide'),
 ('app.currency_symbol',           '$',                'STRING',  'Symbol shown on the dashboard, reports and tips'),
 ('app.timezone',                  'Asia/Ho_Chi_Minh', 'STRING',  'Time zone fixing the day / week / month boundaries'),
 ('app.week_start',                'MONDAY',           'STRING',  'Weeks start on Monday'),
 ('budget.near_threshold_pct',     '80',               'DECIMAL', 'BR-12: "approaching budget" threshold (%)'),
 ('budget.exceeded_threshold_pct', '100',              'DECIMAL', 'BR-12: "budget exceeded" threshold (%)'),
 ('insight.spike_threshold_pct',   '30',               'DECIMAL', 'BR-15: rise treated as abnormal (%)'),
 ('insight.spike_baseline_months', '3',                'INT',     'BR-15: number of months averaged for the baseline'),
 ('tips.max_dashboard',            '3',                'INT',     'BR-14: number of tips shown on the dashboard'),
 ('auth.reset_token_ttl_minutes',  '30',               'INT',     'BR-04: password reset token lifetime (minutes)'),
 ('auth.session_ttl_minutes',      '120',              'INT',     'Lifetime of one sign-in session (minutes)'),
 ('auth.max_login_attempts',       '5',                'INT',     'Failed sign-in attempts allowed before a temporary lock'),
 ('anomaly.duplicate_window_days', '3',                'INT',     'UC-24: window for detecting suspected duplicate transactions (days)'),
 ('anomaly.unusual_multiplier',    '3',                'DECIMAL', 'UC-24: multiple of the average treated as unusual'),
 ('ai.enabled',                    'true',             'BOOLEAN', 'Turns every AI feature on or off (UC-08, UC-17)'),
 ('ai.send_aggregates_only',       'true',             'BOOLEAN', 'Send aggregates only to the AI service, never student identifiers');


-- ============================================================================
--  2. MONTH DIMENSION (BR-17)
--  Must be populated so v_monthly_income_expense_6m returns six rows even for
--  months in which a student recorded nothing.
-- ============================================================================
CALL sp_seed_dim_month('2023-01-01', '2030-12-01');


-- ============================================================================
--  3. ACCOUNTS — SRS §1.9 requires sign-in details for every user type, with
--  passwords.
--
--  Passwords are bcrypt hashes (cost 10, $2y$). Spring Security's
--  BCryptPasswordEncoder.matches() accepts both the $2a$ and $2y$ prefixes.
--
--  Credentials (see also docs/CREDENTIALS.md):
--    admin@campuscoin.edu                / Admin@123
--    an.nguyen@student.campuscoin.edu    / Student@123
--    binh.tran@student.campuscoin.edu    / Student@123
--
--  ⚠ CHANGE ALL OF THESE PASSWORDS BEFORE ANY REAL DEPLOYMENT.
-- ============================================================================
INSERT INTO users
  (email, password_hash, full_name, role, academic_year,
   monthly_allowance_baseline, monthly_savings_goal, currency, status)
VALUES
 ('admin@campuscoin.edu',
  '$2y$10$GRALQlXS7PZicW3k0sEo6uhaRsGS4WeZ1nMfD46o262Z27B0ZqYNa',
  'System Administrator', 'ADMIN', NULL, 0.00, 0.00, 'USD', 'ACTIVE'),

 ('an.nguyen@student.campuscoin.edu',
  '$2y$10$B3/WANQCknajrDNeJMa9g.coT.Cap1t6q0GKmo6k6q1m5KgfW9QJ6',
  'Alex Nguyen', 'STUDENT', 'Year 3', 200.00, 100.00, 'USD', 'ACTIVE'),

 ('binh.tran@student.campuscoin.edu',
  '$2y$10$B3/WANQCknajrDNeJMa9g.coT.Cap1t6q0GKmo6k6q1m5KgfW9QJ6',
  'Bella Tran', 'STUDENT', 'Year 1', 150.00, 50.00, 'USD', 'ACTIVE');

SET @admin_id = (SELECT id FROM users WHERE email = 'admin@campuscoin.edu');


-- ============================================================================
--  4. DEFAULT CATEGORIES — SRS §1.6 Category Management
--  user_id = NULL means a system-wide default category that only an
--  administrator may edit (BR-06). Students can create their own categories.
-- ============================================================================
INSERT INTO categories (user_id, name, type, icon, color, sort_order, created_by) VALUES
 -- 5 income categories
 (NULL, 'Allowance',        'INCOME',  'wallet',           '#22C55E',  1, @admin_id),
 (NULL, 'Part-time Job',    'INCOME',  'briefcase',        '#16A34A',  2, @admin_id),
 (NULL, 'Scholarship',      'INCOME',  'graduation-cap',   '#0EA5E9',  3, @admin_id),
 (NULL, 'Gift',             'INCOME',  'gift',             '#8B5CF6',  4, @admin_id),
 (NULL, 'Other Income',     'INCOME',  'plus-circle',      '#64748B',  5, @admin_id),
 -- 7 expense categories
 (NULL, 'Food',             'EXPENSE', 'utensils',         '#F97316', 10, @admin_id),
 (NULL, 'Transport',        'EXPENSE', 'bus',              '#3B82F6', 11, @admin_id),
 (NULL, 'Hostel/Rent',      'EXPENSE', 'home',             '#EF4444', 12, @admin_id),
 (NULL, 'Academics',        'EXPENSE', 'book-open',        '#6366F1', 13, @admin_id),
 (NULL, 'Subscriptions',    'EXPENSE', 'repeat',           '#EC4899', 14, @admin_id),
 (NULL, 'Entertainment',    'EXPENSE', 'film',             '#A855F7', 15, @admin_id),
 (NULL, 'Miscellaneous',    'EXPENSE', 'more-horizontal',  '#64748B', 16, @admin_id);


-- ============================================================================
--  5. SAVING TIP TEMPLATES (UC-18 B2, UC-21 B3)
--  The text may contain {..} placeholders that fn_render_template() substitutes
--  when a tip is generated. default_priority is only a tie-breaker when two tips
--  have the same potential saving; the real ranking comes from potential_saving
--  (BR-14).
-- ============================================================================
INSERT INTO tip_templates
  (code, condition_type, title_template, body_template, condition_params,
   default_priority, created_by)
VALUES
 ('OVER_BUDGET', 'OVER_BUDGET',
  '{category_name} is over budget',
  'You have spent {amount} on {category_name}, which is {pct}% of your {limit} limit. Try pausing this category for the rest of the month and switching to a cheaper option.',
  JSON_OBJECT('thresholdPct', 100), 10, @admin_id),

 ('NEAR_BUDGET', 'NEAR_BUDGET',
  '{category_name} has used {pct}% of its budget',
  'You have {limit} left minus what you have already spent ({amount}) on {category_name}. Set a weekly cap so you do not go over {limit} this month.',
  JSON_OBJECT('thresholdPct', 80), 20, @admin_id),

 ('CATEGORY_SPIKE', 'CATEGORY_SPIKE',
  '{category_name} spending is up {pct_change}%',
  'This month you spent {amount} on {category_name}, against your usual {baseline_avg}. A rise of {pct_change}% is worth a look - try setting a weekly cap for this category.',
  JSON_OBJECT('thresholdPct', 30, 'baselineMonths', 3), 30, @admin_id),

 ('NO_BUDGET_SET', 'NO_BUDGET_SET',
  'No budget set for {category_name}',
  '{category_name} is one of your largest expenses ({amount}) but has no limit yet. Set a budget so the system can warn you early when you overspend.',
  JSON_OBJECT(), 40, @admin_id),

 ('SAVINGS_GOAL_AT_RISK', 'SAVINGS_GOAL_AT_RISK',
  'Your savings goal is at risk',
  'Your income minus spending is currently {amount}, below your goal of {limit}. Income this month is {baseline_avg}. Consider cutting one non-essential expense to get back on target.',
  JSON_OBJECT(), 50, @admin_id),

 ('LOW_SAVINGS_RATE', 'LOW_SAVINGS_RATE',
  'Your savings rate is still low',
  'You kept only {pct}% of your income this month. A reasonable target for a student is 15-20%. Start by moving a fixed amount into savings as soon as your allowance arrives.',
  JSON_OBJECT('targetPct', 20), 60, @admin_id),

 ('GENERIC', 'GENERIC',
  'Start your spending control journey',
  'Record your first income and expense. After a few transactions the system compares them with your own habits and offers tips that fit you.',
  JSON_OBJECT(), 99, @admin_id);


-- ============================================================================
--  6. WELCOME ANNOUNCEMENTS (UC-21 B1/B2)
-- ============================================================================
INSERT INTO announcements
  (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
VALUES
 ('Welcome to Campus Coin',
  'Record every income and expense to get saving tips based on your own habits. All analysis is a suggestion only, not financial advice.',
  'INFO', 'STUDENTS', NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY), 1, @admin_id),

 ('Import your past spending from a CSV file',
  'You can upload a CSV file to bring your earlier spending into the system. Every row is previewed before anything is written to your ledger.',
  'SUCCESS', 'STUDENTS', NOW(), DATE_ADD(NOW(), INTERVAL 60 DAY), 1, @admin_id);


-- ============================================================================
--  7. SEED VERIFICATION
-- ============================================================================
SELECT 'COMPONENT' AS `item`, 'COUNT' AS `value`
UNION ALL SELECT 'Accounts',              CAST(COUNT(*) AS CHAR) FROM users
UNION ALL SELECT 'Default categories',    CAST(COUNT(*) AS CHAR) FROM categories WHERE user_id IS NULL
UNION ALL SELECT 'Tip templates',         CAST(COUNT(*) AS CHAR) FROM tip_templates
UNION ALL SELECT 'Announcements',         CAST(COUNT(*) AS CHAR) FROM announcements
UNION ALL SELECT 'System settings',       CAST(COUNT(*) AS CHAR) FROM system_settings
UNION ALL SELECT 'Months in dimension',   CAST(COUNT(*) AS CHAR) FROM dim_month;

-- ##########################################################################
--  PART 6/6 - 06_demo.sql
-- ##########################################################################

-- ============================================================================
--  CAMPUS COIN — 06_demo.sql
--  DEMO DATA (optional) — pre-builds a realistic spending history for BOTH
--  student accounts, so the dashboard, charts, reports, budgets, tips and the
--  6-month trend all have figures the moment the web app opens.
--
--  Alex Nguyen (an.nguyen@student.campuscoin.edu) — the primary demo account.
--  The scenario is shaped so it can be verified directly on screen:
--    • UAT-07 (BR-12) — Food budget 30, spend 24 ⇒ exactly ONE "approaching"
--      alert. Spending 7 more then raises exactly ONE "exceeded" alert, with no
--      repeat.
--    • BR-15 / UC-25 — Entertainment this month is 25 against a three-month
--      baseline of 11 ⇒ up 127%, flagged as abnormal, and the matching tip is
--      generated.
--    • BR-17 / UAT-09 — the six-month report always returns six rows, even for
--      empty months.
--
--  Bella Tran (binh.tran@student.campuscoin.edu) — the second account, for
--  OWNERSHIP ISOLATION testing (BR-02). She has a deliberately different profile,
--  budget and category mix, her own personal category, and six months of history
--  with one near-empty month so the trend chart has a trough as well as peaks.
--  Her Food budget is EXCEEDED, which Alex's is not, so both alert states are
--  observable in the running system without editing any data. It also carries the
--  NEAR alert that preceded it: crossing 80% is what fires the near alert, and
--  BR-12 keeps that row rather than replacing it when the budget is later
--  exceeded.
--
--  Run this file AFTER 05_seed.sql. To get back to an empty database, remove the
--  demo rows and re-run 05_seed.sql.
--
--  Transaction descriptions here are sample free text, written in English like
--  the rest of the seed content so a Vietnamese-text scan of the SQL source
--  comes back clean.
-- ============================================================================

USE campuscoin;

SET @u1 = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu');
SET @m0 = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE);   -- current month
SET @m1 = DATE_SUB(@m0, INTERVAL 1 MONTH);
SET @m2 = DATE_SUB(@m0, INTERVAL 2 MONTH);
SET @m3 = DATE_SUB(@m0, INTERVAL 3 MONTH);

SET @i_allow = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Allowance');
SET @i_part  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Part-time Job');
SET @i_schol = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Scholarship');
SET @e_food  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Food');
SET @e_tran  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Transport');
SET @e_host  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Hostel/Rent');
SET @e_acad  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Academics');
SET @e_subs  = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Subscriptions');
SET @e_ent   = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Entertainment');


-- ============================================================================
--  1. CURRENT-MONTH BUDGETS (UC-13)
--  Food = 30 is the UAT-07 number: spending 24 reaches exactly 80% ⇒ one
--  "approaching" alert.
--  Hostel/Rent = 160 so that the 120 payment sits at 75% and raises no alert —
--  that way a demo shows exactly one budget alert, which is easy to observe.
-- ============================================================================
INSERT INTO budgets (user_id, category_id, period_month, limit_amount) VALUES
 (@u1, @e_food, @m0,  30.00),
 (@u1, @e_tran, @m0,  25.00),
 (@u1, @e_ent,  @m0,  40.00),
 (@u1, @e_subs, @m0,  15.00),
 (@u1, @e_host, @m0, 160.00);


-- ============================================================================
--  2. TRANSACTIONS FOR THE THREE PREVIOUS MONTHS (history behind the BR-15
--  baseline)
-- ============================================================================
-- The income/expense distinction is NOT passed below: it is the type of the
-- category the transaction points at (BR-05), so there is no `type` column to
-- pass.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u1, @i_allow, 200.00, 'Monthly allowance',        DATE_ADD(@m3, INTERVAL  1 DAY), 'MANUAL'),
 (@u1, @i_part,   80.00, 'Weekend shift',            DATE_ADD(@m3, INTERVAL 14 DAY), 'MANUAL'),
 (@u1, @e_host,  120.00, 'Dorm rent',                DATE_ADD(@m3, INTERVAL  2 DAY), 'MANUAL'),
 (@u1, @e_subs,    8.00, 'Music streaming plan',     DATE_ADD(@m3, INTERVAL  5 DAY), 'MANUAL'),
 (@u1, @e_food,   18.00, 'Campus canteen',           DATE_ADD(@m3, INTERVAL  6 DAY), 'MANUAL'),
 (@u1, @e_tran,   15.00, 'Monthly bus pass',         DATE_ADD(@m3, INTERVAL  8 DAY), 'MANUAL'),
 (@u1, @e_acad,   40.00, 'Semester textbooks',       DATE_ADD(@m3, INTERVAL 10 DAY), 'MANUAL'),
 (@u1, @e_ent,    10.00, 'Weekend movie',            DATE_ADD(@m3, INTERVAL 20 DAY), 'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u1, @i_allow, 200.00, 'Monthly allowance',        DATE_ADD(@m2, INTERVAL  1 DAY), 'MANUAL'),
 (@u1, @i_schol, 150.00, 'Merit scholarship',        DATE_ADD(@m2, INTERVAL  9 DAY), 'MANUAL'),
 (@u1, @e_host,  120.00, 'Dorm rent',                DATE_ADD(@m2, INTERVAL  2 DAY), 'MANUAL'),
 (@u1, @e_subs,    8.00, 'Music streaming plan',     DATE_ADD(@m2, INTERVAL  5 DAY), 'MANUAL'),
 (@u1, @e_food,   20.00, 'Campus canteen',           DATE_ADD(@m2, INTERVAL  6 DAY), 'MANUAL'),
 (@u1, @e_tran,   14.00, 'Monthly bus pass',         DATE_ADD(@m2, INTERVAL  8 DAY), 'MANUAL'),
 (@u1, @e_acad,   25.00, 'Notebooks and pens',       DATE_ADD(@m2, INTERVAL 10 DAY), 'MANUAL'),
 (@u1, @e_ent,    12.00, 'Coffee with friends',      DATE_ADD(@m2, INTERVAL 20 DAY), 'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u1, @i_allow, 200.00, 'Monthly allowance',        DATE_ADD(@m1, INTERVAL  1 DAY), 'MANUAL'),
 (@u1, @i_part,   90.00, 'Part-time shift',          DATE_ADD(@m1, INTERVAL 14 DAY), 'MANUAL'),
 (@u1, @e_host,  120.00, 'Dorm rent',                DATE_ADD(@m1, INTERVAL  2 DAY), 'MANUAL'),
 (@u1, @e_subs,    8.00, 'Music streaming plan',     DATE_ADD(@m1, INTERVAL  5 DAY), 'MANUAL'),
 (@u1, @e_food,   22.00, 'Campus canteen',           DATE_ADD(@m1, INTERVAL  6 DAY), 'MANUAL'),
 (@u1, @e_tran,   16.00, 'Monthly bus pass',         DATE_ADD(@m1, INTERVAL  8 DAY), 'MANUAL'),
 (@u1, @e_acad,   30.00, 'Reference materials',      DATE_ADD(@m1, INTERVAL 10 DAY), 'MANUAL'),
 (@u1, @e_ent,    11.00, 'Movie night',              DATE_ADD(@m1, INTERVAL 20 DAY), 'MANUAL');


-- ============================================================================
--  3. CURRENT-MONTH TRANSACTIONS
--  LEAST(..., CURDATE()) guarantees a date can never land in the future (BR-08),
--  even when this file is run at the very start of a month.
--
--  KNOWN DATE DEPENDENCY (pre-existing, unchanged): the UAT-07 budget position
--  only lands if the current month is at least 6 days old. On days 1-5 every date
--  below collapses onto CURDATE() and the Food total is whatever rows share that
--  day. The demo is therefore fully representative from the 6th of a month
--  onward. To see the "approaching budget" alert on an early-month run, add one
--  Food expense (UC-07) to bring the total to 24.00 of the 30.00 limit — that is
--  the documented UAT-07 step, not a workaround.
-- ============================================================================
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u1, @i_allow, 200.00, 'Monthly allowance',      LEAST(DATE_ADD(@m0, INTERVAL 1 DAY), CURDATE()), 'MANUAL'),
 (@u1, @i_part,   60.00, 'Part-time shift',        LEAST(DATE_ADD(@m0, INTERVAL 3 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_host,  120.00, 'Dorm rent',              LEAST(DATE_ADD(@m0, INTERVAL 2 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_subs,    8.00, 'Music streaming plan',   LEAST(DATE_ADD(@m0, INTERVAL 4 DAY), CURDATE()), 'MANUAL'),
 -- ── The UAT-07 row: 24/30 = exactly 80% ⇒ raises ONE "approaching" alert ────
 (@u1, @e_food,   24.00, 'Campus Cafe',            LEAST(DATE_ADD(@m0, INTERVAL 6 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_tran,   12.00, 'Monthly bus pass',       LEAST(DATE_ADD(@m0, INTERVAL 7 DAY), CURDATE()), 'MANUAL'),
 -- ── The BR-15 row: 25 against the average (10+12+11)/3 = 11 ⇒ +127% ────────
 (@u1, @e_ent,    25.00, 'Food delivery and a movie', LEAST(DATE_ADD(@m0, INTERVAL 9 DAY), CURDATE()), 'MANUAL');


-- ============================================================================
--  4. RECURRING RULE TEMPLATES (UC-09, BR-16)
--  The next run is placed in the following month, so seeding does NOT post any
--  transaction and the demo figures above stay exactly as calculated.
--
--  To verify BR-16 (one transaction per period even when catching up over many
--  periods), pass a date inside the NEXT month:
--    CALL sp_post_recurring_transactions(DATE_ADD(@m0, INTERVAL 1 MONTH));
--  Running it twice in a row shows no additional transactions the second time.
-- ============================================================================
INSERT INTO recurring_rules
  (user_id, category_id, type, amount, description, frequency, interval_count,
   day_of_month, start_date, end_date, next_run_date, status)
VALUES
 (@u1, @i_allow, 'INCOME',  200.00, 'Monthly allowance',    'MONTHLY', 1, 1,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE'),
 (@u1, @e_subs,  'EXPENSE',   8.00, 'Music streaming plan', 'MONTHLY', 1, 5,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE');


-- ============================================================================
--  5. GENERATE TIPS AND INSIGHTS FOR THE LAST THREE MONTHS (UC-17, UC-18)
-- ============================================================================
CALL sp_generate_tips(@u1, @m2, 3);
CALL sp_generate_tips(@u1, @m1, 3);
CALL sp_generate_tips(@u1, @m0, 3);

CALL sp_generate_monthly_insight(@u1, @m2);
CALL sp_generate_monthly_insight(@u1, @m1);
CALL sp_generate_monthly_insight(@u1, @m0);


-- ============================================================================
--  6. SECOND STUDENT — BELLA TRAN
--
--  Bella exists for OWNERSHIP ISOLATION testing (BR-02): every read and every
--  write is scoped to the signed-in account, so a tester signs in as Bella and
--  confirms Alex's data is invisible, then signs in as Alex and confirms Bella's
--  is. She is deliberately NOT a copy of Alex:
--
--    • a different, smaller budget - a Year 1 student living on less
--    • a different category mix: no Scholarship, no Hostel/Rent (Bella pays no
--      dorm rent), but a personal "Gym & Sports" category Alex does not have
--    • her own personal category, so `GET /categories` differs between the two
--      accounts and personal-category scope can actually be observed
--    • six months of history, but the dimmest month left almost empty so the
--      six-month chart shows a real trough as well as peaks
--
--  Figures are chosen so that Bella's Food budget ends up EXCEEDED (30.00 spent
--  against a 25.00 limit) while Alex's only reaches NEAR (24.00 of 30.00). A
--  tester therefore sees both alert states in the system without editing data.
-- ============================================================================

SET @u2 = (SELECT id FROM users WHERE email = 'binh.tran@student.campuscoin.edu');

SET @m4 = DATE_SUB(@m0, INTERVAL 4 MONTH);
SET @m5 = DATE_SUB(@m0, INTERVAL 5 MONTH);

SET @i_gift = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Gift');
SET @e_misc = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Miscellaneous');

-- ---------------------------------------------------------------------------
--  6a. Bella's own personal category (UC-06). A personal category, so it is
--  visible only to Bella: this is the row that makes ownership observable in the
--  category list itself rather than only in the figures.
-- ---------------------------------------------------------------------------
INSERT INTO categories (user_id, name, type, icon, color, sort_order, created_by)
VALUES (@u2, 'Gym & Sports', 'EXPENSE', 'dumbbell', '#14B8A6', 30, @u2);

SET @e_gym = (SELECT id FROM categories
              WHERE user_id = @u2 AND name = 'Gym & Sports');
SET @i_allow2 = @i_allow;
SET @i_part2  = @i_part;
SET @e_food2  = @e_food;
SET @e_tran2  = @e_tran;
SET @e_subs2  = @e_subs;
SET @e_ent2   = @e_ent;

-- ---------------------------------------------------------------------------
--  6b. Bella's current-month budgets (UC-13).
--  Food 25 against 30.00 spent => 120%. The Food rows are inserted below in an
--  order that crosses 80% first and 100% second, so BR-12 records ONE NEAR row
--  and ONE EXCEEDED row — the same progression UAT-07 describes.
--  The other four budgets stay under 80%, so the alert list holds exactly two
--  rows, both Food, and is unambiguous on screen.
-- ---------------------------------------------------------------------------
INSERT INTO budgets (user_id, category_id, period_month, limit_amount) VALUES
 (@u2, @e_food2, @m0, 25.00),
 (@u2, @e_tran2, @m0, 20.00),
 (@u2, @e_gym,   @m0, 30.00),
 (@u2, @e_subs2, @m0, 10.00),
 (@u2, @e_ent2,  @m0, 25.00);


-- ---------------------------------------------------------------------------
--  6c. Bella's six-month history. @m5 is intentionally almost empty (a single
--  small expense) so the trend chart is not a flat line.
-- ---------------------------------------------------------------------------
-- Five months back — the quiet month.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m5, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,    9.00, 'Campus canteen',          DATE_ADD(@m5, INTERVAL 4 DAY), 'MANUAL');

-- Four months back.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m4, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @i_gift,    40.00, 'Birthday gift',           DATE_ADD(@m4, INTERVAL 3 DAY), 'MANUAL'),
 (@u2, @e_food2,   14.00, 'Campus canteen',          DATE_ADD(@m4, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   11.00, 'Groceries',               DATE_ADD(@m4, INTERVAL 19 DAY),'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',        DATE_ADD(@m4, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m4, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m4, INTERVAL 7 DAY), 'MANUAL');

-- Three months back.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m3, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,   16.00, 'Campus canteen',          DATE_ADD(@m3, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   13.00, 'Groceries',               DATE_ADD(@m3, INTERVAL 18 DAY),'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',        DATE_ADD(@m3, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m3, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m3, INTERVAL 7 DAY), 'MANUAL'),
 (@u2, @e_ent2,    15.00, 'Concert ticket',          DATE_ADD(@m3, INTERVAL 22 DAY),'MANUAL');

-- Two months back.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m2, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @i_part2,   45.00, 'Weekend shift',           DATE_ADD(@m2, INTERVAL 12 DAY),'MANUAL'),
 (@u2, @e_food2,   19.00, 'Campus canteen',          DATE_ADD(@m2, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   12.00, 'Groceries',               DATE_ADD(@m2, INTERVAL 18 DAY),'MANUAL'),
 (@u2, @e_tran2,   11.00, 'Monthly bus pass',        DATE_ADD(@m2, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m2, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m2, INTERVAL 7 DAY), 'MANUAL');

-- One month back.
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m1, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,   17.00, 'Campus canteen',          DATE_ADD(@m1, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   15.00, 'Groceries',               DATE_ADD(@m1, INTERVAL 17 DAY),'MANUAL'),
 (@u2, @e_tran2,   10.00, 'Monthly bus pass',        DATE_ADD(@m1, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m1, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m1, INTERVAL 7 DAY), 'MANUAL'),
 (@u2, @e_ent2,    14.00, 'Cinema with friends',     DATE_ADD(@m1, INTERVAL 21 DAY),'MANUAL');

-- ---------------------------------------------------------------------------
--  6d. Bella's current month. The two Food rows are ordered deliberately: the
--  20.00 row takes Food to exactly 80% of its 25.00 limit (raising the NEAR
--  alert), and the 10.00 row that follows takes it to 120% (raising EXCEEDED).
--  Inserting them the other way round would skip straight past 80% and record
--  only the EXCEEDED row.
-- ---------------------------------------------------------------------------
INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',        LEAST(DATE_ADD(@m0, INTERVAL 1 DAY), CURDATE()), 'MANUAL'),
 (@u2, @i_part2,   40.00, 'Weekend shift',            LEAST(DATE_ADD(@m0, INTERVAL 4 DAY), CURDATE()), 'MANUAL'),
 -- Requires the month to be at least 8 days old (it stores 2026-09-08). See the
 -- note above the equivalent Alex row: on a freshly-started month the dates
 -- collapse onto CURDATE() and the Food figure lands below the 80% threshold
 -- instead. Add a Food expense and re-run 06_demo.sql to see the alert.
 (@u2, @e_food2,   20.00, 'Campus canteen',           LEAST(DATE_ADD(@m0, INTERVAL 6 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_food2,   10.00, 'Groceries',                LEAST(DATE_ADD(@m0, INTERVAL 8 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',         LEAST(DATE_ADD(@m0, INTERVAL 7 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership', LEAST(DATE_ADD(@m0, INTERVAL 8 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',     LEAST(DATE_ADD(@m0, INTERVAL 5 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_ent2,     8.00, 'Board game cafe',          LEAST(DATE_ADD(@m0, INTERVAL 9 DAY), CURDATE()), 'MANUAL');


-- ---------------------------------------------------------------------------
--  6e. Bella's recurring rules (UC-09, BR-16) — same shape as Alex's, different
--  amounts, so each account has its own rule list.
-- ---------------------------------------------------------------------------
INSERT INTO recurring_rules
  (user_id, category_id, type, amount, description, frequency, interval_count,
   day_of_month, start_date, end_date, next_run_date, status)
VALUES
 (@u2, @i_allow2, 'INCOME',  150.00, 'Monthly allowance',    'MONTHLY', 1, 1,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE'),
 (@u2, @e_gym,    'EXPENSE',  22.00, 'Sports centre membership', 'MONTHLY', 1, 8,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE');


-- ---------------------------------------------------------------------------
--  6f. Bella's own tips and insights, generated from her own data by the same
--  procedures Alex's used. Nothing is fabricated: a tip exists only if the
--  engine found a reason for one.
-- ---------------------------------------------------------------------------
CALL sp_generate_tips(@u2, @m1, 3);
CALL sp_generate_tips(@u2, @m0, 3);

CALL sp_generate_monthly_insight(@u2, @m4);
CALL sp_generate_monthly_insight(@u2, @m3);
CALL sp_generate_monthly_insight(@u2, @m2);
CALL sp_generate_monthly_insight(@u2, @m1);
CALL sp_generate_monthly_insight(@u2, @m0);


-- ============================================================================
--  7. RESULT VERIFICATION
-- ============================================================================

SELECT '1. Row counts' AS `check`;
SELECT 'users' AS `table`, COUNT(*) AS `rows` FROM users
UNION ALL SELECT 'categories',            COUNT(*) FROM categories
UNION ALL SELECT 'transactions',          COUNT(*) FROM transactions
UNION ALL SELECT 'transaction history',   COUNT(*) FROM transaction_history
UNION ALL SELECT 'budgets',               COUNT(*) FROM budgets
UNION ALL SELECT 'budget alerts',         COUNT(*) FROM budget_alert_log
UNION ALL SELECT 'notifications',         COUNT(*) FROM notifications
UNION ALL SELECT 'generated tips',        COUNT(*) FROM user_tips
UNION ALL SELECT 'insights',              COUNT(*) FROM insights;

SELECT '2. Budget alerts - UAT-07 expects exactly ONE NEAR row for Food' AS `check`;
SELECT c.name AS `category`, a.threshold_type AS `threshold`,
       a.consumed_pct AS `used (%)`, a.spent_amount AS `spent`,
       a.limit_amount AS `limit`, a.triggered_at AS `triggered at`
  FROM budget_alert_log a
  JOIN categories c ON c.id = a.category_id
 ORDER BY a.id;

SELECT '3. Current-month saving tips - ranked by potential saving (BR-14)' AS `check`;
SELECT title AS `title`, potential_saving AS `potential saving`,
       rank_score AS `rank score`, state AS `state`
  FROM user_tips
 WHERE user_id = @u1 AND period_month = @m0
 ORDER BY rank_score DESC;

SELECT '4. Abnormal category growth (BR-15) - Entertainment up 127%' AS `check`;
SELECT category_name AS `category`, current_spend AS `this month`,
       baseline_avg_spend AS `3-month average`, pct_change AS `change (%)`,
       is_spike AS `flagged`
  FROM v_category_spend_trend
 WHERE user_id = @u1 AND current_month = @m0
 ORDER BY pct_change DESC;

SELECT '5. Six-month report, months with no data return 0 (BR-17, UAT-09)' AS `check`;
SELECT period_month AS `month`, total_income AS `income`,
       total_expense AS `expense`, net_amount AS `net`
  FROM v_monthly_income_expense_6m
 WHERE user_id = @u1
 ORDER BY period_month;

SELECT '6. Current-month budget progress (UC-13)' AS `check`;
SELECT category_name AS `category`, limit_amount AS `limit`,
       spent_amount AS `spent`, consumed_pct AS `used (%)`,
       remaining_amount AS `remaining`, consumption_status AS `status`
  FROM v_budget_consumption
 WHERE user_id = @u1 AND period_month = @m0
 ORDER BY consumed_pct DESC;

SELECT '7. Ownership isolation - each student sees only their own rows' AS `check`;
SELECT u.email AS `student`,
       (SELECT COUNT(*) FROM transactions t WHERE t.user_id = u.id)  AS `transactions`,
       (SELECT COUNT(*) FROM budgets      b WHERE b.user_id = u.id)  AS `budgets`,
       (SELECT COUNT(*) FROM categories   c WHERE c.user_id = u.id)  AS `personal categories`,
       (SELECT COUNT(*) FROM user_tips    x WHERE x.user_id = u.id)  AS `tips`,
       (SELECT COUNT(*) FROM insights     i WHERE i.user_id = u.id)  AS `insights`,
       (SELECT COUNT(*) FROM recurring_rules r WHERE r.user_id = u.id) AS `recurring rules`
  FROM users u
 WHERE u.role = 'STUDENT'
 ORDER BY u.id;

SELECT '8. Budget alerts - Alex: ONE NEAR. Bella: ONE NEAR + ONE EXCEEDED' AS `check`;
SELECT u.email AS `student`, c.name AS `category`, a.threshold_type AS `threshold`,
       a.consumed_pct AS `used (%)`, a.spent_amount AS `spent`, a.limit_amount AS `limit`
  FROM budget_alert_log a
  JOIN users u      ON u.id = a.user_id
  JOIN categories c ON c.id = a.category_id
 ORDER BY u.id, a.id;

SELECT '9. Six-month report for BOTH students (BR-17)' AS `check`;
SELECT u.email AS `student`, v.period_month AS `month`,
       v.total_income AS `income`, v.total_expense AS `expense`, v.net_amount AS `net`
  FROM v_monthly_income_expense_6m v
  JOIN users u ON u.id = v.user_id
 WHERE u.role = 'STUDENT'
 ORDER BY u.id, v.period_month;
