package com.campuscoin.common.exception;

import java.util.List;

/**
 * A validation failure that Jakarta Bean Validation cannot express on a single field.
 *
 * <p>The case in this module is UC-01 B3 and UC-03 B7: a password and its confirmation must be
 * equal. A field annotation cannot compare two fields, and an assertion on the record as a whole
 * would report a form-level error that Angular could not attach to an input. Carrying the
 * {@link ApiError.FieldError} list here lets the response look exactly like a Bean Validation
 * failure - same error code, same body shape - so the client has one way to render validation
 * problems.
 */
public class RequestValidationException extends ApiException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public RequestValidationException(String message, List<ApiError.FieldError> fieldErrors) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
