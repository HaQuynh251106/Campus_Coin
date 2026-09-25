package com.campuscoin.budget.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.budget.dto.BudgetResponse;
import com.campuscoin.budget.dto.CreateBudgetRequest;
import com.campuscoin.budget.dto.UpdateBudgetRequest;
import com.campuscoin.budget.service.BudgetService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Setting and managing a student's monthly spending limits: UC-13.
 *
 * <p>Five endpoints: list, read, create, edit and delete. They are addressed by id, so ownership
 * cannot be implied by the URL - it is enforced by queries that take the caller's id alongside the
 * record's, and asserted by tests in which one student tries to reach another's budget.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, a spend figure or a status: those are the
 * fields a client would use to write a limit it does not own, to report spending that did not
 * happen, or to claim a budget is on track when the alert log disagrees.
 *
 * <p><b>There is no endpoint here that raises a budget alert, and that is deliberate.</b> An alert is
 * UC-14's, and it is raised when a <em>transaction</em> pushes a month's spending past a threshold -
 * {@code sp_check_budget_alerts}, called from the transaction triggers, which is why module 4's
 * create and update endpoints already document it. Setting a limit is a target, not a thing that gets
 * exceeded, so creating one writes no alert. What every response here carries is the month's
 * consumption as the database computes it, so a student who sets a limit they have already passed
 * sees {@code EXCEEDED} at once - without a second alert being written, which BR-12 forbids.
 */
@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets",
        description = "Setting and managing a student's monthly spending limits per category "
                + "(UC-13).")
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    @Operation(
            summary = "List my spending limits",
            description = """
                    Returns the caller's limits for one month, each with how much of it has been \
                    used. `month` selects the month as `yyyy-MM` and defaults to the current one, \
                    which is the month a budgets screen opens on.

                    Every derived figure is the database's: `spentAmount` counts only records that \
                    are not in the trash (BR-09), `remainingAmount` is negative once the limit is \
                    passed, and `consumptionStatus` is `NEAR` or `EXCEEDED` against the thresholds \
                    an administrator configures. The same comparison decides when an alert is \
                    raised, so a row reading `EXCEEDED` here and a `BUDGET_EXCEEDED` notification \
                    are two views of one fact.

                    A month in which the caller has set nothing returns an empty list.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's limits for the month."),
            @ApiResponse(responseCode = "400",
                    description = "The month is not in `yyyy-MM` form or names no real month "
                            + "(VALIDATION_ERROR).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<BudgetResponse> listBudgets(
            @AuthenticationPrincipal AuthenticatedUser principal,

            @Parameter(description = "Month to report, as `yyyy-MM`. Defaults to the current "
                    + "month.", example = "2026-09")
            @RequestParam(required = false) String month) {
        return budgetService.listBudgets(principal, month);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one of my spending limits",
            description = """
                    Returns one limit the caller owns, with the month's consumption, so a client can \
                    refresh a single row after a change without reloading the list.

                    Another student's limit is not reachable here, and neither is one that does not \
                    exist. Both answer `404`, so a client cannot use this endpoint to discover which \
                    budget identifiers exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's budget.",
                    content = @Content(schema = @Schema(implementation = BudgetResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such budget of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public BudgetResponse getBudget(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @PathVariable Long id) {
        return budgetService.getBudget(principal, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Set a spending limit",
            description = """
                    Sets a monthly limit for one expense category, owned by the caller. \
                    `categoryId` and `limitAmount` are required; `month` is optional and defaults to \
                    the current month.

                    The category must be one of the caller's own or a shared default one, must be an \
                    **expense** category (BR-11), and must not have been retired (BR-07). An income \
                    category is refused: a limit measures spending, and there is nothing to measure \
                    against income.

                    **At most one limit may exist per category per month** (BR-11). Setting a second \
                    one answers `409` (`BUDGET_ALREADY_EXISTS`) and names the existing budget, \
                    because the caller's real intent is to change a value they already set - which is \
                    what the update endpoint does.

                    Setting a limit raises no alert. Alerts are raised when a transaction pushes the \
                    month's spending past a threshold (UC-14); the response does carry the \
                    consumption so far, so a limit set below what has already been spent reads \
                    `EXCEEDED` immediately without a notification being written.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The limit that was set.",
                    content = @Content(schema = @Schema(implementation = BudgetResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, or the category is an income category or has "
                            + "been retired; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such category of the caller's to limit.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "A limit already exists for this category and month "
                            + "(BUDGET_ALREADY_EXISTS), or the category changed while the request "
                            + "was in flight (DATA_CONFLICT).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public BudgetResponse createBudget(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateBudgetRequest request) {
        return budgetService.create(principal, request);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Change one of my spending limits",
            description = """
                    Changes the limit for the month. `limitAmount` is the only field this operation \
                    takes, because it is the only part of a budget a student can meaningfully change: \
                    the category and the month are the row's identity (BR-11), and moving a limit \
                    onto another one is a different budget, which delete-then-create already \
                    expresses.

                    A request that sends no fields changes nothing and returns the budget as it \
                    stands.

                    Changing a limit does not retroactively raise or clear an alert. Lowering a limit \
                    below what has already been spent makes the budget read `EXCEEDED` at once - the \
                    status is recomputed on every read - but no second `BUDGET_EXCEEDED` \
                    notification appears, because BR-12 fires each threshold at most once per \
                    category per month.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated budget.",
                    content = @Content(schema = @Schema(implementation = BudgetResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such budget of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The budget changed while the request was in flight "
                            + "(DATA_CONFLICT). Reload and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public BudgetResponse updateBudget(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateBudgetRequest request) {
        return budgetService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Remove one of my spending limits",
            description = """
                    Removes a limit outright. No further confirmation is needed and nothing depends on \
                    it: the spending it measured belongs to the transactions, not to the limit, and a \
                    category with no limit simply stops being tracked against one.

                    The notifications already sent about this budget are **not** removed. A \
                    notification is a message the student received, and deleting the limit it was \
                    about should not erase the fact that they were told.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The limit was removed."),
            @ApiResponse(responseCode = "404", description = "No such budget of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void deleteBudget(@AuthenticationPrincipal AuthenticatedUser principal,
                             @PathVariable Long id) {
        budgetService.delete(principal, id);
    }
}
