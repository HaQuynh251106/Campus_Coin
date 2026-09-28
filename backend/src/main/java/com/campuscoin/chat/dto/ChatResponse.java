package com.campuscoin.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

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
