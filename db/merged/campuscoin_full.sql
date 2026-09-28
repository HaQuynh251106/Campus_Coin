

DROP DATABASE IF EXISTS campuscoin;
CREATE DATABASE campuscoin
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE campuscoin;

SET NAMES utf8mb4;

SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE users (
  id                         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email                      VARCHAR(190)    NOT NULL,
  password_hash              VARCHAR(100)    NOT NULL,
  full_name                  VARCHAR(120)    NOT NULL,
  role                       ENUM('STUDENT','ADMIN') NOT NULL DEFAULT 'STUDENT',
  academic_year              VARCHAR(30)     NULL,
  monthly_allowance_baseline DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  monthly_savings_goal       DECIMAL(15,2)   NOT NULL DEFAULT 0.00,
  currency                   CHAR(3)         NOT NULL DEFAULT 'USD',
  status                     ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  theme_pref                 ENUM('LIGHT','DARK','SYSTEM') NOT NULL DEFAULT 'SYSTEM',
  font_scale                 ENUM('SMALL','MEDIUM','LARGE','XLARGE') NOT NULL DEFAULT 'MEDIUM',
  ai_enabled                 TINYINT(1)      NOT NULL DEFAULT 1,
  email_verified_at          DATETIME        NULL,
  last_login_at              DATETIME        NULL,
  token_version              INT UNSIGNED    NOT NULL DEFAULT 0,
  created_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  KEY ix_users_role_status (role, status),
  CONSTRAINT ck_users_money CHECK (monthly_allowance_baseline >= 0
                               AND monthly_savings_goal      >= 0)
) ENGINE=InnoDB;

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

CREATE TABLE dim_month (
  month_start DATE              NOT NULL,
  month_end   DATE              NOT NULL,
  year_no     SMALLINT UNSIGNED NOT NULL,
  month_no    TINYINT UNSIGNED  NOT NULL,
  label_short CHAR(7)           NOT NULL,
  PRIMARY KEY (month_start),
  UNIQUE KEY uk_dim_month_label (label_short)
) ENGINE=InnoDB;

