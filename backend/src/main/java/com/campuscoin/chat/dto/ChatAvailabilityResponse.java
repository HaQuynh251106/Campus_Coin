package com.campuscoin.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether the chat assistant is available in this deployment.")
public record ChatAvailabilityResponse(

        @Schema(description = "True when a question would reach a provider. False means the chat panel "
                + "should say the assistant is unavailable rather than accept a question.",
                example = "true")
        boolean available,

        @Schema(description = "The model that would answer, so the reply can be attributed. Absent when "
                + "the assistant is unavailable - there is no model to name.", example = "gemini-3.5-flash",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String model,

        @Schema(description = "A sentence for the student explaining why the assistant is unavailable. "
                + "Absent when it is available.", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reason) {
}
