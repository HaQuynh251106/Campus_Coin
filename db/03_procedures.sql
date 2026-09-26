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
