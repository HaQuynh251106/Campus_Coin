package com.campuscoin.common.exception;

/**
 * UC-21 B4: a tip template's {@code code} cannot be changed, and the request tried to.
 *
 * <p><b>This is reported rather than ignored, and the reason is the whole point of the exception.</b>
 * {@code sp_admin_upsert_tip_template}'s update branch writes {@code title_template},
 * {@code body_template}, {@code condition_type}, {@code default_priority} and {@code is_active}, and
 * does not write {@code code} at all. A {@code PATCH} carrying a different code would therefore
 * <em>succeed</em> at the database, change nothing, and let the API answer "saved" - leaving the
 * caller believing a change was made that was not. Silently accepting it is the dangerous behaviour,
 * not a harmless one.
 *
 * <p>The service compares the stored code with the one sent before calling, so the refusal happens
 * where it can be reported. Sending the <em>same</em> code is accepted as a no-op, deliberately: a
 * client editing a template's priority can round-trip the full representation it read without having
 * to strip a field out first.
 *
 * <p>A conflict rather than a validation error: the code the caller sent is a perfectly valid code,
 * and possibly even a valid alternative name. What makes the request fail is that this field of this
 * row does not move.
 */
public class TipTemplateCodeImmutableException extends ApiException {

    public TipTemplateCodeImmutableException(String message) {
        super(ErrorCode.TIP_TEMPLATE_CODE_IMMUTABLE, message);
    }
}
