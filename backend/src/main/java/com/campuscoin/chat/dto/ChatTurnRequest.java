package com.campuscoin.chat.dto;

import com.campuscoin.chat.service.ChatRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One earlier turn of the conversation, as the client sends it back (section 5 of the brief).
 *
 * <p><b>Why history is in the request rather than on the server.</b> There is no conversation table and
 * no session in this feature. The client keeps its own transcript and sends it with each question, which
 * is what makes one student's history structurally unable to reach another's: there is nowhere for it to
 * be stored under the wrong account, and nothing to clean up. Section 9's ownership requirement is met by
 * there being no server-side history at all, rather than by a check on one.
 *
 * <p><b>{@code role} is an enum with two members and no system variant.</b> The instruction that fixes
 * the assistant's behaviour is supplied by the server on every call and cannot be written by a client,
 * so there is no value here that a student could use to dictate the assistant's rules. That is the reason
 * the enum is not a free string.
 *
 * <p><b>Both fields are required and the text is bounded.</b> A turn with no role or no text is a
 * malformed request rather than something to interpret; the bound exists because the history is
 * client-supplied and an unbounded one is an unbounded provider call.
 */
@Schema(description = "One earlier message in the conversation, oldest first.")
public record ChatTurnRequest(

        @Schema(description = "Who said it. `USER` is the student; `ASSISTANT` is a previous reply of "
                + "the assistant's, which you may omit entirely - the server does not need its own past "
                + "answers echoed back, and keeping them is only useful for resolving what a later "
                + "question refers to.", example = "USER", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Each turn must say who spoke it.")
        ChatRole role,

        @Schema(description = "What was said.", example = "How much did I spend on Food this month?",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A turn cannot be empty.")
        @Size(max = 2000, message = "Each turn must be at most 2000 characters.")
        String text) {
}
