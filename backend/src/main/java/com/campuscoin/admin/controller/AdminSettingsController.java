package com.campuscoin.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.SystemSettingResponse;
import com.campuscoin.admin.dto.UpdateThresholdRequest;
import com.campuscoin.admin.service.AdminSettingsService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/settings")
@Tag(name = "Administration - settings",
        description = "Read the system configuration and tune the business thresholds (UC-23, VĐ-05). "
                + "Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSettingsController {

    private final AdminSettingsService adminSettingsService;

    public AdminSettingsController(AdminSettingsService adminSettingsService) {
        this.adminSettingsService = adminSettingsService;
    }

    @GetMapping
    @Operation(
            summary = "List every setting",
            description = """
                    Returns every configuration row, ordered by key.

                    `adjustable` is the field to read first: **true means \
                    `PATCH /api/v1/admin/settings/{key}` will change this row, false means it will be \
                    refused** with `409 THRESHOLD_NOT_ADJUSTABLE`. The read-only rows are still shown - \
                    the currency a deployment runs in, and the session and login-attempt policy, are \
                    facts about the system being administered.

                    `value` is the stored text and `valueType` says how to read it: `INT`, `DECIMAL`, \
                    `BOOLEAN`, `STRING` or `JSON`. A `DECIMAL` threshold is not formatted or rounded \
                    here, and a `BOOLEAN` is the text `true` or `false` rather than a JSON boolean.

                    Which rows are adjustable is decided by the same rule the update below enforces, \
                    so a row reported as changeable cannot then be refused, or the reverse.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every setting, ordered by key."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<SystemSettingResponse> listSettings() {
        return adminSettingsService.list();
    }

    @PatchMapping("/{key}")
    @Operation(
            summary = "Change a business threshold",
            description = """
                    Sets one threshold to a new value and returns the row.

                    **Only the six tunable rows can be changed**, and each is addressed by its key:

                    | Key | What it does |
                    |---|---|
                    | `budget.near_threshold_pct` | The share of a spending limit at which a student is warned they are close to it |
                    | `budget.exceeded_threshold_pct` | The share at which a category counts as over budget |
                    | `insight.spike_threshold_pct` | How far above its recent baseline a category's spending must rise before it is flagged |
                    | `insight.spike_baseline_months` | How many months of history that baseline is taken from (a whole number, 1 to 12) |
                    | `tips.max_dashboard` | How many tips are shown on a dashboard at once |
                    | `auth.reset_token_ttl_minutes` | How long a password reset link stays usable |

                    Any other key answers `409 THRESHOLD_NOT_ADJUSTABLE`, including keys that exist in \
                    the table - `GET` reports which those are.

                    The value is sent as text. `insight.spike_baseline_months` must be a whole number \
                    from 1 to 12; the others must be positive numbers. Anything else is `400` with a \
                    field error on `value`.

                    **Changes take effect for behaviour generated after this call.** A threshold is \
                    read when a budget is evaluated or tips are generated, so nothing already recorded \
                    is recomputed, and a student's existing rows are not revisited.

                    The change is recorded in the audit trail with its previous and new value.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The setting, with its new value.",
                    content = @Content(schema = @Schema(implementation = SystemSettingResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`value` is blank, is not a number, or does not fit the key's range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No setting has this key.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The setting exists but cannot be changed through this API "
                            + "(THRESHOLD_NOT_ADJUSTABLE).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SystemSettingResponse updateSetting(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String key,
            @Valid @RequestBody UpdateThresholdRequest request,
            HttpServletRequest httpRequest) {
        return adminSettingsService.update(
                key, request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }
}
