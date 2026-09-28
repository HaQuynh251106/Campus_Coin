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
