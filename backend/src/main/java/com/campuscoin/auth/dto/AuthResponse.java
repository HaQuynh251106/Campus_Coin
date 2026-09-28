package com.campuscoin.auth.dto;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummaryResponse user) {

    public static final String BEARER = "Bearer";

    public static AuthResponse of(String accessToken, long expiresInSeconds, UserSummaryResponse user) {
        return new AuthResponse(accessToken, BEARER, expiresInSeconds, user);
    }
}
