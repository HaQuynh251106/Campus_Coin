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
