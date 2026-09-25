package com.campuscoin.transaction.controller;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
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
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.transaction.dto.CreateTransactionRequest;
import com.campuscoin.transaction.dto.TransactionResponse;
import com.campuscoin.transaction.dto.UpdateTransactionRequest;
import com.campuscoin.transaction.service.TransactionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Recording and managing a student's transactions: UC-07 and UC-10.
 *
 * <p>Six endpoints: list, read, create, edit, delete and restore. They are addressed by id, so
 * ownership cannot be implied by the URL - it is enforced by queries that take the caller's id
 * alongside the record's ({@code TransactionRepository}), and asserted by tests in which one student
 * tries to reach another's transaction.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, a role, an account status or a
 * {@code source}: those are the fields a client would use to write a record it does not own, mark
 * its own record as reviewed, or date one in the future.
 *
 * <p><b>Delete and restore are separate operations rather than a {@code PATCH} of a flag.</b> BR-09
 * requires the record's history to show every removal and return, and the two stored procedures are
 * what append those rows. A request body able to set {@code isDeleted} would move the record without
 * the log entry, which is the one thing the soft-delete design exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions",
        description = "Recording, editing, deleting and restoring a student's income and expenses "
                + "(UC-07, UC-10).")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    @Operation(
            summary = "List my transactions",
            description = """
                    Returns the caller's transactions, newest first. `from` and `to` narrow the \
                    result to a date range and are both inclusive; either may be omitted.

                    Deleted records are left out unless `includeDeleted` is true, which is how a \
                    client finds a record again in order to restore it (UC-10 A1). Each returned \
                    record carries `isDeleted`, and a deleted one also carries `deletedAt`.

                    `type` is derived from the record's category and is read-only: the database \
                    keeps one source of truth for whether a record is income or expense (BR-05).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's transactions."),
            @ApiResponse(responseCode = "400",
                    description = "A date parameter is malformed, or the range is inverted "
                            + "(INVALID_REQUEST / VALIDATION_ERROR).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<TransactionResponse> listTransactions(
            @AuthenticationPrincipal AuthenticatedUser principal,

            @Parameter(description = "Earliest date to include, inclusive.",
                    example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @Parameter(description = "Latest date to include, inclusive. Defaults to today.",
                    example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @Parameter(description = "Include records that are in the trash.", example = "false")
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        return transactionService.listTransactions(principal, from, to, includeDeleted);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one of my transactions",
            description = """
                    Returns one transaction the caller owns, so a client can refresh a single row \
                    without reloading the list.

                    A deleted record is not reachable here, and neither is another student's. Both \
                    answer `404` rather than `403`, so a client cannot use this endpoint to \
                    discover which transaction identifiers exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's transaction.",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such transaction of the caller's, or it is deleted.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TransactionResponse getTransaction(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable Long id) {
        return transactionService.getTransaction(principal, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record a transaction",
            description = """
                    Records one income or expense owned by the caller. `categoryId`, `amount` and \
                    `txnDate` are required; `description` is optional.

                    The record's type is not sent: it is the chosen category's type (BR-05). The \
                    category must be one of the caller's own or a shared default category, and must \
                    not have been retired - a disabled category keeps its history but is not \
                    offered for new records (BR-07).

                    The date cannot be in the future (BR-08). A record created here is always \
                    `source: MANUAL`; the recurring scheduler and the CSV import write their own \
                    rows in later modules.

                    Recording an expense also raises any budget alert it crosses (UC-14), in the \
                    same database transaction - the client does not ask for that and cannot suppress \
                    it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The transaction that was recorded.",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, the category is retired, or the date is in "
                            + "the future; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such category of the caller's to file under.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The category or the date changed while the request was in "
                            + "flight (DATA_CONFLICT). Reload and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TransactionResponse createTransaction(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateTransactionRequest request) {
        return transactionService.create(principal, request);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Update one of my transactions",
            description = """
                    Changes the supplied fields and leaves the rest as they are. Send only what \
                    you changed.

                    A field sent as `null` is treated as "not changed". To clear `description`, send \
                    an empty string.

                    Moving a record to another category is how its type changes (BR-05), and is a \
                    legitimate correction of a misfiled record. The new category must be one of the \
                    caller's own or a shared default one, and must not be retired - the same rule as \
                    on creation, applied because choosing a category is a new filing decision. A \
                    record that is already filed under a category retired later stays editable.

                    A deleted record cannot be edited: restore it first (UC-10 A1).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated transaction.",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, the target category is retired, or the date "
                            + "is in the future; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such transaction or category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The record or its category changed while the request was in "
                            + "flight (DATA_CONFLICT). Reload and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TransactionResponse updateTransaction(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        return transactionService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete one of my transactions",
            description = """
                    Moves the record to the trash. The row is kept and its history is preserved, so \
                    it can be brought back with the restore endpoint; this is BR-09's soft delete, \
                    and no endpoint in the API removes a transaction outright.

                    A deleted record is left out of the list, out of every balance and out of every \
                    report. Deleting a record that is already deleted answers `409` \
                    (`TRANSACTION_ALREADY_DELETED`) rather than reporting success, because the \
                    caller's copy of the record's state is out of date.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Moved to the trash."),
            @ApiResponse(responseCode = "404", description = "No such transaction of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The record is already deleted "
                            + "(TRANSACTION_ALREADY_DELETED).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void deleteTransaction(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable Long id) {
        transactionService.delete(principal, id);
    }

    @PostMapping("/{id}/restore")
    @Operation(
            summary = "Restore a deleted transaction",
            description = """
                    Brings a record back out of the trash (UC-10 A1). The record returns to the \
                    list, the balances and the reports, and its history keeps every step: the log \
                    reads create, then delete, then restore rather than being rewritten.

                    Restoring a record that is not deleted answers `409` (`TRANSACTION_NOT_DELETED`) \
                    rather than reporting success, because nothing would have changed.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The restored transaction.",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such transaction of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The record is not deleted (TRANSACTION_NOT_DELETED).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TransactionResponse restoreTransaction(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @PathVariable Long id) {
        return transactionService.restore(principal, id);
    }
}
