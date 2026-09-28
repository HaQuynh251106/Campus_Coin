package com.campuscoin.chat.dto;

import com.campuscoin.chat.service.ChatRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
