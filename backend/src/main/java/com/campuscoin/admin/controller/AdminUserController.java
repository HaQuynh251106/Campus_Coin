package com.campuscoin.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.AdminPasswordResetResponse;
import com.campuscoin.admin.dto.AdminUserResponse;
import com.campuscoin.admin.dto.SetUserStatusRequest;
import com.campuscoin.admin.service.AdminUserService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The accounts on the system: UC-22.
 *
 * <p>Three endpoints: list the accounts, enable or disable one, and send one a password reset link.
 * There is no {@code DELETE} - VĐ-06 says an administrator does not remove an account's credentials,
 * only block access to it, and disabling is reversible by the same endpoint that did it. There is no
 * {@code GET /{id}} either: the two writes return the account they acted on, so a bare read would be
 * the same row without the list's context.
 *
 * <p><b>Who is calling never comes from the request.</b> Every method reads the caller from the
 * verified bearer token, and the path id is only ever the account being acted on. A client cannot
 * claim to be another administrator, and no endpoint accepts an actor id it could set to itself.
 *
 * <p><b>The whole path is {@code ADMIN}-only</b>, enforced in {@code SecurityConfig} before any of these
 * methods runs. That is also what makes the {@code 404} on the two write endpoints safe: it does reveal
 * whether an account id exists, which module 10's bookmarks deliberately refuse to do ("not yours and
 * does not exist look identical"), and the difference is the caller. A student probing ids would be
 * learning about other students' rows; an administrator is already entitled to list every account on
 * this very screen.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Administration - users",
        description = "List the accounts, enable or disable one, and send a password reset link "
                + "(UC-22). Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @Operation(
            summary = "List every account",
            description = """
                    Returns every account on the system, students and administrators alike, oldest \
                    first.

                    Each row carries the account's sign-in address, name, role, status, academic year \
                    and last sign-in. **No credential material is published**: the password hash and \
                    the token version are not selected, so they cannot appear however this response is \
                    later changed - the projection has no field for them.

                    There is no filtering, searching or paging. UC-22 asks for the list, and a client \
                    that wants a subset has the whole of it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every account, oldest first."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<AdminUserResponse> listUsers() {
        return adminUserService.list();
    }

    @PostMapping("/{id}/status")
    @Operation(
            summary = "Enable or disable an account",
            description = """
                    Moves one account to `ACTIVE` or `DISABLED` and returns it in its new state.

                    **Disabling takes effect immediately.** Every session the account holds is revoked \
                    and its token version is incremented, so a token that was issued before this call \
                    stops working at once - it is not left valid until it expires (BR-03). Enabling \
                    restores sign-in; the next sign-in issues a fresh token.

                    **An administrator cannot disable their own account** (`409 \
                    SELF_DISABLE_FORBIDDEN`). With one administrator, doing so would leave nobody able \
                    to undo it, because every administrative endpoint requires an active \
                    administrator. Re-enabling your own account is permitted.

                    Sending the status the account is already in is accepted and recorded. The account \
                    is returned either way, so a client does not have to decide whether to re-fetch.

                    The action is recorded in the audit trail, whether it changed anything or not.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account, in its new state.",
                    content = @Content(schema = @Schema(implementation = AdminUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "`status` is missing or not ACTIVE/DISABLED.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account has this id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The caller asked to disable their own account "
                            + "(SELF_DISABLE_FORBIDDEN), or the account changed during the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AdminUserResponse setUserStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody SetUserStatusRequest request,
            HttpServletRequest httpRequest) {
        return adminUserService.setStatus(
                principal, id, request, AdminRequestContext.clientAddress(httpRequest));
    }

    @PostMapping("/{id}/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Send an account a password reset link",
            description = """
                    Sends the account a link with which its owner chooses a new password, and answers \
                    `202` with a fixed confirmation message.

                    **An administrator never sets, sees or receives a password or a link** (VĐ-06). This \
                    endpoint issues a one-time token, stores only its hash, and delivers the link to the \
                    address on the account. Nothing in the response contains it, and the confirmation \
                    message is the same every time - it names the mechanism, not the delivery.

                    The link is the same one the student's own "forgot password" flow produces, so it \
                    works on the same screen. Its cost, its lifetime and its one-time use are the same \
                    rules (BR-04), and asking twice invalidates the first link.

                    **`202`, not `200`**: the work is an email that has not been confirmed to have \
                    arrived, and no resource was created that this response could describe.

                    Unlike the public reset request, this one **answers `404` for an unknown id**. The \
                    public endpoint is addressed by email and stays silent about whether an address \
                    exists, to stop it being used to enumerate accounts; an administrator is already \
                    entitled to the account list, so silence here would only make a typo harder to find.

                    The action is recorded in the audit trail. No account is created, modified or \
                    removed by this endpoint.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "The reset link was sent.",
                    content = @Content(schema = @Schema(implementation = AdminPasswordResetResponse.class))),
            @ApiResponse(responseCode = "404", description = "No account has this id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The account changed during the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AdminPasswordResetResponse sendPasswordReset(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        return adminUserService.sendPasswordReset(
                principal, id, AdminRequestContext.clientAddress(httpRequest));
    }
}
