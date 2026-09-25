package com.campuscoin.auth.dto;

/**
 * The sign-in result (UC-02 B2), returned by both the student and the administrator endpoint.
 *
 * <pre>
 * {
 *   "accessToken": "...",
 *   "tokenType": "Bearer",
 *   "expiresIn": 7200,
 *   "user": { "id": 2, "fullName": "Alex Nguyen", "email": "...", "role": "STUDENT" }
 * }
 * </pre>
 *
 * <p>{@code tokenType} is the literal {@code "Bearer"}: it is the scheme the client must put in
 * the {@code Authorization} header, and sending it as part of the response means the client does
 * not have to know it independently.
 *
 * <p>{@code expiresIn} is the remaining lifetime in <b>seconds</b>, which is what OAuth 2 clients
 * expect and what lets Angular schedule a renewal or a redirect before the token dies. It is
 * derived from the same {@code auth.session_ttl_minutes} setting the session row uses, so the
 * two cannot disagree.
 *
 * <p>The raw token appears exactly once, here, and is never stored server-side or logged; the
 * database keeps only its SHA-256 hash. No refresh token is returned, because no use case defines
 * a refresh flow.
 */
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
