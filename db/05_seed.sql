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
