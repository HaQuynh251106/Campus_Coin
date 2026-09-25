package com.campuscoin.auth.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Answers an authenticated-but-not-permitted request with 403.
 *
 * <p>UC-05 E1: a student reaching an administrator-only URL is rejected on the server, not by
 * hiding the page. The caller is known here, so 403 is correct and different from the 401 the
 * entry point returns - the client should show a "not permitted" state, not send the user back
 * to sign-in.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ApiError body = ApiError.of(ErrorCode.ACCESS_DENIED,
                "You do not have permission to perform this action.", request.getRequestURI());

        response.setStatus(ErrorCode.ACCESS_DENIED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
