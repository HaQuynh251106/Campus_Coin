package com.campuscoin.admin.dto;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the system-wide category ranking (UC-23).
 *
 * <p>{@code scope} is published because the ranking is not readable without it. Default categories
 * and students' own categories share one table, so "Entertainment, 412 transactions" means something
 * different depending on whether it is the shared default or one student's private category that
 * happens to be named the same. {@code DEFAULT} or {@code PERSONAL} is the view's own distinction,
 * derived from whether {@code user_id} is null.
 *
 * <p>{@code distinctUsers} is what separates a category many students use from one student using it
 * heavily: 412 transactions from 3 accounts is a different fact from 412 from 90, and
 * {@code txnCount} alone cannot tell them apart. It is counted over non-deleted transactions only,
 * so a category whose records were all moved to the trash reports zero (BR-09).
 *
 * <p>{@code totalAmount} is a sum across students, like every other money figure this module
 * publishes - see {@link AdminUsageStatsResponse} and {@code docs/OVERNIGHT_BLOCKERS.md} OB-013.
 *
 * <p>A row with {@code txnCount} zero is a real answer, not a defect: the ranking covers every
 * category, including ones nobody has used yet, because the view is a {@code LEFT JOIN} from
 * {@code categories} for exactly that reason and an administrator reading a usage report needs to
 * see what is unused.
 */
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
