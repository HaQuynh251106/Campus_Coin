package com.campuscoin.categorisation.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.categorisation.dto.CategorySuggestionResponse;
import com.campuscoin.categorisation.dto.SuggestCategoryRequest;
import com.campuscoin.categorisation.service.CategorisationService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Asks the system to categorise one of the student's own records, and learns from their filing: UC-08.
 *
 * <p><b>One endpoint, and the resource is a proposal rather than a record.</b> The record is module 4's
 * and module 4 is where it is created, corrected, trashed and restored; nothing here reads or writes
 * the collection the student browses. What this route addresses is a question - "what would you file
 * this under?" - asked about a record they already own, and the answer is advisory by BR-13.
 *
 * <p><b>Why this is not under {@code /transactions}.</b> The suggestion is stored in three columns of
 * {@code transactions}, so the first instinct is that the route belongs there - and it was considered.
 * It does not hold up for three reasons, the same three {@code AnomalyController} gives for the flags
 * it writes. Module 4's {@code Transaction} leaves those three columns unmapped on purpose, so that no
 * statement module 4 builds can write them; the categorisation write therefore goes through a path
 * module 4 does not know about. The response is not a transaction but a proposal about one. And a route
 * under {@code /api/v1/transactions/**} would read to a client as a module-4 operation, when the one
 * thing module 4 would have had to do differently - consult a categoriser as it saves - is exactly what
 * it does not do: filing a record must not depend on a provider being reachable.
 *
 * <p><b>Why {@code /api/v1/ai} is the right collection when the answer often involves no AI.</b> The
 * namespace is the set of routes that ask the system to reason about the student's own data and report
 * what it concluded, together with where the conclusion came from. {@code source} makes that explicit on
 * every response, and the deployment setting {@code ai.enabled} can turn the external half off without
 * turning the route off - a student's own learned mappings still answer, which is the property that
 * makes this a feature rather than a demo. Naming the route after the model would be false the first
 * time it is answered from a keyword rule; naming it after the capability is not.
 *
 * <p><b>The client names a record and nothing else.</b> There is no category field in the request, so
 * the mapping the system learns is derived from where the student actually filed the record - not from
 * what a client says they did. There is no {@code userId}, so identity comes from the verified bearer
 * token, and the record is loaded by {@code (id, user_id)}: another student's record is answered with the
 * same {@code 404} as one that does not exist.
 *
 * <p><b>Nothing this route returns changes where the money is counted.</b> The student's own category is
 * never written here (BR-13), so the proposal is a note beside the record and the learning is a change
 * to the system's memory, not to their history.
 */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI categorisation",
        description = "Asks the system to propose a category for one of the signed-in student's own "
                + "records, and learns the mapping implied by where they filed it (UC-08).")
@SecurityRequirement(name = "bearerAuth")
public class CategorisationController {

    private final CategorisationService categorisationService;

    public CategorisationController(CategorisationService categorisationService) {
        this.categorisationService = categorisationService;
    }

    @PostMapping("/suggest-category")
    @Operation(
            summary = "Propose a category for one of my records",
            description = """
                    Proposes a category for one of the caller's own records, records the proposal \
                    beside it, and learns the mapping implied by the category the record is in. The \
                    answer says where the proposal came from.

                    **It proposes; it does not file.** The record keeps the category the student chose. \
                    The proposal is stored beside it as a suggestion the student may review and \
                    override, which is what BR-13 requires, and no call to this endpoint moves a \
                    transaction from one category to another.

                    **How the proposal is reached.** First the student's own learned mappings are \
                    consulted, and an exact match on the description wins. Only when there is no \
                    mapping is the configured AI service asked, and only with the description and the \
                    names of the categories the student may file under - no amount, no date, no \
                    identifier. Whatever it answers is matched back against those categories before it \
                    becomes anything, so a name the student does not have is discarded rather than \
                    trusted. If neither step proposes anything the answer says so with `source: NONE`; \
                    that is a result, not an error.

                    **It learns as a side effect.** UC-08's "learn from corrections" is a per-student \
                    keyword-to-category mapping: the description the student saved teaches the system \
                    what that merchant is. Repeating a call over an unchanged record writes nothing \
                    the second time, so opening a screen twice cannot accumulate history.

                    **What it costs to run without an AI service.** Nothing about the feature stops \
                    working. A deployment with `ai.enabled` off answers every record from the student's \
                    own mappings and reports `source: RULE`; a new description simply gets `source: \
                    NONE` until the student files something similar enough to have taught one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The proposal, its origin, and the mapping this call left stored.",
                    content = @Content(schema = @Schema(implementation = CategorySuggestionResponse.class))),
            @ApiResponse(responseCode = "400", description = "The body is missing the transaction id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such transaction of the caller's, or it is in the trash.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The record changed while the proposal was being written. Reload "
                            + "and try again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategorySuggestionResponse suggestCategory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SuggestCategoryRequest request) {
        return categorisationService.suggest(principal.userId(), request.transactionId());
    }
}
