package com.campuscoin.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.dto.AuthResponse;
import com.campuscoin.auth.dto.LoginRequest;
import com.campuscoin.auth.dto.MessageResponse;
import com.campuscoin.auth.dto.PasswordResetCompleteRequest;
import com.campuscoin.auth.dto.PasswordResetRequestBody;
import com.campuscoin.auth.dto.PasswordResetTokenRequest;
import com.campuscoin.auth.dto.PasswordResetVerifyResponse;
import com.campuscoin.auth.dto.RegisterRequest;
import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.service.AuthService;
import com.campuscoin.auth.service.PasswordResetService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The student-facing authentication endpoints: UC-01, UC-02 and UC-03.
 *
 * <p>Six endpoints, no more. There is no {@code /refresh}, no {@code /change-password}, no
 * session listing and no {@code /users/*} alias, because no use case in this module defines one;
 * each would be a new flow rather than a new URL for an existing one.
 *
 * <p>This controller does HTTP and nothing else: it binds and validates the body, reads the
 * client address for the session record, and hands off. The rules live in the services, so the
 * administrator controller can reuse them (UC-05).
 *
 * <p>Sign-in and registration are open to anonymous callers; sign-out requires a token, and the
 * identity is taken from that token rather than from the request.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Student registration, sign-in, sign-out and password reset "
        + "(UC-01, UC-02, UC-03).")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(
            summary = "Register a student account",
            description = """
                    Creates a STUDENT account with status ACTIVE. The password is stored only as a \
                    bcrypt hash. A new account uses the shared default category set, so no \
                    categories are created for it.

                    The response contains no body: registration does not sign the student in. \
                    Call /api/v1/auth/login afterwards (UC-01 B6).

                    Email verification is not part of this flow.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created."),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The email address is already registered.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Sign in as a student",
            description = """
                    Verifies the credentials and opens a security session. The response carries the \
                    access token, its lifetime in seconds and the account summary.

                    A wrong password and an unknown address produce the same 401 response, so this \
                    endpoint cannot be used to find out which addresses exist. A disabled account \
                    produces a different 401 that names the reason. An ADMIN account is refused \
                    with 403 here and must use the administrator portal.

                    Repeated failures are throttled and answered with 429.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials, or the account is disabled.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The account is an administrator account; use the administrator portal.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Too many failed attempts.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, UserRole.STUDENT,
                ClientAddress.of(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Sign out of the current session",
            description = """
                    Revokes the session that presented the token. The token stops working \
                    immediately, even though its own expiry has not passed.

                    Only the current session is affected: signing out on one device does not sign \
                    the student out elsewhere. Calling this endpoint twice is not an error.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Signed out."),
            @ApiResponse(responseCode = "401", description = "The access token is missing, invalid or expired.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        authService.logout(principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset/request")
    @SecurityRequirements
    @Operation(
            summary = "Request a password reset link",
            description = """
                    Sends a reset link if the address belongs to an account. The response message \
                    is identical whether or not the account exists, so this endpoint cannot be used \
                    to discover registered addresses.

                    Requesting a new link invalidates the account's earlier unused links: only one \
                    is valid at a time. The link is valid for 30 minutes.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The generic response, always.",
                    content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Too many reset requests.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public MessageResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequestBody request,
                                                HttpServletRequest httpRequest) {
        return new MessageResponse(
                passwordResetService.requestReset(request, ClientAddress.of(httpRequest)));
    }

    @PostMapping("/password-reset/verify")
    @SecurityRequirements
    @Operation(
            summary = "Check a password reset token",
            description = """
                    Confirms that the token from the reset link is still usable, so the \
                    new-password screen may open. The token is not consumed, so a student who \
                    opens the link and closes the tab can use it again until it expires.

                    The token is sent in the request body rather than the URL so it cannot be \
                    recorded in access logs or browser history.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The token is valid.",
                    content = @Content(schema = @Schema(implementation = PasswordResetVerifyResponse.class))),
            @ApiResponse(responseCode = "400", description = "The token is unknown, already used or expired.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public PasswordResetVerifyResponse verifyPasswordReset(@Valid @RequestBody PasswordResetTokenRequest request) {
        return passwordResetService.verifyToken(request);
    }

    @PostMapping("/password-reset/complete")
    @SecurityRequirements
    @Operation(
            summary = "Set a new password using a reset token",
            description = """
                    Consumes the token and sets the new password. The token is single-use: a \
                    second attempt with the same link is rejected, and the student must request a \
                    new link.

                    On success every open session of the account is revoked and its token version \
                    is incremented, so all previously issued access tokens stop working at once. \
                    The student signs in again with the new password.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed.",
                    content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, or the token is invalid, already used or expired.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public MessageResponse completePasswordReset(@Valid @RequestBody PasswordResetCompleteRequest request) {
        return new MessageResponse(passwordResetService.completeReset(request));
    }
}
