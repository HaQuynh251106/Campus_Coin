package com.campuscoin.common.exception;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error body shape returned by every endpoint in every module.
 *
 * <pre>
 * {
 *   "timestamp": "2026-09-25T10:15:30Z",
 *   "status": 400,
 *   "errorCode": "VALIDATION_ERROR",
 *   "message": "Request validation failed.",
 *   "path": "/api/v1/auth/register",
 *   "fieldErrors": [ { "field": "email", "message": "must be a well-formed email address" } ]
 * }
 * </pre>
 *
 * <p>{@code fieldErrors} is omitted entirely when empty, so a non-validation error stays small.
 * Nothing here is ever populated from a SQLException message or a stack trace.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    /** One rejected request field, aligned with a Jakarta Bean Validation constraint. */
    public record FieldError(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, path, null);
    }

    public static ApiError of(ErrorCode code, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, path, fieldErrors);
    }
}
