package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /api/v1/admin/users/{id}/password-reset} answers with (UC-22 B4).
 *
 * <p><b>There is no token field, and that is the whole of this type's design.</b> UC-22 B4 is "the
 * administrator sends the student a reset link": the raw token travels through
 * {@code PasswordResetNotifier} to the account owner's address, exactly as UC-03 B2 does, and never
 * through an HTTP response. An administrator who could read the token off the screen would be able
 * to take over the account, which is the opposite of VĐ-06's "an administrator only <em>sends</em> a
 * reset link". A test asserts that the serialised body contains no token-shaped value.
 *
 * <p>A record with one field rather than a bare message string, so the response is an object like
 * every other in this API and a field can be added later without changing the shape a client parses.
 *
 * <p>{@code 202 Accepted} is the status this accompanies, and it is the honest one: the request has
 * been accepted and the token has been created, but the link's delivery is the notifier's business
 * and its outcome is deliberately not reflected in the response - the same position
 * {@code PasswordResetService} records for UC-03, where a send that fails must not change the
 * answer.
 */
@Schema(description = "Confirmation that a reset link was sent (UC-22 B4).")
public record AdminPasswordResetResponse(

        @Schema(description = "A fixed message. The reset link is sent to the account's email "
                + "address and is never returned here.",
                example = "A password reset link has been sent to the account's email address.")
        String message) {
}
