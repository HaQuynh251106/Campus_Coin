package com.campuscoin.auth.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Answers an unauthenticated request to a protected endpoint with 401.
 *
 * <p>The security filter chain runs before the DispatcherServlet, so
 * {@code GlobalExceptionHandler} never sees this failure. Without an entry point Spring
 * Security would send a redirect to a login page or an empty body; Angular needs the same
 * {@link ApiError} JSON as every other failure, so the contract is the same everywhere
 * (UC-02 E1: an unauthenticated request is answered with 401 so the client can route to sign-in).
 *
 * <p>The message is fixed and says nothing about why authentication failed - no token, bad
 * signature, expired, revoked and wrong token version are one condition to the caller.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ApiError body = ApiError.of(ErrorCode.UNAUTHENTICATED,
                "Authentication is required to access this resource.", request.getRequestURI());

        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
