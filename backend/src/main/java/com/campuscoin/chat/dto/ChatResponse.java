package com.campuscoin.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The assistant's reply, with where it came from (section 8 of the brief).
 *
 * <p><b>{@code model} and {@code toolsUsed} exist so the answer can be attributed.</b> Section 8 asks for
 * the provider and model to be recorded "where the existing architecture supports it", and the precedent
 * is {@code MonthlyInsightResponse}, which publishes both the author ({@code generatedBy}) and the
 * {@code model} beside the text it wrote. A student reading a reply is entitled to know it was written by
 * a model rather than composed by a rule - that is BR-13's requirement that an AI result be presented as
 * one - and an operator needs the same two values to tell a working deployment from one whose calls are
 * failing.
 *
 * <p><b>{@code toolsUsed} names only, and names the capabilities rather than the data.</b> "This reply
 * read your budget status" is useful provenance; the figures it read are in the reply itself. The list is
 * in the order the model asked, and it is empty for an answer given without reading anything - which is
 * the shape a refusal outside the assistant's scope takes, and is worth being able to see.
 *
 * <p><b>There is no field carrying the student's data back.</b> The reply is prose; nothing here echoes
 * the tool payloads, so the response cannot become a second, unreviewed route to the same figures the
 * tools were careful to bound. A client that wants the raw figures already has an endpoint for each of
 * them.
 */
@Schema(description = "The assistant's reply to one question.")
public record ChatResponse(

        @Schema(description = "The reply text, written by the model from figures read for the "
                + "signed-in student alone.", example = "You spent $46.50 on Food & Dining this month.")
        String reply,

        @Schema(description = "The model that wrote the reply. Published so the answer can be "
                + "attributed, exactly as `MonthlyInsightResponse.model` does.", example = "gemini-3.5-flash")
        String model,

        @Schema(description = "The data the reply read, by capability, in the order it was read. Empty "
                + "when the assistant answered without reading anything - an out-of-scope refusal, for "
                + "example. These are capability names, not values, so no figure is disclosed here that "
                + "the reply does not already state.", example = "[\"getCategorySpending\"]")
        List<String> toolsUsed) {
}
