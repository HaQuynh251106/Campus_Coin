package com.campuscoin.tips.dto;

import java.math.BigDecimal;

import com.campuscoin.tips.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One saving tip as the tips screen shows it (UC-18).
 *
 * <p><b>The list arrives already ranked, and the order is the contract.</b>
 * {@code v_dashboard_tips} sorts pinned tips first and then by the score BR-14 ranks by, and
 * {@code TipViewDao} asks for that same order. A client renders the array as it arrives; if it
 * re-sorted by {@code potentialSaving} it would disagree with what the database ranks by, and the tip
 * the student pinned would drift down the page - the same warning {@code DashboardTipResponse}
 * carries for the same reason.
 *
 * <p><b>{@code state} is published so a client can render a pinned tip differently and offer the
 * right action.</b> It is typed by the same {@code TipState} the state-change request uses, because a
 * tip's state is one column and two Java enums over it would be two vocabularies that could drift -
 * so the schema advertises all three members while the field in practice carries the two the view
 * returns. A dismissed tip is excluded before this type is ever constructed (BR-14), and the rule
 * that keeps it out is the view's rather than the type's; the integration test asserts it, because a
 * schema cannot.
 *
 * <p><b>{@code categoryId} is absent for tips that belong to no category.</b> A tip about the savings
 * goal or about having too little data to analyse is not about a category; the column is nullable and
 * so is this field. A client uses it to link the tip to that category's transactions, and omits the
 * link when there is nothing to link to.
 *
 * <p>{@code title} and {@code body} are stored, user-visible prose rendered by the database from a
 * template ({@code fn_render_template}), in English. They are passed through unaltered - the amounts
 * and percentages inside them are the same figures every other part of the application reports, and
 * re-formatting them here would be a second rendering of one fact.
 *
 * <p>{@code periodMonth} is not carried: every tip in a response belongs to the month the response
 * names once, so a copy on every row would be the same string repeated - the reasoning
 * {@code DashboardTipResponse} records.
 */
@Schema(description = "A saving tip, in the ranked order it should be displayed (UC-18).")
public record TipResponse(

        @Schema(description = "Identifier of the tip, used by the pin and dismiss actions.",
                example = "12")
        Long id,

        @Schema(description = "The category this tip is about. Omitted when the tip belongs to no "
                + "category, such as the savings-goal tip.", example = "10", nullable = true)
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
