package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The highest-spending expense category this month (UC-12 B2). Absent when the "
        + "student has recorded no spending this month.")
public record DashboardTopCategoryResponse(

        @Schema(description = "The category's identifier. Only an EXPENSE category can appear here, "
                + "so `6` (`Food`, a shared default `db/05_seed.sql` creates) is the id that goes "
                + "with the name below.", example = "6")
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
