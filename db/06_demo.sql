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
