

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
