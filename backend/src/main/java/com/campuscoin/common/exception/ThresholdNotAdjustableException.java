package com.campuscoin.common.exception;

/**
 * UC-23 / VĐ-05: the setting exists, but this API may not change it.
 *
 * <p>{@code system_settings} holds sixteen rows; {@code sp_admin_set_threshold} permits six. The
 * other ten are not mistakes to be corrected - {@code app.currency} and {@code app.timezone} are set
 * for the deployment, {@code auth.session_ttl_minutes} and {@code auth.max_login_attempts} are read
 * by the application rather than by that procedure, {@code anomaly.*} belongs to a capability this
 * build does not enable, and {@code ai.*} to one it does not have. The distinction is published:
 * {@code GET /api/v1/admin/settings} marks each row {@code adjustable}, computed from the same
 * allow-list this exception enforces, so a client cannot be surprised by it - and this code answers a
 * request that ignored the flag.
 *
 * <p>A conflict rather than a {@code 404}: the key is real and readable through the list endpoint.
 * Answering "not found" would suggest the setting does not exist, which would send the caller looking
 * for a spelling mistake instead of understanding that the value is fixed by configuration. It is
 * also not a field error, because the value the caller may have sent is not what is wrong.
 */
public class ThresholdNotAdjustableException extends ApiException {

    public ThresholdNotAdjustableException(String message) {
        super(ErrorCode.THRESHOLD_NOT_ADJUSTABLE, message);
    }
}
