package com.campuscoin.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the assistant can answer right now (section 8 of the brief).
 *
 * <p><b>The UI needs this before the student types.</b> A chat panel that knows the assistant is off can
 * say so on open, which is a better experience than accepting a question and failing to send it - and it
 * is the honest answer, because the two facts behind it are deployment configuration rather than
 * something about the student's account.
 *
 * <p><b>{@code reason} is written for a student.</b> It says the assistant is unavailable or switched
 * off; it does not name the provider, the credential, the setting key or the model that is missing. An
 * operator who needs that detail reads the application's configuration and logs, not a student's screen.
 * It is absent when the assistant is available, so a client renders the panel rather than a notice.
 *
 * <p><b>This endpoint asks the provider nothing.</b> The free tier permits a small number of requests per
 * model per day, so a status probe that actually called the provider would spend the quota it was
 * reporting on - and would turn a read-only status check into an outbound request carrying the
 * deployment's credential. The two values here are read from configuration and {@code system_settings}.
 */
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
