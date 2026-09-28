

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
