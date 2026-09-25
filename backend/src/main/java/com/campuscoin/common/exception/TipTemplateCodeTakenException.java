package com.campuscoin.common.exception;

/**
 * UC-21 B3: a saving-tip template with this code already exists.
 *
 * <p>{@code uk_tip_template_code} is what actually prevents a second row. This exception exists so
 * the collision is reported as something the caller can act on rather than as a generic refused
 * write: choosing a different code, or editing the template that already has this one. The shape
 * matches {@link BookmarkAlreadyExistsException} and {@link BudgetAlreadyExistsException}, which
 * answer the same kind of collision for their own resources.
 *
 * <p>A conflict rather than a field error, because no field the caller sent is wrong on its own -
 * the code is well formed and the title and body are valid. It is the code's collision with an
 * existing row that fails, and a field error would imply the client should re-enter all of it.
 *
 * <p>Recognised from the constraint by name rather than by SQLSTATE, because {@code 23000} also
 * covers the foreign keys on this table - a violation naming {@code fk_tip_tpl_created_by} means
 * something entirely different, and reporting it as a duplicate code would send the caller to fix a
 * field that is not the problem.
 */
public class TipTemplateCodeTakenException extends ApiException {

    public TipTemplateCodeTakenException(String message) {
        super(ErrorCode.TIP_TEMPLATE_CODE_TAKEN, message);
    }
}