CREATE TABLE categories (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NULL,
  name        VARCHAR(80)     NOT NULL,
  type        ENUM('INCOME','EXPENSE') NOT NULL,
  icon        VARCHAR(50)     NULL,
  color       CHAR(7)         NULL,
  description VARCHAR(255)    NULL,
  sort_order  SMALLINT        NOT NULL DEFAULT 0,
  is_active   TINYINT(1)      NOT NULL DEFAULT 1,
  scope_key   BIGINT UNSIGNED GENERATED ALWAYS AS (IFNULL(user_id, 0)) VIRTUAL,
  created_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_categories_scope_type_name (scope_key, type, name),
  KEY ix_categories_user_type_active (user_id, type, is_active, sort_order),
  CONSTRAINT fk_categories_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by)
    REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE transactions (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id                  BIGINT UNSIGNED NOT NULL,
  category_id              BIGINT UNSIGNED NOT NULL,

  amount                   DECIMAL(15,2)   NOT NULL,

  description              VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NULL,
  txn_date                 DATE            NOT NULL,
  source                   ENUM('MANUAL','CSV','RECURRING') NOT NULL DEFAULT 'MANUAL',

  ai_suggested_category_id BIGINT UNSIGNED NULL,
  ai_confidence            DECIMAL(5,4)    NULL,
  ai_overridden            TINYINT(1)      NOT NULL DEFAULT 0,
  recurring_rule_id        BIGINT UNSIGNED NULL,
  import_batch_id          BIGINT UNSIGNED NULL,
  is_flagged               TINYINT(1)      NOT NULL DEFAULT 0,
  flag_type                ENUM('NONE','DUPLICATE','UNUSUAL_AMOUNT') NOT NULL DEFAULT 'NONE',
  flag_note                VARCHAR(255)    NULL,
  is_deleted               TINYINT(1)      NOT NULL DEFAULT 0,
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

CREATE TABLE recurring_rules (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  category_id    BIGINT UNSIGNED NOT NULL,
  type           ENUM('INCOME','EXPENSE') NOT NULL,

  amount         DECIMAL(15,2)   NOT NULL,

  description    VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NULL,
  frequency      ENUM('DAILY','WEEKLY','MONTHLY','QUARTERLY','YEARLY') NOT NULL,
  interval_count SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  day_of_month   TINYINT UNSIGNED NULL,
  day_of_week    TINYINT UNSIGNED NULL,
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

CREATE TABLE recurring_occurrences (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_id        BIGINT UNSIGNED NOT NULL,
  period_key     VARCHAR(12)     NOT NULL,
  scheduled_date DATE            NOT NULL,
  transaction_id BIGINT UNSIGNED NULL,
  status         ENUM('POSTED','SKIPPED','FAILED') NOT NULL DEFAULT 'POSTED',
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_occurrence_rule_period (rule_id, period_key),
  KEY ix_occurrence_txn (transaction_id),
  CONSTRAINT fk_occurrence_rule FOREIGN KEY (rule_id)
    REFERENCES recurring_rules(id) ON DELETE CASCADE,
  CONSTRAINT fk_occurrence_txn FOREIGN KEY (transaction_id)
    REFERENCES transactions(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE category_rules (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  keyword      VARCHAR(80)     NOT NULL,
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
  UNIQUE KEY uk_budget_user_cat_month (user_id, category_id, period_month),
  KEY ix_budget_month (period_month),
  CONSTRAINT fk_budget_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_budget_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT ck_budget_limit CHECK (limit_amount > 0),
  CONSTRAINT ck_budget_month CHECK (DAYOFMONTH(period_month) = 1)
) ENGINE=InnoDB;

CREATE TABLE budget_alert_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  budget_id       BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  category_id     BIGINT UNSIGNED NOT NULL,
  threshold_type  ENUM('NEAR','EXCEEDED') NOT NULL,
  threshold_pct   DECIMAL(6,2)    NOT NULL,
  consumed_pct    DECIMAL(9,2)    NOT NULL,

  spent_amount    DECIMAL(15,2)   NOT NULL,
  limit_amount    DECIMAL(15,2)   NOT NULL,
  notification_id BIGINT UNSIGNED NULL,
  triggered_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alert_budget_threshold (budget_id, threshold_type),
  KEY ix_alert_user (user_id, triggered_at),
  CONSTRAINT fk_alert_budget FOREIGN KEY (budget_id)
    REFERENCES budgets(id) ON DELETE CASCADE,
  CONSTRAINT fk_alert_user FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_alert_category FOREIGN KEY (category_id)
    REFERENCES categories(id) ON DELETE CASCADE
) ENGINE=InnoDB;

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

CREATE TABLE insights (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id            BIGINT UNSIGNED NOT NULL,
  period_month       DATE            NOT NULL,
  summary_text       TEXT            NULL,
  advice_text        TEXT            NULL,
  flagged_categories JSON            NULL,
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

CREATE TABLE import_rows (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id                 BIGINT UNSIGNED NOT NULL,

  csv_row_no               INT UNSIGNED    NOT NULL,
  raw_data                 JSON            NULL,
  parsed_date              DATE            NULL,

  parsed_amount            DECIMAL(15,2)   NULL,
  parsed_type              ENUM('INCOME','EXPENSE') NULL,
  parsed_description       VARCHAR(255)    NULL,
  parsed_category_name     VARCHAR(80)     NULL,

  resolved_category_id     BIGINT UNSIGNED NULL,

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

USE campuscoin;

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

    WHEN IFNULL(s.spent_amount, 0) >= b.limit_amount
         * IFNULL(NULLIF(GREATEST(CAST(exceed_s.setting_value AS DECIMAL(6,2)), 0), 0), 100) / 100
      THEN 'EXCEEDED'
    WHEN IFNULL(s.spent_amount, 0) >= b.limit_amount
         * IFNULL(NULLIF(GREATEST(CAST(near_s.setting_value AS DECIMAL(6,2)), 0), 0), 80) / 100
      THEN 'NEAR'
    ELSE 'ON_TRACK'
  END AS consumption_status
FROM budgets b
JOIN categories c ON c.id = b.category_id
LEFT JOIN system_settings near_s ON near_s.setting_key = 'budget.near_threshold_pct'
LEFT JOIN system_settings exceed_s ON exceed_s.setting_key = 'budget.exceeded_threshold_pct'
LEFT JOIN (

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

CREATE OR REPLACE VIEW v_daily_spending_current_month AS
SELECT t.user_id, t.txn_date, SUM(t.amount) AS total_expense, COUNT(*) AS txn_count
FROM transactions t
JOIN categories c ON c.id = t.category_id
WHERE t.is_deleted = 0
  AND c.type = 'EXPENSE'
  AND t.txn_date >= CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE)
  AND t.txn_date <  DATE_ADD(CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE), INTERVAL 1 MONTH)
GROUP BY t.user_id, t.txn_date;

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

CREATE OR REPLACE VIEW v_dashboard_tips AS
SELECT user_id, period_month, id AS tip_id, category_id, title, body,
       potential_saving, state,
       ROW_NUMBER() OVER (PARTITION BY user_id, period_month
                          ORDER BY (state = 'PINNED') DESC, rank_score DESC) AS display_order
FROM user_tips
WHERE state <> 'DISMISSED';

CREATE OR REPLACE VIEW v_active_announcements AS
SELECT id, title, body, severity, audience, starts_at, ends_at
FROM announcements
WHERE is_active = 1
  AND starts_at <= NOW()
  AND (ends_at IS NULL OR ends_at >= NOW());

CREATE OR REPLACE VIEW v_user_recent_activity AS
SELECT ra.user_id, ra.action, ra.occurred_at,
       t.id AS transaction_id, t.category_id, c.type, t.amount, t.description, t.txn_date
FROM recent_activity ra
JOIN transactions t ON t.id = ra.transaction_id
JOIN categories   c ON c.id = t.category_id
WHERE t.is_deleted = 0;

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

USE campuscoin;

DELIMITER $$

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

  IF v_cat_user IS NOT NULL AND v_cat_user <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: category belongs to another student';
  END IF;
  IF IFNULL(p_require_active, 0) = 1 AND v_cat_active = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: category has been disabled';
  END IF;

  IF p_txn_date > CURDATE() AND IFNULL(p_source, 'MANUAL') <> 'RECURRING' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-08: transaction date cannot be in the future';
  END IF;

  IF p_ai_category_id IS NOT NULL THEN

    SELECT user_id INTO v_ai_owner FROM categories WHERE id = p_ai_category_id;
    IF v_ai_owner IS NOT NULL AND v_ai_owner <> p_user_id THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'BR-13: suggested category belongs to another student';
    END IF;
  END IF;

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

  IF v_type <> p_type THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-05: recurring rule type must match the category type';
  END IF;

  IF v_owner IS NOT NULL AND v_owner <> p_user_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-02: category belongs to another student';
  END IF;

  IF v_active = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: category has been disabled';
  END IF;
END $$

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

  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 80) INTO v_near_pct
    FROM system_settings WHERE setting_key = 'budget.near_threshold_pct';
  SELECT IFNULL(MAX(CAST(setting_value AS DECIMAL(6,2))), 100) INTO v_exceed_pct
    FROM system_settings WHERE setting_key = 'budget.exceeded_threshold_pct';

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

DROP PROCEDURE IF EXISTS sp_post_recurring_transactions $$
CREATE PROCEDURE sp_post_recurring_transactions(IN p_as_of DATE)
BEGIN
  DECLARE v_done        INT DEFAULT 0;
  DECLARE v_rule_id     BIGINT UNSIGNED;
  DECLARE v_user_id     BIGINT UNSIGNED;
  DECLARE v_category_id BIGINT UNSIGNED;
  DECLARE v_amount      DECIMAL(15,2);

  DECLARE v_desc        VARCHAR(2048);
  DECLARE v_freq        VARCHAR(12);
  DECLARE v_interval    INT;
  DECLARE v_next        DATE;
  DECLARE v_end         DATE;
  DECLARE v_period_key  VARCHAR(12);
  DECLARE v_txn_id      BIGINT UNSIGNED;
  DECLARE v_guard       INT DEFAULT 0;
  DECLARE v_as_of       DATE;

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

      IF ROW_COUNT() > 0 THEN

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

    IF v_r_cat_id IS NULL AND v_choice_bad = 0 AND v_r_cat_name IS NOT NULL THEN
      SET v_r_cat_id = (SELECT id FROM categories
                         WHERE type = v_r_type
                           AND is_active = 1
                           AND name = v_r_cat_name
                           AND (user_id = v_user_id OR user_id IS NULL)
                         ORDER BY (user_id IS NULL)
                         LIMIT 1);
    END IF;

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

DROP PROCEDURE IF EXISTS sp_complete_password_reset $$
CREATE PROCEDURE sp_complete_password_reset(
  IN  p_token_hash        CHAR(64),
  IN  p_new_password_hash VARCHAR(100),
  OUT p_user_id           BIGINT UNSIGNED
)
BEGIN
  DECLARE v_uid BIGINT UNSIGNED DEFAULT NULL;

  UPDATE password_reset_tokens
     SET used_at = NOW()
   WHERE token_hash = p_token_hash
     AND used_at IS NULL
     AND expires_at > NOW();

  IF ROW_COUNT() = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-04: reset token is invalid, already used, or expired';
  END IF;

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

DROP PROCEDURE IF EXISTS sp_admin_upsert_default_category $$
CREATE PROCEDURE sp_admin_upsert_default_category(
  IN p_actor_id      BIGINT UNSIGNED,
  IN p_category_id   BIGINT UNSIGNED,
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

DROP PROCEDURE IF EXISTS sp_admin_upsert_tip_template $$
CREATE PROCEDURE sp_admin_upsert_tip_template(
  IN p_actor_id         BIGINT UNSIGNED,
  IN p_template_id      BIGINT UNSIGNED,
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

USE campuscoin;

DELIMITER $$

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

DROP TRIGGER IF EXISTS trg_categories_before_delete $$
CREATE TRIGGER trg_categories_before_delete
BEFORE DELETE ON categories FOR EACH ROW
BEGIN
  IF (SELECT COUNT(*) FROM budgets b WHERE b.category_id = OLD.id) > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'BR-07: this category has a budget; disable it instead of deleting it';
  END IF;
END $$

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

  CALL sp_validate_recurring_rule(NEW.user_id, NEW.category_id, NEW.type);
END $$

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

DROP TRIGGER IF EXISTS trg_transactions_before_delete $$
CREATE TRIGGER trg_transactions_before_delete
BEFORE DELETE ON transactions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'BR-09: transactions cannot be hard-deleted; use soft delete instead';
END $$

DROP TRIGGER IF EXISTS trg_transactions_after_insert $$
CREATE TRIGGER trg_transactions_after_insert
AFTER INSERT ON transactions FOR EACH ROW
BEGIN
  DECLARE v_type VARCHAR(10) DEFAULT NULL;

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

  IF NEW.is_deleted = 0 THEN
    CALL sp_check_budget_alerts(
      NEW.user_id, NEW.category_id,
      CAST(DATE_FORMAT(NEW.txn_date, '%Y-%m-01') AS DATE));
  END IF;
END $$

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

  IF NEW.is_deleted = 0 THEN
    CALL sp_check_budget_alerts(
      NEW.user_id, NEW.category_id,
      CAST(DATE_FORMAT(NEW.txn_date, '%Y-%m-01') AS DATE));
  END IF;
END $$

DELIMITER ;

DELIMITER $$

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

USE campuscoin;

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

CALL sp_seed_dim_month('2023-01-01', '2030-12-01');

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

INSERT INTO categories (user_id, name, type, icon, color, sort_order, created_by) VALUES

 (NULL, 'Allowance',        'INCOME',  'wallet',           '#22C55E',  1, @admin_id),
 (NULL, 'Part-time Job',    'INCOME',  'briefcase',        '#16A34A',  2, @admin_id),
 (NULL, 'Scholarship',      'INCOME',  'graduation-cap',   '#0EA5E9',  3, @admin_id),
 (NULL, 'Gift',             'INCOME',  'gift',             '#8B5CF6',  4, @admin_id),
 (NULL, 'Other Income',     'INCOME',  'plus-circle',      '#64748B',  5, @admin_id),

 (NULL, 'Food',             'EXPENSE', 'utensils',         '#F97316', 10, @admin_id),
 (NULL, 'Transport',        'EXPENSE', 'bus',              '#3B82F6', 11, @admin_id),
 (NULL, 'Hostel/Rent',      'EXPENSE', 'home',             '#EF4444', 12, @admin_id),
 (NULL, 'Academics',        'EXPENSE', 'book-open',        '#6366F1', 13, @admin_id),
 (NULL, 'Subscriptions',    'EXPENSE', 'repeat',           '#EC4899', 14, @admin_id),
 (NULL, 'Entertainment',    'EXPENSE', 'film',             '#A855F7', 15, @admin_id),
 (NULL, 'Miscellaneous',    'EXPENSE', 'more-horizontal',  '#64748B', 16, @admin_id);

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

INSERT INTO announcements
  (title, body, severity, audience, starts_at, ends_at, is_active, created_by)
VALUES
 ('Welcome to Campus Coin',
  'Record every income and expense to get saving tips based on your own habits. All analysis is a suggestion only, not financial advice.',
  'INFO', 'STUDENTS', NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY), 1, @admin_id),

 ('Import your past spending from a CSV file',
  'You can upload a CSV file to bring your earlier spending into the system. Every row is previewed before anything is written to your ledger.',
  'SUCCESS', 'STUDENTS', NOW(), DATE_ADD(NOW(), INTERVAL 60 DAY), 1, @admin_id);

SELECT 'COMPONENT' AS `item`, 'COUNT' AS `value`
UNION ALL SELECT 'Accounts',              CAST(COUNT(*) AS CHAR) FROM users
UNION ALL SELECT 'Default categories',    CAST(COUNT(*) AS CHAR) FROM categories WHERE user_id IS NULL
UNION ALL SELECT 'Tip templates',         CAST(COUNT(*) AS CHAR) FROM tip_templates
UNION ALL SELECT 'Announcements',         CAST(COUNT(*) AS CHAR) FROM announcements
UNION ALL SELECT 'System settings',       CAST(COUNT(*) AS CHAR) FROM system_settings
UNION ALL SELECT 'Months in dimension',   CAST(COUNT(*) AS CHAR) FROM dim_month;

USE campuscoin;

SET @u1 = (SELECT id FROM users WHERE email = 'an.nguyen@student.campuscoin.edu');
SET @m0 = CAST(DATE_FORMAT(CURDATE(), '%Y-%m-01') AS DATE);
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

INSERT INTO budgets (user_id, category_id, period_month, limit_amount) VALUES
 (@u1, @e_food, @m0,  30.00),
 (@u1, @e_tran, @m0,  25.00),
 (@u1, @e_ent,  @m0,  40.00),
 (@u1, @e_subs, @m0,  15.00),
 (@u1, @e_host, @m0, 160.00);

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

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u1, @i_allow, 200.00, 'Monthly allowance',      LEAST(DATE_ADD(@m0, INTERVAL 1 DAY), CURDATE()), 'MANUAL'),
 (@u1, @i_part,   60.00, 'Part-time shift',        LEAST(DATE_ADD(@m0, INTERVAL 3 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_host,  120.00, 'Dorm rent',              LEAST(DATE_ADD(@m0, INTERVAL 2 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_subs,    8.00, 'Music streaming plan',   LEAST(DATE_ADD(@m0, INTERVAL 4 DAY), CURDATE()), 'MANUAL'),

 (@u1, @e_food,   24.00, 'Campus Cafe',            LEAST(DATE_ADD(@m0, INTERVAL 6 DAY), CURDATE()), 'MANUAL'),
 (@u1, @e_tran,   12.00, 'Monthly bus pass',       LEAST(DATE_ADD(@m0, INTERVAL 7 DAY), CURDATE()), 'MANUAL'),

 (@u1, @e_ent,    25.00, 'Food delivery and a movie', LEAST(DATE_ADD(@m0, INTERVAL 9 DAY), CURDATE()), 'MANUAL');

INSERT INTO recurring_rules
  (user_id, category_id, type, amount, description, frequency, interval_count,
   day_of_month, start_date, end_date, next_run_date, status)
VALUES
 (@u1, @i_allow, 'INCOME',  200.00, 'Monthly allowance',    'MONTHLY', 1, 1,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE'),
 (@u1, @e_subs,  'EXPENSE',   8.00, 'Music streaming plan', 'MONTHLY', 1, 5,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE');

CALL sp_generate_tips(@u1, @m2, 3);
CALL sp_generate_tips(@u1, @m1, 3);
CALL sp_generate_tips(@u1, @m0, 3);

CALL sp_generate_monthly_insight(@u1, @m2);
CALL sp_generate_monthly_insight(@u1, @m1);
CALL sp_generate_monthly_insight(@u1, @m0);

SET @u2 = (SELECT id FROM users WHERE email = 'binh.tran@student.campuscoin.edu');

SET @m4 = DATE_SUB(@m0, INTERVAL 4 MONTH);
SET @m5 = DATE_SUB(@m0, INTERVAL 5 MONTH);

SET @i_gift = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Gift');
SET @e_misc = (SELECT id FROM categories WHERE user_id IS NULL AND name = 'Miscellaneous');

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

INSERT INTO budgets (user_id, category_id, period_month, limit_amount) VALUES
 (@u2, @e_food2, @m0, 25.00),
 (@u2, @e_tran2, @m0, 20.00),
 (@u2, @e_gym,   @m0, 30.00),
 (@u2, @e_subs2, @m0, 10.00),
 (@u2, @e_ent2,  @m0, 25.00);

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m5, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,    9.00, 'Campus canteen',          DATE_ADD(@m5, INTERVAL 4 DAY), 'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m4, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @i_gift,    40.00, 'Birthday gift',           DATE_ADD(@m4, INTERVAL 3 DAY), 'MANUAL'),
 (@u2, @e_food2,   14.00, 'Campus canteen',          DATE_ADD(@m4, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   11.00, 'Groceries',               DATE_ADD(@m4, INTERVAL 19 DAY),'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',        DATE_ADD(@m4, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m4, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m4, INTERVAL 7 DAY), 'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m3, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,   16.00, 'Campus canteen',          DATE_ADD(@m3, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   13.00, 'Groceries',               DATE_ADD(@m3, INTERVAL 18 DAY),'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',        DATE_ADD(@m3, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m3, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m3, INTERVAL 7 DAY), 'MANUAL'),
 (@u2, @e_ent2,    15.00, 'Concert ticket',          DATE_ADD(@m3, INTERVAL 22 DAY),'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m2, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @i_part2,   45.00, 'Weekend shift',           DATE_ADD(@m2, INTERVAL 12 DAY),'MANUAL'),
 (@u2, @e_food2,   19.00, 'Campus canteen',          DATE_ADD(@m2, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   12.00, 'Groceries',               DATE_ADD(@m2, INTERVAL 18 DAY),'MANUAL'),
 (@u2, @e_tran2,   11.00, 'Monthly bus pass',        DATE_ADD(@m2, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m2, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m2, INTERVAL 7 DAY), 'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',       DATE_ADD(@m1, INTERVAL 1 DAY), 'MANUAL'),
 (@u2, @e_food2,   17.00, 'Campus canteen',          DATE_ADD(@m1, INTERVAL 5 DAY), 'MANUAL'),
 (@u2, @e_food2,   15.00, 'Groceries',               DATE_ADD(@m1, INTERVAL 17 DAY),'MANUAL'),
 (@u2, @e_tran2,   10.00, 'Monthly bus pass',        DATE_ADD(@m1, INTERVAL 6 DAY), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership',DATE_ADD(@m1, INTERVAL 8 DAY), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',    DATE_ADD(@m1, INTERVAL 7 DAY), 'MANUAL'),
 (@u2, @e_ent2,    14.00, 'Cinema with friends',     DATE_ADD(@m1, INTERVAL 21 DAY),'MANUAL');

INSERT INTO transactions (user_id, category_id, amount, description, txn_date, source) VALUES
 (@u2, @i_allow2, 150.00, 'Monthly allowance',        LEAST(DATE_ADD(@m0, INTERVAL 1 DAY), CURDATE()), 'MANUAL'),
 (@u2, @i_part2,   40.00, 'Weekend shift',            LEAST(DATE_ADD(@m0, INTERVAL 4 DAY), CURDATE()), 'MANUAL'),

 (@u2, @e_food2,   20.00, 'Campus canteen',           LEAST(DATE_ADD(@m0, INTERVAL 6 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_food2,   10.00, 'Groceries',                LEAST(DATE_ADD(@m0, INTERVAL 8 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_tran2,    9.00, 'Monthly bus pass',         LEAST(DATE_ADD(@m0, INTERVAL 7 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_gym,     22.00, 'Sports centre membership', LEAST(DATE_ADD(@m0, INTERVAL 8 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_subs2,    6.00, 'Video streaming plan',     LEAST(DATE_ADD(@m0, INTERVAL 5 DAY), CURDATE()), 'MANUAL'),
 (@u2, @e_ent2,     8.00, 'Board game cafe',          LEAST(DATE_ADD(@m0, INTERVAL 9 DAY), CURDATE()), 'MANUAL');

INSERT INTO recurring_rules
  (user_id, category_id, type, amount, description, frequency, interval_count,
   day_of_month, start_date, end_date, next_run_date, status)
VALUES
 (@u2, @i_allow2, 'INCOME',  150.00, 'Monthly allowance',    'MONTHLY', 1, 1,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE'),
 (@u2, @e_gym,    'EXPENSE',  22.00, 'Sports centre membership', 'MONTHLY', 1, 8,
  @m0, NULL, DATE_ADD(@m0, INTERVAL 1 MONTH), 'ACTIVE');

CALL sp_generate_tips(@u2, @m1, 3);
CALL sp_generate_tips(@u2, @m0, 3);

CALL sp_generate_monthly_insight(@u2, @m4);
CALL sp_generate_monthly_insight(@u2, @m3);
CALL sp_generate_monthly_insight(@u2, @m2);
CALL sp_generate_monthly_insight(@u2, @m1);
CALL sp_generate_monthly_insight(@u2, @m0);

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

SELECT '2. Budget alerts - Alex: ONE NEAR for Food (UAT-07). Bella: ONE NEAR + ONE EXCEEDED'
       AS `check`;
SELECT u.email AS `student`, c.name AS `category`, a.threshold_type AS `threshold`,
       a.consumed_pct AS `used (%)`, a.spent_amount AS `spent`,
       a.limit_amount AS `limit`, a.triggered_at AS `triggered at`
  FROM budget_alert_log a
  JOIN categories c ON c.id = a.category_id
  JOIN users u ON u.id = a.user_id
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
