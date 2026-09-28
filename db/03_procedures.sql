

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
