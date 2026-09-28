package com.campuscoin.auth.dto;

public record PasswordResetVerifyResponse(boolean valid) {

    public static final PasswordResetVerifyResponse VALID = new PasswordResetVerifyResponse(true);
}
