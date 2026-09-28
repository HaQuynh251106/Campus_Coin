

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
