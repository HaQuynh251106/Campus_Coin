package com.campuscoin.tips.dto;

import java.math.BigDecimal;

import com.campuscoin.tips.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A saving tip, in the ranked order it should be displayed (UC-18).")
public record TipResponse(

        @Schema(description = "Identifier of the tip, used by the pin and dismiss actions. The value "
                + "in this example is an illustration of the type: a tip id is assigned by the "
                + "database when the generator writes the row, so take it from a response.",
                example = "12")
        Long id,

        @Schema(description = "The category this tip is about. Omitted when the tip belongs to no "
                + "category, such as the savings-goal tip. The pair in this example is a real one: "
                + "`11` is `Entertainment`, which `db/05_seed.sql` creates, and it is the category "
                + "the headline and body below describe.", example = "11", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long categoryId,

        @Schema(description = "The headline.", example = "Entertainment spending is up 127.3%")
        String title,

        @Schema(description = "The advice itself.", example = "This month you spent 25.00 on "
                + "Entertainment, against your usual 11.00. A rise of 127.3% is worth a look - try "
                + "setting a weekly cap for this category.")
        String body,

        @Schema(description = "The estimated saving if the advice is followed, in the account's "
                + "currency. `0.00` for advice with no figure attached.", example = "11.20")
        BigDecimal potentialSaving,

        @Schema(description = "`PINNED` tips are shown first and keep their place; `NEW` tips are "
                + "ordered by how much they could save (BR-14). In practice this field holds only "
                + "those two: a dismissed tip is excluded before it is read, so `DISMISSED` never "
                + "reaches a response, even though it is a member of the type because the same type "
                + "carries the state a client asks for.",
                example = "NEW")
        TipState state) {
}
