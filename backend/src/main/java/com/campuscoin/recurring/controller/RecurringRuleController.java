package com.campuscoin.recurring.controller;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.recurring.dto.CreateRecurringRuleRequest;
import com.campuscoin.recurring.dto.RecurringRuleResponse;
import com.campuscoin.recurring.dto.UpdateRecurringRuleRequest;
import com.campuscoin.recurring.service.RecurringRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Setting up and managing a student's recurring rules: UC-09.
 *
 * <p>Five endpoints: list, read, create, edit and delete. There is no separate pause, resume or end
 * endpoint, because those are not separate operations - each one sets {@code status} on the same
 * row, and a {@code /pause} route would be a second way to write it that could disagree with
 * {@code PATCH}. The contract documents the {@code status} values explicitly instead, including the
 * one that matters most: {@code ENDED} is how a rule is stopped for good.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, a type or a status at creation: those are
 * the fields a client would use to write a rule it does not own, disagree with its category
 * (BR-05), or create one already stopped.
 *
 * <p>There is no endpoint that runs the scheduler. Which periods are due is
 * {@code sp_post_recurring_transactions}'s decision, made once a day by
 * {@code com.campuscoin.recurring.scheduler.RecurringScheduler}; exposing it would let a client
 * trigger posting for every student in the system, and giving a student a way to post their own
 * rules early would make the schedule advisory rather than authoritative.
 */
@RestController
@RequestMapping("/api/v1/recurring-rules")
@Tag(name = "Recurring rules",
        description = "Setting up, editing, pausing, ending and removing a student's repeating "
                + "income and expenses (UC-09).")
@SecurityRequirement(name = "bearerAuth")
public class RecurringRuleController {

    private final RecurringRuleService recurringRuleService;

    public RecurringRuleController(RecurringRuleService recurringRuleService) {
        this.recurringRuleService = recurringRuleService;
    }

    @GetMapping
    @Operation(
            summary = "List my recurring rules",
            description = """
                    Returns every rule the caller owns, soonest to run first, whatever its status. \
                    Paused and ended rules are included: a paused rule is one the student means to \
                    resume, so hiding it would leave no way to find it again.

                    `type` is derived from the rule's category and is read-only - one source of \
                    truth for whether a rule posts income or expense (BR-05). `nextRunDate` is \
                    what the scheduler reads next; `lastRunDate` is absent for a rule that has not \
                    run yet, which is how a client tells a new rule from a running one.

                    Nothing here posts a transaction. Rules are turned into transactions once a \
                    day by the scheduler, which catches up on any periods missed while the \
                    application was down.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's rules."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<RecurringRuleResponse> listRules(@AuthenticationPrincipal AuthenticatedUser principal) {
        return recurringRuleService.listRules(principal);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one of my recurring rules",
            description = """
                    Returns one rule the caller owns, so a client can refresh a single row after a \
                    change without reloading the list.

                    Another student's rule is not reachable here, and neither is one that does not \
                    exist. Both answer `404`, so a client cannot use this endpoint to discover \
                    which rule identifiers exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's rule.",
                    content = @Content(schema = @Schema(implementation = RecurringRuleResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such rule of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RecurringRuleResponse getRule(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @PathVariable Long id) {
        return recurringRuleService.getRule(principal, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a recurring rule",
            description = """
                    Sets up a repeating income or expense owned by the caller. `categoryId`, \
                    `amount`, `frequency` and `startDate` are required; the rest are optional.

                    The rule's type is not sent: it is the chosen category's type (BR-05). The \
                    category must be one of the caller's own or a shared default one, and must not \
                    have been retired (BR-07). A new rule is always `ACTIVE`.

                    `nextRunDate` is the date of the first occurrence and defaults to `startDate`. \
                    Set it later than the start to prepare a rule in advance without it firing on \
                    creation. It may be in the future - a rule is a schedule, not a record, so \
                    BR-08's restriction on future-dated transactions does not apply to it; the \
                    transactions the scheduler creates from it are exempt for the same reason.

                    Creating a rule does not post anything. The scheduler turns due rules into \
                    transactions once a day, and catches up on any periods missed while the \
                    application was down.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The rule that was created.",
                    content = @Content(schema = @Schema(implementation = RecurringRuleResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, the category is retired, or the dates do not "
                            + "agree; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such category of the caller's to file under.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The category changed while the request was in flight "
                            + "(DATA_CONFLICT). Reload and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RecurringRuleResponse createRule(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRecurringRuleRequest request) {
        return recurringRuleService.create(principal, request);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Update one of my recurring rules",
            description = """
                    Changes the supplied fields and leaves the rest as they are. Send only what \
                    you changed. A field sent as `null` is treated as "not changed".

                    **Pausing, resuming and ending are this endpoint**, through `status`: `PAUSED` \
                    stops the rule posting without losing it, `ACTIVE` resumes it, and `ENDED` \
                    stops it for good. `ENDED` is final - an ended rule cannot be set back to \
                    `ACTIVE` or `PAUSED`, and asking to answers `409` \
                    (`RECURRING_RULE_ENDED`); create a new rule instead. Ending a rule is how it \
                    is retired - see the delete endpoint for why a rule that has already posted \
                    cannot be deleted.

                    An edit applies to the occurrences the scheduler has **not** posted yet. \
                    Transactions already generated keep the values they were posted with, and \
                    their periods are never re-posted (BR-16).

                    `startDate` cannot be changed: it is the rule's origin, and the periods already \
                    posted are derived from it. To move a rule onto a different day, change \
                    `nextRunDate`.

                    Moving a rule to another category is how its type changes (BR-05), and the type \
                    is re-derived from the new category - it is not sent. The new category must not \
                    be retired.

                    **A rule whose own category has been retired cannot be changed at all** - not \
                    its amount, not its status, not even to end it. This is enforced by the \
                    database rather than by this API, and it answers `409` (`CATEGORY_RETIRED`). \
                    Either move the rule to a category that is still in use, or re-enable the \
                    category, change the rule, and retire the category again.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated rule.",
                    content = @Content(schema = @Schema(implementation = RecurringRuleResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, the target category is retired, or the dates "
                            + "do not agree; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such rule or category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The rule has already ended and the request would move it out of "
                            + "ENDED (RECURRING_RULE_ENDED), the rule's own category has been "
                            + "retired (CATEGORY_RETIRED), or the data changed while the request "
                            + "was in flight (DATA_CONFLICT).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RecurringRuleResponse updateRule(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecurringRuleRequest request) {
        return recurringRuleService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete one of my recurring rules",
            description = """
                    Removes a rule outright. Permitted **only while the rule has generated no \
                    transactions**.

                    A rule that has already posted answers `409` (`RECURRING_RULE_IN_USE`), and \
                    that is not caution for its own sake: the transactions it created reference it, \
                    and although nothing in the database would stop the delete, removing the rule \
                    would leave them unable to be edited, deleted or restored again. **End the rule \
                    instead** - `PATCH` with `{"status": "ENDED"}` stops it posting for good and \
                    leaves everything it generated intact.

                    If the rule has never posted, deleting it is the right operation and this is \
                    how a rule set up by mistake is cleaned up.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The rule was removed."),
            @ApiResponse(responseCode = "404", description = "No such rule of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The rule has already generated transactions "
                            + "(RECURRING_RULE_IN_USE). End it instead.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void deleteRule(@AuthenticationPrincipal AuthenticatedUser principal,
                           @PathVariable Long id) {
        recurringRuleService.delete(principal, id);
    }
}
