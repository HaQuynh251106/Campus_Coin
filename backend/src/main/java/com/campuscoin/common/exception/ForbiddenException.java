package com.campuscoin.common.exception;

/**
 * UC-05 A1 / E1: the caller is authenticated but their role does not permit this endpoint.
 *
 * <p>Used when a STUDENT calls the administrator sign-in endpoint, or when a student token
 * reaches an administrator-only route. Distinct from 401: the caller is known, they are simply
 * not allowed (section 7.5).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.ACCESS_DENIED, message);
    }
}
