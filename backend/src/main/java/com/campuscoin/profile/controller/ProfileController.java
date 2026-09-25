package com.campuscoin.profile.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.profile.dto.ProfileResponse;
import com.campuscoin.profile.dto.UpdatePreferencesRequest;
import com.campuscoin.profile.dto.UpdateProfileRequest;
import com.campuscoin.profile.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The student's own profile and display preferences: UC-04 and UC-27.
 *
 * <p>Three endpoints, no more. There is no {@code /users/{id}} route: UC-04 is the student
 * managing <em>their own</em> profile, and an endpoint addressed by identifier would need an
 * ownership check that the token-based design makes unnecessary. An administrator acting on
 * another account is UC-22, which is module 11 and has its own contract.
 *
 * <p>There is no separate {@code PUT} for the whole profile either. Only the two endpoints the
 * use cases describe exist, and both are {@code PATCH} because a client sends just the fields it
 * changed.
 *
 * <p>Each method reads the caller with {@code @AuthenticationPrincipal}, so the identity comes
 * from the verified bearer token. No endpoint here accepts a user id, a role or an account
 * status, which is what keeps BR-02 and section 7.5 structural rather than a check that could be
 * forgotten.
 */
@RestController
@RequestMapping("/api/v1/profile")
@Tag(name = "Profile & Preferences",
        description = "The signed-in student's own profile and display preferences (UC-04, UC-27).")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get my profile and preferences",
            description = """
                    Returns the signed-in student's profile fields and display preferences. \
                    Always the caller's own record: the account is taken from the bearer token, \
                    not from a parameter, so there is no identifier to tamper with.

                    `academicYear` is null when the student has not set one. The response never \
                    contains the password hash, the token version, the role or the account status.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's profile.",
                    content = @Content(schema = @Schema(implementation = ProfileResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ProfileResponse getMyProfile(@AuthenticationPrincipal AuthenticatedUser principal) {
        return profileService.getProfile(principal);
    }

    @PatchMapping("/me")
    @Operation(
            summary = "Update my profile",
            description = """
                    Changes the supplied profile fields and leaves the rest as they are. Send \
                    only what you changed.

                    A field sent as `null` is treated as "not changed". To clear `academicYear`, \
                    send it as an empty string.

                    The four fields are the whole of UC-04. There is no way to change the email \
                    address, the password, the role or the account status here: none of those is \
                    part of the use case, and each would be a route to escalate the account.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated profile.",
                    content = @Content(schema = @Schema(implementation = ProfileResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ProfileResponse updateMyProfile(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateProfile(principal, request);
    }

    @PatchMapping("/me/preferences")
    @Operation(
            summary = "Update my display preferences",
            description = """
                    Changes the appearance and text-size preferences (UC-27). Send only what you \
                    changed; a field sent as `null` is left as it is.

                    Both values must be one of the members the database stores: `LIGHT`, `DARK` or \
                    `SYSTEM` for the theme, and `SMALL`, `MEDIUM`, `LARGE` or `XLARGE` for the text \
                    size. Any other value is rejected as a field error.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated profile.",
                    content = @Content(schema = @Schema(implementation = ProfileResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "An unrecognised preference value; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ProfileResponse updateMyPreferences(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Valid @RequestBody UpdatePreferencesRequest request) {
        return profileService.updatePreferences(principal, request);
    }
}
