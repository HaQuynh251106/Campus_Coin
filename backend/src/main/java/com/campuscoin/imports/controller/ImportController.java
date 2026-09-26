package com.campuscoin.imports.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.campuscoin.imports.dto.ImportBatchListResponse;
import com.campuscoin.imports.dto.ImportBatchResponse;
import com.campuscoin.imports.dto.ImportRowResponse;
import com.campuscoin.imports.dto.SetImportRowCategoryRequest;
import com.campuscoin.imports.dto.UploadCsvRequest;
import com.campuscoin.imports.service.ImportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Importing a student's records from a CSV file: UC-11.
 *
 * <p>Six operations on four paths, and the shape they describe is one workflow: upload a file to
 * preview it, look at the preview, correct any row whose category was read wrong, commit what is left,
 * or abandon it.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>UC-11 step</th></tr>
 *   <tr><td>{@code POST}</td><td>{@code /api/v1/imports}</td><td>A1 - upload and preview</td></tr>
 *   <tr><td>{@code GET}</td><td>{@code /api/v1/imports}</td><td>the student's import history</td></tr>
 *   <tr><td>{@code GET}</td><td>{@code /api/v1/imports/{batchId}}</td><td>B5 - one preview, with its rows</td></tr>
 *   <tr><td>{@code PATCH}</td><td>{@code /api/v1/imports/{batchId}/rows/{rowId}}</td><td>B6 - choose a row's category</td></tr>
 *   <tr><td>{@code POST}</td><td>{@code /api/v1/imports/{batchId}/commit}</td><td>B9 - import it</td></tr>
 *   <tr><td>{@code POST}</td><td>{@code /api/v1/imports/{batchId}/cancel}</td><td>A2 - abandon it</td></tr>
 * </table>
 *
 * <p><b>Why the commit and the cancel are {@code POST}s on named sub-resources rather than a status
 * update.</b> Each is a transition whose effect exceeds any single column: a commit steps through every
 * importable row and generates transactions, then rewrites the batch's five counters; a cancel ends the
 * batch without touching its rows. A {@code PATCH} carrying {@code {"status": "COMMITTED"}} would
 * suggest the client was naming a state rather than asking for the work to be done, and UC-11 requires
 * the commit to be an action - the student presses a button and the records appear. The shape matches
 * {@code POST /recent-activity} and {@code POST /notifications/{id}/read}, which are the project's
 * other "do this" endpoints.
 *
 * <p><b>Why the file arrives as JSON text and not as a multipart upload.</b> See
 * {@code UploadCsvRequest}: there is no {@code MultipartFile}, no multipart configuration and no
 * over-size error handler anywhere in this build, and adding all three for one endpoint would leave the
 * one refusal a multipart route is most likely to hit answered by Spring's default rather than by this
 * API's error contract.
 *
 * <p><b>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from
 * the verified bearer token.</b> No request accepts a user id, and the two identifiers that do appear
 * in a path - a batch and a row - are resolved through ownership-narrowed reads, so a batch or row
 * belonging to another student is indistinguishable from one that does not exist (section 7.5). The
 * commit is the case where that does real work rather than merely holding a policy: see
 * {@code ImportService#commit}.
 */
@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "CSV import",
        description = "Upload a CSV file, review it row by row, and import it (UC-11).")
