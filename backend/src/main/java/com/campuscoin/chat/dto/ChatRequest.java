package com.campuscoin.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
