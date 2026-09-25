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
