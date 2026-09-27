package com.campuscoin.chat.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.chat.dto.ChatAvailabilityResponse;
import com.campuscoin.chat.dto.ChatRequest;
import com.campuscoin.chat.dto.ChatResponse;
import com.campuscoin.chat.service.ChatService;
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
 * The conversational assistant.
 *
 * <p><b>No use-case identifier is claimed for this, because the SRS has none.</b> The use case and
 * business-flow specification runs UC-01..UC-27, and its twenty-seventh is the display preferences on
 * {@code /api/v1/profile/me}; there is no conversational assistant anywhere in it. This feature was
 * requested separately after module 12 and is therefore documented as an addition of its own rather
 * than as the implementation of a numbered requirement - inventing a use-case number would make it
 * look like an approved requirement that a reviewer could go and check, and there is nothing to find.
 *
 * <p>Two endpoints. The POST is the conversation; the GET reports whether one is possible, so the chat
 * panel can say the assistant is unavailable instead of accepting a question it cannot answer.
 *
 * <p><b>The caller is read from the token and the request has no field that could say otherwise.</b> Both
 * methods take {@code @AuthenticationPrincipal}, so identity comes from the verified bearer token and
 * nowhere else. {@link ChatRequest} has no user id, which is stronger than validating one - a body
 * carrying an unexpected {@code userId} is simply not read, because Jackson ignores an unknown property
 * and no code path consults one. So the id is inert rather than refused, and the turn is answered for the
 * token's owner; {@code ChatApiIT} asserts that outcome by sending another student's id and checking the
 * caller is still read their own figures. That is section 9 of the brief, and it is enforced here plus in
 * {@code ChatService}, which binds the same principal into the only callback the provider can call.
 *
 * <p><b>The endpoint is under its own prefix because the feature is its own thing.</b> It is not
 * {@code /ai/**}: that prefix is UC-08's categorisation, a single-purpose call that writes a suggestion
 * onto a transaction, and this is a conversation that writes nothing at all. Sharing the prefix would also
 * mean a future administrator-facing AI route could not be told apart from it. {@code /api/v1/chat` is
 * what the frontend already calls the feature, so the path says what it is.
 *
 * <p><b>What a client cannot do here.</b> It cannot write the assistant's instruction - the request
 * carries only a user role and an assistant role, and the instruction is the server's constant. It cannot
 * name a read outside the ten the application publishes. It cannot ask the assistant to change anything:
 * the tools are all reads, and there is no endpoint here that mutates. These are properties of the code
 * rather than rules in a prompt, which is what makes them hold when a student phrases something cleverly.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat assistant",
        description = "Ask questions about the signed-in student's own Campus Coin records. "
                + "Read-only: the assistant can report figures but cannot change any data.")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(
            summary = "Ask the assistant about my own records",
            description = """
                    Answers one question using the signed-in student's own Campus Coin data, and returns \
                    the reply together with the model that wrote it and the figures it read.

                    **The conversation is supplied by the client.** There is no session and no stored \
                    transcript: the request carries the earlier turns, oldest first, and the new question \
                    last. That is what lets the assistant resolve "that", "it" and "last month" against \
                    the actual conversation rather than by matching a phrase, and it is also why nothing \
                    can carry over between accounts - nothing is kept server-side to be attributed to \
                    the wrong one.

                    **Identity comes from the token.** There is no user id in the request and no way to \
                    ask about anyone else; every figure the assistant reads is read for the bearer of \
                    the token, by the same services the student's own screens use.

                    **Read-only.** The assistant can read the current month, any earlier month, a \
                    category's spending, budget limits and consumption, individual records over a date \
                    range, saving tips that already exist, the forecast, flagged records and recent \
                    activity. It cannot create, change or delete anything, and it cannot generate a \
                    report, a tip or an anomaly scan on the student's behalf.

                    **Replies are written by a model.** When the assistant is unavailable the request \
                    fails with `503` rather than returning an answer composed by the application, so a \
                    reply that arrives is always one a model wrote. `GET /api/v1/chat` reports \
                    availability without spending a provider call.

                    **Scope.** Questions outside the student's Campus Coin finances are declined with a \
                    fixed sentence rather than answered. Figures are read through the application's own \
                    services, so they match what the student's screens show.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The assistant's reply, with its model and what it read.",
                    content = @Content(schema = @Schema(implementation = ChatResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "The message is missing, empty or too long, or an earlier turn is "
                            + "malformed.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503",
                    description = "The assistant could not answer - no provider is configured, the "
                            + "feature is switched off, or the provider refused or timed out. No answer "
                            + "composed by the application is ever returned in its place.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ChatResponse ask(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChatRequest request) {
        return chatService.ask(principal, request);
    }

    @GetMapping
    @Operation(
            summary = "Is the assistant available?",
            description = """
                    Reports whether a question would reach a provider, so the chat panel can say the \
                    assistant is unavailable on open rather than accepting a question it cannot answer.

                    **It asks the provider nothing.** The answer comes from whether a credential is \
                    installed and whether an administrator has switched the feature on. A probe that \
                    actually called the provider would spend the deployment's daily quota to answer a \
                    question about the quota, and would send an outbound request from a read-only \
                    status check.

                    **The model is named, the credential is not.** The model identifier is published so \
                    a reply can be attributed, the same disclosure the monthly insight makes beside the \
                    text it writes. The API key is never returned and is not readable through any \
                    endpoint.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Whether the assistant is available, and why not when it is not.",
                    content = @Content(schema = @Schema(implementation = ChatAvailabilityResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ChatAvailabilityResponse availability(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return chatService.availability();
    }
}
