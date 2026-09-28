package com.campuscoin.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.dto.AuthResponse;
import com.campuscoin.auth.dto.LoginRequest;
import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.auth.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/auth")
@Tag(name = "Administrator Authentication", description = "Dedicated administrator sign-in (UC-05).")
public class AdminAuthController {

    private final AuthService authService;

    public AdminAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Sign in as an administrator",
            description = """
                    Verifies the credentials and that the account holds the ADMIN role, then opens \
                    a security session exactly as the student portal does.

                    A student account is refused with 403, after the password has been checked, so \
                    the endpoint cannot be used to discover which addresses are administrator \
                    accounts. An unknown address and a wrong password produce the same 401, and a \
                    disabled account produces the disabled-account 401.

                    Repeated failures are throttled and answered with 429.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials, or the account is disabled.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The account does not hold the ADMIN role.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Too many failed attempts.",
                    content = @Content(schema = @Schema(implementation = com.campuscoin.common.exception.ApiError.class)))
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, UserRole.ADMIN,
                ClientAddress.of(httpRequest), httpRequest.getHeader("User-Agent"));
    }
}
