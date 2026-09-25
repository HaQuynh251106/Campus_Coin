package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.campuscoin.dashboard.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One saving tip as the dashboard shows it (UC-12 B3).
 *
 * <p><b>The list arrives already ranked, and the order is the contract.</b>
 * {@code v_dashboard_tips} sorts pinned tips first and then by rank score, and
 * {@code DashboardViewDao} asks for that same order. A client renders the array as it arrives; if it
 * re-sorted by {@code potentialSaving} it would disagree with what the database ranks by (BR-14), and
 * the tip the student pinned would drift down the page.
 *
 * <p><b>{@code state} is published because a pinned tip renders differently from an unpinned one.</b>
 * The two members are {@code NEW} and {@code PINNED}; {@code DISMISSED} is excluded by the view, so a
 * dismissed tip cannot appear here at all - it is not a third value a client must handle. Changing
 * this field is UC-18's, not this endpoint's: the dashboard reports the state and has no route that
 * writes one.
 *
 * <p><b>{@code categoryId} is absent for tips that belong to no category.</b> A tip about the savings
 * goal or about having too little data to analyse is not about a category; the column is nullable and
 * so is this field. A client uses it to link the tip to that category's transactions, and omits the
 * link when there is nothing to link to.
 *
 * <p>{@code title} and {@code body} are stored, user-visible prose rendered by the database from a
 * template ({@code fn_render_template}), in English. They are passed through unaltered - the amounts
 * and percentages inside them are the same figures every other part of the dashboard reports, and
 * re-formatting them here would be a second rendering of one fact.
 */
@Schema(description = "A saving tip shown on the dashboard, in the ranked order it should be "
        + "displayed (UC-12 B3).")
public record DashboardTipResponse(

        @Schema(description = "Identifier of the tip, for the tip actions in UC-18.",
                example = "12")
        Long id,

        @Schema(description = "The category this tip is about. Omitted when the tip belongs to no "
                + "category, such as the savings-goal tip.", example = "1", nullable = true)
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
