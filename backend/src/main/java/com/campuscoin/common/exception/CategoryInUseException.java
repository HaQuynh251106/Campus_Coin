package com.campuscoin.common.exception;

/**
 * UC-06, BR-05, BR-07: the change would rewrite the meaning of records already filed under this
 * category, so the database refuses it.
 *
 * <p>Two operations reach this. Changing the {@code type} of a category that a transaction, budget
 * or recurring rule points at is refused by {@code trg_categories_before_update}, because the
 * category's type is the single source of truth for whether those records are income or expense.
 * Deleting a category that is still referenced is refused by {@code trg_categories_before_delete}
 * and by the {@code RESTRICT} foreign keys, which is BR-07's rule: retire it by setting
 * {@code isActive} to false instead, so the history keeps its category.
 *
 * <p>Neither rule is decided here. Both are the database's, and both hold for hand-run statements
 * as well as for this API; the service only turns the refusal into a message the caller can act
 * on.
 */
public class CategoryInUseException extends ApiException {

    public CategoryInUseException(String message) {
        super(ErrorCode.CATEGORY_IN_USE, message);
    }
}
