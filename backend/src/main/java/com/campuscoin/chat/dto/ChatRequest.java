package com.campuscoin.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One question, with as much of the conversation as the client chose to send (section 5 of the brief).
 *
 * <p><b>There is no user id here, and its absence is the point.</b> Section 9 requires the server to
 * derive the caller from the security context and forbids accepting an identity from the frontend, so
 * this record has no field for one - which is stronger than validating one, because there is nothing to
 * validate. A body reading {@code {"userId": 9, "message": "..."}} is not refused: Jackson ignores the
 * unknown property, the id is discarded, and the turn is answered for the token's owner. That is the
 * guarantee worth stating, and it is the one the integration suite asserts - it sends a second student's
 * id in the request and checks the caller is still read their own figures, so a later refactor that
 * started honouring such a field would fail there rather than in production.
 *
 * <p><b>The conversation is a list, not a session identifier.</b> Section 5 requires multi-turn context
 * and forbids resolving a reference by matching a phrase; sending the turns back is what lets the
 * provider read its own conversation, and it leaves no server-side history to leak. The order is oldest
 * first, and the new question is {@link #message}.
 *
 * <p><b>{@code history} is optional and bounded.</b> A first question has none. The bounds exist because
 * this text goes to an external provider: without them a client could post an arbitrarily long
 * conversation, which is both a cost and a way to push the instruction out of the model's attention.
 */
@Schema(description = "A question for the assistant, with the conversation so far.")
public record ChatRequest(

        @Schema(description = "The student's new question or remark.",
                example = "How much did I spend on Food this month?",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A message is required.")
        @Size(max = 2000, message = "A message must be at most 2000 characters.")
        String message,

        @Schema(description = "Earlier turns, oldest first, ending with the message before this one. "
                + "Omit it for the first question of a conversation. The server keeps no history of "
                + "its own, so anything not sent here is not known - which also means nothing can be "
                + "carried over from another account.")
        @Valid
        @Size(max = 30, message = "A conversation may carry at most 30 earlier turns.")
        List<ChatTurnRequest> history) {
}
