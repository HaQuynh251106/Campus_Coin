package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The expense category the student has spent the most on this month (UC-12 B2).
 *
 * <p><b>The whole object is omitted when the student has spent nothing, which is why it is nullable
 * on {@link DashboardResponse}.</b> {@code v_top_category_current_month} selects a row only from
 * months that have expense transactions, so there is no category to name for a new account. Showing
 * a placeholder row with a zero would be inventing an answer: the student has no highest-spending
 * category, rather than one on which they spent nothing.
 *
 * <p><b>The icon and colour come from {@code categories}, and only those two.</b> The view publishes
 * the id, the name and the amount; the DAO joins the category for the two presentation columns so a
 * dashboard can render the row with the same swatch the categories screen uses. The amount itself is
 * still the view's figure, so the widget and the reports pie chart cannot disagree.
 *
 * <p>{@code spentAmount} is named as the view names it - the month's total for that category - and it
 * is the same number that would appear as that category's slice in the reports module.
 */
@Schema(description = "The highest-spending expense category this month (UC-12 B2). Absent when the "
        + "student has recorded no spending this month.")
public record DashboardTopCategoryResponse(

        @Schema(description = "The category's identifier.", example = "1")
        Long categoryId,

        @Schema(description = "The category's name.", example = "Food")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "utensils", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#F97316", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "Total spent in that category this month.", example = "24.00")
        BigDecimal totalAmount) {
}
