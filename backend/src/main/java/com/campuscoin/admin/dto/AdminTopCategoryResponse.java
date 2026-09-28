package com.campuscoin.admin.dto;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A category's system-wide usage (UC-23).")
public record AdminTopCategoryResponse(

        @Schema(description = "Identifier of the category.", example = "4")
        Long categoryId,

        @Schema(description = "Category name.", example = "Entertainment")
        String categoryName,

        @Schema(description = "Whether records filed here are income or expense.", example = "EXPENSE")
        CategoryType type,

        @Schema(description = "`DEFAULT` for a shared category, `PERSONAL` for one belonging to a "
                + "student.", example = "DEFAULT")
        String scope,

        @Schema(description = "Number of non-deleted transactions recorded in this category.",
                example = "412")
        Long txnCount,

        @Schema(description = "Total amount recorded in this category, across all students.",
                example = "5120.75")
        BigDecimal totalAmount,

        @Schema(description = "How many distinct students have recorded a transaction here.",
                example = "90")
        Long distinctUsers) {
}