@SecurityRequirement(name = "bearerAuth")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Upload a CSV file and preview it",
            description = """
                    Parses the file, decides what will happen to each of its rows, stores the batch, \
                    and returns the preview.

                    **The file is sent as JSON text, not as a multipart upload.** Send the file's \
                    contents, decoded as UTF-8, in `content`. A byte-order mark is handled, because \
                    Excel writes one even on a "CSV UTF-8" export. `filename` is stored and echoed so \
                    the student can tell two uploads apart; it is never opened or resolved as a path.

                    **The file needs a header row naming its columns**, including `date`, `amount` and \
                    `type`. `description` and `category` are optional. A file missing a required \
                    column is refused with `400` and no batch is stored.

                    **A row the importer cannot read does not stop the file.** It is stored as an \
                    `ERROR` row carrying a sentence in the student's terms, and the rows around it are \
                    previewed normally. A file that will import nothing is still a preview; the \
                    student sees which rows to fix.

                    **Some rows are previewed as `DUPLICATE`.** A row is a duplicate when one of the \
                    student's own records already matches it on category, amount and a date within a \
                    few days. Those rows will not be imported if the batch is committed. This is \
                    decided from the student's own data and cannot be set from a request.

                    **Nothing is imported by this call.** The batch is stored as `PREVIEWED` and no \
                    transaction exists until the commit endpoint is called.

                    The file may have at most 2000 rows and about two million characters. A larger \
                    file is refused rather than trimmed, because a preview of part of a file would be \
                    showing the student something other than their file.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The stored preview, with its rows.",
                    content = @Content(schema = @Schema(implementation = ImportBatchResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "The file is empty, too large, or cannot be read as CSV; or it "
                            + "names no columns or is missing a required one.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportBatchResponse uploadCsv(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UploadCsvRequest request) {
        return importService.upload(principal, request);
    }

    @GetMapping
    @Operation(
            summary = "List my imports",
            description = """
                    Returns the caller's uploads, most recent first, each with the counters the \
                    preview or the commit reached.

                    **The rows are not included.** This is the list of imports; ask for one batch to \
                    get its rows. A student with ten imports should not receive ten files' worth of \
                    rows to render ten summary lines.

                    `modifiable` on each entry says whether its rows can still be changed and the \
                    batch committed - which is how a student finds the import they were part way \
                    through.

                    `limit` defaults to 20 and may be at most 100. A larger value is refused with \
                    `400` rather than reduced, because the response reports the limit it applied.

                    An empty `entries` array is a real answer: a student who has never imported \
                    anything has an empty history.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The caller's imports, most recent first."),
            @ApiResponse(responseCode = "400", description = "`limit` is out of range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportBatchListResponse listImports(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "How many imports to return. Defaults to 20; at most 100.")
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return importService.list(principal, limit);
    }

    @GetMapping("/{batchId}")
    @Operation(
            summary = "Get one import and its rows",
            description = """
                    Returns one of the caller's batches with every row of its file, in file order.

                    **Each row says what will happen to it.** `rowStatus` is `VALID` (it will be \
                    imported), `ERROR` (it cannot be, and `errorMessage` says why), or `DUPLICATE` (the \
                    student appears to have it already). After a commit the rows that became \
                    transactions read `IMPORTED` and carry the `transactionId` they produced.

                    **Both the parsed values and the original line are returned.** `rawData` is the \
                    line as it arrived, keyed by column name, so a client can show the student what \
                    the importer read beside what the file actually said.

                    `aiSuggestedCategoryId` is where the system would file the row, from the \
                    student's own learned rules (UC-08). It is advice and nothing else - a row is \
                    filed under `parsedCategoryName` or under what the student chose, never under \
                    the suggestion on its own.

                    `resolvedCategoryId` is null until the student chooses a category for the row. A \
                    category name in the file is not resolved here; the commit does that, so \
                    resolving it now would be a second answer to a question the commit owns.

                    A batch belonging to another student is answered exactly like one that does not \
                    exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The batch and its rows.",
                    content = @Content(schema = @Schema(implementation = ImportBatchResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such import of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportBatchResponse getImport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long batchId) {
        return importService.get(principal, batchId);
    }

    @PatchMapping("/{batchId}/rows/{rowId}")
    @Operation(
            summary = "Choose the category for one previewed row",
            description = """
                    Records which category one row of the preview should be filed under, and returns \
                    the row as it now stands.

                    **This is what the commit treats as authoritative.** Once a row has a chosen \
                    category, the commit uses it as it is and never re-derives the category from the \
                    file's own name. Without it a student who could see a row was misfiled would have \
                    no way to say so.

                    **The category also decides the record's type** (BR-05), which is how a wrong \
                    `type` in the file is corrected: choose a category of the type the record should \
                    have.

                    **Changing the category re-runs the duplicate check.** Whether a row is a \
                    duplicate depends on the category it would be filed under, so correcting the \
                    category re-decides that row and can bring it back as importable, or flag another \
                    row that has just become a duplicate of it.

                    **There is no way to undo a choice.** The field is required and is never cleared; \
                    a student who wants the file's own category back leaves the row alone.

                    Only an open batch can be changed. A batch that has been committed or cancelled \
                    answers `409`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The row, as it now stands.",
                    content = @Content(schema = @Schema(implementation = ImportRowResponse.class))),
            @ApiResponse(responseCode = "400", description = "`categoryId` is missing, or names a "
                    + "retired category.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such import of the caller's, no such row in it, or no such "
                            + "category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The import has been committed or cancelled; it can no longer be "
                            + "changed.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportRowResponse setRowCategory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long batchId,
            @PathVariable Long rowId,
            @Valid @RequestBody SetImportRowCategoryRequest request) {
        return importService.setRowCategory(principal, batchId, rowId, request);
    }

    @PostMapping("/{batchId}/commit")
    @Operation(
            summary = "Import the batch",
            description = """
                    Generates a transaction for every row the preview left importable, and returns \
                    the batch and its rows as they now stand.

                    **Which rows are imported is decided by the preview, not by this call.** A row \
                    stored as `VALID` becomes a transaction; a row stored as `ERROR` or `DUPLICATE` \
                    does not. A duplicate is left alone rather than imported, because it is a record \
                    the student appears to have already.

                    **One row the database refuses does not end the import.** A row from the future \
                    (BR-08) or filed under a category that has since been retired (BR-07) becomes an \
                    `ERROR` row with a reason while the rest of the file imports normally. So a \
                    commit can legitimately import some rows and report others as errors, and the \
                    counters it returns describe exactly that.

                    **The counters are rewritten from what the import actually did**, so \
                    `importedRows` is no longer zero and `errorRows` and `duplicateRows` describe the \
                    outcome rather than the prediction. Every imported row carries the \
                    `transactionId` it produced.

                    **The import teaches the system, in the same step.** Each imported row maps its \
                    description to the category it was filed under, so the next record from the same \
                    merchant is suggested correctly (UC-08). If the import fails, the mappings fail \
                    with it.

                    Committing twice is refused with `409`: a batch that has already been committed \
                    or cancelled cannot be committed again. This is also how another student's batch \
                    is answered - identically to one that does not exist, so the endpoint cannot be \
                    used to discover batch identifiers.

                    The batch's transactions are created in one step and are all-or-nothing per row, \
                    not per file: a row the database refuses is reported, not propagated.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The batch after importing.",
                    content = @Content(schema = @Schema(implementation = ImportBatchResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such import of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The import has already been committed or cancelled.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportBatchResponse commitImport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long batchId) {
        return importService.commit(principal, batchId);
    }

    @PostMapping("/{batchId}/cancel")
    @Operation(
            summary = "Abandon the import",
            description = """
                    Marks the batch `CANCELLED`, so nothing is imported and nothing will be, and \
                    returns it.

                    **The rows are kept.** A cancelled batch keeps its rows and its counters, so the \
                    student can still see what they had and the record of what the file contained \
                    survives the decision not to import it. Nothing reads a cancelled batch's rows as \
                    importable, so keeping them cannot cause a later import.

                    **This is safe to repeat.** Cancelling a batch that is already cancelled is \
                    refused with `409` rather than being an error the student has to work around - \
                    reload the import and its state will be clear.

                    A batch belonging to another student answers `404`, exactly as one that does not \
                    exist does.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The batch, now cancelled.",
                    content = @Content(schema = @Schema(implementation = ImportBatchResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such import of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The import has already been committed or cancelled.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ImportBatchResponse cancelImport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long batchId) {
        return importService.cancel(principal, batchId);
    }
}
