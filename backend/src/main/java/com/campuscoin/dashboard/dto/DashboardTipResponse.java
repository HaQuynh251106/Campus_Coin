package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.campuscoin.dashboard.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A saving tip shown on the dashboard, in the ranked order it should be "
        + "displayed (UC-12 B3).")
public record DashboardTipResponse(

        @Schema(description = "Identifier of the tip, for the tip actions in UC-18. The value in this "
                + "example is an illustration of the type: a tip id is assigned by the database when "
                + "the generator writes the row.", example = "12")
        Long id,

        @Schema(description = "The category this tip is about. Omitted when the tip belongs to no "
                + "category, such as the savings-goal tip. The pair in this example is a real one: "
                + "`11` is `Entertainment`, which `db/05_seed.sql` creates, and it is the category "
                + "the headline below describes.", example = "11", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long categoryId,

        @Schema(description = "The headline.", example = "Entertainment spending is up 127.3%")
        String title,

        @Schema(description = "The advice itself.", example = "You spent 25.00 on Entertainment "
                + "this month against a 11.00 average over the last 3 months.")
        String body,

        @Schema(description = "The estimated saving if the advice is followed, in the account's "
                + "currency. `0.00` for advice with no figure attached.", example = "11.20")
        BigDecimal potentialSaving,

        @Schema(description = "`PINNED` tips are shown first and keep their place; `NEW` tips are "
                + "ordered by how much they could save (BR-14). A dismissed tip never reaches this "
                + "list.", example = "NEW")
        TipState state) {
}
