package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One category's total for the month, as a slice of the report (UC-15).")
public record ReportCategoryResponse(

        @Schema(description = "The category's identifier. `6` is `Food`, a shared default category "
                + "`db/05_seed.sql` creates, so the id and the name in this example belong "
                + "together.", example = "6")
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

        @Schema(description = "Whether this category's records are income or spending (BR-05).",
                example = "EXPENSE")
        CategoryType type,

        @Schema(description = "Total recorded in that category that month, counting only records "
                + "that are not in the trash (BR-09).", example = "24.00")
        BigDecimal total,

        @Schema(description = "This category's own share of the block it appears in, as a percentage "
                + "to two decimal places. Shares are rounded independently and so need not total "
                + "exactly 100.", example = "12.70")
        BigDecimal percentage,

        @Schema(description = "How many live records make up the total.", example = "3")
        Long transactionCount) {
}
