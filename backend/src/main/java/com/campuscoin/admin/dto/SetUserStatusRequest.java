package com.campuscoin.admin.dto;

import com.campuscoin.auth.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/admin/users/{id}/status} (UC-22 B3): enable or disable one account.
 *
 * <p><b>One field, and only the two values the column holds.</b> Typing it as
 * {@link AccountStatus} rather than a string means the schema advertises exactly what MySQL accepts,
 * so there is no second vocabulary to drift and an unknown value is a field error naming the
 * accepted ones rather than a database refusal.
 *
 * <p><b>Required, not optional.</b> A status endpoint with nothing to set has no meaning, and the
 * three states a request could be in - absent, {@code null}, or a value - would all have to be given
 * a meaning, of which only one is real. Requiring it makes "I forgot the field" a validation error
 * instead of an operation that quietly did nothing.
 *
 * <p>This is a <em>transition</em>, not a field edit, which is why it is a {@code POST} to
 * {@code /status} rather than a {@code PATCH} of the resource: disabling an account also revokes
 * every open session and bumps {@code token_version} (BR-03), which is more than the named field.
 * It is the same shape as {@code POST /tips/{id}/state}.
 *
 * <p>There is deliberately no way to express "disable whoever this is" or to target an account by
 * email. The id is the address of the operation and the actor is read from the caller's token -
 * {@code sp_set_user_status} refuses a self-disable regardless, but the request body never carries
 * an identity at all.
 */
@Schema(description = "The status to move an account to (UC-22 B3).")
public record SetUserStatusRequest(

        @Schema(description = "`DISABLED` blocks sign-in and immediately invalidates the "
                + "account's existing tokens and sessions. `ACTIVE` restores access. An "
                + "administrator cannot disable their own account.",
                example = "DISABLED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose a status: ACTIVE or DISABLED.")
        AccountStatus status) {
}
