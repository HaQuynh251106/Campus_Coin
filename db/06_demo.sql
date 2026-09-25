-- ============================================================================
--  CAMPUS COIN — 06_demo.sql
--  DEMO DATA (optional) — pre-builds three months of spending history for one
--  student, so the dashboard, charts, reports and tips all have figures the
--  moment the web app opens.
--
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
--  6. RESULT VERIFICATION
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
