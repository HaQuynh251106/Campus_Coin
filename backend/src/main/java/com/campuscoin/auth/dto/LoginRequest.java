package com.campuscoin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login} (UC-02) and
 * {@code POST /api/v1/admin/auth/login} (UC-05).
 *
 * <p>One record for both endpoints because the credentials are the same; what differs is the
 * endpoint, the role expected and the access policy, not the payload.
 *
 * <p>No length or complexity rule is applied to {@code password} here - only a non-blank check.
 * A sign-in request must be accepted and then rejected on its merits, because a validation error
 * on the password shape would distinguish "this could never be a password" from "wrong password"
 * and give an attacker a way to probe. The upper size bound only guards against an oversized
 * body reaching bcrypt.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required.")
        @Size(max = 190, message = "Email must be at most 190 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(max = 72, message = "Password must be at most 72 characters.")
        String password) {
}
