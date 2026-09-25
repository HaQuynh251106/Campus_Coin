package com.campuscoin.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.CreateTipTemplateRequest;
import com.campuscoin.admin.dto.TipTemplateResponse;
import com.campuscoin.admin.dto.UpdateTipTemplateRequest;
import com.campuscoin.admin.service.AdminTipTemplateService;
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
 * The text of the advice the tip generator produces: UC-21 B3 and B4.
 *
 * <p>Three endpoints: list, create and edit. There is no delete, because
 * {@code user_tips.tip_template_id} restricts deletion and a template that is switched off with its
 * history intact is what the audit trail is for. There is no {@code PUT}: the update is partial by
 * nature, since the database procedure writes each column as {@code IFNULL(new, old)}.
 *
 * <p><b>A template is not a tip.</b> This controller manages the wording and the condition that
 * {@code sp_generate_tips} renders and decides by - "you have spent {amount} of your {limit}" and the
 * rule that says when to say it. The tips students actually receive are UC-18 and belong to the tips
 * module; nothing here can read, change or produce one. Switching a template off stops it producing
 * new advice and leaves every tip already generated untouched.
 *
 * <p><b>{@code code} is immutable, and sending a different one is refused rather than ignored.</b>
 * {@code sp_admin_upsert_tip_template}'s update branch does not write the column at all, so accepting
 * the request would report success while nothing moved. Sending the current code unchanged is allowed,
 * so a client can round-trip a whole template through this endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/tip-templates")
@Tag(name = "Administration - tip templates",
        description = "Manage the templates the saving-tip generator renders advice from (UC-21). "
                + "Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminTipTemplateController {

    private final AdminTipTemplateService adminTipTemplateService;

    public AdminTipTemplateController(AdminTipTemplateService adminTipTemplateService) {
        this.adminTipTemplateService = adminTipTemplateService;
    }

    @GetMapping
    @Operation(
            summary = "List the tip templates",
            description = """
                    Returns every tip template, ordered by display priority then identifier.

                    **Templates that are switched off are included**, because `isActive: false` is how \
                    one is withdrawn without being deleted, and the withdrawn one is exactly what an \
                    administrator needs to find in order to restore it.

                    `conditionType` is the rule the template belongs to, not a label: it is what \
                    decides which students receive this advice when tips are generated. \
                    `defaultPriority` orders templates when several apply - lower first.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every template, in display order."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<TipTemplateResponse> listTipTemplates() {
        return adminTipTemplateService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a tip template",
            description = """
                    Creates a template and returns it.

                    `code` identifies the template and is stored upper case, so `over_budget` and \
                    `OVER_BUDGET` are the same code. A code already in use is refused with `409 \
                    TIP_TEMPLATE_CODE_TAKEN` - and the code cannot be changed afterwards, so it is worth \
                    choosing deliberately.

                    `titleTemplate` and `bodyTemplate` are the wording, rendered with the student's own \
                    figures at generation time; `{amount}`, `{limit}` and `{category_name}` are \
                    substituted. They cannot be blank: a template with no text has nothing to render.

                    `conditionType` ties the template to a rule and defaults to `GENERIC`, which applies \
                    regardless of condition. Note that a template is only used when its condition \
                    matches - a `NEAR_BUDGET` template produces nothing for a student who is not near a \
                    limit.

                    `isActive: false` creates the template already switched off, so it produces no \
                    tips until an administrator turns it on.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The template that was created.",
                    content = @Content(schema = @Schema(implementation = TipTemplateResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The code is already in use "
                            + "(TIP_TEMPLATE_CODE_TAKEN).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipTemplateResponse createTipTemplate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateTipTemplateRequest request,
            HttpServletRequest httpRequest) {
        return adminTipTemplateService.create(
                request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Change a tip template",
            description = """
                    Changes one or more fields of a tip template and returns it.

                    **Omit a field to leave it as it is.** `titleTemplate` and `bodyTemplate` cannot be \
                    sent blank - there is no way to empty a template through this endpoint, and a \
                    template with no text is refused rather than stored. `conditionType` is what ties \
                    the template to a rule, so changing it changes which students receive this advice; \
                    `defaultPriority` changes when it is shown relative to others.

                    `isActive: false` stops the template producing new tips. **Tips already generated \
                    are not affected** - they are the students' own rows - and switching the template \
                    back on makes it eligible again.

                    **`code` cannot be changed.** Omit it, or send it exactly as the template currently \
                    has it; sending a different one is `409 TIP_TEMPLATE_CODE_IMMUTABLE` and nothing is \
                    written. It is refused rather than ignored because the database would accept the \
                    request, change nothing, and report success.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The template, as it now stands.",
                    content = @Content(schema = @Schema(implementation = TipTemplateResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No tip template has this id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "A different `code` was sent (TIP_TEMPLATE_CODE_IMMUTABLE), or the "
                            + "template changed during the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipTemplateResponse updateTipTemplate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTipTemplateRequest request,
            HttpServletRequest httpRequest) {
        return adminTipTemplateService.update(
                id, request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }
}
