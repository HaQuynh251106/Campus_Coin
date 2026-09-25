package com.campuscoin.common.exception;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/**
 * Turns every exception into the one documented {@link ApiError} shape.
 *
 * <p>Two rules hold for every handler here:
 * <ul>
 *   <li>The response never contains a stack trace, a SQL statement, a driver message, a
 *       credential or any other internal detail (section 7.7). Internal causes are logged
 *       server-side instead.</li>
 *   <li>Request bodies are never logged. Sign-in and reset requests carry passwords and
 *       tokens, and section 7.6 forbids writing those anywhere (section 7.6).</li>
 * </ul>
 *
 * <p>This advice covers exceptions raised inside the DispatcherServlet. Authentication and
 * authorisation failures thrown by the security filter chain never reach it, because the
 * chain runs before the servlet; those are handled by {@code RestAuthenticationEntryPoint}
 * and {@code RestAccessDeniedHandler}, which return the same body shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A validation rule that spans more than one field, such as a password not matching its
     * confirmation. Answered exactly like a Bean Validation failure - same code, same body, same
     * per-field list - so the client renders both the same way.
     */
    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiError> handleRequestValidation(RequestValidationException ex,
                                                            HttpServletRequest request) {
        log.debug("Request failed cross-field validation path={} fields={}", request.getRequestURI(),
                ex.getFieldErrors().stream().map(ApiError.FieldError::field).toList());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiError.of(ErrorCode.VALIDATION_ERROR, ex.getMessage(),
                        request.getRequestURI(), ex.getFieldErrors()));
    }

    /** Every deliberate application failure, including all UC-specific ones. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        // 4xx are expected outcomes of normal use, so they stay at debug/warn and carry no
        // stack trace. 5xx are genuine faults and are logged in full for diagnosis.
        if (code.status().is5xxServerError()) {
            log.error("Request failed path={} errorCode={}", request.getRequestURI(), code, ex);
        } else {
            log.debug("Request rejected path={} errorCode={} message={}",
                    request.getRequestURI(), code, ex.getMessage());
        }
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, ex.getMessage(), request.getRequestURI()));
    }

    /** UC-01 A2 / UC-03: Jakarta Bean Validation rejected one or more body fields. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        log.debug("Request failed validation path={} fields={}", request.getRequestURI(),
                fieldErrors.stream().map(ApiError.FieldError::field).toList());

        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiError.of(ErrorCode.VALIDATION_ERROR,
                        "Request validation failed.", request.getRequestURI(), fieldErrors));
    }

    /**
     * Body that is absent, not JSON, or has a field of the wrong type.
     *
     * <p>Two different situations reach this handler and they deserve different answers. A body
     * that is missing, truncated or not JSON at all is a malformed request: there is no field to
     * point at. A body that is well-formed JSON but whose <em>value</em> is wrong - an
     * unrecognised enum member, a string where a number belongs - is a field validation error,
     * and the caller needs the field name to render it beside the input. Jackson reports the
     * path to the offending field, so the second case is answered like a Bean Validation failure
     * instead of being flattened into "malformed".
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        // Jackson wraps the useful detail: the field path and the type it could not build.
        if (ex.getCause() instanceof InvalidFormatException invalid
                && !invalid.getPath().isEmpty()) {
            String field = invalid.getPath().stream()
                    .map(reference -> reference.getFieldName() != null
                            ? reference.getFieldName()
                            : "[" + reference.getIndex() + "]")
                    .collect(Collectors.joining("."));

            // The target type is not echoed: it would expose a Java class name. The message names
            // what is acceptable in general terms, which is all the client needs.
            List<ApiError.FieldError> fieldErrors = List.of(new ApiError.FieldError(
                    field, "The value is not one of the accepted values for this field."));

            log.debug("Unreadable field path={} field={}", request.getRequestURI(), field);
            return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                    .body(ApiError.of(ErrorCode.VALIDATION_ERROR, "Request validation failed.",
                            request.getRequestURI(), fieldErrors));
        }

        log.debug("Unreadable request body path={} reason={}", request.getRequestURI(),
                ex.getClass().getSimpleName());
        return build(ErrorCode.MALFORMED_REQUEST,
                "The request body is missing or malformed.", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParameter(Exception ex, HttpServletRequest request) {
        log.debug("Bad request parameter path={} type={}", request.getRequestURI(),
                ex.getClass().getSimpleName());
        return build(ErrorCode.INVALID_REQUEST,
                "A required request parameter is missing or has the wrong type.", request);
    }

    /**
     * A database constraint rejected the write.
     *
     * <p>BR-01 makes {@code users.email} unique, so a duplicate key on registration means the
     * address is taken. That is reported as {@link ErrorCode#EMAIL_ALREADY_REGISTERED}, which is
     * the same answer the pre-check gives; anything else is a generic conflict. The driver's
     * message is logged but never echoed, because it would disclose the table and column names.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        String driverMessage = ex.getMostSpecificCause().getMessage();
        log.warn("Database constraint rejected the write path={}", request.getRequestURI(), ex);

        if (driverMessage != null && driverMessage.contains("uk_users_email")) {
            return build(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "This email address is already registered. Please sign in or reset your password.",
                    request);
        }
        return build(ErrorCode.DATA_CONFLICT,
                "The request conflicts with existing data.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                         HttpServletRequest request) {
        log.debug("Authentication failed path={}", request.getRequestURI());
        return build(ErrorCode.UNAUTHENTICATED, "Authentication is required.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        log.debug("Access denied path={}", request.getRequestURI());
        return build(ErrorCode.ACCESS_DENIED,
                "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex,
                                                   HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "The requested resource was not found.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        return build(ErrorCode.INVALID_REQUEST,
                "The HTTP method is not supported by this endpoint.", request);
    }

    /**
     * Last resort. The message is fixed and generic: whatever the real exception says could
     * describe the schema, a file path, or a dependency version, none of which belong in a
     * response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception path={}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

    private ApiError.FieldError toFieldError(FieldError fieldError) {
        // The default message is used as-is so validation text stays in one place: the DTO
        // annotations. It never contains user input, only the constraint description.
        return new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, message, request.getRequestURI()));
    }
}
